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
 * Describes how much of {@link org.opensearch.cluster.node.DiscoveryNodes} an operation
 * actually needs.
 * <p>
 * The catalog showed many "logical" operations pulling the full node list just for a
 * narrow check — e.g. {@code PutPipelineTransportAction} fans out to every node to
 * gather an ingest-processor compatibility catalog; {@code CreateSnapshot} consults
 * {@code nodes.getMinNodeVersion()}. The projections below let suppliers serve those
 * cheap reads without materializing the full node list.
 * <p>
 * A supplier that cannot honor a narrowed projection should fall back to {@link Full}.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public sealed interface NodeProjection permits NodeProjection.Full, NodeProjection.MinMaxVersion, NodeProjection.ClusterManagerOnly, NodeProjection.CountByAwareness {

    Full FULL = new Full();
    MinMaxVersion MIN_MAX_VERSION = new MinMaxVersion();
    ClusterManagerOnly CLUSTER_MANAGER_ONLY = new ClusterManagerOnly();

    static NodeProjection countByAwareness(String attribute) {
        return new CountByAwareness(attribute);
    }

    /**
     * The full {@link org.opensearch.cluster.node.DiscoveryNodes} object.
     */
    @ExperimentalApi
    record Full() implements NodeProjection {}

    /**
     * Just enough to satisfy {@code nodes.getMinNodeVersion()} and
     * {@code nodes.getMaxNodeVersion()}.
     */
    @ExperimentalApi
    record MinMaxVersion() implements NodeProjection {}

    /**
     * Just the elected cluster-manager node (or null if no leader).
     */
    @ExperimentalApi
    record ClusterManagerOnly() implements NodeProjection {}

    /**
     * Node counts grouped by the values of one awareness attribute. Used by
     * weighted-routing and decommission validations.
     */
    @ExperimentalApi
    record CountByAwareness(String attribute) implements NodeProjection {
        public CountByAwareness {
            if (attribute == null || attribute.isBlank()) {
                throw new IllegalArgumentException("CountByAwareness attribute must be non-blank");
            }
        }
    }
}
