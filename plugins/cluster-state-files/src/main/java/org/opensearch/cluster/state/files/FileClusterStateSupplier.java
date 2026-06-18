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
import org.opensearch.cluster.ClusterStateLazyComposer;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.cluster.service.ClusterStateSupplier;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
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
 * manifest and produce a <em>lazy</em> {@link ClusterState} whose top-level slot accessors
 * read their component files on first call (cached thereafter via {@code CachedSupplier});
 * see {@link ClusterStateLazyComposer}. Tasks that consult only a subset of slices avoid
 * the I/O for everything else.
 * <p>
 * Filter-aware reads via {@link #getClusterState(ClusterStateFilter)} go through
 * {@link FileClusterStateProjection#project}; because the cached state is lazy, projection
 * naturally avoids materializing components outside the filter. The same call shape backs
 * {@link #getClusterStateForTask(ClusterStateFilter)} — narrowed task input is now safe
 * because {@code ClusterStateMerger} preserves slices outside the filter when stitching
 * the executor's output back onto the prior state.
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
        return FileClusterStateProjection.project(readOrCached(), filter);
    }

    /**
     * Returns the cached lazy state narrowed to {@code hint}. With the lazy ClusterState
     * in place and {@code ClusterStateMerger} stitching un-touched slices back through
     * on publication, it is safe (and beneficial) to hand the executor a narrow state:
     * unread component files never open.
     */
    @Override
    public ClusterState getClusterStateForTask(ClusterStateFilter hint) {
        return FileClusterStateProjection.project(readOrCached(), hint);
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
                ClusterState fromDisk = composeLazyState(manifestFile, registry);
                this.cached = fromDisk;
                this.lastSeenManifestMtime = currentMtime;
                return fromDisk;
            } catch (IOException e) {
                logger.warn("failed to read cluster state from {}", manifestFile, e);
                return cached;
            }
        }
    }

    /**
     * Reads the manifest (cheap — a small JSON parse) and returns a lazy {@link ClusterState}.
     * Each top-level slot's supplier captures the manifest plus the components directory and
     * opens its file on first access. The local node is resolved lazily inside the nodes
     * supplier so a not-yet-built {@link ClusterService} doesn't block the read.
     */
    private ClusterState composeLazyState(Path manifestFile, NamedWriteableRegistry registry) throws IOException {
        ComponentManifest manifest = ComponentManifest.read(manifestFile);
        Path componentsDir = stateDir.resolve(COMPONENTS_DIR);

        return ClusterStateLazyComposer.compose(
            new ClusterName(manifest.clusterName()),
            manifest.clusterStateVersion(),
            manifest.stateUuid(),
            () -> buildMetadata(componentsDir, manifest, registry),
            () -> readRouting(componentsDir, manifest),
            () -> readNodes(componentsDir, manifest),
            () -> readBlocks(componentsDir, manifest),
            () -> readStateCustoms(componentsDir, manifest, registry)
        );
    }

    private static Metadata buildMetadata(Path componentsDir, ComponentManifest manifest, NamedWriteableRegistry registry) {
        try {
            Metadata.Builder mdBuilder = Metadata.builder().clusterUUID(manifest.clusterUuid());

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

            String coordName = manifest.components().get(SLOT_COORDINATION);
            if (coordName != null) {
                CoordinationMetadata coord = ComponentCodec.readCoordination(componentsDir.resolve(coordName));
                mdBuilder.coordinationMetadata(coord);
            }

            for (Map.Entry<String, String> e : manifest.indices().entrySet()) {
                mdBuilder.put(ComponentCodec.readIndex(componentsDir.resolve(e.getValue())), false);
            }

            for (Map.Entry<String, String> e : manifest.metadataCustoms().entrySet()) {
                mdBuilder.putCustom(e.getKey(), ComponentCodec.readMetadataCustom(componentsDir.resolve(e.getValue()), registry));
            }

            return mdBuilder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to materialize Metadata from " + componentsDir, e);
        }
    }

    private static RoutingTable readRouting(Path componentsDir, ComponentManifest manifest) {
        String name = manifest.components().get(SLOT_ROUTING_TABLE);
        if (name == null) {
            return RoutingTable.EMPTY_ROUTING_TABLE;
        }
        try {
            return ComponentCodec.readRoutingTable(componentsDir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read routing table " + name, e);
        }
    }

    private DiscoveryNodes readNodes(Path componentsDir, ComponentManifest manifest) {
        String name = manifest.components().get(SLOT_NODES);
        if (name == null) {
            return DiscoveryNodes.EMPTY_NODES;
        }
        try {
            return ComponentCodec.readNodes(componentsDir.resolve(name), safeLocalNode(clusterService));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read nodes " + name, e);
        }
    }

    private static ClusterBlocks readBlocks(Path componentsDir, ComponentManifest manifest) {
        String name = manifest.components().get(SLOT_BLOCKS);
        if (name == null) {
            return ClusterBlocks.EMPTY_CLUSTER_BLOCK;
        }
        try {
            return ComponentCodec.readBlocks(componentsDir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read blocks " + name, e);
        }
    }

    private static Map<String, ClusterState.Custom> readStateCustoms(
        Path componentsDir,
        ComponentManifest manifest,
        NamedWriteableRegistry registry
    ) {
        if (manifest.stateCustoms().isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, ClusterState.Custom> out = new HashMap<>(manifest.stateCustoms().size());
            for (Map.Entry<String, String> e : manifest.stateCustoms().entrySet()) {
                out.put(e.getKey(), ComponentCodec.readStateCustom(componentsDir.resolve(e.getValue()), registry));
            }
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read state customs", e);
        }
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
