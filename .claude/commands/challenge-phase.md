---
name: challenge-phase
description: Execute a single phase of a Rama challenge. Invoked by the rama-ai-learn phase-orchestrating runner with `/challenge-phase <name> <phase-id>`. Each invocation does ONE phase only and stops.
arguments:
  - name: challenge_name
    description: Name of the challenge under challenges/
    required: true
  - name: phase_id
    description: Phase to execute (0..7)
    required: true
---

# /challenge-phase <challenge_name> <phase_id>

You are doing **one phase** of a phased Rama module build. The orchestrating runner has invoked you with a fresh context. Do this phase only — do not run any later phase, do not run tests, do not "finish the whole thing."

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
(`=== PHASE <N> attempt <K> — <timestamp> ===`); your entries go below it.

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
  this phase. Validation phases (2, 4, 6, 7): record the reasoning behind
  your verdict before emitting it.

## Phase dispatch

Read the per-phase doc for `<phase_id>` and follow it. Do not read other phase docs.

| phase_id | Per-phase doc | Output artifact |
|---|---|---|
| 0 | `plugins/rama-skill/skills/rama/references/phase-0-implicit-spec.md` | `implementations/<challenge_name>/IMPLICIT_SPEC.md` |
| 1 | `plugins/rama-skill/skills/rama/references/phase-1-plan.md` | `implementations/<challenge_name>/PLAN.md` |
| 2 | `plugins/rama-skill/skills/rama/references/phase-2-plan-validate.md` | `implementations/<challenge_name>/PLAN_VALIDATION.md` |
| 3 | `plugins/rama-skill/skills/rama/references/phase-3-implement.md` | `implementations/<challenge_name>/src/<challenge_name>/module.clj` |
| 4 | `plugins/rama-skill/skills/rama/references/phase-4-impl-validate.md` | `implementations/<challenge_name>/IMPLEMENTATION_VALIDATION.md` |
| 5 | `plugins/rama-skill/skills/rama/references/phase-5-tests.md` | `implementations/<challenge_name>/test/...` |
| 6 | `plugins/rama-skill/skills/rama/references/phase-6-test-validate.md` | `implementations/<challenge_name>/TEST_VALIDATION.md` |
| 7 | `plugins/rama-skill/skills/rama/references/phase-7-finish.md` | passing tests; module + tests modified in place |

The implementation root is `implementations/<challenge_name>/` — substitute this for `<impl-root>` in any cp command in the per-phase doc.

The skill root is `plugins/rama-skill/skills/rama/` — substitute this for `<skill-root>` in any cp command in the per-phase doc.

## Retry handling

If a validation artifact already exists from a prior attempt at this phase or a downstream phase, this is a retry:

- **Phase 1 retry** (because Phase 2 failed): `implementations/<challenge_name>/PLAN_VALIDATION.md` exists with FAIL entries. Read it. Revise `PLAN.md` to address every FAIL item explicitly. Update the "Rejected alternatives" section to record what was tried and rejected.
- **Phase 3 retry from impl validation** (because Phase 4 returned minor-fail or major-fail): `implementations/<challenge_name>/IMPLEMENTATION_VALIDATION.md` exists with FAIL entries. Read it. Revise the module source.
- **Phase 5 retry from test validation** (because Phase 6 returned minor-fail or major-fail): `implementations/<challenge_name>/TEST_VALIDATION.md` exists with FAIL entries. Read it. Revise the test source.

## Verdict emission (validation phases only)

Phases 2, 4, 6, and 7 emit verdicts as the LAST non-empty line of output. The runner extracts this line; do not put any text after it.

- **Phase 2** (plan validation) — binary verdict:
  ```
  PHASE_VALIDATION:pass
  PHASE_VALIDATION:fail
  ```
- **Phase 4** (impl validation) and **Phase 6** (test validation) — three-way verdict:
  ```
  PHASE_VALIDATION:pass
  PHASE_VALIDATION:minor-fail
  PHASE_VALIDATION:major-fail
  ```
  See the artifact template for the rubric distinguishing minor from major.
- **Phase 7** (finish) — binary verdict reflecting whether tests pass:
  ```
  PHASE_VALIDATION:pass
  PHASE_VALIDATION:fail
  ```

Default to FAIL (or `major-fail` for phases 4 and 6). PASS only after the criteria in the per-phase doc are met.

Other phases (0, 1, 3, 5) do not emit a verdict — the runner moves on once the output artifact exists.

## Production deployment

After the full phased build passes tests, this module will be:
1. Deployed to a multi-node Rama cluster with 64+ tasks across 8+ workers
2. Serving production traffic from multiple application servers running concurrent clients
3. Operating continuously for months, accumulating unbounded data over time
4. Subject to node failures, network partitions, and processing retries during normal operation

The module must handle all of these correctly. There is no opportunity to fix issues after deployment.
