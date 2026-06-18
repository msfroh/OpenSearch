/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.RepositoryCleanupInProgress;
import org.opensearch.cluster.RestoreInProgress;
import org.opensearch.cluster.SnapshotDeletionsInProgress;
import org.opensearch.cluster.SnapshotsInProgress;
import org.opensearch.cluster.block.ClusterBlock;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.ComponentTemplateMetadata;
import org.opensearch.cluster.metadata.ComposableIndexTemplateMetadata;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexTemplateMetadata;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.service.filter.BlockScope;
import org.opensearch.cluster.service.filter.BlocksSlice;
import org.opensearch.cluster.service.filter.ClusterMetadataSlice;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.cluster.service.filter.CoordinationSlice;
import org.opensearch.cluster.service.filter.IndexMetadataSection;
import org.opensearch.cluster.service.filter.IndexMetadataSlice;
import org.opensearch.cluster.service.filter.IndexScope;
import org.opensearch.cluster.service.filter.InProgressSlice;
import org.opensearch.cluster.service.filter.InProgressType;
import org.opensearch.cluster.service.filter.MetadataCustomSlice;
import org.opensearch.cluster.service.filter.NodesSlice;
import org.opensearch.cluster.service.filter.RoutingSlice;
import org.opensearch.cluster.service.filter.TemplateKind;
import org.opensearch.cluster.service.filter.TemplatesSlice;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Narrows a fully-materialized {@link ClusterState} to the slices requested by a
 * {@link ClusterStateFilter}. The supplier contract permits returning a superset, so any
 * unsupported scope (e.g. {@link IndexScope.Pattern}) widens to "all indices" rather
 * than failing.
 * <p>
 * The output always carries the cheap identity bits (cluster name, version, state UUID,
 * cluster UUID, metadata version, coordination metadata) so downstream response builders
 * can rely on them without checking the filter.
 */
final class FileClusterStateProjection {

    private FileClusterStateProjection() {}

    static ClusterState project(ClusterState full, ClusterStateFilter filter) {
        if (filter == null || filter.isFullState()) {
            return full;
        }
        Needs needs = new Needs();
        filter.flatten().forEach(leaf -> accumulate(leaf, needs));
        if (needs.fullState) {
            return full;
        }
        return build(full, needs);
    }

    private static void accumulate(ClusterStateFilter leaf, Needs needs) {
        if (leaf instanceof ClusterMetadataSlice) {
            needs.clusterMetadata = true;
        } else if (leaf instanceof CoordinationSlice) {
            // The full CoordinationMetadata is always copied (see build()); the slice is a no-op
            // flag for projection purposes, since we don't synthesize partial CoordinationMetadata.
        } else if (leaf instanceof IndexMetadataSlice s) {
            mergeIndexMetadataSlice(needs, s);
        } else if (leaf instanceof RoutingSlice s) {
            mergeRoutingScope(needs, s.scope());
        } else if (leaf instanceof BlocksSlice s) {
            mergeBlockScope(needs, s.scope());
        } else if (leaf instanceof NodesSlice) {
            // The projection enum (FULL / MIN_MAX_VERSION / ...) is not honored yet; we copy the
            // full DiscoveryNodes when any nodes slice is requested.
            needs.nodes = true;
        } else if (leaf instanceof TemplatesSlice s) {
            needs.templateKinds.addAll(s.kinds());
        } else if (leaf instanceof MetadataCustomSlice s) {
            needs.metadataCustomTypes.addAll(s.typeNames());
        } else if (leaf instanceof InProgressSlice s) {
            needs.inProgressTypes.addAll(s.types());
        } else {
            // Includes FullStateFilter (shouldn't appear post-flatten) and any future leaf type.
            // Be conservative: return the entire state.
            needs.fullState = true;
        }
    }

    private static void mergeIndexMetadataSlice(Needs needs, IndexMetadataSlice slice) {
        IndexScope scope = slice.scope();
        Set<IndexMetadataSection> sections = slice.sections();
        if (scope instanceof IndexScope.All) {
            needs.metadataAllSections.addAll(sections);
        } else if (scope instanceof IndexScope.Named n) {
            for (String name : n.indices()) {
                needs.metadataPerIndexSections
                    .computeIfAbsent(name, k -> EnumSet.noneOf(IndexMetadataSection.class))
                    .addAll(sections);
            }
        } else {
            // Pattern and DataStreamBackings would require resolving against metadata
            // (which we haven't materialized yet at accumulation time). Fall back to "all
            // indices, requested sections" — a valid superset.
            needs.metadataAllSections.addAll(sections);
        }
    }

    private static void mergeRoutingScope(Needs needs, IndexScope scope) {
        if (needs.routingAllIndices) {
            return;
        }
        if (scope instanceof IndexScope.All) {
            needs.routingAllIndices = true;
            needs.routingIndices.clear();
        } else if (scope instanceof IndexScope.Named n) {
            needs.routingIndices.addAll(n.indices());
        } else {
            // Pattern / DataStreamBackings → fall back to all (superset is permitted).
            needs.routingAllIndices = true;
            needs.routingIndices.clear();
        }
    }

    private static void mergeBlockScope(Needs needs, BlockScope scope) {
        if (needs.blockScope instanceof BlockScope.All) {
            return;
        }
        if (scope instanceof BlockScope.All) {
            needs.blockScope = BlockScope.ALL;
            return;
        }
        if (needs.blockScope == null) {
            needs.blockScope = scope;
            return;
        }
        boolean wantGlobal = hasGlobal(needs.blockScope) || hasGlobal(scope);
        Set<String> wantIndices = new HashSet<>();
        addIndices(wantIndices, needs.blockScope);
        addIndices(wantIndices, scope);
        if (wantGlobal && wantIndices.isEmpty() == false) {
            needs.blockScope = BlockScope.globalAndIndices(wantIndices);
        } else if (wantGlobal) {
            needs.blockScope = BlockScope.GLOBAL_ONLY;
        } else {
            needs.blockScope = BlockScope.indices(wantIndices);
        }
    }

    private static boolean hasGlobal(BlockScope scope) {
        return scope instanceof BlockScope.GlobalOnly || scope instanceof BlockScope.GlobalAndIndices || scope instanceof BlockScope.All;
    }

    private static void addIndices(Set<String> sink, BlockScope scope) {
        if (scope instanceof BlockScope.Indices i) {
            sink.addAll(i.indices());
        } else if (scope instanceof BlockScope.GlobalAndIndices gi) {
            sink.addAll(gi.indices());
        }
    }

    private static ClusterState build(ClusterState full, Needs needs) {
        ClusterState.Builder builder = ClusterState.builder(full.getClusterName())
            .version(full.version())
            .stateUUID(full.stateUUID());

        Metadata.Builder mdBuilder = Metadata.builder()
            .clusterUUID(full.metadata().clusterUUID())
            .clusterUUIDCommitted(full.metadata().clusterUUIDCommitted())
            .version(full.metadata().version())
            .coordinationMetadata(full.coordinationMetadata());

        if (needs.clusterMetadata) {
            mdBuilder.persistentSettings(full.metadata().persistentSettings());
            mdBuilder.transientSettings(full.metadata().transientSettings());
        }

        if (needs.metadataAllSections.isEmpty() == false) {
            for (IndexMetadata idx : full.metadata()) {
                Set<IndexMetadataSection> sections = sectionsFor(needs, idx.getIndex().getName());
                mdBuilder.put(projectIndex(idx, sections), false);
            }
        } else {
            for (Map.Entry<String, EnumSet<IndexMetadataSection>> e : needs.metadataPerIndexSections.entrySet()) {
                IndexMetadata idx = full.metadata().index(e.getKey());
                if (idx != null) {
                    mdBuilder.put(projectIndex(idx, e.getValue()), false);
                }
            }
        }

        if (needs.templateKinds.contains(TemplateKind.LEGACY)) {
            for (IndexTemplateMetadata t : full.metadata().templates().values()) {
                mdBuilder.put(t);
            }
        }

        Set<String> customTypes = new HashSet<>(needs.metadataCustomTypes);
        if (needs.templateKinds.contains(TemplateKind.COMPONENT)) {
            customTypes.add(ComponentTemplateMetadata.TYPE);
        }
        if (needs.templateKinds.contains(TemplateKind.COMPOSABLE)) {
            customTypes.add(ComposableIndexTemplateMetadata.TYPE);
        }
        boolean wildcardAll = customTypes.contains("*");
        for (Map.Entry<String, Metadata.Custom> e : full.metadata().customs().entrySet()) {
            if (wildcardAll || customTypes.contains(e.getKey())) {
                mdBuilder.putCustom(e.getKey(), e.getValue());
            }
        }

        builder.metadata(mdBuilder);

        if (needs.routingAllIndices) {
            builder.routingTable(full.routingTable());
        } else if (needs.routingIndices.isEmpty() == false) {
            RoutingTable.Builder rtBuilder = RoutingTable.builder();
            for (String name : needs.routingIndices) {
                IndexRoutingTable irt = full.routingTable().index(name);
                if (irt != null) {
                    rtBuilder.add(irt);
                }
            }
            builder.routingTable(rtBuilder.build());
        }

        if (needs.blockScope != null) {
            builder.blocks(projectBlocks(full.blocks(), needs.blockScope));
        }

        if (needs.nodes) {
            builder.nodes(full.nodes());
        }

        for (InProgressType t : needs.inProgressTypes) {
            String typeName = inProgressTypeName(t);
            ClusterState.Custom custom = full.customs().get(typeName);
            if (custom != null) {
                builder.putCustom(typeName, custom);
            }
        }

        return builder.build();
    }

    private static ClusterBlocks projectBlocks(ClusterBlocks full, BlockScope scope) {
        boolean wantGlobal;
        Set<String> wantIndices;
        if (scope instanceof BlockScope.All) {
            wantGlobal = true;
            wantIndices = full.indices().keySet();
        } else if (scope instanceof BlockScope.GlobalOnly) {
            wantGlobal = true;
            wantIndices = Set.of();
        } else if (scope instanceof BlockScope.Indices i) {
            wantGlobal = false;
            wantIndices = i.indices();
        } else {
            BlockScope.GlobalAndIndices gai = (BlockScope.GlobalAndIndices) scope;
            wantGlobal = true;
            wantIndices = gai.indices();
        }
        ClusterBlocks.Builder b = ClusterBlocks.builder();
        if (wantGlobal) {
            for (ClusterBlock cb : full.global()) {
                b.addGlobalBlock(cb);
            }
        }
        for (String name : wantIndices) {
            Set<ClusterBlock> blocks = full.indices().get(name);
            if (blocks != null) {
                for (ClusterBlock cb : blocks) {
                    b.addIndexBlock(name, cb);
                }
            }
        }
        return b.build();
    }

    private static String inProgressTypeName(InProgressType t) {
        return switch (t) {
            case SNAPSHOTS -> SnapshotsInProgress.TYPE;
            case SNAPSHOT_DELETIONS -> SnapshotDeletionsInProgress.TYPE;
            case RESTORE -> RestoreInProgress.TYPE;
            case REPOSITORY_CLEANUP -> RepositoryCleanupInProgress.TYPE;
        };
    }

    private static Set<IndexMetadataSection> sectionsFor(Needs needs, String indexName) {
        EnumSet<IndexMetadataSection> sections = EnumSet.copyOf(needs.metadataAllSections);
        EnumSet<IndexMetadataSection> perIndex = needs.metadataPerIndexSections.get(indexName);
        if (perIndex != null) {
            sections.addAll(perIndex);
        }
        return sections;
    }

    /**
     * Returns an {@link IndexMetadata} that retains the foundational pieces every consumer
     * needs (settings — required by {@link IndexMetadata.Builder#build}, routing-num-shards,
     * system flag, context, split-shards info, versions) and as many requested sections as
     * the public {@code Builder} API can strip.
     * <p>
     * Today the public Builder API lets us strip {@link IndexMetadataSection#MAPPINGS},
     * {@link IndexMetadataSection#ALIASES}, and {@link IndexMetadataSection#INGESTION_STATUS}.
     * The remaining sections ({@code SETTINGS}, {@code STATE}, {@code CUSTOM_DATA},
     * {@code IN_SYNC_ALLOCATION_IDS}, {@code PRIMARY_TERMS}, {@code ROLLOVER_INFO}) have no
     * public removers, so they remain as in {@code full} — a valid superset per the
     * supplier contract.
     */
    private static IndexMetadata projectIndex(IndexMetadata full, Set<IndexMetadataSection> sections) {
        if (sections.containsAll(EnumSet.allOf(IndexMetadataSection.class))) {
            return full;
        }
        IndexMetadata.Builder b = IndexMetadata.builder(full);
        if (sections.contains(IndexMetadataSection.MAPPINGS) == false) {
            b.putMapping((MappingMetadata) null);
        }
        if (sections.contains(IndexMetadataSection.ALIASES) == false) {
            b.removeAllAliases();
        }
        if (sections.contains(IndexMetadataSection.INGESTION_STATUS) == false) {
            b.ingestionStatus(null);
        }
        return b.build();
    }

    private static final class Needs {
        boolean fullState;
        boolean clusterMetadata;
        final EnumSet<IndexMetadataSection> metadataAllSections = EnumSet.noneOf(IndexMetadataSection.class);
        final Map<String, EnumSet<IndexMetadataSection>> metadataPerIndexSections = new HashMap<>();
        boolean routingAllIndices;
        final Set<String> routingIndices = new HashSet<>();
        BlockScope blockScope;
        boolean nodes;
        final Set<TemplateKind> templateKinds = EnumSet.noneOf(TemplateKind.class);
        final Set<String> metadataCustomTypes = new HashSet<>();
        final Set<InProgressType> inProgressTypes = EnumSet.noneOf(InProgressType.class);
    }
}
