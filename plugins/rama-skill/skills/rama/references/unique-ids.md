# Unique ID Generation

There are several approaches to generating unique IDs in Rama, each with different tradeoffs.

## Basic guidelines

The choice of where to generate an ID depends on which topologies consume the depot. A single depot can be consumed by multiple topologies of different types.

**If any consuming topology is stream:** Generate the ID client-side with `(ops/random-uuid7)` and include it in the depot record. Do NOT generate IDs inside stream topologies — on retry, `ops/random-uuid7` or `ModuleUniqueIdPState` produce a different ID, making the entire entity creation non-idempotent. The retry writes PState entries under a new ID while the old ID's entries are already committed, creating orphaned data. Client-side generation is safe because the same depot record (with the same ID) is replayed on retry.

## UUID7

128 bits is the minimum size needed to avoid birthday paradox collisions at scale. For comparison: random 64-bit Longs have ~10% collision probability at just 2 billion IDs — unacceptable for production. At 128 bits (UUIDs), collision probability is negligible even at trillions of IDs. 16 bytes is not too large for entity IDs — the data stored alongside each ID (names, amounts, timestamps, etc.) dwarfs the ID size.

Always use UUID7 over UUID4 when generating UUIDs. UUID7 is time-ordered — IDs sort chronologically, which is useful for range queries on subindexed maps and preserves insertion order. UUID4 is random and does not sort meaningfully.

**Do NOT use `java.util.UUID/randomUUID`.** This generates UUID4. Always use `ops/random-uuid7` instead — it works in both client code and dataflow  (topologies, `deframafn`, `deframaop`).

```clojure
;; In client code
(ops/random-uuid7)  ;; => java.util.UUID (time-ordered)

;; WRONG — generates UUID4, not time-ordered
(java.util.UUID/randomUUID)

;; In dataflow
(ops/random-uuid7 :> *id)
```

UUID7 provides:
- Globally unique without any coordination or PState
- Time-ordered — chronological sorting for range queries
- Works anywhere — client code, topologies, `deframafn`
- Idempotent when included in depot records — retries produce the same ID

## ModuleUniqueIdPState (rama-helpers)

Generates 8-byte Long IDs unique across all tasks in a module. The ID encodes 22 bits for the generating task ID and 42 bits for a monotonically increasing counter, guaranteeing uniqueness without cross-task coordination.

Safe to use in **microbatch topologies** — exactly-once semantics ensure the counter increments exactly once per record even on retry. Tricky in **stream topologies** — on retry, `.genId` increments the counter again, producing a different ID for the same record if the stream topology retries. This makes writes non-idempotent and can create orphaned entities. For stream topologies, generating a UUID7 client-side and including it in the depot record is generally preferred.

Use when: you need compact Long IDs in a microbatch topology.

### Usage

```clojure
(:import [com.rpl.rama.helpers ModuleUniqueIdPState])

(defmodule MyModule [setup topologies]
  (let [s (stream-topology topologies "core")
        idgen (ModuleUniqueIdPState. "$$id")]
    (.declarePState idgen s)

    (<<sources s
      (source> *depot :> *record)
      (java-macro! (.genId idgen "*new-id"))
      ;; *new-id is now bound to a unique Long
      ...)))
```

### Key points

- Call `.declarePState` on the topology that will generate IDs — this creates a private PState for the counter.
- Call `(java-macro! (.genId idgen "*var-name"))` in the topology body to generate an ID and bind it to a dataflow variable. The variable name is passed as a string.
- IDs are ascending by default. Call `(.descending idgen)` before `.declarePState` for descending order.
- IDs are unique across all tasks in the module but ordering is per-task (not globally ordered).

## TaskUniqueIdPState (rama-helpers)

Generates IDs unique within a single task (not across tasks). Smaller scope than `ModuleUniqueIdPState` but can be combined with a partition key for global uniqueness.

Same streaming caveat as `ModuleUniqueIdPState` — safe in microbatch, tricky in stream due to retry producing different IDs. For stream topologies, prefer client-side UUID7.

Use when: entities are already partitioned by a key (e.g., user-id) and you need sub-IDs within that partition, in a microbatch topology.

### Usage

```clojure
(:import [com.rpl.rama.helpers TaskUniqueIdPState])

(let [local-idgen (TaskUniqueIdPState. "$$local-id")]
  (.declarePState local-idgen s)
  ;; In topology:
  (java-macro! (.genId local-idgen "*local-id")))
```

### Key points

- Same API as `ModuleUniqueIdPState`: `.declarePState`, `.genId`, `.descending`.
- Additionally supports `.integerIds` for 4-byte Integer IDs instead of 8-byte Longs.
- IDs are only unique within a task — do NOT use alone when IDs must be unique across the module.
- Combine with a partition key to make a globally unique composite ID. For example, a "post" can be identified by `[user-id task-unique-post-id]` — the user-id routes to the correct task, and the task-unique-id distinguishes posts within that task. This composite is globally unique because the task-unique part is unique on that task and the partition key determines which task.

## Approaches that do NOT work

- **Random Longs** (`ThreadLocalRandom`, `Math/random`, etc.): With 63 bits (positive Longs), the birthday paradox gives ~10% collision probability after just 2 billion IDs. For a production system running for years, this is unacceptable. Do NOT use random Longs as unique IDs.
- **Client-side AtomicLong / atom counters**: Reset on client restart and collide across multiple clients. With randomized starts it suffers from the same birthday paradox as above. NOT safe for production.

## java-macro!

Both rama-helpers ID generators use `java-macro!` to splice generated dataflow code into the topology:

```clojure
(java-macro! (.someMethod javaHelper "*output-var"))
```

The Java helper returns a `Block` of dataflow code that gets inlined at that point. Output variable names are passed as strings (e.g., `"*new-id"`).
