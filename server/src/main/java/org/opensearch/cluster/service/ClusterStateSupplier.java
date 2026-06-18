/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.common.annotation.ExperimentalApi;

import java.util.function.Supplier;

/**
 * Supplies the current {@link ClusterState} to the {@link ClusterManagerService}.
 * <p>
 * Implementations may return the entire cluster state or a subset described by a
 * {@link ClusterStateFilter}. Returning a superset of the requested state is also
 * acceptable; the cluster manager only requires that the returned state contain at
 * least the requested portion.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface ClusterStateSupplier extends Supplier<ClusterState> {

    /**
     * Returns the current cluster state, restricted to (at minimum) the parts described
     * by {@code filter}. Implementations may return a superset, up to the full state.
     */
    ClusterState getClusterState(ClusterStateFilter filter);

    /**
     * Convenience equivalent to {@code getClusterState(ClusterStateFilter.FULL_STATE)},
     * provided so this interface satisfies the existing {@link Supplier} contract used
     * throughout the cluster manager wiring.
     */
    @Override
    default ClusterState get() {
        return getClusterState(ClusterStateFilter.FULL_STATE);
    }

    /**
     * Returns a {@link ClusterState} suitable as the {@code currentState} input to a
     * {@link org.opensearch.cluster.ClusterStateUpdateTask}. The {@code hint} is the
     * union filter declared by the batch of tasks about to run — implementations may
     * narrow to just the slices the tasks consult, or return a superset (up to the full
     * state). Either way is safe: the {@code ClusterManagerService} composes the
     * executor's result back onto the prior full state via
     * {@link org.opensearch.cluster.service.filter.ClusterStateMerger}, so any slice
     * missing from the executor's input is preserved from prior rather than erased.
     * <p>
     * The default implementation ignores the hint and returns the full state via
     * {@link #get()}.
     */
    default ClusterState getClusterStateForTask(ClusterStateFilter hint) {
        return get();
    }
}
