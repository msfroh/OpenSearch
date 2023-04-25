/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.pipeline.common;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.document.DocumentField;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;

import java.util.List;
import java.util.Map;

public class AppendQueryResponseProcessor extends AbstractProcessor implements SearchResponseProcessor {
    public static final String TYPE = "append_query";

    protected AppendQueryResponseProcessor(String tag, String description) {
        super(tag, description);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SearchResponse processResponse(SearchRequest request, SearchResponse response) {
        if (response.getHits() != null && response.getHits().getHits().length > 0 && request.source() != null) {
            SearchHits hits = response.getHits();
            SearchHit searchHit = hits.getAt(0);
            DocumentField queryField = new DocumentField("query", List.of(request.source().query().toString()));
            searchHit.setDocumentField("query", queryField);
        }
        return response;
    }

    static class Factory implements Processor.Factory {

        @Override
        public Processor create(Map<String, Processor.Factory> processorFactories, String tag, String description, Map<String, Object> config) throws Exception {
            return new AppendQueryResponseProcessor(tag, description);
        }
    }
}
