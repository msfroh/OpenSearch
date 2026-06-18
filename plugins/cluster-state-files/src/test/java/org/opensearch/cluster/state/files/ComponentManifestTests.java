/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.test.OpenSearchTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ComponentManifestTests extends OpenSearchTestCase {

    public void testJsonRoundTrip() throws IOException {
        ComponentManifest original = sampleManifest();

        byte[] bytes = original.toJsonBytes();
        ComponentManifest parsed = ComponentManifest.fromXContent(new ByteArrayInputStream(bytes));

        assertEquals(original, parsed);
    }

    public void testReadFromFileRoundTrip() throws IOException {
        ComponentManifest original = sampleManifest();
        Path tmpDir = createTempDir();
        Path manifestFile = tmpDir.resolve("manifest-x.json");
        Files.write(manifestFile, original.toJsonBytes());

        ComponentManifest parsed = ComponentManifest.read(manifestFile);
        assertEquals(original, parsed);
    }

    public void testNullCollectionsAreRejected() {
        expectThrows(
            NullPointerException.class,
            () -> new ComponentManifest(1L, "u", "c", false, 1L, "n", null, Map.of(), Map.of(), Map.of())
        );
    }

    public void testParsingMissingRequiredScalarThrows() {
        // Cluster UUID is required — manifest without it must fail to parse.
        String json = "{\"cluster_state_version\":1,\"state_uuid\":\"u\",\"cluster_name\":\"n\","
            + "\"components\":{},\"indices\":{},\"state_customs\":{},\"metadata_customs\":{}}";
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> ComponentManifest.fromXContent(new ByteArrayInputStream(json.getBytes()))
        );
        assertTrue(e.getMessage().contains("cluster_uuid"));
    }

    public void testMissingMapsParseAsEmpty() throws IOException {
        // Optional maps absent → parsed as empty maps. Lets the publisher emit only the slots
        // it actually uses without forcing readers to handle nulls.
        String json = "{\"cluster_state_version\":7,\"state_uuid\":\"u\",\"cluster_uuid\":\"c\","
            + "\"cluster_name\":\"n\",\"components\":{}}";
        ComponentManifest m = ComponentManifest.fromXContent(new ByteArrayInputStream(json.getBytes()));
        assertEquals(7L, m.clusterStateVersion());
        assertTrue(m.indices().isEmpty());
        assertTrue(m.stateCustoms().isEmpty());
        assertTrue(m.metadataCustoms().isEmpty());
    }

    public void testRecordIsImmutable() {
        Map<String, String> mutableComponents = new LinkedHashMap<>();
        mutableComponents.put(FileClusterStateLayout.SLOT_METADATA, "metadata-h.bin");
        ComponentManifest m = new ComponentManifest(
            1L,
            "u",
            "c",
            false,
            1L,
            "n",
            mutableComponents,
            Map.of(),
            Map.of(),
            Map.of()
        );
        // Mutating the original input map shouldn't affect the manifest's view.
        mutableComponents.put(FileClusterStateLayout.SLOT_NODES, "nodes-h.bin");
        assertEquals(1, m.components().size());
        // Direct mutation of the manifest's map is rejected.
        expectThrows(UnsupportedOperationException.class, () -> m.components().put("x", "y"));
    }

    private static ComponentManifest sampleManifest() {
        return new ComponentManifest(
            42L,
            "state-uuid",
            "cluster-uuid",
            true,
            17L,
            "test-cluster",
            Map.of(
                FileClusterStateLayout.SLOT_METADATA,
                FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_METADATA, "h1"),
                FileClusterStateLayout.SLOT_ROUTING_TABLE,
                FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_ROUTING_TABLE, "h2"),
                FileClusterStateLayout.SLOT_NODES,
                FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_NODES, "h3"),
                FileClusterStateLayout.SLOT_BLOCKS,
                FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_BLOCKS, "h4"),
                FileClusterStateLayout.SLOT_COORDINATION,
                FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_COORDINATION, "h5")
            ),
            Map.of(
                "idx-uuid-1",
                FileClusterStateLayout.indexComponentFileName("idx-uuid-1", "hi1"),
                "idx-uuid-2",
                FileClusterStateLayout.indexComponentFileName("idx-uuid-2", "hi2")
            ),
            Map.of(
                "snapshots",
                FileClusterStateLayout.stateCustomFileName("snapshots", "hsc")
            ),
            Map.of(
                "component_template",
                FileClusterStateLayout.metadataCustomFileName("component_template", "hmc")
            )
        );
    }
}
