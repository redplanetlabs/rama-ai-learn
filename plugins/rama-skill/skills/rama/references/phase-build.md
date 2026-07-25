# Easy Build (collapsed path)

Do everything phases 3 through 7 do — implement, validate the implementation, write tests, validate the tests, and iterate until the suite passes — but in ONE session instead of separate fresh-context ones. This path is taken when plan-validation classified the subproblem **easy**: the plan is settled and mechanical, so the work does not need the fresh-context round-trips between steps. You still do every step; you do NOT re-open the design.

This is a single long-running agent session. You have the plan and references in context, you write the module and tests, run the suite, and fix as needed — no round-trip to another phase.

## Inputs

- `<impl-root>/PLAN.md` — the validated design. Adhere to it; do NOT diverge unless it has an outright correctness bug ("easier another way" is not a reason).
- `<impl-root>/IMPLICIT_SPEC.md` — edge cases and invariants.
- The user-facing spec and any protocol/contract files.
- The `rama` skill (load it with Skill), plus the reference files for the mechanisms the plan uses.

## Steps

Read and follow each phase doc in order, doing its work in this session before moving to the next. Fix what each validation step finds directly rather than emitting a verdict and stopping.

1. `references/phase-3-implement.md` — implement.
2. `references/phase-4-impl-validate.md` — validate the implementation.
3. `references/phase-5-tests.md` — write tests.
4. `references/phase-6-test-validate.md` — validate the tests.
5. `references/phase-7-finish.md` — iterate until the suite passes.

## Budget

Bounded wall-clock. If it runs out, exit cleanly and emit the verdict for whatever state you have.

## Output

- The module source (`<impl-root>/src/<name>/module.clj`), final.
- The test sources (`<impl-root>/test/...`), final.
- The verdict line.

## Verdict

Emit one of these as the LAST non-empty line of your output:

- `PHASE_VALIDATION:pass` — the suite passed with no failures or errors on the last run.
- `PHASE_VALIDATION:fail` — the suite did not pass. Include a one-paragraph summary of what's still failing and what you tried.

## Orchestration routing

Binary verdict, terminal for this subproblem:

- **pass** → the subproblem is done; the run proceeds (next subproblem, or full-spec review).
- **fail** → the whole run fails, naming the subproblem.

## Do NOT

- Do NOT re-open or revise the design — plan-validation already accepted it. If a failure exposes a genuine design flaw, fix it in the module if at all possible; only if the design is truly wrong, emit `PHASE_VALIDATION:fail` with a clear explanation.
- Do NOT skip the validation steps (2 and 4) just because they are in the same session — you are doing that work yourself, not a fresh reviewer.
- Do NOT delete failing test cases to get a passing suite.
