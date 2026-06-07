# Phase 3: Implement

Produce the full module: `defmodule` with depot/PState/topology declarations, ETL topology bodies, query topology bodies, and the foreign client wrapper. This is the only phase that writes code.

## Inputs

- `<impl-root>/PLAN.md` — the design. No divergences from the plan are allowed unless the plan has a correctness issue or the plan's approach would have much worse performance. "Easier to implement" is NOT a valid reason to diverge.
- The user-facing spec
- This skill

## Steps


### Step 0 – Avoid unnecessary complexity

These mistakes waste entire retry cycles. Read and internalize before writing any code:

1. **No type hints on `defn` args.** Primitive type hints on function arguments hit Clojure compiler restrictions (max 4 primitive args, arity conflicts). The performance gain from primitive arg hints is irrelevant in virtually all Rama modules — the bottleneck is always disk I/O and network, never function call overhead. Put type hints on `let` bindings inside the body instead where they enable Java interop without compiler issues.
   ```clojure
   ;; WRONG
   (defn my-fn [^HashMap m ^Long k] (.get m k))

   ;; RIGHT
   (defn my-fn [m k]
     (let [^HashMap m m]
       (.get m k)))
   ```

2. **No Clojure special forms in dataflow code.** Bodies of `deframafn`, `deframaop`, and `<<sources` blocks are Rama dataflow, not Clojure. Do NOT use `let`, `do`, `fn`, `#()`, `loop`, `if`, `when`, `cond`, or any other Clojure special form/macro. Use the Rama dataflow equivalents (`identity` for binding, `<<if` for branching, etc.). See `references/dataflow.md`.

3. **Use plain `defn` for Java interop helpers, not `deframafn`.** If you need Java method calls, array manipulation, or imperative logic, write a plain `defn` and call it from dataflow.

4. **Do not think about testing in this phase.** This phase writes the module only. How modules are launched and how IPC is created is not relevant here.


### Step 1 — File skeleton

Write the namespace, `defmodule`, and all declarations: depots, PStates, topology handles, task globals. No topology bodies yet — just the skeleton. Include a comment at the top reminding to re-read PLAN.md before modifying:

```clojure
;; IMPORTANT: Before modifying this file, re-read PLAN.md and check pending todos.
;; Adhere to all previously decided design decisions.

(ns my.module
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.ops :as ops]))

(defmodule MyModule [setup topologies]
  (declare-depot setup *events (hash-by :user-id))

  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$profiles
      {String (fixed-keys-schema {:name String, :email String})})

    ;; ETL topology bodies go in Step 2
    ))
```

### Step 2 — Implement ETL topologies

Read `references/dataflow.md` and `references/paths.md`. Read `references/troubleshooting.md` to avoid common mistakes in dataflow code that are hard to debug due to confusing errors. Also read the reference for the topology types chosen in the plan:
- Microbatch: read `references/microbatch.md`
- Stream: read `references/stream.md`
- If using batch blocks: read `references/batch.md`
- If using aggregators: read `references/aggregators.md`
- If using TaskGlobals: read `references/task-globals.md`

**Rama dataflow can express anything you can express in Clojure, just with different syntax.** Do NOT extract logic to plain `defn` helpers because you think dataflow is limited — use the dataflow equivalents instead. See `references/dataflow.md` for the details of how to express logic in dataflow.

For each ETL topology:
- Bind source: stream `(source> *depot :> *record)`, microbatch `(source> *depot :> %mb)`.
- After sourcing, you are already on the partition determined by the depot's partitioner. Do NOT add a redundant partitioner call (e.g. `|hash`) if the depot is already partitioned by that key – it wastes time appending the continuation to a queue.
- Use a partitioner only when you need to relocate computation a different way than the depot partitioner.
- Write PState: prefer `+compound` with aggregators over `local-transform>` for accumulation patterns. Example:
  ```clojure
  ;; OK but verbose
  (local-transform> [(keypath *k) (nil->val 0) (term (partial + *v))] $$p)

  ;; BETTER: handles initialization, more concise
  (+compound $$p {*k (aggs/+sum *v)})
  ```
  Use `local-transform>` only when you need path control that aggregators don't support (e.g., conditional navigation, `NONE>` deletion).
- Read PState (colocated): `(local-select> [path] $$pstate :> *val)`
- For multi-entity updates that span partitions, use partitioner hops within the topology

### Step 3 — Implement query topologies

Read `references/query-topologies.md` and `references/batch.md`. Also read `references/dataflow.md` and `references/paths.md` if not already loaded.

For each query topology:
- Declare with `<<query-topology` — takes input args, returns output values
- Last partitioner must be `(|origin)` to return results to the caller
- Leading partitioner determines which task starts execution
- Use `local-select>` to read colocated PState data
- Read-only: no `local-transform>` on user PStates (implicit temp PState available per invocation)

### Step 4 — Implement foreign client

Read `references/foreign-client.md`.

`defmodule` binds a var to a module instance — it's not a class to instantiate. Use `(get-module-name MyModule)` to get the module's name string for client operations. Foreign clients obtain depot/PState/query handles by name from a cluster connection:

- `(foreign-depot ipc (get-module-name MyModule) "*depot")` — get depot handle
- `(foreign-pstate ipc (get-module-name MyModule) "$$pstate")` — get PState handle
- `(foreign-query ipc (get-module-name MyModule) "query-name")` — get query topology client handle

Each client write operation must use a single `foreign-append!`. If additional depot writes are needed, do them server-side in the topology via `depot-partition-append!`. A client crash between multiple `foreign-append!` calls leaves the system in an inconsistent state.

Client operations:

- `foreign-append!` — append to depot from client code
- `foreign-select-one` — read single value from PState (one path -> one value). Returns just the navigated value and errors if path navigates to zero values or more than one value.
- `foreign-select` — read multiple values from PState (path may navigate to multiple results). Returns vector of all navigated values.
- `foreign-invoke-query` — invoke query topology for cross-partition or multi-PState reads
- Ack levels: `:append-ack` (depot durable), `:ack` (stream processing complete + PState visible)

Foreign query paths must start with `keypath` for partition routing if not using `:pkey` option (see `references/foreign-client.md` for all constraints).

**Querying subindexed PStates from foreign clients:** The raw subindexed structure object cannot be transferred over the wire — but `foreign-select` works fine with navigators that traverse into it. So the entire subindexed structure can be read with `ALL` navigator, as that puts subindexed values into a plain vector which can be serialized. You do NOT need a query topology just to read subindexed data. Examples:

```clojure
;; Schema: {Long (map-schema Long String {:subindex? true})}

;; WRONG — tries to return the raw subindexed map
(foreign-select-one (keypath id) pstate)

;; RIGHT — ALL navigates into the subindexed map, returns entries as a vector
(foreign-select [(keypath id) ALL] pstate)
;; => [[1 "a"] [2 "b"] [3 "c"]]

;; RIGHT — sorted-map-range for a subset
(foreign-select-one [(keypath id) (sorted-map-range 1 3)] pstate)
;; => [[1 "a"] [2 "b"]]
```

For microbatch modules, use `wait-for-microbatch-processed-count` after appends to ensure PState visibility before reads.

### Step 5 — Verify it compiles and lints clean

Before finishing this phase:

1. Load the module's namespace (REPL eval or `clojure -M -e "(require 'your.module :reload)"`). Resolve every compile error.
2. Run clj-kondo on the module file (`clj-kondo --lint <module-path>` or whatever the project's lint command is). Resolve every error and every warning. Errors are real bugs the agent missed; warnings often catch unused vars, arity mismatches in uncalled helpers, or deprecated forms.

A compile or lint failure surfacing later costs a full retry of downstream phases, so close them out here.

## Output

The module source file (e.g. `<impl-root>/src/<name>/module.clj`), fully implemented: declarations + ETL topology bodies + query topology bodies + foreign client wrappers.

## Do NOT

- Do NOT deviate from `PLAN.md`. If the plan needs to change, the right move is to fail this phase, not to silently re-design while implementing.
- Do NOT extract logic to plain `defn` helpers because you think dataflow is limited.
- Do NOT add redundant partitioners that match the source partitioner.
