/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.common.annotation.ExperimentalApi;

import java.util.EnumSet;
import java.util.Set;

/**
 * Requests one or more of the top-level {@code ClusterState} in-progress customs
 * (snapshots, snapshot deletions, restores, repository cleanups). Most callers will
 * ask for all four via {@link Slices#inProgressAll()}.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record InProgressSlice(Set<InProgressType> types) implements ClusterStateFilter {

    public InProgressSlice {
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("InProgressSlice types must be non-empty");
        }
        types = Set.copyOf(EnumSet.copyOf(types));
    }
}
