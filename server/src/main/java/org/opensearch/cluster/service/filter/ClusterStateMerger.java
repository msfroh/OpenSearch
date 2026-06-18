/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

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
import org.opensearch.cluster.metadata.LazyIndices;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.metadata.MetadataLazyComposer;
import org.opensearch.cluster.metadata.TemplatesMetadata;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.common.annotation.InternalApi;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Composes a new {@link ClusterState} from a prior state plus the narrow input/output a
 * task ran against — overlaying only the slices declared in {@code filter}, and
 * propagating prior's supplier references for everything else so unread components stay
 * lazy.
 * <p>
 * Contract:
 * <ul>
 *   <li>{@code narrowInput == narrowOutput} → {@code priorState} unchanged.</li>
 *   <li>{@code filter.isFullState()} → {@code narrowOutput} unchanged (unmigrated path).</li>
 *   <li>Otherwise: walk the filter's leaves, apply each to an accumulator, then compose.</li>
 * </ul>
 * The merger MUST NOT call accessors on {@code priorState} for slices outside
 * {@code filter} — that is what makes lazy prior states survive the merge.
 * <p>
 * v1 supports {@link InProgressSlice}, {@link TemplatesSlice}, {@link IndexMetadataSlice},
 * {@link BlocksSlice}, and {@link RoutingSlice}. Other slice families throw
 * {@link UnsupportedOperationException} so accidental use fails loudly rather than silently
 * corrupting state.
 *
 * @opensearch.internal
 */
@InternalApi
public final class ClusterStateMerger {

    private ClusterStateMerger() {}

    public static ClusterState merge(
        ClusterState priorState,
        ClusterState narrowInput,
        ClusterState narrowOutput,
        ClusterStateFilter filter
    ) {
        if (narrowInput == narrowOutput) {
            return priorState;
        }
        if (filter.isFullState()) {
            return narrowOutput;
        }
        Mergers acc = new Mergers(priorState, narrowOutput);
        filter.flatten().forEach(acc::apply);
        return acc.build();
    }

    private static String inProgressTypeName(InProgressType t) {
        return switch (t) {
            case SNAPSHOTS -> SnapshotsInProgress.TYPE;
            case SNAPSHOT_DELETIONS -> SnapshotDeletionsInProgress.TYPE;
            case RESTORE -> RestoreInProgress.TYPE;
            case REPOSITORY_CLEANUP -> RepositoryCleanupInProgress.TYPE;
        };
    }

    private static final class Mergers {
        private final ClusterState prior;
        private final ClusterState narrow;

        // State-level cluster customs (snapshots, restore, etc.). Null = no change.
        private Map<String, ClusterState.Custom> stateCustomChanges;
        private Set<String> stateCustomRemovals;

        // Metadata-level overrides.
        private TemplatesMetadata templatesOverride;
        private Map<String, Metadata.Custom> metadataCustomChanges;
        private Set<String> metadataCustomRemovals;
        private Map<String, IndexMetadata> indexOverrides;

        // Top-level cluster slices.
        private ClusterBlocks blocksOverride;
        private RoutingTable routingOverride;

        Mergers(ClusterState prior, ClusterState narrow) {
            this.prior = prior;
            this.narrow = narrow;
        }

        void apply(ClusterStateFilter leaf) {
            if (leaf instanceof InProgressSlice s) {
                applyInProgress(s);
            } else if (leaf instanceof TemplatesSlice s) {
                applyTemplates(s);
            } else if (leaf instanceof IndexMetadataSlice s) {
                applyIndexMetadata(s);
            } else if (leaf instanceof BlocksSlice s) {
                applyBlocks(s);
            } else if (leaf instanceof RoutingSlice s) {
                applyRouting(s);
            } else if (leaf instanceof NodesSlice
                || leaf instanceof MetadataCustomSlice
                || leaf instanceof CoordinationSlice
                || leaf instanceof ClusterMetadataSlice) {
                    throw new UnsupportedOperationException("Slice not yet supported by ClusterStateMerger: " + leaf);
                } else if (leaf instanceof FullStateFilter) {
                    throw new IllegalStateException("FullStateFilter leaf in non-full filter (should have short-circuited)");
                } else if (leaf instanceof UnionFilter) {
                    throw new IllegalStateException("Unflattened UnionFilter leaf (filter.flatten() should have unwrapped this)");
                } else {
                    throw new IllegalArgumentException("Unknown slice type: " + leaf);
                }
        }

        private void applyInProgress(InProgressSlice slice) {
            for (InProgressType t : slice.types()) {
                String typeName = inProgressTypeName(t);
                ClusterState.Custom narrowV = narrow.customs().get(typeName);
                ClusterState.Custom priorV = prior.customs().get(typeName);
                if (narrowV == priorV) {
                    continue; // task didn't actually change this type
                }
                if (narrowV == null) {
                    if (stateCustomRemovals == null) stateCustomRemovals = new HashSet<>();
                    stateCustomRemovals.add(typeName);
                } else {
                    if (stateCustomChanges == null) stateCustomChanges = new LinkedHashMap<>();
                    stateCustomChanges.put(typeName, narrowV);
                }
            }
        }

        private void applyTemplates(TemplatesSlice slice) {
            Metadata priorMeta = prior.metadata();
            Metadata narrowMeta = narrow.metadata();
            for (TemplateKind kind : slice.kinds()) {
                switch (kind) {
                    case LEGACY -> {
                        TemplatesMetadata narrowT = narrowMeta.templatesMetadata();
                        if (narrowT != priorMeta.templatesMetadata()) {
                            templatesOverride = narrowT;
                        }
                    }
                    case COMPONENT -> {
                        Metadata.Custom narrowCt = narrowMeta.customs().get(ComponentTemplateMetadata.TYPE);
                        Metadata.Custom priorCt = priorMeta.customs().get(ComponentTemplateMetadata.TYPE);
                        if (narrowCt != priorCt) {
                            stageMetadataCustom(ComponentTemplateMetadata.TYPE, narrowCt);
                        }
                    }
                    case COMPOSABLE -> {
                        Metadata.Custom narrowCit = narrowMeta.customs().get(ComposableIndexTemplateMetadata.TYPE);
                        Metadata.Custom priorCit = priorMeta.customs().get(ComposableIndexTemplateMetadata.TYPE);
                        if (narrowCit != priorCit) {
                            stageMetadataCustom(ComposableIndexTemplateMetadata.TYPE, narrowCit);
                        }
                    }
                }
            }
        }

        private void stageMetadataCustom(String typeName, Metadata.Custom value) {
            if (value == null) {
                if (metadataCustomRemovals == null) metadataCustomRemovals = new HashSet<>();
                metadataCustomRemovals.add(typeName);
            } else {
                if (metadataCustomChanges == null) metadataCustomChanges = new LinkedHashMap<>();
                metadataCustomChanges.put(typeName, value);
            }
        }

        private void applyIndexMetadata(IndexMetadataSlice slice) {
            // v1: only IndexScope.Named is supported. Other scopes (All, Pattern,
            // DataStreamBackings) widen the merge to potentially every index, which
            // requires support for add/remove of index keys (and the corresponding
            // arrays/lookup rebuild). Deferred.
            if (slice.scope() instanceof IndexScope.Named named) {
                Metadata priorMeta = prior.metadata();
                Metadata narrowMeta = narrow.metadata();
                for (String name : named.indices()) {
                    IndexMetadata newIdx = narrowMeta.index(name);
                    IndexMetadata priorIdx = priorMeta.index(name);
                    if (newIdx == priorIdx) {
                        continue; // task didn't actually mutate this index
                    }
                    if (newIdx == null) {
                        throw new UnsupportedOperationException(
                            "ClusterStateMerger does not yet support deleting indices via IndexMetadataSlice; name=" + name
                        );
                    }
                    if (indexOverrides == null) indexOverrides = new LinkedHashMap<>();
                    indexOverrides.put(name, newIdx);
                }
            } else {
                throw new UnsupportedOperationException(
                    "ClusterStateMerger only supports IndexScope.Named for IndexMetadataSlice; got " + slice.scope()
                );
            }
        }

        private void applyBlocks(BlocksSlice slice) {
            ClusterBlocks priorBlocks = prior.blocks();
            ClusterBlocks narrowBlocks = narrow.blocks();
            if (priorBlocks == narrowBlocks) {
                return; // task didn't actually mutate blocks
            }
            BlockScope scope = slice.scope();
            ClusterBlocks.Builder b = ClusterBlocks.builder();
            if (scope instanceof BlockScope.All) {
                blocksOverride = narrowBlocks;
                return;
            }
            // Decide which side contributes global blocks.
            boolean takeGlobalFromNarrow = scope instanceof BlockScope.GlobalOnly || scope instanceof BlockScope.GlobalAndIndices;
            for (ClusterBlock block : takeGlobalFromNarrow ? narrowBlocks.global() : priorBlocks.global()) {
                b.addGlobalBlock(block);
            }
            // Decide per-index contributions.
            Set<String> indicesFromNarrow;
            if (scope instanceof BlockScope.Indices i) {
                indicesFromNarrow = i.indices();
            } else if (scope instanceof BlockScope.GlobalAndIndices gi) {
                indicesFromNarrow = gi.indices();
            } else {
                indicesFromNarrow = Collections.emptySet();
            }
            for (Map.Entry<String, Set<ClusterBlock>> e : priorBlocks.indices().entrySet()) {
                String name = e.getKey();
                if (indicesFromNarrow.contains(name)) {
                    continue; // narrow overrides
                }
                for (ClusterBlock block : e.getValue()) {
                    b.addIndexBlock(name, block);
                }
            }
            for (String name : indicesFromNarrow) {
                Set<ClusterBlock> nb = narrowBlocks.indices().get(name);
                if (nb == null) continue;
                for (ClusterBlock block : nb) {
                    b.addIndexBlock(name, block);
                }
            }
            blocksOverride = b.build();
        }

        private void applyRouting(RoutingSlice slice) {
            IndexScope scope = slice.scope();
            RoutingTable narrowRouting = narrow.routingTable();
            RoutingTable priorRouting = prior.routingTable();
            if (narrowRouting == priorRouting) {
                return; // task didn't actually mutate routing
            }
            if (scope instanceof IndexScope.All) {
                routingOverride = narrowRouting;
                return;
            }
            if (scope instanceof IndexScope.Named named) {
                RoutingTable.Builder b = RoutingTable.builder(priorRouting);
                boolean changed = false;
                for (String name : named.indices()) {
                    IndexRoutingTable newR = narrowRouting.index(name);
                    IndexRoutingTable priorR = priorRouting.index(name);
                    if (newR == priorR) {
                        continue;
                    }
                    if (newR == null) {
                        b.remove(name);
                    } else {
                        b.add(newR);
                    }
                    changed = true;
                }
                if (changed) {
                    routingOverride = b.build();
                }
                return;
            }
            throw new UnsupportedOperationException(
                "ClusterStateMerger only supports IndexScope.All and IndexScope.Named for RoutingSlice; got " + scope
            );
        }

        ClusterState build() {
            boolean anyChange = stateCustomChanges != null
                || stateCustomRemovals != null
                || templatesOverride != null
                || metadataCustomChanges != null
                || metadataCustomRemovals != null
                || indexOverrides != null
                || blocksOverride != null
                || routingOverride != null;
            if (anyChange == false) {
                return prior;
            }

            ClusterState.Builder b = ClusterState.builder(prior);
            if (stateCustomChanges != null) {
                for (Map.Entry<String, ClusterState.Custom> e : stateCustomChanges.entrySet()) {
                    b.putCustom(e.getKey(), e.getValue());
                }
            }
            if (stateCustomRemovals != null) {
                for (String type : stateCustomRemovals) {
                    b.removeCustom(type);
                }
            }
            boolean metaChange = templatesOverride != null
                || metadataCustomChanges != null
                || metadataCustomRemovals != null
                || indexOverrides != null;
            if (metaChange) {
                b.metadata(composeMetadata());
            }
            if (blocksOverride != null) {
                b.blocks(blocksOverride);
            }
            if (routingOverride != null) {
                b.routingTable(routingOverride);
            }
            return b.build();
        }

        private Metadata composeMetadata() {
            Metadata priorMeta = prior.metadata();
            // Templates: simple supplier swap.
            java.util.function.Supplier<TemplatesMetadata> templatesSup = null;
            if (templatesOverride != null) {
                final TemplatesMetadata t = templatesOverride;
                templatesSup = () -> t;
            }
            // Customs: must materialize prior's customs map to merge (map is the unit).
            java.util.function.Supplier<Map<String, Metadata.Custom>> customsSup = null;
            if (metadataCustomChanges != null || metadataCustomRemovals != null) {
                Map<String, Metadata.Custom> merged = new HashMap<>(priorMeta.customs());
                if (metadataCustomChanges != null) {
                    merged.putAll(metadataCustomChanges);
                }
                if (metadataCustomRemovals != null) {
                    for (String type : metadataCustomRemovals) {
                        merged.remove(type);
                    }
                }
                final Map<String, Metadata.Custom> sealed = Collections.unmodifiableMap(merged);
                customsSup = () -> sealed;
            }
            // Indices: in-place section overlay via LazyIndices.with(...). Per-key supplier
            // references for non-overridden keys are inherited and never invoked.
            LazyIndices indicesOverride = null;
            if (this.indexOverrides != null) {
                LazyIndices priorIndices = (LazyIndices) priorMeta.indices();
                indicesOverride = priorIndices.with(this.indexOverrides);
            }
            return MetadataLazyComposer.compose(
                priorMeta,
                /* coordination */ null,
                /* transient    */ null,
                /* persistent   */ null,
                /* hashes       */ null,
                indicesOverride,
                templatesSup,
                customsSup
            );
        }
    }
}
