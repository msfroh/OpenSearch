# Cluster Manager Operations: Logical vs Physical State

This document catalogs every `TransportClusterManagerNodeAction` (and `TransportClusterManagerNodeReadAction` / `TransportClusterInfoAction`) subclass in `server/` by the slice of `ClusterState` it reads and writes. The goal is to support a `ClusterStateFilter` design that lets each operation declare just the slice of state it needs, so a future plugin can lazy-load that slice from external storage instead of materializing the whole `ClusterState` on every update.

It also calls out which operations modify **logical** state (indices, templates, pipelines — metadata that exists independently of any node), **physical** state (routing, nodes, blocks, in-progress operations — metadata that depends on the live topology), or both. The split is meant to feed the larger goal of separating logical and physical cluster managers.

## Methodology

For each transport action:

- I read the action's `clusterManagerOperation(...)` to see what it pulls off the `ClusterState` parameter.
- I followed any `submitStateUpdateTask(...)` calls into the relevant `Metadata*Service` / `AllocationService` / `SnapshotsService` / etc. to capture what the executor reads and what it writes.
- I noted what `checkBlock(...)` consults.

Where an operation "needs" a slice it's because (a) the action body directly accesses it, (b) the state-update task accesses it, or (c) `checkBlock` accesses it.

State-piece names are used consistently across the catalog. Reading a name like `metadata.indices[<scope>]` always means the same thing.

### State-piece vocabulary

**Logical** (no dependency on the live cluster topology):

| Name | Meaning |
|---|---|
| `metadata.indices[<scope>]` | Per-index `IndexMetadata`. `<scope>` ∈ `{all, <requested names>, <resolved concrete indices>, <single index>, <data-stream backings>}` |
| `metadata.indices[<scope>].settings` / `.mappings` / `.aliases` / `.customData` / `.state` / `.ingestionStatus` | Just one section of `IndexMetadata` |
| `metadata.templates` | Legacy index templates |
| `metadata.componentTemplates` | v2 component templates |
| `metadata.composableIndexTemplates` | v2 composable index templates |
| `metadata.customs[dataStreams]` | `DataStreamMetadata` |
| `metadata.customs[views]` | `ViewsMetadata` |
| `metadata.customs[ingest]` | `IngestMetadata` (ingest pipelines) |
| `metadata.customs[searchPipelines]` | `SearchPipelineMetadata` |
| `metadata.customs[scripts]` | `ScriptMetadata` (stored scripts) |
| `metadata.customs[repositories]` | `RepositoriesMetadata` (definitions only — in-flight operations are physical) |
| `metadata.customs[weightedRouting]` | `WeightedRoutingMetadata` (straddler — logical metadata that influences routing) |
| `metadata.customs[decommissionAttribute]` | `DecommissionAttributeMetadata` (straddler — drives voting-config exclusions) |
| `metadata.customs[persistentTasks]` | `PersistentTasksCustomMetadata` (straddler — logical task records driven by physical node changes) |
| `metadata.indexGraveyard` | Tombstones for deleted indices |
| `metadata.persistentSettings` / `metadata.transientSettings` | Cluster settings |
| `metadata.hashesOfConsistentSettings` | Hash table for consistent-settings validation |
| `metadata.indicesLookup` | Derived index/alias/data-stream name lookup |
| `metadata.coordinationMetadata` | Term, last-committed config, last-accepted config |

**Physical** (depends on the live cluster topology):

| Name | Meaning |
|---|---|
| `nodes` | `DiscoveryNodes` |
| `routingTable[<scope>]` | `RoutingTable` filtered by `<scope>` (same meanings as for indices) |
| `blocks[<scope>]` | `ClusterBlocks` (global, per-index, or by APIBlock type) |
| `customs[snapshots]` | `SnapshotsInProgress` (top-level `ClusterState` custom) |
| `customs[snapshotDeletions]` | `SnapshotDeletionsInProgress` |
| `customs[restoreInProgress]` | `RestoreInProgress` |
| `customs[repositoryCleanup]` | `RepositoryCleanupInProgress` |
| `coordinationMetadata.votingConfig` | Last-committed voting configuration |
| `coordinationMetadata.votingConfigExclusions` | Excluded cluster-manager nodes |

Note the asymmetry: `customs[snapshots/snapshotDeletions/restoreInProgress/repositoryCleanup]` live as top-level `ClusterState` customs (physical, "in-progress"); `customs[repositories]` and the rest live under `metadata.customs` (logical, "definitions").

---

## Per-operation catalog

### Index lifecycle

#### TransportCreateIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/create/TransportCreateIndexAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices` (existence), `metadata.templates`, `metadata.componentTemplates`, `metadata.composableIndexTemplates` (template matching), `metadata.customs[ingest]`, `metadata.customs[dataStreams]`, `metadata.persistentSettings`
- **Reads (physical)**: `nodes` (shard allocation limits)
- **Writes (logical)**: `metadata.indices[+new]`, `metadata.customs[dataStreams]` (if applicable)
- **Writes (physical)**: `routingTable[+new]` (unassigned shards), `blocks[+index blocks]`
- **Notes**: applies index-mapping transformers before submission. Actual creation runs in `MetadataCreateIndexService.createIndex()` which internally reads templates then triggers `AllocationService.reroute()`.

#### AutoCreateAction (`server/src/main/java/org/opensearch/action/admin/indices/create/AutoCreateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices`, `metadata.composableIndexTemplates`, `metadata.customs[dataStreams]`
- **Reads (physical)**: none in `clusterManagerOperation`; downstream paths consult `nodes`
- **Writes (logical)**: branch: either `metadata.customs[dataStreams][+stream + backing index]` OR `metadata.indices[+new]`
- **Writes (physical)**: `routingTable[+new]`, `blocks[+index blocks if applicable]`
- **Notes**: branches on whether a data-stream template matches the index name. `checkBlock` consults `blocks[METADATA_WRITE]` only — not `CREATE_INDEX`.

#### TransportDeleteIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/delete/TransportDeleteIndexAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices`, `metadata.customs[dataStreams]` (backing-index detection), `metadata.indexGraveyard`
- **Reads (physical)**: `routingTable` (via `checkBlock`), `customs[snapshots]` (via `SnapshotsService.snapshottingIndices`), `customs[restoreInProgress]`
- **Writes (logical)**: `metadata.indices[-deleted]`, `metadata.customs[dataStreams][backing-index removal]`, `metadata.indexGraveyard[+tombstones]`
- **Writes (physical)**: `routingTable[-deleted]`, `blocks[-deleted]`
- **Notes**: calls `DestructiveOperations.failDestructive()` before submitting, then `AllocationService.reroute()` after deletion. Refuses to delete indices held by snapshots or restores.

#### TransportOpenIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/open/TransportOpenIndexAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<resolved>]`, `nodes` (version compatibility)
- **Reads (physical)**: `routingTable[<resolved>]` (to rebuild routing for previously closed indices)
- **Writes (logical)**: `metadata.indices[<resolved>].state` (`CLOSE → OPEN`), settings (removes `VERIFIED_BEFORE_CLOSE_SETTING`)
- **Writes (physical)**: `routingTable[<resolved>]` (rebuilt), `blocks[-INDEX_CLOSED_BLOCK_ID]`
- **Notes**: `MetadataIndexStateService.openIndex()` calls `MetadataIndexUpgradeService.upgradeIndexMetadata()`; final reroute via `AllocationService`.

#### TransportCloseIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/close/TransportCloseIndexAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices`, `metadata.customs[dataStreams]` (write-index check)
- **Reads (physical)**: `routingTable[<resolved>]` (shard readiness), `customs[snapshots]`, `customs[restoreInProgress]`
- **Writes (logical)**: `metadata.indices[<resolved>].state = CLOSED`; settings (`+VERIFIED_BEFORE_CLOSE_SETTING`)
- **Writes (physical)**: `routingTable[<resolved>]` (removed), `blocks[+INDEX_CLOSED_BLOCK_ID]` (added then transformed when finalized)
- **Notes**: three-phase: (1) add write block + verify, (2) `TransportVerifyShardBeforeCloseAction` waits for all shards, (3) finalize by changing state and replacing the temporary block. Refuses while a snapshot or restore is in progress.

#### TransportDeleteDanglingIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/dangling/delete/TransportDeleteDanglingIndexAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices` (must not contain the dangling index), `metadata.indexGraveyard`
- **Reads (physical)**: none from `ClusterState` directly — node list is queried via a separate `ListDanglingIndicesAction`
- **Writes (logical)**: `metadata.indexGraveyard[+tombstone]`
- **Writes (physical)**: none
- **Notes**: `checkBlock()` returns `null` (no block checks). Requires explicit `accept_data_loss`.

#### TransportAddIndexBlockAction (`server/src/main/java/org/opensearch/action/admin/indices/readonly/TransportAddIndexBlockAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<resolved>]`
- **Reads (physical)**: `routingTable[<resolved>]` (shard readiness via `TransportVerifyShardIndexBlockAction`)
- **Writes (logical)**: `metadata.indices[<resolved>].settings` (synced with block flag)
- **Writes (physical)**: `blocks[+temp UUID block, then +final APIBlock]`, `routingTable` (indirect via reroute)
- **Notes**: three-phase like close. Refuses on data-stream write index.

#### TransportScaleIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/scale/searchonly/TransportScaleIndexAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<target>]`
- **Reads (physical)**: `routingTable[<target>]` (primary assignments), `nodes` (shard limits)
- **Writes (logical)**: `metadata.indices[<target>].settings` (toggles `INDEX_BLOCKS_SEARCH_ONLY_SETTING`, may adjust routing shard count)
- **Writes (physical)**: `routingTable[<target>]` (rebuilt: search-only on scale-down, full on scale-up), `blocks[+temp prep, +INDEX_SEARCH_ONLY_BLOCK_ID]` (scale-down) or `[-INDEX_SEARCH_ONLY_BLOCK_ID]` (scale-up)
- **Notes**: multi-phase scale-down with flush + remote-store sync; `ScaleIndexClusterStateBuilder` does the routing-table rewrite.

#### TransportResizeAction (`server/src/main/java/org/opensearch/action/admin/indices/shrink/TransportResizeAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<source>]` (full IndexMetadata: settings, mappings, aliases, remote-store metadata)
- **Reads (physical)**: `routingTable[<source>]` (shard counts), `nodes` (limits), `blocks[<source>]`
- **Writes (logical)**: `metadata.indices[+target]` (settings copied/derived), `metadata.indices[+target].customs[remote store]` (if applicable)
- **Writes (physical)**: `routingTable[+target]`, `blocks[<target>]` (if specified)
- **Notes**: SHRINK / SPLIT / CLONE variants. Fetches per-shard stats via `IndicesStatsAction` before creating the target via `MetadataCreateIndexService.createIndex(... recoverFromIndex)`.

#### TransportRolloverAction (`server/src/main/java/org/opensearch/action/admin/indices/rollover/TransportRolloverAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<source/write-alias>]`, `metadata.indices[<source>].aliases`, `metadata.composableIndexTemplates` (validation), `metadata.customs[dataStreams]` (if data-stream rollover)
- **Reads (physical)**: indices stats via `IndicesStatsAction` (condition evaluation), `nodes` (version)
- **Writes (logical)**: `metadata.indices[+new + RolloverInfo on source]`, `metadata.indices[<source>].aliases` (write-alias swap), `metadata.customs[dataStreams]` (generation + backings)
- **Writes (physical)**: `routingTable[+new]`, `blocks[+new]` if applicable
- **Notes**: dry-run path returns without state change. Real path threads through `MetadataCreateIndexService`, `MetadataIndexAliasesService`, and `DataStream.rollover()`.

### Index metadata (mappings, settings, aliases, get index, exists, upgrade)

#### TransportIndicesAliasesAction (`server/src/main/java/org/opensearch/action/admin/indices/alias/TransportIndicesAliasesAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<requested>]`, `metadata.indices[all]` (wildcard alias expansion via `findAliases`), `metadata.indicesLookup` (data-stream backing-index validation)
- **Reads (physical)**: `blocks[REMOTE_SNAPSHOT]` on targets
- **Writes (logical)**: `metadata.indices[<resolved>].aliases` (+ `aliasesVersion`)
- **Writes (physical)**: none directly
- **Notes**: `resolvedAliasActions()` requires all aliases to expand wildcards — single largest unrecognized read amplifier in this group.

#### TransportGetAliasesAction (`server/src/main/java/org/opensearch/action/admin/indices/alias/get/TransportGetAliasesAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices[<requested>|all].aliases` (via `metadata.findAliases()`)
- **Reads (physical)**: `blocks[METADATA_READ]` on resolved indices
- **Writes**: none

#### TransportGetMappingsAction (`server/src/main/java/org/opensearch/action/admin/indices/mapping/get/TransportGetMappingsAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices[<requested>].mappings` via `metadata.findMappings()`
- **Reads (physical)**: inherits `METADATA_READ` block from `TransportClusterInfoAction`
- **Writes**: none

#### TransportPutMappingAction (`server/src/main/java/org/opensearch/action/admin/indices/mapping/put/TransportPutMappingAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<resolved>].mappings`, `metadata.templates` / `componentTemplates` (implicit during validation)
- **Reads (physical)**: `blocks[METADATA_WRITE]` on targets
- **Writes (logical)**: `metadata.indices[<resolved>].mappings` (+ `mappingVersion`)
- **Writes (physical)**: none
- **Notes**: `PutMappingExecutor` re-reads each target's `IndexMetadata` to merge mappings.

#### TransportAutoPutMappingAction (`server/src/main/java/org/opensearch/action/admin/indices/mapping/put/TransportAutoPutMappingAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<single concrete index>].mappings`
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: same as `TransportPutMappingAction` but scoped to one pre-resolved index
- **Writes (physical)**: none
- **Notes**: delegates straight to `TransportPutMappingAction.performMappingUpdate()` skipping name expansion.

#### TransportGetSettingsAction (`server/src/main/java/org/opensearch/action/admin/indices/settings/get/TransportGetSettingsAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices[<resolved>].settings`
- **Reads (physical)**: `blocks[METADATA_READ]` on resolved indices
- **Writes**: none

#### TransportUpdateSettingsAction (`server/src/main/java/org/opensearch/action/admin/indices/settings/put/TransportUpdateSettingsAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<resolved>]` (state, settings, aliases), `metadata.persistentSettings` (defaults), `metadata.indicesLookup`
- **Reads (physical)**: `blocks[METADATA_WRITE, WRITE, READ]` on targets and globally
- **Writes (logical)**: `metadata.indices[<resolved>].settings` (+ `settingsVersion`), `metadata.indices[<resolved>]` (replica counts when changed)
- **Writes (physical)**: `blocks` (added/removed for APIBlock settings), `routingTable[<resolved>]` (when replica count changes), implicit `reroute` after applying
- **Notes**: closed indices accept all settings; open accept only dynamic. Almost always triggers a reroute.

#### TransportUpgradeSettingsAction (`server/src/main/java/org/opensearch/action/admin/indices/upgrade/post/TransportUpgradeSettingsAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<indices in request.versions()>].settings` (creation version)
- **Reads (physical)**: `blocks[METADATA_WRITE]` global
- **Writes (logical)**: `metadata.indices[<upgraded>].settings` (+ `SETTING_VERSION_UPGRADED`, `settingsVersion`)
- **Writes (physical)**: none

#### TransportGetIndexAction (`server/src/main/java/org/opensearch/action/admin/indices/get/TransportGetIndexAction.java`)
- **Kind**: read
- **Reads (logical)**: per-feature flag — `metadata.indices[<requested>].{mappings,aliases,settings,context}`; `metadata.customs[dataStreams]` (for `findDataStreams`)
- **Reads (physical)**: inherits `METADATA_READ` block from `TransportClusterInfoAction`
- **Writes**: none

#### TransportIndicesExistsAction (`server/src/main/java/org/opensearch/action/admin/indices/exists/indices/TransportIndicesExistsAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices` (implicit via `concreteIndexNames`)
- **Reads (physical)**: `blocks[METADATA_READ]` on resolved indices
- **Writes**: none

### Templates

#### TransportDeleteIndexTemplateAction (`server/src/main/java/org/opensearch/action/admin/indices/template/delete/TransportDeleteIndexTemplateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.templates`
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.templates[-entry]`
- **Writes (physical)**: none

#### TransportDeleteComposableIndexTemplateAction (`.../delete/TransportDeleteComposableIndexTemplateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.composableIndexTemplates`, `metadata.customs[dataStreams]` (usage validation)
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.composableIndexTemplates[-entry]`
- **Writes (physical)**: none

#### TransportDeleteComponentTemplateAction (`.../delete/TransportDeleteComponentTemplateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.componentTemplates`, `metadata.composableIndexTemplates` (usage validation — scans **all** composable templates)
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.componentTemplates[-entry]`
- **Writes (physical)**: none

#### TransportGetIndexTemplatesAction (`.../get/TransportGetIndexTemplatesAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.templates`; **Reads (physical)**: `blocks[METADATA_READ]`; **Writes**: none.

#### TransportGetComposableIndexTemplateAction (`.../get/TransportGetComposableIndexTemplateAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.composableIndexTemplates`; **Reads (physical)**: `blocks[METADATA_READ]`; **Writes**: none.

#### TransportGetComponentTemplateAction (`.../get/TransportGetComponentTemplateAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.componentTemplates`; **Reads (physical)**: `blocks[METADATA_READ]`; **Writes**: none.

#### TransportPutIndexTemplateAction (`.../put/TransportPutIndexTemplateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.templates` (collision), `metadata.composableIndexTemplates` (overlap validation)
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.templates[+entry]`
- **Writes (physical)**: none

#### TransportPutComposableIndexTemplateAction (`.../put/TransportPutComposableIndexTemplateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.composableIndexTemplates`, `metadata.componentTemplates` (resolution), `metadata.templates` (overlap), `metadata.customs[systemTemplates]` (context validation), `metadata.indices` (full validation against existing indices)
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.composableIndexTemplates[+entry]`
- **Writes (physical)**: none
- **Notes**: must consult **all** indices for overlap validation — biggest read amplifier on the logical side.

#### TransportPutComponentTemplateAction (`.../put/TransportPutComponentTemplateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.componentTemplates`, `metadata.composableIndexTemplates` (re-validates **all** dependents when updating)
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.componentTemplates[+entry]`
- **Writes (physical)**: none

#### TransportSimulateIndexTemplateAction (`.../post/TransportSimulateIndexTemplateAction.java`)
- **Kind**: read (with transient state for simulation)
- **Reads (logical)**: `metadata.composableIndexTemplates`, `metadata.componentTemplates`, `metadata.templates` (overlap), `metadata.indices` (resolution context)
- **Reads (physical)**: `blocks[METADATA_READ]`
- **Writes**: none (constructs an ephemeral state for validation, never publishes)

#### TransportSimulateTemplateAction (`.../post/TransportSimulateTemplateAction.java`)
- **Kind**: read (transient state); same shape as `SimulateIndexTemplate` but keyed by template name.

### Snapshots and restore

#### TransportCreateSnapshotAction (`server/src/main/java/org/opensearch/action/admin/cluster/snapshots/create/TransportCreateSnapshotAction.java`)
- **Kind**: write (creates a snapshot-in-progress entry)
- **Reads (logical)**: `metadata.indices[<resolved>]` (settings, mappings, aliases), `metadata.customs[dataStreams]`, `metadata.customs[repositories]`, `metadata.persistentSettings` (`REMOTE_STORE_COMPATIBILITY_MODE`)
- **Reads (physical)**: `routingTable[<resolved>]` (per-shard state in `shards()`), `customs[snapshots]`, `customs[snapshotDeletions]`, `customs[repositoryCleanup]`, `nodes` (min version)
- **Writes (logical)**: none — logical metadata is *captured into* the snapshot entry, not modified in `ClusterState`
- **Writes (physical)**: `customs[snapshots][+entry]`
- **Notes**: V2 shallow-snapshot path (`createSnapshotV2`) is conditional on remote-store compatibility flags.

#### TransportDeleteSnapshotAction (`server/src/main/java/org/opensearch/action/admin/cluster/snapshots/delete/TransportDeleteSnapshotAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices` (via `validateSnapshotsBackingAnyIndex`), `metadata.customs[repositories]`
- **Reads (physical)**: `customs[snapshots]` (active clones from source, aborts in-progress), `customs[snapshotDeletions]` (concurrency), `customs[restoreInProgress]` (refuse if restoring), `customs[repositoryCleanup]`, `nodes` (min version)
- **Writes (physical)**: `customs[snapshots]` (mark matching entries ABORTED), `customs[snapshotDeletions][+/update entry]`
- **Notes**: heaviest concurrency-coordination operation. Reads every in-progress custom.

#### TransportCloneSnapshotAction (`server/src/main/java/org/opensearch/action/admin/cluster/snapshots/clone/TransportCloneSnapshotAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.customs[repositories]`
- **Reads (physical)**: `customs[snapshots]` (concurrency limits, source-snapshot checks), `customs[snapshotDeletions]`, `customs[repositoryCleanup]`, `nodes` (min version)
- **Writes (physical)**: `customs[snapshots][+clone entry]`; V2 finalize submits a second task to remove it on completion.

#### TransportRestoreSnapshotAction (`server/src/main/java/org/opensearch/action/admin/cluster/snapshots/restore/TransportRestoreSnapshotAction.java`)
- **Kind**: write (creates restore-in-progress and modifies indices)
- **Reads (logical)**: `metadata.indices`, `metadata.customs[dataStreams]`, `metadata.customs[repositories]`, `metadata.persistentSettings`
- **Reads (physical)**: `customs[snapshotDeletions]`, `customs[restoreInProgress]`, `customs[repositoryCleanup]`, `routingTable`, `blocks`, `nodes`
- **Writes (logical)**: `metadata.indices[<restored>]` (new or modified), `metadata.customs[dataStreams]` (if included)
- **Writes (physical)**: `customs[restoreInProgress][+entry]`, `routingTable` (`addAsNewRestore` / `addAsRestore`), `blocks` (per-index)
- **Notes**: cross-cuts logical and physical more than almost any other operation — re-creates entire index metadata from the snapshot and initiates shard recovery in one publish.

#### TransportRestoreRemoteStoreAction (`server/src/main/java/org/opensearch/action/admin/cluster/remotestore/restore/TransportRestoreRemoteStoreAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices`, `metadata.persistentSettings` (remote-store settings)
- **Reads (physical)**: `routingTable`, `blocks`
- **Writes (logical)**: `metadata.indices[<restored>]` (versions, state); optionally global metadata if restoring from a remote `clusterUUID`
- **Writes (physical)**: `routingTable` (`addAsRemoteStoreRestore`), `blocks`
- **Notes**: synchronous in a single state update — no `RestoreInProgress` entry.

#### TransportGetSnapshotsAction (`server/src/main/java/org/opensearch/action/admin/cluster/snapshots/get/TransportGetSnapshotsAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.customs[repositories]`
- **Reads (physical)**: `customs[snapshots]`; then asynchronous `RepositoriesService.getRepositoryData()`
- **Writes**: none

#### TransportSnapshotsStatusAction (`server/src/main/java/org/opensearch/action/admin/cluster/snapshots/status/TransportSnapshotsStatusAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices` (filter validation), `metadata.customs[repositories]`
- **Reads (physical)**: `customs[snapshots]`, `routingTable` (shard counts), `nodes` (node-status fan-out), `blocks`
- **Writes**: none

### Repositories

#### TransportPutRepositoryAction (`.../put/TransportPutRepositoryAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.customs[repositories]`
- **Reads (physical)**: `customs[snapshots]`, `customs[snapshotDeletions]`, `customs[restoreInProgress]`, `customs[repositoryCleanup]` (via `ensureRepositoryNotInUse`), `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.customs[repositories]`
- **Writes (physical)**: none

#### TransportGetRepositoriesAction (`.../get/TransportGetRepositoriesAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.customs[repositories]`; **Reads (physical)**: `blocks[METADATA_READ]`; **Writes**: none.

#### TransportDeleteRepositoryAction (`.../delete/TransportDeleteRepositoryAction.java`)
- **Kind**: write; identical read/write shape to `PutRepository`, plus `ensureNotSystemRepository()` guard.

#### TransportVerifyRepositoryAction (`.../verify/TransportVerifyRepositoryAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.customs[repositories]` (lookup only); **Reads (physical)**: `blocks[METADATA_READ]`; **Writes**: none. (No state-update task — verification runs on the SNAPSHOT threadpool.)

#### TransportCleanupRepositoryAction (`.../cleanup/TransportCleanupRepositoryAction.java`)
- **Kind**: write (two-phase)
- **Reads (logical)**: `metadata.customs[repositories]`
- **Reads (physical)**: all four in-progress customs (refuses if any is active)
- **Writes (physical)**: `customs[repositoryCleanup]` (phase 1: `+entry`; phase 2: remove after cleanup completes)
- **Notes**: a state-applier removes dangling cleanup entries on cluster-manager failover.

### Cluster admin / read

#### TransportClusterStateAction (`.../state/TransportClusterStateAction.java`)
- **Kind**: read (selectable pass-through)
- **Reads (logical)**: selectable via request flags — `{metadata.indices[any], metadata.templates, metadata.componentTemplates, metadata.composableIndexTemplates, metadata.customs[any non-private], metadata.persistentSettings, metadata.transientSettings, metadata.coordinationMetadata}`
- **Reads (physical)**: selectable — `{routingTable[any], nodes, blocks, ClusterState.customs[any non-private]}`
- **Writes**: none
- **Notes**: this is the most useful single test case for a `ClusterStateFilter` design. Its existing request flags map almost 1:1 onto the filter dimensions a future framework would expose.

#### TransportClusterHealthAction (`.../health/TransportClusterHealthAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices[any]` (concrete-index enumeration), `metadata.coordinationMetadata`
- **Reads (physical)**: `routingTable[any]`, `nodes`, `blocks`
- **Writes**: none
- **Notes**: `checkBlock()` returns `null` so health works even under global blocks.

#### TransportClusterAllocationExplainAction (`.../allocation/TransportClusterAllocationExplainAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices[any]` (during shard lookup)
- **Reads (physical)**: `routingTable[any]`, `nodes`, `customs[snapshots]`, `customs[snapshotDeletions]` (via `RoutingAllocation`)
- **Writes**: none
- **Blocks**: `METADATA_READ`
- **Notes**: simulates allocation decisions over the whole `RoutingAllocation`; effectively touches every physical slice.

#### TransportPendingClusterTasksAction (`.../tasks/TransportPendingClusterTasksAction.java`)
- **Kind**: read
- **Reads**: nothing from `ClusterState` — reads `clusterService.getClusterManagerService().pendingTasks()`
- **Writes**: none
- **Notes**: `checkBlock()` returns `null`.

#### TransportClusterSearchShardsAction (`.../shards/TransportClusterSearchShardsAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.indices[<requested>]` (aliases, index metadata)
- **Reads (physical)**: `routingTable[<requested>]`, `nodes`
- **Writes**: none
- **Blocks**: `METADATA_READ` per-index

#### TransportGetTermVersionAction (`.../term/TransportGetTermVersionAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.coordinationMetadata` (term, version)
- **Reads (physical)**: none
- **Writes**: none
- **Notes**: `checkBlock()` returns `null`. Optional pre-commit-state behind a feature flag.

#### TransportIndicesShardStoresAction (`.../shards/TransportIndicesShardStoresAction.java`)
- **Kind**: read (then fans out to data nodes)
- **Reads (logical)**: `metadata.indices[<resolved>].settings` (`INDEX_DATA_PATH_SETTING`)
- **Reads (physical)**: `routingTable[<resolved>]`, `nodes`
- **Writes**: none
- **Blocks**: per-index `METADATA_READ`

### Cluster settings, reroute, weighted routing

#### TransportClusterUpdateSettingsAction (`.../settings/TransportClusterUpdateSettingsAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.persistentSettings`, `metadata.transientSettings`
- **Reads (physical)**: `blocks[METADATA_WRITE]`, `nodes` (compatibility mode validation), `routingTable + nodes` when settings trigger an implicit reroute
- **Writes (logical)**: `metadata.persistentSettings`, `metadata.transientSettings`, occasionally `metadata.coordinationMetadata` (remote-store migration finalization)
- **Writes (physical)**: `blocks` (read-only / read-only-allow-delete / create-index toggles), `routingTable + routingNodes` (implicit reroute)
- **Notes**: read-amplifier — most non-trivial settings changes drag in routing + nodes via the follow-up reroute.

#### TransportClusterRerouteAction (`.../reroute/TransportClusterRerouteAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices` (per-index settings consumed by deciders, e.g. delayed-shard timeouts)
- **Reads (physical)**: `routingTable`, `nodes`, `blocks`, `customs[restoreInProgress]`, allocation-decider state
- **Writes (logical)**: none
- **Writes (physical)**: `routingTable` (potentially every shard's assignment), `routingNodes` (mutable during allocation)
- **Notes**: the canonical "physical-only" operation, but still reads logical `metadata.indices` because allocation deciders consult per-index settings.

#### TransportAddWeightedRoutingAction (`.../weighted/put/TransportAddWeightedRoutingAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.customs[weightedRouting]`, `metadata.customs[decommissionAttribute]`
- **Reads (physical)**: `nodes` (awareness-attribute counts), `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.customs[weightedRouting]`
- **Writes (physical)**: none directly
- **Notes**: weighted routing is logical metadata that downstream physical routing reads.

#### TransportGetWeightedRoutingAction (`.../weighted/get/TransportGetWeightedRoutingAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.customs[weightedRouting]`; **Reads (physical)**: `blocks[METADATA_READ]`, `nodes` (for `clusterManagerNodeId`); **Writes**: none.

#### TransportDeleteWeightedRoutingAction (`.../weighted/delete/TransportDeleteWeightedRoutingAction.java`)
- **Kind**: write; **Reads (logical)**: `metadata.customs[weightedRouting]`; **Reads (physical)**: `blocks[METADATA_WRITE]`; **Writes (logical)**: `metadata.customs[weightedRouting]`; **Writes (physical)**: none.

### Decommission and voting config

#### TransportDecommissionAction (`.../awareness/put/TransportDecommissionAction.java`)
- **Kind**: write (logical + physical bridge)
- **Reads (logical)**: `metadata.customs[decommissionAttribute]`, `metadata.customs[weightedRouting]`
- **Reads (physical)**: `nodes` (cluster-manager-eligible match), `coordinationMetadata.votingConfig`
- **Writes (logical)**: `metadata.customs[decommissionAttribute]` (INIT status)
- **Writes (physical)**: `coordinationMetadata.votingConfigExclusions` (excluded cluster-manager nodes)

#### TransportGetDecommissionStateAction (`.../awareness/get/TransportGetDecommissionStateAction.java`)
- **Kind**: read; **Reads (logical)**: `metadata.customs[decommissionAttribute]`; **Reads (physical)**: none; **Writes**: none.

#### TransportDeleteDecommissionStateAction (`.../awareness/delete/TransportDeleteDecommissionStateAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.customs[decommissionAttribute]`
- **Writes (logical)**: `metadata.customs[decommissionAttribute][-]`
- **Writes (physical)**: `coordinationMetadata.votingConfigExclusions[-all]`
- **Notes**: idempotent recommission. Asserts both pieces are empty afterwards.

#### TransportAddVotingConfigExclusionsAction (`.../configuration/TransportAddVotingConfigExclusionsAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.coordinationMetadata`
- **Reads (physical)**: `nodes`, `coordinationMetadata.votingConfig`
- **Writes (logical)**: none
- **Writes (physical)**: `coordinationMetadata.votingConfigExclusions`

#### TransportClearVotingConfigExclusionsAction (`.../configuration/TransportClearVotingConfigExclusionsAction.java`)
- **Kind**: write
- **Reads (physical)**: `coordinationMetadata.votingConfigExclusions`, `nodes`, `coordinationMetadata.lastCommittedConfig`
- **Writes (physical)**: `coordinationMetadata.votingConfigExclusions[-all]`
- **Notes**: optional `waitForRemoval` blocks until excluded nodes leave the nodes registry.

### Scripts and pipelines

#### TransportPutStoredScriptAction (`.../storedscripts/TransportPutStoredScriptAction.java`)
- **Kind**: write
- **Reads (logical)**: pre-execution validation reads local settings only — does **not** read `metadata`
- **Reads (physical)**: `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.customs[scripts]`
- **Writes (physical)**: none

#### TransportGetStoredScriptAction (`.../storedscripts/TransportGetStoredScriptAction.java`)
- **Kind**: read; `metadata.customs[scripts]`; `blocks[METADATA_READ]`.

#### TransportDeleteStoredScriptAction (`.../storedscripts/TransportDeleteStoredScriptAction.java`)
- **Kind**: write; reads `metadata.customs[scripts]` (in executor); writes `metadata.customs[scripts]`.

#### PutPipelineTransportAction (`.../ingest/PutPipelineTransportAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.customs[ingest]`
- **Reads (physical)**: `nodes` (fans out a `NodesInfoRequest` for processor compatibility), `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.customs[ingest]`
- **Notes**: looks logical but the compatibility check pulls every node's ingest-processor catalog.

#### GetPipelineTransportAction / DeletePipelineTransportAction
- **Kind**: read / write. Reads `metadata.customs[ingest]`; deletes write same. No nodes read for delete.

#### PutSearchPipelineTransportAction (`.../search/PutSearchPipelineTransportAction.java`)
- **Kind**: write
- Same shape as `PutPipelineTransportAction` but for `metadata.customs[searchPipelines]`; uses `NodesInfoRequest.SEARCH_PIPELINES`.

#### GetSearchPipelineTransportAction / DeleteSearchPipelineTransportAction
- Same shape as the ingest equivalents over `metadata.customs[searchPipelines]`.

### Data streams, views, indices shard stores

#### CreateDataStreamAction.TransportAction (`.../datastream/CreateDataStreamAction.java`)
- **Kind**: write (logical with physical side-effects)
- **Reads (logical)**: `metadata.customs[dataStreams]`, `metadata.composableIndexTemplates`, `metadata.componentTemplates`
- **Reads (physical)**: `nodes` (indirect, via index creation), `blocks[METADATA_WRITE]`
- **Writes (logical)**: `metadata.customs[dataStreams]`, `metadata.indices[+first backing index]`
- **Writes (physical)**: `routingTable[+IndexRoutingTable]`, `blocks[+index write block]`
- **Notes**: the canonical "logical operation with compound physical effects" — creating a data stream necessarily creates an index and allocates shards.

#### DeleteDataStreamAction.TransportAction (`.../datastream/DeleteDataStreamAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.customs[dataStreams]`
- **Reads (physical)**: `customs[snapshots]` (via `snapshottingDataStreams`), `blocks`
- **Writes (logical)**: `metadata.customs[dataStreams][-removed]`, `metadata.indices[-all backings]`
- **Writes (physical)**: `routingTable[-backings]`, `blocks[-]`

#### GetDataStreamAction.TransportAction (`.../datastream/GetDataStreamAction.java`)
- **Kind**: read
- **Reads (logical)**: `metadata.customs[dataStreams]`, `metadata.composableIndexTemplates`
- **Reads (physical)**: `routingTable` (via `ClusterStateHealth`), `nodes`

#### CreateViewAction.TransportAction (`.../view/CreateViewAction.java`)
- **Kind**: write; pure logical — reads/writes `metadata.customs[views]`.

#### UpdateViewAction.TransportAction (`.../view/UpdateViewAction.java`)
- **Kind**: write; same shape as Create — overwrites always.

#### GetViewAction.TransportAction (`.../view/GetViewAction.java`)
- **Kind**: read; `metadata.customs[views]`; `blocks[METADATA_READ]`.

#### DeleteViewAction.TransportAction (`.../view/DeleteViewAction.java`)
- **Kind**: write; reads/writes `metadata.customs[views]`. No cascades.

### Tiering, streaming ingestion, persistent tasks

#### TransportHotToWarmTieringAction (`.../tiering/TransportHotToWarmTieringAction.java`)
- **Kind**: validate-only (no state update task)
- **Reads (logical)**: `metadata.indices[<target>]`
- **Reads (physical)**: `nodes` (via `ClusterInfoService`)
- **Blocks**: `METADATA_WRITE` on targets
- **Notes**: a stub action; actual tiering goes through `TransportTierAction`.

#### TransportTierAction (`.../tiering/TransportTierAction.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<target>]`
- **Reads (physical)**: `nodes`, `routingTable[<target>]`, `blocks`
- **Writes (logical)**: `metadata.indices[<target>].settings` (`INDEX_TIERING_STATE`), `metadata.indices[<target>].customData[tiering]`
- **Writes (physical)**: `routingTable[<target>]` (via reroute)
- **Blocks**: global `METADATA_WRITE`, `CREATE_INDEX`, `WRITE`
- **Notes**: in-progress tiering is tracked in two places — `IndexMetadata.customData[tiering]` (logical, per-index) plus an in-memory `tieringIndices` set on the service that is **not** in `ClusterState`. The service synthesizes the latter from `ClusterStateListener` callbacks.

#### TransportCancelTierAction (`.../tiering/TransportCancelTierAction.java`)
- **Kind**: write; reverts settings + `customData[tiering]` on the target; reroute.

#### TransportGetTieringStatusAction / TransportListTieringStatusAction
- **Kind**: read; reads `metadata.indices[*].settings`, plus the service's in-memory `tieringIndices`.

#### TransportPauseIngestionAction / TransportResumeIngestionAction (`.../streamingingestion/.../...Action.java`)
- **Kind**: write
- **Reads (logical)**: `metadata.indices[<target>].ingestionStatus`
- **Reads (physical)**: `nodes` (shard targeting), `blocks[METADATA_WRITE]` per index
- **Writes (logical)**: `metadata.indices[<target>].ingestionStatus`
- **Writes (physical)**: none at the cluster-manager level (shard-level updates via a follow-up transport action)
- **Notes**: ingestion state is a *top-level field on `IndexMetadata`*, not a per-index custom. Pause/resume is two-phase: cluster-state update, then shard-level transport.

#### StartPersistentTaskAction / UpdatePersistentTaskStatusAction / RemovePersistentTaskAction / CompletionPersistentTaskAction (`.../persistent/...Action.java`)
- **Kind**: write (all four)
- **Reads (logical)**: `metadata.customs[persistentTasks]`
- **Reads (physical)**: `nodes` (for `StartPersistentTaskAction` — executor assignment); none for the others
- **Writes (logical)**: `metadata.customs[persistentTasks]`
- **Writes (physical)**: none
- **Notes**: persistent tasks live as `Metadata.Custom` (logical storage) but each task's `Assignment` is a node ID — so they're driven by physical (node-set) changes. They're the clearest "logical record of physical work" straddler.

---

## Cross-cutting observations

### Read amplifiers (operations that look narrow but force a wide read)

| Operation | Why it pulls in more than expected |
|---|---|
| `TransportIndicesAliasesAction` | Must enumerate *all* aliases (across all indices) to expand wildcards |
| `TransportPutComposableIndexTemplateAction`, `TransportPutComponentTemplateAction` | Must read *all* templates of both kinds for overlap/dependency validation; PutComposable also reads *all* indices |
| `TransportClusterUpdateSettingsAction` | Most non-trivial settings changes trigger an implicit reroute (full `routingTable` + `nodes`) |
| `TransportCreateSnapshotAction` | Reads `routingTable[<resolved>]` to determine per-shard `ShardSnapshotStatus`, plus all four in-progress customs |
| `TransportDeleteSnapshotAction` | Reads every in-progress custom for concurrency coordination |
| `PutPipelineTransportAction`, `PutSearchPipelineTransportAction` | Fans out a `NodesInfoRequest` to every node for processor compatibility before submitting the state update |

### Straddlers (logical metadata that influences physical behavior, or vice versa)

- **Weighted routing** — logical metadata that downstream physical routing reads.
- **Decommission attribute** — logical attribute; writing it causes voting-config exclusions (physical) to be added.
- **Persistent tasks** — logical task records that hold a physical `Assignment` (node ID).
- **Index settings that drive routing** — `number_of_replicas`, `number_of_search_replicas`, `INDEX_TIERING_STATE`, `INDEX_BLOCKS_SEARCH_ONLY_SETTING`, and various block-related APIBlock settings all live in `IndexMetadata.settings` (logical) but updates to them require a reroute (physical).
- **Ingestion status** — lives as a top-level field on `IndexMetadata`, not a custom. Pause/resume is logical metadata but the shard-level follow-up needs `nodes`.

### "Hidden" physical reads on logical-looking operations

- `nodes` is read for compatibility/version checks by: `PutPipeline`, `PutSearchPipeline`, `CreateSnapshot`, `DeleteSnapshot`, `CloneSnapshot`, `RestoreSnapshot`, `OpenIndex` (version), `CreateIndex` (shard limits), `Resize` (shard limits), `Decommission` (cluster-manager-eligible match), `AddVotingConfigExclusions`, `ClearVotingConfigExclusions` (waitForRemoval), `GetWeightedRouting` (cluster-manager-node id), `Reroute` (everything).

  ⇒ A filter framework that lets operations request only a small projection of `nodes` (e.g. *min/max version*, *cluster-manager id*, *count by awareness attribute*) would unlock real savings.

### Cross-state coordination primitives

Snapshots/restore/repositories form a single coordination web. The same four in-progress customs (`snapshots`, `snapshotDeletions`, `restoreInProgress`, `repositoryCleanup`) are read by **every** snapshot/repository write so the system can serialize long-running operations. A filter design must treat these as a coherent unit ("the snapshot coordination set") — splitting them slows reads but doesn't save anything because every snapshot/repo write needs all four.

### Operations that compute against an ephemeral state, never publish

`TransportSimulateIndexTemplateAction` and `TransportSimulateTemplateAction` construct a transient `ClusterState` for validation but never call `submitStateUpdateTask`. A filter framework should support read-only "validation copy" requests cleanly — but the existing code is fine, because everything stays local.

---

## Logical vs physical: summary

### Pure logical writes
`TransportPutMappingAction`, `TransportAutoPutMappingAction`, `TransportUpgradeSettingsAction`, all template puts/deletes (legacy, composable, component), `TransportIndicesAliasesAction` (alias actions only), all view CRUD, all repository CRUD (definition), all stored-script CRUD, all ingest pipeline CRUD, all search pipeline CRUD, weighted routing put/delete, decommission delete (logically — clears voting exclusions too), persistent tasks update/remove/complete, streaming ingestion pause/resume (logical per-index field).

### Pure physical writes
`TransportClusterRerouteAction`, `TransportAddVotingConfigExclusionsAction`, `TransportClearVotingConfigExclusionsAction`, `TransportCreateSnapshotAction`, `TransportDeleteSnapshotAction`, `TransportCloneSnapshotAction`, `TransportCleanupRepositoryAction`.

### Mixed (writes both)
`TransportCreateIndexAction`, `AutoCreateAction`, `TransportDeleteIndexAction`, `TransportOpenIndexAction`, `TransportCloseIndexAction`, `TransportAddIndexBlockAction`, `TransportScaleIndexAction`, `TransportResizeAction`, `TransportRolloverAction`, `TransportUpdateSettingsAction` (settings → reroute), `TransportClusterUpdateSettingsAction`, `TransportRestoreSnapshotAction`, `TransportRestoreRemoteStoreAction`, `TransportDecommissionAction`, `TransportTierAction`, `TransportCancelTierAction`, `CreateDataStreamAction`, `DeleteDataStreamAction`, `StartPersistentTaskAction` (assignment).

### Splittability assessment (informal)

| Group | Easy to split? | Why |
|---|---|---|
| Pure logical writes | Yes | Already touch only `metadata`/its customs. Could run against a logical-only cluster manager. |
| Pure physical writes | Yes | Already touch only `routingTable`/`nodes`/in-progress customs. |
| Index lifecycle (create, delete, open, close, scale, resize, rollover, addBlock) | **No — these are the hard ones.** | Each one publishes a logical change (settings/state) *and* a routing-table change in the same atomic publish. To split them you have to (a) decide which side runs first and (b) handle the in-between state where, e.g., the index exists logically but has no allocated shards. Most of these are also multi-phase already (close, addBlock, scale, resize), which actually makes the split more tractable — the first phase can be logical only. |
| Settings updates that trigger reroute | Yes-ish | The reroute is a side-effect issued by the logical write. A split design would have the logical CM emit a "reroute desired" event for the physical CM to consume. |
| Snapshot/restore | Mixed | Snapshot create/delete/clone are physical. Restore is mixed (mints indices logically *and* spins up shard recovery physically). Restore is the most concerning one because the recovery source on each shard refers to snapshot metadata — splitting it requires the physical side to be able to consume snapshot metadata that the logical side just minted. |
| Decommission | Mixed but already two-step | Decommission writes a logical attribute then waits for physical exclusions to take effect. The split is already in the data shape. |

### Surprise findings

- **`TransportDeleteDanglingIndexAction` skips block checks entirely** (`checkBlock()` returns `null`). Easy to miss when reasoning about which operations should/shouldn't honor cluster blocks.
- **`TransportPendingClusterTasksAction` doesn't read `ClusterState` at all** — it reads from `clusterService.getClusterManagerService().pendingTasks()`. It's a `TransportClusterManagerNodeAction` only because it has to run on the cluster manager. A `ClusterStateFilter` design should support the "needs nothing" case.
- **`TransportRestoreRemoteStoreAction` may load the *entire remote cluster state*** when `restoreClusterUUID` is set, including global metadata, and write the result locally. This is effectively a state-replacement operation; any external store implementation needs an "import bulk" path.
- **`TransportClusterStateAction` already exposes a request-level filter** with flags for `nodes`, `routingTable`, `blocks`, `metadata`, `customs`, plus per-index narrowing. That request type is the closest existing analogue to what a `ClusterStateFilter` framework would look like and is the obvious starting point.
- **Tiering keeps in-progress state outside `ClusterState`** (in a service-level set rebuilt from `ClusterStateListener` callbacks). That breaks the "everything important is in `ClusterState`" assumption and would have to be reconciled with an externalized-state design.

---

## ClusterStateFilter framework suggestions

These are recommendations for the shape of the framework, intentionally generic — concrete API decisions can come later.

### 1. A filter is a *composite* of *slices*

Operations almost never need exactly one slice. Reroute reads routing + nodes + blocks + in-progress customs + per-index settings. `TransportRestoreSnapshotAction` reads almost everything. So the natural shape is a **set of slice descriptors**, combined by union. The supplier may return a superset of any union, up to the full state. There's no `AND`/`OR` algebra to invent — just `union`.

```java
ClusterStateFilter f = ClusterStateFilter.union(
    Slices.indexMetadata(IndexScope.named(targetIndices), Sections.of(SETTINGS, MAPPINGS)),
    Slices.routingTable(IndexScope.named(targetIndices)),
    Slices.blocks(BlockScope.indices(targetIndices))
);
```

### 2. Slice taxonomy

Based on what every operation actually asks for, the dimensions worth modeling as first-class slices are:

- `ClusterMetadataSlice` — cluster name, version, term, state UUID, persistent + transient settings, coordination metadata, hashes-of-consistent-settings. Always cheap; almost always included.
- `IndexMetadataSlice(scope, sections)` — `scope` ∈ `{ALL, NAMED(set), PATTERN(glob), DATA_STREAM_BACKINGS(name)}`; `sections` ⊆ `{SETTINGS, MAPPINGS, ALIASES, CUSTOM_DATA, STATE, INGESTION_STATUS, IN_SYNC_ALLOCATION_IDS, PRIMARY_TERMS, ROLLOVER_INFO}`. The four-section split from your example (`SETTINGS, MAPPINGS, ALIASES, CUSTOM_DATA`) is a good start; my analysis suggests we'll want `STATE` and `INGESTION_STATUS` as separate sections too.
- `TemplatesSlice(kinds)` — `kinds` ⊆ `{LEGACY, COMPONENT, COMPOSABLE}`.
- `MetadataCustomSlice(types)` — selectable custom types: `dataStreams, views, ingest, searchPipelines, scripts, repositories, decommissionAttribute, weightedRouting, persistentTasks, indexGraveyard, …`.
- `NodesSlice(projection)` — `projection` ∈ `{FULL, MIN_MAX_VERSION, CLUSTER_MANAGER_ONLY, COUNT_BY_AWARENESS(attr)}`. Most "physical" pulls on `nodes` are for compatibility checks or counting — the projection is small.
- `RoutingSlice(scope)` — same scopes as `IndexMetadataSlice`.
- `BlocksSlice(scope)` — `{GLOBAL_ONLY, INDICES(set), GLOBAL_AND_INDICES(set), ALL}`.
- `InProgressSlice(types)` — `types` ⊆ `{SNAPSHOTS, SNAPSHOT_DELETIONS, RESTORE, REPOSITORY_CLEANUP}`. Snapshot/repo ops almost always want all four; that's fine — `union` handles it.
- `CoordinationSlice(parts)` — `parts` ⊆ `{VOTING_CONFIG, VOTING_CONFIG_EXCLUSIONS, TERM, COMMITTED}`. Mostly only voting-config ops need this, so it's worth being granular.

### 3. Scope-expansion needs its own pre-step

Almost every multi-index operation expands wildcards / aliases / data-stream names before it knows which concrete indices to ask for. Today this uses `IndexNameExpressionResolver` against the full `metadata.indicesLookup`. To not pay the cost of materializing all index metadata just to expand, the filter framework should expose a separate lightweight call:

```java
Set<Index> resolved = supplier.resolveIndices(IndexExpression expr, IndicesOptions opts);
```

Implementations can answer this from a much smaller index — typically just the list of `(name, uuid, aliases, data-stream-membership)` per index, without settings/mappings. Once resolved, the operation builds an `IndexMetadataSlice(NAMED(resolved), …)`.

### 4. Two-phase reads for write operations

The action's `clusterManagerOperation` runs against a snapshot taken outside the cluster-manager loop. The state-update task's `execute(currentState, …)` then runs against the latest state *inside* the loop. The two reads often want the same slice but they're separated by an unbounded amount of time and other state updates in between.

Design suggestion: each action declares a *single* `ClusterStateFilter requiredState()` used by both reads. The framework guarantees that `state()` (the snapshot read) returns at least that slice; the state-update task's `currentState` argument is the actually-current full state but the supplier's plugin can also be told ahead of time which slice it'll be asked for.

### 5. Subset-vs-superset contract

This is implicit in your task description but worth restating prominently: the supplier may return *more* than requested. Operations must **not** depend on the absence of state to mean "nothing exists." E.g., if an operation requests `metadata.indices[foo]` and the supplier returns `metadata.indices[foo, bar]`, the operation must filter its own results — it must not assume `bar` doesn't exist. This is easy to get wrong, and worth a static-analysis-friendly idiom (e.g. requiring callers to access state only via methods that take the filter, like `state.indices(scope).get(name)`).

### 6. Default and migration

Keep `ClusterStateFilter.FULL_STATE` as the default for actions that haven't been annotated yet — so the migration is incremental. The compiler can't help here; pick a few canonical operations (`TransportClusterStateAction`, `TransportGetMappingsAction`, `TransportPutMappingAction`, `TransportClusterRerouteAction`) as proof points, get them down to a narrow slice, then expand outward.

### 7. Where the existing code is already most filter-shaped

- `TransportClusterStateAction`'s request flags map almost directly onto the slice taxonomy above. Use it as the first end-to-end test of the framework.
- `TransportGetIndexAction`'s feature flags (`MAPPINGS`, `ALIASES`, `SETTINGS`, `CONTEXT`) are exactly the `sections` enum for `IndexMetadataSlice`.
- `IndexNameExpressionResolver` is the obvious extension point for the lightweight `resolveIndices(...)` call.

### 8. Things to *not* try to filter (at first)

- `coordinationMetadata` — small and read on every cluster-manager-loop iteration. Always include.
- The in-progress customs as a group — they're small, almost always wanted together for any snapshot/repo write, and serializing them individually saves nothing.
- `metadata.persistentSettings` / `metadata.transientSettings` — small, frequently consulted for default-value resolution. Always include.

### 9. Things that don't fit the slice model cleanly

- **Allocation deciders** (read by reroute and `TransportClusterAllocationExplainAction`) consult per-index settings across the *full* index set. Until allocation is moved off the cluster-manager update path, reroute will always need full state.
- **Tiering service's in-memory `tieringIndices`** — not in `ClusterState`. A future filter-driven supplier won't help here; that service needs refactoring to derive its state from `IndexMetadata.customData[tiering]` rather than from `ClusterStateListener` callbacks.
- **Allocation-affecting cluster settings** — `TransportClusterUpdateSettingsAction` looks like a logical write but the implicit reroute drags in full physical state. The split design suggested earlier (logical CM emits a "reroute desired" event for the physical CM) would actually be necessary here, not just nice-to-have.
