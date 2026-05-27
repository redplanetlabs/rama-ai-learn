# Stream Topology Reference

Stream topologies are event-driven, low-latency, at-least-once (per-event transactions). Each depot record spawns an event tree; `:ack` returns when the entire tree completes.

Use stream when: (1) single-digit millisecond update latency is required, or (2) append coordination is needed (stream participates in depot ack, enabling write-then-read-back patterns).

## Stream retries and exactly-once

A stream topology can retry a record even after all PState writes have completed and committed — if Rama's internal progress tracking fails afterward, the entire record is reprocessed. All writes for that record execute again. This means every write in a stream topology must be either naturally idempotent (e.g., `termval` overwrites) or explicitly deduplicated. Non-idempotent writes (e.g., `AFTER-ELEM` appends, counter increments) will produce duplicates on retry. The burden is on the topology writer to ensure correctness under retry.

## Formal model

### Source binding

```text
(Source-Stream)
Γ; o; M; stream ⊢ source>(depot, opts, :> *x) : τ     -- *x is a value var
```

Stream sources bind to **value vars** (`*x`), not fragment vars.

### Event tree

```text
(Event-Tree)
source>(depot, :> *record)  ⟹  tree(root-event)
partitioner(v)              ⟹  tree(root-event) += child-event(target-task)
tree-complete(root-event)   ⟹  record marked processed, :ack returns
```

Partition ordering: for any two tasks A, B, if A sends events e₁, e₂, e₃ to B, then B processes them in order e₁ → e₂ → e₃.

### When PState writes commit

In a stream topology, PState writes are committed (replicated and made durable) at **partitioner boundaries**. Each partitioner call causes all pending writes on the current task to commit before moving computation to the next task. Writes between two partitioners form one atomic group.

This means:
- Writes before a partitioner are committed before the partitioner executes.
- Writes after a partitioner are in a new transaction group.

**This matters for `depot-partition-append!`**: ALWAYS add a commit boundary before `depot-partition-append!` to an internal depot in a stream topology. Use `(|direct (ops/current-task-id))` to force a commit without changing tasks. Do NOT reason about whether the other topology "will read the data later anyway" or "won't need it immediately" — the processing schedule of the other topology is not guaranteed, and defensive commit boundaries are cheap (just a flush on the same task).

```clojure
;; Write data to PStates
(local-transform> [(keypath *id) (termval *data)] $$my-pstate)
;; ALWAYS force commit before internal depot append
(|direct (ops/current-task-id))
;; Other topology can now safely read $$my-pstate for this item
(depot-partition-append! *internal-depot *item :append-ack)
```

Without the `(|direct ...)`, the PState writes and the depot append are in the same atomic group. If the other topology reads from these PStates during its processing, it could see stale data or miss the writes entirely if there's a failure after the append but before the PStates commit.

### Ack semantics

```text
(Ack-Own-Stream)
foreign-append!(d : Depot α M V, val, :ack) → AckReturnMap
  where Owner(d) = Mod M
⊢ event-tree(d, val) complete ∧ PState(M): Visible
  AckReturnMap = { topo-name → ack-return>(v) value | topo ∈ topologies(M) that called ack-return> }
```

### Ack return

```text
(Ack-Return)
Γ; o; M; stream ⊢ ack-return>(val) : Unit ! {Ack}
```

Only valid in stream topologies. The value is placed in the `AckReturnMap` under the topology name. Multiple `ack-return>` calls are aggregated by `:ack-return-agg` (if specified on the source) or last-write-wins.

### Read-after-write

```text
(RAW-Stream)
foreign-append!(d : Depot α M V, val, :ack) returns    -- Owner(d) = Mod M
⊢ foreign-select-one(path, $$p) observes val's effects  -- $$p updated by stream topology
```

`:ack` guarantees read-after-write for stream topologies.

### Retry semantics

```text
RetryMode ::= :none         -- at-most-once
            | :individual    -- at-least-once; retry failed record(s) only
            | :all-after     -- at-least-once; retry failed + all subsequent on same partition

(Stream-Retry)
retry(record, mode) ⟹ restart from source> block start
                    ⟹ PState writes from failed streaming batch on the failing task discarded
                    ⟹ PState writes already committed on OTHER tasks (via prior partitioner hops) are NOT rolled back
```

A streaming batch is a group of events processed together on one task thread. Within a streaming batch, all PState writes are atomic — if the streaming batch fails, its writes are discarded. But an event that hops across tasks via partitioners creates work in different streaming batches on different tasks. Those streaming batches commit independently. If a streaming batch on task B already committed but the streaming batch on task A fails, task B's writes are durable. On retry, the entire event replays from the source, and task B receives duplicate writes. This is why non-idempotent writes (AFTER-ELEM, counter increments) in stream topologies that span multiple partitions are dangerous — a partial failure produces duplicates that cannot be rolled back.

Example — what gets committed on partial failure:

```clojure
;; On task A
(local-transform> [(keypath *k1) (termval *v1)] $$p1)               ;; write W1 (termval)
(local-transform> [(keypath *k1) AFTER-ELEM (termval *v2)] $$p2)    ;; write W2 (AFTER-ELEM)
(|hash *k2)
;; ── partitioner boundary: W1 and W2 commit on task 0 ──
;; On task B
(local-transform> [(keypath *k2) (termval *v3)] $$p3)               ;; write W3 (termval)
(local-transform> [(keypath *k2) AFTER-ELEM (termval *v4)] $$p4)    ;; write W4 (AFTER-ELEM)
;; ← FAILURE HERE — W3 and W4 are discarded (never committed)
(|hash *k3)
;; On task C — never reached
(local-transform> [(keypath *k3) (termval *v5)] $$p5)               ;; write W5 — never executed
```

On retry, the entire event replays from source:
- W1 replays on task A — `termval` overwrites with same value. Idempotent, no harm.
- W2 replays on task A — `AFTER-ELEM` appends again. **Duplicate entry in $$p2.** Bug.
- W3 replays on task B — `termval` writes for the first time (previous attempt was discarded). Correct.
- W4 replays on task B — `AFTER-ELEM` appends for the first time. Correct.
- W5 runs on task C for the first time. Correct.

Result: `$$p2` has a duplicate entry from W2. Only writes that committed before the failure AND are non-idempotent produce duplicates on retry.

## Syntax

```ebnf
topology-def    = '(let' '[' topo-var '(stream-topology' 'topologies' string ')' ']' body ')' ;

sources-block   = '(<<sources' topo-var { source-form } ')' ;
stream-source   = '(source>' depot-var [ source-opts ] ':>' binding ')' ;
source-opts     = '{' { ':start-from' start-from | ':retry-mode' retry-mode
                       | ':source-id' string | ':ack-return-agg' agg-expr } '}' ;
start-from      = ':end' | ':beginning'
                | '(offset-ago' number unit ')'
                | '(offset-after-timestamp-millis' number ')' ;
unit            = ':records' | ':days' | ':months' ;
retry-mode      = ':individual' | ':all-after' | ':none' ;

ack-return      = '(ack-return>' expr ')' ;
```

## Quick reference

| Construct | Syntax |
|---|---|
| Stream topology | `(let [s (stream-topology topologies "s")] ...)` |
| Subscribe to depots | `(<<sources topo ...)` |
| Stream source | `(source> *depot :> *data)` |
| Source with options | `(source> *depot {:start-from :beginning :retry-mode :all-after} :> *data)` |
| Source with ID (multi-source) | `(source> *depot {:source-id "myId"} :> *data)` |
| Ack return aggregation | `(source> *depot {:ack-return-agg (combiner +)} :> *data)` |
| Ack return | `(ack-return> *v)` |
| Subsource (type dispatch) | `(<<subsource *data TypeA (...) TypeB (...))` |

### Source options

- **`:start-from`**: `:end` (default), `:beginning`, `(offset-ago N :records)`, `(offset-ago N :days)`, `(offset-ago N :months)`, `(offset-after-timestamp-millis ts)`.
- **`:retry-mode`** (stream only): `:individual` (default), `:all-after`, `:none`.
- **`:source-id`**: unique string for multi-source — same depot consumed multiple times; each tracks progress independently. Restriction: only `:individual` or `:all-after` retry modes.
- **`:ack-return-agg`**: combiner for aggregating multiple `ack-return>` calls per event.

## Handler patterns

- Transaction granularity: **event** (between partition boundaries).
- Event tree completion is the basis for `:ack` semantics.
- Streaming batch-level visibility: task threads batch multiple events; all PState writes from a streaming batch become visible atomically after replication. Individual events can read their own writes immediately, but external clients see changes only after the batch commits.
- Foreign-client read-after-write: `:ack` guarantees read-after-write. Troubleshooting: write followed by immediate read returns stale/nil — check ack level. `:append-ack` does not guarantee PState visibility.
- Retry modes per `source>` (default `:individual`); retries restart from the start of that source block. PState writes from the failed streaming batch on the failing task are discarded, but writes already committed on other tasks via partitioner hops are NOT rolled back.
- Checkpoint: progress tracked in internal PState `$$__streaming-state-<topologyId>`.

### Multi-entity update patterns

Event transactions do not cross partition boundaries (Suspend = Txn boundary in stream). To update multiple keys atomically, route to one partition and nest related data under a single key.

For *non-atomic* fan-out updates, use sequential `<<if` blocks with partitioners inside. Each creates sub-events in the event tree. `:ack` waits for the entire tree to complete:

```clojure
(|hash *id)
(local-transform> [(keypath *id) (termval *data)] $$records)
(<<if (some? *ref1)
  (|hash *ref1)
  (local-select> [(keypath *ref1 :links) (nil->val #{})] $$records :> *cur)
  (conj *cur *id :> *new)
  (local-transform> [(keypath *ref1 :links) (termval *new)] $$records))
```

Under at-least-once retry modes, include a command-id/transfer-id set in PState and short-circuit if already seen before applying mutations.

### Stream reducer pattern

Single reducer PState key for multi-entity validation: `(|hash state-key) -> (local-select>) -> (or>) -> (pure reducer fn) -> (local-transform>)`. Keeps event handling deterministic; refactor to sharded keys when scaling demands it.

### Source and ingress patterns

- Tick depot source: consume with `(source> *tick)` (no `:>` binding). Each tick event originates on task 0.
- Scheduler ownership: if app events and tick events both need to mutate the same PState, keep both `source>` roots in the same stream topology under one `<<sources` block. Splitting across topologies triggers PState ownership violations.
- Multi-depot event-type pattern: one depot per event type with `hash-by` matching the PState key. Each `source>` in `<<sources` creates an independent branch — no type dispatch needed:

```clojure
(declare-depot setup *posts-depot (hash-by :to))
(declare-depot setup *mutes-depot (hash-by :user))
(let [s (stream-topology topologies "events")]
  (declare-pstate s $$feeds {String (vector-schema Object {:subindex? true})})
  (declare-pstate s $$mutes {String Object})
  (<<sources s
    (source> *posts-depot :> {*from :from *to :to *content :content})
    (local-transform> [(keypath *to) AFTER-ELEM (termval {:from *from :content *content})] $$feeds)
    (source> *mutes-depot :> {*op :op *user :user *target :target})
    (<<if (= *op :mute) ... (else>) ...)))
```


## Worked example

```clojure
(<<sources s
  (source> *events :> [*user-id *delta])
  (|hash *user-id)
  (local-select> (keypath *user-id) $$balances :> *b0)
  (or> *b0 0 :> *b)
  (+ *b *delta :> *b1)
  (local-transform> [(keypath *user-id) (termval *b1)] $$balances)
  (ack-return> *b1))
```

## Returning values to the client with ack-return>

Stream topologies can send a value back to the `foreign-append!` caller via `ack-return>`. The caller receives a map keyed by topology name:

```clojure
;; In the topology:
(<<sources s
  (source> *depot :> *record)
  ;; ... process record, compute a result ...
  (compute-something *record :> *result)
  (ack-return> *result))

;; On the client:
(let [ack-map (foreign-append! depot record :ack)]
  (get ack-map "topology-name"))  ;; => the value from ack-return>
```

`ack-return>` is only valid in stream topologies. It can be called at any point during processing, on any task — it does not have to be the last thing the topology does. However, the ack response is not sent back to the caller until the entire event tree for that record completes. The value is placed in the ack response map under the topology name (the string passed to `stream-topology`).

If multiple `ack-return>` calls occur for the same event, they are aggregated by `:ack-return-agg` (if specified on the source) or last-write-wins.

## Config

| Config | Scope | Purpose |
|---|---|---|
| `topology.stream.max.executing.per.task` | dynamic, per-topology | Pending record limit per task |
| `topology.stream.max.events.per.batch` | dynamic, per-topology | Max events batched per task execution |
| `topology.stream.timeout.seconds` | dynamic, per-topology | Event tree completion deadline |
| `topology.stream.checkpoint.progress.threshold` | dynamic, per-topology | Records between checkpoints |
| `topology.stream.periodic.checkpoint.seconds` | fixed | Time-based checkpoint fallback |
| `depot.ack.failure.on.any.streaming.failure` | fixed | ACK appends fail on streaming failure |
| `depot.cache.cardinality` | fixed | Depot cache size for retry |

## Troubleshooting

- `<<sources` called with raw `topologies` instead of a named topology object — create with `(let [s (stream-topology topologies "name")] ...)` first.
- `ops/expand` in `<<sources` blocks causes `Syntax error compiling` — use `(first *tuple :> *a)` / `(second *tuple :> *b)` instead.
- `get-in` with vector path inside `<<sources` causes `Syntax error compiling` — use destructuring: `(identity *m :> {*inner :k1})` then `(identity *inner :> {*v :k2})`.
- Stream topologies do not support `<<batch`.
