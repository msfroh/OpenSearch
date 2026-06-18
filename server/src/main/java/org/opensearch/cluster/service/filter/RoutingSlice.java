/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.common.annotation.ExperimentalApi;

/**
 * Requests a slice of the {@link org.opensearch.cluster.routing.RoutingTable}, scoped to
 * a set of indices.
 * <p>
 * The supplier returns the corresponding {@link org.opensearch.cluster.routing.IndexRoutingTable}
 * entries; it may always return more (up to the full routing table). Routing for indices
 * outside the scope is conceptually unobservable through this slice.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record RoutingSlice(IndexScope scope) implements ClusterStateFilter {

    public RoutingSlice {
        if (scope == null) {
            throw new IllegalArgumentException("RoutingSlice scope must be non-null");
        }
    }
}
