/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.common.annotation.ExperimentalApi;

import java.util.List;
import java.util.stream.Stream;

/**
 * Composite filter that asks for the union of its children's slices.
 * <p>
 * Constructed indirectly via {@link ClusterStateFilter#union}. The factory deduplicates
 * leaves and collapses to {@link ClusterStateFilter#FULL_STATE} if any child is the
 * full-state sentinel, so {@code UnionFilter} instances always hold two or more
 * distinct, non-full-state leaves.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record UnionFilter(List<ClusterStateFilter> children) implements ClusterStateFilter {

    public UnionFilter {
        if (children == null || children.size() < 2) {
            throw new IllegalArgumentException("UnionFilter requires at least two children; got " + children);
        }
        for (ClusterStateFilter child : children) {
            if (child == null) {
                throw new IllegalArgumentException("UnionFilter children must be non-null");
            }
            if (child.isFullState()) {
                throw new IllegalArgumentException("UnionFilter cannot contain FULL_STATE; collapse via ClusterStateFilter.union()");
            }
            if (child instanceof UnionFilter) {
                throw new IllegalArgumentException("UnionFilter cannot contain another UnionFilter; flatten via ClusterStateFilter.union()");
            }
        }
    }

    @Override
    public Stream<ClusterStateFilter> flatten() {
        return children.stream();
    }
}
