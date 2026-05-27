# Query Topologies

A query topology is a distributed, on-demand, read-only function. It accepts input arguments, performs computation across tasks, and returns a single result. Query topologies are batch blocks — read `batch.md` before writing any query topology. You must understand batch block semantics (pre-agg/agg/post-agg phases, how aggregators work, joins) to write correct query topologies.

## How query topologies work

A query topology is a batch block that:
1. **Pre-agg**: reads from PStates, partitions to different tasks, explodes data — produces rows
2. **Agg**: aggregates all rows into a result using combiners/aggregators
3. **Post-agg**: post-processes the aggregated result

The final pre-agg partitioner must be `|origin`, which routes computation back to the calling task. The output variable must be emitted exactly once. No partitioners can be used in the post-agg phase.

## Choosing pre-agg only vs. aggregation

A query topology always produces one result. The question is how that result is computed.

**Pre-agg only (no aggregator):** Every read contributes meaningful data to the result for all possible inputs. A read that navigates to zero entries, an empty collection, or an empty submap is NOT meaningful — it is a wasted disk seek that consumes resources and reduces module throughput under load. If any read would produce empty results for some inputs, the reads are variable — see "with aggregation" below. Multiple sequential `local-select>` calls each bind their own variable — they do NOT cross-product. Combine them with plain operations before `|origin`.

**With aggregation:** The number of meaningful reads changes based on the input. If some inputs need fewer reads than others, the count is variable and the query MUST handle it dynamically. Do NOT pad with reads that produce empty results — even if latency is acceptable for a single query, wasted reads consume disk and CPU resources that reduce the module's throughput under load. Use `ops/explode`, `loop<-`, or other dynamic approaches to produce only the reads needed for each input, then aggregate the results.



## Core patterns

### Pre-agg only: fixed reads

```clojure
(<<query-topology topologies "my-query" [*id :> *result]
  (|hash *id)
  (local-select> [(keypath *id)] $$p1 :> *v1)
  (local-select> [(keypath *id)] $$p2 :> *v2)
  (+ *v1 *v2 :> *result)
  (|origin))
```

### Aggregation: variable number of reads

```clojure
(<<query-topology topologies "aggregate-range" [*id *n :> *total]
  (|hash *id)
  (ops/range> 0 *n :> *i)
  (local-select> (keypath *id *i) $$stats :> *stat)
  (|origin)
  ;; Aggregate all emitted values into single result
  (+combine-stats *stat :> *total))
```

`ops/range>` emits all values up to `*n`. `local-select>` emits per navigated value. The aggregator reduces everything to one result. The number of segments varies per query — the dataflow handles it.

## More examples

### Simple point lookup (no aggregation needed)

```clojure
(<<query-topology topologies "my-query" [*id :> *result]
  (|hash *user-id)
  (local-select> [(keypath *id) (nil->val 0)] $$p1 :> *v1)
  (local-select> [(keypath *id) (nil->val 0)] $$p2 :> *v2)
  (|origin)
  (+ *v1 *v2 :> *result) ;; this line can be either here or before the |origin
  )
```

Pre-agg only — no aggregator needed when the query produces exactly one value.

### Gather from all partitions

```clojure
(<<query-topology topologies "get-all" [:> *result]
  (|all)
  (local-select> STAY $$my-pstate :> *partition-data)
  (|origin)
  (aggs/+merge *partition-data :> *agg)
  (my-post-process-fn *agg :> *result))
```

`|all` sends computation to every task. Each task emits its partition data. `aggs/+merge` combines all partitions at origin with Clojure's `merge` function. `my-post-process-fn` shows how you can run arbitrary code after the aggregators (the "post-agg" phase);

### Cross-partition join

```clojure
(<<query-topology topologies "user-with-friends" [*user-id :> *friend-list]
  (|hash *user-id)
  (local-select> [(keypath *user-id) ALL] $$friends :> *friend-id)
  (|hash *friend-id)
  (local-select> (keypath *friend-id) $$profiles :> *friend-profile)
  (|origin)
  (aggs/+vec-agg *friend-profile :> *friend-list))
```

Reads a user's profile and friend list on one partition, then fans out to each friend's partition to read their profiles, aggregating the results at origin.

## Declaration

`<<query-topology` is placed at the `defmodule` body level — not inside a `let` binding like stream/microbatch topologies. PState symbols are module-scoped, so query topologies can access any PState declared in any topology:

```clojure
(defmodule M [setup topologies]
  (let [mb (microbatch-topology topologies "etl")]
    (declare-pstate mb $$data {String Long})
    (<<sources mb ...))

  (<<query-topology topologies "q" [*k :> *v]
    ...
    (|origin)
    ...))
```

## Signatures

- Zero-input: `[:> *result]`
- Single-argument: `[*k :> *result]`
- Multi-argument: `[*a *b :> *result]`

## Leading partitioner optimization

If the first line of a query topology is a partitioner, it is evaluated client-side to route the query directly to the right task, avoiding a random-task hop. Requirements:
- Must be a built-in partitioner (not custom)
- Must target exactly one task (not `|all`)
- All inputs must be topology input variables
- Cannot target a mirror PState partitioner

## Invocation

```clojure
;; From client code
(def q (foreign-query ipc module-name "query-name"))
(foreign-invoke-query q *arg1 *arg2)

;; From within another topology in the same module
(invoke-query "query-name" *arg :> *result)
```

## Self-invocation (recursion)

Query topologies can call themselves via (invoke-query "query-name" ...argz). Execution is timeout-bounded to prevent infinite loops.

## Temporary per-invocation PState

Every query topology has an implicit unreplicated in-memory PState `$$<topology-name>$$` for storing intermediate results during execution. It starts as nil on every partition for each invocation.

## Constraints

- Read-only: no `local-transform>` on user PStates (only the implicit temp PState)
- Must end with `|origin` as the final pre-agg partitioner
- Output variable must be emitted exactly once
- No partitioners in post-agg

## Troubleshooting

- `|origin must be specified in final subbatch to <<query` — missing `|origin`
- `Partitioning not allowed in postagg` — extra `|origin` or other partitioner after aggregation
- `:no-possible-joins` — pre-agg branches have incompatible logvar shapes; keep the pre-agg tail unified
- Output variable not in scope — ensure the aggregator or final statement binds to the output var declared in the signature
