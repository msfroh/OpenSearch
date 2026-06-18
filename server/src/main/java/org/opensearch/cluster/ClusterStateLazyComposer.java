/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster;

import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.common.annotation.InternalApi;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Bridge that lets cross-package callers (notably file- and remote-backed
 * {@link org.opensearch.cluster.service.ClusterStateSupplier}s) compose a fully-lazy
 * {@link ClusterState}. Each supplier is invoked at most once via {@code CachedSupplier},
 * so accessors for slices the caller never touches do not pay the materialization cost.
 * <p>
 * {@link ClusterState}'s lazy constructor is kept package-private to keep the
 * {@code @InternalApi} contract off its public surface — this bridge is itself
 * {@code @InternalApi}, so it is free to expose the constructor as a public static factory.
 *
 * @opensearch.internal
 */
@InternalApi
public final class ClusterStateLazyComposer {

    private ClusterStateLazyComposer() {}

    /**
     * Returns a new lazy {@link ClusterState} whose component accessors invoke the given
     * suppliers on first access. {@code minimumClusterManagerNodesOnPublishingClusterManager}
     * defaults to {@code -1} and {@code wasReadFromDiff} to {@code false} — the same defaults
     * as the {@code (long version, String stateUUID, ClusterState state)} copy constructor.
     */
    public static ClusterState compose(
        ClusterName clusterName,
        long version,
        String stateUUID,
        Supplier<Metadata> metadataSupplier,
        Supplier<RoutingTable> routingTableSupplier,
        Supplier<DiscoveryNodes> nodesSupplier,
        Supplier<ClusterBlocks> blocksSupplier,
        Supplier<Map<String, ClusterState.Custom>> customsSupplier
    ) {
        return new ClusterState(
            clusterName,
            version,
            stateUUID,
            metadataSupplier,
            routingTableSupplier,
            nodesSupplier,
            blocksSupplier,
            customsSupplier,
            -1,
            false
        );
    }
}
