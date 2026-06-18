/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Path and JSON-key constants shared between {@link FileClusterStatePublisher} and
 * {@link FileClusterStateSupplier}.
 *
 * <h2>Per-component write-once layout</h2>
 * <pre>
 * state/
 *   current-manifest.json                     # atomic pointer to the latest manifest
 *   manifests/manifest-&lt;state-uuid&gt;.json     # versioned manifest
 *   components/
 *     metadata-&lt;sha256&gt;.bin                   # top-level Metadata (without indices/customs)
 *     routing-table-&lt;sha256&gt;.bin
 *     blocks-&lt;sha256&gt;.bin
 *     nodes-&lt;sha256&gt;.bin
 *     coordination-&lt;sha256&gt;.bin
 *     indices/&lt;index-uuid&gt;-&lt;sha256&gt;.bin
 *     customs/&lt;custom-type&gt;-&lt;sha256&gt;.bin       # ClusterState.Custom (snapshots, restore, …)
 *     metadata-customs/&lt;type&gt;-&lt;sha256&gt;.bin     # Metadata.Custom (templates, data streams, …)
 * </pre>
 * Each component file is content-addressed by the SHA-256 of its serialized bytes, so
 * unchanged components produced by the next publication dedupe to the same filename and
 * skip the write. The manifest at the top of each publication enumerates which file
 * stands in for each slot; cross-publication GC walks recent manifests to keep referenced
 * files alive and reap the rest.
 */
final class FileClusterStateLayout {

    static final String CURRENT_MANIFEST = "current-manifest.json";
    static final String MANIFESTS_DIR = "manifests";
    static final String COMPONENTS_DIR = "components";
    static final String COMPONENTS_INDICES_DIR = "indices";
    static final String COMPONENTS_STATE_CUSTOMS_DIR = "customs";
    static final String COMPONENTS_METADATA_CUSTOMS_DIR = "metadata-customs";

    /** Top-level component slot keys — also the slot's filename prefix. */
    static final String SLOT_METADATA = "metadata";
    static final String SLOT_ROUTING_TABLE = "routing-table";
    static final String SLOT_BLOCKS = "blocks";
    static final String SLOT_NODES = "nodes";
    static final String SLOT_COORDINATION = "coordination";

    /** Manifest JSON keys. */
    static final String MANIFEST_CLUSTER_STATE_VERSION = "cluster_state_version";
    static final String MANIFEST_STATE_UUID = "state_uuid";
    static final String MANIFEST_CLUSTER_UUID = "cluster_uuid";
    static final String MANIFEST_CLUSTER_UUID_COMMITTED = "cluster_uuid_committed";
    static final String MANIFEST_METADATA_VERSION = "metadata_version";
    static final String MANIFEST_CLUSTER_NAME = "cluster_name";
    static final String MANIFEST_COMPONENTS = "components";
    static final String MANIFEST_INDICES = "indices";
    static final String MANIFEST_STATE_CUSTOMS = "state_customs";
    static final String MANIFEST_METADATA_CUSTOMS = "metadata_customs";

    private FileClusterStateLayout() {}

    /** Returns {@code "<slot>-<sha>.bin"}, e.g. {@code "metadata-abc….bin"}. */
    static String componentFileName(String slot, String sha256) {
        return slot + "-" + sha256 + ".bin";
    }

    /**
     * Returns the components-relative path for a per-index file, e.g.
     * {@code "indices/<index-uuid>-<sha>.bin"}.
     */
    static String indexComponentFileName(String indexUuid, String sha256) {
        return COMPONENTS_INDICES_DIR + "/" + indexUuid + "-" + sha256 + ".bin";
    }

    /**
     * Returns the components-relative path for a {@code ClusterState.Custom} file, e.g.
     * {@code "customs/snapshots-<sha>.bin"}.
     */
    static String stateCustomFileName(String typeName, String sha256) {
        return COMPONENTS_STATE_CUSTOMS_DIR + "/" + typeName + "-" + sha256 + ".bin";
    }

    /**
     * Returns the components-relative path for a {@code Metadata.Custom} file, e.g.
     * {@code "metadata-customs/component_template-<sha>.bin"}.
     */
    static String metadataCustomFileName(String typeName, String sha256) {
        return COMPONENTS_METADATA_CUSTOMS_DIR + "/" + typeName + "-" + sha256 + ".bin";
    }

    /** Returns {@code "manifests/manifest-<state-uuid>.json"}. */
    static String manifestFileName(String stateUuid) {
        return MANIFESTS_DIR + "/manifest-" + stateUuid + ".json";
    }

    /** Lowercase-hex SHA-256 over {@code bytes}. Used as the content-address of a component file. */
    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Convenience: SHA-256 of the UTF-8 bytes of {@code s}. Test helper. */
    static String sha256Utf8(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
