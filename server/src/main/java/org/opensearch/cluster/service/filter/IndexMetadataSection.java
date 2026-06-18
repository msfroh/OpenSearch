/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.service.filter;

import org.opensearch.common.annotation.ExperimentalApi;

/**
 * The independently-loadable sections of {@link org.opensearch.cluster.metadata.IndexMetadata}.
 * <p>
 * Operations that need only one or two sections of per-index metadata (e.g. mappings for
 * {@code TransportGetMappingsAction}, settings for {@code TransportGetSettingsAction})
 * declare it here so a supplier with a section-keyed backing store can fetch only what's
 * asked for. Suppliers that store {@code IndexMetadata} monolithically should ignore the
 * section set and return the full object.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public enum IndexMetadataSection {
    SETTINGS,
    MAPPINGS,
    ALIASES,
    CUSTOM_DATA,
    STATE,
    INGESTION_STATUS,
    IN_SYNC_ALLOCATION_IDS,
    PRIMARY_TERMS,
    ROLLOVER_INFO
}
