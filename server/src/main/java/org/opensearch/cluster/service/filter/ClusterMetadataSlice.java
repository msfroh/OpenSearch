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
 * Requests the cluster-wide metadata fields that don't fit any other slice: cluster
 * name, version, term, state UUID, persistent and transient settings, and the
 * hashes-of-consistent-settings table.
 * <p>
 * These are tiny and almost every cluster-manager operation needs them at least
 * indirectly (defaults resolution, version checks). Suppliers should treat this slice
 * as effectively free.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record ClusterMetadataSlice() implements ClusterStateFilter {

    /**
     * Singleton — there is no state to vary.
     */
    public static final ClusterMetadataSlice INSTANCE = new ClusterMetadataSlice();
}
