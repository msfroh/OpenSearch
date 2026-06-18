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

public class ComponentGarbageCollectorTests extends OpenSearchTestCase {

    private Path stateDir;
    private NamedWriteableRegistry registry;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        stateDir = createTempDir();
        registry = new NamedWriteableRegistry(ClusterModule.getNamedWriteables());
    }

    public void testKeepsOnlyRetainedManifestsAndDropsTheirUniqueFiles() throws IOException {
        // Three publishes, each mutating a different index → each generates a new
        // hashed component file for that index. With retention=2 the oldest manifest
        // and its UNIQUE files should be reaped.
        ComponentGarbageCollector gc = new ComponentGarbageCollector(2);
        FileClusterStateSupplier supplier = new FileClusterStateSupplier(stateDir);
        supplier.setNamedWriteableRegistry(registry);
        FileClusterStatePublisher publisher = new FileClusterStatePublisher(stateDir, supplier, gc);

        ClusterState s1 = buildState("v1", indexWithMarker("foo", "a"), indexWithMarker("bar", "common"));
        publisher.writeAll(s1);
        String foo_a = currentManifest().indices().get("foo");
        String bar_common = currentManifest().indices().get("bar");

        ClusterState s2 = buildState("v2", indexWithMarker("foo", "b"), indexWithMarker("bar", "common"));
        publisher.writeAll(s2);
        String foo_b = currentManifest().indices().get("foo");

        ClusterState s3 = buildState("v3", indexWithMarker("foo", "c"), indexWithMarker("bar", "common"));
        publisher.writeAll(s3);
        String foo_c = currentManifest().indices().get("foo");

        // After the third publish only s2 and s3 manifests should remain.
        assertEquals(2, listManifests().size());

        Set<String> remainingFiles = listComponentFiles();
        // foo-a was unique to s1 and should be reaped.
        assertFalse("foo's a-marker file should be gone, was " + foo_a, remainingFiles.contains(foo_a));
        // foo-b and foo-c are referenced by retained manifests — must remain.
        assertTrue("foo-b should remain (referenced by retained s2 manifest), was " + foo_b, remainingFiles.contains(foo_b));
        assertTrue("foo-c should remain (referenced by current manifest), was " + foo_c, remainingFiles.contains(foo_c));
        // bar's file is the same across all three publishes (content-addressed dedup) so it
        // is referenced by every retained manifest — must remain.
        assertTrue("bar-common should remain", remainingFiles.contains(bar_common));
    }

    public void testKeepsAllManifestsWhenWithinRetention() throws IOException {
        ComponentGarbageCollector gc = new ComponentGarbageCollector(10);
        FileClusterStateSupplier supplier = new FileClusterStateSupplier(stateDir);
        supplier.setNamedWriteableRegistry(registry);
        FileClusterStatePublisher publisher = new FileClusterStatePublisher(stateDir, supplier, gc);

        for (int i = 0; i < 5; i++) {
            publisher.writeAll(buildState("v" + i, indexWithMarker("foo", "m" + i)));
        }
        assertEquals(5, listManifests().size());
    }

    public void testRetentionOfOneIsLegalAndOnlyKeepsTheLatest() throws IOException {
        ComponentGarbageCollector gc = new ComponentGarbageCollector(1);
        FileClusterStateSupplier supplier = new FileClusterStateSupplier(stateDir);
        supplier.setNamedWriteableRegistry(registry);
        FileClusterStatePublisher publisher = new FileClusterStatePublisher(stateDir, supplier, gc);

        publisher.writeAll(buildState("v1", indexWithMarker("foo", "a")));
        publisher.writeAll(buildState("v2", indexWithMarker("foo", "b")));

        assertEquals(1, listManifests().size());
        // The current manifest is the v2 one; only its component files survive.
        ComponentManifest current = currentManifest();
        Set<String> remaining = listComponentFiles();
        for (String live : current.indices().values()) {
            assertTrue("live file " + live + " should remain", remaining.contains(live));
        }
        for (String live : current.components().values()) {
            assertTrue("live file " + live + " should remain", remaining.contains(live));
        }
    }

    public void testRejectsInvalidRetentionCount() {
        expectThrows(IllegalArgumentException.class, () -> new ComponentGarbageCollector(0));
        expectThrows(IllegalArgumentException.class, () -> new ComponentGarbageCollector(-1));
    }

    public void testSweepOnEmptyStateDirIsANoOp() {
        new ComponentGarbageCollector(2).sweep(stateDir);
        // No manifests dir present → nothing to do, no error.
    }

    // ---- helpers ----

    private ComponentManifest currentManifest() throws IOException {
        return ComponentManifest.read(stateDir.resolve(FileClusterStateLayout.CURRENT_MANIFEST));
    }

    private Set<String> listManifests() throws IOException {
        Set<String> out = new HashSet<>();
        Path dir = stateDir.resolve(FileClusterStateLayout.MANIFESTS_DIR);
        if (Files.isDirectory(dir) == false) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "manifest-*.json")) {
            for (Path p : stream) {
                out.add(p.getFileName().toString());
            }
        }
        return out;
    }

    private Set<String> listComponentFiles() throws IOException {
        Set<String> out = new HashSet<>();
        Path componentsDir = stateDir.resolve(FileClusterStateLayout.COMPONENTS_DIR);
        collect(componentsDir, componentsDir, out);
        return out;
    }

    private static void collect(Path root, Path dir, Set<String> out) throws IOException {
        if (Files.isDirectory(dir) == false) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    collect(root, p, out);
                } else if (p.getFileName().toString().endsWith(".bin")) {
                    out.add(root.relativize(p).toString().replace('\\', '/'));
                }
            }
        }
    }

    /**
     * Builds an index whose hashed content depends on {@code marker} (via a custom
     * setting) so two indices with the same name but different markers serialize to
     * different bytes and therefore different content-addressed filenames.
     */
    private static IndexMetadata indexWithMarker(String name, String marker) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_INDEX_UUID, name + "-uuid")
                    .put("index.test.marker", marker)
            )
            .numberOfShards(1)
            .numberOfReplicas(1)
            .build();
    }

    private static ClusterState buildState(String stateUuid, IndexMetadata... indices) {
        Metadata.Builder mdBuilder = Metadata.builder().clusterUUID("cluster-uuid").version(1L);
        for (IndexMetadata idx : indices) {
            mdBuilder.put(idx, false);
        }
        return ClusterState.builder(new ClusterName("test-cluster"))
            .version(Long.parseLong(stateUuid.substring(1)))
            .stateUUID(stateUuid)
            .metadata(mdBuilder)
            .build();
    }
}
