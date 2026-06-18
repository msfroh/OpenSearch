/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.common.annotation.ExperimentalApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * A declarative description of the slice of {@link org.opensearch.cluster.ClusterState}
 * an operation needs.
 * <p>
 * {@code ClusterStateFilter} is a sealed hierarchy of <em>leaf slices</em>
 * ({@link ClusterMetadataSlice}, {@link IndexMetadataSlice}, {@link TemplatesSlice},
 * {@link MetadataCustomSlice}, {@link NodesSlice}, {@link RoutingSlice},
 * {@link BlocksSlice}, {@link InProgressSlice}, {@link CoordinationSlice}) plus the
 * composite {@link UnionFilter} and the sentinel {@link FullStateFilter} (accessible as
 * {@link #FULL_STATE}). Operations build a filter by unioning slices; a supplier may
 * always return a superset, up to the full state.
 * <p>
 * Two contracts that callers must respect:
 * <ol>
 *   <li><em>Superset semantics.</em> If you ask for one index's mappings, the supplier
 *       may return all indices' full metadata. Do not infer absence from a narrow
 *       return — always filter the returned state yourself.</li>
 *   <li><em>Composition by union.</em> There is no AND/OR algebra here. To ask for two
 *       slices you union them; the supplier fetches the minimal state that covers
 *       both.</li>
 * </ol>
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public sealed interface ClusterStateFilter
    permits
        FullStateFilter,
        UnionFilter,
        ClusterMetadataSlice,
        IndexMetadataSlice,
        TemplatesSlice,
        MetadataCustomSlice,
        NodesSlice,
        RoutingSlice,
        BlocksSlice,
        InProgressSlice,
        CoordinationSlice {

    /**
     * Sentinel that asks for the entire {@link org.opensearch.cluster.ClusterState}.
     * Unioning anything with {@code FULL_STATE} collapses back to {@code FULL_STATE}.
     * This is the default for actions that have not been migrated to a narrower
     * filter yet.
     */
    ClusterStateFilter FULL_STATE = FullStateFilter.INSTANCE;

    /**
     * Walks this filter and emits each leaf slice. The default implementation is the
     * common case for leaves: emit {@code this} as a one-element stream. Composite
     * filters override.
     */
    default Stream<ClusterStateFilter> flatten() {
        return Stream.of(this);
    }

    /**
     * Returns {@code true} if this filter is (or contains) {@link #FULL_STATE} — i.e.
     * the supplier has no opportunity to narrow.
     */
    default boolean isFullState() {
        return this == FULL_STATE;
    }

    /**
     * Combines the supplied filters into a single filter that asks for the union of
     * their slices. Null inputs are skipped; a single non-null input is returned as-is;
     * any input that is (or contains) {@link #FULL_STATE} causes the entire result to
     * collapse to {@code FULL_STATE}. Otherwise a {@link UnionFilter} is returned with
     * duplicates removed.
     */
    static ClusterStateFilter union(ClusterStateFilter... filters) {
        if (filters == null || filters.length == 0) {
            return FULL_STATE; // unioning nothing is conservatively the full state
        }
        return union(Arrays.asList(filters));
    }

    /**
     * Collection-taking equivalent of {@link #union(ClusterStateFilter...)}.
     */
    static ClusterStateFilter union(Collection<? extends ClusterStateFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return FULL_STATE;
        }
        List<ClusterStateFilter> flat = new ArrayList<>();
        for (ClusterStateFilter f : filters) {
            if (f == null) {
                continue;
            }
            if (f.isFullState()) {
                return FULL_STATE;
            }
            f.flatten().forEach(leaf -> {
                if (leaf.isFullState()) {
                    // Will be handled by the outer return below — but flatten() of a
                    // UnionFilter that contained FULL_STATE shouldn't happen because
                    // we collapse on construction. Defensive only.
                    return;
                }
                if (flat.contains(leaf) == false) {
                    flat.add(leaf);
                }
            });
        }
        if (flat.isEmpty()) {
            return FULL_STATE;
        }
        if (flat.size() == 1) {
            return flat.get(0);
        }
        return new UnionFilter(List.copyOf(flat));
    }
}
