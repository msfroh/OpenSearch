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
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.coordination.ClusterStatePublisher;
import org.opensearch.cluster.coordination.FailedToCommitClusterStateException;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingNode;
import org.opensearch.cluster.routing.RoutingNodes;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes accepted cluster state updates to a directory tree of files.
 * <p>
 * Layout produced under {@code stateDir}:
 * <ul>
 *   <li>{@code cluster-state.json} — top-level summary: cluster name, version, the list
 *       of index names (without per-index metadata), and the current manifest filename.</li>
 *   <li>{@code state-&lt;uuid&gt;.bin} — full binary {@link ClusterState} (produced by
 *       {@link ClusterState.Builder#toBytes}); read back by {@link FileClusterStateSupplier}.</li>
 *   <li>{@code indices/&lt;index-uuid&gt;.json} — per-index metadata, for downstream
 *       consumers (e.g. data nodes that want just one index).</li>
 *   <li>{@code routing/nodes/node-&lt;node-id&gt;-&lt;uuid&gt;.json} — per-node routing
 *       table, enumerating the shards assigned to that node.</li>
 *   <li>{@code routing/indices/index-&lt;index-name&gt;-&lt;uuid&gt;.json} — per-index
 *       routing table, enumerating each shard and its assignment.</li>
 *   <li>{@code manifest-&lt;uuid&gt;.json} — single atomically-written file listing all
 *       current state and routing files. Atomically renamed onto
 *       {@code current-manifest.json} via {@link StandardCopyOption#ATOMIC_MOVE}.</li>
 * </ul>
 *
 * <p>For the proof of concept the publisher synchronously writes the files, primes the
 * supplier's cache with the freshly-written state and manifest mtime, then signals
 * {@code onCommit} and a single {@code onNodeAck} for the local cluster manager node.
 * Cross-node propagation, fsync, and partial-state filtering are deliberately left for
 * a follow-up.</p>
 */
final class FileClusterStatePublisher implements ClusterStatePublisher {

    private static final Logger logger = LogManager.getLogger(FileClusterStatePublisher.class);

    private final Path stateDir;
    private final FileClusterStateSupplier supplier;

    FileClusterStatePublisher(Path stateDir, FileClusterStateSupplier supplier) {
        this.stateDir = stateDir;
        this.supplier = supplier;
    }

    @Override
    public void publish(ClusterChangedEvent event, ActionListener<Void> publishListener, AckListener ackListener) {
        long start = System.nanoTime();
        ClusterState newState = event.state();
        FileTime manifestMtime;
        try {
            manifestMtime = writeAll(newState);
        } catch (IOException e) {
            logger.warn("failed to persist cluster state to {}", stateDir, e);
            publishListener.onFailure(new FailedToCommitClusterStateException("file-based publish failed", e));
            return;
        }

        supplier.updateCached(newState, manifestMtime);

        TimeValue commitTime = TimeValue.timeValueNanos(System.nanoTime() - start);
        try {
            ackListener.onCommit(commitTime);
            DiscoveryNode clusterManager = newState.nodes().getClusterManagerNode();
            if (clusterManager != null) {
                ackListener.onNodeAck(clusterManager, null);
            }
        } catch (Exception ackFailure) {
            logger.warn("ack listener threw after a successful file publish", ackFailure);
        }
        publishListener.onResponse(null);
    }

    private FileTime writeAll(ClusterState state) throws IOException {
        Files.createDirectories(stateDir);
        Path indicesDir = stateDir.resolve(FileClusterStateLayout.INDICES_DIR);
        Path routingDir = stateDir.resolve(FileClusterStateLayout.ROUTING_DIR);
        Path nodeRoutingDir = routingDir.resolve(FileClusterStateLayout.NODE_ROUTING_DIR);
        Path indexRoutingDir = routingDir.resolve(FileClusterStateLayout.INDEX_ROUTING_DIR);
        Files.createDirectories(indicesDir);
        Files.createDirectories(nodeRoutingDir);
        Files.createDirectories(indexRoutingDir);

        // The consolidated binary state — the supplier reads this file back on the next get().
        String stateFileName = "state-" + UUID.randomUUID() + ".bin";
        Path stateFile = stateDir.resolve(stateFileName);
        byte[] stateBytes = ClusterState.Builder.toBytes(state);
        writeAtomic(stateFile, stateBytes);

        List<String> indexNames = new ArrayList<>();
        for (IndexMetadata indexMetadata : state.metadata().indices().values()) {
            indexNames.add(indexMetadata.getIndex().getName());
            Path indexFile = indicesDir.resolve(indexMetadata.getIndexUUID() + ".json");
            writeJson(indexFile, builder -> {
                builder.startObject();
                indexMetadata.toXContent(builder, ToXContent.EMPTY_PARAMS);
                builder.endObject();
            });
        }

        List<String> indexRoutingFiles = new ArrayList<>();
        for (IndexRoutingTable indexRouting : state.routingTable()) {
            String fileName = "index-" + indexRouting.getIndex().getName() + "-" + UUID.randomUUID() + ".json";
            indexRoutingFiles.add(fileName);
            Path indexRoutingFile = indexRoutingDir.resolve(fileName);
            writeJson(indexRoutingFile, builder -> {
                builder.startObject();
                builder.field("index", indexRouting.getIndex().getName());
                builder.field("index_uuid", indexRouting.getIndex().getUUID());
                builder.startArray("shards");
                indexRouting.shards().forEach((shardId, shardRoutingTable) -> {
                    try {
                        builder.startObject();
                        builder.field("shard_id", shardId);
                        builder.startArray("routings");
                        for (ShardRouting routing : shardRoutingTable) {
                            routing.toXContent(builder, ToXContent.EMPTY_PARAMS);
                        }
                        builder.endArray();
                        builder.endObject();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                builder.endArray();
                builder.endObject();
            });
        }

        List<String> nodeRoutingFiles = new ArrayList<>();
        RoutingNodes routingNodes = state.getRoutingNodes();
        for (RoutingNode node : routingNodes) {
            String fileName = "node-" + node.nodeId() + "-" + UUID.randomUUID() + ".json";
            nodeRoutingFiles.add(fileName);
            Path nodeFile = nodeRoutingDir.resolve(fileName);
            writeJson(nodeFile, builder -> {
                builder.startObject();
                builder.field("node_id", node.nodeId());
                if (node.node() != null) {
                    builder.field("node_name", node.node().getName());
                }
                builder.startArray("shards");
                for (ShardRouting routing : node) {
                    routing.toXContent(builder, ToXContent.EMPTY_PARAMS);
                }
                builder.endArray();
                builder.endObject();
            });
        }

        Path manifestFile = stateDir.resolve("manifest-" + UUID.randomUUID() + ".json");
        writeJson(manifestFile, builder -> {
            builder.startObject();
            builder.field("cluster_state_version", state.version());
            builder.field("state_uuid", state.stateUUID());
            builder.field(FileClusterStateLayout.MANIFEST_STATE_FILE_KEY, stateFileName);
            builder.startArray("index_metadata_files");
            for (IndexMetadata indexMetadata : state.metadata().indices().values()) {
                builder.value(FileClusterStateLayout.INDICES_DIR + "/" + indexMetadata.getIndexUUID() + ".json");
            }
            builder.endArray();
            builder.startArray("node_routing_files");
            for (String fn : nodeRoutingFiles) {
                builder.value(fn);
            }
            builder.endArray();
            builder.startArray("index_routing_files");
            for (String fn : indexRoutingFiles) {
                builder.value(fn);
            }
            builder.endArray();
            builder.endObject();
        });

        // Atomically expose the new manifest by renaming it over current-manifest.json.
        Path currentManifest = stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST);
        moveAtomic(manifestFile, currentManifest);

        // Top-level summary — references only the index names, not their metadata.
        Path rootFile = stateDir.resolve(FileClusterStateLayout.CLUSTER_STATE_FILE);
        writeJson(rootFile, builder -> {
            builder.startObject();
            builder.field("cluster_name", state.getClusterName().value());
            builder.field("cluster_state_version", state.version());
            builder.field("state_uuid", state.stateUUID());
            builder.startArray("indices");
            for (String name : indexNames) {
                builder.value(name);
            }
            builder.endArray();
            builder.field("manifest_file", currentManifest.getFileName().toString());
            builder.endObject();
        });

        // Best-effort cleanup of routing files and consolidated state blobs no longer
        // referenced by the latest manifest. Index-metadata files are keyed by index
        // UUID and overwritten in place; nothing to clean there.
        cleanupStaleFiles(nodeRoutingDir, nodeRoutingFiles);
        cleanupStaleFiles(indexRoutingDir, indexRoutingFiles);
        cleanupStaleStateFiles(stateDir, stateFileName);

        return Files.getLastModifiedTime(currentManifest);
    }

    private static void cleanupStaleFiles(Path dir, List<String> keep) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (keep.contains(file.getFileName().toString()) == false) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // Stale file cleanup is best-effort.
                    }
                }
            }
        } catch (IOException ignored) {
            // Listing failures are non-fatal for the publish itself.
        }
    }

    private static void cleanupStaleStateFiles(Path dir, String keep) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "state-*.bin")) {
            for (Path file : stream) {
                if (keep.equals(file.getFileName().toString()) == false) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @FunctionalInterface
    private interface JsonWriter {
        void write(XContentBuilder builder) throws IOException;
    }

    private static void writeJson(Path file, JsonWriter body) throws IOException {
        try (XContentBuilder builder = JsonXContent.contentBuilder()) {
            body.write(builder);
            byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
            writeAtomic(file, bytes);
        }
    }

    private static void writeAtomic(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        moveAtomic(tmp, file);
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
