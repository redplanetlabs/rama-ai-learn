# Rama PState Schema Design

PState schemas define composable nested data structures for durable,
partitioned indexes. Each PState partition stores one value whose shape
is determined by its schema.

## Schema Primitives (Clojure API)

| Form | Meaning |
|---|---|
| `{K V}` | Map literal — shorthand for `(map-schema K V)` |
| `(map-schema K V)` | Map with key type K, value schema V |
| `(set-schema V)` | Set of values of type V. V must be a class, not a nested schema |
| `(vector-schema V)` | Ordered list of values of type V |
| `(fixed-keys-schema {:k1 S1 :k2 S2})` | Fixed-key map; each key has its own schema. All keys are optional on write |
| `ClassName` | Leaf type (e.g. `Long`, `String`, `Object`) |

Schemas nest arbitrarily: values of maps/vectors/fixed-keys can be
other schemas. Set values are the exception — they must be classes.

Any PState location can be set to `nil`, regardless of its declared
type — schemas constrain non-nil values; `nil` is always permitted.

## Top-Level Constraint

Top-level schema must be one of:
- Map literal / `map-schema`
- `fixed-keys-schema`
- Class reference (e.g. `Long`)

Vectors and sets cannot be top-level. Use `java.util.ArrayList` or
`java.util.HashSet` if needed (elements won't be individually indexed).

## Storage Model

**First-class schemas** (top-level map and fixed-keys-schema) are backed by RocksDB — each key/field is individually addressable on disk. Reads and writes to individual keys are efficient O(1) operations without loading the entire structure.

**Class-reference schemas** (top-level `Long`, `String`, `Object`, etc.) are backed by a single value on disk. The entire value is read/written as one unit. These do not support subindexing.

**Writes are batched to disk** according to topology type: microbatch flushes at the end of each microbatch attempt, stream flushes at the end of each batch of streaming events executed together on a task. Individual `local-transform>` calls within a batch update an in-memory buffer; the disk write happens once at batch boundary.

**Read visibility:** PState reads from inside the owning topology see its uncommitted writes. Readers outside the owning topology — query topologies, foreign reads, other topologies — see only committed state.

**Nested structures** — maps, sets, and vectors nested inside a first-class schema — are stored as single serialized values by default. Without subindexing, the entire nested structure must be read from and written to disk even for a single-element operation.

## Subindexing

**Subindexing** indexes each element of a nested collection separately on disk via RocksDB, just like top-level keys. Subindexed structures can exceed available memory since elements are stored and retrieved individually. Benefits:
- O(1) point lookups into arbitrarily large nested structures
- Subindexed maps and sets are sorted — efficient range queries
  - Sorting is lexicographic based on the serialized form of the key
  - UUID7 keys sort in time order (see unique-ids.md)
- O(1) size queries (when size tracking is on)

**Critical constraint:** A subindexed structure is NOT a plain value — it is a handle to RocksDB storage. It can be used like a plain data structure, but it cannot be transferred over network boundaries. Queries must always navigate INTO it using element/range navigators (`ALL`, `MAP-VALS`, `MAP-KEYS`, `sorted-map-range`, `sorted-set-range-from-start`, etc.) to access individual elements, which are plain serializable values.

### When to Subindex

**Default to subindexing** nested data structures unless the application design makes it clear the collection will always be small (< 50 elements). The overhead of subindexing on small collections is minimal, but failing to subindex a collection that grows large causes serious performance problems — every read/write loads the entire serialized collection from disk.

A location must be subindexed if ANY instance of that location can grow large. Even if most instances are small, a single popular entity with a large collection will cause performance problems if that collection is not subindexed. Do NOT reason about "typical" or "average" sizes — reason about the worst case.

Specifically:
- **Always subindex**: user-generated collections (followers, posts, messages, history), anything that grows with usage or popularity
- **Don't subindex**: fixed-size structures (status enums, small config maps, coordinates), collections with a known small upper bound enforced by the application (e.g., roles per user capped at 5). "Bounded in practice" or "typically small" is NOT a known upper bound — only explicit application-enforced limits count. This applies even if there are subindexed structures nested deeper inside — subindexing is independent at each level. Subindexing a small collection adds an extra RocksDB read per navigation through it — unnecessary overhead when the entire collection fits in a single serialized value.

### Declaration

```clojure
;; Subindexed set inside a map
(declare-pstate s $$p {String (set-schema Long {:subindex? true})})

;; Subindexed map inside a map
(declare-pstate s $$p {Long (map-schema String String {:subindex? true})})

;; Subindexed vector
(declare-pstate s $$p {Long (vector-schema String {:subindex? true})})

;; Subindexing is independent at each nesting level — an inner map can be
;; subindexed even if the outer map is not, and vice versa. Subindexed
;; structures can be nested anywhere: inside other subindexed structures,
;; inside non-subindexed structures, and inside fixed-keys-schema.
(declare-pstate s $$p {Long (map-schema Long
                            (set-schema Long {:subindex? true})
                            {:subindex? true})})

;; Complex nesting: subindexed and non-subindexed mixed freely
(declare-pstate s $$p {String (fixed-keys-schema
                        {:f1 String
                         :f2 (set-schema String {:subindex? true})
                         :f3 (map-schema Long
                               (fixed-keys-schema
                                 {:a String
                                  :b (set-schema String {:subindex? true})})
                               {:subindex? true})})})
```

### Size Tracking

On by default. Adds an extra disk read on every write to maintain the
count. Disable for write-heavy paths that never query size:

```clojure
(declare-pstate s $$p
  {String (set-schema Long {:subindex-options {:track-size? false}})})
```

With size tracking on, `(view count)` is O(1).
Without it, O(n).

### Sorted Range Queries

Use range navigators for efficient disk-level iteration (single
disk seek per range):

```clojure
(sorted-map-range :a :z)                          ;; start inclusive, end exclusive
(sorted-map-range :a :z {:inclusive-start? false}) ;; exclusive start
(sorted-map-range-from :k 10)                     ;; from key, first N entries scanning forward
(sorted-map-range-to :k 10)                       ;; up to key (exclusive), last N entries scanning backward
;; Set variants: sorted-set-range, sorted-set-range-from, sorted-set-range-to
```

### Deleting Subindexed Structures

Delete a subindexed structure directly (e.g. `(keypath "a") NONE>`)
for proper cleanup. Deleting a **parent** of a subindexed structure
leaves orphaned elements on disk.

## Fixed-Keys Schema

Use for structured records with known fields:

```clojure
(declare-pstate s $$users
  {String (fixed-keys-schema
            {:age Long
             :location String
             :scores (set-schema Long {:subindex? true})})})
```

Fixed-keys schemas cannot themselves be subindexed, but their values
can contain subindexed structures. All keys are optional — writes
only need to include the keys being set.

## PState Options

```clojure
(declare-pstate s $$p Long {:global? true})        ;; single partition on task 0
(declare-pstate s $$p Long {:initial-value 0})      ;; starting value per partition
(declare-pstate s $$p {String Long} {:private? true}) ;; no external reads
(declare-pstate s $$p {String Long} {:key-partitioner my-fn}) ;; custom routing
```

- `:global?` — single-partition state on task 0 (counts, top-N)
- `:initial-value` — only for class-reference top-level schemas
- `:private?` — topology-internal only; throws on foreign access
- `:key-partitioner` — `(fn [num-partitions key] partition-idx)`

## Schema Validation Options

Two validation settings can be disabled in production for performance:

- `pstate.validate.subindexed.structure.locations` (default true) —
  prevents moving subindexed structures between locations via disk
  read validation
- `pstate.maximal.schema.validations` (default true) — iterates
  through large non-subindexed values for complete schema checking

Keep both enabled during development.

## Operational Config

| Option | Default | Purpose |
|---|---|---|
| `pstate.rocksdb.options.builder` | 2-level index, 256MB cache | Custom RocksDB config class |
| `foreign.pstate.operation.timeout.millis` | — | Timeout for foreign PState queries |
| `foreign.proxy.thread.pool.size` | — | Thread pool for `ProxyState` callbacks |
| `foreign.proxy.failure.window.seconds` | — | Window for proxy failure counting |
| `foreign.proxy.failure.threshold` | — | Failure count to force proxy termination |
| `pstate.excessive.write.time.warning.millis` | — | Log slow `local-transform>` calls |
| `pstate.reactivity.queue.limit` | — | Limit queued PState change subscribers |
| `pstate.yielding.select.page.size` | — | Page size for `.allowYield()` queries |

Set via `set-launch-pstate-dynamic-option!` or cluster config.

## Implicit Schema Widening

Schema changes that generalize types need no explicit migration:
- `Long` → `Object` (widening)
- `Map` ↔ `(map-schema Object Object)` (structuring/destructuring)

Narrowing or conflicting type changes (e.g. `String` keys → `Long`
keys) require explicit migration. See the **rama-pstate-migration**
skill.

## Serializable Types

PState values must be serializable. Built-in support for:
- Primitives: `int`, `long`, `float`, `double`, `String`, `boolean`
- `java.util` collections, Clojure persistent data structures
- `defrecord` types

Custom types need `RamaCustomSerialization` or Nippy
`extend-freeze`/`extend-thaw`.

## Mirror PState

Read-only cross-module reference to another module's PState:

```clojure
(mirror-pstate s $$p "com.mycompany.OtherModule" "$$p")
```

Four args: setup handle, local PState symbol, source module name (string),
source PState name (string). Queries route to the source module's partitions.

## Cross-References

- Clojure API cheatsheet: see `references/clojure-api.md`
- Querying PStates: see the **rama-paths** skill
- Testing schemas: see the **rama-testing** skill
- Schema migrations: see the **rama-pstate-migration** skill

## Storing Polymorphic Data

When a PState position needs to hold different types of values with different fields, do NOT use `Object` and do NOT use a single `fixed-keys-schema` with all possible fields — this makes schema enforcement weak because any variant can be stored with any combination of fields, and there is no validation that the right fields are set for each type.

Instead, define a Java interface as the common type and implement it with `defrecord` for each variant. Each record stores only the fields it needs and enforces the correct fields per type at construction time. Rama serializes `defrecord` types automatically. If consumers need to handle all variants uniformly, add methods to the interface that each record implements. If plain maps are needed, write conversion code.

```clojure
(definterface IEvent)
(defrecord TypeA [x y] IEvent)
(defrecord TypeB [x] IEvent)

(declare-pstate s $$pstate {Long IEvent})

;; Write specific record types
(local-transform> [(keypath *id) (termval (->TypeA *x *y))] $$pstate)

;; Read returns the actual record instance
(foreign-select (keypath id) events)
```

## Design Checklist

1. What queries does this PState serve? (drives structure choice)
2. Top-level key = partitioning key? (if not, use `:key-partitioner`
   or explicit `:pkey` on client queries)
3. Which nested collections can grow large? (subindex those)
4. Need size of subindexed structures? (keep size tracking on)
5. Need sorted iteration? (subindexed maps/sets are sorted)
6. Is this colocated with related PStates? (same partitioning key)
7. Single-partition or partitioned? (`:global?` for low-throughput)

## Schema Well-formedness

```text
jvm-class     ::= String | Long | Integer | Double | Boolean | Object
                | clojure.lang.Keyword | java.util.UUID | ...
                -- must be a resolvable JVM class symbol
                -- NOT: bare Keyword (unresolvable), {} (empty map literal), nil
```

Schema type rules:
- Schema entries must be resolvable JVM class symbols. `Keyword` → use `clojure.lang.Keyword`. `{}` → use `Object`.
- Narrow schemas (e.g. `{String String}`) reject mismatched writes. Use `Object` for heterogeneous values.

## `:initial-value` Rules

```text
:initial-value availability by schema type:

value-schema       ✗  (no options map accepted; Syntax error macroexpanding)
{K V}  (map)       ✗  (Top-level maps cannot have an init value)
vector-schema      ✓
set-schema         ✓
fixed-keys-schema  ✓  (per-key defaults via schema)
```

## PState Nil Semantics

**`keypath` on absent key navigates to nil (multiplicity 1).** This is distinct from navigators that produce 0 values (`must`, `FIRST`, `set-elem` on absent targets).

```text
local-select>([(keypath k)] $$p)  where k absent  →  nil     (keypath navigates to nil)
local-select>(STAY $$p)           where $$p is value-schema, never written  →  nil
local-select>([(must k)] $$p)     where k absent  →  0 emits (branch dies)
foreign-select-one([(keypath k)] p)  where k absent  →  nil  (keypath navigates to nil)
```

**`foreign-select-one` contract**: path must navigate to exactly one value. Throws if path produces 0 or >1 values. Safe with `keypath` (always multiplicity 1); unsafe with `must`, `ALL`, `FIRST` on potentially empty data.

Nil-handling patterns (from most to least preferred):

```text
Pattern                          Where           Example
──────────────────────────────────────────────────────────────────────
nil->val in path                 topology write  (nil->val 0) before (term inc)
or> after local-select>          topology read   (or> *v default :> *v)
(or result default)              client read     (or (foreign-select-one ...) default)
```

Nil-safe counter increment: `(local-transform> [(keypath *k) (nil->val 0) (term inc)] $$counts)`.

## Navigator Arity Constraints

```text
(term f)        -- f is a unary transform function; exactly one argument
(termval v)     -- v is a value-expr; NOT a navigator
```

`term` takes exactly one argument — a Clojure function or Rama function. To close over dataflow variables, use `<<ramafn` or `partial`:
```clojure
;; Option 1: <<ramafn (preferred — closes over *amount)
(<<ramafn %add [*cur] (:> (+ *cur *amount)))
(local-transform> [(keypath *id) :qty (nil->val 0) (term %add)] $$p)

;; Option 2: partial
(partial + *amount :> *add-fn)
(local-transform> [(keypath *id) :qty (nil->val 0) (term *add-fn)] $$p)
```

## References

- [Declaring PStates (module guide)](https://redplanetlabs.com/docs/~/clj-defining-modules.html#_declaring_pstates)
- [PState internals](https://redplanetlabs.com/docs/~/pstates.html#_declaring_pstates)
- [Clojure API docs](https://redplanetlabs.com/clojuredoc/index.html)
