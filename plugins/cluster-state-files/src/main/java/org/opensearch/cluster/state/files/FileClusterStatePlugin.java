/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.cluster.service.ClusterStatePersistence;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.DiscoveryPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Proof-of-concept {@link DiscoveryPlugin} that registers a file-based
 * {@link ClusterStatePersistence}. With this plugin installed the cluster manager will
 * read cluster state from on-disk files (cached by manifest mtime) and persist accepted
 * updates as a directory tree of files under the node's data directory.
 */
public final class FileClusterStatePlugin extends Plugin implements DiscoveryPlugin {

    private final FileClusterStatePersistence persistence;

    public FileClusterStatePlugin(Settings settings, Path configPath) {
        this.persistence = new FileClusterStatePersistence(new Environment(settings, configPath));
    }

    @Override
    public Optional<ClusterStatePersistence> getClusterStatePersistence() {
        return Optional.of(persistence);
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        // Wire the registry and cluster service into the supplier so it can rebuild
        // ClusterState from the binary state file when get() is called after a restart
        // or by another process.
        persistence.bindNodeServices(namedWriteableRegistry, clusterService);
        return Collections.emptyList();
    }
}
