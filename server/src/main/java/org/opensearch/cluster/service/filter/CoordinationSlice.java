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
 * Requests one or more {@link CoordinationPart}s from
 * {@code metadata.coordinationMetadata}. Tiny by construction — most suppliers will
 * service these from a single in-memory record.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record CoordinationSlice(Set<CoordinationPart> parts) implements ClusterStateFilter {

    public CoordinationSlice {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("CoordinationSlice parts must be non-empty");
        }
        parts = Set.copyOf(EnumSet.copyOf(parts));
    }
}
