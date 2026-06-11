# Rama Aggregators

Aggregators auto-initialize missing values, unlike paths which require
explicit null handling. Within `+compound`, missing nested keys are
auto-initialized. Top-level PState aggregators (outside `+compound`)
require the PState value to already exist.

In batch blocks and query topologies, aggregators always fire and bind
their output even when zero rows reach them: the output is the
aggregator's zero value (`0` for `+sum`, `[]` for `+vec-agg`, `#{}` for
`+set-agg`, `nil` for `+last`, etc.). Do NOT add special-case branches
for "empty input" — the zero value flows through normally. See batch.md
"Zero rows — aggregators still fire".

## Formalism

Universe
- E  = emitted records
- L  = aggregation locations (top-level or nested key-path)
- S  = aggregate state
- PS = map L -> S (logical PState view)

Selectors
- loc  : E -> L
- args : E -> A...     ;; accumulator inputs
- lift : E -> S        ;; combiner contribution

### 1) Accumulator

- Acc = {:init init-fn :step step-fn}
- init-fn : () -> S
- step-fn : (S, A...) -> S

Per-event: l = (loc e), curr = (get PS l (init-fn)),
PS' = (assoc PS l (step-fn curr (args e)))

Batch: eval-acc(xs) = (reduce (fn [s e] (step-fn s (args e))) (init-fn) xs)

Laws:
- Order-dependence: result depends on emission sequence, not set.
  step-fn need not be commutative or associative.
- init-fn is pure (deterministic, side-effect-free).

Sequential only — outputs cannot feed back to the same aggregator.
Prevents two-phase optimization.

### 2) Combiner

- Comb = {:zero zero-fn :combine op :lift lift}
- zero-fn : () -> S
- op      : (S, S) -> S

Per-event: l = (loc e), curr = (get PS l (zero-fn)),
PS' = (assoc PS l (op curr (lift e)))

Laws (required for two-phase):
- Closure: ∀ a,b ∈ S → (op a b) ∈ S
- (op z s) = s, (op s z) = s                          ;; identity
- (op (op a b) c) = (op a (op b c))                   ;; associativity
- if merge order is nondeterministic: (op a b) = (op b a) ;; commutativity
- zero-fn is pure (deterministic, side-effect-free).

Two-phase law:
(reduce op z (map lift xs)) = (reduce op z (map #(reduce op z (map lift %)) partitions))

### 3) +compound

Spec grammar: Spec := Leaf(Agg) | Map(key-expr, Spec) | Tuple(Spec...)

Semantics: recursively derive sublocation(s) from event and Spec shape.
Leaf aggregator applied at each leaf. Map nodes aggregate per key.
Tuple/List nodes aggregate component-wise.

Compositionality: each leaf aggregates independently; tree structure
distributes over aggregation.

### 4) +group-by

- key : E -> K, result : K -> S_i (one per agg i)
- auto hash-partitions by key, runs each agg per key-group
- emits one output row per key
- max 6 grouping vars; batch/query-topology only

### 5) :new-val>

Given PS_before, PS_after in a batch:
Delta = { l | PS_before[l] != PS_after[l] }

Post-agg emits each changed key-path once with new aggregate value(s).
Key vars from pre-agg are rebound in post-agg.

Constraints:
- `<<batch` only
- within `+compound` leaves, no output stream besides `:new-val>`
- no branching into multiple maps within the `+compound` template
- cannot use multiple keys in a single map level

### 6) Special aggregators

+top-monotonic: bounded top-N using combiner infrastructure.
Exact in batch/query finalization; in stream/microbatch, exactness
requires monotonic (strictly ascending/descending) score movement per id.
Sort values may be any Comparable (numbers, strings, UUIDs, vectors, ...).
Buffer grows to 2×N before sort-and-prune (O(N log N)).
Non-batched contexts skip final sort — client must sort.

+limit: batch-only keep-N relation reducer.
Without `:sort`, keeps first N encountered (undefined order globally).
Accepts multiple vars; selected vars rebound in post-agg.
Sort var need not be a selected var.
Uses internal combiner for efficient global aggregation.
Emits selected rows for post-agg flow.

### 7) Two-phase optimization

In `<<batch`, when ALL aggregators are combiners, Rama applies two-phase:
1. Partial aggregation before the final partitioner (pre-agg side)
2. Reduced data across partitioner
3. Final aggregation on post-agg task

Critical for global aggregation performance.

`:flush-required?` — set true on combiners whose state grows unbounded
(e.g., maps with increasing keys). Controls partial result flushing.
When all combiners are non-flush-required, flush at pre-agg completion.
Otherwise tunable via `topology.combiner.limit` dynamic option.

### 8) Temporary PStates

In `<<batch`: unnamed temporary PStates scoped to single microbatch,
initialized empty each time. Enables result sharing across sequential
batch blocks. Later blocks wait for prior completion.

### 9) Topology context differences

| Aspect              | Batched (<<batch)                      | Stream (non-batched)          |
|---------------------|----------------------------------------|-------------------------------|
| Two-phase           | Yes, when all aggs are combiners       | No (sequential only)          |
| +top-monotonic      | Final sort applied                     | No final sort (client sorts)  |
| +limit              | Supported                              | Not supported                 |
| :new-val>           | Supported                              | Not supported                 |
| Temporary PStates   | Supported                              | Not supported                 |

`<<batch` occurs in microbatch and query topologies.
Query topologies support batch output forms (`:> *out`) for aggregated
results without PState writes.

## Clojure Syntax Mapping

### Namespaces

- Core: `com.rpl.rama` (`accumulator`, `combiner`, `+compound`, `+group-by`)
- Built-ins: `com.rpl.rama.aggs`
- Introspection: `com.rpl.rama.ops` (`agg->init-fn`, `agg->update-fn`)

```clojure
(require '[com.rpl.rama :refer :all])
(require '[com.rpl.rama.aggs :as aggs])
```

### Built-in aggregators

Combiners (support two-phase):
- `+sum` — numeric sum
- `+count` — increments by 1 per event. Takes trigger var in PState/batch forms; zero-arity in `+compound` leaf.
- `+max` — maximum value (nil if no input)
- `+min` — minimum value (nil if no input)
- `+and` — logical AND. Last true value if all true, first false/nil if any. Init: `true`.
- `+or` — logical OR. First true value if any true, last false/nil if all. Init: `false`.
- `+first` — first value seen (requires ordered merge for two-phase correctness)
- `+last` — last value seen (requires ordered merge for two-phase correctness)
- `+set-agg` — collects into set
- `+set-remove-agg` — removes element from set
- `+multi-set-agg` — value→count map
- `+map-agg` — key/value pairs into map (2 args: `*k *v`)
- `+vec-agg` — collects into vector
- `+merge` — merges maps (like `clojure.core/merge`)

Accumulator-only:
- `+avg` — average. Batch output only; cannot aggregate into existing PState.

Special:
- `+top-monotonic` — bounded top-N (see formalism §6)
- `+limit` — batch-only keep-N (see formalism §6)
- `+NONE` — removes element at position in `+compound` (analogous to `NONE>` navigator)

### Callsite forms

Two forms for most built-ins:
- PState update: `(aggs/+sum $$p *v)`, `(aggs/+map-agg $$p *k *v)`
- Batch output: `(aggs/+sum *v :> *sum)`, `(aggs/+map-agg *k *v :> *map)`

### Custom accumulator

```clojure
(def +my-acc
  (accumulator
    (fn [*v] (term (fn [*curr] (+ *curr *v))))
    :init-fn (fn [] 0)))
```
- PState update: `(+my-acc $$p *v)`
- Batch output: `(+my-acc *v :> *out)`

### Custom combiner

```clojure
(def +my-comb
  (combiner + :init-fn (fn [] 0) :flush-required? false))
```
- `:flush-required?` — true when aggregate state grows unbounded (e.g., maps)

### +compound syntax

Shape mapping:
  - `Leaf(Agg)` → `(aggs/+count)` / `(aggs/+sum *v)` / custom agg call
  - `Map(key-expr, Spec)` → `{*k spec}` (key: var, var tuple, or constant)
  - `Tuple(Spec...)` → `[spec1 spec2 ...]`

Invocation:
  - PState update: `(+compound $$p template)`
  - Batch output: `(+compound template :> *out)`

```clojure
(+compound $$p
  {*k {:a [(aggs/+sum *v) (aggs/+count)]
       :b (aggs/+max *v2)}})
```

### +group-by syntax

```clojure
(+group-by *k
  (aggs/+count :> *count)
  (aggs/+sum *v :> *sum))

;; multi-key
(+group-by [*k1 *k2]
  (aggs/+max *v :> *max-val))
```

### :new-val> syntax

Leaf-level declaration inside `+compound`:
```clojure
(+compound $$p
  {*k {:a (aggs/+sum *v :new-val> *new-sum)}})
```

### +top-monotonic syntax

```clojure
;; batch output
(aggs/+top-monotonic [n] *obj :> *top
  :+options {:id-fn first :sort-val-fn second :sort-type :descending})

;; PState update
(aggs/+top-monotonic [n] $$top *obj
  :+options {:id-fn first :sort-val-fn second})
```
`:sort-type` — `:ascending` or `:descending`.

### +limit syntax (batch only)

```clojure
(aggs/+limit [n] *v1 *v2
  :+options {:sort *v1 :reverse? true :index-var *i})
```
Selected vars (`*v1`, `*v2`) rebound in post-agg phase.

### Temporary PState syntax

```clojure
(+compound {*k (aggs/+count)} :> $$temp-count)
```

## Tips

- `+top-monotonic`: prefer a fixed literal bound (`[1000]`) rather than a branch-derived logvar for `[n]`. Dynamic size vars can fail with `Attach point missing needed logvar`.
- `+top-monotonic` in stream/microbatch: treat output order as not-finalized; apply explicit sort in client/query code. Defensively dedupe by entity ID on read.
- `:flush-required? true` for combiners with unbounded growth (e.g. set union) — signals Rama to flush during batch processing.

## Sources

- https://redplanetlabs.com/clojuredoc/index.html
- https://redplanetlabs.com/docs/~/aggregators.html
