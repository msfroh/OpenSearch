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
 * Requests a slice of per-index metadata.
 * <p>
 * Examples:
 * <pre>
 *   // Mappings for a single index — what TransportGetMappingsAction wants.
 *   Slices.indexMetadata(IndexScope.named("foo"), EnumSet.of(IndexMetadataSection.MAPPINGS));
 *
 *   // Full IndexMetadata for several indices — what TransportRestoreSnapshotAction wants.
 *   Slices.indexMetadata(IndexScope.named(restored), EnumSet.allOf(IndexMetadataSection.class));
 *
 *   // Every index, settings only — for a hypothetical settings-survey tool.
 *   Slices.indexMetadata(IndexScope.ALL, EnumSet.of(IndexMetadataSection.SETTINGS));
 * </pre>
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record IndexMetadataSlice(IndexScope scope, Set<IndexMetadataSection> sections) implements ClusterStateFilter {

    public IndexMetadataSlice {
        if (scope == null) {
            throw new IllegalArgumentException("IndexMetadataSlice scope must be non-null");
        }
        if (sections == null || sections.isEmpty()) {
            throw new IllegalArgumentException("IndexMetadataSlice sections must be non-empty");
        }
        // Defensive copy into an immutable EnumSet-backed view so callers can't mutate
        // the slice after construction.
        sections = Set.copyOf(EnumSet.copyOf(sections));
    }
}
