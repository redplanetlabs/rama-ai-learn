# Implementation Validation

<!-- Phase 4. Fill in after Phase 3 produces the module source. -->

Review all topology, query topology, and foreign client code. For each check, state pass or fail with evidence. Then emit one of three verdicts at the end of this artifact, per the rubric below.

## Redundant conditionals
<!-- if every branch of an <<if, <<cond, or <<switch does the same operation with only a variable differing, replace with a single operation using that variable directly -->

## Consecutive keypath
<!-- (keypath *a) (keypath *b) → (keypath *a *b) -->

## Select-compute-transform
<!-- local-select> followed by computation followed by local-transform> with termval — replace with +compound and an aggregator when possible -->

## Unnecessary nil->val
<!-- navigators handle nil as empty collection — do not add nil->val unless the next navigator requires a non-nil value (e.g., (nil->val 0) before (term inc)) -->

## :allow-yield?
<!-- local-select> or select> that iterates over a subindexed structure on a non-mirror PState should include {:allow-yield? true} whenever the iteration count can exceed ~100 entries. This includes ALL, MAP-KEYS, MAP-VALS, sorted-map-range, sorted-map-range-from, sorted-map-range-to, sorted-map-range-to-end, and any other range navigator. A navigator that returns a single submap value (e.g. sorted-map-range-to-end 800) still iterates internally and still needs :allow-yield? when the range can exceed ~100 entries. Do NOT add it to reads bounded to small counts — it adds overhead on every iteration. -->

## Non-subindexed collections without size limits
<!-- for every write to a non-subindexed inner collection (map, set, vector), verify the application explicitly enforces a maximum size. If there is no code that caps the collection size, it must be subindexed — change the schema and update the module accordingly. -->

## Stream topology idempotency
<!-- for each stream topology, trace through what happens if any event retries. Check every write and side effect:
  - Are all PState writes idempotent (e.g., termval overwrites)? If any write uses AFTER-ELEM, counter increment, or other non-idempotent operation, it must have a mechanism to prevent duplicates on retry.
  - Are IDs generated inside the topology (e.g., ops/random-uuid7 in dataflow)? On retry, a new ID is generated. Will that create orphaned PState entries under the old ID? It is usually best to generate IDs client-side and included in the depot record.
  - Does the topology call depot-partition-append! to an internal depot? On retry, duplicate records are appended. Is there a (|direct (ops/current-task-id)) commit boundary before the append? Does the receiving topology deduplicate? -->

## Partial failure in stream topologies
<!-- for each stream event that writes to multiple PStates across multiple partitions, consider what happens if the event fails and retries after some writes have committed but others have not. Is it possible for a partial failure + retry to leave any writes permanently unexecuted? -->

## Single depot append per client operation
<!-- each client write operation must call foreign-append! exactly once. If the operation needs additional depot writes, they must happen server-side in the topology via depot-partition-append!. Multiple client-side appends for one operation means a client crash between them leaves inconsistent state. -->

## Application-state caches survive restart
<!-- for each TaskGlobal or in-process cache holding application state, verify:
  - There is a durable source (e.g. a PState or external system) sufficient to rebuild the cached data. Name it.
  - There is a concrete rebuild path. State which one and cite the code that implements it. -->

## No reimplementation of built-in operations
<!-- Scan the module source for any custom code that duplicates functionality already provided by Rama's built-in namespaces, such as com.rpl.rama.ops. Hand-rolled versions are FAIL. -->

## Plan conformance
<!-- Compare the implementation against PLAN.md. Any divergence from the plan is a FAIL unless the plan was wrong — meaning either a correctness issue (the plan's approach would produce incorrect behavior) or the plan's approach would have significantly worse performance than the implementation's approach. "Simpler," "functionally equivalent," "not a correctness issue," and "easier to implement" are not valid reasons to diverge. The plan was reviewed and validated; the implementation must follow it. List every divergence found and state PASS (matches plan) or FAIL (diverges without valid justification). -->

## Verdict

Emit exactly one of `pass`, `minor-fail`, or `major-fail`, with a one-sentence justification.

- **pass**: every check above passed.
- **minor-fail**: at least one check failed, but every failure is fixable by editing specific lines in the existing module.
- **major-fail**: at least one failure requires more significant changes or restructuring to the module. Pick `major-fail` whenever you are unsure between minor and major.

Pick the strongest category that applies to any single failure. If one finding is major, the verdict is `major-fail` even if other failures are minor.
