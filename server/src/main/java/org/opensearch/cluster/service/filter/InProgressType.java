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
 * The four cluster-level in-progress operation customs that snapshot and repository
 * writes consult for concurrency coordination.
 * <p>
 * Per the catalog: nearly every snapshot/repo write reads all four. Granular filtering
 * here saves nothing in practice, but the type is split out so the supplier knows the
 * shape of what's being asked for.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public enum InProgressType {

    /** {@code customs[snapshots]} — {@code SnapshotsInProgress}. */
    SNAPSHOTS,

    /** {@code customs[snapshotDeletions]} — {@code SnapshotDeletionsInProgress}. */
    SNAPSHOT_DELETIONS,

    /** {@code customs[restoreInProgress]} — {@code RestoreInProgress}. */
    RESTORE,

    /** {@code customs[repositoryCleanup]} — {@code RepositoryCleanupInProgress}. */
    REPOSITORY_CLEANUP
}
