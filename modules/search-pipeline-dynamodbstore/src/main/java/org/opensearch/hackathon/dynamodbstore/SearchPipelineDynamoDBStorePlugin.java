/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.hackathon.dynamodbstore;

import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPipelinePlugin;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;

import java.util.Map;

public class SearchPipelineDynamoDBStorePlugin extends Plugin implements SearchPipelinePlugin {

    /**
     * No constructor needed, but build complains if we don't have a constructor with JavaDoc.
     */
    public SearchPipelineDynamoDBStorePlugin() {}


    @Override
    public Map<String, Processor.Factory<SearchResponseProcessor>> getResponseProcessors(Processor.Parameters parameters) {
        return Map.of(DynamoDBStoreResponseProcessor.TYPE, new DynamoDBStoreResponseProcessor.Factory());
    }
}
