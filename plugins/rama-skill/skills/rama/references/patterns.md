# Architectural Patterns in Rama

A catalog of recurring structural patterns found across Rama applications.
Each pattern captures an architectural idiom — a proven way to wire depots,
topologies, PStates, and queries to solve a class of problem.

---

## Core data flow

These patterns form the backbone of most Rama modules.

### Event-sourced view

A topology sources events from a depot and materializes a derived view as a
PState. Events in, state out — Rama's fundamental building block.

**When to use:** Any time you need to maintain a derived view of incoming data.

```clojure
(defmodule ExampleModule [setup topologies]
  (declare-depot setup *events (hash-by :entity-id))
  (let [s (stream-topology topologies "events->view")]
    (declare-pstate s $$view {Long String})
    (<<sources s
      (source> *events :> %event)
      (local-transform> [(keypath (:entity-id %event)) (termval %event)] $$view))))
```

**Composes with:** fan-out-materializer (multiple views from one depot),
CQRS (add a query topology that reads the view).

### Fan-out materializer

A single topology sources one depot and writes multiple PStates. Each PState
represents a different index or projection of the same data.

**When to use:** When you need multiple access patterns over the same event
stream — e.g., a primary key lookup and a secondary index.

```clojure
(defmodule ExampleModule [setup topologies]
  (declare-depot setup *events (hash-by :entity-id))
  (let [s (stream-topology topologies "events->views")]
    (declare-pstate s $$profiles {Long String})
    (declare-pstate s $$profiles-by-email {String Long})
    (<<sources s
      (source> *events :> %event)
      (local-transform> [(keypath (:entity-id %event)) (termval %event)] $$profiles)
      (local-transform> [(keypath (:email %event)) (termval (:entity-id %event))] $$profiles-by-email))))
```

---

## Cross-module coordination

These patterns connect separate Rama modules using mirror declarations.

### Cross-module choreography

A module mirrors a depot from another module and materializes local state
from its events. The producer doesn't know who consumes its events.

**When to use:** When one module needs to react to events produced by
another, without coupling.

```clojure
(defmodule ExampleModule [setup topologies]
  (mirror-depot setup *remote-events RemoteProducerModule)
  (let [s (stream-topology topologies "remote-events->view")]
    (declare-pstate s $$projection {Long String})
    (<<sources s
      (source> *remote-events :> %event)
      (local-transform> [(keypath (:entity-id %event)) (termval %event)] $$projection))))
```

### Cross-module command dispatch

A query topology appends records to foreign depots in other modules —
sending commands across module boundaries.

**When to use:** When a user action or workflow step needs to trigger
processing in multiple other modules.

```clojure
(defmodule ExampleModule [setup topologies]
  (mirror-depot setup *remote-actions-depot "other.ns/ActionsModule" "*actions")
  (mirror-depot setup *remote-analytics-depot "other.ns/AnalyticsModule" "*analytics")
  (<<query-topology topologies "dispatch-command" [*user-id *action :> *result]
    (depot-partition-append! *remote-actions-depot {:user-id *user-id :action *action} :append-ack)
    (depot-partition-append! *remote-analytics-depot {:user-id *user-id :action *action} :ack)
    (identity true :> *result)
    (|origin)))
```

### Cross-module read gateway

A topology reads a mirrored PState from an external module — read access
to another module's state without copying.

**When to use:** When a query needs data owned by another module — e.g.,
joining local data with a remote profile lookup.

```clojure
(defmodule ExampleModule [setup topologies]
  (mirror-pstate setup $$remote-profiles RemoteProfilesModule)
  (let [q (query-topology topologies "lookup-remote-profile")]
    (<<sources q
      (select> [(keypath ?id)] $$remote-profiles :> ?profile))))
```

---

## Specialized topologies

These patterns exploit specific Rama topology features — partitioning
strategies, batch blocks, and data replication.

### Periodic computation

A topology sources from a tick depot (periodic timer) and materializes
state. Useful for heartbeats, periodic aggregation, or time-window
computations.

**When to use:** When processing needs to happen on a schedule rather than
in response to external events.

```clojure
(defmodule ExampleModule [setup topologies]
  (declare-tick-depot setup *tick-depot 1000)
  (let [s (stream-topology topologies "tick->view")]
    (declare-pstate s $$metrics {String Long})
    (<<sources s
      (source> *tick-depot)
      (local-transform> [(keypath "heartbeat") (nil->val 0) (term inc)] $$metrics))))
```

### Co-partitioned join

Two depots sharing the same partitioner feed one topology. Related records
from both depots land on the same task, enabling local joins without
network hops.

**When to use:** When you need to join data from two event streams and can
control their partitioning strategy.

```clojure
(defmodule ExampleModule [setup topologies]
  (declare-depot setup *orders (custom-partitioner order-partitioner))
  (declare-depot setup *items (custom-partitioner order-partitioner))
  (let [s (stream-topology topologies "join->view")]
    (declare-pstate s $$joined {Long String})
    (<<sources s
      (source> *orders :> *order)
      (local-transform> [(keypath (:id *order)) (termval *order)] $$joined)

      (source> *items :> *item)
      (local-transform> [(keypath (:id *item)) (termval *item)] $$joined))))
```

### Broadcast materializer

A topology uses `|all` to replicate data to every task. Every task gets a
full copy — useful for lookup tables or configuration.

**When to use:** When every partition needs a complete copy of a small
dataset — e.g., feature flags, configuration, or dimension tables.

```clojure
(defmodule ExampleModule [setup topologies]
  (declare-depot setup *items (hash-by :id))
  (let [mb (microbatch-topology topologies "load-items")]
    (declare-pstate mb $$items {Long String})
    (<<sources mb
      (source> *items :> %microbatch)
      (%microbatch :> *item)
      (|all)
      (local-transform> [(keypath (:id *item)) (termval *item)] $$items))))
```

### Two-phase aggregation

A microbatch topology uses a `<<batch` block for a first aggregation pass,
then repartitions (via `|global`, `|hash`, or `|all`) for a second pass.
Enables aggregations that combine results across partitions.

**When to use:** When a single-pass aggregation isn't sufficient because
the final result depends on data spread across partitions.

```clojure
(defmodule ExampleModule [setup topologies]
  (declare-depot setup *events (hash-by :user-id))
  (let [mb (microbatch-topology topologies "top-users")]
    (declare-pstate mb $$top-users {Long Long})
    (<<sources mb
      (source> *events :> %microbatch)
      (%microbatch :> *event)
      (<<batch
        (local-transform> [(keypath (:user-id *event)) (nil->val 0) (term inc)] $$top-users))
      (|global)
      (+compound $$top-users ...))))
```

### Query pagination

A query topology reads a PState using range navigation and accepts a cursor
parameter, implementing cursor-based pagination.

**When to use:** When clients need to iterate through large result sets
in bounded pages.

```clojure
(<<query-topology topologies "list-items"
  [*page-size *pagination-params :> *res]
  (|all)
  (local-select>
   [(sorted-map-range-to *pagination-params
                          {:inclusive? true :max-amt *page-size})]
   $$items :> *page)
  (|origin)
  (hash-map :items *page
            :pagination-params (last-key *page)
            :> *res))
```

---

## Runtime interaction

These patterns describe how application code outside of topologies — HTTP
handlers, CLI tools, background jobs — interacts with a Rama cluster through
client handles, `foreign-select`, and `foreign-invoke-query`.

### Query broker

Application code resolves a query handle and invokes a single query topology.

**When to use:** Any time application code needs to call a Rama query.

```clojure
(defn fetch-data [manager]
  (let [{:keys [search-query]} (underlying-objects manager)]
    (foreign-invoke-query search-query {:search-string "abc"} 20 nil)))
```

### Query fanout

Application code invokes multiple query topologies to assemble a composite
result from several data sources.

**When to use:** When a single response requires data from multiple
independent query topologies.

```clojure
(defn fetch-ui-data [manager]
  (let [{:keys [search-query results-query]} (underlying-objects manager)]
    {:search (foreign-invoke-query search-query "abc" 10)
     :results (foreign-invoke-query results-query 25 nil)}))
```

### State inspector

Application code reads PState data directly through `foreign-select`,
without going through a query topology.

**When to use:** Simple state lookups where a full query topology would be
overhead — configuration reads, health checks, debugging.

```clojure
(defn inspect-state [client]
  (let [config-pstate (:config-pstate (underlying-objects client))]
    (foreign-select-one STAY config-pstate {:pkey 0})))
```

### Enrichment orchestrator

Application code fetches supporting data from remote state or queries,
then uses it to validate, prepare, or enrich inputs for a downstream
workflow step. The remote data is context, not the response.

**When to use:** When a workflow step needs to look up reference data
before processing.

```clojure
(defn run-evaluator [manager run-config]
  (let [{:keys [examples-query]} (underlying-objects manager)
        examples (foreign-invoke-query examples-query
                   (:dataset-id run-config)
                   (:example-ids run-config))
        prepared (mapv #(prepare-run-input % (get examples (:id %)))
                       (:example-ids run-config))]
    (execute-evaluator manager (:name run-config) prepared)))
```

### Response orchestrator

Application code coordinates remote data access and multiple query
invocations to serve a response. The fetched data directly forms the
response.

**When to use:** When an API endpoint needs to gather data from several
remote sources to build a response.

```clojure
(defn get-dataset-view [manager dataset-id]
  (let [{:keys [metadata-pstate examples-query stats-query]}
          (underlying-objects manager)]
    {:metadata (foreign-select-one (keypath dataset-id) metadata-pstate)
     :examples (foreign-invoke-query examples-query dataset-id nil 100)
     :stats    (foreign-invoke-query stats-query dataset-id)}))
```

## Pattern composition

Patterns compose naturally as applications grow:

- **Event-sourced view** is the atom. Almost every module starts here.
- Add a secondary index → **fan-out materializer**.
- Add a query topology reading the PState → **CQRS**.
- Add range navigation with a cursor → **query pagination**.
- Mirror a PState into another module → **cross-module read gateway**.
- Mirror a depot across modules → **cross-module choreography**.
- Use `|all` for replication → **broadcast materializer**.
- Pair two co-partitioned depots → **co-partitioned join**.
- At the runtime boundary: **query broker** calls one query;
  **query fanout** calls many; **enrichment orchestrator** uses results
  as context for further work.

## Application problem → pattern mapping

| Application problem | Typical patterns |
|---------------------|-----------------|
| Entity CRUD with idempotency | event-sourced-view + fan-out-materializer |
| Global analytics / top-N | two-phase-aggregation + broadcast-materializer |
| Reference data replication | broadcast-materializer |
| Multi-entity transactional join | co-partitioned-join + event-sourced-view |
| Time-series rollup | event-sourced-view (windowed writes) |
| External service integration | event-sourced-view (with task-global client) |
| Workflow step preparation | enrichment-orchestrator |
| Multi-source API response | response-orchestrator + query-fanout |
| Paginated list/search endpoints | query-pagination (+ CQRS for writes) |
| Simple data access | query-broker or state-inspector |
| Cross-module command routing | cross-module-command-dispatch |
