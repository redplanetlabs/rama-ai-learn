# Phase 2: Plan Validation

Adversarially validate `PLAN.md` against the spec. Produce `PLAN_VALIDATION.md` with a verdict.

**You are an adversarial reviewer. The default verdict is FAIL.** PASS only after explicit scenario-tracing for every constraint shows no violations. The plan you are reviewing was written by someone with a stake in shipping it; your job is to find every way it does not satisfy the spec.

## Inputs

- The user-facing spec (read it now, even if you read it earlier)
- `<impl-root>/IMPLICIT_SPEC.md`
- `<impl-root>/PLAN.md`
- This skill

## Steps

1. Copy the validation template if it doesn't already exist:
   ```bash
   cp <skill-root>/references/artifact-plan-validation.md <impl-root>/PLAN_VALIDATION.md
   ```
2. Read the spec in full. Re-read every constraint, prohibition, and performance requirement. Do not paraphrase — quote the exact wording when checking compliance.
3. Read `PLAN.md` in full.
4. Read `references/artifact-plan-validation.md` — the template lists the categories of checks to perform.
5. Walk every check item. **Do not tick PASS until you have done explicit scenario-tracing.** For each constraint in the spec:
   - State the constraint verbatim.
   - Walk through a concrete scenario that exercises it (specific values, specific entity counts, specific timing).
   - Cite the line(s) in `PLAN.md` that handle this scenario.
   - State whether the cited mechanism actually handles it. Do NOT accept "the plan addresses this" without a citation.
6. **Self-consistency check.** Re-read your own validation entries. If anywhere in this artifact you wrote that something is "a gap" or "doesn't fully solve" or "not ideal" or "this is a tradeoff," that's a FAIL on the corresponding check. Do not pass and contradict yourself in the same artifact.
7. Fill in `PLAN_VALIDATION.md` with the results. For each check, state pass or fail with cited evidence.

## Output

`<impl-root>/PLAN_VALIDATION.md`, fully filled in.

## Verdict

Emit one of `PHASE_VALIDATION:pass`, `PHASE_VALIDATION:minor-fail`, or `PHASE_VALIDATION:major-fail` as the last non-empty line of your output. Default to major-fail.

- **pass** — every check passes after honest scenario-tracing.
- **minor-fail** — failures are fixable by specific, localized edits to `PLAN.md`. You can see exactly what lines to change. After emitting the verdict, fix the plan directly: edit `PLAN.md` to address every FAIL item yourself. Then proceed.
- **major-fail** — at least one failure requires rethinking the architecture. Return to Phase 1 for redesign.

When unsure between minor and major, choose **major-fail**.

## Orchestration routing

This is a three-way verdict phase. The orchestrator uses the verdict to decide what happens next:

- **pass** → proceed to Phase 3 (implement).
- **minor-fail** → the validator fixes `PLAN.md` directly and proceeds to Phase 3. No return to Phase 1, no re-validation.
- **major-fail** → return to Phase 1 (plan) for revision. The plan author reads `PLAN_VALIDATION.md` and addresses every FAIL item. Up to 3 retry iterations; if the cap is hit, the orchestrator proceeds to Phase 3 with the best plan so far.

## Do NOT

- Do NOT default to PASS. The default is FAIL.
- Do NOT paraphrase a constraint to make the plan satisfy it. Quote the spec verbatim and check the plan against the verbatim text.
- Do NOT certify any check whose body says "this is a gap" or equivalent. Those are FAILs.
- Do NOT skip scenario-tracing. "The plan addresses constraint X" is not evidence — a citation to specific lines that handle a specific scenario is.
