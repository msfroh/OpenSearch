/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.hackathon.dynamodbstore;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.bytes.BytesArray;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ingest.ConfigurationUtils;
import org.opensearch.search.SearchHit;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DynamoDBStoreResponseProcessor implements SearchResponseProcessor {
    public static final String TYPE = "dynamodb_source";
    private final String tag;
    private final String description;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final String primaryKeyAttribute;

    private DynamoDBStoreResponseProcessor(String tag, String description, DynamoDbClient dynamoDbClient, String tableName, String primaryKeyAttribute) {
        this.tag = tag;
        this.description = description;
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.primaryKeyAttribute = primaryKeyAttribute;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public SearchResponse processResponse(SearchRequest request, SearchResponse response) throws Exception {
        if (response.getHits() == null) {
            return response;
        }
        List<String> hitIds = new ArrayList<>(response.getHits().getHits().length);
        Map<String, SearchHit> searchHitsById = new HashMap<>();
        for (SearchHit searchHit : response.getHits()) {
            hitIds.add(searchHit.getId());
            searchHitsById.put(searchHit.getId(), searchHit);
        }
        // TODO: Split into batches of 100
        List<Map<String, AttributeValue>> keysToGet = new ArrayList<>(hitIds.size());
        for (String hitId : hitIds) {
            keysToGet.add(Map.of(primaryKeyAttribute, AttributeValue.fromS(hitId)));
        }
        KeysAndAttributes keysAndAttributes = KeysAndAttributes.builder()
            .keys(keysToGet)
            .build();
        BatchGetItemRequest batchGetItemRequest = BatchGetItemRequest.builder()
            .requestItems(Map.of(tableName, keysAndAttributes))
            .build();
        BatchGetItemResponse batchGetItemResponse = SocketAccess.doPrivileged(() -> dynamoDbClient.batchGetItem(batchGetItemRequest));
        List<Map<String, AttributeValue>> docs = batchGetItemResponse.responses().get(tableName);
        for (Map<String, AttributeValue> doc : docs) {
            copyDocToSearchHit(doc, searchHitsById.get(doc.get(primaryKeyAttribute).s()));
        }
        return response;
    }

    private static void copyDocToSearchHit(Map<String, AttributeValue> dynamoDbDoc, SearchHit searchHit) throws IOException {
        Map<String, Object> primitiveMap = toPrimitiveMap(dynamoDbDoc);
        XContentBuilder builder = XContentBuilder.builder(JsonXContent.jsonXContent);
        builder.map(primitiveMap);
        searchHit.sourceRef(BytesReference.bytes(builder));
    }

    private static Map<String, Object> toPrimitiveMap(Map<String, AttributeValue> dynamoDbMap) {
        Map<String, Object> primitiveMap = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> entry : dynamoDbMap.entrySet()) {
            Object value = toPrimitiveValue(entry.getValue());
            if (value != null) {
                primitiveMap.put(entry.getKey(), value);
            }
        }
        return primitiveMap;
    }

    private static Object toPrimitiveValue(AttributeValue dynamoDbValue) {
        switch (dynamoDbValue.type()) {
            case S:
                return dynamoDbValue.s();
            case N:
                return dynamoDbValue.n();
            case B:
                return new BytesArray(dynamoDbValue.b().asByteArray());
            case SS:
                return dynamoDbValue.ss();
            case NS:
                return dynamoDbValue.ns();
            case BS:
                List<BytesArray> bytesArrays = new ArrayList<>();
                for (SdkBytes sdkBytes : dynamoDbValue.bs()) {
                    bytesArrays.add(new BytesArray(sdkBytes.asByteArray()));
                }
                return bytesArrays;
            case M:
                return toPrimitiveMap(dynamoDbValue.m());
            case L:
                List<Object> values = new ArrayList<>();
                for (AttributeValue subValue : dynamoDbValue.l()) {
                    values.add(toPrimitiveValue(subValue));
                }
                return values;
            case BOOL:
                return dynamoDbValue.bool();
            default:
                return null;
        }
    }

    public static class Factory implements Processor.Factory<SearchResponseProcessor> {

        public Factory() {
            setDefaultAwsProfilePath();
        }

        // Aws v2 sdk tries to load a default profile from home path which is restricted. Hence, setting these to random
        // valid paths.
        @SuppressForbidden(reason = "Need to provide this override to v2 SDK so that path does not default to home path")
        static void setDefaultAwsProfilePath() {
            if (ProfileFileSystemSetting.AWS_SHARED_CREDENTIALS_FILE.getStringValue().isEmpty()) {
                SocketAccess.doPrivileged(
                    () -> System.setProperty(
                        ProfileFileSystemSetting.AWS_SHARED_CREDENTIALS_FILE.property(),
                        System.getProperty("opensearch.path.conf")
                    )
                );
            }
            if (ProfileFileSystemSetting.AWS_CONFIG_FILE.getStringValue().isEmpty()) {
                SocketAccess.doPrivileged(
                    () -> System.setProperty(ProfileFileSystemSetting.AWS_CONFIG_FILE.property(), System.getProperty("opensearch.path.conf"))
                );
            }
        }

        public SearchResponseProcessor create(Map<String, Processor.Factory<SearchResponseProcessor>> processorFactories, String tag, String description, Map<String, Object> config) throws Exception {
            Map<String, Object> credentialsMap = XContentHelper.convertToMap(JsonXContent.jsonXContent, CREDENTIALS_JSON, false);

            AwsSessionCredentials sessionCredentials = AwsSessionCredentials.create(credentialsMap.get("AccessKeyId").toString(), credentialsMap.get("SecretAccessKey").toString(), credentialsMap.get("SessionToken").toString());
            StaticCredentialsProvider staticCredentialsProvider = StaticCredentialsProvider.create(sessionCredentials);
            String region = ConfigurationUtils.readStringProperty(TYPE, tag, config, "region");
            DynamoDbClient dynamoDbClient = SocketAccess.doPrivileged((PrivilegedAction<DynamoDbClient>)() -> DynamoDbClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration.builder().build())
                .credentialsProvider(staticCredentialsProvider)
                .region(Region.of(region))
                .build());

            String tableName = ConfigurationUtils.readStringProperty(TYPE, tag, config, "table_name");
            String primaryKeyAttribute = ConfigurationUtils.readStringProperty(TYPE, tag, config, "pk_attribute");
            return new DynamoDBStoreResponseProcessor(tag, description, dynamoDbClient, tableName, primaryKeyAttribute);
        }
    }

    // Put session credentials as a JSON string here.
    // TODO: Pass credentials a better way, like via the keystore.
    private static final String CREDENTIALS_JSON = "";
}
