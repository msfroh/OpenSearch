/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible order open source license.
 */

package org.opensearch.cluster.metadata;

import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class MetadataLazinessTests extends OpenSearchTestCase {

    public void testLazyConstructorMaterializesOnlyAccessedComponents() {
        AtomicInteger coordinationCalls = new AtomicInteger();
        AtomicInteger transientCalls = new AtomicInteger();
        AtomicInteger persistentCalls = new AtomicInteger();
        AtomicInteger hashesCalls = new AtomicInteger();
        AtomicInteger templatesCalls = new AtomicInteger();
        AtomicInteger customsCalls = new AtomicInteger();

        Metadata metadata = new Metadata(
            "cluster-uuid",
            false,
            1L,
            counting(CoordinationMetadata.EMPTY_METADATA, coordinationCalls),
            counting(Settings.EMPTY, transientCalls),
            counting(Settings.EMPTY, persistentCalls),
            counting(DiffableStringMap.EMPTY, hashesCalls),
            LazyIndices.empty(),
            counting(new TemplatesMetadata(Collections.emptyMap()), templatesCalls),
            counting(Collections.<String, Metadata.Custom>unmodifiableMap(new HashMap<>()), customsCalls),
            null,  // index name arrays — compute lazily
            null,  // indices lookup — compute lazily
            null   // system templates lookup — compute lazily
        );

        assertEquals(0, coordinationCalls.get());
        assertEquals(0, transientCalls.get());
        assertEquals(0, persistentCalls.get());
        assertEquals(0, hashesCalls.get());
        assertEquals(0, templatesCalls.get());
        assertEquals(0, customsCalls.get());

        // Touching coordinationMetadata() only materializes coordination.
        metadata.coordinationMetadata();
        assertEquals(1, coordinationCalls.get());
        assertEquals(0, transientCalls.get());
        assertEquals(0, persistentCalls.get());
        assertEquals(0, hashesCalls.get());
        assertEquals(0, templatesCalls.get());
        assertEquals(0, customsCalls.get());

        // CachedSupplier: repeated access doesn't re-invoke.
        metadata.coordinationMetadata();
        metadata.coordinationMetadata();
        assertEquals(1, coordinationCalls.get());

        // settings() materializes both persistent and transient (it composes them) but
        // doesn't touch templates, customs, hashes, or coordination beyond the prior call.
        metadata.settings();
        assertEquals(1, transientCalls.get());
        assertEquals(1, persistentCalls.get());
        assertEquals(0, hashesCalls.get());
        assertEquals(0, templatesCalls.get());
        assertEquals(0, customsCalls.get());
        assertEquals(1, coordinationCalls.get());

        // settings() called again — composed supplier is itself cached.
        metadata.settings();
        assertEquals(1, transientCalls.get());
        assertEquals(1, persistentCalls.get());

        // customs() touches only customs.
        metadata.customs();
        assertEquals(0, hashesCalls.get());
        assertEquals(0, templatesCalls.get());
        assertEquals(1, customsCalls.get());

        // templates() and templatesMetadata() share the same supplier.
        metadata.templates();
        metadata.templatesMetadata();
        assertEquals(1, templatesCalls.get());

        // hashesOfConsistentSettings() touches only hashes.
        metadata.hashesOfConsistentSettings();
        assertEquals(1, hashesCalls.get());
    }

    public void testEagerConstructorBehavesIdentically() {
        // The existing public-shaped constructor still produces a Metadata whose accessors
        // return the eager values. (Reached via Metadata.Builder, which routes through it.)
        Metadata metadata = Metadata.builder().clusterUUID("uuid").version(7L).build();
        assertEquals("uuid", metadata.clusterUUID());
        assertEquals(7L, metadata.version());
        assertSame(CoordinationMetadata.EMPTY_METADATA, metadata.coordinationMetadata());
        assertSame(Settings.EMPTY, metadata.transientSettings());
        assertSame(Settings.EMPTY, metadata.persistentSettings());
    }

    public void testPerIndexLazinessOnlyMaterializesTouchedEntries() {
        AtomicInteger fooCalls = new AtomicInteger();
        AtomicInteger barCalls = new AtomicInteger();
        AtomicInteger bazCalls = new AtomicInteger();

        IndexMetadata foo = indexMeta("foo");
        IndexMetadata bar = indexMeta("bar");
        IndexMetadata baz = indexMeta("baz");

        Map<String, Supplier<IndexMetadata>> entries = new HashMap<>();
        entries.put("foo", () -> {
            fooCalls.incrementAndGet();
            return foo;
        });
        entries.put("bar", () -> {
            barCalls.incrementAndGet();
            return bar;
        });
        entries.put("baz", () -> {
            bazCalls.incrementAndGet();
            return baz;
        });

        LazyIndices lazy = LazyIndices.ofLazy(entries);
        Metadata metadata = new Metadata(
            "cluster-uuid",
            false,
            1L,
            () -> CoordinationMetadata.EMPTY_METADATA,
            () -> Settings.EMPTY,
            () -> Settings.EMPTY,
            () -> DiffableStringMap.EMPTY,
            lazy,
            () -> new TemplatesMetadata(Collections.emptyMap()),
            () -> Collections.unmodifiableMap(new HashMap<>()),
            null,
            null,
            null
        );

        // Touching one named index materializes only that one.
        assertSame(foo, metadata.index("foo"));
        assertEquals(1, fooCalls.get());
        assertEquals(0, barCalls.get());
        assertEquals(0, bazCalls.get());

        // Per-key cached: repeated access doesn't re-invoke.
        metadata.index("foo");
        metadata.index("foo");
        assertEquals(1, fooCalls.get());

        // Touching a different name only materializes that one.
        assertSame(bar, metadata.index("bar"));
        assertEquals(1, fooCalls.get());
        assertEquals(1, barCalls.get());
        assertEquals(0, bazCalls.get());

        // size() / containsKey() should NOT force materialization.
        assertEquals(3, metadata.indices().size());
        assertTrue(metadata.indices().containsKey("baz"));
        assertEquals(0, bazCalls.get());

        // getTotalNumberOfShards() forces every entry — the documented cost of bulk access.
        metadata.getTotalNumberOfShards();
        assertEquals(1, bazCalls.get());
        // …and the count supplier is itself cached.
        metadata.getTotalNumberOfShards();
        metadata.getTotalOpenIndexShards();
        assertEquals(1, bazCalls.get());
    }

    private static IndexMetadata indexMeta(String name) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, org.opensearch.Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    .build()
            )
            .build();
    }

    private static <T> Supplier<T> counting(T value, AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            return value;
        };
    }
}
