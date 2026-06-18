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
 * The independently-loadable kinds of index template metadata.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public enum TemplateKind {

    /**
     * Legacy {@link org.opensearch.cluster.metadata.IndexTemplateMetadata}, lives at
     * {@code metadata.templates}.
     */
    LEGACY,

    /**
     * v2 component templates, live as a {@code Metadata.Custom}.
     */
    COMPONENT,

    /**
     * v2 composable index templates, live as a {@code Metadata.Custom}.
     */
    COMPOSABLE
}
