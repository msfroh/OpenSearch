/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.metadata.DiffableStringMap;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexTemplateMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.metadata.TemplatesMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.NamedWriteableAwareStreamInput;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.opensearch.common.settings.Settings.readSettingsFromStream;
import static org.opensearch.common.settings.Settings.writeSettingsToStream;

/**
 * Serializes and parses the per-slot component files referenced by a
 * {@link ComponentManifest}.
 * <p>
 * The on-disk format for each component is the same {@code writeTo} representation used
 * by inter-node transport, so existing wire-format compatibility carries over:
 * {@link CoordinationMetadata#writeTo}, {@link IndexMetadata#writeTo},
 * {@link RoutingTable#writeTo}, {@link ClusterBlocks#writeTo},
 * {@link DiscoveryNodes#writeTo}, and {@code writeNamedWriteable} for the two custom
 * registries. Only the metadata <em>header</em> (every {@link Metadata} field except
 * indices, customs, and coordination — all stored as their own component files) uses a
 * format owned by this class.
 */
final class ComponentCodec {

    private ComponentCodec() {}

    /** Parsed shape of the metadata header file — everything in {@link Metadata} except indices, customs, and coordination. */
    record MetadataHeader(
        long version,
        String clusterUUID,
        boolean clusterUUIDCommitted,
        Settings transientSettings,
        Settings persistentSettings,
        DiffableStringMap hashesOfConsistentSettings,
        TemplatesMetadata templates
    ) {}

    // ---- Metadata header ----

    static byte[] writeMetadataHeader(Metadata metadata) throws IOException {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            out.writeLong(metadata.version());
            out.writeString(metadata.clusterUUID());
            out.writeBoolean(metadata.clusterUUIDCommitted());
            writeSettingsToStream(metadata.transientSettings(), out);
            writeSettingsToStream(metadata.persistentSettings(), out);
            new DiffableStringMap(metadata.hashesOfConsistentSettings()).writeTo(out);
            metadata.templatesMetadata().writeTo(out);
            return BytesReference.toBytes(out.bytes());
        }
    }

    static MetadataHeader readMetadataHeader(Path file) throws IOException {
        try (StreamInput in = openStream(file)) {
            long version = in.readLong();
            String clusterUUID = in.readString();
            boolean clusterUUIDCommitted = in.readBoolean();
            Settings transientSettings = readSettingsFromStream(in);
            Settings persistentSettings = readSettingsFromStream(in);
            DiffableStringMap hashes = DiffableStringMap.readFrom(in);
            int n = in.readVInt();
            Map<String, IndexTemplateMetadata> templates = new LinkedHashMap<>(n);
            for (int i = 0; i < n; i++) {
                IndexTemplateMetadata t = IndexTemplateMetadata.readFrom(in);
                templates.put(t.name(), t);
            }
            return new MetadataHeader(
                version,
                clusterUUID,
                clusterUUIDCommitted,
                transientSettings,
                persistentSettings,
                hashes,
                new TemplatesMetadata(templates)
            );
        }
    }

    // ---- Top-level slots ----

    static byte[] writeCoordination(CoordinationMetadata coord) throws IOException {
        return toBytes(coord::writeTo);
    }

    static CoordinationMetadata readCoordination(Path file) throws IOException {
        try (StreamInput in = openStream(file)) {
            return new CoordinationMetadata(in);
        }
    }

    static byte[] writeRoutingTable(RoutingTable rt) throws IOException {
        return toBytes(rt::writeTo);
    }

    static RoutingTable readRoutingTable(Path file) throws IOException {
        try (StreamInput in = openStream(file)) {
            return RoutingTable.readFrom(in);
        }
    }

    static byte[] writeBlocks(ClusterBlocks blocks) throws IOException {
        return toBytes(blocks::writeTo);
    }

    static ClusterBlocks readBlocks(Path file) throws IOException {
        try (StreamInput in = openStream(file)) {
            return ClusterBlocks.readFrom(in);
        }
    }

    static byte[] writeNodes(DiscoveryNodes nodes) throws IOException {
        // writeToWithAttribute mirrors what ClusterState.writeTo emits for DiscoveryNodes
        // (the attributes are required by the published-state reader on followers).
        return toBytes(nodes::writeToWithAttribute);
    }

    static DiscoveryNodes readNodes(Path file, DiscoveryNode localNode) throws IOException {
        try (StreamInput in = openStream(file)) {
            return DiscoveryNodes.readFrom(in, localNode);
        }
    }

    // ---- Per-index ----

    static byte[] writeIndex(IndexMetadata idx) throws IOException {
        return toBytes(idx::writeTo);
    }

    static IndexMetadata readIndex(Path file) throws IOException {
        try (StreamInput in = openStream(file)) {
            return IndexMetadata.readFrom(in);
        }
    }

    // ---- Per-custom ----

    static byte[] writeMetadataCustom(Metadata.Custom custom) throws IOException {
        return toBytes(out -> out.writeNamedWriteable(custom));
    }

    static Metadata.Custom readMetadataCustom(Path file, NamedWriteableRegistry registry) throws IOException {
        try (StreamInput in = openNamedAwareStream(file, registry)) {
            return in.readNamedWriteable(Metadata.Custom.class);
        }
    }

    static byte[] writeStateCustom(ClusterState.Custom custom) throws IOException {
        return toBytes(out -> out.writeNamedWriteable(custom));
    }

    static ClusterState.Custom readStateCustom(Path file, NamedWriteableRegistry registry) throws IOException {
        try (StreamInput in = openNamedAwareStream(file, registry)) {
            return in.readNamedWriteable(ClusterState.Custom.class);
        }
    }

    // ---- Stream plumbing ----

    @FunctionalInterface
    private interface StreamWriter {
        void write(StreamOutput out) throws IOException;
    }

    private static byte[] toBytes(StreamWriter writer) throws IOException {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            writer.write(out);
            return BytesReference.toBytes(out.bytes());
        }
    }

    private static StreamInput openStream(Path file) throws IOException {
        InputStream is = Files.newInputStream(file);
        return new org.opensearch.core.common.io.stream.InputStreamStreamInput(is);
    }

    private static StreamInput openNamedAwareStream(Path file, NamedWriteableRegistry registry) throws IOException {
        return new NamedWriteableAwareStreamInput(openStream(file), registry);
    }
}
