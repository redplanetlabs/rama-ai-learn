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

TOPOLOGY TYPE RULES:
- Default is microbatch. Every topology is microbatch unless one of these forces stream:
  (a) millisecond-level update latency — the caller needs PState changes visible immediately
  (b) ack coordination — the caller needs to block or receive a return value via the depot append
- A single user-facing operation often has multiple processing concerns with different latency
  requirements. Separate them into different topologies — stream for the latency-sensitive part,
  microbatch for everything else. Multiple topologies can consume the same depot independently, or the
  stream topology can communicate to the microbatch topology with an internal depot.

For each topology, list:
- topology-name: microbatch | stream
- Why this type: cite which stream reason applies, or state "default microbatch"
- For each processing concern in the topology: does it actually require this topology type?
  If any concern does not need the topology's stream/latency guarantees, it belongs in a
  separate microbatch topology.
- If stream: list each write and whether it is idempotent or non-idempotent.
    Non-idempotent writes in stream topologies will duplicate on retry.
    Move non-idempotent writes to microbatch, make them idempotent, or explain the dedup mechanism.
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
