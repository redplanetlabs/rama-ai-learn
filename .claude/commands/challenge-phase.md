---
name: challenge-phase
description: Execute a single phase of a Rama challenge. Invoked by the rama-ai-learn phase-orchestrating runner with `/challenge-phase <name> <phase-id> [<subsystem>]`. Each invocation does ONE phase only and stops.
arguments:
  - name: challenge_name
    description: Name of the challenge under challenges/
    required: true
  - name: phase_id
    description: Phase to execute (0..7, decompose, full-spec-review, or full-spec-fix)
    required: true
  - name: subsystem
    description: Subsystem slug from DECOMPOSITION.json (present only on multi-subsystem runs, phases 1..7)
    required: false
---

# /challenge-phase <challenge_name> <phase_id> [<subsystem>]

You are doing **one phase** of a phased Rama module build. The orchestrating runner has invoked you with a fresh context. Do this phase only — do not run any later phase, do not run tests (unless this phase's doc explicitly says to), do not "finish the whole thing."

## Pre-flight (every phase)

1. Verify `challenges/<challenge_name>/README.md` exists. If not, list available challenges and stop.
2. On phase 0 only, import clj-kondo configs:
   ```
   bash scripts/import-kondo-configs.sh <challenge_name>
   ```
3. Read `challenges/<challenge_name>/README.md` and any protocol/source files referenced by it. Do NOT read `challenges/<challenge_name>/test-resources/*` or `test-private/*` — those are private. Do NOT read any other challenge directory.
4. Load the `rama` skill.

## Production design (always on)

You are building a production Rama module. It will be deployed under real conditions — node failures, processing retries, concurrent clients, high throughput. Tests evaluate fault-tolerance and performance, not just functional correctness. Do NOT cut corners because "the test won't hit this case" — that produces unacceptable production behavior.

Any specification in the README or protocol is non-negotiable.

## Project-specific rules

- **Do NOT run any command with `run_in_background`.** You are running headlessly: ending your turn ends the session, and background-task completion notifications will NEVER arrive. A backgrounded test run is orphaned and the phase fails with no verdict. Run long commands (test suites, REPL checks) in the foreground with an explicit generous timeout.
- **rama-helpers is available but not necessarily needed.** Use only if relevant and helpful.
- **Test harness does NOT influence topology choice.** The challenge harness (`wait-for-processing*`) auto-detects stream vs microbatch and synchronizes correctly for both. Choose topology based on domain requirements, not test synchronization patterns.
- **Phase 5 (tests) constraints:**
  - Do NOT use `rama-challenges.harness` in tests — not in requires, imports, or fully-qualified calls.
  - If the module uses tick depots, use `rama-challenges.shared/REPLACE-TICK-DEPOTS` (already on classpath). Read its docstring for usage.

## Reasoning log (every phase)

`implementations/<challenge_name>/REASONING.md` is an append-only reasoning
log. The runner has already appended a sentinel line for this phase
(`=== PHASE <N> attempt <K> — <timestamp> ===`, with the subsystem slug in
brackets after `<N>` on multi-subsystem runs); your entries go below it.

As you work, append your reasoning AT EACH DECISION POINT — the alternatives
you weighed, why you rejected them, and which constraint drove the choice.
Write entries as you decide, not as a summary at the end; a retrospective
summary loses the dead ends, and the dead ends are the point.

ALWAYS log anything you are confused or uncertain about, AT THE MOMENT of
confusion: an API whose behavior you can't predict, documentation that seems
ambiguous or contradictory, an error you don't understand, a constraint you
aren't sure how to satisfy. Log it even if — especially if — you resolve the
confusion moments later, and note what resolved it. Confusion entries are the
single most valuable content in this file: they identify exactly where the
skill documentation failed you. Mark them with a `CONFUSION:` prefix.

- Append with:
  ```
  cat >> implementations/<challenge_name>/REASONING.md <<'EOF'
  ...your reasoning...
  EOF
  ```
- Do NOT rewrite REASONING.md, do NOT edit or delete prior entries, and do
  NOT remove sentinel lines. The file is append-only.
- This phase is NOT complete until REASONING.md has at least one entry for
  this phase. Validation phases (2, 4, 6, 7, full-spec-review,
  full-spec-fix): record the reasoning behind your verdict before emitting
  it. Decompose: record the boundaries you considered and rejected.

## Phase dispatch

Read the per-phase doc for `<phase_id>` and follow it. Do not read other phase docs.

| phase_id | Per-phase doc | Output artifact |
|---|---|---|
| 0 | `plugins/rama-skill/skills/rama/references/phase-0-implicit-spec.md` | `implementations/<challenge_name>/IMPLICIT_SPEC.md` |
| decompose | `plugins/rama-skill/skills/rama/references/phase-decompose.md` | `implementations/<challenge_name>/DECOMPOSITION.json` |
| 1 | `plugins/rama-skill/skills/rama/references/phase-1-plan.md` | `implementations/<challenge_name>/PLAN.md` |
| 2 | `plugins/rama-skill/skills/rama/references/phase-2-plan-validate.md` | `implementations/<challenge_name>/PLAN_VALIDATION.md` |
| 3 | `plugins/rama-skill/skills/rama/references/phase-3-implement.md` | `implementations/<challenge_name>/src/<challenge_name>/module.clj` |
| 4 | `plugins/rama-skill/skills/rama/references/phase-4-impl-validate.md` | `implementations/<challenge_name>/IMPLEMENTATION_VALIDATION.md` |
| 5 | `plugins/rama-skill/skills/rama/references/phase-5-tests.md` | `implementations/<challenge_name>/test/...` |
| 6 | `plugins/rama-skill/skills/rama/references/phase-6-test-validate.md` | `implementations/<challenge_name>/TEST_VALIDATION.md` |
| 7 | `plugins/rama-skill/skills/rama/references/phase-7-finish.md` | passing tests; module + tests modified in place |
| full-spec-review | `plugins/rama-skill/skills/rama/references/phase-full-spec-review.md` (review session) | `implementations/<challenge_name>/FULL_SPEC_REVIEW.md` |
| full-spec-fix | `plugins/rama-skill/skills/rama/references/phase-full-spec-review.md` (fix session) | module + tests fixed in place; full suite passing |

The implementation root is `implementations/<challenge_name>/` — substitute this for `<impl-root>` in any cp command in the per-phase doc.

The skill root is `plugins/rama-skill/skills/rama/` — substitute this for `<skill-root>` in any cp command in the per-phase doc.

**Decompose stage only:** `DECOMPOSITION.json` is read by the orchestrating runner to drive the per-subsystem cycles (it takes the `"name"` order and each entry's `"difficulty"` to pick the model tier; phase agents read the `"scope"` entries). Every entry needs `"name"`, `"scope"`, and `"difficulty"` (`"normal"` or `"hard"`). Verify it parses as JSON before finishing — if it is missing or malformed the runner silently falls back to a single-subsystem build and your decomposition is discarded.

## Subsystem (third argument, phases 1..7 only)

The decompose stage splits some modules into subsystems. When the runner
passes a third argument, you are building ONE subsystem of the module:

1. Read `implementations/<challenge_name>/DECOMPOSITION.json` and locate your
   subsystem's entry. That entry's `"scope"` is WHAT you build this cycle: the
   operations you own and the state (PStates, depots, query topologies) you
   must figure out — including state whose only consumers are later
   subsystems. The scope is NOT your spec. Your requirements are the FULL
   spec (README + protocol files, which you have already read): every
   requirement there that binds anything in your scope applies at full
   strength — including global properties (performance, balance,
   fault-tolerance) as they bear on reads and writes of YOUR state, whether
   those reads come from your own operations or from later subsystems'
   workloads as the spec describes them.
2. **Earlier subsystems are already implemented and tested.** Before designing,
   read their `PLAN-<sub>.md` artifacts and the current module source. EXTEND
   the module — do NOT redesign, rewrite, or degrade what earlier subsystems
   built. Their tests must keep passing.
3. **Later subsystems will build on your state.** Deliver the state your scope
   assigns you, satisfying the full-spec requirements that bind it — but do
   NOT design or implement the later subsystems' own mechanisms, and do NOT
   build anything outside your scope.
4. Artifact names gain your subsystem slug as a suffix:
   `PLAN-<subsystem>.md`, `PLAN_VALIDATION-<subsystem>.md`,
   `IMPLEMENTATION_VALIDATION-<subsystem>.md`, `TEST_VALIDATION-<subsystem>.md`.
   Substitute these wherever the per-phase doc names the unsuffixed artifact
   (including any cp command). Module source and test namespaces are SHARED
   and accumulate across subsystems — do not suffix them.
5. Phase 7 runs the FULL test suite — all subsystems' tests — and must keep
   earlier subsystems' tests green.

Without a third argument, everything is exactly as described elsewhere in
this command: unsuffixed artifact names, the whole spec is your scope.

## Retry handling

If a validation artifact already exists from a prior attempt at this phase or a downstream phase, this is a retry (on multi-subsystem runs, substitute the `-<subsystem>` suffixed artifact names):

- **Phase 1 retry** (because Phase 2 failed): `implementations/<challenge_name>/PLAN_VALIDATION.md` exists with FAIL entries. Read it. Revise `PLAN.md` to address every FAIL item explicitly. Update the "Rejected alternatives" section to record what was tried and rejected.
- **Phase 3 retry from impl validation** (because Phase 4 returned minor-fail or major-fail): `implementations/<challenge_name>/IMPLEMENTATION_VALIDATION.md` exists with FAIL entries. Read it. Revise the module source.
- **Phase 5 retry from test validation** (because Phase 6 returned minor-fail or major-fail): `implementations/<challenge_name>/TEST_VALIDATION.md` exists with FAIL entries. Read it. Revise the test source.

## Verdict emission (validation phases only)

Phases 2, 4, 6, 7, full-spec-review, and full-spec-fix emit verdicts as the LAST non-empty line of output. The runner extracts this line; do not put any text after it.

- **Phase 2** (plan validation), **Phase 4** (impl validation), and **Phase 6** (test validation) — three-way verdict:
  ```
  PHASE_VALIDATION:pass
  PHASE_VALIDATION:minor-fail
  PHASE_VALIDATION:major-fail
  ```
  See the artifact template and per-phase doc for the rubric distinguishing minor from major. For Phase 2, minor-fail means the validator fixed PLAN.md directly and the build proceeds; major-fail sends the build back to Phase 1.
- **Phase 7** (finish) — binary verdict reflecting whether tests pass:
  ```
  PHASE_VALIDATION:pass
  PHASE_VALIDATION:fail
  ```
- **full-spec-review** — binary verdict reflecting whether the whole module + test suite satisfies the whole spec (default fail). On fail, the runner invokes full-spec-fix and then re-runs the review fresh.
- **full-spec-fix** — binary verdict reflecting whether the full test suite passes after applying every FAIL item from `FULL_SPEC_REVIEW.md`.

Default to FAIL (or `major-fail` for phases 2, 4, and 6). PASS only after the criteria in the per-phase doc are met.

Other phases (0, decompose, 1, 3, 5) do not emit a verdict — the runner moves on once the output artifact exists.

## Production deployment

After the full phased build passes tests, this module will be:
1. Deployed to a multi-node Rama cluster with 64+ tasks across 8+ workers
2. Serving production traffic from multiple application servers running concurrent clients
3. Operating continuously for months, accumulating unbounded data over time
4. Subject to node failures, network partitions, and processing retries during normal operation

The module must handle all of these correctly. There is no opportunity to fix issues after deployment.
