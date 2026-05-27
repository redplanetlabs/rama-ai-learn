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
