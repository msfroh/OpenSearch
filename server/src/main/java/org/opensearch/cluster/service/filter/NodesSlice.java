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
 * Requests {@link org.opensearch.cluster.node.DiscoveryNodes}, optionally narrowed by a
 * {@link NodeProjection}. The supplier is free to return a richer projection (up to
 * {@link NodeProjection.Full}); operations must defensively handle anything that
 * satisfies <em>at least</em> the projection they asked for.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record NodesSlice(NodeProjection projection) implements ClusterStateFilter {

    public NodesSlice {
        if (projection == null) {
            throw new IllegalArgumentException("NodesSlice projection must be non-null");
        }
    }
}
