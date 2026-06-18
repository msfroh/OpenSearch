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
 * Requests a slice of {@link org.opensearch.cluster.block.ClusterBlocks}, scoped by
 * {@link BlockScope}. Every action's {@code checkBlock} consumes some subset of the
 * block set; the supplier may always return more.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record BlocksSlice(BlockScope scope) implements ClusterStateFilter {

    public BlocksSlice {
        if (scope == null) {
            throw new IllegalArgumentException("BlocksSlice scope must be non-null");
        }
    }
}
