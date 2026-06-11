# Microbatch Topology Reference

Microbatch topologies provide exactly-once PState updates, cross-partition atomicity, and higher throughput via batched I/O. Processing is decoupled from depot append acknowledgement.

**Choosing stream vs microbatch:** Default to microbatch. Use stream only when: (1) single-digit millisecond update latency is required, or (2) append coordination is needed (stream participates in depot ack, enabling write-then-read-back patterns).

## Core concepts

### Source binding and emitting records

Microbatch sources bind to **fragment vars** (`%mb`), not value vars. The fragment var represents the current batch of records across all tasks. Emitting from it with `(%mb :> *record)` iterates over all records in this microbatch on every task where records landed.

Each `source>` in a `<<sources` block begins an independent dataflow section with its own variable scope — vars bound under one source are not visible under another.

```clojure
(let [mb (microbatch-topology topologies "core")]
  (<<sources mb
    (source> *my-depot :> %mb)
    (%mb :> {:keys [*user-id *value]})
    ;; dataflow continues here, processing each record across all tasks
    (local-transform> [(keypath *user-id) (termval *value)] $$my-pstate)))
```

You do NOT have to emit from the fragment var immediately or only once. If you need to run code that is disconnected from the fragment var, or you need to use the fragment var in multiple independent computations, use `<<batch` blocks.

### Sequential processing of microbatches

Microbatches process sequentially, and the next one does not start until all data in the current microbatch finishes processing and committing all changes to PStates. So a record that is appended to a depot immediately after a microbatch starts will not have its changes visible until two microbatches have run and finished.

### Behavior when subscribed depots are empty

When a microbatch topology's subscribed depots have no new data, the next microbatch is **delayed** — the topology enters lightweight polling mode waiting for new data, sleeping up to **one minute by default** between empty checks (`topology.microbatch.empty.sleep.time.millis`). Any pending background work scheduled inside the topology stalls for that delay. If background work must run on a faster cadence regardless of incoming data, subscribe the topology to a tick depot (see "Tick depots for timely background work" below).

### `<<batch` blocks

`<<batch` is the microbatch equivalent of a barrier — it does NOT continue past the `<<batch` until the entire batch block has completed across ALL tasks. This makes it the right tool for multi-step processing within a single microbatch where later steps depend on earlier steps being globally complete.

```clojure
(<<sources mb
  (source> *my-depot :> %mb)

  ;; First batch block: process all records and materialize results
  (<<batch
    (%mb :> {:keys [*user-id *value]})
    (local-transform> [(keypath *user-id) (termval *value)] $$my-pstate))

  ;; Second batch block: runs AFTER the first has completed on all tasks.
  ;; Can read PState values written by the first block, or use materialized
  ;; temporary PStates.
  (<<batch
    (%mb :> {:keys [*user-id]})
    (local-select> [(keypath *user-id)] $$my-pstate :> *current-value)
    ;; ... further processing with the updated values ...
    ))
```

Key properties of `<<batch`:
- Everything inside one `<<batch` is one batch block with pre-agg / agg / post-agg phases (see [batch reference](batch.md)).
- Code after a `<<batch` does not execute until the batch block completes globally.
- You can emit from the fragment var (`%mb`) inside a `<<batch` — this is the normal way to process microbatch records.
- Multiple `<<batch` blocks run sequentially. Use this when step 2 needs step 1's results to be fully committed across all tasks.
- `<<batch` is valid in microbatch topologies (not stream).
- `<<batch` can only be invoked from task 0 (where microbatch processing always starts)
- `local-transform>` is permitted in `<<batch` pre-agg (unlike query topology batch blocks which are read-only).

### When to use `<<batch` vs direct emit

**Direct emit** (no `<<batch`): Simple cases where you process each record independently and don't need cross-record or cross-task coordination within the microbatch.

```clojure
(<<sources mb
  (source> *depot :> %mb)
  (%mb :> *record)
  ;; simple per-record processing
  (local-transform> [...] $$pstate))
```

**`<<batch`**: When you need any of:
- Aggregation across records (e.g., counting, summing, grouping)
- Materialization of intermediate results for use in later steps
- Multiple passes over the same microbatch data
- Code that runs independently of the data in the microbatch

## Running code disconnected from microbatch data

A `<<batch` block does not have to emit from a fragment var. It can run code that is completely independent of the incoming microbatch data. For example:

```clojure
(<<sources mb
  (source> *tick-depot :> %tick)
  (%tick)

  (source> *events :> %mb)

  ;; Batch 1: background work, independent of incoming events.
  ;; Runs every microbatch cycle. Does not read from %mb.
  (<<batch
    (|all)
    (local-select> ALL $$pending-work {:allow-yield? true} :> [*key *state])
    ;; ... process pending work items, update $$pending-work ...
    )

  ;; Batch 2: process incoming events. Runs AFTER Batch 1 completes.
  (<<batch
    (%mb :> {:keys [*id *value]})
    ;; ... process each event ...
    ))
```

Because `<<batch` is a global barrier, Batch 2 sees the results of Batch 1 across all tasks.

**Tick depots for timely background work.** When subscribed depots have no new data, the next microbatch is delayed up to a minute by default (see "Behavior when subscribed depots are empty"). If the background work needs to run on a regular cadence regardless of whether new events arrive, subscribe the topology to a **tick depot** (`declare-tick-depot`) that fires periodically. Emit from the tick fragment var (`(%tick)`) without binding any output — this forces the microbatch to run on every tick even when `*events` has no new data. Without a tick depot, the background `<<batch` only runs when new events arrive on other subscribed depots.

## Transaction scope

Cross-partition atomicity by construction: `Write $$p1` then partitioner then `Write $$p2` remain one transaction within a microbatch attempt.

```text
txn-scope(microbatch) = microbatch-attempt   -- entire attempt across all tasks
```

## Guarantees

- **Exactly-once PState updates** across retries of the same microbatch ID. Non-deterministic ops (`|shuffle`, mirror reads) may vary per retry, but PState writes converge.
- **Depot appends** (`depot-partition-append!`) from microbatch code do NOT have exactly-once semantics on retry. A retry re-appends.
- **Phases per attempt:** prime (clear buffers, reset PStates to previous state) → process → commit (checkpoint + replicate). During the commit phase, each task commits independently and its writes become visible as soon as its own commit finishes — so external readers can observe two tasks on different microbatches at the same moment. The topology does not start the next microbatch until ALL tasks have committed successfully.

## Ack semantics

`:ack` on `foreign-append!` confirms **depot durability only**, not PState visibility. Microbatch processing is decoupled from the append ack — the PState update happens later when the microbatch processes and commits.

## Idempotent writes to non-PState stores

PState updates are automatically exactly-once across microbatch retries, but writes to TaskGlobals, external databases, or other non-PState stores are NOT rolled back on retry. Use `ops/current-microbatch-id` to make these writes idempotent.

`(ops/current-microbatch-id :> *mb-id)` returns an opaque identifier that is the same across retries of the same microbatch. It increments by one for every new microbatch. Store it alongside your non-PState write and check it before writing:

```clojure
(<<sources mb
  (source> *events :> %mb)
  (<<batch
    (%mb :> {:keys [*id *value]})
    ;; ... process records ...
    (ops/current-microbatch-id :> *mb-id)
    ;; Pass *mb-id to your update function.
    ;; The function checks if it has already seen this *mb-id
    ;; and skips the write if so.
    (my-idempotent-update! *my-non-pstate-store *id *value *mb-id)))
```

This is the standard pattern for achieving exactly-once semantics on writes that Rama cannot automatically roll back. Without this check, a microbatch retry re-executes the same writes, potentially duplicating side effects.

## Testing with InProcessCluster

`wait-for-microbatch-processed-count` is a **test-only** function from `com.rpl.rama.test`. It blocks until a microbatch topology has processed at least `n` records from its source depot. Use it in tests with `InProcessCluster` to synchronize writes and reads:

```clojure
(require '[com.rpl.rama.test :as rtest])

;; In test code with an IPC:
(foreign-append! depot data)
(rtest/wait-for-microbatch-processed-count ipc "my.ns/MyModule" "topo-name" 1)
;; Now safe to read PState — the microbatch has committed
(foreign-select-one [(keypath k)] pstate)
```

The count `n` is cumulative across the IPC's lifetime — if you've appended 5 records total, pass 5 to wait for all of them. Keep a running counter.

**Do NOT use `wait-for-microbatch-processed-count` in production code, module code, or client wrappers.** It exists only in `com.rpl.rama.test` for InProcessCluster testing. In production, microbatch processing happens asynchronously and clients read eventually-consistent PState values.

## Quick reference

| Construct | Syntax |
|---|---|
| Microbatch topology | `(let [mb (microbatch-topology topologies "mb")] ...)` |
| Microbatch source | `(source> *depot :> %microbatch)` |
| Emit microbatch items | `(%microbatch :> *data)` |
| Batch block | `(<<batch (%microbatch :> *data) ... (aggs/+sum *v :> *total) ...)` |
| Current microbatch ID | `(ops/current-microbatch-id :> *mbid)` |
| Pause (test only) | `(rtest/pause-microbatch-topology! ipc "ns/Mod" "topo")` |
| Resume (test only) | `(rtest/resume-microbatch-topology! ipc "ns/Mod" "topo")` |
| Wait (test only) | `(rtest/wait-for-microbatch-processed-count ipc "ns/Mod" "topo" n)` |

## Config

| Config | Scope | Purpose |
|---|---|---|
| `depot.microbatch.max.records` | dynamic, per-depot | Max records per partition per microbatch |
| `topology.microbatch.phase.timeout.seconds` | dynamic, per-topology | Phase timeout before retry |
| `topology.microbatch.empty.sleep.time.millis` | dynamic, per-topology | Sleep between empty microbatches |
| `topology.microbatch.pstate.flush.path.count` | dynamic, per-topology | PState write flush frequency |

## Troubleshooting

- `declare-microbatch-topology` does not exist — use `microbatch-topology`. No `<<microbatch` macro — use `(microbatch-topology topologies "name")` then `(<<sources mb ...)`.
- Stale reads after microbatch write — `:ack` does not guarantee PState visibility. In tests, use `wait-for-microbatch-processed-count`. In production, reads are eventually consistent.
- Source binding must use fragment vars (`%mb`), not value vars (`*x`).
