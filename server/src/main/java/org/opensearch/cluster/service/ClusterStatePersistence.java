/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service;

import org.opensearch.cluster.coordination.ClusterStatePublisher;
import org.opensearch.common.annotation.ExperimentalApi;

/**
 * Bundles together the two halves of an externalized cluster state implementation:
 * a {@link ClusterStateSupplier} used by the {@link ClusterManagerService} to read
 * the current state, and a {@link ClusterStatePublisher} used to durably write
 * accepted updates.
 * <p>
 * Plugins provide a single {@link ClusterStatePersistence} via
 * {@link org.opensearch.plugins.DiscoveryPlugin#getClusterStatePersistence()}. It is an
 * error to load multiple plugins that each provide one.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface ClusterStatePersistence {

    /**
     * Returns the supplier used by the cluster manager to read the current state.
     */
    ClusterStateSupplier getClusterStateSupplier();

    /**
     * Returns the publisher used by the cluster manager to persist accepted updates.
     */
    ClusterStatePublisher getClusterStatePublisher();
}
