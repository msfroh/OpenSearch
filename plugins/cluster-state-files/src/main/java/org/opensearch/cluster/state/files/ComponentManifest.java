/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Manifest for the per-component write-once layout (see {@link FileClusterStateLayout}).
 * Names the file that stands in for each top-level component, each per-index entry, each
 * {@code ClusterState.Custom}, and each {@code Metadata.Custom}. Also carries the cheap
 * scalar identity fields ({@code metadata_version}, {@code cluster_uuid_committed}) so a
 * lazy reader can populate {@link org.opensearch.cluster.metadata.Metadata}'s eager fields
 * without opening the metadata header component file.
 * <p>
 * Each filename is a components-relative path (e.g. {@code "indices/foo-uuid-<sha>.bin"}).
 * Equal-content components published in successive states reuse the same filename, so the
 * manifest is a structural diff of the cluster state: whatever changed has a new content
 * address; whatever didn't is referenced by its prior filename.
 */
record ComponentManifest(
    long clusterStateVersion,
    String stateUuid,
    String clusterUuid,
    boolean clusterUuidCommitted,
    long metadataVersion,
    String clusterName,
    Map<String, String> components,
    Map<String, String> indices,
    Map<String, String> stateCustoms,
    Map<String, String> metadataCustoms
) {

    ComponentManifest {
        Objects.requireNonNull(stateUuid, "stateUuid");
        Objects.requireNonNull(clusterUuid, "clusterUuid");
        Objects.requireNonNull(clusterName, "clusterName");
        components = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(components, "components")));
        indices = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(indices, "indices")));
        stateCustoms = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(stateCustoms, "stateCustoms")));
        metadataCustoms = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(metadataCustoms, "metadataCustoms")));
    }

    /** Serializes this manifest as JSON to {@code out}. Sorted keys for stable output. */
    void toXContent(XContentBuilder out) throws IOException {
        out.startObject();
        out.field(FileClusterStateLayout.MANIFEST_CLUSTER_STATE_VERSION, clusterStateVersion);
        out.field(FileClusterStateLayout.MANIFEST_STATE_UUID, stateUuid);
        out.field(FileClusterStateLayout.MANIFEST_CLUSTER_UUID, clusterUuid);
        out.field(FileClusterStateLayout.MANIFEST_CLUSTER_UUID_COMMITTED, clusterUuidCommitted);
        out.field(FileClusterStateLayout.MANIFEST_METADATA_VERSION, metadataVersion);
        out.field(FileClusterStateLayout.MANIFEST_CLUSTER_NAME, clusterName);
        writeStringMap(out, FileClusterStateLayout.MANIFEST_COMPONENTS, components);
        writeStringMap(out, FileClusterStateLayout.MANIFEST_INDICES, indices);
        writeStringMap(out, FileClusterStateLayout.MANIFEST_STATE_CUSTOMS, stateCustoms);
        writeStringMap(out, FileClusterStateLayout.MANIFEST_METADATA_CUSTOMS, metadataCustoms);
        out.endObject();
    }

    /** Returns the JSON bytes of this manifest — convenient for writing to disk. */
    byte[] toJsonBytes() throws IOException {
        try (XContentBuilder builder = JsonXContent.contentBuilder()) {
            toXContent(builder);
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Parses a manifest from a JSON {@link InputStream}. */
    static ComponentManifest fromXContent(InputStream in) throws IOException {
        try (
            XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                LoggingDeprecationHandler.INSTANCE,
                in
            )
        ) {
            Map<String, Object> map = parser.map();
            return fromMap(map);
        }
    }

    /** Reads and parses a manifest file. */
    static ComponentManifest read(Path manifestFile) throws IOException {
        try (InputStream in = Files.newInputStream(manifestFile)) {
            return fromXContent(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static ComponentManifest fromMap(Map<String, Object> map) {
        long version = requireLong(map, FileClusterStateLayout.MANIFEST_CLUSTER_STATE_VERSION);
        String stateUuid = requireString(map, FileClusterStateLayout.MANIFEST_STATE_UUID);
        String clusterUuid = requireString(map, FileClusterStateLayout.MANIFEST_CLUSTER_UUID);
        boolean clusterUuidCommitted = requireBooleanOrDefault(map, FileClusterStateLayout.MANIFEST_CLUSTER_UUID_COMMITTED, false);
        long metadataVersion = requireLongOrDefault(map, FileClusterStateLayout.MANIFEST_METADATA_VERSION, 0L);
        String clusterName = requireString(map, FileClusterStateLayout.MANIFEST_CLUSTER_NAME);
        return new ComponentManifest(
            version,
            stateUuid,
            clusterUuid,
            clusterUuidCommitted,
            metadataVersion,
            clusterName,
            stringMap(map, FileClusterStateLayout.MANIFEST_COMPONENTS),
            stringMap(map, FileClusterStateLayout.MANIFEST_INDICES),
            stringMap(map, FileClusterStateLayout.MANIFEST_STATE_CUSTOMS),
            stringMap(map, FileClusterStateLayout.MANIFEST_METADATA_CUSTOMS)
        );
    }

    private static long requireLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("manifest missing required key '" + key + "'");
        }
        if (v instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalArgumentException("manifest key '" + key + "' is not a number: " + v);
    }

    private static long requireLongOrDefault(Map<String, Object> map, String key, long fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalArgumentException("manifest key '" + key + "' is not a number: " + v);
    }

    private static boolean requireBooleanOrDefault(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("manifest key '" + key + "' is not a boolean: " + v);
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("manifest missing required key '" + key + "'");
        }
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Collections.emptyMap();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey().toString(), e.getValue() == null ? null : e.getValue().toString());
            }
            return out;
        }
        throw new IllegalArgumentException("manifest key '" + key + "' is not an object: " + v);
    }

    private static void writeStringMap(XContentBuilder out, String field, Map<String, String> values) throws IOException {
        out.startObject(field);
        for (Map.Entry<String, String> e : values.entrySet()) {
            out.field(e.getKey(), e.getValue());
        }
        out.endObject();
    }
}
