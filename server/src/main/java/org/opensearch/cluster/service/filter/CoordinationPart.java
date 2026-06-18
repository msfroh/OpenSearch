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
 * The parts of {@code metadata.coordinationMetadata} an operation can ask for
 * individually. Voting-config-exclusions ops only need the exclusion list; term-version
 * fetches only need {@link #TERM}; full coordination details are only needed by node-set
 * coordination machinery.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public enum CoordinationPart {

    /** Last-committed voting configuration. */
    VOTING_CONFIG,

    /** Voting-config exclusions. */
    VOTING_CONFIG_EXCLUSIONS,

    /** Current term. */
    TERM,

    /** Last-committed configuration (distinct from the live voting config). */
    COMMITTED
}
