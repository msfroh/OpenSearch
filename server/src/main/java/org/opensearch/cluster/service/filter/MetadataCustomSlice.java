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
 * Requests one or more {@link org.opensearch.cluster.metadata.Metadata.Custom} entries
 * by their registered type names. Examples (from the catalog):
 * <ul>
 *   <li>{@code "data_stream"} — {@code DataStreamMetadata}</li>
 *   <li>{@code "views"} — {@code ViewsMetadata}</li>
 *   <li>{@code "ingest"} — {@code IngestMetadata}</li>
 *   <li>{@code "search_pipeline"} — {@code SearchPipelineMetadata}</li>
 *   <li>{@code "stored_scripts"} — {@code ScriptMetadata}</li>
 *   <li>{@code "repositories"} — {@code RepositoriesMetadata}</li>
 *   <li>{@code "decommissionedAttribute"} — {@code DecommissionAttributeMetadata}</li>
 *   <li>{@code "weighted_shard_routing"} — {@code WeightedRoutingMetadata}</li>
 *   <li>{@code "persistent_tasks"} — {@code PersistentTasksCustomMetadata}</li>
 *   <li>{@code "index-graveyard"} — {@code IndexGraveyard}</li>
 * </ul>
 * The slice does not validate the names — unknown types simply yield nothing from the
 * supplier.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record MetadataCustomSlice(Set<String> typeNames) implements ClusterStateFilter {

    public MetadataCustomSlice {
        if (typeNames == null || typeNames.isEmpty()) {
            throw new IllegalArgumentException("MetadataCustomSlice typeNames must be non-empty");
        }
        typeNames = Set.copyOf(typeNames);
    }
}
