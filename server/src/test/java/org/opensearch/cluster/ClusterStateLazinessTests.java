/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster;

import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ClusterStateLazinessTests extends OpenSearchTestCase {

    public void testLazyConstructorMaterializesOnlyAccessedComponents() {
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger routingCalls = new AtomicInteger();
        AtomicInteger nodesCalls = new AtomicInteger();
        AtomicInteger blocksCalls = new AtomicInteger();
        AtomicInteger customsCalls = new AtomicInteger();

        ClusterState state = new ClusterState(
            ClusterName.DEFAULT,
            1L,
            "test-uuid",
            counting(Metadata.EMPTY_METADATA, metadataCalls),
            counting(RoutingTable.EMPTY_ROUTING_TABLE, routingCalls),
            counting(DiscoveryNodes.EMPTY_NODES, nodesCalls),
            counting(ClusterBlocks.EMPTY_CLUSTER_BLOCK, blocksCalls),
            counting(Collections.<String, ClusterState.Custom>unmodifiableMap(new HashMap<>()), customsCalls),
            -1,
            false
        );

        // No accessors called yet — nothing materialized.
        assertEquals(0, metadataCalls.get());
        assertEquals(0, routingCalls.get());
        assertEquals(0, nodesCalls.get());
        assertEquals(0, blocksCalls.get());
        assertEquals(0, customsCalls.get());

        // Touching metadata() materializes only metadata.
        state.metadata();
        assertEquals(1, metadataCalls.get());
        assertEquals(0, routingCalls.get());
        assertEquals(0, nodesCalls.get());
        assertEquals(0, blocksCalls.get());
        assertEquals(0, customsCalls.get());

        // CachedSupplier semantics: repeated access does not re-invoke the supplier.
        state.metadata();
        state.metadata();
        assertEquals(1, metadataCalls.get());

        // Touching another accessor only materializes that one.
        state.routingTable();
        assertEquals(1, metadataCalls.get());
        assertEquals(1, routingCalls.get());
        assertEquals(0, nodesCalls.get());
        assertEquals(0, blocksCalls.get());
        assertEquals(0, customsCalls.get());
    }

    public void testEagerConstructorBehavesIdentically() {
        // Existing public constructor still works and returns the eager values.
        Map<String, ClusterState.Custom> customs = new HashMap<>();
        ClusterState state = new ClusterState(
            ClusterName.DEFAULT,
            42L,
            "uuid",
            Metadata.EMPTY_METADATA,
            RoutingTable.EMPTY_ROUTING_TABLE,
            DiscoveryNodes.EMPTY_NODES,
            ClusterBlocks.EMPTY_CLUSTER_BLOCK,
            customs,
            -1,
            false
        );
        assertSame(Metadata.EMPTY_METADATA, state.metadata());
        assertSame(RoutingTable.EMPTY_ROUTING_TABLE, state.routingTable());
        assertSame(DiscoveryNodes.EMPTY_NODES, state.nodes());
        assertSame(ClusterBlocks.EMPTY_CLUSTER_BLOCK, state.blocks());
        assertEquals(42L, state.version());
        assertEquals("uuid", state.stateUUID());
    }

    public void testBuilderCopyPropagatesLazinessWhenNothingMutated() {
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger routingCalls = new AtomicInteger();
        AtomicInteger nodesCalls = new AtomicInteger();
        AtomicInteger blocksCalls = new AtomicInteger();
        AtomicInteger customsCalls = new AtomicInteger();

        ClusterState source = new ClusterState(
            ClusterName.DEFAULT,
            1L,
            "src-uuid",
            counting(Metadata.EMPTY_METADATA, metadataCalls),
            counting(RoutingTable.EMPTY_ROUTING_TABLE, routingCalls),
            counting(DiscoveryNodes.EMPTY_NODES, nodesCalls),
            counting(ClusterBlocks.EMPTY_CLUSTER_BLOCK, blocksCalls),
            counting(Collections.<String, ClusterState.Custom>unmodifiableMap(new HashMap<>()), customsCalls),
            -1,
            false
        );

        // Pure copy: bumping the version shouldn't force any component to materialize.
        ClusterState copy = ClusterState.builder(source).incrementVersion().build();
        assertEquals(0, metadataCalls.get());
        assertEquals(0, routingCalls.get());
        assertEquals(0, nodesCalls.get());
        assertEquals(0, blocksCalls.get());
        assertEquals(0, customsCalls.get());

        // The copy and source share the same per-component suppliers — accessing copy.metadata()
        // materializes once, and source.metadata() returns the same instance for free.
        copy.metadata();
        assertEquals(1, metadataCalls.get());
        assertSame(copy.metadata(), source.metadata());
        assertEquals(1, metadataCalls.get());
    }

    public void testBuilderSetterMaterializesOnlyTheTouchedSlice() {
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger routingCalls = new AtomicInteger();
        AtomicInteger nodesCalls = new AtomicInteger();
        AtomicInteger blocksCalls = new AtomicInteger();
        AtomicInteger customsCalls = new AtomicInteger();

        ClusterState source = new ClusterState(
            ClusterName.DEFAULT,
            1L,
            "src-uuid",
            counting(Metadata.EMPTY_METADATA, metadataCalls),
            counting(RoutingTable.EMPTY_ROUTING_TABLE, routingCalls),
            counting(DiscoveryNodes.EMPTY_NODES, nodesCalls),
            counting(ClusterBlocks.EMPTY_CLUSTER_BLOCK, blocksCalls),
            counting(Collections.<String, ClusterState.Custom>unmodifiableMap(new HashMap<>()), customsCalls),
            -1,
            false
        );

        // Swap in fresh blocks; nothing else should materialize.
        ClusterState rebuilt = ClusterState.builder(source).blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK).build();
        assertEquals(0, metadataCalls.get());
        assertEquals(0, routingCalls.get());
        assertEquals(0, nodesCalls.get());
        assertEquals(0, blocksCalls.get()); // source's blocks supplier was never asked
        assertEquals(0, customsCalls.get());
    }

    private static <T> Supplier<T> counting(T value, AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            return value;
        };
    }
}
