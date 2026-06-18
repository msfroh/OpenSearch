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
import java.util.TreeMap;
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
            Collections.emptyMap(),
            counting(new TemplatesMetadata(Collections.emptyMap()), templatesCalls),
            counting(Collections.<String, Metadata.Custom>unmodifiableMap(new HashMap<>()), customsCalls),
            new String[0],
            new String[0],
            new String[0],
            new String[0],
            new String[0],
            new String[0],
            Collections.unmodifiableSortedMap(new TreeMap<>()),
            Collections.emptyMap()
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

    private static <T> Supplier<T> counting(T value, AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            return value;
        };
    }
}
