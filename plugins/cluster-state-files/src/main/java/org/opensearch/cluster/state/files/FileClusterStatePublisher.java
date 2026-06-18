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
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.TreeMap;

import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_INDICES_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_METADATA_CUSTOMS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_STATE_CUSTOMS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.CURRENT_MANIFEST;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.MANIFESTS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_BLOCKS;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_COORDINATION;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_METADATA;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_NODES;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_ROUTING_TABLE;

/**
 * Writes accepted cluster state updates as content-addressed per-component files.
 * <p>
 * Each publication walks the new state, dedupes each component against the
 * previously-published state by reference equality (no I/O, no hashing on a clean slice),
 * and otherwise serializes the component, hashes the bytes with SHA-256, and writes
 * {@code components/.../<slot-or-uuid-or-type>-<hash>.bin} if the file isn't already
 * present (content-addressed dedup across publications). A {@link ComponentManifest}
 * naming every component's file is then atomic-moved onto {@code current-manifest.json}.
 * <p>
 * Layout produced under {@code stateDir} — see {@link FileClusterStateLayout} for the full
 * description. Cross-publication GC of unreferenced component files is a follow-up.
 */
final class FileClusterStatePublisher implements ClusterStatePublisher {

    private static final Logger logger = LogManager.getLogger(FileClusterStatePublisher.class);

    private final Path stateDir;
    private final FileClusterStateSupplier supplier;
    private final ComponentGarbageCollector gc;

    /** Last successfully-published state, used for reference-equality short-circuits on next publish. */
    private ClusterState lastPublishedState;
    /** Manifest of the last successfully-published state, used to reuse filenames for unchanged components. */
    private ComponentManifest lastPublishedManifest;

    FileClusterStatePublisher(Path stateDir, FileClusterStateSupplier supplier) {
        this(stateDir, supplier, new ComponentGarbageCollector(ComponentGarbageCollector.DEFAULT_RETENTION));
    }

    /** Test-friendly constructor that takes an explicit GC (e.g. with a tighter retention window). */
    FileClusterStatePublisher(Path stateDir, FileClusterStateSupplier supplier, ComponentGarbageCollector gc) {
        this.stateDir = stateDir;
        this.supplier = supplier;
        this.gc = gc;
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

    /** Visible for testing: serializes {@code state} to the on-disk layout and returns the new manifest's mtime. */
    FileTime writeAll(ClusterState state) throws IOException {
        Files.createDirectories(stateDir);
        Path componentsDir = stateDir.resolve(COMPONENTS_DIR);
        Files.createDirectories(componentsDir);
        Files.createDirectories(componentsDir.resolve(COMPONENTS_INDICES_DIR));
        Files.createDirectories(componentsDir.resolve(COMPONENTS_STATE_CUSTOMS_DIR));
        Files.createDirectories(componentsDir.resolve(COMPONENTS_METADATA_CUSTOMS_DIR));
        Files.createDirectories(stateDir.resolve(MANIFESTS_DIR));

        Metadata metadata = state.metadata();
        Metadata priorMetadata = lastPublishedState == null ? null : lastPublishedState.metadata();

        Map<String, String> components = new TreeMap<>();
        Map<String, String> indices = new TreeMap<>();
        Map<String, String> stateCustoms = new TreeMap<>();
        Map<String, String> metadataCustoms = new TreeMap<>();

        // ---- Top-level slots ----

        components.put(
            SLOT_METADATA,
            writeOrReuse(
                componentsDir,
                SLOT_METADATA,
                priorMetadata != null && sameMetadataHeader(priorMetadata, metadata),
                slot -> ComponentCodec.writeMetadataHeader(metadata),
                slot -> FileClusterStateLayout.componentFileName(slot, "")
            )
        );

        components.put(
            SLOT_ROUTING_TABLE,
            writeOrReuse(
                componentsDir,
                SLOT_ROUTING_TABLE,
                lastPublishedState != null && lastPublishedState.routingTable() == state.routingTable(),
                slot -> ComponentCodec.writeRoutingTable(state.routingTable()),
                slot -> FileClusterStateLayout.componentFileName(slot, "")
            )
        );

        components.put(
            SLOT_BLOCKS,
            writeOrReuse(
                componentsDir,
                SLOT_BLOCKS,
                lastPublishedState != null && lastPublishedState.blocks() == state.blocks(),
                slot -> ComponentCodec.writeBlocks(state.blocks()),
                slot -> FileClusterStateLayout.componentFileName(slot, "")
            )
        );

        components.put(
            SLOT_NODES,
            writeOrReuse(
                componentsDir,
                SLOT_NODES,
                lastPublishedState != null && lastPublishedState.nodes() == state.nodes(),
                slot -> ComponentCodec.writeNodes(state.nodes()),
                slot -> FileClusterStateLayout.componentFileName(slot, "")
            )
        );

        components.put(
            SLOT_COORDINATION,
            writeOrReuse(
                componentsDir,
                SLOT_COORDINATION,
                priorMetadata != null && priorMetadata.coordinationMetadata() == metadata.coordinationMetadata(),
                slot -> ComponentCodec.writeCoordination(metadata.coordinationMetadata()),
                slot -> FileClusterStateLayout.componentFileName(slot, "")
            )
        );

        // ---- Per-index ----
        for (IndexMetadata idx : metadata.indices().values()) {
            String name = idx.getIndex().getName();
            String uuid = idx.getIndexUUID();
            IndexMetadata prior = priorMetadata == null ? null : priorMetadata.indices().get(name);
            boolean reuse = prior != null && prior == idx;
            String reusedName = reuse ? lastPublishedManifest.indices().get(name) : null;
            byte[] bytes = reuse ? null : ComponentCodec.writeIndex(idx);
            String fileName = writePerKey(
                componentsDir,
                COMPONENTS_INDICES_DIR,
                reusedName,
                bytes,
                sha -> FileClusterStateLayout.indexComponentFileName(uuid, sha)
            );
            indices.put(name, fileName);
        }

        // ---- State customs ----
        for (Map.Entry<String, ClusterState.Custom> e : state.customs().entrySet()) {
            String type = e.getKey();
            ClusterState.Custom custom = e.getValue();
            ClusterState.Custom prior = lastPublishedState == null ? null : lastPublishedState.customs().get(type);
            boolean reuse = prior != null && prior == custom;
            String reusedName = reuse ? lastPublishedManifest.stateCustoms().get(type) : null;
            byte[] bytes = reuse ? null : ComponentCodec.writeStateCustom(custom);
            String name = writePerKey(
                componentsDir,
                COMPONENTS_STATE_CUSTOMS_DIR,
                reusedName,
                bytes,
                sha -> FileClusterStateLayout.stateCustomFileName(type, sha)
            );
            stateCustoms.put(type, name);
        }

        // ---- Metadata customs ----
        for (Map.Entry<String, Metadata.Custom> e : metadata.customs().entrySet()) {
            String type = e.getKey();
            Metadata.Custom custom = e.getValue();
            Metadata.Custom prior = priorMetadata == null ? null : priorMetadata.customs().get(type);
            boolean reuse = prior != null && prior == custom;
            String reusedName = reuse ? lastPublishedManifest.metadataCustoms().get(type) : null;
            byte[] bytes = reuse ? null : ComponentCodec.writeMetadataCustom(custom);
            String name = writePerKey(
                componentsDir,
                COMPONENTS_METADATA_CUSTOMS_DIR,
                reusedName,
                bytes,
                sha -> FileClusterStateLayout.metadataCustomFileName(type, sha)
            );
            metadataCustoms.put(type, name);
        }

        ComponentManifest manifest = new ComponentManifest(
            state.version(),
            state.stateUUID(),
            metadata.clusterUUID(),
            metadata.clusterUUIDCommitted(),
            metadata.version(),
            state.getClusterName().value(),
            components,
            indices,
            stateCustoms,
            metadataCustoms
        );

        // Atomically expose the new manifest: write versioned manifest, then atomic-move onto current-manifest.json.
        Path versionedManifest = stateDir.resolve(FileClusterStateLayout.manifestFileName(state.stateUUID()));
        writeAtomic(versionedManifest, manifest.toJsonBytes());
        Path currentManifest = stateDir.resolve(CURRENT_MANIFEST);
        writeAtomic(currentManifest, manifest.toJsonBytes());

        this.lastPublishedState = state;
        this.lastPublishedManifest = manifest;

        // Reap stale component files and expired versioned manifests. Best-effort: the GC
        // logs and swallows its own errors so a sweep miss never trips a publish failure.
        gc.sweep(stateDir);

        return Files.getLastModifiedTime(currentManifest);
    }

    /**
     * Slot writer for top-level components. {@code filenameForSha} is invoked only when the component is materialized.
     */
    private String writeOrReuse(
        Path componentsDir,
        String slot,
        boolean canReuse,
        ComponentBytesProducer producer,
        java.util.function.Function<String, String> filenameForSha
    ) throws IOException {
        if (canReuse) {
            String prior = lastPublishedManifest.components().get(slot);
            if (prior != null) {
                return prior;
            }
        }
        byte[] bytes = producer.produce(slot);
        String sha = FileClusterStateLayout.sha256(bytes);
        String filename = FileClusterStateLayout.componentFileName(slot, sha);
        Path file = componentsDir.resolve(filename);
        if (Files.exists(file) == false) {
            writeAtomic(file, bytes);
        }
        return filename;
    }

    /**
     * Per-key writer for indices and customs. Either reuses {@code reusedName} (skipping I/O entirely)
     * or hashes {@code bytes} and writes {@code subdir/<filenameForSha(sha)>} if absent.
     */
    private String writePerKey(
        Path componentsDir,
        String subdir,
        String reusedName,
        byte[] bytes,
        java.util.function.Function<String, String> filenameForSha
    ) throws IOException {
        if (reusedName != null) {
            return reusedName;
        }
        String sha = FileClusterStateLayout.sha256(bytes);
        String filename = filenameForSha.apply(sha);
        Path file = componentsDir.resolve(filename);
        if (Files.exists(file) == false) {
            Files.createDirectories(file.getParent());
            writeAtomic(file, bytes);
        }
        return filename;
    }

    @FunctionalInterface
    private interface ComponentBytesProducer {
        byte[] produce(String slot) throws IOException;
    }

    /** True when every field in the metadata header (see {@link ComponentCodec.MetadataHeader}) matches by equals. */
    private static boolean sameMetadataHeader(Metadata a, Metadata b) {
        return a.version() == b.version()
            && a.clusterUUID().equals(b.clusterUUID())
            && a.clusterUUIDCommitted() == b.clusterUUIDCommitted()
            && a.transientSettings().equals(b.transientSettings())
            && a.persistentSettings().equals(b.persistentSettings())
            && a.hashesOfConsistentSettings().equals(b.hashesOfConsistentSettings())
            && a.templates().equals(b.templates());
    }

    private static void writeAtomic(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
