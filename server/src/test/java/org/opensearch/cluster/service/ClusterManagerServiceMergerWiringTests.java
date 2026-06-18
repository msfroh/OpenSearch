/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.SnapshotsInProgress;
import org.opensearch.cluster.block.ClusterBlock;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.cluster.service.filter.InProgressType;
import org.opensearch.cluster.service.filter.Slices;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.node.Node;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

/**
 * End-to-end wiring tests for {@code ClusterManagerService} + the diff-apply merger.
 * Constructs a filter-aware supplier and a task that declares a narrow filter, submits
 * the task, and asserts the publisher receives a full state with the executor's diff
 * overlaid on prior — untouched slices reference-equal to prior.
 */
public class ClusterManagerServiceMergerWiringTests extends OpenSearchTestCase {

    private static ThreadPool threadPool;

    @BeforeClass
    public static void createThreadPool() {
        threadPool = new TestThreadPool(ClusterManagerServiceMergerWiringTests.class.getName());
    }

    @AfterClass
    public static void stopThreadPool() {
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    public void testNarrowTaskMergesIntoFullStateAndPreservesReferenceEqualityOfUntouchedSlices() throws Exception {
        DiscoveryNode localNode = new DiscoveryNode("node1", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT);
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(localNode).localNodeId(localNode.getId()).clusterManagerNodeId(localNode.getId()).build();

        IndexMetadata idx = IndexMetadata.builder("idx")
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    .build()
            )
            .build();
        Metadata priorMetadata = Metadata.builder().put(idx, false).build();
        ClusterBlock globalBlock = new ClusterBlock(
            42,
            "test-block",
            false,
            false,
            false,
            RestStatus.OK,
            EnumSet.of(ClusterBlockLevel.READ)
        );
        ClusterBlocks priorBlocks = ClusterBlocks.builder().addGlobalBlock(globalBlock).build();
        RoutingTable priorRouting = RoutingTable.builder().build();

        ClusterState priorState = ClusterState.builder(new ClusterName(ClusterManagerServiceMergerWiringTests.class.getSimpleName()))
            .nodes(nodes)
            .metadata(priorMetadata)
            .blocks(priorBlocks)
            .routingTable(priorRouting)
            .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY)
            .build();

        AtomicReference<ClusterState> currentStateRef = new AtomicReference<>(priorState);
        // Track every getClusterStateForTask hint the supplier saw — so we can prove the
        // executor got a narrow input, not the full state.
        AtomicReference<ClusterStateFilter> seenHint = new AtomicReference<>();
        AtomicReference<ClusterState> narrowReturned = new AtomicReference<>();

        ClusterStateSupplier supplier = new ClusterStateSupplier() {
            @Override
            public ClusterState getClusterState(ClusterStateFilter filter) {
                return currentStateRef.get();
            }

            @Override
            public ClusterState getClusterStateForTask(ClusterStateFilter hint) {
                seenHint.set(hint);
                // Hand back a deliberately narrow ClusterState: only the InProgress custom plus
                // bare scaffolding. metadata/routing/blocks are stubs — the merger must NOT
                // propagate these into the published state for slices outside the filter.
                ClusterState narrow = ClusterState.builder(currentStateRef.get().getClusterName())
                    .version(currentStateRef.get().version())
                    .stateUUID(currentStateRef.get().stateUUID())
                    .nodes(currentStateRef.get().nodes())
                    .metadata(Metadata.EMPTY_METADATA)
                    .blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK)
                    .routingTable(RoutingTable.EMPTY_ROUTING_TABLE)
                    .putCustom(SnapshotsInProgress.TYPE, currentStateRef.get().custom(SnapshotsInProgress.TYPE))
                    .build();
                narrowReturned.set(narrow);
                return narrow;
            }
        };

        AtomicReference<ClusterState> publishedRef = new AtomicReference<>();
        AtomicReference<ClusterState> executorSawRef = new AtomicReference<>();
        AtomicBoolean publishCalled = new AtomicBoolean(false);

        ClusterManagerService service = new ClusterManagerService(
            Settings.builder()
                .put(ClusterName.CLUSTER_NAME_SETTING.getKey(), ClusterManagerServiceMergerWiringTests.class.getSimpleName())
                .put(Node.NODE_NAME_SETTING.getKey(), "test_node")
                .build(),
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
            threadPool
        );
        service.setClusterStateSupplier(supplier);
        service.setClusterStatePublisher((event, publishListener, ackListener) -> {
            publishedRef.set(event.state());
            publishCalled.set(true);
            publishListener.onResponse(null);
        });
        service.start();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> taskFailure = new AtomicReference<>();
        service.submitStateUpdateTask("test-narrow-merge", new ClusterStateUpdateTask() {
            @Override
            public ClusterStateFilter requiredState() {
                return Slices.inProgress(InProgressType.SNAPSHOTS);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                executorSawRef.set(currentState);
                return ClusterState.builder(currentState).removeCustom(SnapshotsInProgress.TYPE).build();
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                done.countDown();
            }

            @Override
            public void onFailure(String source, Exception e) {
                taskFailure.set(e);
                done.countDown();
            }
        });

        assertTrue("task did not complete in time", done.await(10, TimeUnit.SECONDS));
        assertNull("task failed: " + taskFailure.get(), taskFailure.get());

        service.stop();

        // 1. Supplier was asked for the right hint.
        ClusterStateFilter hint = seenHint.get();
        assertNotNull("getClusterStateForTask was never called", hint);
        assertEquals(Slices.inProgress(InProgressType.SNAPSHOTS), hint);

        // 2. Executor saw the narrow state, not the prior.
        assertSame("executor input must be the narrow state from the supplier", narrowReturned.get(), executorSawRef.get());

        // 3. Publisher saw a full state with the executor's diff applied AND the prior's
        //    untouched slices preserved via supplier reference inheritance.
        ClusterState published = publishedRef.get();
        assertNotNull("publish was never called", published);
        assertNull("SNAPSHOTS custom was removed", published.custom(SnapshotsInProgress.TYPE));
        assertSame("metadata inherited from prior", priorState.metadata(), published.metadata());
        assertSame("routingTable inherited from prior", priorState.routingTable(), published.routingTable());
        assertSame("blocks inherited from prior", priorState.blocks(), published.blocks());
        assertSame("nodes inherited from prior", priorState.nodes(), published.nodes());
    }
}
