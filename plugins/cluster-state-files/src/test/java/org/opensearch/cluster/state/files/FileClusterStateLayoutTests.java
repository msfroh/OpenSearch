/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.test.OpenSearchTestCase;

public class FileClusterStateLayoutTests extends OpenSearchTestCase {

    public void testSha256IsStableAndLowercaseHex() {
        String a = FileClusterStateLayout.sha256Utf8("hello");
        // RFC 6234 reference: SHA-256("hello") = 2cf24dba…
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", a);
        // Stable across calls.
        assertEquals(a, FileClusterStateLayout.sha256Utf8("hello"));
        // Different content → different hash.
        assertNotEquals(a, FileClusterStateLayout.sha256Utf8("world"));
    }

    public void testComponentFileNameUsesSlotPrefix() {
        assertEquals(
            "metadata-abc123.bin",
            FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_METADATA, "abc123")
        );
        assertEquals(
            "routing-table-deadbeef.bin",
            FileClusterStateLayout.componentFileName(FileClusterStateLayout.SLOT_ROUTING_TABLE, "deadbeef")
        );
    }

    public void testPerIndexFileNameContainsIndexUuidAndHash() {
        String path = FileClusterStateLayout.indexComponentFileName("idx-uuid", "h1");
        assertEquals("indices/idx-uuid-h1.bin", path);
    }

    public void testCustomFileNamesGoUnderTheRightSubdirectory() {
        assertEquals(
            "customs/snapshots-h1.bin",
            FileClusterStateLayout.stateCustomFileName("snapshots", "h1")
        );
        assertEquals(
            "metadata-customs/component_template-h2.bin",
            FileClusterStateLayout.metadataCustomFileName("component_template", "h2")
        );
    }

    public void testManifestFileNameContainsStateUuid() {
        assertEquals("manifests/manifest-abc.json", FileClusterStateLayout.manifestFileName("abc"));
    }
}
