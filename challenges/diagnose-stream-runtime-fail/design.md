# diagnose-stream-runtime-fail design

## Summary

Create a cluster diagnosis challenge adjacent to `diagnose-deploy-fail`, but for a **runtime** failure in a **streaming topology**.

The learner should interact with the running cluster as an external client, **append a value to a depot**, and observe that the append does **not successfully ack** because the deployed streaming topology hits a persistent runtime error while processing that record.

The learner's task is **diagnosis only**. They should not fix code or redeploy anything. They must determine the runtime root cause using the running cluster's diagnostics and write the diagnosis to `implementations/diagnose-stream-runtime-fail/answer.md`.

---

## Challenge shape

### Core experience

1. A module is already deployed successfully to the running cluster.
2. The learner appends a record to a depot from an external JVM client.
3. The append call is expected to wait for processing / ack semantics.
4. The ack does not succeed because the streaming topology repeatedly fails on that record.
5. The learner inspects the conductor UI/API and/or runtime diagnostics.
6. The learner writes down the root cause.

### Why this shape

This makes the failure concrete and operational:

- it is clearly **not a deploy failure**
- it is clearly **not a build problem**
- it is tied to a real client action
- it forces the distinction between **append accepted by the depot** and **processing ack / successful end-to-end handling**
- it feels like a realistic streaming incident

---

## Scope

### In scope

- running local Rama cluster
- module already deployed
- one deployed streaming topology with a persistent runtime failure
- learner appends one or more records via external client code
- learner observes append/ack behavior and cluster diagnostics
- learner produces a written diagnosis only

### Out of scope

- repairing code
- redeploying the module
- modifying cluster configuration
- intermittent failures
- infra/network/connectivity issues
- multi-module incidents
- multiple simultaneous root causes

---

## Diagnosis target

The learner should determine:

1. **the module deployed successfully**
2. **the failure is a runtime failure in a streaming topology**
3. **which topology is failing**
4. **the exception type**
5. **the specific bad field/value or data-shape mismatch that triggers it**
6. **that this runtime failure is why the append ack does not successfully complete**

---

## Concrete module / topology / event contract

Use a single-purpose deployed module dedicated to this challenge.

### Module

- module name: `com.rpl.challenges.stream-ack-fail/StreamAckFailModule`

### Topology

- topology kind: stream
- topology name: `payments`
- source depot: `*payments`

### Event shape

Use a map event so the root cause is readable in diagnostics and easy to name in the answer:

```clojure
{:user-id "u1" :amount "12"}
```

Schema expectation at the application level:

- `:user-id` is a string
- `:amount` is a string representation of an integer

### Processing behavior

The `payments` stream topology reads events from `*payments` and parses `:amount` as an integer before writing some derived result to PState.

A healthy record example:

```clojure
{:user-id "u1" :amount "12"}
```

A poison-pill record example:

```clojure
{:user-id "u1" :amount "abc"}
```

### Bug

The topology assumes `:amount` is always numeric and performs integer parsing without validating malformed input first.

### Exception

- exception type: `NumberFormatException`

### Why this contract

- concrete topology name to diagnose
- concrete depot name to append to
- concrete field name to mention in answer
- concrete bad value to mention in answer
- easy to reproduce deterministically
- stable for harness validation

---

## User-facing task statement

The README should instruct the learner to:

- connect to the running cluster
- get a handle to depot `*payments` in module `com.rpl.challenges.stream-ack-fail/StreamAckFailModule`
- append a payment event using `foreign-append!` with `:ack`
- observe that the append does not successfully return an ack within the expected time
- diagnose why the deployed `payments` streaming topology is failing
- write the diagnosis to `implementations/diagnose-stream-runtime-fail/answer.md`

The README should also explicitly say:

- do **not** modify code
- do **not** redeploy anything
- use the running cluster and conductor UI/API
- use the poison-pill event shape shown in the README

---

## Required observability

The challenge must be solvable from stable runtime evidence.

At minimum, the learner must be able to discover:

- the module is deployed
- the topology name
- the runtime exception type
- enough evidence to connect the exception to the bad input

### Preferred evidence sources

- conductor Web UI/API
- topology/task failure status
- stacktrace or exception summary
- append client behavior showing ack failure / timeout / error

### Fairness constraint

Do **not** require the learner to guess the root cause from a vague failure symptom alone. The runtime diagnostics must make the exception and the bad input mismatch inferable.

---

## Ack semantics

This is now resolved.

The challenge should use Rama's built-in **stream ack** semantics directly.

### Runtime contract

The learner appends to an **owned stream depot** using:

```clojure
(r/foreign-append! depot record :ack)
```

Per Rama's foreign client semantics for stream topologies:

- `:append-ack` means the depot write is durable
- `:ack` means the depot write is durable **and** colocated stream processing has completed and PState effects are visible

So for this challenge, "verify the ack return" means:

> invoke `foreign-append!` with `:ack` against the deployed stream depot and observe that the call does not complete successfully within a bounded time because the event tree never completes.

### Why this is correct

For an owned stream depot, `:ack` waits for the stream event tree to complete. If the topology persistently fails on the appended poison-pill record under retry, the event tree never completes, so the `:ack` call never yields a successful ack result.

This gives the exact behavior we want:

- deploy succeeded
- depot is reachable
- append path is real
- failure is specifically in stream processing
- the symptom is tied to Rama's native ack semantics, not a custom invented contract

### Concrete challenge API decision

The challenge should instruct the learner to use:

```clojure
(r/foreign-append! depot bad-record :ack)
```

not `:append-ack` and not the default nil/fire-and-forget mode.

### Observable symptom

To make the challenge bounded and testable, the learner-facing and harness-facing probe should wrap the blocking `:ack` call in a timeout.

Recommended probe shape:

```clojure
(deref
  (future (r/foreign-append! depot bad-record :ack))
  5000
  ::timeout)
```

Expected result for the poison-pill record:

- returns `::timeout` within the bounded wait window

Expected result for a good record in a healthy version of the topology:

- returns an ack map

### What the timeout means

The timeout does **not** mean the TCP connection failed or the depot append was rejected.
It means:

- the client is waiting on Rama stream `:ack`
- Rama is waiting for stream processing completion
- completion never happens because the topology keeps failing on that record

### Important distinction

To avoid ambiguity, the challenge should explicitly teach this distinction:

- `:append-ack` would only prove the depot write is durable
- `:ack` additionally waits for stream processing completion
- this challenge is about `:ack` not completing, not about raw depot acceptance

### Harness contract

The harness should reproduce the symptom with a helper equivalent to:

```clojure
(defn append-with-ack-timeout!
  [depot record timeout-ms]
  (deref (future (r/foreign-append! depot record :ack))
         timeout-ms
         ::timeout))
```

and assert that the poison-pill append yields `::timeout`.

### README wording

The README should say:

- append a record to the depot using `foreign-append!` with `:ack`
- observe that the call does not successfully return an ack within the expected time
- diagnose why stream processing is preventing ack completion

This removes the remaining ambiguity about what counts as the ack symptom.

---

## Proposed challenge contract

### Deliverable

Learner writes:

`implementations/diagnose-stream-runtime-fail/answer.md`

### Correct answer must include

- that module `com.rpl.challenges.stream-ack-fail/StreamAckFailModule` was deployed successfully
- that streaming topology `payments` fails at runtime
- the exception type `NumberFormatException`
- that field `:amount` contains a non-numeric value
- that the concrete bad value is `"abc"`
- that this is why `foreign-append!` with `:ack` does not successfully return an ack

### Example sufficient answer

> The module `com.rpl.challenges.stream-ack-fail/StreamAckFailModule` is deployed, but the streaming topology `payments` is repeatedly failing at runtime with a `NumberFormatException`. It tries to parse field `:amount` as a number, but the appended poison-pill event uses the non-numeric value `"abc"`, so stream processing never completes successfully and `foreign-append!` with `:ack` does not return an ack.

---

## Harness design

Mirror `diagnose-deploy-fail`.

### Files

- `README.md`
- `src/diagnose_stream_runtime_fail/test_support.clj`
- `test/diagnose_stream_runtime_fail/challenge_test.clj`
- `test-harness/diagnose_stream_runtime_fail/harness_test.clj`
- `test-resources/diagnose_stream_runtime_fail/reference_solution.sh`
- `implementations/diagnose-stream-runtime-fail/answer.md`

### test_support responsibilities

Should verify:

1. cluster is running
2. target module `com.rpl.challenges.stream-ack-fail/StreamAckFailModule` is deployed
3. learner answer file exists
4. answer includes expected diagnosis substrings:
   - `payments`
   - `numberformatexception`
   - `amount`
   - `abc`
   - `ack`
5. an external append-plus-ack probe reproduces the `::timeout` symptom for the poison-pill event

Optional but desirable if stable:

6. target topology is in a failed/unhealthy state according to conductor diagnostics

### harness_test responsibilities

Reference setup should:

1. deploy module `com.rpl.challenges.stream-ack-fail/StreamAckFailModule` successfully
2. append poison-pill event `{:user-id "u1" :amount "abc"}` through the intended client path
3. reproduce the failed `:ack` symptom as `::timeout`
4. write a reference diagnosis
5. assert the harness passes

---

## Scoring rubric

A diagnosis is correct only if it identifies all key layers:

### Layer 1: deployment state
- module deployed successfully

### Layer 2: runtime location
- streaming topology is failing
- topology named if stable

### Layer 3: exception
- exception type named correctly

### Layer 4: data root cause
- specific field/value/type mismatch identified

### Layer 5: symptom connection
- explains that this runtime failure causes the append ack not to complete successfully

A weaker answer like "there is a runtime exception" should not pass.

---

## Design decisions

### Chosen

- diagnosis only
- streaming topology
- single deterministic poison-pill record
- single root cause
- successful deploy
- persistent runtime failure
- answer written to markdown file

### Rejected

- requiring a code fix
- multiple failing topologies
- intermittent timing failures
- generic NPE-only diagnosis
- hidden support-code bug as root cause

---

## Remaining implementation questions

Most design ambiguity is resolved. The remaining questions are mechanical implementation details:

1. **What timeout window should the harness use for `:ack`?**
   - recommended initial value: `5000` ms
   - must be long enough to avoid false positives from normal cluster slowness

2. **What retry mode should the stream source use?**
   - it should preserve the intended persistent-failure behavior
   - implementation should choose the mode that makes the poison-pill keep the event tree from completing in a stable way

3. **Can the harness robustly assert failed runtime state from cluster APIs?**
   - if yes, include that check
   - if no, rely on module-deployed + reproduced `::timeout` symptom + diagnosis text

4. **Should the topology emit an `ack-return>` value for healthy records?**
   - recommended: yes
   - this makes the success shape explicit and the timeout shape easier to contrast during manual exploration

---

## Resolved implementation contract

The challenge should be scaffolded with these fixed choices:

- challenge name: `diagnose-stream-runtime-fail`
- module name: `com.rpl.challenges.stream-ack-fail/StreamAckFailModule`
- depot name: `*payments`
- topology name: `payments`
- topology type: stream
- append API: `(r/foreign-append! depot record :ack)`
- timeout probe: bounded `future` + `deref`
- healthy event example: `{:user-id "u1" :amount "12"}`
- poison-pill event example: `{:user-id "u1" :amount "abc"}`
- exception type: `NumberFormatException`
- required answer terms: topology name, exception type, `:amount`, `"abc"`, and ack non-completion

With these choices fixed, the challenge can now be scaffolded.
