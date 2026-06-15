# Phase 6: Test Validation

Adversarially validate the test source against `IMPLICIT_SPEC.md` and the protocol contract. Produce `TEST_VALIDATION.md` with a verdict.

**You are an adversarial reviewer. The default verdict is `major-fail`.** PASS only after explicit walk-through for every check shows no violations. The tests you are reviewing were written by someone with a stake in shipping them; your job is to find every way they fail to exercise the contract.

## Inputs

- `<impl-root>/IMPLICIT_SPEC.md`
- The protocol file (read it now, even if you read it earlier)
- The test source files under `<impl-root>/test/`
- The module source (for reference; do not validate it here — that was Phase 4)
- This skill

## Steps

1. Copy the validation template if it doesn't already exist:
   ```bash
   cp <skill-root>/references/artifact-test-validation.md <impl-root>/TEST_VALIDATION.md
   ```
2. Read the protocol in full. Re-read every method's docstring; quote them when checking that tests exercise the documented behavior.
3. Read `IMPLICIT_SPEC.md` in full.
4. Read every test source file in full.
5. Read `references/artifact-test-validation.md` — the template lists the categories of checks to perform.
6. Walk every check item. **Do not tick PASS until you have done explicit walk-through.** For each check:
   - State the check verbatim.
   - Identify the test(s) the check applies to, by file and `deftest` / `testing` block.
   - State whether the test exercises the case being checked.
7. **Self-consistency check.** Re-read your own validation entries. If anywhere in this artifact you wrote that something is "a gap" or "not fully covered" or "this case is missing," that's a FAIL on the corresponding check. Do not pass and contradict yourself in the same artifact.
8. Fill in `TEST_VALIDATION.md` with the results. For each check, state pass or fail with cited evidence.
9. **Emit a verdict** per the rubric in the template's "Verdict" section: `pass`, `minor-fail`, or `major-fail`. Pick the strongest category that applies to any single failure. The verdict determines what happens next — minor-fail leads to a localized fix without re-running this phase, major-fail leads to a fix followed by another pass through this phase.

## Output

`<impl-root>/TEST_VALIDATION.md`, fully filled in with verdict.

## Verdict line

Emit one of `PHASE_VALIDATION:pass`, `PHASE_VALIDATION:minor-fail`, or `PHASE_VALIDATION:major-fail` as the last non-empty line of your output. The artifact contains the detailed reasoning; the verdict line is what the calling system reads to decide the next move.

## Orchestration routing

This is a three-way verdict phase. The orchestrator uses the verdict to decide what happens next:

- **pass** → proceed to Phase 7 (finish).
- **minor-fail** → proceed directly to Phase 7 (finish). Phase 7 absorbs the test fix during its iterate loop — no fresh re-invocation of Phase 5, and no re-run of Phase 6. This avoids paying for a full context switch when only localized line edits are needed.
- **major-fail** → return to Phase 5 (tests) for restructuring. After the fix, Phase 6 re-runs to validate the new test suite.

Up to 3 retry iterations per gate; if the cap is hit, the orchestrator proceeds to Phase 7.

## Do NOT

- Do NOT default to PASS. The default is `major-fail`.
- Do NOT certify any check whose body acknowledges a missing case or weak coverage. Those are FAILs.
- Do NOT skip explicit citation of the test that exercises each case. "The tests cover X" without a deftest/testing reference is not evidence.
- Do NOT run the tests in this phase. Test runs are Phase 7.
- Do NOT pick `minor-fail` when the fix requires adding new test namespaces or restructuring the suite. Those are `major-fail`.
