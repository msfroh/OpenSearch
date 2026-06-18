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
 * Requests one or more template registries — legacy, component, composable, or any
 * combination. Most template write operations need all three for overlap and dependency
 * validation; pure-read operations (the {@code TransportGet*TemplateAction} family)
 * need only one.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record TemplatesSlice(Set<TemplateKind> kinds) implements ClusterStateFilter {

    public TemplatesSlice {
        if (kinds == null || kinds.isEmpty()) {
            throw new IllegalArgumentException("TemplatesSlice kinds must be non-empty");
        }
        kinds = Set.copyOf(EnumSet.copyOf(kinds));
    }
}
