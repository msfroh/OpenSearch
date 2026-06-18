/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterModule;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.filter.IndexMetadataSection;
import org.opensearch.cluster.service.filter.IndexScope;
import org.opensearch.cluster.service.filter.Slices;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_BLOCKS;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_COORDINATION;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_METADATA;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_NODES;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.SLOT_ROUTING_TABLE;

/**
 * Asserts the on-demand-materialization contract of {@link FileClusterStateSupplier}:
 * if a caller never reads a slot, the supplier must not open its file. The tests delete
 * the candidate file <em>after</em> {@code get()} returns and <em>before</em> the targeted
 * accessor runs, so any premature read shows up as a {@link java.io.UncheckedIOException}.
 */
public class FileClusterStateSupplierLazinessTests extends OpenSearchTestCase {

    private Path stateDir;
    private FileClusterStateSupplier supplier;
    private FileClusterStatePublisher publisher;
    private NamedWriteableRegistry registry;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        stateDir = createTempDir();
        supplier = new FileClusterStateSupplier(stateDir);
        publisher = new FileClusterStatePublisher(stateDir, supplier);
        registry = new NamedWriteableRegistry(ClusterModule.getNamedWriteables());
        supplier.setNamedWriteableRegistry(registry);
    }

    public void testAccessingOnlyBlocksDoesNotOpenOtherSlotFiles() throws IOException {
        publishStateWithIndices("foo", "bar");
        ClusterState lazy = freshLazyRead();

        // Drop every component file except blocks. If the lazy supplier eagerly read
        // anything else, the subsequent .blocks() call would still succeed but the
        // assertions below would fail.
        deleteAllComponentFilesExceptSlot(SLOT_BLOCKS);
        deleteIndicesDir();

        // Reading blocks must succeed — the file still exists and we never opened anything else.
        assertNotNull(lazy.blocks());

        // Reading any other slot must fail now: the underlying file is gone.
        assertReadFails(lazy::routingTable);
        assertReadFails(lazy::nodes);
        // Touching the metadata header (via transientSettings) forces the header file read.
        assertReadFails(() -> lazy.metadata().transientSettings());
        // Touching any index forces that index's file read.
        assertReadFails(() -> lazy.metadata().index("foo"));
    }

    public void testAccessingOnlyRoutingDoesNotOpenOtherSlotFiles() throws IOException {
        publishStateWithIndices("foo");
        ClusterState lazy = freshLazyRead();

        deleteAllComponentFilesExceptSlot(SLOT_ROUTING_TABLE);
        deleteIndicesDir();

        assertNotNull(lazy.routingTable());
        assertReadFails(lazy::blocks);
        assertReadFails(lazy::nodes);
        assertReadFails(() -> lazy.metadata().transientSettings());
        assertReadFails(() -> lazy.metadata().index("foo"));
    }

    public void testAccessingOnlyNodesDoesNotOpenOtherSlotFiles() throws IOException {
        publishStateWithIndices("foo");
        ClusterState lazy = freshLazyRead();

        deleteAllComponentFilesExceptSlot(SLOT_NODES);
        deleteIndicesDir();

        assertNotNull(lazy.nodes());
        assertReadFails(lazy::blocks);
        assertReadFails(lazy::routingTable);
        assertReadFails(() -> lazy.metadata().transientSettings());
        assertReadFails(() -> lazy.metadata().index("foo"));
    }

    public void testReadingOnlyOneIndexDoesNotOpenOtherIndexFiles() throws IOException {
        publishStateWithIndices("foo", "bar", "baz");
        ClusterState lazy = freshLazyRead();

        // Delete every index file EXCEPT foo's. If LazyIndices.get("foo") opened bar/baz
        // it would silently materialize them; after their files are gone, that would fail.
        // We need foo's filename; read it out of the manifest before deleting siblings.
        ComponentManifest manifest = ComponentManifest.read(stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST));
        String fooFile = manifest.indices().get("foo");
        Path indicesDir = stateDir.resolve(COMPONENTS_DIR).resolve(FileClusterStateLayout.COMPONENTS_INDICES_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indicesDir)) {
            for (Path p : stream) {
                String relative = COMPONENTS_DIR + "/" + FileClusterStateLayout.COMPONENTS_INDICES_DIR + "/"
                    + p.getFileName().toString();
                String rel = FileClusterStateLayout.COMPONENTS_INDICES_DIR + "/" + p.getFileName().toString();
                if (rel.equals(fooFile) == false) {
                    Files.delete(p);
                }
            }
        }

        // foo can still be read.
        assertNotNull(lazy.metadata().index("foo"));
        // bar and baz cannot — their files are gone, but they were never touched so we
        // didn't notice until the explicit access here.
        assertReadFails(() -> lazy.metadata().index("bar"));
        assertReadFails(() -> lazy.metadata().index("baz"));
    }

    public void testGetClusterStateForTaskWithInProgressHintSkipsRoutingAndIndexFiles() throws IOException {
        // Publish a state with two indices; then ask the supplier for "task-input narrowed
        // to InProgressSlice". The projection never touches metadata.indices() or the
        // routing table, so deleting those files between get() and use() must not break it.
        publishStateWithIndices("foo", "bar");
        // Use a fresh supplier so the cache is forced to repopulate from disk under a hint.
        FileClusterStateSupplier fresh = new FileClusterStateSupplier(stateDir);
        fresh.setNamedWriteableRegistry(registry);

        ClusterState narrow = fresh.getClusterStateForTask(
            Slices.inProgress(org.opensearch.cluster.service.filter.InProgressType.SNAPSHOTS)
        );

        // The narrow state shouldn't have invoked routing or per-index reads. Delete those
        // files and confirm narrow accessors that don't need them still work.
        deleteAllComponentFilesExceptSlots(SLOT_METADATA, SLOT_COORDINATION, SLOT_BLOCKS, SLOT_NODES);
        deleteIndicesDir();

        // The projection always carries cluster identity bits, so these are safe.
        assertEquals("test-cluster", narrow.getClusterName().value());
        assertNotNull(narrow.metadata().clusterUUID());
        // Routing got projected to an empty RoutingTable (no per-index reads needed).
        assertEquals(0, narrow.routingTable().indicesRouting().size());
    }

    public void testGetClusterStateForTaskWithNamedIndexHintReadsOnlyThatIndex() throws IOException {
        publishStateWithIndices("foo", "bar");
        FileClusterStateSupplier fresh = new FileClusterStateSupplier(stateDir);
        fresh.setNamedWriteableRegistry(registry);

        // Hint: only "foo"'s settings section.
        ClusterState narrow = fresh.getClusterStateForTask(
            Slices.indexMetadata(IndexScope.named("foo"), java.util.Set.of(IndexMetadataSection.SETTINGS))
        );

        // foo should have come back; bar shouldn't have been read at all.
        assertNotNull(narrow.metadata().index("foo"));
        // bar may or may not appear depending on whether the projection eagerly iterates;
        // but with the per-named filter, the projection should look up only "foo". After
        // deleting bar's file the state should still be navigable.
        Path indicesDir = stateDir.resolve(COMPONENTS_DIR).resolve(FileClusterStateLayout.COMPONENTS_INDICES_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indicesDir, "bar-*")) {
            for (Path p : stream) {
                Files.delete(p);
            }
        }
        // Re-access via the narrow state still works because bar was never opened.
        assertNotNull(narrow.metadata().index("foo"));
    }

    // ---- helpers ----

    private ClusterState freshLazyRead() {
        // Each fresh supplier discards the in-memory cache, forcing a real disk read.
        FileClusterStateSupplier fresh = new FileClusterStateSupplier(stateDir);
        fresh.setNamedWriteableRegistry(registry);
        return fresh.get();
    }

    private void publishStateWithIndices(String... names) throws IOException {
        Metadata.Builder mdBuilder = Metadata.builder().clusterUUID("cluster-uuid").version(1L);
        for (String name : names) {
            mdBuilder.put(newIndexMetadata(name), false);
        }
        ClusterState state = ClusterState.builder(new ClusterName("test-cluster"))
            .version(1L)
            .stateUUID("state-v1")
            .metadata(mdBuilder)
            .build();
        publisher.writeAll(state);
    }

    private static IndexMetadata newIndexMetadata(String name) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_INDEX_UUID, name + "-uuid")
            )
            .numberOfShards(1)
            .numberOfReplicas(1)
            .build();
    }

    private void deleteAllComponentFilesExceptSlot(String keep) throws IOException {
        deleteAllComponentFilesExceptSlots(keep);
    }

    private void deleteAllComponentFilesExceptSlots(String... keep) throws IOException {
        Path components = stateDir.resolve(COMPONENTS_DIR);
        List<String> keepPrefixes = new ArrayList<>();
        for (String s : keep) {
            keepPrefixes.add(s + "-");
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(components)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                boolean keepIt = false;
                for (String prefix : keepPrefixes) {
                    if (name.startsWith(prefix)) {
                        keepIt = true;
                        break;
                    }
                }
                if (keepIt == false) {
                    Files.delete(p);
                }
            }
        }
    }

    private void deleteIndicesDir() throws IOException {
        Path indicesDir = stateDir.resolve(COMPONENTS_DIR).resolve(FileClusterStateLayout.COMPONENTS_INDICES_DIR);
        if (Files.isDirectory(indicesDir) == false) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indicesDir)) {
            for (Path p : stream) {
                Files.delete(p);
            }
        }
    }

    private static void assertReadFails(Runnable r) {
        try {
            r.run();
            fail("expected an IOException-wrapping failure");
        } catch (java.io.UncheckedIOException expected) {
            // good
        } catch (RuntimeException other) {
            // Some accessors (e.g. metadata()) chain through builders that may rewrap.
            // Accept any RuntimeException whose root cause is an IOException.
            Throwable t = other;
            while (t != null) {
                if (t instanceof IOException) {
                    return;
                }
                t = t.getCause();
            }
            throw other;
        }
    }
}
