# Rama Dataflow: Augmented Typed Lambda Model

Formal model of Rama dataflow as a typed lambda calculus with:

- **E**ffects (read/write/route/append/ack/invoke/txn)
- **O**wnership (topology-level PState writes, module-level depots/queries, mirror immutability)
- **V**isibility (three-phase progression: Pending → Committed → Visible; privacy gates)
- **G**raph control (named output streams, anchors/hooks, unification)
- **B**atch phases (`<<batch`: pre-agg / agg / post-agg)

---

## 1) Core entities

```text
Module = ⟨Depots, PStates, TaskGlobals, StreamTopologies, MicrobatchTopologies, QueryTopologies,
          MirrorDepots, MirrorPStates, MirrorQueries, ExternalDepots⟩
```

- **Depot**: append-only partitioned log. All data enters the system through depots.
- **PState**: partitioned, durable, replicated indexed state. Stores the projection of depot data computed by Topologies).
- **TaskGlobal**: per-task mutable object with managed lifecycle. Holds non-durable per-task resources (caches, clients, models, external APIS). See [task global reference](references/task-globals.md)
- **ExternalDepot**: adapter bridging external partitioned logs (e.g. Kafka) into the depot abstraction. See [external depot reference](references/external-depots.md)
- **Mirror**: cross-module reference. Enables inter-module composition without data copying — all operations route to the source module. See [mirror reference](references/mirrors.md)
  - `MirrorDepot` (subscribe to / append into a foreign depot)
  - `MirrorPState` (read a foreign PState; no writes)
  - `MirrorQuery` (invoke a foreign query topology)
- **Topologies**: computation units that consume depots and materialize PStates.
  - `stream` — event-driven, low-latency, at-least-once (per-event transactions). See [stream reference](references/stream.md)
  - `microbatch` — batch-oriented, exactly-once PState updates (cross-partition atomic). See [microbatch reference](references/microbatch.md)
  - `query` — on-demand distributed read-only function (implicitly batch-mode). See [query topology reference](references/query-topologies.md)

**Choosing stream vs microbatch:** Default to microbatch. It provides exactly-once PState updates (eliminating a class of correctness bugs), higher throughput (batcheding amortizes coordination overhead), and every microbatch topology is a cross-partition transaction. Use stream only when: (1) single-digit millisecond update latency is required, or (2) append coordination is needed (stream participates in depot ack, enabling write-then-read-back patterns like registration or friend requests).

---

## 2) Types

```text
τ ::= Unit | Bool | Long | String | Keyword
    | Option τ | Vec τ | Set τ | Map κ τ
    | Depot α M V        -- owner module M, visibility V
    | PState o M κ ν V   -- owner topology o, owner module M, visibility V
    | Query α β M V      -- owner module M, visibility V
    | Batch α
    | Path ρ σ
    | AckLevel           -- nil | :append-ack | :ack
    | AckReturnMap       -- Map<String, Object>: topology-name → ack-return> value
```

Ownership and visibility domains:

```text
M ∈ Module             -- module identity (namespace/Name)
o ∈ Topology           -- topology identity within a module

Owner ::= Topo o M     -- PState: owned by topology o in module M
        | Mod M        -- Depot, Query, TaskGlobal: owned by module M
        | Mirror M s   -- imported reference from source module s into M

V ∈ Visibility ::= Public        -- accessible by any module (default)
                 | Private       -- only owning module (:private? true)
                 | Disallow      -- depot: no external appends (:disallow)

Access ::= Write       -- PState mutation (topology-restricted)
         | ReadInt      -- colocated read (module-local)
         | ReadExt      -- cross-partition or foreign read
         | Append       -- depot append
         | Invoke       -- query invocation
```

Auxiliary typing domains:

```text
ℓ ∈ Label              -- output stream label (:>, :a>, :err>, ...)
a ∈ Anchor             -- anchor label (<a>, <root>, ...)

c ∈ Ctx ::= TopoBody    -- stream/microbatch topology body
           | QueryBody    -- query topology body (implicit batch)
           | FnBody       -- deframafn body
           | OpBody       -- deframaop body
           | BatchBlock   -- <<batch block
           | ClientCode   -- foreign client code
```

Operation signatures are multi-stream CPS:

```text
op : (τ1, ... , τn) => {ℓ1:σ1, ... , ℓm:σm} ! ε
```

Meaning: `op` may emit zero or more tuples of shape `σi` on each output stream `ℓi`.

---

## 3) Effects

Effect row:

```text
ε ::= ∅
    | ε ∪ { Append d
          , ReadInt s
          , ReadExt s
          , Write s
          , Route π                -- π → TaskId | Set TaskId
          , Route¹ π              -- π → TaskId  (exactly one partition)
          , Invoke q
          , Ack
          , Agg
          , Join
          , Emit ℓ
          , Tick
          , Replicate
          , Visible
          , Retry r
          , Txn k
          , Suspend
          }
```

Where:

- `ReadInt`: read internal view (owner topology context)
- `ReadExt`: read external replicated view (foreign/non-owner)
- `Txn k`, `k ∈ {stream-event, microbatch}`
- `Route π`, `π ∈ {hash, all, global, origin, shuffle, direct, path, hash$$, all$$, direct$$, global$$, path$$, custom$$, custom}` — unconstrained cardinality
- `Route¹ π ⊂ Route π` — single-partition: `route(π, v) : TaskId` (not `Set TaskId`). Subtype: `Route¹ π` satisfies `Route π` but not vice versa
- `Suspend`: async suspension — current continuation is captured, execution resumes elsewhere or later. Sources: partitioners (`Route`), mirror PState reads, `completable-future>`, `select>`, `yield-if-overtime`

### 3.1 Explicit foreign-client primitives

```text
foreign-append!      : Depot α × α × AckLevel -> AckReturnMap
                       ! {Append d, Ack}

foreign-select       : Path ρ (Seq ν) × PState o κ ν × Opts
                       -> Seq ν
                       ! {ReadExt s, Route¹ π}

foreign-select-one   : Path ρ ν × PState o κ ν × Opts
                       -> ν
                       ! {ReadExt s, Route¹ π}

foreign-invoke-query : Query α β × α -> β
                       ! {Invoke q, Route π}
```

### 3.2 Mirror semantics

A mirror imports a reference from a source module `s` into the current module `M`. No data is stored locally; all operations route to `s`.

```text
MirrorDepot  : Depot α s V   →  Depot α M (Mirror s)
MirrorPState : PState o s κ ν V  →  PState o s κ ν (Mirror M)
MirrorQuery  : Query α β s V →  Query α β M (Mirror s)
```

Access restrictions induced by `Mirror`:

```text
Mirror M ⊢ PState   : {ReadExt}              -- no Write, no ReadInt
Mirror M ⊢ Depot    : {Append, ReadExt}       -- append routes to source
Mirror M ⊢ Query    : {Invoke}                -- invocation routes to source
```

Partition routing (extends §6 `route`/`home`):

```text
N_M = task-count(M)              -- tasks in referencing module
N_s = partition-count(s, entity) -- partitions in source module for entity

route_mirror : Partitioner × Value × Mirror → TaskId_s
route_mirror(|hash$$ $$m, k) = home_s($$m, k)    -- routes to source partition space
                              = hash(k) mod N_s

all_mirror : Mirror → Set TaskId_s
all_mirror(|all$$ $$m) = {0..N_s - 1}

-- When N_M ≠ N_s, each task t ∈ M maps to
-- {p ∈ 0..N_s-1 | assigned(t, p)} partitions in s (zero or more)
```

---

## 4) Judgments

### 4.1 Expression/segment typing

```text
Γ; o; M; m ⊢ e : τ ! ε
```

- `Γ`: variable typing environment
- `o`: current topology owner
- `M`: current module identity
- `m`: mode (`stream`, `microbatch`, `query`, `batch-preagg`, `batch-agg`, `batch-postagg`)

### 4.2 Graph typing

```text
Γ; A ⊢ block ✓
```

- `A`: anchor environment `Anchor -> Env`

### 4.3 Declaration scope

```text
Γ_M ⊢ declare-depot(setup, *d, part)       ⟹ *d : Depot α M Public ∈ Γ_M
Γ_M ⊢ declare-depot(setup, *d, :disallow)  ⟹ *d : Depot α M Disallow ∈ Γ_M
Γ_M ⊢ declare-pstate(o, $$p, schema)       ⟹ $$p : PState o M κ ν Public ∈ Γ_M
Γ_M ⊢ declare-pstate(o, $$p, schema, {:private? true})
                                            ⟹ $$p : PState o M κ ν Private ∈ Γ_M
Γ_M ⊢ mirror-pstate(setup, $$p, s, name)   ⟹ $$p : PState _ s κ ν Public ∈ Γ_M
                                               access($$p) = {ReadExt}
Γ_M ⊢ mirror-depot(setup, *d, s, name)     ⟹ *d : Depot α s Public ∈ Γ_M
                                               access(*d) = {Append, ReadExt}
Γ_M ⊢ mirror-query(setup, *q, s, name)     ⟹ *q : Query α β s Public ∈ Γ_M
                                               access(*q) = {Invoke}
```

All declarations are **module-scoped** — symbols enter `Γ_M` and are accessible throughout the entire `defmodule` body, regardless of lexical nesting within topology `let` blocks.


## 5) Static rules (selected)

### 5.1 Named output streams

Each output declaration for stream `ℓi` must bind exactly tuple shape `σi`.

### 5.2 Anchors and hooks

- `anchor> <a>` stores current continuation environment into `A[a]`.
- `hook> <a>` moves attachment point to `A[a]`.

These are **graph-control constructs** (ASG wiring), not state effects.

### 5.3 Unification and shadowing

```text
(Shadow)
Γ ⊢ e :> *x : τ₂       *x : τ₁ ∈ Γ
────────────────────────────────────
Γ' = Γ[*x ↦ τ₂]                       -- τ₁ no longer accessible

(Unify)
∀i. *x : τᵢ ∈ Γᵢ       τ₁ = τ₂ = ... = τₙ
────────────────────────────────────────────
*x : τ ∈ ⋂i Γi

(Unify-Drop)
∃i. *x ∉ Γᵢ
────────────────────────────────────────────
*x ∉ ⋂i Γi
```

`Γout = ⋂i Γi` applies after `unify>`, `<<if`, `<<cond`, `<<switch`.

`<<shadowif` conditionally shadows a variable's value:

```text
(ShadowIf)
Γ ⊢ <<shadowif(*x, pred, new-val)
────────────────────────────
if pred(*x) → *x = new-val in continuation
else        → *x unchanged in continuation
```

Example: `(<<shadowif *v nil? 5)` — if `*v` is nil, it becomes 5; otherwise unchanged.

### 5.4 Ownership and access control

Note: the formal branch-unification rule is defined in §5.3. Practical coding guidance, operator usage, and troubleshooting patterns derived from it are collected in §Practical guidance and troubleshooting.

**PState write** — topology-restricted, partition-local:

```text
(Write-Own)
Γ ⊢ $$p : PState o M κ ν V
pre: current-task = home($$p, k)       -- partition alignment (see §6)
---------------------------------------------
Γ; o; M; m ⊢ local-transform>($$p, path[k], val) : Unit ! {Write $$p}
```

Only the owning topology `o` may write (rejected at compile/deploy time otherwise). The write lands on the **current task's partition** — if `current-task ≠ home($$p, k)`, the write succeeds but the data is unreachable by correctly-routed reads. Establish alignment with a partitioner before the local operation.

**PState internal read** — module-local, partition-local:

```text
(Read-Int)
Γ ⊢ $$p : PState o M' κ ν V       M' = M
pre: current-task = home($$p, k)       -- partition alignment (see §6)
---------------------------------------------
Γ; o'; M; m ⊢ local-select>($$p, path[k]) : ν ! {ReadInt $$p}
```

Any topology in the same module may read colocated (including query topologies reading PStates declared by stream/microbatch topologies). If `current-task ≠ home($$p, k)`, the read returns `nil` (empty partition), not an error.

**PState external read** — visibility-gated:

```text
(Read-Ext)
Γ ⊢ $$p : PState o M' κ ν V       V ≠ Private ∨ M' = M
---------------------------------------------
Γ; o'; M; m ⊢ select>($$p, path) : ν ! {ReadExt $$p, Route π}
```

`select>` auto-repartitions. Private PStates reject cross-module reads.

**Mirror read** — ReadExt only, no writes:

```text
(Mirror-Read)
Γ ⊢ $$p : PState _ s κ ν Public    -- mirror from source module s
---------------------------------------------
Γ; o; M; m ⊢ local-select>($$p, path) : ν ! {ReadExt $$p}   -- async boundary
Γ; o; M; m ⊢ local-transform>($$p, ...) : ⊥                  -- REJECTED
```

**Depot append** — module ownership + visibility:

```text
(Append-Own)
Γ ⊢ *d : Depot α M V        V ≠ Disallow ∨ caller ∈ M
---------------------------------------------
Γ; M ⊢ depot-partition-append!(*d, val, ack) : Unit ! {Append *d, Ack}

(Append-Foreign)
Γ ⊢ *d : Depot α M' V       V ≠ Disallow
---------------------------------------------
foreign-append!(*d, val, ack) : AckReturnMap ! {Append *d, Ack}
```

`:disallow` depots accept appends only from topology code within the owning module.

**Query invocation** — visibility-gated:

```text
(Invoke-Query)
Γ ⊢ q : Query α β M' V       V ≠ Private ∨ M' = M
---------------------------------------------
invoke-query(q, args) : β ! {Invoke q, Route π}
```

### 5.5 Additional effect rules

**Branch termination** — `filter>`:

```text
(Filter)
Γ; o; M; m ⊢ filter>(pred) : Unit ! ∅
```

Terminates the current dataflow branch when `pred` is false. No downstream code executes on that branch for that record.

**Ack return** — stream topology only:

```text
(Ack-Return)
Γ; o; M; stream ⊢ ack-return>(val) : Unit ! {Ack}
```

Emits `val` as the ack return value for the current depot record's event tree. Only valid in stream topologies. The value is placed in the `AckReturnMap` under the topology name (the string passed to `stream-topology`). The caller receives this map from `foreign-append!` with `:ack`. When multiple `ack-return>` calls occur for the same event, they are aggregated by `:ack-return-agg` (if specified on the source) or last-write-wins.

**Tick depot**:

```text
TickDepot : Depot Unit M V

(Tick-Declare)
Γ_M ⊢ declare-tick-depot(setup, *d, millis)
⟹ *d : TickDepot ∈ Γ_M        millis : Long, millis > 0

(Tick-Source-Stream)
*d : TickDepot       m = stream
────────────────────────────────────
source>(*d) : Unit ! {Tick, Route¹ global}
-- tick originates on task 0 at frequency millis (push-based)
-- no :> binding (tick carries no payload)

(Tick-Source-Microbatch)
*d : TickDepot       m = microbatch
────────────────────────────────────
source>(*d) : Unit ! {Tick}
-- pull-based: checked per attempt, emitted once if interval elapsed
-- no :> binding
```

### 5.6 Query topology semantics

```text
(Query-Topo)
<<query-topology(name, [args :> outputs], body)
  where m = query (implicit batch)
────────────────────────────────────────────────
Γ_body = Γ_args
body : τ ! ε
Route origin ∈ ε                    -- |origin required as final partitioner
|outputs| = |declared-outputs|      -- exactly one value per declared output

(Query-StartTask)
leading(body) = Route¹ π            -- first call is single-task built-in partitioner
                                    --   using only topology input vars
⟹ start-task = route(π, args)      -- client-side routing

leading(body) ≠ Route¹ π
⟹ start-task = random

(Query-ReadOnly)
Write s ∉ ε                         -- no user PState writes
                                    -- implicit temp PState $$<name>$$ available (per-invoke, starts nil)

(Query-SelfInvoke)
Γ_body ⊢ %self(args) : τ ! {Invoke, Suspend}   -- recursive, timeout-bounded
```

### 5.7 Dataflow operation position

```text
op ∈ OpPosition ::= NamedVar        -- top-level def, defn, deframafn, deframaop
                  | RamaBuiltin      -- ops/*, aggs/*, identity, etc.
                  | ClojureCoreFn    -- pure functions from clojure.core
                  | Partial          -- (partial f args...)

op ∉ OpPosition ::= FnLiteral       -- fn, #()
                  | Keyword          -- :k (keyword-as-function)
                  | LogVar           -- *var
                  | HostParam        -- non-var params in defgenerator/deframafn
                  | JavaInterop      -- .method, Class/static
                  | SpecialForm      -- let, do, def, if, etc.
```

### 5.8 Source binding type rule

The output binding of `source>` is mode-dependent:

```text
(Source-Stream)
Γ; o; M; stream ⊢ source>(depot, opts, :> *x) : τ     -- *x is a value var

(Source-Microbatch)
Γ; o; M; microbatch ⊢ source>(depot, opts, :> %mb) : Batch τ    -- %mb is a fragment var
                       (%mb :> *x) emits individual records
```

### 5.9 Batch block validity

```text
(Batch-Context)
m ∈ {microbatch, query}
────────────────────────
Γ; o; M; m ⊢ <<batch(pre-agg, agg, post-agg) : τ ! ε

m = stream
────────────────────────
Γ; o; M; m ⊢ <<batch(...) : ⊥        -- compile error

(Batch-PreAgg)
Γ_pre ⊢ branches B₁...Bₙ
∀i. Bᵢ produces Γᵢ
join(Γ₁...Γₙ) = Γ_joined            -- single tail via merge/join
────────────────────────────────────
Γ_pre ⊢ pre-agg : Γ_joined ! {Route π}

(Batch-PreAgg-Final-Partitioner)
agg ≠ ∅  ⟹  last(pre-agg) = Route π  -- required when aggregators present

(Batch-Agg)
Γ_joined ⊢ agg-forms : Γ_agg
────────────────────────────────────
Γ_agg = group-by-keys ∪ agg-outputs

(Batch-PostAgg)
Γ_agg ⊢ post-agg : τ
────────────────────────────────────
Γ_pre ∩ Γ_agg = ∅                    -- pre-agg vars not in scope
Route π ∉ effects(post-agg)          -- no partitioners allowed
```

### 5.10 Execution context validity

Construct validity by execution context `c ∈ Ctx`:

```text
                    TopoBody  QueryBody  FnBody  OpBody  BatchBlock  ClientCode
Suspend (§6.1)         ✓         ✓        ✗       ✓      ✓(pre-agg)    ✗
Partitioners           ✓         ✓        ✗       ✓      ✓(pre-agg)    ✗
<<batch                ✗(1)      impl     ✗       ✗        —           ✗
Clojure specials       ✗         ✗        ✗       ✗        ✗           ✓
ops/vget               ✓         ✓        ✗       ✗        ✓           ✗
local-transform>       ✓         ✗(2)     ✗       ✗        ✓(mb)       ✗
```

(1) `<<batch` valid in microbatch `TopoBody`; invalid in stream `TopoBody`.
(2) Query topologies are read-only; writes go to implicit temp PState only.

Context establishment:

```text
deframafn body  ⟹  c = FnBody      -- Suspend ∉ effects
deframaop body  ⟹  c = OpBody      -- Suspend ∈ effects (may repartition)
```

Partitioners are suspension points — valid iff `Suspend ∈ effects(c)`.

### 5.11 Loop emit semantics

```text
loop<- [*vars :> *out] body
⊢ *out is bound after loop  iff  body contains (:> expr) on some reachable execution path
```

### 5.12 `<<if` branch constraint

```text
(If-Else)
<<if(cond, then-body [, else> else-body])
⊢ |else>| ≤ 1 per <<if                    -- at most one else> marker
⊢ nested <<if scopes are independent      -- inner else> binds to inner <<if
```

---

## 6) Operational model

Partitioner semantics:

| Partitioner | Behavior |
|---|---|
| `hash` | one destination task by hash |
| `all` | one emit per task |
| `global` | task 0 |
| `origin` | query invoke origin task |
| `shuffle` | random round-robin, one emit |
| `direct` | explicit task ID |
| `path` | route via PState key-partitioner |
| `hash$$` | route to correct partition of a mirrored object |
| `all$$` | send to all partitions of a PState |
| `direct$$` | send to specific partition of a PState |
| `global$$` | send to partition 0 of a PState |
| `path$$` | route via PState key-partitioner (PState-scoped) |
| `custom$$` | custom using PState partitions |
| `custom` | user-defined partition function |

Formal routing function:

```text
route : Partitioner × Value → TaskId | Set TaskId
route(|hash v)    = hash(v) mod N
route(|global)    = 0
route(|origin)    = caller-task
route(|all)       = {0..N-1}
route(|shuffle)   = round-robin
route(|direct t)  = t

home : PState × Key → TaskId
home($$p, k)      = route(key-partitioner($$p), k)    -- default: |hash
                   -- for global PStates: home($$p, _) = 0

foreign-route : Path × PState → TaskId
foreign-route(path, pstate) = home(pstate, first-key(path))
```

Partition alignment invariant:

```text
(Align)
local-select>(path, $$p) ∨ local-transform>(path, $$p)
⊢ current-task = home($$p, k)        -- where k = first-key(path)

(Align-Violation-Write)
current-task ≠ home($$p, k)
⊢ local-transform> succeeds silently  -- data lands on wrong partition (unreachable)

(Align-Violation-Read)
current-task ≠ home($$p, k)
⊢ local-select> returns nil           -- correct partition is elsewhere
```

Event tree (stream topologies):

```text
(Event-Tree)
source>(depot, :> *record)  ⟹  tree(root-event)
partitioner(v)              ⟹  tree(root-event) += child-event(target-task)
tree-complete(root-event)   ⟹  record marked processed, :ack returns
```

Partition ordering: for any two tasks A, B, if A sends events e₁, e₂, e₃ to B, then B processes them in order e₁ → e₂ → e₃.

Variable transfer: `Γ_post-partitioner = {v ∈ Γ_pre | v referenced after partitioner}`.

### 6.1 Suspension model

Dataflow execution is continuation-based with explicit suspension points. At a suspension point:

```text
suspend : (Γ_live, k) → target
```

where `Γ_live` is the set of live variables (referenced downstream), `k` is the remaining continuation, and `target` is the destination (task, future, etc.). Execution resumes on `target` with `Γ_live` restored.

Suspension points:

```text
Source                   Target              Effect
─────────────────────────────────────────────────────────
Partitioner (|hash etc)  destination task    Route π, Suspend
Mirror PState read       source module task  ReadExt, Suspend
select> (auto-repart)    home($$p, k)        ReadExt, Route, Suspend
completable-future>      same task (later)   Suspend
yield-if-overtime        same task (later)   Suspend
```

**Context restriction on Suspend**:

```text
c ⊢ Suspend ok    iff    c ∈ {TopoBody, QueryBody, OpBody, BatchBlock}
c ⊢ Suspend ⊥     iff    c ∈ {FnBody, ClientCode}
```

```text
(Atomic)
Γ; c ⊢ <<atomic(body) : τ ! ε \ {Suspend}
-- partitioners inside body create child events but do not suspend the parent
```

### 6.2 Transaction scope

Transaction scope is determined by mode and the presence of partitioners:

```text
txn-scope : Mode → Scope
txn-scope(stream)     = between-partitioners -- maximal sequence of ops on one task without a partitioner
txn-scope(microbatch) = microbatch-attempt   -- entire attempt across all tasks
txn-scope(query)      = query-invocation     -- entire query (read-only, no user writes)
```

A **partitioner** is any operation that routes execution to a (possibly different) task. This includes explicit partitioners (`|hash`, `|all`, `|origin`, etc.) and implicit partitioners (e.g., `select>` auto-repartitions to the PState's home task). The presence of a partitioner creates a transaction boundary in stream topologies.

Effect rules connecting partitioners and Txn:

```text
(Txn-Fragment)
Γ; o; M; stream ⊢ e : τ ! ε       Partitioner ∈ ε
───────────────────────────────────────────────────────
e fragments the enclosing Txn: Write effects before e and after e
are in separate atomic scopes (separate Txn stream-event instances)

(Txn-Preserve)
Γ; o; M; microbatch ⊢ e : τ ! ε    Partitioner ∈ ε
───────────────────────────────────────────────────────
e does NOT fragment the Txn: all Write effects remain in one
atomic scope (single Txn microbatch)

(Atomic-Commit)
∀ Write $$p within one txn-scope(m):
  all writes commit atomically, or all are discarded on retry

(Atomic-Suppress)
<<atomic suppresses partitioner-induced fragmentation within its block
⊢ all Write effects inside <<atomic are in one txn-scope segment
```

Derivable consequences:
- **Stream atomicity limit**: `Write $$p₁` then partitioner then `Write $$p₂` — by Txn-Fragment, these are separate transactions. To make them atomic, eliminate the partitioner (keep writes on the same partition).
- **Microbatch cross-partition atomicity**: `Write $$p₁` then partitioner then `Write $$p₂` — by Txn-Preserve, these remain one transaction. Microbatch topologies provide cross-partition atomicity by construction.

---

## 7) ACID and topology handlers

Transaction scope is defined formally in §6.2. Stream = `between-partitioners` (per-event); microbatch = `microbatch-attempt` (cross-partition atomic). PState/depot writes are not visible until durable on leader and all ISR followers.

### 7.0 AckLevel semantics and read-after-write

```text
AckLevel ::= nil           -- fire-and-forget: no durability guarantee
            | :append-ack   -- depot replicated to leader + ISR followers
            | :ack          -- colocated stream processing complete + PState replicated

AckScope ::= Own            -- depot owned by current module
           | Mirror s       -- depot mirrored from source module s
```

```text
(Ack-Own-Stream)
foreign-append!(d : Depot α M V, val, :ack) → AckReturnMap
  where Owner(d) = Mod M
⊢ event-tree(d, val) complete ∧ PState(M): Visible
  AckReturnMap = { topo-name → ack-return>(v) value | topo ∈ topologies(M) that called ack-return> }

(Ack-Own-Microbatch)
foreign-append!(d : Depot α M V, val, :ack) returns
  where Owner(d) = Mod M
⊢ Depot(M): Committed
⊬ PState(M): Visible                       -- microbatch decoupled from append ack

(Ack-Mirror-Stream)
foreign-append!(d : Depot α M (Mirror s), val, :ack) returns
⊢ Depot(s): Committed
⊬ event-tree(s, val) complete              -- source topology may still be processing
```

Read-after-write rules:

```text
(RAW-Stream)
foreign-append!(d : Depot α M V, val, :ack) returns    -- Owner(d) = Mod M
⊢ foreign-select-one(path, $$p) observes val's effects  -- $$p updated by stream topology

(RAW-Mirror)
foreign-append!(d : Depot α M (Mirror s), val, :ack) returns
⊬ foreign-select-one(path, $$p_s) observes val's effects -- source event tree may be incomplete

(RAW-Microbatch)
foreign-append!(depot, val, :ack) returns
⊬ foreign-select-one(path, $$p) observes val's effects   -- NOT guaranteed

(RAW-Microbatch-Barrier)
foreign-append!(depot, val, ack) returns
wait-for-microbatch-processed-count(ipc, mod, topo, n) returns  -- n ≥ append count
⊢ foreign-select-one(path, $$p) observes val's effects
```

### 7.1 Stream retry semantics

```text
RetryMode ::= :none         -- at-most-once
            | :individual    -- at-least-once; retry failed record(s) only
            | :all-after     -- at-least-once; retry failed + all subsequent on same partition

(Stream-Retry)
retry(record, mode) ⟹ restart from source> block start
                    ⟹ PState writes from failed streaming batch on the failing task discarded
                    ⟹ PState writes already committed on OTHER tasks (via prior partitioner hops) are NOT rolled back
```

### 7.2 Microbatch guarantees

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

### 7.3 Query handler

Formal typing in §5.6. Timeout: `topology.query.timeout.millis`.

---

## 8) Batch-mode semantics (`<<batch`)

Phase-separated relational sublanguage (formal typing in §5.9). Execution starts on task 0.

### 8.1 Pre-agg join semantics

```text
(Join-Inner)
B₁ produces {*x, *a}    B₂ produces {*x, *b}    *x common
────────────────────────────────────────────────────────────
join(B₁, B₂) = {*x, *a, *b}    -- only matching rows survive

(Join-Left-Outer)
B₁ produces {*x, *a}    B₂ produces {*x, **b}   **b unground
────────────────────────────────────────────────────────────
join(B₁, B₂) = {*x, *a, **b}   -- all B₁ rows; **b = nil when unmatched

(Join-Full-Outer)
B₁ produces {*x, **a}   B₂ produces {*x, **b}   both unground
────────────────────────────────────────────────────────────
join(B₁, B₂) = {*x, **a, **b}  -- union of keys

(Delayed-Unground)
*__x : τ                         -- ground until unground var introduced on same source
⟹ *__x behaves as *x until **v appears, then *__x becomes **__x
```

Merge: `unify>` — preserves all rows (no join key required).

### 8.2 Agg phase boundary

First aggregator form marks the pre-agg → agg transition. `+group-by` keys carry into post-agg scope.

### 8.3 Post-agg scope

```text
Γ_postagg = group-by-keys ∪ agg-outputs
Γ_preagg ∩ Γ_postagg = ∅              -- see §5.9 Batch-PostAgg
Route π ∉ effects(post-agg)
```

### 8.4 Subbatches

`defgenerator` + `batch<-` define nested batch computations with independent phases.

---

## 9) Laws and invariants

### 9.1 Visibility progression

```text
                    ┌─────────┐
  Write $$p ──────▶│ Pending  │──── own-event ReadInt (immediate)
                    └────┬────┘
                         │ replicate to leader + ISR
                    ┌────▼─────┐
                    │ Committed │──── same-module ReadInt (batch boundary)
                    └────┬─────┘
                         │ batch/microbatch commit
                    ┌────▼─────┐
                    │ Visible   │──── ReadExt, foreign clients
                    └──────────┘
```

### 9.2 Invariants

1. **Visibility ordering**: Pending → Committed → Visible; no state skips a phase
2. **No external regression**: external readers see only Visible (replicated, durable) versions
3. **Owner-write**: only topology `o` may write `PState o M κ ν V`
4. **Module-local read**: `ReadInt` requires `M' = M` (same module)
5. **Visibility gate**: `ReadExt` requires `V ≠ Private`; `Append` requires `V ≠ Disallow` (unless caller ∈ M)
6. **Mirror immutability**: mirror bindings permit `{ReadExt, Append, Invoke}` only — never `Write`
7. **Unify intersection**: post-unify scope is intersection of incoming branch scopes
8. **Batch phase separation**: pre-agg vars do not leak into post-agg except through aggregator outputs
9. **Declaration scope**: all `declare-*` and `mirror-*` symbols are module-scoped in `Γ_M`
10. **Partition alignment**: `local-select>` / `local-transform>` on key `k` require `current-task = home($$p, k)` for correctness; misaligned writes are silent data loss

---

## 10) One-line characterization

Rama dataflow is a distributed **multi-stream CPS graph calculus** with explicit routing, ownership-controlled durable state effects, and three-phase visibility progression, plus a phase-separated relational sublanguage for batch/query computation.
