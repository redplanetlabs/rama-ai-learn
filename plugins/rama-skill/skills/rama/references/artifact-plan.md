# Plan

<!-- Phase 1 Step 5. Fill in after completing Steps 1-4. After completing this, fill in PLAN_VALIDATION.md. -->

## Reads
<!-- For each read operation: access method, path expression, partition -->

## Writes
<!-- For each write operation: depot, event type -->

## PState Design
<!-- For each PState:
If obvious: why this is the only reasonable design
If not obvious:
  Option A: schema — query cost: N seeks, M iterations because why
  Option B: schema — query cost: N seeks, M iterations because why
  Chosen: A or B because why -->

## Depots
<!-- - *name: (hash-by :key), event types [...] -->

## Topologies and PStates
<!-- Each topology owns zero or more PStates. Only the owning topology can write to a PState.
A module can have multiple topologies of different types.
Default is microbatch (exactly-once, cross-partition atomic).
Use stream when: foreign-append! must block until PState changes are visible.
If stream: can a partial failure + retry leave any writes permanently unexecuted or duplicated non-idempotently? If yes, fix the streaming logic or move that logic to a microbatch topology.

- topology-name: microbatch | stream, because why
  If stream: list each PState write and whether it is idempotent or non-idempotent.
    Non-idempotent writes in stream topologies will duplicate on retry.
    Move non-idempotent writes to microbatch, make them idempotent, or explain how those writes are deduplicated.
  PStates:
    - $$name: full typed schema — no Object anywhere -->

## Query Topologies
<!-- - name:
  Input example 1: describe input → N total reads, M meaningful (non-empty) reads
  Input example 2: different input → N total reads, M meaningful (non-empty) reads
  Fixed or variable: fixed if M is same for all inputs, variable if M differs
  If variable, dynamic approach: ops/explode + aggregator | loop<- | etc. -->

## Design Decisions
<!-- - Subindexing: which PState collections are subindexed and why
- Colocation: how depot partitioning aligns with PState keys -->

## State primitive selection
<!-- For every piece of state in the design, name the storage class and justify:
- $$name (PState): per-source-event write volume O(?) in <bounded inputs>. Durable.
- *cache (TaskGlobal): non-durable. Rebuild by reading from PState on each cache miss. Cite the code that does it.
- external: <name>  -->

## Resource usage analysis
<!-- For each storage location (PState, TaskGlobal), estimate the resource footprint per task under load. The goal is to minimize memory and disk usage while staying within latency constraints.

### Disk usage (PStates)
For each PState and depot, estimate bytes per entry and total size per task:
- Entry size: key size + value size (include all fields, nested structures, index overhead for subindexed structures)
- Growth rate: how many entries per unit time
- Total per task: entries × entry size / number of tasks

### Memory usage (TaskGlobals)
For each TaskGlobal, estimate:
- Entry size in bytes: count every field stored per entry. Use primitives (long, int) instead of boxed objects (Long, Integer) where possible.
- Entries per task: worst-case count
- Total memory per task: entries × entry size
- GC pressure: large object graphs with many small maps/vectors create GC overhead. Flat structures, primitive arrays, or compact representations MUST be used when possible since GC causes long pauses which degrade latency-sensitive operations like foreign reads.

### Minimization
For each storage location, state whether the current design is minimal or whether data could be reduced:
- Can any of the data in the cache be fetched from a PState at query time instead of cached in memory without violating latency requirements?
- Can fields be stored as primitives instead of objects?
- Can per-entry overhead be reduced by using arrays or packed representations instead of maps?
- Does the design duplicate data across storage locations? If so, justify why (latency constraint) or eliminate. -->
