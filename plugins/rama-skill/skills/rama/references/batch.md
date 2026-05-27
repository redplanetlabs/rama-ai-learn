# Batch Blocks

Batch blocks are a partially declarative execution mode for dataflow, similar to relational query semantics. You specify computation at an abstract level — Rama decides the execution order. Available in microbatch (via `<<batch`) and query topologies (not stream). Query topologies are implicitly batch blocks.

## Three-phase execution

Every batch block has three sequential phases:

### Pre-agg (data production)

Produces rows of data. The pre-agg builds up by **sequential attachment**: each line attaches to the current branch. Standard dataflow operations, partitioners, `local-select>`, `ops/explode` all attach sequentially. Sequential operations in the same branch are straightforward — multiple `local-select>` calls binding different variables (`*v1`, `*v2`, etc.) just extend the current branch.

Control flow (`<<if`, `<<cond`, `<<switch`) is fine within a branch — it unifies back to one branch after the conditional.

**Branches and joins:** `gen>` starts a new independent branch. If a line needs a variable not in the current branch's scope, Rama searches for a join across branches that brings that variable in scope. Branches join automatically on shared variable names. All branches must combine into a single branch (through merges via `unify>` or implicit joins) by the end of pre-agg.

Example — `<<cond` within a branch unifies and continues into aggregation:

```clojure
(ops/explode *items :> *item)
(<<cond
  (case> (> *item 20))
  (* *item 3 :> *val)

  (case> (> *item 10))
  (* *item 2 :> *val)

  (default>)
  (identity *item :> *val))
;; *val is in scope — <<cond unified the branches
(|origin)
(aggs/+sum *val :> *total)
```

Constraints:
- Variable shadowing prohibited — var names determine join semantics across branches
- The pre-agg must combine to a single branch

### Agg (aggregation)

The first aggregator form marks the transition from pre-agg to agg. Aggregators consume all data emitted from pre-agg. Multiple aggregators can run, each consuming the complete input.

Aggregators always produce output, even when zero rows flow through the pre-agg phase — they emit their init value (e.g., 0 for `+count`, 0 for `+sum`). The output variable is always bound. 


### Post-agg (result processing)

Code after aggregators. Only group-by keys (if `+group-by` is used) and aggregator outputs are in scope — pre-agg variables are gone (they've been aggregated). No partitioners allowed in post-agg.

## Core pattern: explode → process → aggregate

The fundamental batch block pattern:

```clojure
;; Pre-agg: produce multiple values
(ops/explode *items :> *item)
(some-operation *item :> *value)
;; Agg: reduce to single result
(|origin)  ;; or other partitioner
(aggs/+sum *value :> *total)
;; Post-agg: use aggregated result
```

Pre-agg emits one row per item. The aggregator reduces all rows to one result. This replaces procedural loops and manual accumulation.

## Examples

These examples are run outside of modules so run as "single-threaded batch mode". It's mostly identical to module batch blocks, but there are no partitioners here like you would need before aggregation in a module batch block.

### Sum and count

```clojure
(?<-
  (<<batch
    (ops/range> 0 10 :> *v)
    (aggs/+sum *v :> *sum)
    (aggs/+count :> *count)
    (println "Res:" *sum *count)))
;; Res: 45 10
```

### Zero rows — aggregators still fire

```clojure
(?<-
  (<<batch
    (ops/explode [] :> *v)
    (aggs/+sum *v :> *sum)
    (aggs/+count :> *count)
    (println "Res:" *sum *count)))
;; Res: 0 0
```

### Group-by

```clojure
(?<-
  (<<batch
    (ops/explode [[:a 1] [:b 2] [:a 3] [:a 4] [:b 10] [:c 9]] :> [*k *v])
    (+group-by *k
      (aggs/+count :> *count)
      (aggs/+sum *v :> *sum))
    (println *k *count *sum)))
;; :a 3 8
;; :b 2 12
;; :c 1 9
```

## Joins

Batch sources join automatically on shared variable names — no explicit join syntax needed.

### Inner join

```clojure
(?<-
  (<<batch
    (ops/explode [[:a 1] [:a 2] [:b 2] [:c 3]] :> [*k *v1])

    (gen>)
    (ops/explode [[:a 10] [:c 4] [:d 100]] :> [*k *v2])

    (println "Res:" *k *v1 *v2)))
;; Res: :a 1 10
;; Res: :a 2 10
;; Res: :c 3 4
```

`gen>` starts a new batch source. The two sources join on `*k`. Only matching rows survive.

### Left outer join

Prefix variables with `**` (unground) to keep all rows from the ground source:

```clojure
(?<-
  (<<batch
    (ops/explode [[:a 1] [:a 2] [:b 2] [:c 3]] :> [*k *v1])

    (gen>)
    (ops/explode [[:a 10] [:c 4] [:d 100]] :> [*k **v2])

    (println "Res:" *k *v1 **v2)))
;; Res: :a 1 10
;; Res: :a 2 10
;; Res: :b 2 nil
;; Res: :c 3 4
```

All rows from the first source survive; `**v2` is nil when unmatched.

### Delayed unground

Variables prefixed with `*__` (triple underscore) are ground until an unground var is introduced on the same source, then they become unground too.

## Subbatches

Subbatches are batch blocks nested inside batch blocks — they have their own pre-agg/agg/post-agg phases. Define with `defgenerator` + `batch<-`:

```clojure
(defgenerator counts-subbatch [source]
  (batch<- [*k *count]
    (source :> *k)
    (+group-by *k
      (aggs/+count :> *count))))

(?<-
  (<<batch
    (counts-subbatch [:b :a :b :b :c :a] :> *k *count)
    (aggs/+limit [2] *k *count :+options {:sort *count :reverse? true})
    (println "Res:" *k *count)))
;; Res: :b 3
;; Res: :a 2
```

The subbatch computes word counts; the outer batch finds the top 2. Subbatches compose infinitely — batch blocks can consume many subbatches, subbatches can build on other subbatches.

## Two-phase aggregation

For combiner-compatible aggregators (like `+sum`, `+count`, `+min`, `+max`), batch blocks automatically optimize global aggregations: partial aggregates compute locally on each task, then combine after partitioning. This scales much better than centralizing all data before aggregating.

## Materialization (microbatch only)

`materialize>` stores batch results in temporary in-memory PStates for reuse within the same microbatch attempt:

```clojure
(<<batch
  (%microbatch :> [*k *v])
  (+group-by *k
    (aggs/+sum *v :> *sum))
  (materialize> *k *sum :> $$t))

;; Subsequent batch block can read $$t
(<<batch
  ($$t :> *k *sum)
  ...)
```

Materialized PStates are cleared between microbatches.

## Pre-agg only

Batch blocks without aggregators are valid — they have only a pre-agg phase. All sources must still combine into a single branch.

## Syntax

```ebnf
batch-block     = '(<<batch' { batch-stmt } ')' ;
gen-source      = '(gen>)' ;
generator-def   = '(defgenerator' symbol '[' { var } ']' '(batch<-' '[' { var } ']' body ')' ')' ;
subbatch        = '(batch<-' '[' { var } ']' { batch-stmt } ')' ;
materialize     = '(materialize>' { var } ':>' pstate-var ')' ;
```

## Constraints

- Variable shadowing prohibited in pre-agg
- Final pre-agg partitioner required when aggregators are present
- No partitioners in post-agg
- Post-agg scope: only group-by keys + aggregator outputs
- Stream topologies cannot use `<<batch`
- `local-transform>` allowed in pre-agg of microbatch batch blocks (not query topologies — they are read-only)

## Troubleshooting

- `Could not produce valid preagg configuration ... :reason {:shadowed #{*a}}` — variable shadowing in pre-agg
- `Expected single tail, found multiple` — batch sources not joined/merged into single branch
- `Could not produce valid preagg configuration ... :no-possible-joins` — batch sources share no common variables for joining
- `Partitioning not allowed in postagg` — partitioner in post-agg phase
