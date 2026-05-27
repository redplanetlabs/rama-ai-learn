# Scheduled Processing (TopologyScheduler)

`TopologyScheduler` from `rama-helpers` schedules items for future processing at specific times. Under the hood, it keeps items sorted in a PState keyed by timestamp, enabling efficient detection of expired items — only items up to the current time are scanned, not all scheduled items.

Since `TopologyScheduler` is a Java API, it is used via `java-macro!` and `java-block<-` to integrate with Clojure dataflow.

`TopologyScheduler` uses `TopologyUtils/currentTimeMillis` internally, so it integrates with simulated time for testing. See `references/testing.md` for the sim time and tick depot testing patterns.

## Setup

`TopologyScheduler` can be used with either stream or microbatch topologies. However, it has some restrictions on usage with stream topologies (see below) that MUST be considered when planning.

```clojure
(:import [com.rpl.rama.helpers TopologyScheduler])

(defmodule MyModule [setup topologies]
  (declare-tick-depot setup *expire-tick 30000) ;; fires every 30 seconds

  (let [mb (microbatch-topology topologies "expirations")
        scheduler (TopologyScheduler. "$$scheduler")]
    (.declarePStates scheduler mb)

    (<<sources mb
      ;; Schedule items when they arrive
      (source> *item-depot :> %mb)
      (%mb :> {:keys [*item-id *expiration-time]})
      (java-macro! (.scheduleItem scheduler "*expiration-time" "*item-id"))

      ;; Process expirations on each tick
      (source> *expire-tick :> %mb)
      (%mb)
      (java-macro!
        (.handleExpirations scheduler "*item-id" "*current-time"
          (java-block<-
            ;; *item-id and *current-time are bound here
            ;; Write your expiration handling logic:
            (process-expired-item *item-id)
            ))))))
```

## Topology type constraints

**Microbatch:** No constraints on what the `handleExpirations` block can do. It can fan out with `ops/explode`, repartition to multiple tasks, do anything.

**Stream:** The code inside the `handleExpirations` block MUST reach the end exactly once. If the expiration handling needs multiple effects on multiple tasks, it must do them sequentially — NOT via `ops/explode` + partitioner, which fans out and breaks the exactly-once-to-end constraint.

Valid — sequential partitioner calls, reaches end once:

```clojure
(java-block<-
  ;; First effect on partition A
  (|hash *key-a)
  (local-transform> [(keypath *key-a) (termval *val-a)] $$pstate1)
  ;; Second effect on partition B
  (|hash *key-b)
  (local-transform> [(keypath *key-b) (termval *val-b)] $$pstate2))
;; Reaches end once ✓
```

Invalid — `ops/explode` fans out, reaches end multiple times:

```clojure
(java-block<-
  (ops/explode *list :> *r)  ;; fans out!
  (|hash *r)
  (+compound $$pstate {*r (aggs/+count)}))
;; Reaches end N times ✗ — breaks stream handleExpirations constraint
```

Valid — same logic using `loop<-` to stay sequential:

```clojure
(java-block<-
  (loop<- [*remaining *list :> *done]
    (<<if (empty? *remaining)
      (:> true)
     (else>)
      (first *remaining :> *r)
      (|hash *r)
      (+compound $$pstate {*r (aggs/+count)})
      (continue> (rest *remaining)))))
;; Reaches end once ✓
```

`.scheduleItem` and `.handleExpirations` must be called in the same topology — the scheduler stores items in PStates owned by that topology. If the data needed for scheduling originates in a different topology, forward it via an internal depot (`:disallow` + `depot-partition-append!`) to the scheduling topology.

```clojure
(depot-partition-append! *other-depot *item-to-schedule :append-ack)
```

## How to use

- Call `.declarePStates` on the topology during setup.
- `.scheduleItem` takes a timestamp (millis) and an item. Each call creates a new scheduled entry with a unique internal key — calling `.scheduleItem` twice with the same item creates two entries that will both fire. `.scheduleItem` is NOT idempotent.
- `.handleExpirations` does `|all` to go to all tasks, finds items whose scheduled time ≤ current time, and runs the provided code block for each. The item variable is bound to the exact same object that was passed to `.scheduleItem`, and it is emitted on the same task where it was scheduled. Processed items are deleted automatically.
- DO NOT call `|all` before `.handleExpirations` since `.handleExpirations` is already doing that. Calling `|all` beforehand is extremely inefficient.
- The `java-block<-` macro exports a block of Clojure dataflow code to pass to the Java helper. Inside the block, the item and current-time variables are bound.
- `.maxFetchAmt` configures max items processed per call to `handleExpirations` on each task (default 1000).

## Tick depots

Tick depots automatically emit events at a fixed interval and are the natural choice for triggering periodic expiration processing.

```clojure
(declare-tick-depot setup *tick 30000) ;; emit every 30 seconds
```

In the topology, source from the tick depot — it emits nil values at the configured interval:

```clojure
(source> *tick :> %mb)
(%mb) ;; no useful value, just triggers processing
```

For testing tick depots, see the "Testing Tick Depots" section in `references/testing.md`.

## java-block<-

`java-block<-` converts Clojure dataflow code into a Java `Block` that can be passed to Java helper methods. The code inside has access to all dataflow variables in scope:

```clojure
(java-macro!
  (.handleExpirations scheduler "*expired-item" "*now"
    (java-block<-
      ;; *expired-item and *now are available here
      (|hash *user-id)
      (local-transform> [(keypath *user-id) AFTER-ELEM (termval *notification)]
                        $$notifications))))
```
