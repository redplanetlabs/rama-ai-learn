# Rama Module Testing

Test Rama modules end-to-end using `InProcessCluster`, which
simulates a full cluster in a single process with no mocks required.

## Test Structure

**Minimize IPC and module launches.** Each `create-ipc` + `launch-module!` is expensive — it starts a full cluster simulation that can take 30s to complete launching. Do NOT create a separate IPC/module per assertion or per scenario. Test scenarios that use disjoint keys do not interfere and belong in the same `with-open` block, even if they exercise different operations. Use one `with-open` block covering multiple `testing` sections that share the same module instance:

```clojure
(deftest module-test
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc MyModule {:tasks (rand-nth [2 4 8]) :threads 2 :workers 2})
    (let [module-name (get-module-name MyModule)
          depot (foreign-depot ipc module-name "*depot")
          pstate (foreign-pstate ipc module-name "$$pstate")]

      (testing "basic writes and reads"
        (foreign-append! depot ...)
        (rtest/wait-for-microbatch-processed-count ipc module-name "topo" 1)
        (is (= expected (foreign-select-one ...))))

      (testing "edge cases"
        ...)

      (testing "error handling"
        ...))))
```

**Write fewer test functions with more assertions.** Each test function that launches a module adds seconds of startup time. Group related assertions into `testing` blocks within the same test function. One well-structured test with 20 assertions is better than 10 tests with 2 assertions each.

**Randomize task count.** Unless a test requires a specific number of tasks, use a random task count (e.g., `(rand-nth [2 4 8])`) to exercise partitioning logic across different configurations. Partition alignment bugs often only surface with certain task counts.

**Launch config invariant: `:tasks` ≥ `:threads` ≥ `:workers`.** `:threads` is the cluster-wide total of task threads spread across all workers, and each worker needs at least one. Violating it fails at launch (e.g. "Number of workers must be less than or equal to the number of threads").

## Axioms

1. **IPC = real cluster** — no capability or semantic differences; only replication factor (always 1) and serialization scope differ
2. **ACK blocks downstream** — acked depot appends block until all downstream processing on colocated stream topologies complete; assertions are immediate
3. **ACK does not cross module boundaries** — mirror depot stream topologies in other modules are not waited on; polling required
4. **Microbatch count is cumulative** — `wait-for-microbatch-processed-count` tracks total records ever processed, not since last call
5. **Pause completes in-flight** — `pause-microbatch-topology!` waits for the current microbatch to finish before returning
6. **Deletion is explicit** — removing PStates/depots on update requires `objects-to-delete`; omission throws
7. **Serialization only crosses workers** — custom serializations only exercise between workers, not within; use `:workers > 1` to test them
8. **test-pstate is module-free** — `create-test-pstate` tests PState operations and `deframafn`/`deframaop` without launching a module
9. **Hashing is deterministic** — `gen-hashing-index-keys` always produces the same keys for the same inputs

## Setup

```clojure
(ns my.module-test
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.path :refer :all]
    [com.rpl.rama.test :as rtest]
    [clojure.test :refer [deftest is testing]]))
```

## InProcessCluster

Create with `rtest/create-ipc`. Always use `with-open` for cleanup.

```clojure
(with-open [ipc (rtest/create-ipc)]
  (rtest/launch-module! ipc MyModule {:tasks 4 :threads 2})
  (let [module-name (get-module-name MyModule)
        depot       (foreign-depot ipc module-name "*depot")
        counts      (foreign-pstate ipc module-name "$$counts")]
    (foreign-append! depot "a")
    (foreign-append! depot "a")
    (is (= 2 (foreign-select-one (keypath "a") counts)))))
```

### LaunchConfig Options

| Key | Description |
|---|---|
| `:tasks` | Number of task partitions |
| `:threads` | Number of task threads per worker |
| `:workers` | Number of workers (default 1) |

Multiple workers exercise custom serializations (Rama only serializes
between workers). Replication factor is always 1 on InProcessCluster.

### Custom Serializations

```clojure
(with-open [ipc (rtest/create-ipc [MyCustomSerialization])]
  ...)
```

## Testing Stream Topologies

With colocated stream topologies, depot appends using `AckLevel/ACK`
(the default) block until all downstream processing finishes. Assert
on PStates immediately after appends.

```clojure
(foreign-append! depot "x")
(foreign-append! depot "x")
(is (= 2 (foreign-select-one (keypath "x") counts)))
```

## Testing Microbatch Topologies

Microbatch processing is asynchronous to depot appends. Use
`wait-for-microbatch-processed-count` to block until processing
completes. Accepts an optional `timeout-millis` (throws on timeout).

```clojure
(foreign-append! depot "a")
(foreign-append! depot "b")
(foreign-append! depot "a")
(rtest/wait-for-microbatch-processed-count ipc module-name "counter" 3)
(is (= 2 (foreign-select-one (keypath "a") counts)))

(foreign-append! depot "a")
(rtest/wait-for-microbatch-processed-count ipc module-name "counter" 4)
(is (= 3 (foreign-select-one (keypath "a") counts)))
```

The count is the **total** records ever processed by the topology,
not since the last call.

### Controlling Microbatch Composition

Use pause/resume to guarantee which records appear in a single
microbatch (useful for testing batch blocks):

```clojure
(rtest/pause-microbatch-topology! ipc module-name "counter")
(foreign-append! depot "a")
(foreign-append! depot "b")
(foreign-append! depot "c")
(rtest/resume-microbatch-topology! ipc module-name "counter")
```

`pause-microbatch-topology!` waits for the in-flight microbatch to
complete before returning (no-op if already paused). The next
microbatch contains all records appended while paused (up to 1000 per
partition). `resume-microbatch-topology!` is a no-op if already active.

## Testing Stream Topologies with Mirror Depots

Depot appends with `AckLevel/ACK` do **not** wait for stream
topologies in other modules to finish. Poll the PState with a
timeout:

```clojure
(defn assert-value-attained
  "Polls f until (= expected (f)), with timeout-ms (default 30s)."
  ([expected f] (assert-value-attained expected f 30000))
  ([expected f timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [v (f)]
         (cond
           (= expected v) true
           (> (System/currentTimeMillis) deadline)
           (throw (ex-info "Timed out" {:expected expected :actual v}))
           :else (do (Thread/sleep 50) (recur))))))))

;; Usage
(with-open [ipc (rtest/create-ipc)]
  (rtest/launch-module! ipc DepotModule {:tasks 8 :threads 2})
  (rtest/launch-module! ipc CounterModule {:tasks 4 :threads 2})
  (let [depot  (foreign-depot ipc (get-module-name DepotModule) "*depot")
        counts (foreign-pstate ipc (get-module-name CounterModule) "$$counts")]
    (foreign-append! depot "a")
    (foreign-append! depot "a")
    (assert-value-attained 2 #(foreign-select-one (keypath "a") counts))))
```

Use a generous timeout (30s) to avoid false failures from GC pauses.

## Module Update

Test module evolution by overriding `getModuleName` so both versions
share the same module name:

```clojure
(with-open [ipc (rtest/create-ipc)]
  (rtest/launch-module! ipc ModuleV1 {:tasks 4 :threads 2})
  (let [module-name (get-module-name ModuleV1)
        depot  (foreign-depot ipc module-name "*depot")
        counts (foreign-pstate ipc module-name "$$counts")]
    (foreign-append! depot "a")
    (is (= 2 (foreign-select-one (keypath "a") counts)))

    (rtest/update-module! ipc ModuleV2)
    (foreign-append! depot "a")
    (is (= 3 (foreign-select-one (keypath "a") counts)))))
```

Existing depot and PState clients automatically redirect to the
updated module instance.

`update-module!` also accepts an **unchanged** module value. This is
useful for simulating a worker restart in tests: task globals are
recreated (`prepareForTask` runs again) and all in-memory state is
lost, while PStates and depots persist.

### Removing PStates or Depots on Update

When the new version removes PStates or depots, specify them
explicitly:

```clojure
(rtest/update-module! ipc ModuleV2
  {:objects-to-delete ["*depot2" "$$old-pstate"]})
```

The deleted objects must be specified exactly or the update throws.

## Module Destroy

```clojure
(rtest/destroy-module! ipc "com.mycompany.MyModule")
```

Fails if other modules depend on the module being destroyed.

## Circular Dependencies

Create mutual module dependencies through sequential updates:

1. Launch Module A (with depot only)
2. Launch Module B (mirrors Module A's depot)
3. Update Module A to add dependency on Module B's PState

Override `getModuleName` on Module A's versions to keep the same
module name across updates.

## create-test-pstate

Test PState operations and `deframafn`/`deframaop` without launching
a module. Use `with-open` for cleanup.

```clojure
(with-open [tp (rtest/create-test-pstate
                 {String (map-schema Long String {:subindex? true})})]
  (rtest/test-pstate-transform [(keypath "k" 1) (termval "v")] tp)
  (is (= "v" (rtest/test-pstate-select-one [(keypath "k" 1)] tp))))
```

### Testing a deframafn

```clojure
(deframafn inc-counter [$$p k]
  (local-transform> [(keypath k) (term inc)] $$p)
  (:>))

(with-open [tp (rtest/create-test-pstate {String Long})]
  (rtest/test-pstate-transform [(keypath "a") (termval 10)] tp)
  (inc-counter tp "a")
  (is (= 11 (rtest/test-pstate-select-one [(keypath "a")] tp))))
```

Works identically for `deframaop` — pass the test PState where the
operation expects a `$$pstate` argument.

## gen-hashing-index-keys

Generate keys that hash to specific task partitions, useful for
testing partition-dependent logic:

```clojure
;; Returns a seq where nth element hashes to task n
(rtest/gen-hashing-index-keys 4)
;; With prefix
(rtest/gen-hashing-index-keys "user-" 4)
```

Deterministic — always produces the same keys for the same inputs.

## com.rpl.rama.test API Summary

| Function | Purpose |
|---|---|
| `(rtest/create-ipc)` | Create InProcessCluster |
| `(rtest/create-ipc [serdes])` | Create IPC with custom serializations |
| `(rtest/launch-module! ipc mod cfg)` | Launch module |
| `(rtest/update-module! ipc mod)` | Update module |
| `(rtest/update-module! ipc mod opts)` | Update with `:objects-to-delete` |
| `(rtest/destroy-module! ipc name)` | Destroy module |
| `(rtest/wait-for-microbatch-processed-count ipc mod topo n)` | Wait for microbatch |
| `(rtest/wait-for-microbatch-processed-count ipc mod topo n timeout-ms)` | Wait with timeout |
| `(rtest/pause-microbatch-topology! ipc mod topo)` | Pause microbatch |
| `(rtest/resume-microbatch-topology! ipc mod topo)` | Resume microbatch |
| `(rtest/create-test-pstate schema)` | Create test PState |
| `(rtest/test-pstate-transform path tp)` | Transform test PState |
| `(rtest/test-pstate-select-one path tp)` | Query test PState |
| `(rtest/test-pstate-select path tp)` | Query test PState (multi) |
| `(rtest/gen-hashing-index-keys n)` | Keys hashing to each task |
| `(rtest/gen-hashing-index-keys prefix n)` | Keys with prefix |

## Simulated Time

Modules that check the current time should use `TopologyUtils/currentTimeMillis` instead of `System/currentTimeMillis`. In production this returns real time. In tests, you can control it:

```clojure
(:import [com.rpl.rama.helpers TopologyUtils])

(with-open [_sim (TopologyUtils/startSimTime)]
  ;; Sim time starts at 0
  (TopologyUtils/advanceSimTime 60000) ;; now at 60000ms
  ;; Module code calling TopologyUtils/currentTimeMillis sees 60000
  (TopologyUtils/advanceSimTime 30000) ;; now at 90000ms
  )
;; After close, currentTimeMillis returns real system time again
```

`startSimTime` uses global state — call `.close` on the returned `Closeable` before exiting the test. Use `with-open` for automatic cleanup.

## Testing Tick Depots

Tick depots fire automatically on a timer, which makes testing non-deterministic. To control when ticks fire in tests, replace the tick depot with a regular global depot that tests can append to manually.

Define a global var somewhere in the project that tests can redef:

```clojure
(def REPLACE-TICK-DEPOTS false)

(defmodule MyModule [setup topologies]
  (if REPLACE-TICK-DEPOTS
    (declare-depot setup *expire-tick :random {:global? true})
    (declare-tick-depot setup *expire-tick 30000))
  ...)
```

Tests set this flag with `with-redefs` before creating the module:

```clojure
(with-redefs [my.config/REPLACE-TICK-DEPOTS true]
  (with-open [_sim (TopologyUtils/startSimTime)]
    ;; Launch module — tick depot is now a regular depot
    ;; Advance sim time, then append to tick depot to trigger processing
    (TopologyUtils/advanceSimTime 60000)
    (foreign-append! tick-depot nil)
    (rtest/wait-for-microbatch-processed-count ipc module-name "topo" 1)))
```

## References

- [Clojure API: com.rpl.rama.test](https://redplanetlabs.com/clojuredoc/com.rpl.rama.test.html)
- [Testing (main)](https://redplanetlabs.com/docs/~/testing.html)
- [Testing (Clojure)](https://redplanetlabs.com/docs/~/clj-testing.html)
- [rama-demo-gallery tests](https://github.com/redplanetlabs/rama-demo-gallery)
