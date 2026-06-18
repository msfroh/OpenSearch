/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Declarative filters describing the subset of {@link org.opensearch.cluster.ClusterState}
 * an operation needs to read.
 * <p>
 * A {@link org.opensearch.cluster.service.filter.ClusterStateFilter} is a composite of
 * one or more <em>slices</em>. Each slice describes a single, narrow dimension of state
 * (per-index metadata sections, the routing table, in-progress snapshots, etc.); a
 * union of slices says "give me state that covers all of these." Suppliers may always
 * return a superset of the requested state — up to the full {@code ClusterState} — so
 * operations must filter their own results and never infer the absence of state from a
 * narrowed return.
 * <p>
 * The full slice taxonomy and the rationale behind it lives in
 * {@code cluster_manager_operations.md} at the repository root.
 */
package org.opensearch.cluster.service.filter;
