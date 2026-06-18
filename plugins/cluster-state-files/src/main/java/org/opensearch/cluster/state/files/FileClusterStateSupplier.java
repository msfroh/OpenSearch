/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.cluster.service.ClusterStateSupplier;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.CURRENT_MANIFEST;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_BLOCKS;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_COORDINATION;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_METADATA;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_NODES;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_ROUTING_TABLE;

/**
 * Returns the most recently published {@link ClusterState}.
 * <p>
 * On each call to {@link #get()} we stat {@code current-manifest.json}. If its modification
 * time matches the one we last observed, we return the cached state. Otherwise we parse the
 * manifest and reassemble a {@link ClusterState} by reading each component file it
 * references (see {@link ComponentManifest} and {@link FileClusterStateLayout}).
 * <p>
 * Filter-aware reads via {@link #getClusterState(ClusterStateFilter)} fetch the full cached
 * state and project it through {@link FileClusterStateProjection}. The reassembly itself is
 * eager in this revision; per-component lazy loading lands in a follow-up.
 * <p>
 * The publisher also primes the cache directly after a successful write (see
 * {@link #updateCached(ClusterState, FileTime)}), so steady-state reads inside the same JVM
 * avoid hitting the filesystem.
 */
public final class FileClusterStateSupplier implements ClusterStateSupplier {

    private static final Logger logger = LogManager.getLogger(FileClusterStateSupplier.class);

    private final Path stateDir;
    private final Object mutex = new Object();
    private ClusterState cached = ClusterState.EMPTY_STATE;
    private FileTime lastSeenManifestMtime;
    private volatile NamedWriteableRegistry namedWriteableRegistry;
    private volatile ClusterService clusterService;

    FileClusterStateSupplier(Path stateDir) {
        this.stateDir = stateDir;
    }

    /**
     * Called by the plugin's {@code createComponents} once the node-level
     * {@link NamedWriteableRegistry} is available. Required before {@link #get()} can
     * reconstruct a non-empty {@link ClusterState} from disk.
     */
    void setNamedWriteableRegistry(NamedWriteableRegistry registry) {
        this.namedWriteableRegistry = registry;
    }

    /**
     * Called by the plugin's {@code createComponents}; lets the supplier look up the
     * local {@link DiscoveryNode} lazily once the node is available.
     */
    void setClusterService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    void updateCached(ClusterState newState, FileTime manifestMtime) {
        synchronized (mutex) {
            this.cached = newState;
            this.lastSeenManifestMtime = manifestMtime;
        }
    }

    @Override
    public ClusterState getClusterState(ClusterStateFilter filter) {
        ClusterState fullState = readOrCached();
        return FileClusterStateProjection.project(fullState, filter);
    }

    /**
     * Tasks construct their result via {@code ClusterState.builder(currentState)} which
     * copies the input wholesale — narrowing the returned state would silently delete
     * everything outside the filter on publication. We log the declared hint at trace
     * level (useful for understanding which slices a batch of tasks reads) and return
     * the full cached state, which is always a valid superset of the requested slices.
     */
    @Override
    public ClusterState getClusterStateForTask(ClusterStateFilter hint) {
        if (logger.isTraceEnabled() && hint.isFullState() == false) {
            logger.trace("task batch declared filter hint: {}", hint);
        }
        return readOrCached();
    }

    private ClusterState readOrCached() {
        Path manifestFile = stateDir.resolve(CURRENT_MANIFEST);
        FileTime currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(manifestFile);
        } catch (NoSuchFileException nsfe) {
            // No state has been written yet (fresh node). Return whatever we have cached
            // — likely ClusterState.EMPTY_STATE.
            synchronized (mutex) {
                return cached;
            }
        } catch (IOException e) {
            logger.warn("failed to stat manifest at {}", manifestFile, e);
            synchronized (mutex) {
                return cached;
            }
        }

        synchronized (mutex) {
            if (lastSeenManifestMtime != null && lastSeenManifestMtime.equals(currentMtime)) {
                return cached;
            }
            NamedWriteableRegistry registry = this.namedWriteableRegistry;
            if (registry == null) {
                // Plumbing not finished yet; fall back to whatever we have cached.
                return cached;
            }
            try {
                ClusterState fromDisk = readState(manifestFile, registry);
                this.cached = fromDisk;
                this.lastSeenManifestMtime = currentMtime;
                return fromDisk;
            } catch (IOException e) {
                logger.warn("failed to read cluster state from {}", manifestFile, e);
                return cached;
            }
        }
    }

    private ClusterState readState(Path manifestFile, NamedWriteableRegistry registry) throws IOException {
        ComponentManifest manifest = ComponentManifest.read(manifestFile);
        Path componentsDir = stateDir.resolve(COMPONENTS_DIR);
        DiscoveryNode localNode = safeLocalNode(clusterService);

        ClusterState.Builder builder = ClusterState.builder(new ClusterName(manifest.clusterName()))
            .version(manifest.clusterStateVersion())
            .stateUUID(manifest.stateUuid());

        Metadata.Builder mdBuilder = Metadata.builder().clusterUUID(manifest.clusterUuid());

        // Metadata header — version / settings / templates / hashes / clusterUUIDCommitted.
        ComponentCodec.MetadataHeader header = ComponentCodec.readMetadataHeader(
            componentsDir.resolve(requiredComponent(manifest, SLOT_METADATA))
        );
        mdBuilder.version(header.version())
            .clusterUUID(header.clusterUUID())
            .clusterUUIDCommitted(header.clusterUUIDCommitted())
            .transientSettings(header.transientSettings())
            .persistentSettings(header.persistentSettings())
            .hashesOfConsistentSettings(header.hashesOfConsistentSettings())
            .templates(header.templates());

        // Coordination metadata.
        String coordName = manifest.components().get(SLOT_COORDINATION);
        if (coordName != null) {
            CoordinationMetadata coord = ComponentCodec.readCoordination(componentsDir.resolve(coordName));
            mdBuilder.coordinationMetadata(coord);
        }

        // Per-index.
        for (Map.Entry<String, String> e : manifest.indices().entrySet()) {
            IndexMetadata idx = ComponentCodec.readIndex(componentsDir.resolve(e.getValue()));
            mdBuilder.put(idx, false);
        }

        // Metadata customs.
        for (Map.Entry<String, String> e : manifest.metadataCustoms().entrySet()) {
            Metadata.Custom c = ComponentCodec.readMetadataCustom(componentsDir.resolve(e.getValue()), registry);
            mdBuilder.putCustom(e.getKey(), c);
        }

        builder.metadata(mdBuilder.build());

        // Routing table.
        String rtName = manifest.components().get(SLOT_ROUTING_TABLE);
        if (rtName != null) {
            RoutingTable rt = ComponentCodec.readRoutingTable(componentsDir.resolve(rtName));
            builder.routingTable(rt);
        }

        // Blocks.
        String blocksName = manifest.components().get(SLOT_BLOCKS);
        if (blocksName != null) {
            ClusterBlocks blocks = ComponentCodec.readBlocks(componentsDir.resolve(blocksName));
            builder.blocks(blocks);
        }

        // Nodes.
        String nodesName = manifest.components().get(SLOT_NODES);
        if (nodesName != null) {
            DiscoveryNodes nodes = ComponentCodec.readNodes(componentsDir.resolve(nodesName), localNode);
            builder.nodes(nodes);
        }

        // State customs.
        for (Map.Entry<String, String> e : manifest.stateCustoms().entrySet()) {
            ClusterState.Custom c = ComponentCodec.readStateCustom(componentsDir.resolve(e.getValue()), registry);
            builder.putCustom(e.getKey(), c);
        }

        return builder.build();
    }

    private static String requiredComponent(ComponentManifest manifest, String slot) throws IOException {
        String name = manifest.components().get(slot);
        if (name == null) {
            throw new IOException("manifest is missing required component slot '" + slot + "'");
        }
        return name;
    }

    private static DiscoveryNode safeLocalNode(ClusterService cs) {
        if (cs == null) {
            return null;
        }
        try {
            return cs.localNode();
        } catch (Exception e) {
            // localNode() throws AssertionError until the node has been built. Treat that
            // as "not available yet" so the first reads (before ClusterService.start)
            // still succeed.
            return null;
        }
    }
}
