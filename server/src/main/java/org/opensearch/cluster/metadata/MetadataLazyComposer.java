/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata;

import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.common.annotation.InternalApi;
import org.opensearch.common.settings.Settings;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Bridge that lets cross-package callers (notably
 * {@code org.opensearch.cluster.service.filter.ClusterStateMerger}) compose a new
 * lazy {@link Metadata} from a prior instance plus selective per-component overrides.
 * <p>
 * {@link Metadata}'s lazy constructor and its {@code composeFromPrior} helper are kept
 * package-private so that {@link Metadata}'s public API surface does not leak the
 * {@link LazyIndices} type — the OpenSearch API annotation processor rejects
 * {@code @PublicApi} classes that expose {@code @InternalApi} types through public
 * non-constructor signatures. This class is itself {@code @InternalApi}, so its public
 * static methods are free to reference {@link LazyIndices}.
 *
 * @opensearch.internal
 */
@InternalApi
public final class MetadataLazyComposer {

    private MetadataLazyComposer() {}

    /**
     * Returns a new {@code Metadata} that inherits {@code prior}'s suppliers for every
     * component left null, swapping in the provided overrides for the rest. See
     * {@link Metadata#composeFromPrior(Metadata, Supplier, Supplier, Supplier, Supplier, LazyIndices, Supplier, Supplier)}
     * for the inheritance and invariant rules.
     */
    public static Metadata compose(
        Metadata prior,
        Supplier<CoordinationMetadata> coordinationOverride,
        Supplier<Settings> transientSettingsOverride,
        Supplier<Settings> persistentSettingsOverride,
        Supplier<DiffableStringMap> hashesOverride,
        LazyIndices indicesOverride,
        Supplier<TemplatesMetadata> templatesOverride,
        Supplier<Map<String, Metadata.Custom>> customsOverride
    ) {
        return Metadata.composeFromPrior(
            prior,
            coordinationOverride,
            transientSettingsOverride,
            persistentSettingsOverride,
            hashesOverride,
            indicesOverride,
            templatesOverride,
            customsOverride
        );
    }

    /**
     * Returns a fully-lazy {@link Metadata}: every supplier-typed component is invoked on
     * first access, the {@link LazyIndices} defers per-index materialization to its own
     * per-key suppliers, and the index-name arrays / indices lookup / system-templates
     * lookup are computed on first access from {@code indices} and the metadata customs
     * (no eager iteration, no validation pass). Intended for file- and remote-backed
     * suppliers reading state that was already validated when it was published.
     *
     * Pass {@code null} for any optional supplier to default to the equivalent empty value
     * (no coordination, empty settings, empty hashes, empty templates, empty customs).
     */
    public static Metadata composeFresh(
        String clusterUUID,
        boolean clusterUUIDCommitted,
        long version,
        Supplier<CoordinationMetadata> coordinationMetadataSupplier,
        Supplier<Settings> transientSettingsSupplier,
        Supplier<Settings> persistentSettingsSupplier,
        Supplier<DiffableStringMap> hashesOfConsistentSettingsSupplier,
        LazyIndices indices,
        Supplier<TemplatesMetadata> templatesSupplier,
        Supplier<Map<String, Metadata.Custom>> customsSupplier
    ) {
        return new Metadata(
            clusterUUID,
            clusterUUIDCommitted,
            version,
            coordinationMetadataSupplier != null ? coordinationMetadataSupplier : () -> CoordinationMetadata.EMPTY_METADATA,
            transientSettingsSupplier != null ? transientSettingsSupplier : () -> Settings.EMPTY,
            persistentSettingsSupplier != null ? persistentSettingsSupplier : () -> Settings.EMPTY,
            hashesOfConsistentSettingsSupplier != null
                ? hashesOfConsistentSettingsSupplier
                : () -> new DiffableStringMap(java.util.Map.of()),
            indices != null ? indices : LazyIndices.empty(),
            templatesSupplier != null ? templatesSupplier : () -> new TemplatesMetadata(java.util.Map.of()),
            customsSupplier != null ? customsSupplier : java.util.Map::of,
            null,  // index name arrays — compute on first access
            null,  // indices lookup — compute on first access
            null   // system templates lookup — compute on first access
        );
    }
}
