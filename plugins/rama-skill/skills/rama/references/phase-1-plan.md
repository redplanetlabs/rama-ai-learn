# Phase 1: Plan

Produce `PLAN.md` — a complete design covering reads, writes, PStates, depots, and topologies. Do NOT write any topology or ETL implementation code in this phase. Implementations without plans produce wrong PState schemas, incorrect partition alignment, wrong query topology patterns, and unnecessary disk I/O.

## Inputs

- The user-facing spec
- `<impl-root>/IMPLICIT_SPEC.md` (from Phase 0)
- This skill

## Steps

### Step 1 — Enumerate reads and writes

Read `references/app-design.md`. List every operation the system needs to support. Each write becomes a depot append.

For each read, decide the access method by answering these questions in order:
1. Can this read be answered by a single `foreign-select` or `foreign-select-one` with a path expression against one PState on one partition? If yes → use `foreign-select` or `foreign-select-one`.
2. If not — does it need multiple reads from the same PState or reads from multiple PStates? If yes → use a **query topology**. Every read that requires more than one PState read to produce its result should use a query topology. Client-side multi-read with multiple foreign selects is an anti-pattern (N roundtrips instead of 1).

For each query topology, read `references/query-topologies.md` and answer this question:

**Does the number of meaningful reads vary based on the query topology input?** A read is meaningful only if it navigates to entries that affect the output. A read that returns an empty collection or zero entries is NOT meaningful — it is a wasted disk seek. Even if the latency of a few extra seeks is acceptable for a single query, wasted reads consume disk and CPU resources that reduce the module's overall throughput under load. Work through concrete examples — try different inputs and count only the meaningful reads. If some inputs need fewer meaningful reads than others, the count is variable.

If variable: the query topology MUST handle the count dynamically and issue only the reads actually needed for each input. Do NOT hardcode N separate `local-select>` calls and let some return empty when it can be avoided. Use `ops/explode` with aggregation, `loop<-`, or another dynamic approach.

Write in the plan for each query topology:
- Concrete examples of different inputs and how many reads each needs
- Whether reads are fixed or variable
- If variable: how the variable reads are handled dynamically (e.g., `ops/explode` + aggregator, `loop<-`)

Note latency requirements (stream vs microbatch) and throughput expectations.

### Step 2 — Design PStates (work backwards from reads)

Read `references/pstate-schema.md` and `references/pstate-schema-clojure-api.md`. Also read `references/patterns.md` for architectural patterns (event-sourced view, fan-out materializer, CQRS, co-partitioned join) that inform denormalization tradeoffs.

For each read operation, design PStates that make that read efficient:

- **Simple lookups** (one entity by key): one `foreign-select-one` with a path expression. Design the PState so the path navigates directly to the answer.
- **Multi-field reads** (several fields of one entity): one `foreign-select-one` with a path that selects a map or fixed-keys structure.
- **Cross-partition reads** (e.g., gathering data from multiple entities): use a query topology that partitions to each relevant task, does `local-select>`, and aggregates results.
- **Multi-read aggregation** (multiple reads on the same partition combined into one result): use a query topology to batch all reads into a single roundtrip instead of multiple foreign selects from the client.
- **Denormalized views** (precompute expensive queries): materialize a PState that directly answers the query, updated by the topology as data flows in.

Do NOT create separate PStates for multiple categories of data that share the same schema and partition key — this leads to complicated read code with conditionals in both foreign client code and query topologies. Use one PState with a category dimension (e.g., a fixed-keys-schema or map key for the category) instead — this leads to much simpler query code.

Do NOT commit to the first PState design that comes to mind — PState schema is the hardest decision to change later and the wrong schema leads to excessive seeks, complex query code, or both. If the optimal design is obvious (e.g., simple key-value lookup), state why in the plan. If not, consider at least two alternative schemas and cost each for both **latency** and **throughput** (SKILL.md cost model): latency = seeks/iterations on one request's critical path, parallel work counted once; throughput = seeks/iterations summed across all tasks, weighted by each operation's call rate. Pick the design that maximizes throughput within the latency target — not simply the lowest single-request cost. A design can give every request low latency and still exhaust the cluster under load.

Do NOT cost contiguous keys in a subindexed sorted structure as N point seeks — they're a range scan (1 seek + N iter-reads × 5µs), ~100× cheaper than N × 0.5ms. Think through whether a query can be satisifed completely or partially with range scans.

**Choose a partitioning scheme for every write and justify it for both latency and throughput** (see `references/pstate-schema.md` "Partitioning control"):
- **`|hash`** if the keyspace is large (many keys per task, so hash variance is negligible) AND no single key takes a disproportionate share of events or storage.
- **`|all`** if the data is small to hold on every task AND written rarely — every task pays every write.
- otherwise a custom **`|direct`** scheme that keeps computation and storage balanced across tasks.

**State primitive selection.** PStates are not the only state primitive. For state that does not need durable disk storage (e.g. derived caches that can be rebuilt from durable sources, expensive pre-merged views whose write volume would be prohibitive in a PState), use a TaskGlobal — see `references/task-globals.md`. For each piece of state in the design, decide explicitly:
- PState: durable, indexed, partitioned. Use when the data is the source of truth or is a derived view whose write volume per source event is bounded by inputs the application controls.
- TaskGlobal: in-memory per-task, non-durable, must be rebuildable from durable state if state is lost due to worker process restart of module update.
- External system: database, queue, etc.

Schema rules:
- Use `{K V}` maps for entity lookups by key
- Use `fixed-keys-schema` for record-like structures where all instances have the same fields. For polymorphic data (different types with different fields stored in the same position), use `definterface` + `defrecord` instead — see "Storing Polymorphic Data" in `references/pstate-schema.md`.
- **Never use `Object` as a schema type.** Every PState value must have a concrete, fully-typed schema. `Object` hides the data model and defeats Rama's schema validation. Spell out the full nested type.
- Use `{:subindex? true}` on inner maps/sets that grow per-entity. If ANY instance of that location can accumulate more than 100 items, the read/write amplification is too high. Subindexed maps are sorted — design for range scans via `sorted-map-range` and similar navigators, not point lookups on individual keys. A range scan over N contiguous entries is one seek; N individual point lookups is N seeks. A subindexed structure can be read in full as a single value with `ALL` with `foreign-select` or `(subselect ALL)` with `local-select>`.
- Use `:global? true` only for singletons

### Step 3 — Design depots

Read `references/depot-design.md`.

Group related events into depots. Same depot if events are order-dependent on the same entity. Separate depots for logically independent streams.

Partition by the key matching the primary PState's key — this colocates depot processing with PState writes, avoiding repartitioning.

### Step 4 — Design topologies

Identify the distinct processing concerns in the module. A single user-facing operation can involve multiple topologies — different parts of its processing may have different latency and consistency requirements.

For each processing concern, consider:
1. Does it need single-digit millisecond latency or must `foreign-append!` block until PState changes are visible? If yes → **stream**.
2. Does it need to return a value computed during processing back to the caller? If yes → **stream** with `ack-return>` (see `references/stream.md`).
3. Are all of its PState writes naturally idempotent (e.g., setting a value with `termval`)? If any write is non-idempotent (e.g., appending to a list, incrementing a counter), stream retries could produce duplicates → **microbatch** for exactly-once, or design the write flow to be idempotent (e.g. tracking state to enable deduplication).

A module can have **multiple topologies** of different types. All PState symbols are module-scoped — any topology can read any PState in the module (seeing the last committed value), but only the owning topology can write to it. Use internal depots (`:disallow` + `depot-partition-append!`) to pass data between topologies within the same module.

Topology types:
- **Microbatch** (default): exactly-once semantics from depot to PState — if a microbatch retries, all PState updates are applied exactly once. Cross-partition atomic writes within a microbatch. Higher latency than streaming (at least 300ms) and no coordination with depot appends. Microbatch also offers `<<batch` blocks and multi-step processing patterns that stream does not — read `references/microbatch.md` for the full capabilities.
- **Stream**: low-latency, at-least-once or at-most once. Stream topologies can retry, so non-idempotent writes could produce duplicates.

**Read BOTH `references/microbatch.md` AND `references/stream.md` before choosing topology types.** Do NOT skip either reference — the topology choice must be informed by the full capabilities of both, not just latency.

If the design requires any of these, read the corresponding reference:
- Unique ID generation either client-side or within topologies → read `references/unique-ids.md`
- Time-based/scheduled processing (expirations, delayed tasks, periodic cleanup) → read `references/scheduling.md`
- In-memory per-task state (e.g. cache) or integration with external non-Rama infrastructure → read `references/task-globals.md`

### Step 5 — Fill in the plan

Copy the plan template if it doesn't already exist:
```bash
cp <skill-root>/references/artifact-plan.md <impl-root>/PLAN.md
```

Read `references/artifact-plan.md` for the template structure, then fill in `PLAN.md` with the design from Steps 1-4.

### Step 6 — Self-validate before finishing

Read `references/artifact-plan-validation.md` and go through every check against your plan. Fix any failures directly in `PLAN.md` before finishing this phase. Do NOT produce the `PLAN_VALIDATION.md` artifact — that is Phase 2's job. This step catches issues while the full design is in your context, avoiding expensive retry cycles through Phase 2 → Phase 1.

Pay special attention to knock-on effects: when you change one part of the design (e.g., moving a concern to a different topology), re-trace every related operation and constraint to verify the change didn't break something elsewhere (e.g., recovery paths, self-inclusion, synchronization).

## Output

`<impl-root>/PLAN.md`, fully filled in and self-validated.

## Do NOT

- Do NOT write any topology, ETL, or query topology implementation code in this phase. Phase 1 is design only.
- Do NOT skip any section of the plan template.
- Do NOT commit to the first design that comes to mind for non-obvious decisions. State alternatives, estimate costs, pick.
- Do NOT produce `PLAN_VALIDATION.md` in this phase. Self-validate against the checklist but do not write the artifact.
