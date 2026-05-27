# Rama Application Design Methodology

Design a Rama application by working top-down: tasks → PStates →
queries → data types → depots → ETL topologies → query topologies →
tests. Each step validates and refines the previous.

## Task execution model and concurrency

A module runs on a fixed number of **tasks** (partitions). Every depot, PState, and topology is sharded across all tasks. Tasks are distributed across **threads**, and threads across **workers** (JVM processes on cluster machines). For example: 64 tasks / 32 threads / 8 workers = 2 tasks per thread, 4 threads per worker. Task count is fixed at module creation; scaling is done by adjusting threads and workers. The number of tasks is specified on module deploy and is fixed, and is an upper bound on how many nodes a module can be scaled.

Dataflow computation moves between tasks via **partitioners** (`|hash`, `|origin`, `|all`, `|custom`). A partitioner relocates the current event to a different task — typically to reach the partition where a PState key lives. Depot partitioners (`hash-by`, `custom`) determine which task initially processes each appended record. Rama automatically handles serialization and transfer of all dataflow variables used after a partitioner, including anonymous `<<ramafn` and `<<ramaop` values.

Every task is single-threaded. All topology processing, query topology invocations, and foreign selects on a task execute sequentially — never concurrently. While code runs on a task, it has exclusive access to all PState partitions on that task.

Consequences for correctness:
- All reads and writes to all PStates on a task within a single event are atomic and sequential. An event that updates `$$p1` and also `$$p2` on the same task does so atomically — no other event can interleave.
- Read-then-write within the same event is always safe. No locking, CAS loops, or atomic coordination needed.
- Check-and-set within a single event (e.g., register only if userId is null) is safe because no concurrent event for that entity runs on the same task.
- Write-then-read within a topology always sees the write. A `local-select>` after a `+compound` or `local-transform>` on the same task returns the updated value. Writes are buffered for later commit that will make them externally visible to foreign selects, but locally they are immediately visible.
- Two events repartitioned to the same task in any topology are processed one after the other — each sees the prior event's writes.
- Encode all side-effects of an event as a single depot append. Do not split across multiple appends — partial failure between appends leaves inconsistent state.

Do NOT add concurrency guards for same-partition operations — they are unnecessary and add complexity.

## Step 1: Enumerate Tasks

List every operation the application must support, grouped by domain
entity. Each task is either a **write** (state mutation via depot
append) or a **read** (query against PStates).

For each task, capture:
- Operation name
- Input parameters
- Expected result (for reads)
- Latency requirement (milliseconds vs hundreds of milliseconds)
- Cardinality expectations (e.g., "millions of friends per user")

Categorize tasks:
- **Point reads**: fetch single value by key
- **Range reads**: paginate through sorted collections
- **Aggregation reads**: compute over ranges (counts, sums)
- **Cross-entity reads**: join data from multiple PStates
- **Writes**: append events that mutate state
- **Writes that return computed values**: if a write operation needs to return a value computed during processing, use a stream topology with `ack-return>` to send the value back to the caller via the ack response

## Step 2: Design PStates

Map tasks to PStates. The goal: every read task must be answerable by
navigating one or more PStates efficiently with Rama's path API.

Principles:
- **One PState per access pattern** — if two tasks need different
  key structures over the same data, use two PStates.
- **Subindex inner collections** that will grow large (hundreds+
  elements). Subindexed maps and sets are sorted, enabling range
  queries and efficient SIZE operations.
- **Use fixed-keys schemas** for record-like structures (profile
  fields, config). Analogous to named columns in relational DBs.
- **Global PStates** for singleton values (e.g., counters, ID
  generators). Only one partition, on task 0.

Schema notation for design documents:

```
$$profiles:  {userId<String>: fixed-keys{displayName String, email String, ...}}
$$friends:   {userId<String>: Set<userId<String>>}  ;; subindexed
$$posts:     {userId<String>: {postId<Long>: Post}}  ;; subindexed
$$postId:    <Long>  ;; global, per-task unique ID
$$views:     {userId<String>: {hourBucket<Long>: count<Long>}}  ;; subindexed
```

Validation: for every read task, write the path expression that
answers it. If you cannot write a simple path, revisit the PState
structure.

## Step 3: Define Queries

Write every query as a path expression against the designed PStates.
This validates PState structure before writing any ETL code.

Query patterns:

| Pattern | Path shape | Example |
|---|---|---|
| Point lookup | `(key userId field)` | get password hash |
| Submap select | `(key userId) (submap k1 k2 ...)` | get profile fields |
| Collection size | `(key userId) (view count)` | friend count |
| Set membership | `(key userId) (set-elem other)` | are-friends? |
| Sorted range | `(key userId) (sorted-set-range-from start opts)` | paginate friends |
| Map range + agg | `(key userId) (sorted-map-range lo hi) (subselect (map-vals)) (view sum)` | profile views over hours |

Cross-partition reads that need data from multiple PStates or
partitions, or that need many queries to the same partition require a **query topology** to minimize roundtrips (Step 7).

## Step 4: Define Data Types

Define first-class types for all depot records and query results.

Categories:
- **Depot records** — events appended by clients. Use strong field
  validation; fail early on invalid data rather than debugging
  downstream.
- **Query results** — returned to clients from PState queries or
  query topologies.

All types that cross the network (depot appends, topology transfers,
PState queries) must be serializable.

In Clojure, depot records are typically maps or records. In Java,
implement `RamaSerializable`.

## Step 5: Design Depots

Each depot is an append-only partitioned log. Design along three axes.

### Scope: which event types share a depot?

**Same depot** when:
- Events affect the same PStates AND require ordering guarantees
  relative to each other (e.g., FriendRequest and
  CancelFriendRequest from the same user must be processed in
  order).
- Use `sub-source` / `subSource` to dispatch by event type within
  the topology.

**Separate depots** when:
- Events are logically independent.
- Events have different throughput or latency profiles.
- Events need different ack levels.

### Partitioning

Partition by the key that determines which task processes the event.
Events for the same entity land on the same partition, guaranteeing
sequential processing per entity.

Choose the partition key so the depot is **colocated** with the
primary PState the event updates — this avoids repartitioning in the
ETL.

### Ack level

- **ACK** (default): depot append returns after all colocated
  stream topologies finish processing. Client can immediately query
  updated PStates.
- **ACK_APPEND**: returns after data is durably stored on the depot
  partition. Use when write-and-read-back is not needed.

## Step 6: Implement ETL Topologies

For each depot, implement one or more topologies that consume events
and maintain PStates.

### Stream vs Microbatch

| Factor | Stream | Microbatch |
|---|---|---|
| Latency | Milliseconds | Hundreds of milliseconds |
| Throughput | Lower | Higher (less per-record overhead) |
| PState updates | At-least-once or at-most once | Exactly-once |
| Cross-partition atomicity | No | Yes in every case |
| Ack coordination | Participates in depot ack | Decoupled from depot append |

**Default to microbatch.** Exactly-once semantics eliminate a class of
correctness bugs, and batched I/O gives higher throughput. Use stream
only when: (1) single-digit millisecond update latency is required, or
(2) the application's client-facing API requires `foreign-append!` to
block until PState updates are visible — e.g., a user registration
endpoint that must return the created account synchronously, or a
friend request endpoint where the caller must see the updated state
immediately upon return.

### ETL patterns

**Conditional write** — write only if key does not exist (idempotent
registration):
```
local-transform PState
  (key userId) (filter-pred nil?)
    (multi-path
      (key "email")    (term-val email)
      (key "name")     (term-val name))
```

**Bidirectional relationship** — one depot append updates two
partitions using anchor/hook or sequential partition hops:
```
source *depot -> extract fields
  compound-agg $$outgoing (map userId (set toUserId))
  hash-partition toUserId
  compound-agg $$incoming (map toUserId (set userId))
```

**Type dispatch** — `sub-source` to branch by event type within a
single depot source:
```
sub-source *event
  FriendRequest:    add to sets
  CancelFriendRequest: remove from sets
```

**Time-bucketed aggregation** — convert timestamp to bucket, use
compound-agg with count:
```
(quot *timestamp 3600000 :> *bucket)
(+compound $$views {*user-id {*bucket (aggs/+count)}})
```

## Step 7: Implement Query Topologies

Use query topologies for reads that:
- span multiple partitions,
- join data from multiple PStates, or
- require multiple reads on the same partition that should be batched into one roundtrip

A query topology is a batched computation using the same dataflow API as ETL topologies.

Pattern:
1. Partition to the entity's task.
2. `local-select>` the primary PState.
3. Explode results.
4. Partition to related entities' tasks.
5. `local-select>` related PStates.
6. Combine results with aggregator and return.

Query topologies execute server-side — only the final result
transfers to the client. This replaces N+1 client round-trips with
a single invocation.

## Step 8: Test with InProcessCluster

Test pattern:
1. Create `InProcessCluster`.
2. Launch the module with a `LaunchConfig` (tasks, threads).
3. Append data to depots.
4. Assert PState changes.

Coordination:
- **Stream topology**: depot append with ACK returns only after
  processing completes. Assert immediately after append.
- **Microbatch topology**: use `wait-for-microbatch-processed-count`
  to block until N records are processed, then assert.

Test query topologies by invoking them as functions after setting up
state through depot appends.

## Design Checklist

- [ ] All tasks enumerated and categorized (read/write)
- [ ] Every read task has a path expression on a designed PState
- [ ] Subindexed all inner collections that grow beyond hundreds
- [ ] Related event types share a depot when ordering matters
- [ ] Depot partition key matches primary PState partition key
- [ ] Stream chosen for low-latency write-read-back; microbatch for throughput
- [ ] Cross-partition reads use query topologies, not N+1 client queries
- [ ] Single depot append encodes all side-effects of an event
- [ ] Tests cover both stream (assert after append) and microbatch (wait then assert)
