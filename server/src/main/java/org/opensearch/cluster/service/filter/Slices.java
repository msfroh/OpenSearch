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
 * Terse, varargs-friendly factories for the slice families. Use this when you'd
 * otherwise be typing out {@code new IndexMetadataSlice(IndexScope.named(...), EnumSet.of(...))}
 * by hand.
 *
 * <pre>
 *   ClusterStateFilter f = ClusterStateFilter.union(
 *       Slices.clusterMetadata(),
 *       Slices.indexMetadata(IndexScope.named(targets), IndexMetadataSection.MAPPINGS, IndexMetadataSection.SETTINGS),
 *       Slices.routing(IndexScope.named(targets)),
 *       Slices.blocks(BlockScope.globalAndIndices(targets)),
 *       Slices.nodes(NodeProjection.MIN_MAX_VERSION),
 *       Slices.inProgressAll()
 *   );
 * </pre>
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public final class Slices {

    private Slices() {}

    /** {@link ClusterMetadataSlice#INSTANCE}. */
    public static ClusterMetadataSlice clusterMetadata() {
        return ClusterMetadataSlice.INSTANCE;
    }

    /** @see IndexMetadataSlice */
    public static IndexMetadataSlice indexMetadata(IndexScope scope, IndexMetadataSection first, IndexMetadataSection... rest) {
        return new IndexMetadataSlice(scope, EnumSet.of(first, rest));
    }

    /** @see IndexMetadataSlice */
    public static IndexMetadataSlice indexMetadata(IndexScope scope, Set<IndexMetadataSection> sections) {
        return new IndexMetadataSlice(scope, sections);
    }

    /** Full {@link org.opensearch.cluster.metadata.IndexMetadata} for every section. */
    public static IndexMetadataSlice fullIndexMetadata(IndexScope scope) {
        return new IndexMetadataSlice(scope, EnumSet.allOf(IndexMetadataSection.class));
    }

    /** @see RoutingSlice */
    public static RoutingSlice routing(IndexScope scope) {
        return new RoutingSlice(scope);
    }

    /** Full {@link org.opensearch.cluster.routing.RoutingTable}. */
    public static RoutingSlice routingAll() {
        return new RoutingSlice(IndexScope.ALL);
    }

    /** @see BlocksSlice */
    public static BlocksSlice blocks(BlockScope scope) {
        return new BlocksSlice(scope);
    }

    /** Just the global block set. */
    public static BlocksSlice globalBlocks() {
        return new BlocksSlice(BlockScope.GLOBAL_ONLY);
    }

    /** Every block in {@link org.opensearch.cluster.block.ClusterBlocks}. */
    public static BlocksSlice allBlocks() {
        return new BlocksSlice(BlockScope.ALL);
    }

    /** @see NodesSlice */
    public static NodesSlice nodes(NodeProjection projection) {
        return new NodesSlice(projection);
    }

    /** Full {@link org.opensearch.cluster.node.DiscoveryNodes}. */
    public static NodesSlice allNodes() {
        return new NodesSlice(NodeProjection.FULL);
    }

    /** @see TemplatesSlice */
    public static TemplatesSlice templates(TemplateKind first, TemplateKind... rest) {
        return new TemplatesSlice(EnumSet.of(first, rest));
    }

    /** @see TemplatesSlice */
    public static TemplatesSlice templates(Set<TemplateKind> kinds) {
        return new TemplatesSlice(kinds);
    }

    /** Every template registry. */
    public static TemplatesSlice allTemplates() {
        return new TemplatesSlice(EnumSet.allOf(TemplateKind.class));
    }

    /** @see MetadataCustomSlice */
    public static MetadataCustomSlice metadataCustoms(String first, String... rest) {
        Set<String> typeNames = new java.util.LinkedHashSet<>();
        typeNames.add(first);
        for (String name : rest) {
            typeNames.add(name);
        }
        return new MetadataCustomSlice(typeNames);
    }

    /** @see MetadataCustomSlice */
    public static MetadataCustomSlice metadataCustoms(Set<String> typeNames) {
        return new MetadataCustomSlice(typeNames);
    }

    /** @see InProgressSlice */
    public static InProgressSlice inProgress(InProgressType first, InProgressType... rest) {
        return new InProgressSlice(EnumSet.of(first, rest));
    }

    /** All four in-progress customs — the typical ask for snapshot/repo writes. */
    public static InProgressSlice inProgressAll() {
        return new InProgressSlice(EnumSet.allOf(InProgressType.class));
    }

    /** @see CoordinationSlice */
    public static CoordinationSlice coordination(CoordinationPart first, CoordinationPart... rest) {
        return new CoordinationSlice(EnumSet.of(first, rest));
    }

    /** All coordination parts. */
    public static CoordinationSlice allCoordination() {
        return new CoordinationSlice(EnumSet.allOf(CoordinationPart.class));
    }
}
