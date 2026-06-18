/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.common.annotation.ExperimentalApi;

import java.util.Set;

/**
 * Names the subset of {@link org.opensearch.cluster.block.ClusterBlocks} a slice asks
 * for.
 * <p>
 * The catalog showed three patterns in practice: actions whose {@code checkBlock} cares
 * only about the global block set, actions that care about per-index blocks on a
 * specific set of indices, and (less commonly) actions that need both.
 * {@link All} is the conservative fallback.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public sealed interface BlockScope permits BlockScope.GlobalOnly, BlockScope.Indices, BlockScope.GlobalAndIndices, BlockScope.All {

    GlobalOnly GLOBAL_ONLY = new GlobalOnly();
    All ALL = new All();

    static BlockScope indices(Set<String> indices) {
        return new Indices(Set.copyOf(indices));
    }

    static BlockScope globalAndIndices(Set<String> indices) {
        return new GlobalAndIndices(Set.copyOf(indices));
    }

    /**
     * Only the global cluster-level blocks.
     */
    @ExperimentalApi
    record GlobalOnly() implements BlockScope {}

    /**
     * Only per-index blocks on the listed indices (no global blocks).
     */
    @ExperimentalApi
    record Indices(Set<String> indices) implements BlockScope {
        public Indices {
            if (indices == null || indices.isEmpty()) {
                throw new IllegalArgumentException("Indices block scope requires at least one index");
            }
        }
    }

    /**
     * Global blocks plus per-index blocks on the listed indices.
     */
    @ExperimentalApi
    record GlobalAndIndices(Set<String> indices) implements BlockScope {
        public GlobalAndIndices {
            if (indices == null || indices.isEmpty()) {
                throw new IllegalArgumentException("GlobalAndIndices block scope requires at least one index");
            }
        }
    }

    /**
     * Everything in {@link org.opensearch.cluster.block.ClusterBlocks}.
     */
    @ExperimentalApi
    record All() implements BlockScope {}
}
