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
 * The sentinel filter that requests the full {@link org.opensearch.cluster.ClusterState}.
 * <p>
 * Use {@link ClusterStateFilter#FULL_STATE} rather than constructing instances directly;
 * this is a record so equality is identity-by-shape and there is no useful state to
 * carry.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record FullStateFilter() implements ClusterStateFilter {

    static final FullStateFilter INSTANCE = new FullStateFilter();

    @Override
    public boolean isFullState() {
        return true;
    }

    @Override
    public String toString() {
        return "FULL_STATE";
    }
}
