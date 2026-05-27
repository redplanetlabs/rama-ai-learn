# Rama Depot Reference

Comprehensive reference for depot types, declaration APIs, client
operations, migrations, trimming, and tuning.

## Depot Types

### Regular Depots

Append-only partitioned logs. The most common depot type.

```clojure
(declare-depot setup *depot :random)
(declare-depot setup *depot (hash-by :field))
(declare-depot setup *depot (hash-by extract-fn))
(declare-depot setup *depot :disallow)
```

### Tick Depots

Emit on time intervals, not client appends. Cannot be appended to.

```clojure
(declare-tick-depot setup *tick 60000)  ;; every 60 seconds
```

Tick behavior by topology type:
- **Stream**: fires at configured frequency (push-based)
- **Microbatch**: fires once per attempt if enough time has elapsed

Ticks emit on task 0. To trigger all tasks, follow with `(|all)`.

### Global Depots

Single partition on task 0. For low-throughput data only.

```clojure
(declare-depot setup *depot :random {:global? true})
```

The partitioner argument is irrelevant for global depots.

### Output Depots (Disallow)

Reject client appends. Only writable from topology code via
`depot-partition-append!`. Use for cross-module data publishing.

```clojure
(declare-depot setup *notifications :disallow)
```

### Mirror Depots

Cross-module reference. Data stored on source module only.

```clojure
(mirror-depot setup *ext-depot "com.company/Module" "*depot")
```

Appending to mirror depots requires explicit partition routing:

```clojure
(|hash$$ $$ext-pstate *key)
(depot-partition-append! *ext-depot *value :append-ack)
```

Mirror depot ack: completes without waiting for remote module
topologies to finish processing.

### External Depots

Bridge external systems (e.g., Kafka) into Rama via the
`ExternalDepot` interface (a task global). Supports backpressure for
stream topologies. See `rama-kafka` for reference implementation.

Interface methods:
- `getNumPartitions()` — partition count
- `startOffset(partitionIndex)` — first available offset
- `endOffset(partitionIndex)` — next offset (exclusive)
- `offsetAfterTimestampMillis(partitionIndex, millis)` — temporal offset lookup
- `fetchFrom(partitionIndex, startOffset, endOffset)` — range fetch (microbatch)
- `fetchFrom(partitionIndex, startOffset)` — incremental fetch (stream)

## Partitioning

### Built-in Schemes

| Scheme | Syntax | Behavior |
|---|---|---|
| Random | `:random` | Even distribution, no ordering |
| Hash by keyword | `(hash-by :k)` | Deterministic by map field |
| Hash by function | `(hash-by fn-var)` | Deterministic by extracted value |
| Hash by identity | `(hash-by identity)` | Hash of value itself |
| Disallow | `:disallow` | Client appends throw exception |

`hash-by` function must be a keyword or top-level var (not `fn` literal).

### Custom Partitioners

```clojure
(defdepotpartitioner partition-by-region
  [data num-partitions]
  (mod (hash (:region data)) num-partitions))

(declare-depot setup *regional-events partition-by-region)
```

## Topology Sourcing

### Stream

```clojure
(<<sources s
  (source> *depot :> *data)
  ;; process *data
  )
```

With options:

```clojure
(source> *depot {:start-from :beginning
                 :retry-mode :all-after} :> *data)
```

### Microbatch

```clojure
(<<sources mb
  (source> *depot :> %microbatch)
  (%microbatch :> *record)
  ;; process *record
  )
```

### Source Options

**Start-from** (first deployment only; resumes from checkpoint after):

| Option | Syntax |
|---|---|
| End (default) | `:end` or omit |
| Beginning | `:beginning` |
| Records back | `(offset-ago N :records)` |
| Days back | `(offset-ago N :days)` |
| After timestamp | `(offset-after-timestamp-millis ts)` |

**Retry modes** (stream only):

| Mode | Syntax | Guarantee |
|---|---|---|
| Individual (default) | `:individual` | At-least-once |
| All after | `:all-after` | At-least-once, local order preserved |
| None | `:none` | At-most-once |

### Multi-Source

A topology can consume the same depot multiple times using a `sourceId`:

```clojure
(source> *depot {:source-id "my-source"} :> *data)
```

Sources sync automatically.

### Appending from Topology Code

```clojure
;; Must partition first to target correct task
(|hash *target-key)
(depot-partition-append! *out-depot *value :append-ack)
```

For mirror depots, use mirror-aware partitioning:

```clojure
(|hash$$ $$mirror-pstate *key)
(depot-partition-append! *mirror-depot *value :ack)
```

## Client APIs (Clojure)

### Fetching Depot Client

```clojure
;; Production (cluster manager)
(def manager (open-cluster-manager {"conductor.host" "1.2.3.4"}))
(def depot (foreign-depot manager "com.company/Module" "*depot"))

;; Testing (InProcessCluster)
(def depot (foreign-depot ipc module-name "*depot"))
```

### Appending

```clojure
;; Blocking
(foreign-append! depot data)             ;; default :ack
(foreign-append! depot data :ack)
(foreign-append! depot data :append-ack)
(foreign-append! depot data nil)          ;; fire-and-forget

;; Non-blocking
(foreign-append-async! depot data)        ;; returns CompletableFuture
```

### Ack Levels

| Level | Waits for |
|---|---|
| `:ack` (default) | Persistence + replication + colocated stream topology processing |
| `:append-ack` | Persistence + replication only |
| `nil` | Nothing |

An exception does not guarantee the append failed (network partition
before ack response).

### Ack Returns

Stream topologies return values to appenders:

```clojure
;; In topology
(ack-return> *computed-value)

;; Client receives
(foreign-append! depot data)
;; => {"topology-name" computed-value}
```

Custom aggregation of ack returns:

```clojure
(source> *depot {:ack-return-agg aggs/+sum} :> *data)
```

### Metadata

```clojure
(foreign-object-info depot)
;; => {:module-name "..." :name "*depot" :num-partitions 32}

(foreign-depot-partition-info depot partition-idx)
;; => {:start-offset 100 :end-offset 1973000}
```

### Reading Ranges

```clojure
(foreign-depot-read depot partition-idx start-offset end-offset)
;; => [record1 record2 ...]
```

Rule of thumb: fetch at most ~50kb (~1000 records of ~50 bytes) per call.

Async variants: `foreign-depot-partition-info-async`,
`foreign-depot-read-async`.

## Depot Migrations

For depot migrations, see the **rama-depot-migration** skill.

## Depot Trimming

By default depots store all data permanently. Configure trimming via
dynamic options:

| Option | Effect |
|---|---|
| `depot.max.entries.per.partition` | Max records per partition (checked every 10 min) |
| `depot.excess.proportion` | Buffer beyond max to guard new ETLs |
| `depot.trim.coordinate.local.topologies` | Check local topology offsets before trim (default: true) |
| `depot.trim.coordinate.remote.topologies` | Check remote topology offsets before trim (default: true) |

ETLs that fall behind a trimmed offset skip ahead to next available.

## Tuning Options

### Stream Topology

| Option | Purpose |
|---|---|
| `depot.cache.cardinality` | In-memory cache size for retries |
| `depot.cache.catchup.chunk.size` | Records per fetch when catching up |
| `depot.max.pending.streaming.per.partition` | Max tracked acked appends |
| `depot.ack.failure.on.any.streaming.failure` | Whether first-attempt failure fails ack |
| `topology.stream.max.executing.per.task` | Max pending records per task |

### Microbatch Topology

| Option | Purpose |
|---|---|
| `depot.microbatch.max.records` | Max records per partition per microbatch |
| `depot.max.fetch` | Max records per fetch (≤ microbatch.max.records) |

### Replication

| Option | Purpose |
|---|---|
| `replication.depot.append.timeout.millis` | Timeout for replicating each append |

### Foreign (Client)

| Option | Purpose |
|---|---|
| `foreign.depot.flush.delay.millis` | Delay before flushing appends (0-50ms; enables batching) |
| `foreign.depot.operation.timeout.millis` | Timeout for foreign depot operations |
