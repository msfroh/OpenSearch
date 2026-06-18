/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.action.admin.indices.mapping.get;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.clustermanager.info.TransportClusterInfoAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.DataStreamMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.cluster.service.filter.BlockScope;
import org.opensearch.cluster.service.filter.ClusterStateFilter;
import org.opensearch.cluster.service.filter.IndexMetadataSection;
import org.opensearch.cluster.service.filter.IndexScope;
import org.opensearch.cluster.service.filter.Slices;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.indices.IndicesService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transport action to get field mappings.
 *
 * @opensearch.internal
 */
public class TransportGetMappingsAction extends TransportClusterInfoAction<GetMappingsRequest, GetMappingsResponse> {

    private static final Logger logger = LogManager.getLogger(TransportGetMappingsAction.class);

    private final IndicesService indicesService;

    @Inject
    public TransportGetMappingsAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        IndicesService indicesService
    ) {
        super(
            GetMappingsAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            GetMappingsRequest::new,
            indexNameExpressionResolver
        );
        this.indicesService = indicesService;
    }

    @Override
    protected GetMappingsResponse read(StreamInput in) throws IOException {
        return new GetMappingsResponse(in);
    }

    @Override
    protected ClusterStateFilter requiredState(GetMappingsRequest request) {
        // Index resolution always needs the full SETTINGS+ALIASES+STATE lookup across all
        // indices (plus the DataStreamMetadata custom). When the request's indices resolve
        // cleanly against the local applier state we narrow two slices to the resolved
        // set: the per-index METADATA_READ block check and per-index MAPPINGS (the actual
        // response payload). The resolution is best-effort — if it throws, we fall back
        // to an ALL-scope filter which is correct but coarser.
        Optional<Set<String>> resolved = tryResolveConcreteIndices(request);
        List<ClusterStateFilter> slices = new ArrayList<>();
        slices.add(
            Slices.indexMetadata(
                IndexScope.ALL,
                IndexMetadataSection.SETTINGS,
                IndexMetadataSection.ALIASES,
                IndexMetadataSection.STATE
            )
        );
        slices.add(Slices.metadataCustoms(DataStreamMetadata.TYPE));
        if (resolved.isPresent()) {
            slices.add(Slices.blocks(BlockScope.indices(resolved.get())));
            slices.add(Slices.indexMetadata(IndexScope.named(resolved.get()), IndexMetadataSection.MAPPINGS));
        } else {
            slices.add(Slices.allBlocks());
            slices.add(Slices.indexMetadata(IndexScope.ALL, IndexMetadataSection.MAPPINGS));
        }
        return ClusterStateFilter.union(slices);
    }

    @Override
    protected void doClusterManagerOperation(
        final GetMappingsRequest request,
        String[] concreteIndices,
        final ClusterState state,
        final ActionListener<GetMappingsResponse> listener
    ) {
        logger.trace("serving getMapping request based on version {}", state.version());
        try {
            final Map<String, MappingMetadata> result = state.metadata().findMappings(concreteIndices, indicesService.getFieldFilter());
            listener.onResponse(new GetMappingsResponse(result));
        } catch (IOException e) {
            listener.onFailure(e);
        }
    }
}
