/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.SnapshotsInProgress;
import org.opensearch.cluster.block.ClusterBlock;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.ComponentTemplate;
import org.opensearch.cluster.metadata.ComponentTemplateMetadata;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexTemplateMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.metadata.Template;
import org.opensearch.cluster.metadata.TemplatesMetadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.Index;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ClusterStateMergerTests extends OpenSearchTestCase {

    public void testIdentityShortCircuitReturnsPriorWhenInputEqualsOutput() {
        ClusterState prior = baseState();
        ClusterState narrow = baseState();
        ClusterState merged = ClusterStateMerger.merge(prior, narrow, narrow, Slices.inProgressAll());
        assertSame(prior, merged);
    }

    public void testFullStateShortCircuitReturnsNarrowOutput() {
        ClusterState prior = baseState();
        ClusterState narrowInput = baseState();
        ClusterState narrowOutput = ClusterState.builder(narrowInput).incrementVersion().build();
        ClusterState merged = ClusterStateMerger.merge(prior, narrowInput, narrowOutput, ClusterStateFilter.FULL_STATE);
        assertSame(narrowOutput, merged);
    }

    public void testInProgressSlicePutsCustom() {
        ClusterState prior = baseState();
        ClusterState narrowInput = baseState();
        SnapshotsInProgress added = SnapshotsInProgress.EMPTY;
        ClusterState narrowOutput = ClusterState.builder(narrowInput).putCustom(SnapshotsInProgress.TYPE, added).build();

        ClusterState merged = ClusterStateMerger.merge(prior, narrowInput, narrowOutput, Slices.inProgressAll());

        assertSame(added, merged.custom(SnapshotsInProgress.TYPE));
        assertSame("unrelated state slices stay reference-equal to prior", prior.metadata(), merged.metadata());
        assertSame(prior.routingTable(), merged.routingTable());
        assertSame(prior.blocks(), merged.blocks());
        assertSame(prior.nodes(), merged.nodes());
    }

    public void testInProgressSliceRemovesCustomWhenNarrowOutputLacksIt() {
        ClusterState prior = ClusterState.builder(baseState())
            .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY)
            .build();
        ClusterState narrowInput = ClusterState.builder(baseState())
            .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY)
            .build();
        ClusterState narrowOutput = ClusterState.builder(narrowInput).removeCustom(SnapshotsInProgress.TYPE).build();

        ClusterState merged = ClusterStateMerger.merge(
            prior,
            narrowInput,
            narrowOutput,
            new InProgressSlice(EnumSet.of(InProgressType.SNAPSHOTS))
        );

        assertNull("snapshots custom was removed", merged.custom(SnapshotsInProgress.TYPE));
    }

    public void testTemplatesSliceLegacyReplaces() {
        IndexTemplateMetadata priorT = IndexTemplateMetadata.builder("t-old").patterns(java.util.List.of("logs-*")).order(1).build();
        IndexTemplateMetadata narrowT = IndexTemplateMetadata.builder("t-new").patterns(java.util.List.of("logs-*")).order(2).build();

        ClusterState prior = ClusterState.builder(baseState()).metadata(Metadata.builder().put(priorT).build()).build();
        ClusterState narrowInput = ClusterState.builder(baseState()).metadata(Metadata.builder().put(priorT).build()).build();
        ClusterState narrowOutput = ClusterState.builder(narrowInput).metadata(Metadata.builder().put(narrowT).build()).build();

        ClusterState merged = ClusterStateMerger.merge(prior, narrowInput, narrowOutput, Slices.templates(TemplateKind.LEGACY));

        assertNotNull(merged.metadata().templates().get("t-new"));
        assertNull(merged.metadata().templates().get("t-old"));
    }

    public void testTemplatesSliceComponentReplacesCustom() {
        ComponentTemplate priorCt = new ComponentTemplate(new Template(Settings.EMPTY, null, null), 1L, null);
        ComponentTemplate narrowCt = new ComponentTemplate(new Template(Settings.EMPTY, null, null), 2L, null);

        Map<String, ComponentTemplate> priorMap = new HashMap<>();
        priorMap.put("ct", priorCt);
        Map<String, ComponentTemplate> narrowMap = new HashMap<>();
        narrowMap.put("ct", narrowCt);

        ClusterState prior = ClusterState.builder(baseState())
            .metadata(Metadata.builder().putCustom(ComponentTemplateMetadata.TYPE, new ComponentTemplateMetadata(priorMap)).build())
            .build();
        ClusterState narrowInput = ClusterState.builder(baseState())
            .metadata(Metadata.builder().putCustom(ComponentTemplateMetadata.TYPE, new ComponentTemplateMetadata(priorMap)).build())
            .build();
        ClusterState narrowOutput = ClusterState.builder(baseState())
            .metadata(Metadata.builder().putCustom(ComponentTemplateMetadata.TYPE, new ComponentTemplateMetadata(narrowMap)).build())
            .build();

        ClusterState merged = ClusterStateMerger.merge(prior, narrowInput, narrowOutput, Slices.templates(TemplateKind.COMPONENT));

        ComponentTemplateMetadata mergedCt = (ComponentTemplateMetadata) merged.metadata().custom(ComponentTemplateMetadata.TYPE);
        assertNotNull(mergedCt);
        assertEquals(2L, mergedCt.componentTemplates().get("ct").version().longValue());
    }

    public void testIndexMetadataSliceNamedReplacesEntryAndPreservesOthers() {
        IndexMetadata foo = indexMeta("foo");
        IndexMetadata bar = indexMeta("bar");
        IndexMetadata fooMutated = indexMeta("foo", 7);

        Metadata priorMeta = Metadata.builder().put(foo, false).put(bar, false).build();
        ClusterState prior = ClusterState.builder(baseState()).metadata(priorMeta).build();

        Metadata narrowInputMeta = Metadata.builder().put(foo, false).put(bar, false).build();
        ClusterState narrowInput = ClusterState.builder(baseState()).metadata(narrowInputMeta).build();
        Metadata narrowOutputMeta = Metadata.builder().put(fooMutated, false).put(bar, false).build();
        ClusterState narrowOutput = ClusterState.builder(baseState()).metadata(narrowOutputMeta).build();

        ClusterState merged = ClusterStateMerger.merge(
            prior,
            narrowInput,
            narrowOutput,
            Slices.indexMetadata(IndexScope.named("foo"), IndexMetadataSection.SETTINGS)
        );

        assertEquals(7, merged.metadata().index("foo").getNumberOfReplicas());
        // bar's per-key supplier inherited from prior — same instance.
        assertSame(prior.metadata().index("bar"), merged.metadata().index("bar"));
    }

    public void testBlocksSliceIndicesReplacesPerIndex() {
        ClusterBlock blockA = new ClusterBlock(1, "block-a", false, false, false, RestStatus.OK, EnumSet.of(ClusterBlockLevel.READ));
        ClusterBlock blockB = new ClusterBlock(2, "block-b", false, false, false, RestStatus.OK, EnumSet.of(ClusterBlockLevel.READ));

        ClusterBlocks priorBlocks = ClusterBlocks.builder().addIndexBlock("foo", blockA).addIndexBlock("bar", blockA).build();
        ClusterBlocks narrowBlocks = ClusterBlocks.builder().addIndexBlock("foo", blockB).build();

        ClusterState prior = ClusterState.builder(baseState()).blocks(priorBlocks).build();
        ClusterState narrowInput = ClusterState.builder(baseState()).blocks(priorBlocks).build();
        ClusterState narrowOutput = ClusterState.builder(baseState()).blocks(narrowBlocks).build();

        ClusterState merged = ClusterStateMerger.merge(
            prior,
            narrowInput,
            narrowOutput,
            new BlocksSlice(BlockScope.indices(Set.of("foo")))
        );

        // foo gets narrow's blocks, bar keeps prior's.
        Set<ClusterBlock> mergedFoo = merged.blocks().indices().get("foo");
        assertTrue("foo gets block-b", mergedFoo.contains(blockB));
        assertFalse("foo lost block-a", mergedFoo.contains(blockA));
        assertTrue("bar keeps block-a", merged.blocks().indices().get("bar").contains(blockA));
    }

    public void testRoutingSliceNamedReplacesPerIndex() {
        IndexMetadata foo = indexMeta("foo");
        IndexMetadata bar = indexMeta("bar");
        IndexRoutingTable fooR = singleShardRouting(foo, "node-1");
        IndexRoutingTable barR = singleShardRouting(bar, "node-1");
        IndexRoutingTable fooRNew = singleShardRouting(foo, "node-2");

        RoutingTable priorRT = RoutingTable.builder().add(fooR).add(barR).build();
        RoutingTable narrowRT = RoutingTable.builder().add(fooRNew).build();

        Metadata m = Metadata.builder().put(foo, false).put(bar, false).build();
        ClusterState prior = ClusterState.builder(baseState()).metadata(m).routingTable(priorRT).build();
        ClusterState narrowInput = ClusterState.builder(baseState()).metadata(m).routingTable(priorRT).build();
        ClusterState narrowOutput = ClusterState.builder(baseState()).metadata(m).routingTable(narrowRT).build();

        ClusterState merged = ClusterStateMerger.merge(
            prior,
            narrowInput,
            narrowOutput,
            new RoutingSlice(IndexScope.named("foo"))
        );

        // foo's routing came from narrow, bar's is unchanged.
        assertSame(fooRNew, merged.routingTable().index("foo"));
        assertSame(barR, merged.routingTable().index("bar"));
    }

    public void testReturnsPriorWhenNoEffectiveChangesAccumulated() {
        // narrowInput != narrowOutput by identity (forces past the first short-circuit), but
        // the InProgressSlice filter touches no customs in either side — merger accumulates
        // nothing, must hand back prior.
        ClusterState prior = baseState();
        ClusterState narrowInput = baseState();
        ClusterState narrowOutput = ClusterState.builder(narrowInput).incrementVersion().build();

        ClusterState merged = ClusterStateMerger.merge(
            prior,
            narrowInput,
            narrowOutput,
            new InProgressSlice(EnumSet.of(InProgressType.SNAPSHOTS))
        );
        assertSame(prior, merged);
    }

    public void testUnsupportedSliceThrows() {
        ClusterState prior = baseState();
        ClusterState narrow = ClusterState.builder(prior).incrementVersion().build();
        expectThrows(
            UnsupportedOperationException.class,
            () -> ClusterStateMerger.merge(prior, prior, narrow, Slices.allNodes())
        );
    }

    public void testIndexMetadataSliceAllScopeIsRejectedInV1() {
        ClusterState prior = baseState();
        ClusterState narrow = ClusterState.builder(prior).incrementVersion().build();
        expectThrows(
            UnsupportedOperationException.class,
            () -> ClusterStateMerger.merge(
                prior,
                prior,
                narrow,
                Slices.indexMetadata(IndexScope.ALL, IndexMetadataSection.SETTINGS)
            )
        );
    }

    private static ClusterState baseState() {
        return ClusterState.builder(ClusterName.DEFAULT)
            .version(1L)
            .stateUUID("uuid-1")
            .metadata(Metadata.EMPTY_METADATA)
            .routingTable(RoutingTable.EMPTY_ROUTING_TABLE)
            .nodes(DiscoveryNodes.EMPTY_NODES)
            .blocks(ClusterBlocks.EMPTY_CLUSTER_BLOCK)
            .build();
    }

    private static IndexMetadata indexMeta(String name) {
        return indexMeta(name, 0);
    }

    private static IndexMetadata indexMeta(String name, int replicas) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, replicas)
                    .build()
            )
            .build();
    }

    private static IndexRoutingTable singleShardRouting(IndexMetadata index, String nodeId) {
        Index idx = index.getIndex();
        ShardRouting shard = TestShardRouting.newShardRouting(
            new org.opensearch.core.index.shard.ShardId(idx, 0),
            nodeId,
            true,
            org.opensearch.cluster.routing.ShardRoutingState.STARTED
        );
        return IndexRoutingTable.builder(idx).addShard(shard).build();
    }
}
