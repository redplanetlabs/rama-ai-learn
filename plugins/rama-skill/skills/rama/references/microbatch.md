# Microbatch Topology Reference

Microbatch topologies provide exactly-once PState updates, cross-partition atomicity, and higher throughput via batched I/O. Processing is decoupled from depot append acknowledgement.

**Choosing stream vs microbatch:** Default to microbatch. Use stream only when: (1) single-digit millisecond update latency is required, or (2) append coordination is needed (stream participates in depot ack, enabling write-then-read-back patterns).

## Formal model

### Source binding

```text
(Source-Microbatch)
Γ; o; M; microbatch ⊢ source>(depot, opts, :> %mb) : Batch τ    -- %mb is a fragment var
                       (%mb :> *x) emits individual records
```

Microbatch sources bind to **fragment vars** (`%mb`), not value vars.

### Transaction scope

```text
txn-scope(microbatch) = microbatch-attempt   -- entire attempt across all tasks

(Txn-Preserve)
Γ; o; M; microbatch ⊢ e : τ ! ε    Partitioner ∈ ε
───────────────────────────────────────────────────────
e does NOT fragment the Txn: all Write effects remain in one
atomic scope (single Txn microbatch)
```

Cross-partition atomicity by construction: `Write $$p₁` then partitioner then `Write $$p₂` remain one transaction.

### Guarantees

```text
(Microbatch-ExactlyOnce)
retry(microbatch-id) ⟹ PState updates exactly-once
                     -- same depot records replayed deterministically
                     -- non-deterministic ops (|shuffle, mirror reads) may vary

(Microbatch-Append-NoGuarantee)
depot-partition-append! in microbatch ⟹ NOT exactly-once on retry

(Microbatch-Phases)
attempt = prime → process → commit
prime   : clear buffers, reset PStates to previous microbatch state
commit  : checkpoint + replicate; PState changes visible atomically
```

### Ack semantics

```text
(Ack-Own-Microbatch)
foreign-append!(d : Depot α M V, val, :ack) returns
  where Owner(d) = Mod M
⊢ Depot(M): Committed
⊬ PState(M): Visible                       -- microbatch decoupled from append ack
```

### Read-after-write

```text
(RAW-Microbatch)
foreign-append!(depot, val, :ack) returns
⊬ foreign-select-one(path, $$p) observes val's effects   -- NOT guaranteed

(RAW-Microbatch-Barrier)
foreign-append!(depot, val, ack) returns
wait-for-microbatch-processed-count(ipc, mod, topo, n) returns  -- n ≥ append count
⊢ foreign-select-one(path, $$p) observes val's effects
```

### `<<batch` validity

```text
m ∈ {microbatch, query}
────────────────────────
Γ; o; M; m ⊢ <<batch(pre-agg, agg, post-agg) : τ ! ε
```

`<<batch` is valid in microbatch topologies. See [batch reference](batch.md) for batch block semantics.

### Execution context

```text
                    TopoBody  QueryBody  FnBody  OpBody  BatchBlock  ClientCode
<<batch                ✗(1)      impl     ✗       ✗        —           ✗
local-transform>       ✓         ✗(2)     ✗       ✗        ✓(mb)       ✗
```

(1) `<<batch` valid in microbatch `TopoBody`; invalid in stream `TopoBody`.

`local-transform>` is permitted in microbatch batch block post-agg (but not in query topology post-agg, which is read-only).

## Syntax

```ebnf
topology-def      = '(let' '[' topo-var '(microbatch-topology' 'topologies' string ')' ']' body ')' ;

microbatch-source = '(source>' depot-var [ source-opts ] ':>' frag-var ')' ;
microbatch-emit   = '(' frag-var ':>' binding ')' ;

pause-mb          = '(pause-microbatch-topology!' ipc module-name string ')' ;
resume-mb         = '(resume-microbatch-topology!' ipc module-name string ')' ;
wait-mb           = '(wait-for-microbatch-processed-count' ipc module-name string int ')' ;

current-mb-id     = '(ops/current-microbatch-id' ':>' binding ')' ;
```

## Quick reference

| Construct | Syntax |
|---|---|
| Microbatch topology | `(let [mb (microbatch-topology topologies "mb")] ...)` |
| Microbatch source | `(source> *depot :> %microbatch)` |
| Emit microbatch items | `(%microbatch :> *data)` |
| Current microbatch ID | `(ops/current-microbatch-id :> *mbid)` |
| Pause microbatch | `(pause-microbatch-topology! ipc "ns/Mod" "topo")` |
| Resume microbatch | `(resume-microbatch-topology! ipc "ns/Mod" "topo")` |
| Wait for microbatch | `(wait-for-microbatch-processed-count ipc "ns/Mod" "topo" n)` |

## Handler patterns

- Per attempt phases: prime → process → commit. All PState changes visible atomically after commit.
- Exactly-once PState updates across retries of same microbatch ID. Non-deterministic ops (`|shuffle`, mirror reads) may vary per retry.
- Depot appends from microbatch code do NOT have exactly-once semantics on retry.
- `:ack` confirms depot durability only, not PState visibility. Use `wait-for-microbatch-processed-count` for read-after-write (RAW-Microbatch-Barrier).
- Checkpoint: microbatch ID/version in internal PState `$$__microbatcher-state-<topologyId>`. Last two versions retained for recovery.

## Blocking client recipe

Append first (`:append-ack`), then wait for processed count `N+1`. Keep the count target monotonic per client/session.

```clojure
(r/foreign-append! depot data :append-ack)
(r/wait-for-microbatch-processed-count ipc mod-name "topo" (inc n))
;; now safe to read
(r/foreign-select-one [(keypath k)] pstate)
```

## Config

| Config | Scope | Purpose |
|---|---|---|
| `depot.microbatch.max.records` | dynamic, per-depot | Max records per partition per microbatch |
| `topology.microbatch.phase.timeout.seconds` | dynamic, per-topology | Phase timeout before retry |
| `topology.microbatch.empty.sleep.time.millis` | dynamic, per-topology | Sleep between empty microbatches |
| `topology.microbatch.pstate.flush.path.count` | dynamic, per-topology | PState write flush frequency |

## Troubleshooting

- `declare-microbatch-topology` does not exist — use `microbatch-topology`. No `<<microbatch` macro — use `(microbatch-topology topologies "name")` then `(<<sources mb ...)`.
- Stale reads after microbatch write — `:ack` does not guarantee PState visibility. Use `wait-for-microbatch-processed-count`.
- Source binding must use fragment vars (`%mb`), not value vars (`*x`).
