# Phase 4: Implementation Validation

Adversarially validate the module source against `PLAN.md` and the spec. Produce `IMPLEMENTATION_VALIDATION.md` with a verdict.

**You are an adversarial reviewer. The default verdict is `major-fail`.** PASS only after explicit code-tracing for every check shows no violations. The implementation you are reviewing was written by someone with a stake in shipping it; your job is to find every way it does not match the plan or violates the spec.

## Inputs

- The user-facing spec
- `<impl-root>/PLAN.md`
- The module source file
- This skill

## Steps

1. Copy the validation template if it doesn't already exist:
   ```bash
   cp <skill-root>/references/artifact-impl-validation.md <impl-root>/IMPLEMENTATION_VALIDATION.md
   ```
2. Read the module source in full.
3. Read `PLAN.md` in full. Compare: does the implementation match the plan? Note any divergence.
4. Read `references/artifact-impl-validation.md` — the template lists the categories of checks to perform.
5. Walk every check item. **Do not tick PASS until you have done explicit code-tracing.** For each check:
   - State the check verbatim.
   - Cite the line(s) in the module source the check applies to.
   - Trace through what happens at runtime for that code under the relevant scenario.
   - State whether the trace shows the check is satisfied.
6. **Self-consistency check.** Re-read your own validation entries. If anywhere in this artifact you wrote that something is "a gap" or "doesn't fully solve" or "not ideal" or "this is a tradeoff," that's a FAIL on the corresponding check. Do not pass and contradict yourself in the same artifact.
7. Fill in `IMPLEMENTATION_VALIDATION.md` with the results. For each check, state pass or fail with cited evidence.
8. **Emit a verdict** per the rubric in the template's "Verdict" section: `pass`, `minor-fail`, or `major-fail`. Pick the strongest category that applies to any single failure. The verdict determines what happens next — minor-fail leads to a localized fix without re-running this phase, major-fail leads to a fix followed by another pass through this phase — so a misclassified verdict either lets a bad architectural fix through or wastes a full validation cycle on a one-line change.

## Output

`<impl-root>/IMPLEMENTATION_VALIDATION.md`, fully filled in with verdict.

## Verdict line

Emit one of `PHASE_VALIDATION:pass`, `PHASE_VALIDATION:minor-fail`, or `PHASE_VALIDATION:major-fail` as the last non-empty line of your output. The artifact contains the detailed reasoning; the verdict line is what the calling system reads to decide the next move.

## Orchestration routing

This is a three-way verdict phase. The orchestrator uses the verdict to decide what happens next:

- **pass** → proceed to Phase 5 (tests).
- **minor-fail** → return to Phase 3 (implement) for a localized fix. After the fix, the orchestrator skips re-running Phase 4 on the next pass (the fix is too small to warrant re-validation) and proceeds directly to Phase 5. A misclassified minor-fail (should have been major) lets an architectural problem through without re-validation.
- **major-fail** → return to Phase 3 (implement) for restructuring. After the fix, Phase 4 re-runs to validate the new architecture. A misclassified major-fail (should have been minor) wastes a full validation cycle on a one-line change.

Up to 3 retry iterations per gate; if the cap is hit, the orchestrator proceeds to Phase 5.

## Do NOT

- Do NOT default to PASS. The default is `major-fail`.
- Do NOT certify any check whose body acknowledges a gap, a tradeoff, or a "not ideal." Those are FAILs.
- Do NOT skip code-tracing. "The code handles X" without a line citation and a runtime trace is not evidence.
- Do NOT validate against the plan only — validate against the spec too. The plan can be wrong; if the implementation matches a wrong plan, the spec is still being violated.
