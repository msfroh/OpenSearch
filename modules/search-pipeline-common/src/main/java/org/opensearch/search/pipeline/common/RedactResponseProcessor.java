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
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.document.DocumentField;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.core.xcontent.MediaType;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ingest.ConfigurationUtils;
import org.opensearch.search.SearchHit;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RedactResponseProcessor extends AbstractProcessor implements SearchResponseProcessor {
    private final List<String> targets;

    public static final String TYPE = "redact";

    public RedactResponseProcessor(String tag, String description, List<String> targets) {
        super(tag, description);
        this.targets = targets;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public List<String> getTargets() {
        return targets;
    }

    @Override
    public SearchResponse processResponse(SearchRequest request, SearchResponse response) throws Exception {

        SearchHit[] hits = response.getHits().getHits();
        for (SearchHit hit : hits) {
            Map<String, DocumentField> fields = hit.getFields();
            for (String key : new ArrayList<>(fields.keySet())) {
                DocumentField field = fields.get(key);
                if (field.getValues().size() == 1) {
                    String stringValue = field.getValues().get(0).toString();
                    String replacement = redact(stringValue, targets);
                    if (!Objects.equals(stringValue, replacement)) {
                        hit.setDocumentField(key, new DocumentField(key, List.of(stringValue)));
                    }
                }
            }
            BytesReference sourceRef = hit.getSourceRef();
            Tuple<? extends MediaType, Map<String, Object>> typeAndSourceMap =
                XContentHelper.convertToMap(sourceRef, false, (MediaType) null);

            Map<String, Object> sourceAsMap = typeAndSourceMap.v2();
            Map<String, Object> clonedMap = null;
            for (String key : sourceAsMap.keySet()) {
                Object val = sourceAsMap.get(key);
                String stringValue = val.toString();
                String replaced = redact(stringValue, targets);
                if (!Objects.equals(replaced, stringValue)) {
                    if (clonedMap == null) {
                        clonedMap = new LinkedHashMap<>(sourceAsMap);
                    }
                    clonedMap.put(key, replaced);
                }
            }
            if (clonedMap != null) {
                XContentBuilder builder = XContentBuilder.builder(typeAndSourceMap.v1().xContent());
                builder.map(clonedMap);
                hit.sourceRef(BytesReference.bytes(builder));
            }
        }
        return response;
    }

    private static String redact(String input, Collection<String> forbiddenWords) {
        for (String word : forbiddenWords) {
            if (input.contains(word)) {
                String replacement = "*".repeat(word.length());
                input = input.replace(word, replacement);
            }
        }
        return input;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public RedactResponseProcessor create(Map<String, Processor.Factory> processorFactories, String tag, String description, Map<String, Object> config) throws Exception {
            List<String> targets = ConfigurationUtils.readList(TYPE, tag, config, "target");
            return new RedactResponseProcessor(tag, description, targets);
        }
    }
}
