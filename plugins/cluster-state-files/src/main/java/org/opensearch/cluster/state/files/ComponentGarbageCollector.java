/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.state.files;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.opensearch.cluster.state.files.FileClusterStateLayout.COMPONENTS_DIR;
import static org.opensearch.cluster.state.files.FileClusterStateLayout.MANIFESTS_DIR;

/**
 * Reaps stale per-component files and old versioned manifests under a state directory.
 * <p>
 * The publisher's content-addressed layout means an unchanged component is referenced by
 * the same filename across successive manifests, but a component that changes only once
 * leaves the old hashed file behind forever. This collector closes the loop:
 * <ol>
 *   <li>List versioned manifests under {@code manifests/} and keep the
 *       {@link #retentionCount} most-recently-modified.</li>
 *   <li>Delete the older versioned manifests.</li>
 *   <li>Parse every retained manifest, union all referenced component filenames into a
 *       "live" set.</li>
 *   <li>Walk {@code components/} (and its subdirectories) and delete any file not in the
 *       live set.</li>
 * </ol>
 * <p>
 * The retention window is intentionally small but configurable on construction; the
 * sweep is invoked by {@link FileClusterStatePublisher} at the end of each publication.
 * The pass is conservative: only files definitely unreferenced by any kept manifest are
 * removed, and any I/O error during enumeration is logged and swallowed (GC is best-effort
 * — losing one round of cleanup is harmless, dropping a referenced file is not).
 */
final class ComponentGarbageCollector {

    private static final Logger logger = LogManager.getLogger(ComponentGarbageCollector.class);

    /** Default number of versioned manifests to keep. */
    static final int DEFAULT_RETENTION = 10;

    private final int retentionCount;

    ComponentGarbageCollector(int retentionCount) {
        if (retentionCount < 1) {
            throw new IllegalArgumentException("retentionCount must be at least 1, was " + retentionCount);
        }
        this.retentionCount = retentionCount;
    }

    /** Runs one GC pass over {@code stateDir}. Errors are logged and swallowed. */
    void sweep(Path stateDir) {
        Path manifestsDir = stateDir.resolve(MANIFESTS_DIR);
        if (Files.isDirectory(manifestsDir) == false) {
            return;
        }

        List<Path> retained;
        try {
            retained = pruneOldManifests(manifestsDir);
        } catch (IOException e) {
            logger.warn("failed to enumerate manifests under {}", manifestsDir, e);
            return;
        }
        if (retained.isEmpty()) {
            return;
        }

        Set<String> live;
        try {
            live = collectLiveComponentPaths(retained);
        } catch (IOException e) {
            logger.warn("failed to read manifests during GC; skipping component sweep", e);
            return;
        }

        Path componentsDir = stateDir.resolve(COMPONENTS_DIR);
        try {
            sweepComponentsDir(componentsDir, live);
        } catch (IOException e) {
            logger.warn("failed to enumerate component files under {}", componentsDir, e);
        }
    }

    /** Deletes versioned manifests outside the retention window. Returns the retained ones. */
    private List<Path> pruneOldManifests(Path manifestsDir) throws IOException {
        List<Path> manifests = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(manifestsDir, "manifest-*.json")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    manifests.add(p);
                }
            }
        }
        if (manifests.size() <= retentionCount) {
            return manifests;
        }
        // Sort newest-first by mtime. Stable ordering on equal mtimes keeps the sort deterministic.
        manifests.sort(Comparator.comparing(ComponentGarbageCollector::mtimeOrZero).reversed());

        List<Path> retained = manifests.subList(0, retentionCount);
        for (Path expired : manifests.subList(retentionCount, manifests.size())) {
            try {
                Files.deleteIfExists(expired);
            } catch (IOException e) {
                // Lost a single delete — not fatal; next sweep will retry.
                logger.warn("failed to delete expired manifest {}", expired, e);
            }
        }
        return retained;
    }

    /** Parses each retained manifest and unions every component path it references. */
    private static Set<String> collectLiveComponentPaths(List<Path> manifests) throws IOException {
        Set<String> live = new HashSet<>();
        for (Path manifest : manifests) {
            ComponentManifest m = ComponentManifest.read(manifest);
            live.addAll(m.components().values());
            live.addAll(m.indices().values());
            live.addAll(m.stateCustoms().values());
            live.addAll(m.metadataCustoms().values());
        }
        return live;
    }

    /** Deletes any file under {@code componentsDir} whose components-relative path is not in {@code live}. */
    private static void sweepComponentsDir(Path componentsDir, Set<String> live) throws IOException {
        if (Files.isDirectory(componentsDir) == false) {
            return;
        }
        deleteUnreferenced(componentsDir, componentsDir, live);
    }

    private static void deleteUnreferenced(Path componentsDir, Path dir, Set<String> live) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    deleteUnreferenced(componentsDir, p, live);
                    continue;
                }
                String relative = componentsDir.relativize(p).toString().replace('\\', '/');
                if (live.contains(relative) == false) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.warn("failed to delete unreferenced component file {}", p, e);
                    }
                }
            }
        }
    }

    private static FileTime mtimeOrZero(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            // Treat unreadable as oldest so we err on the side of pruning it first.
            return FileTime.fromMillis(0);
        }
    }
}
