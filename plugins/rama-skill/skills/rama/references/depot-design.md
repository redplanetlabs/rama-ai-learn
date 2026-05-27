# Rama Depot Design

Depots are append-only durable partitioned logs — the sole entry point for all
data into a Rama module. Design each depot along five axes: ownership,
scope, access, ordering, and mirror.

## Owner

The declaring module owns the depot. Only the owner defines its
partitioner and trimming policy (see `references/depot-migration.md` for
migrations). Colocated stream topologies on the owner module participate in ack coordination.

Depot boundaries — same depot vs separate:

- **Same depot** when events require local ordering on the same entity
  (e.g., Follow/Unfollow for one user). Use `<<subsource` to dispatch
  distinct event types cleanly.
- **Separate depots** when events have no ordering requirements between them and can be safely processed in different order than they were appended

## Scope

| Scope | Declaration | Behavior |
|---|---|---|
| Partitioned (default) | `(declare-depot setup *d (hash-by :k))` | Data distributed across N tasks |
| Global | `(declare-depot setup *d :random {:global? true})` | Single partition on task 0 |

Global depots: low-throughput only. Partitioner argument is irrelevant.

## Access

How data enters the depot:

| Access mode | Declaration | Appended by |
|---|---|---|
| Client append | `(hash-by :k)` or `:random` | External clients via `foreign-append!` |
| From topology | `:disallow` | `depot-partition-append!` from topology code |
| Tick | `(declare-tick-depot setup *t ms)` | Time-driven emission; no appends |
| External | `ExternalDepot` interface | Bridged system (e.g., Kafka) |

### Client ack levels

| Level | Waits for |
|---|---|
| `:ack` (default) | Persistence + replication + colocated stream processing |
| `:append-ack` | Persistence + replication only |
| `nil` | Nothing (fire-and-forget) |

Stream topologies return values to appenders via `(ack-return> *v)`.

### Topology-only (output) depots

For cross-topology data publishing. Reject client appends:

```clojure
(declare-depot setup *notifications :disallow)

;; In topology: partition then append
(|hash *target-key)
(depot-partition-append! *notifications *value :append-ack)
```

The last argument is the ack level:

- `nil`:Fire-and-forget, no guarantee of the append going through. Emits immediately.
- `:append-ack`: Emits after append to depot is successful
- `:ack`: Emits after append to depot is successful AND all colocated stream topologies have finished processing it. DO NOT use `:ack` if depot is consumed by same stream topology as is appending it, since that will cause a deadlock (the appending streaming batch is not complete since it's waiting for `depot-partition-append!` to finish, which is blocking another streaming batch from executing which would process it)

Note: `depot-partition-append!` within microbatch topologies does NOT
have exactly-once semantics on failure.

### Tick depots

Ticks emit on task 0. Fan out with `(|all)` if necessary:

```clojure
(declare-tick-depot setup *cleanup 60000)

(<<sources s
  (source> *cleanup :> _)
  (|all)
  (local-transform> [(nil->val 0) (term inc)] $$ticks))
```

### Topology sourcing

| Type | Source pattern | Guarantee |
|---|---|---|
| Stream | `(source> *depot :> *data)` | At-least-once or at-most once (configurable) |
| Microbatch | `(source> *depot :> %mb)` then `(%mb :> *data)` | Exactly-once PState updates |

Start-from options (first deployment only): `:end` (default),
`:beginning`, `(offset-ago N :records)`, `(offset-ago N :days)`.

```clojure
;; Start from the beginning (replay all data)
(source> *depot {:start-from :beginning} :> *data)

;; Start from end (new data only, default)
(source> *depot {:start-from :end} :> *data)

;; Start from 7 days ago
(source> *depot {:start-from (offset-ago 7 :days)} :> *data)

;; Start from 1000 records ago
(source> *depot {:start-from (offset-ago 1000 :records)} :> *data)
```

## Ordering

Ordering is determined by partitioning scheme and retry mode.

### Partitioning → local ordering

| Scheme | Syntax | Ordering |
|---|---|---|
| Hash by field | `(hash-by :user-id)` | Same key → same partition → local order |
| Hash by fn | `(hash-by extract-fn)` | Same extracted value → local order |
| Random | `:random` | No ordering guarantee |

`hash-by` function must be a keyword or top-level `defn` var (not `fn`
literal).

Custom partitioners via `defdepotpartitioner`:

```clojure
(defdepotpartitioner partition-by-region
  "Route by geographic region."
  [data num-partitions]
  (mod (hash (:region data)) num-partitions))
```

### Partition key selection

Match the partition key to the **primary PState access pattern**:

- Colocated key enables `local-transform>` / `local-select>` without
  repartitioning (zero network hops)
- Mismatched key forces `|hash` repartition (extra hop, breaks local
  ordering)
- Multiple PStates with different keys: partition by the most
  write-intensive key; repartition for secondary indexes

```clojure
;; Colocation: depot and PState share partition key
(declare-depot setup *events (hash-by :user-id))
(declare-pstate s $$profiles {String (fixed-keys-schema
                                       {:name String :email String})})
(<<sources s
  (source> *events :> {:keys [*user-id *name *email]})
  ;; Already on correct partition
  (local-transform> [(keypath *user-id)
                     (multi-path [:name (termval *name)]
                                 [:email (termval *email)])]
                    $$profiles))
```

### Stream retry modes

| Mode | Syntax | Guarantee |
|---|---|---|
| `:individual` (default) | `{:retry-mode :individual}` | At-least-once; retry failed records only |
| `:all-after` | `{:retry-mode :all-after}` | At-least-once; replay failed + all subsequent on partition |
| `:none` | `{:retry-mode :none}` | At-most-once; drop failed records |

`:all-after` for order-sensitive streams. `:none` for best-effort
analytics.

```clojure
;; Individual retry (default) — retry only the failed record
(source> *depot {:retry-mode :individual} :> *data)

;; All-after — replay failed record + all subsequent on that partition
(source> *depot {:retry-mode :all-after} :> *data)

;; None — drop failed records (at-most-once)
(source> *depot {:retry-mode :none} :> *data)
```

## Mirror

Cross-module depot reference. No local data storage; operations route
to the source module.

```clojure
(mirror-depot setup *ext-depot "com.company/OtherModule" "*depot")
```

### Appending to mirror depots

Requires explicit partition routing via `|hash$$` because number of tasks on other module can be different than appending module:

```clojure
(|hash$$ $$ext-pstate *key)
(depot-partition-append! *ext-depot *value :append-ack)
```

### Mirror ack semantics

Ack completes without waiting for the mirroring module's stream
topologies. `AckLevel.ACK` may return before the remote module
finishes processing.

## Design Checklist

- Each depot has a clear ownership boundary
- Partition key matches primary PState access pattern
- Ordering-sensitive events share a depot and partition key
- Ack level matches client consistency requirements
- Stream retry mode matches ordering/delivery needs
- Internal depots use `:disallow`
- Global depots only for genuinely low-throughput data
- Tick depots preferred over external schedulers

## Additional Resources

- **`references/depot-reference.md`** — Client APIs, trimming, tuning options, external depot interface, multi-source
- **`references/depot-migration.md`** — Depot record migrations
