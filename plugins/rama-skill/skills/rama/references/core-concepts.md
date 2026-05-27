# Rama Core Concepts

**Module** is the unit of deployment. A module contains depots (append-only logs), PStates (partitioned durable indexes), topologies (computation), and optionally mirrors (cross-module references) and task globals (per-task resources).

**Depots** are append-only partitioned logs. All data enters the system through depots. Each append is routed to a partition by the depot's partitioner (e.g., `(hash-by :user-id)`).

**PStates** are partitioned, durable, replicated indexed state. Topologies consume depot records and materialize PStates. A PState is owned by exactly one topology — only that topology may write it. Any topology in the same module may read it.

**Topologies** are computation units:
- **Stream** — event-driven, low-latency, configurable at-least-once or at-most-once. Each depot record triggers immediate processing. Transaction scope = between partitioners (per-task atomic). Stream topologies participate in depot ack — a client appending with `:ack` blocks until the stream topology finishes processing that record and PState updates are visible. This enables write-then-read-back patterns (e.g., register user, then immediately read back).
- **Microbatch** — batch-oriented, exactly-once PState updates. Entire microbatch is a cross-partition transaction. Higher throughput due to less per-record overhead. Microbatch does not participate in depot ack — appending with `:ack` only confirms the depot write is durable, not that PState updates are visible. Use `wait-for-microbatch-processed-count` to wait for processing.
- **Query** — on-demand distributed read-only computation. Invoked by clients or other topologies. Implicit batch mode.

**Default to microbatch.** It provides exactly-once PState updates and cross-partition atomicity. Use stream only when: (1) single-digit millisecond update latency is required, or (2) depot ack coordination is needed (write-then-read-back patterns like registration or friend requests).

## Dataflow

Rama code inside topologies and `deframafn`/`deframaop` uses a **dataflow language** that can express anything Clojure can, with different syntax. Clojure special forms are replaced by dataflow equivalents:

- **Binding:** `let` → `(:> *var)`. `(+ 1 2 :> *a)` binds `*a` for all downstream code, exactly like `(let [a (+ 1 2)] ...)`. Any operation can bind results with `:>`.
- **Branching:** `if` → `<<if`, `cond` → `<<cond`, `case` → `<<switch`. All branch constructs unify variable scope afterward.
- **Loops:** `loop`/`recur` → `loop<-`/`continue>`. `recur>` for tail-call recursion in `deframafn`.
- **Anonymous fns:** `fn`/`#()` → `<<ramafn`/`<<ramaop`.
- **Logical ops:** `and`/`or` → `and>`/`or>`.
- **Think in emits, not returns.** An operation can emit zero, one, or many times. `(ops/explode *list :> *item)` emits once per element — all downstream code runs once per emit. Zero emits means downstream code is skipped entirely (no writes, no side effects).
- **Named output streams.** A `deframaop` can emit on multiple streams: `(:ok> *val)`, `(:err> *msg)`. Callers use anchors/hooks to handle each stream separately.

All `clojure.core` functions work in operation position (e.g., `inc`, `conj`, `assoc`, `str`, `count`, `get`, `quot`, `rem`, `min`, `max`). Expressions nest naturally — anywhere you could put a variable, you can put a nested expression: `(<<if (< *a 10) ...)`, `(+ (* *x *x) (* *y *y) :> *sum)`. The only things that do NOT work in dataflow: Java interop (`.method`), type hints, and Clojure special forms — use the equivalents above instead. Note that static Java methods can be used in dataflow (e.g. `(System/currentTimeMillis :> *time)`).

**What's shared with Clojure:** Namespaces (`ns`, `require`, `import`), `defn`/`defrecord`/`deftype`, all Clojure data structures (maps, vectors, sets, keywords, strings). The dataflow language replaces Clojure's control flow and binding forms, but everything else is standard Clojure.

## Paths

Paths are the primary way to read and write PStates, both inside topologies (via `local-select>`/`local-transform>`) and from foreign clients (via `foreign-select-one`/`foreign-select`). A path is a sequence of navigators that drill into a data structure.

- **Reads**: `(local-select> [(keypath *k)] $$p :> *val)` — navigate to key `*k`, bind value
- **Writes**: `(local-transform> [(keypath *k) (termval *val)] $$p)` — navigate to key `*k`, set value
- **Update in place**: `(local-transform> [(keypath *k) (nil->val 0) (term inc)] $$p)` — navigate, default to 0 if nil, increment. This is a single RocksDB read+write, more efficient than separate select+transform.

When aggregators are more concise or handle initialization, use `+compound`: `(+compound $$p {*k (aggs/+sum *v)})` — atomically adds `*v` to key `*k`, initializing to 0 if absent. Aggregators also support two-phase aggregation in batch blocks for distributed computation.

Paths are always used for reads. For writes, choose between `local-transform>` with path navigators (fine-grained control) and `+compound` with aggregators (handles initialization, more concise for accumulation patterns).

## Foreign Context

"Foreign" means code running outside a module's topology — application servers, CLI tools, other services, or test code. Foreign clients interact with a deployed module through four operations:

- **Depot appends** (`foreign-append!`): write data into the system. Ack levels control how long the caller blocks (`:append-ack` = depot durable, `:ack` = depot durable and stream processing complete).
- **PState point queries** (`foreign-select-one`, `foreign-select`): read PState data using path expressions. The client automatically routes to the correct partition based on the path's first key. These are non-reactive — each call is a one-shot read.
- **PState reactive queries** (`foreign-proxy`): subscribe to PState changes. The proxy receives updates whenever the navigated value changes, enabling real-time UIs without polling.
- **Query topology invocations** (`foreign-invoke-query`): invoke a query topology for reads that require cross-partition computation, joining data from multiple PStates, or combining multiple reads on the same partition into a single roundtrip.

## Mirrors

Mirrors enable cross-module composition without copying data. A module can import references to another module's depots, PStates, and query topologies:

- **Mirror depot**: subscribe to or append into another module's depot. Appends route to the source module.
- **Mirror PState**: read another module's PState. Read-only — no writes allowed. Reads are async (suspension point) since they route to the source module's partitions.
- **Mirror query**: invoke another module's query topology.

Mirrors are declared at module setup time and are subject to the source's visibility settings (private depots/PStates cannot be mirrored).

## ACID and Replication

Rama provides ACID guarantees scoped by topology type. Stream transactions are atomic between partitioners on a single task. Microbatch transactions are atomic across all tasks for the entire batch.

PState data is replicated automatically. Replication factor is configurable. Replication is incremental — only changed data is replicated, not the entire PState. Writes are not visible to external readers until replicated to the leader and all in-sync replicas.

## Serialization

Any Java/Clojure object can be used in depots, PStates, and distributed computation. Core Java and Clojure types (String, Long, maps, vectors, keywords, etc.) have efficient built-in serializers. Custom types are registered via Nippy extensions — once registered globally on the module or client, those types can be used freely in PState values, depot records, and query topology arguments/results.

## Task Model

A module runs on N tasks (partitions). Each depot, PState (partitioned state), and topology is sharded across all N tasks. Module implementation chooses how data is sharded across tasks. Most common way to shard is with `hash(k) mod num-tasks` either through `hash-by` depot partitioner or with `(|hash *k)` dataflow call.

**Partition alignment** is the most critical correctness rule: `local-select>` and `local-transform>` operate on the current task's partition. If current task is wrong, writes land on the wrong partition (silent data loss) and reads return nil. Establish alignment with correct partitioner (most commonly `(|hash *k)`) before local operations.

**Tasks, threads, and workers:** Tasks are distributed across threads, and threads across workers (JVM processes on cluster machines). For example, 64 tasks / 32 threads / 8 workers = 2 tasks per thread, 4 threads per worker. The task count is fixed at module creation. Scaling is done by adjusting threads and workers — more threads = more parallelism, more workers = more machines.
