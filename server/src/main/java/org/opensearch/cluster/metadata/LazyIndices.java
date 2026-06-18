/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata;

import org.opensearch.common.annotation.InternalApi;
import org.opensearch.common.util.CachedSupplier;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An immutable {@code Map<String, IndexMetadata>} whose values are materialized lazily.
 * <p>
 * Per-key access via {@link #get(Object)} triggers only that entry's supplier and caches
 * the result. Bulk accessors ({@link #values()}, {@link #entrySet()}, iteration) materialize
 * every entry — callers that want to stay lazy must use per-key {@code get(name)}.
 * <p>
 * Equality is defined over the materialized contents (per {@link AbstractMap#equals}), so
 * comparing two LazyIndices forces both to materialize fully.
 *
 * @opensearch.internal
 */
@InternalApi
public final class LazyIndices extends AbstractMap<String, IndexMetadata> {

    private static final LazyIndices EMPTY = new LazyIndices(Collections.emptyMap());

    private final Map<String, CachedSupplier<IndexMetadata>> entries;

    private LazyIndices(Map<String, CachedSupplier<IndexMetadata>> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static LazyIndices empty() {
        return EMPTY;
    }

    /**
     * Wraps an already-materialized {@code Map<String, IndexMetadata>}. Each entry's
     * supplier is a no-op that returns the existing value — bulk accessors are essentially
     * free, matching today's eager behaviour.
     */
    public static LazyIndices ofEager(Map<String, IndexMetadata> eager) {
        if (eager.isEmpty()) {
            return EMPTY;
        }
        Map<String, CachedSupplier<IndexMetadata>> m = new LinkedHashMap<>(eager.size());
        for (Map.Entry<String, IndexMetadata> e : eager.entrySet()) {
            IndexMetadata value = e.getValue();
            m.put(e.getKey(), new CachedSupplier<>(() -> value));
        }
        return new LazyIndices(m);
    }

    /**
     * Builds a lazy view from per-key suppliers. The supplier for a given index is invoked
     * the first time {@link #get(Object)} (or a bulk accessor) requests that entry, and the
     * result is cached for the lifetime of this {@code LazyIndices}.
     */
    public static LazyIndices ofLazy(Map<String, Supplier<IndexMetadata>> lazy) {
        if (lazy.isEmpty()) {
            return EMPTY;
        }
        Map<String, CachedSupplier<IndexMetadata>> m = new LinkedHashMap<>(lazy.size());
        for (Map.Entry<String, Supplier<IndexMetadata>> e : lazy.entrySet()) {
            Supplier<IndexMetadata> supplier = e.getValue();
            m.put(e.getKey(), supplier instanceof CachedSupplier ? (CachedSupplier<IndexMetadata>) supplier : new CachedSupplier<>(supplier));
        }
        return new LazyIndices(m);
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    @Override
    public IndexMetadata get(Object key) {
        CachedSupplier<IndexMetadata> s = entries.get(key);
        return s == null ? null : s.get();
    }

    @Override
    public Set<String> keySet() {
        return entries.keySet();
    }

    @Override
    public Collection<IndexMetadata> values() {
        // AbstractMap's default values() iterates entrySet() which we override below.
        return super.values();
    }

    @Override
    public Set<Map.Entry<String, IndexMetadata>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<String, IndexMetadata>> iterator() {
                Iterator<Map.Entry<String, CachedSupplier<IndexMetadata>>> backing = entries.entrySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return backing.hasNext();
                    }

                    @Override
                    public Map.Entry<String, IndexMetadata> next() {
                        Map.Entry<String, CachedSupplier<IndexMetadata>> e = backing.next();
                        // SimpleImmutableEntry triggers the supplier eagerly here, which is
                        // the intended bulk-access behaviour.
                        return new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue().get());
                    }
                };
            }

            @Override
            public int size() {
                return entries.size();
            }
        };
    }

    /**
     * Materializes every entry and returns a snapshot {@code HashMap}. Useful when callers
     * need a mutable copy or want to detach from this lazy view.
     */
    public Map<String, IndexMetadata> materializeAll() {
        Map<String, IndexMetadata> out = new HashMap<>(entries.size());
        for (Map.Entry<String, CachedSupplier<IndexMetadata>> e : entries.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return out;
    }
}
