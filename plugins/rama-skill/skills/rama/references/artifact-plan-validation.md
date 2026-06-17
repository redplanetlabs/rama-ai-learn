# Plan Validation

<!-- Phase 1 Step 6. Fill in after PLAN.md is complete. After all items pass, write the file skeleton (Step 7), then implement topologies (Phase 2), then foreign client (Phase 3), then fill in IMPLEMENTATION_VALIDATION.md (Phase 4). -->

Read `PLAN.md` first, then fill in each item below. Extract the relevant data from the plan and state pass or fail with evidence. If any item fails, fix `PLAN.md` and re-validate.

## Query topology: <name>
- Input examples present: <yes/no>
- Example 1: N=<total reads>, M=<meaningful reads>. N == M? <yes/no>
- Example 2: N=<total reads>, M=<meaningful reads>. N == M? <yes/no>
- If any N > M: FAIL — query issues wasted reads. Fix the plan.
- M values across examples: <list>. All same? <yes/no>
- If M differs: must be marked variable with dynamic approach. Is it? <yes/no>

## PState schemas
- Any Object type? <yes/no — if yes, FAIL>
- Uniform record-like values (all instances have same fields) use fixed-keys-schema? <yes/no – if no, FAIL>
- If different instances at the same PState position have different fields, the schema MUST use definterface + defrecord. No exceptions. IPersistentMap, Object, fixed-keys-schema with optional/nil fields, and "justified deviation" are all FAIL. <yes/no – if no, FAIL>
- Inner collections that can exceed 100 elements subindexed? A collection needs subindexing if ANY instance can grow to have more than 100 elements (e.g., a popular entity). For each non-subindexed inner collection, name the specific code or protocol rule that enforces the size limit. If no enforcement mechanism exists, it is not bounded — subindex it. "Bounded by domain dynamics" or "typically small" without an enforcement mechanism does not count. <yes/no - if no, FAIL>

## Partitioning
For each write, state its partitioner and justify it (see `references/pstate-schema.md` "Partitioning control"). These indicators rule out some bad partitionings, not all:
- `|hash`: is the keyspace large (many keys per task → negligible hash variance) AND free of any key taking a disproportionate share of events or storage? <if the keyspace is sparse or any key can be hot, FAIL>
- `|all`: is the data small to hold on every task AND written rarely? <if large or high write throughput, FAIL>

General test — for each frequent read, give its disk cost in seeks + iterator reads, and the single-task baseline: the same data held whole on one task. <if the design's seek count jumps substantially above the baseline, FAIL — design a better partitioning strategy that reduces seeks (such as by finding way to replace them with iterator reads); `|direct` gives full control over placement>

## Topologies
- Microbatch unless justified? <yes/no>
- For each write operation that must be visible in single-digit milliseconds: is it handled by a stream topology? Microbatch has at least 300ms latency and cannot meet single-digit millisecond visibility requirements. <list each low-latency write and its topology — if any uses microbatch, FAIL>
- For each stream topology: list every processing concern it handles. For each concern, does it actually require stream semantics (millisecond-level update latency or ack coordination with the appender)? If any concern does not require stream, FAIL — that concern belongs in a separate microbatch topology. Multiple topologies can consume the same depot independently or topologies can communicate with an internal depot.

## Production readiness
- Does the plan work correctly with multiple concurrent clients? <yes/no — if no, FAIL>
- Does the plan work correctly after a client process restarts? <yes/no — if no, FAIL>
- Does the plan work correctly if a worker process restarts at any point during a topology execution? <yes/no — if no, FAIL>
- Does the plan work at large scale (millions of entities, unbounded growth over time)? Are all collections that aren't enforced by the application to always be less than 100 elements subindexed? <yes/no — if no, FAIL>
- For each stream topology: list every non-idempotent write (appending to lists, incrementing counters, etc.). For each one, state its resolution — one of:
    - "Moved to microbatch topology: <topology-name>"
    - "Made idempotent by: <specific mechanism>"
    - "Deduplicated by: <specific mechanism>"
  If a non-idempotent write has no resolution, FAIL.
- For each stream topology that writes to multiple partitions: can a partial failure + retry leave any writes permanently unexecuted? <trace through failure at each partition hop — if yes, FAIL>

## Internal depot usage
- For each internal depot (`:disallow`): why can't the consuming topology just consume the original client-appended depot directly? An internal depot is only justified when:
  (a) the consuming topology must wait for the sending topology to complete first (ordering dependency), or
  (b) the internal depot carries data that is not available in any client-appended depot (e.g., computed/derived values)
- If neither condition applies, FAIL — remove the internal depot and have the second topology consume the same client-appended depot independently.

## Cross-topology correctness
- If data flows between topologies via internal depots: can the sending topology produce duplicate records (e.g., from stream retry)? If so, how does the receiving topology handle duplicates? <list each internal depot flow and dedup mechanism>

## Stream topology correctness
- For each `depot-partition-append!` in a stream topology: is there a `(|direct (ops/current-task-id))` immediately before it to force a commit boundary? If no, FAIL. Do NOT reason about whether the receiving topology "will read the data later" — ALWAYS add the commit boundary. See `references/stream.md` "When PState writes commit."

## In-memory state efficiency
- For each TaskGlobal or in-memory cache, does the plan use compact, flat data structures (primitive arrays) instead of object-heavy structures (TreeMap, HashMap, vectors of maps)?
- Object-heavy structures create per-entry heap overhead (object headers, pointers, boxed primitives) and produce large object graphs that increase GC pause times. On a latency-sensitive task thread, GC pauses cause latency spikes that propagate to all operations on that task — including unrelated reads and writes. The effect is non-local: one task's GC pause delays every client whose request routes to that task.
- For each TaskGlobal: FAIL if the data can be partially or fully stored in a compact, flat data structure.
- Does any TaskGlobal store data that could be fetched from a PState at query time without violating latency requirements? If so, FAIL.

## Minimality — adversarial simplification

Attack the plan as an over-engineering reviewer: actively search for a SIMPLER plan that keeps every required property — fewer depots, fewer topologies, fewer PStates, fewer mechanisms, less logic.

First, sketch in 2–4 sentences the simplest design you can construct that plausibly satisfies the spec. Diff the plan against the sketch: every mechanism in the plan that is absent from the sketch must justify itself below.

For EVERY mechanism in the plan — each depot, topology, query topology, PState, TaskGlobal, and each nontrivial protocol (handoff, flag, counter, cursor, gate) — fill in a block:

### <mechanism name>
- **Delete it**: what specifically breaks? Name the required property lost, with a verbatim spec/IMPLICIT_SPEC citation. If nothing breaks, or the only thing lost is a property the spec does not require, FAIL — remove it from the plan.
- **Merge or bypass it**: can it be folded into an existing mechanism, or replaced by doing the work directly where it's triggered? If yes with no required property lost, FAIL — simplify the plan.

Rules:
- A property the spec does not demand (e.g. exactness beyond stated guarantees) does NOT justify a mechanism.
- "It is legal/documented" is not a justification — legality is necessary, not sufficient.
- Indirection is itself a mechanism: if component A could do X directly but instead signals component B to do X, the signaling channel must justify itself.

## Throughput — adversarial

Attack the plan as a throughput reviewer: actively search for a design that sustains higher throughput — lower aggregate resource usage (seeks/iterations summed across all tasks, weighted by each operation's call rate) — while still meeting every latency requirement.

For each frequent operation, sketch the lowest-aggregate-cost design you can construct that still meets its latency target, then diff it against the plan. If the sketch does less total work per operation while staying within latency, FAIL — adopt the cheaper design.

Look for ways to reduce RocksDB seeks or replace seeks with cheaper iterator reads.

Rules:
- Parallelizing across more tasks lowers latency, not aggregate cost — spreading the same work wider is not a throughput improvement.
- Meeting the latency target is necessary, not sufficient: among designs that meet it, the one with the lowest aggregate cost wins.

## Spec coverage — trace every operation and constraint

Enumerate every operation in the protocol/spec AND every constraint, prohibition, invariant, and edge case from the spec and `IMPLICIT_SPEC.md`. For each one, fill in a block below. Do NOT collapse multiple items into one block. Do NOT replace any block with a single-bullet "covered" claim — that bypasses the check.

For each operation and each constraint:

### <operation name or constraint label>
- **Source** (verbatim quote from spec / IMPLICIT_SPEC.md): "..."
- **Trace through the plan**: walk the plan's mechanism end-to-end on a concrete scenario. Use real numbers (entity counts, sizes, timings) — not "a user does X." Show what the plan produces at each step.
- **Fault-tolerance check** — for each of these, state what happens and whether the constraint still holds:
  - In-memory state on the affected task is cleared (worker restart): what is the state of this operation/constraint immediately after, and how does the plan recover it?
  - A topology is retried: does re-executing the event produce the same result, or does it double-count / duplicate / corrupt?
  - A multi-partition write fails partway: can the system end up in an inconsistent state where some partitions reflect the write and others don't?
- **Race-condition check** — for each of these, state whether the plan handles it:
  - Two concurrent clients writing the same entity at the same time
  - Events arriving out of order at a partition due to partitioner hops
- **Flaws found**: list every way the trace, fault-tolerance, or race analysis shows the plan failing to satisfy the source. If none, write "none found, with reasoning: <reasoning>".
- **Verdict**: PASS | FAIL
- **If FAIL**: the specific plan change required.

### Anti-patterns that must FAIL this section

- Skipping the fault-tolerance check because "the spec doesn't mention it." Worker restart and retry are always in scope; the plan must hold under them.
- Skipping the race-condition check because "tests are single-threaded." Production has concurrent clients; the plan must hold under them.
