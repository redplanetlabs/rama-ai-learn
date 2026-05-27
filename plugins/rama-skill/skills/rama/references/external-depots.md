# ExternalDepot Reference

An `ExternalDepot` adapts an external partitioned log (e.g. Kafka, Kinesis, custom queue) into the Rama depot abstraction. Topologies consume it identically to native depots — same `source>`, same start-from options, same retry semantics.

## Formal model

```text
ExternalDepot ∈ Module
Owner(ExternalDepot) = Mod M

ExternalDepot : Depot α M V
  where α = record type produced by fetchFrom
        V = Public (default) | Disallow

-- Consumed via source> like any depot:
Γ; o; M; stream ⊢ source>(*ext-depot, opts, :> *x) : τ ! {Tick, Route¹ π}
Γ; o; M; microbatch ⊢ source>(*ext-depot, opts, :> %mb) : Batch τ ! {Tick}

-- Partition model:
getNumPartitions() → N
∀ partition ∈ {0..N-1}:
  startOffset(partition) → Long           -- earliest available
  endOffset(partition) → Long             -- next offset to be appended
  fetchFrom(partition, startOffset) → [[offset, key, data]]       -- streaming
  fetchFrom(partition, startOffset, endOffset) → [[offset, key, data]]  -- microbatch (exact range)
```

Rama maps external partitions to tasks. Partition indexes must consistently reference the same data partition.

## Declaration

```clojure
;; Declare as a task global — ExternalDepot extends TaskGlobalObject
(declare-object setup *ext-depot (MyExternalDepot. config))
```

First argument is always `setup`. The object must implement `com.rpl.rama.integration.ExternalDepot`.

## Syntax (EBNF)

```ebnf
external-depot-decl = '(declare-object' 'setup' var expr ')' ;
```

## Java interface: `com.rpl.rama.integration.ExternalDepot`

`ExternalDepot` extends `TaskGlobalObject`, so implementations must also provide `prepareForTask` and `close`. All depot methods return `CompletableFuture` (non-blocking contract).

| Method | Required | Description |
|---|---|---|
| `getNumPartitions()` | yes | Total partition count. Rama polls every ~30s to detect scaling. Returns `CompletableFuture<Integer>`. |
| `endOffset(int partition)` | yes | Next offset to be appended on this partition |
| `startOffset(int partition)` | no | Earliest available offset (default: 0) |
| `fetchFrom(int partition, long startOffset, long endOffset)` | yes | Fetch exact range `[startOffset, endOffset)` for microbatch |
| `fetchFrom(int partition, long startOffset)` | yes | Fetch available records from offset for streaming; called only when downstream has capacity (backpressure) |
| `offsetAfterTimestampMillis(int partition, long millis)` | no | Offset at or after timestamp; enables `offset-after-timestamp-millis` start-from |

### Record format

Each `fetchFrom` method returns `CompletableFuture<List>` where the list contains records. Each record is a 3-element Java list: `[offset, key, data]`.

- Index 0: offset (`Long`) — used by Rama for checkpointing
- Index 1: key (`Object`, may be `nil`) — the record key (e.g. Kafka message key)
- Index 2: data (`Object`) — the record payload

Use `java.util.Arrays/asList` to construct records:

```clojure
(java.util.Arrays/asList (into-array Object [(long offset) nil data]))
```

In topology code, the raw 3-element list is emitted by `source>`. Extract the data with `nth`:

```clojure
(source> *ext-depot :> %batch)
(%batch :> *record)
(nth *record 2 :> *data)    ;; extract payload from [offset, key, data]
```

## Consumption

Consumed via `source>` exactly like native depots:

```clojure
;; Stream
(<<sources s
  (source> *ext-depot {:start-from :beginning} :> *record)
  ...)

;; Microbatch
(<<sources mb
  (source> *ext-depot :> %batch)
  (%batch :> *record)
  ...)
```

All `source>` options apply: `:start-from`, `:retry-mode`, `:source-id`, `:ack-return-agg`.

## Kafka integration via `rama-kafka`

```clojure
(:import [com.rpl.rama.kafka KafkaExternalDepot])

(declare-object setup *kafka-depot
  (-> (KafkaExternalDepot. {"bootstrap.servers" "localhost:9092"} "my-topic")
      (.deserializer (MyDeserializer.))))
```

The Kafka class is `com.rpl.rama.kafka.KafkaExternalDepot` (in the `rama-kafka` artifact).

## Backpressure

Rama calls `fetchFrom` for streaming only when consuming topologies have available capacity. This automatically throttles the external source when stream topologies fall behind.

## Partition scaling

`getNumPartitions()` is polled every ~30 seconds. If the external source adds partitions, Rama detects and begins consuming them. Partition indexes must remain stable — index `i` must always refer to the same logical partition.

## Exactly-once with external sinks

For microbatch topologies writing to external systems, use `ops/current-microbatch-id` to version writes. Store `{key → {microbatch-id, current-value, previous-value}}` in the external system and reconcile on retry.

## Usage hints

- **Same as native** — all depot features work: start-from, retry modes, multi-source, ack semantics.
- **Non-blocking** — interface methods must return `CompletableFuture`. Blocking calls stall the Rama worker thread.
- **Offset tracking** — Rama checkpoints progress per partition. On restart, consumption resumes from the last checkpoint.
- **No client appends** — external depots are read-only from Rama's perspective. Data is appended by the external system.
- **Partition stability** — partition index → data mapping must not change. Rebalancing partitions breaks offset tracking.
- **Serializable** — like all task globals, the object is serialized to each task. Use serializable field types (primitives, arrays, Strings). Avoid `AtomicInteger` and similar.
- **Testing** — in `InProcessCluster`, external depots work normally. For simple tests, back with a global atom and a `deftype` implementing `ExternalDepot`. Mock the external source or use an embedded instance (e.g. embedded Kafka).
