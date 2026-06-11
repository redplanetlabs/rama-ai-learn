# Foreign Client Reference

Foreign client APIs are the external interface to a running Rama module. Clients construct handles to depots, PStates, and query topologies, then invoke operations through those handles. All operations route to the correct partition in the target module.

## Formal model

### Type signatures

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

### Routing

```text
foreign-route : Path × PState → TaskId
foreign-route(path, pstate) = home(pstate, first-key(path))
```

`foreign-select-one` routes to a single partition determined by the first key in the path. `foreign-invoke-query` routes via the query topology's partitioners (can access 1..N partitions).

**Path constraints:** Foreign query paths must start with `keypath` (for partition routing), unless using the `:pkey` option to specify the partition key separately.

### Append judgment

```text
(Append-Foreign)
Γ ⊢ *d : Depot α M' V       V ≠ Disallow
---------------------------------------------
foreign-append!(*d, val, ack) : AckReturnMap ! {Append *d, Ack}
```

`:disallow` depots reject external appends.

### Ack levels

```text
AckLevel ::= nil           -- fire-and-forget: no durability guarantee
           | :append-ack   -- depot replicated to leader + ISR followers
           | :ack          -- colocated stream processing complete + PState replicated
```

### Read-after-write rules

```text
(RAW-Stream)
foreign-append!(d : Depot α M V, val, :ack) returns    -- Owner(d) = Mod M
⊢ foreign-select-one(path, $$p) observes val's effects  -- $$p updated by stream topology

(RAW-Microbatch)
foreign-append!(depot, val, :ack) returns
⊬ foreign-select-one(path, $$p) observes val's effects   -- NOT guaranteed

(RAW-Microbatch-Barrier)
foreign-append!(depot, val, ack) returns
  then wait-for-microbatch-processed-count(...)
⊢ foreign-select-one(path, $$p) observes val's effects
```

### Ack visibility by context

```text
                    Own / Stream     Own / Microbatch    Mirror s / Stream
nil                 —                —                   —
:append-ack         Depot: Committed Depot: Committed    Depot(s): Committed
                    PState: Pending  PState: Pending     PState(s): Pending
:ack                Depot: Committed Depot: Committed    Depot(s): Committed
                    PState: Visible  PState: Pending(!)  PState(s): Pending(!)
```

- `:ack` on an **owned stream** depot guarantees PState visibility (event tree complete + committed).
- `:ack` on a **microbatch** depot only confirms depot replication — microbatch processing is decoupled from append acknowledgement.
- `:ack` on a **mirror** depot only confirms the append reached the source depot — the source module's topologies may not have finished processing. The ack scope does not cross module boundaries.

### Ack return

```text
(Ack-Return)
Γ; o; M; stream ⊢ ack-return>(val) : Unit ! {Ack}
```

`ack-return>` emits a value into the `AckReturnMap` under the topology name. The caller receives this map from `foreign-append!` with `:ack`. Multiple `ack-return>` calls are aggregated by `:ack-return-agg` (if specified on the source) or last-write-wins.

### `foreign-select-one` contract

Path must navigate to exactly one value. Throws if path produces 0 or >1 values. Use `keypath` (navigates to nil for absent keys = multiplicity 1), not `must` (navigates to zero values for absent keys = error).

```text
foreign-select-one([(keypath k)] p)  where k absent  →  nil  (keypath navigates to nil)
foreign-select-one([(must k)] p)     where k absent  →  THROWS (0 navigations)
```

### Subindexed PState queries

`foreign-select-one` on a PState value that contains a subindexed map/set/vector will error since subindexed structures cannot be transferred over the wire, as the underlying RocksDB reference cannot be serialized.

To read an entire subindexed structure, use `foreign-select` with `ALL`:

```clojure
;; Returns vector of [key value] entries, or [] if user-id is absent
(foreign-select [(keypath user-id) ALL] $$pstate)
```

More commonly, you would select a subset of a subindexed structure with a range query navigator like `sorted-map-range` or `sorted-map-range-from`.

Foreign reads **yield implicitly on the source task**: a foreign read that iterates many entries suspends periodically to let other events run, like `:allow-yield? true` does for topology-side reads. No option is needed (or available) — a large foreign read does not block the serving task thread uncooperatively.

## Syntax (EBNF)

```ebnf
(* Client construction — obtain handles once after module launch: *)
cluster-mgr     = '(open-cluster-manager' config-map ')'
                | '(open-cluster-manager-internal' config-map ')' ;
close-mgr       = '(close!' mgr ')' ;
depot-client    = '(foreign-depot' ipc module-name string ')' ;
pstate-client   = '(foreign-pstate' ipc module-name string ')' ;
query-client    = '(foreign-query' ipc module-name string ')' ;
module-name     = string ;              (* use (get-module-name ModuleVar) *)

(* Depot operations: *)
append-sync     = '(foreign-append!' depot-client expr ack-level ')' ;
                (* → Map<String,Object> with :ack; nil otherwise *)
append-async    = '(foreign-append-async!' depot-client expr ack-level ')' ;
                (* → CompletableFuture<Map<String,Object>> with :ack *)
ack-level       = ':ack' | ':append-ack' | 'nil' ;

(* PState reads: *)
select-one      = '(foreign-select-one' path-vec pstate-client [ read-opts ] ')' ;
                (* → single value; throws if path navigates to 0 or >1 values *)
select-many     = '(foreign-select' path-vec pstate-client [ read-opts ] ')' ;
                (* → list of values *)
select-one-async = '(foreign-select-one-async' path-vec pstate-client ')' ;
                (* → CompletableFuture<value> *)
select-async    = '(foreign-select-async' path-vec pstate-client ')' ;
                (* → CompletableFuture<list> *)
path-vec        = '[' { navigator } ']' ;
read-opts       = '{' ':pkey' expr '}' ;

(* Compiled path variants: *)
comp-select-one = '(compiled-foreign-select-one' compiled-path pstate-client ')' ;
comp-select     = '(compiled-foreign-select' compiled-path pstate-client ')' ;

(* Query invocation: *)
query-sync      = '(foreign-invoke-query' query-client { arg } ')' ;
                (* → query result value *)
query-async     = '(foreign-invoke-query-async' query-client { arg } ')' ;
                (* → CompletableFuture<result> *)

(* Reactive proxies: *)
proxy-sync      = '(foreign-proxy' path-vec pstate-client '{' ':callback-fn' fn '}' ')' ;
                (* → ProxyState *)
proxy-async     = '(foreign-proxy-async' path-vec pstate-client ')' ;
                (* → CompletableFuture<ProxyState> *)

(* Depot data access: *)
partition-info  = '(foreign-depot-partition-info' depot-client partition-idx ')' ;
depot-read      = '(foreign-depot-read' depot-client partition-idx start-offset end-offset ')' ;
object-info     = '(foreign-object-info' depot-client ')' ;
```

## Quick reference

| Construct | Syntax |
|---|---|
| Cluster manager | `(open-cluster-manager {"conductor.host" "1.2.3.4"})` |
| Cluster manager (internal) | `(open-cluster-manager-internal {"conductor.host" "1.2.3.4"})` |
| Close manager | `(close! manager)` |
| Depot client | `(foreign-depot ipc mod-name "*d")` |
| PState client | `(foreign-pstate ipc mod-name "$$p")` |
| Query client | `(foreign-query ipc mod-name "q")` |
| Append (sync) | `(foreign-append! depot data :ack)` |
| Append (async) | `(foreign-append-async! depot data :ack)` |
| Select one | `(foreign-select-one path pstate)` |
| Select (sequence) | `(foreign-select path pstate)` |
| Select with partition key | `(foreign-select-one path pstate {:pkey k})` |
| Compiled select one | `(compiled-foreign-select-one compiled-path pstate)` |
| Compiled select | `(compiled-foreign-select compiled-path pstate)` |
| Compiled select one (async) | `(compiled-foreign-select-one-async compiled-path pstate)` |
| Compiled select (async) | `(compiled-foreign-select-async compiled-path pstate)` |
| Select one (async) | `(foreign-select-one-async path pstate)` |
| Select (async) | `(foreign-select-async path pstate)` |
| Invoke query (sync) | `(foreign-invoke-query query args...)` |
| Invoke query (async) | `(foreign-invoke-query-async query args...)` |
| Reactive proxy (sync) | `(foreign-proxy path pstate {:callback-fn f})` |
| Reactive proxy (async) | `(foreign-proxy-async path pstate)` |
| Compiled proxy (sync) | `(compiled-foreign-proxy compiled-path pstate {:callback-fn f})` |
| Compiled proxy (async) | `(compiled-foreign-proxy-async compiled-path pstate)` |
| Proxy status | `(proxy-status proxy)` → `:active`, `:terminated`, `:terminated-ungracefully` |
| Depot partition info | `(foreign-depot-partition-info depot partition-idx)` |
| Depot partition info (async) | `(foreign-depot-partition-info-async depot partition-idx)` |
| Depot read range | `(foreign-depot-read depot partition-idx start-offset end-offset)` |
| Depot read range (async) | `(foreign-depot-read-async depot partition-idx start-offset end-offset)` |
| Depot object info | `(foreign-object-info depot)` |
| Microbatch depot info | `(microbatch-depot-info cluster mod-name topo-name)` |

| Config | Purpose |
|---|---|
| `foreign.depot.flush.delay.millis` | Append batching delay (0–50ms recommended) |
| `foreign.depot.operation.timeout.millis` | Foreign depot operation timeout |
| `replication.depot.append.timeout.millis` | Depot replication timeout |

## Module name resolution

Module name resolves to `namespace/Name` (e.g. `my.ns/MyModule`). Use `get-module-name` on the module var for the precise name. All foreign API calls use this exact name.

Call `get-module-name` only **after** the `defmodule` form has run and bound the module var.

Troubleshooting:
- `Module not alive: <ModuleName>` from `foreign-depot`/`foreign-pstate`/`foreign-query` — almost always a hardcoded short module name instead of fully-qualified. Always derive with `(get-module-name MyModule)`.
- `IllegalAccessError: *module-name* does not exist` — do not depend on `*module-name*` from `com.rpl.rama`. Instead define `(def module-name* (r/get-module-name MyModule))` after `defmodule`.

## `foreign-select-one` vs `foreign-invoke-query`

```text
                         foreign-select-one/select     foreign-invoke-query
────────────────────────────────────────────────────────────────────────────
Partitions accessed      1 (home($$p, first-key(path))) 1..N (via partitioners)
Cross-partition read     no                             yes (|hash, |all, |global)
Multiple PStates         no (one PState per call)       yes (any module-local PState)
Aggregation              path-based (subselect, view)   full dataflow (<<batch, aggregators)
Derived computation      path-based (view, transformed) full dataflow
Nil coalescing           path-based (nil->val, etc.)    topology-side (or>)
```

- Paths in `foreign-select` run on the source module's topology, so all functions used in paths must be present in the source module code (on its classpath).
- Use `foreign-select-one` for single-key point reads where the path navigates within one partition.
- Use `foreign-invoke-query` when the read requires cross-partition access, joining multiple PStates, or computation beyond what paths provide.

## Usage patterns

- Create depot/PState/query handles **once** via `foreign-depot`, `foreign-pstate`, `foreign-query` — reusable client objects, not per-call lookups.
- `foreign-select-one` requires the path to navigate to exactly one value (throws on 0 or >1). With `keypath`, absent keys navigate to nil (multiplicity 1, safe). With `must`/`FIRST`/`ALL`, 0 navigations cause a throw. Coalesce nil server-side in query topologies (`or>`) to avoid duplicating fallback logic in clients.
- For graph/tree traversals, expose a point-read query and run BFS/DFS in client code with repeated `foreign-invoke-query` calls.
- Path arguments: bare navigator `(keypath k)` or vector `[(keypath k)]`. First argument is path, second is client handle.
- Foreign-client read-after-write: `:ack` guarantees read-after-write for stream topologies. Stale reads after stream write — check ack level. After microbatch write — use `wait-for-microbatch-processed-count`.

### Ack-return value extraction

`foreign-append!` with `:ack` returns a `Map<String, Object>` keyed by **topology name** (the string passed to `stream-topology`). When `ack-return>` is used in a stream topology named `"my-topo"`, extract the value with `(get result "my-topo")`:

```clojure
;; topology: (let [s (stream-topology topologies "inventory")] ... (ack-return> *val))
;; client:
(let [result (r/foreign-append! depot data :ack)]
  (get result "inventory"))   ;=> value from ack-return>
```

With `:ack-return-agg` on the source, multiple `ack-return>` calls are aggregated per the specified combiner before being placed in the map.

### Depot data access

- `foreign-depot-partition-info` returns `{:start-offset N :end-offset N}` (Clojure map). Access keys directly.
- `foreign-depot-read` returns a sequence of records (Clojure data).
- For partition count: `(-> (foreign-object-info depot) :num-partitions)`. If unavailable, probe indexes from `0` upward with `foreign-depot-partition-info` until `ExceptionInfo`.
- A module can declare only a depot with no topologies — use when reads go directly to depot APIs.

## Example

```clojure
(def module-name (r/get-module-name MyModule))
(let [depot (r/foreign-depot ipc module-name "*events")
      ps    (r/foreign-pstate ipc module-name "$$balances")
      q     (r/foreign-query ipc module-name "summary")]
  (r/foreign-append! depot {:user "alice" :delta 100} :ack)
  (r/foreign-select-one [(keypath "alice")] ps)       ;=> 100
  (r/foreign-invoke-query q "alice"))                  ;=> {:total 100}
```
