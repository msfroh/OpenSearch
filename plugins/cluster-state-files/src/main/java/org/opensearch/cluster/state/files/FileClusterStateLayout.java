/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

/**
 * Path and JSON-key constants shared between {@link FileClusterStatePublisher} and
 * {@link FileClusterStateSupplier}.
 */
final class FileClusterStateLayout {

    static final String CURRENT_MANIFEST = "current-manifest.json";
    static final String CLUSTER_STATE_FILE = "cluster-state.json";
    static final String INDICES_DIR = "indices";
    static final String ROUTING_DIR = "routing";
    static final String NODE_ROUTING_DIR = "nodes";
    static final String INDEX_ROUTING_DIR = "indices";

    static final String MANIFEST_STATE_FILE_KEY = "state_file";

    private FileClusterStateLayout() {}
}
