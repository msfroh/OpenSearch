/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.cluster.coordination.ClusterStatePublisher;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.cluster.service.ClusterStatePersistence;
import org.opensearch.cluster.service.ClusterStateSupplier;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.env.Environment;

import java.nio.file.Path;

/**
 * Bundles the {@link FileClusterStateSupplier} and {@link FileClusterStatePublisher} so a
 * single plugin instance can register both halves with the {@code ClusterManagerService}.
 */
public final class FileClusterStatePersistence implements ClusterStatePersistence {

    private final FileClusterStateSupplier supplier;
    private final FileClusterStatePublisher publisher;

    public FileClusterStatePersistence(Environment environment) {
        Path[] dataDirs = environment.dataFiles();
        Path baseDir = dataDirs.length == 0 ? environment.logsDir() : dataDirs[0];
        Path stateDir = baseDir.resolve("pluggable-cluster-state");
        this.supplier = new FileClusterStateSupplier(stateDir);
        this.publisher = new FileClusterStatePublisher(stateDir, supplier);
    }

    /**
     * Plugged in from {@code Plugin.createComponents} once the node-level
     * {@link NamedWriteableRegistry} and {@link ClusterService} are available. The
     * supplier needs both to reconstruct a {@code ClusterState} from the binary state
     * file on disk.
     */
    void bindNodeServices(NamedWriteableRegistry registry, ClusterService clusterService) {
        supplier.setNamedWriteableRegistry(registry);
        supplier.setClusterService(clusterService);
    }

    @Override
    public ClusterStateSupplier getClusterStateSupplier() {
        return supplier;
    }

    @Override
    public ClusterStatePublisher getClusterStatePublisher() {
        return publisher;
    }
}
