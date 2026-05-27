---
name: rama
description: >-
  Rama module development process and API reference. ALWAYS use this skill
  before writing, reading, debugging, or explaining any Rama code. Do not
  write Rama modules, topologies, PStates, or depot code without loading
  this skill first and following its phased build process.
---

# Rama Development Skill

## How to Build a Rama Module

Read `references/phases.md` for the overview of the multi-phase process. Each phase has a dedicated doc under `references/phase-N-*.md` with the full instructions for that phase, including which artifact template to copy and what to fill in.

**One phase per agent session.** Each phase is a fresh context, reads exactly one phase doc, produces exactly one artifact, and stops. Validation phases emit a verdict consumed by the calling system; on FAIL the prior phase is re-invoked with the failure artifact as input. This isolation is intentional: empirically, agents that walk multiple phases in one session anchor on early design decisions and fail to revise them when later thinking surfaces problems.

Every phase produces a required artifact. Skipping artifacts leads to wrong PState schemas, wasted disk I/O, and topologies that need to be rewritten.

**If context compacts**, re-read the current phase doc and the artifact you're filling in — both contain the full instructions on disk.

**Always design for production.** InProcessCluster is a test harness — production has node failures, process crashes, and retries. Never downgrade a correctness guarantee because "tests won't hit that case." Design the module to be correct under all conditions, then test it as best as possible with IPC.

**Every task is single-threaded.** All topology processing, query invocations, and foreign selects on a task execute sequentially with exclusive access to all PState partitions on that task. Within a single event, reads and writes to any number of PStates on the same task are atomic — no locking or concurrency coordination needed.

**Because tasks are single-threaded, topology code must use cooperative multitasking.** Long-running synchronous work on a task blocks everything else — including query topology invocations, creating latency spikes for reads. Risk factors: large `local-select>` calls on subindexed structures that iterate many entries, or `loop<-` with many iterations of PState reads/writes. Use `{:allow-yield? true}` on large reads of local PStates, such as iterating on a subindexed structure (e.g. `(local-select> [(keypath *k) MAP-VALS] $$p {:allow-yield? true} :> *v)`), and `(yield-if-overtime)` in long loops to periodically yield the task thread. See "Yielding" section in `references/dataflow.md` for details on both.

**Worker restart does NOT replay depot history.** PStates are durable storage in their own right — replicated and persisted to RocksDB at write time, NOT a materialized view recomputed from depots — and topologies persist their consumed offset / microbatch ID and resume from where they left off on restart of worker processes.

**Never trade I/O efficiency for code simplicity.** Rama modules are production backends serving millions of queries. Unnecessary I/O is not just a latency concern for one query — it is wasted resources that will lower the throughput the module can handle. Code complexity is a one-time cost; unnecessary I/O is a per-query cost multiplied by every invocation in production. When there is a conflict between simpler code and fewer disk reads or network roundtrips, always choose fewer I/O operations.

**Never trade fault tolerance for code simplicity.** Duplicate side effects from retried processing are bugs, not acceptable tradeoffs. Every write must produce correct results even if processing retries. Do NOT dismiss retry-safety concerns as "acceptable for this use case."

**Never delete data unless the spec explicitly requires it.** Read operations can be called at any time, including after processing is complete. Deleting data that read operations depend on breaks the contract, even if the spec is silent on deletion.

**Performance costs to design around:**
- **RocksDB seek** (point read or iterator seek): ~0.3-0.5ms on SSD. This is the expensive disk operation.
- **RocksDB iterator Next** (sequential read after initial seek): ~1-10µs. 50-100x cheaper than a seek. A range scan of 100 contiguous entries costs ~0.5ms (1 seek) + ~0.1ms (100 iterations) ≈ 0.6ms. But 100 separate point reads cost ~50ms (100 seeks). Design PStates so queries use few seeks with sequential iteration over subindexed sorted maps/sets/vectors rather than many random point lookups.
- **Network roundtrip**: each `foreign-select`/`foreign-select-one`/`foreign-invoke-query` from client code is a full network roundtrip. Partitioner calls in topologies also add network latency. Minimize roundtrips by using query topologies instead of multiple client-side foreign selects.

Write-path work (updating an extra precomputed level or denormalized view) happens once per event and is amortized across all future queries. A small increase in write-path work that dramatically reduces read-path seeks is almost always worth it. When evaluating a design, estimate worst-case cost as: (number of seeks × ~0.5ms) + (number of iterated entries × ~5µs).



### When to Use the Full Phased Approach

The full Phase 0-4 process applies to **module implementations** — tasks that involve designing depots, PStates, and topologies together.

For simpler tasks (e.g., implementing a single `deframafn`, fixing a bug, or explaining existing code), use judgment. Still load and consult references relevant to the task, but the full planning artifact may not be needed.

## Implementation Goals

Every Rama implementation should optimize for:

1. **Balanced computation across tasks.** Work should distribute as evenly as possible across all N tasks. Avoid funneling writes through `|global` (task 0) for high-throughput data, unless it's filtered down first with two-phase aggregation in batch blocks. Partition depots by the key that will be used for PState lookups.

2. **Balanced storage across tasks.** PState data should spread evenly. Choose depot partitioners that match PState key access patterns so data colocates naturally. Global PStates (`:global? true`) are only for singletons (config, counters, ID generators).

3. **Colocate related data.** Design PState partitioning around the application's core queries, not just the top-level key. For example, in a social network: `$$accounts`, `$$account->posts`, and `$$post->likes` might all partition by account ID (not post ID for likes), because the core query is fetching a timeline — all information about a user's posts should be colocated on the same task. `select>` is convenient when repartitioning is needed, but the goal is to minimize how often that's necessary. There are always tradeoffs — optimizing colocation for one query pattern may require repartitioning for another.

4. **Subindexing for large collections.** PState collections that grow beyond ~100 elements should use `{:subindex? true}`. This enables O(1) lookups, efficient range queries, and avoids loading entire collections into memory. Don't subindex small collections (< 50 elements) — overhead without benefit.

5. **Correct partition alignment.** Every `local-select>`/`local-transform>` must be preceded by a partitioner that routes to the correct task for the key being accessed, if it's not already on that task. Misalignment is silent — it compiles, runs, and produces wrong results.

6. **Appropriate topology choice.** Stream for low-latency + ack coordination. Microbatch for everything else (exactly-once, cross-partition atomicity, higher throughput).

7. **Minimize storage I/O.** Each path navigation through a top-level key or subindexed structure is a RocksDB read. Choose the approach that minimizes total reads:
   - If you do NOT need the current value: use `(term f)` for read+write in one operation (e.g., `(term inc)` to increment without reading separately).
   - If you already HAVE the value (from a prior `local-select>`): compute the new value and use `(termval *new-val)` — this does NO read, just a write. Do NOT use `(term f)` to re-read a value you already have.
   - `keypath` + `termval` directly skips the read entirely; `keypath` + further navigation does read. See `references/paths.md` "No-Read Optimizations" for details and other similar optimizations.

## Quick Reference

### Variable Conventions

- `*var` — value binding
- `$$pstate` — PState reference
- `%frag` — fragment var (microbatch source binding or anonymous operations)
- `**unground` — outer join variable (nullable, batch mode)
- `:>` — output binding

### Core Concepts

Read `references/core-concepts.md` for the full reference on: dataflow language (`:>` binding, `<<if`, `loop<-`, emit semantics), paths (navigators, `local-select>`, `local-transform>`), foreign context (depot appends, PState queries, ack levels), mirrors, ACID/replication, serialization, and task model (partition alignment, tasks/threads/workers).

## Other References

### Cross-Module Composition
- `references/mirrors.md` — mirror declarations, cross-module depot/PState/query access, partition routing, ack semantics

### Migrations
- `references/pstate-migration.md` — PState schema migration patterns, idempotency, subindex conversion
- `references/depot-migration.md` — depot record migration, DEPOT-TOMBSTONE, migration IDs

### Operations
- `references/operate.md` — cluster setup, CLI commands, module deploy/update/scale, monitoring, upgrades, module management functions, dynamic options

### Formal Model & Syntax
- `references/formal-model.md` — typed lambda calculus model: types, effects, judgments, ownership, visibility, transaction scope, invariants
- `references/syntax.md` — EBNF grammar for declarations, built-in ops with emit cardinality, custom operations, partitioners, state interaction, source/ingress

### External Documentation
- Documentation index: https://redplanetlabs.com/docs/~/index.html
- Clojure API: https://redplanetlabs.com/clojuredoc/index.html
- Defining modules: https://redplanetlabs.com/docs/~/clj-defining-modules.html
- Dataflow language: https://redplanetlabs.com/docs/~/clj-dataflow-lang.html
- Intermediate dataflow: https://redplanetlabs.com/docs/~/intermediate-dataflow.html
- Paths: https://redplanetlabs.com/docs/~/paths.html
- Stream topologies: https://redplanetlabs.com/docs/~/stream.html
- Microbatch topologies: https://redplanetlabs.com/docs/~/microbatch.html
- Query topologies: https://redplanetlabs.com/docs/~/query.html
- Depots: https://redplanetlabs.com/docs/~/depots.html
- PStates: https://redplanetlabs.com/docs/~/pstates.html
- Partitioners: https://redplanetlabs.com/docs/~/partitioners.html
- Aggregators: https://redplanetlabs.com/docs/~/aggregators.html
- Module dependencies: https://redplanetlabs.com/docs/~/module-dependencies.html
- ACID semantics: https://redplanetlabs.com/docs/~/acid.html
- Replication: https://redplanetlabs.com/docs/~/replication.html
- Custom serialization: https://redplanetlabs.com/docs/~/clj-serialization.html
- Testing: https://redplanetlabs.com/docs/~/clj-testing.html
- All configs: https://redplanetlabs.com/docs/~/all-configs.html
