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
 * Names the set of indices a slice applies to.
 * <p>
 * Most multi-index operations only know their final set of concrete indices after
 * wildcard/alias/data-stream resolution, which itself needs a lightweight read against
 * the supplier (see {@code ClusterStateSupplier.resolveIndices(...)}). The four
 * implementations cover the cases observed in the action catalog:
 * <ul>
 *   <li>{@link All} — the full index set (read amplifier; used by template overlap
 *       validation, cluster health, allocation explain, and friends).</li>
 *   <li>{@link Named} — an explicit set of concrete index names, post-resolution.</li>
 *   <li>{@link Pattern} — a glob/wildcard expression handed to the supplier for
 *       expansion; suppliers that can't pre-resolve patterns may treat this as
 *       {@link All}.</li>
 *   <li>{@link DataStreamBackings} — the backing indices of a named data stream
 *       (delete-data-stream and similar enumerate these).</li>
 * </ul>
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public sealed interface IndexScope permits IndexScope.All, IndexScope.Named, IndexScope.Pattern, IndexScope.DataStreamBackings {

    /**
     * Singleton instance of {@link All}.
     */
    All ALL = new All();

    /**
     * Convenience constructor for {@link Named}.
     */
    static IndexScope named(Set<String> indices) {
        return new Named(Set.copyOf(indices));
    }

    /**
     * Convenience constructor for {@link Named} with a single index.
     */
    static IndexScope named(String index) {
        return new Named(Set.of(index));
    }

    /**
     * Convenience constructor for {@link Pattern}.
     */
    static IndexScope pattern(String expression) {
        return new Pattern(expression);
    }

    /**
     * Convenience constructor for {@link DataStreamBackings}.
     */
    static IndexScope dataStreamBackings(String dataStreamName) {
        return new DataStreamBackings(dataStreamName);
    }

    /**
     * The full index set — every index in the cluster's metadata.
     */
    @ExperimentalApi
    record All() implements IndexScope {}

    /**
     * An explicit set of resolved concrete index names.
     */
    @ExperimentalApi
    record Named(Set<String> indices) implements IndexScope {
        public Named {
            if (indices == null || indices.isEmpty()) {
                throw new IllegalArgumentException("Named index scope requires at least one index");
            }
        }
    }

    /**
     * A pattern that the supplier may expand server-side (e.g. {@code logs-*}). If the
     * supplier does not implement pattern expansion it should treat this as {@link All}.
     */
    @ExperimentalApi
    record Pattern(String expression) implements IndexScope {
        public Pattern {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("Pattern expression must be non-blank");
            }
        }
    }

    /**
     * The backing indices of a named data stream. The supplier consults
     * {@code metadata.customs[dataStreams]} to expand.
     */
    @ExperimentalApi
    record DataStreamBackings(String dataStreamName) implements IndexScope {
        public DataStreamBackings {
            if (dataStreamName == null || dataStreamName.isBlank()) {
                throw new IllegalArgumentException("DataStreamBackings name must be non-blank");
            }
        }
    }
}
