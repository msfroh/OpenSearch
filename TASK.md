# Task Description

## Objective

I would like to allow OpenSearch cluster managers to run with externalized `ClusterState`, where the actual
`ClusterState` reading and writing is implemented in a plugin. My proposed solution for this is to let
`org.opensearch.cluster.service.ClusterManagerService` take a custom `ClusterStatePublisher` and `ClusterStateSupplier`,
injected from a plugin on startup.

The related GitHub issue is https://github.com/opensearch-project/OpenSearch/issues/20443

## Background / Context

Currently, OpenSearch models a **lot** of context in `ClusterState`, including:
* the set of nodes in a cluster,
* the schema (also known as "mappings") for every search index,
* the assignment of every shard of every index to nodes in the cluster,
* various additional kinds of metadata (aliases, templates, search pipelines, ingest pipelines),
* settings for the cluster and individual indices,
* and more.

Not every node in the cluster needs all of this information. For example, in a large-scale deployment, we are likely
to have dedicated coordinators that serve client requests and fan out to data nodes. In this case, the coordinators
do need to know which data nodes hold which shards for each index, but the coordinators don't actually care about the
mappings for each index. A data node generally needs to know which shards it hosts and the mappings for the index
associated with each shard, but it doesn't need to know what any other node is doing. I would like to avoid propagating
the full `ClusterState` to every node in the cluster on every update. Ideally I would like to avoid storing the full
`ClusterState` in memory on the elected cluster manager leader node.

Instead, in the `org.opensearch.cluster.service.ClusterManagerService.runTasks` method, I would like it to fetch just
enough `ClusterState` from a `ClusterStateSupplier` based on the `TaskInputs` (though fetching the full `ClusterState`
is functionally correct, just inefficient, since it's sufficient to process any task). Then, when we call
`org.opensearch.cluster.service.ClusterManagerService.publish`, that should persist the cluster state changes to
"somewhere" as determined by the plugged in `ClusterStatePublisher` implementation. I'm going to generally assume
that the `ClusterStateSupplier` and `ClusterStatePublisher` implementations should be provided by a single plugin, and
it would be an error to load multiple plugins that provide `ClusterStateSupplier` and `ClusterStatePublisher` instances.

Examples of places where a cluster manager could read and write cluster state:
* To local disk, in order to handle cluster state that is too large to fit in memory.
    * We can use a directory tree to break up the various pieces of the cluster state.
* To network-attached disk, where the various data nodes and coordinators could have a read-only view of the published
  state.
* To an object store, whether cloud-based (S3, GCS, Azure blob store, etc.) or on-prem (e.g. HDFS).
* To a key-value store, like etcd, Cassandra, DynamoDB, etc.

In practice, we have an implementation of data nodes and coordinators that are able to read cluster state from etcd
at https://github.com/opensearch-project/cluster-etcd.

I've glossed over a lot of details. Currently, the cluster manager does too many things at once with a single API
call from a client. For example, currently, when you create an index, the cluster manager allocates all shards for the
index and then proceeds to assign them to data nodes.

I have an additional goal to decouple logical metadata (indices, aliases, templates, ingest/search pipelines) from
physical metadata (mostly shard allocation). Managing logical metadata does not rely on knowing what nodes are in an
OpenSearch cluster. Meanwhile, managing physical metadata has a much smaller client-facing API (mostly the
`/_cluster/reroute` API). As a secondary goal for this task (possibly before writing the code), I would like you to
identify all existing cluster manager operations that modify both logical and physical metadata. Ideally, we will be
able to split these operations into their logical component (which will typically run first) and their physical
component. I'd like to review this list to see if there are any surprise operations that are likely to be more difficult
to detangle.

### Fetching partial cluster state

A piece that is not completely clear to me yet is how to best filter cluster state retrieval. Currently, cluster state
is held in memory as a single (potentially large) object with references to many other metadata objects, so we always
have the whole thing. Once we move cluster state to an external store, we will want to support retrieving and updating
partial cluster state. For example, if someone wants to modify the mapping for an index, we should ideally be able to
fetch just the existing mapping for that index, validate that the changes are correct (i.e. we are not modifying an
existing field), apply the changes, and write the changes back to the store. Throughout, the cluster manager logic
should not change.

In order to do that, I think we'll want the `ClusterStateSupplier` interface to declare a method like:

```java
ClusterState getClusterState(ClusterStateFilter clusterStateFilter);
```

The `ClusterStateFilter` type should be an interface. I'm imagining a possible implementation kind of like:

```java
public record IndexMetadataClusterStateFilter(Index index, Set<IndexMetadataType> requestedMetadata) implements ClusterStateFilter {
    public enum IndexMetadataType {
        SETTINGS,
        MAPPINGS,
        ALIASES,
        CUSTOM_DATA
    }
}
```

I don't really know how I want to implement the cluster state filtering logic, though. I'm definitely open to
suggestions.

In my opinion, the filter types should live in the `server` sub-project so they can be used by cluster manager update
actions. The actual filtering logic needs to live in plugins, since the plugins will know how their implementation has
split the cluster state across objects, records, etc.

Note that it's acceptable for a given implementation to return a superset of the `ClusterState`. A given cluster
manager operation can say, "I require the following parts of the cluster state to perform my operation". Then the
`ClusterStateSupplier` implementation can try to identify the minimal superset of `ClusterState` that can be returned,
to reduce the amount of metadata loaded from the external store. Under the current "`ClusterState` is fully loaded in
memory" behavior, just returning the whole `ClusterState` is fine. That is, the current `ClusterStateSupplier` could
just delegate to `org.opensearch.cluster.coordination.Coordinator.getStateForClusterManagerService` (which seems to be
the current `Supplier<ClusterState>` implementation).

For now, I think we'll need all operations to "require" the full cluster state. To reduce code changes, I think we can
make the current implementation of submitStateUpdateTask an overload that delegates to a filtered version, but passes
a "FULL_STATE" filter. Over time, we can scrutinize individual operations and reduce the filters to the specific subset
of state needed for the given operation.

## Requirements
- [ ] Analyze existing cluster manager operations to understand which ones modify logical state, which modify physical
      state, and which modify both. Output that as a report in a Markdown file, say "cluster_manager_operations.md".
- [ ] Create a ClusterStateFilter interface.
- [ ] Create a ClusterStateSupplier interface.
- [ ] Create a ClusterStatePersistence interface that plugins can implement to return a ClusterStateSupplier and a
      ClusterStatePublisher.
- [ ] Modify `DiscoveryPlugin` to add a `getClusterStatePersistence` method (which returns `Optional.empty()` by default).
- [ ] Figure out how to wire the `ClusterStateSupplier` and `ClusterStatePublisher` into `ClusterManagerService`.
      Maybe we pass `Optional<ClusterStatePersistence>` into the constructor for `Coordinator`. We can set the
      `ClusterStateSupplier` there. Finally, we can make `Coordinator`'s `publish` method delegate to the
      `ClusterStatePersistence`'s `ClusterStatePublisher`, if applicable.
- [ ] Create a plugin (under the `plugins` directory) that writes cluster state to files.
  - [ ] The root cluster state file should have a list indices in the cluster, but not the indices' metadata.
  - [ ] Each index's metadata should be stored in a separate file.
  - [ ] Each node should have a file with that node's routing table. The filename should have a UUID suffix.
        This allows data nodes to see which shards they need to host.
  - [ ] Each index should have a file with that index's routing table. The filename should have a UUID suffix.
        Note that this duplicates the information from the node routing table, but from an index-centric view, which
        will be more useful for routing traffic to the correct nodes.
  - [ ] To atomically update all routing tables, we should write a single manifest file that references all current
        UUID-suffixed routing table files. Assuming the individual files have all been flushed and synced before the
        manifest is written, we can guarantee that the full state is visible when we read the updated manifest.

## Constraints

Minimize changes to other parts of the codebase. Assuming we overload submitStateUpdateTask, most existing actions
should still work.

## Affected Areas

TOOD

## Examples

TODO

## How to Verify

TODO

## Out of Scope

For now, let's focus on the behavior of cluster manager nodes in terms of reading and updating cluster state persisted to an
external store.
