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
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class FileClusterStatePublisherTests extends OpenSearchTestCase {

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

    public void testPublishWritesManifestAndAllTopLevelComponents() throws IOException {
        ClusterState state = stateWithIndices("foo", "bar");
        publisher.writeAll(state);

        // Manifest exists and parses; every top-level slot is present.
        ComponentManifest manifest = ComponentManifest.read(stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST));
        assertEquals(state.version(), manifest.clusterStateVersion());
        assertEquals(state.stateUUID(), manifest.stateUuid());
        assertEquals(state.metadata().clusterUUID(), manifest.clusterUuid());
        assertEquals(state.getClusterName().value(), manifest.clusterName());
        assertTrue(manifest.components().containsKey(FileClusterStateLayout.SLOT_METADATA));
        assertTrue(manifest.components().containsKey(FileClusterStateLayout.SLOT_ROUTING_TABLE));
        assertTrue(manifest.components().containsKey(FileClusterStateLayout.SLOT_BLOCKS));
        assertTrue(manifest.components().containsKey(FileClusterStateLayout.SLOT_NODES));
        assertTrue(manifest.components().containsKey(FileClusterStateLayout.SLOT_COORDINATION));

        // Per-index entries are present and reference real files.
        assertEquals(2, manifest.indices().size());
        Path componentsDir = stateDir.resolve(FileClusterStateLayout.COMPONENTS_DIR);
        for (String relPath : manifest.indices().values()) {
            assertTrue("expected per-index file " + relPath, Files.exists(componentsDir.resolve(relPath)));
        }
        // The versioned manifest also exists.
        assertTrue(Files.exists(stateDir.resolve(FileClusterStateLayout.manifestFileName(state.stateUUID()))));
    }

    public void testPublishingIdenticalStateTwiceWritesNoNewFiles() throws IOException {
        ClusterState state = stateWithIndices("foo", "bar");
        publisher.writeAll(state);
        Set<Path> filesAfterFirst = listAllComponentFiles();
        ComponentManifest manifestAfterFirst = ComponentManifest.read(
            stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST)
        );

        publisher.writeAll(state);
        Set<Path> filesAfterSecond = listAllComponentFiles();
        ComponentManifest manifestAfterSecond = ComponentManifest.read(
            stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST)
        );

        // Reference-equality dedup → no new component files written.
        assertEquals(filesAfterFirst, filesAfterSecond);
        // And the manifest names the same files.
        assertEquals(manifestAfterFirst.components(), manifestAfterSecond.components());
        assertEquals(manifestAfterFirst.indices(), manifestAfterSecond.indices());
    }

    public void testReplacingOneIndexWritesOnlyThatIndexFile() throws IOException {
        ClusterState first = stateWithIndices("foo", "bar");
        publisher.writeAll(first);
        Set<Path> filesAfterFirst = listAllComponentFiles();
        ComponentManifest firstManifest = ComponentManifest.read(stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST));

        // Replace just "foo" with a different IndexMetadata; "bar" stays reference-equal.
        IndexMetadata fooReplacement = newIndexMetadata("foo", 3);
        Metadata.Builder mdBuilder = Metadata.builder(first.metadata())
            .clusterUUID(first.metadata().clusterUUID())
            .put(fooReplacement, true);
        ClusterState second = ClusterState.builder(first)
            .version(first.version() + 1)
            .stateUUID("state-v2")
            .metadata(mdBuilder)
            .build();
        publisher.writeAll(second);
        Set<Path> filesAfterSecond = listAllComponentFiles();
        ComponentManifest secondManifest = ComponentManifest.read(stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST));

        // Exactly one component file added — foo's new content. The metadata header
        // didn't change (its version is the Metadata version, which we haven't bumped;
        // put(idx, incrementVersion=true) bumps the per-index version, not Metadata's).
        Set<Path> added = new HashSet<>(filesAfterSecond);
        added.removeAll(filesAfterFirst);
        assertEquals("expected exactly one new file: the replaced index. Added: " + added, 1, added.size());
        Path onlyAdded = added.iterator().next();
        assertTrue(
            "the new file should be under indices/. Was: " + onlyAdded,
            onlyAdded.getParent().getFileName().toString().equals(FileClusterStateLayout.COMPONENTS_INDICES_DIR)
        );
        assertTrue(
            "the new file should be the foo index. Was: " + onlyAdded.getFileName(),
            onlyAdded.getFileName().toString().startsWith("foo-uuid-")
        );

        // bar's filename is reused, foo's is not.
        String fooUuid = second.metadata().index("foo").getIndexUUID();
        String barUuid = first.metadata().index("bar").getIndexUUID();
        assertNotEquals(
            "foo's filename should have changed",
            firstManifest.indices().get(fooUuid),
            secondManifest.indices().get(fooUuid)
        );
        assertEquals(
            "bar's filename should be reused",
            firstManifest.indices().get(barUuid),
            secondManifest.indices().get(barUuid)
        );

        // Routing/blocks/nodes/coordination filenames all reused (reference-equal).
        for (String slot : new String[] {
            FileClusterStateLayout.SLOT_ROUTING_TABLE,
            FileClusterStateLayout.SLOT_BLOCKS,
            FileClusterStateLayout.SLOT_NODES,
            FileClusterStateLayout.SLOT_COORDINATION }) {
            assertEquals(slot + " should reuse prior filename", firstManifest.components().get(slot), secondManifest.components().get(slot));
        }
    }

    public void testRoundTripThroughSupplierReproducesState() throws IOException {
        ClusterState state = stateWithIndices("foo", "bar");
        publisher.writeAll(state);

        // Reset the supplier's cache so it goes back to disk for the read.
        FileClusterStateSupplier freshSupplier = new FileClusterStateSupplier(stateDir);
        freshSupplier.setNamedWriteableRegistry(registry);
        ClusterState fromDisk = freshSupplier.get();

        assertEquals(state.version(), fromDisk.version());
        assertEquals(state.stateUUID(), fromDisk.stateUUID());
        assertEquals(state.getClusterName(), fromDisk.getClusterName());
        assertEquals(state.metadata().clusterUUID(), fromDisk.metadata().clusterUUID());
        // Index metadata fidelity: both indices come back with the same UUIDs.
        assertEquals(
            state.metadata().index("foo").getIndexUUID(),
            fromDisk.metadata().index("foo").getIndexUUID()
        );
        assertEquals(
            state.metadata().index("bar").getIndexUUID(),
            fromDisk.metadata().index("bar").getIndexUUID()
        );
    }

    public void testContentAddressDeduplicatesAcrossPublisherInstances() throws IOException {
        // A fresh publisher with no in-memory priorState can still skip writes because
        // the on-disk filename is the content hash — if a file with that name already
        // exists, the second publisher just references it. This is what allows two
        // independent processes to share the same state directory without duplication.
        ClusterState state = stateWithIndices("foo");
        publisher.writeAll(state);
        Set<Path> filesAfterFirst = listAllComponentFiles();

        FileClusterStatePublisher fresh = new FileClusterStatePublisher(stateDir, supplier);
        fresh.writeAll(state);
        Set<Path> filesAfterSecond = listAllComponentFiles();

        assertEquals(filesAfterFirst, filesAfterSecond);
    }

    private Set<Path> listAllComponentFiles() throws IOException {
        Set<Path> out = new HashSet<>();
        collect(stateDir.resolve(FileClusterStateLayout.COMPONENTS_DIR), out);
        return out;
    }

    private static void collect(Path dir, Set<Path> out) throws IOException {
        if (Files.isDirectory(dir) == false) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    collect(p, out);
                } else if (p.getFileName().toString().endsWith(".bin")) {
                    out.add(p);
                }
            }
        }
    }

    private ClusterState stateWithIndices(String... names) {
        Metadata.Builder mdBuilder = Metadata.builder().clusterUUID("cluster-uuid").version(1L);
        for (String name : names) {
            mdBuilder.put(newIndexMetadata(name, 1), false);
        }
        return ClusterState.builder(new ClusterName("test-cluster"))
            .version(1L)
            .stateUUID("state-v1")
            .metadata(mdBuilder)
            .build();
    }

    private static IndexMetadata newIndexMetadata(String name, int numShards) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_INDEX_UUID, name + "-uuid")
            )
            .numberOfShards(numShards)
            .numberOfReplicas(1)
            .build();
    }
}
