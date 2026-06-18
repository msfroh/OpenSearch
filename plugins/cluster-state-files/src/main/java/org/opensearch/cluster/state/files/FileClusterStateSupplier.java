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
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.cluster.service.ClusterStateSupplier;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

/**
 * Returns the most recently published {@link ClusterState}.
 * <p>
 * On each call to {@link #get()} we stat the manifest file. If its modification time
 * matches the one we last observed, we return the cached state. Otherwise we parse the
 * manifest, follow it to the consolidated binary state file written by
 * {@link FileClusterStatePublisher}, and rebuild the {@code ClusterState} from disk.
 * <p>
 * Filter-aware reads via {@link #getClusterState(ClusterStateFilter)} fetch the full
 * cached state and then project it through {@link FileClusterStateProjection} so callers
 * only see the slices they asked for. The on-disk format is still the full state — the
 * narrowing is in-memory.
 * <p>
 * The publisher also primes the cache directly after a successful write (see
 * {@link #updateCached(ClusterState, FileTime)}), so steady-state reads inside the
 * same JVM avoid hitting the filesystem at all.
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
     * local {@link DiscoveryNode} lazily for {@link ClusterState.Builder#fromBytes}.
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
        Path manifest = stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST);
        FileTime currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(manifest);
        } catch (NoSuchFileException nsfe) {
            // No state has been written yet (fresh node). Return whatever we have cached
            // — likely ClusterState.EMPTY_STATE.
            synchronized (mutex) {
                return cached;
            }
        } catch (IOException e) {
            logger.warn("failed to stat manifest at {}", manifest, e);
            synchronized (mutex) {
                return cached;
            }
        }

        synchronized (mutex) {
            if (lastSeenManifestMtime != null && lastSeenManifestMtime.equals(currentMtime)) {
                return cached;
            }
            NamedWriteableRegistry registry = this.namedWriteableRegistry;
            ClusterService cs = this.clusterService;
            if (registry == null) {
                // Plumbing not finished yet; fall back to whatever we have cached.
                return cached;
            }
            try {
                ClusterState fromDisk = readState(manifest, registry, cs);
                this.cached = fromDisk;
                this.lastSeenManifestMtime = currentMtime;
                return fromDisk;
            } catch (IOException e) {
                logger.warn("failed to read cluster state from {}", manifest, e);
                return cached;
            }
        }
    }

    private ClusterState readState(Path manifest, NamedWriteableRegistry registry, ClusterService cs) throws IOException {
        String stateFileName = readStateFileNameFromManifest(manifest);
        Path stateFile = stateDir.resolve(stateFileName);
        byte[] bytes = Files.readAllBytes(stateFile);
        DiscoveryNode localNode = cs == null ? null : safeLocalNode(cs);
        return ClusterState.Builder.fromBytes(bytes, localNode, registry);
    }

    private static DiscoveryNode safeLocalNode(ClusterService cs) {
        try {
            return cs.localNode();
        } catch (Exception e) {
            // localNode() throws AssertionError until the node has been built. Treat that
            // as "not available yet" so the first reads (before ClusterService.start)
            // still succeed.
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String readStateFileNameFromManifest(Path manifest) throws IOException {
        try (
            XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                LoggingDeprecationHandler.INSTANCE,
                Files.newInputStream(manifest)
            )
        ) {
            Map<String, Object> map = parser.map();
            Object stateFile = map.get(FileClusterStateLayout.MANIFEST_STATE_FILE_KEY);
            if (stateFile == null) {
                throw new IOException(
                    "manifest " + manifest + " is missing required key '" + FileClusterStateLayout.MANIFEST_STATE_FILE_KEY + "'"
                );
            }
            return stateFile.toString();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
