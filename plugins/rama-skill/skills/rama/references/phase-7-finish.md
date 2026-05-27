# Phase 7: Finish

Run the tests written in Phase 5 against the module written in Phase 3. Iterate on both until the test suite passes, then stop.

This is a single long-running agent session. You have the full implementation, tests, and references already in context. You edit the module source, edit the test sources, and run `clojure -X:test` directly to get test output. No round-trip to another phase.

## Pre-loop

If `<impl-root>/TEST_VALIDATION.md` exists with FAIL items, you arrived here from a phase-6 minor-fail. Before running the test suite, read that file and apply each FAIL item's fix to the test sources. These are localized fixes flagged by the test-validation phase; address them all, then proceed to the loop below.

## Loop

1. Run the test suite (e.g. `clojure -X:test` from the project's test root, or whatever command the project uses). Capture stdout and stderr.
2. If tests pass with `0 failures, 0 errors`, exit the loop. Do not load any other context — you are done.
3. If tests fail, load the context you need to fix them. You have NOT loaded any of this yet:
   - `<impl-root>/PLAN.md` — the design you must adhere to.
   - `<impl-root>/IMPLICIT_SPEC.md` — edge cases and invariants.
   - `<impl-root>/IMPLEMENTATION_VALIDATION.md` and `<impl-root>/TEST_VALIDATION.md` — what was already checked.
   - `<impl-root>/src/<name>/module.clj` and the test sources under `<impl-root>/test/`.
   - The `rama` skill (load it with Skill).
   - Reference files for the bug class — at minimum `references/troubleshooting.md` and `references/testing.md`, plus subsystem-specific references as needed (`references/stream.md`, `references/microbatch.md`, `references/aggregators.md`, `references/paths.md`, `references/task-globals.md`, `references/mirrors.md`, etc.).
4. Read the failure output carefully. Identify the root cause — do not chase symptoms.
5. Edit the module source or the test source as needed. The module is the source of truth for protocol behavior; tests are the spec. Prefer module fixes unless a test is clearly wrong (e.g. it assumes a behavior the protocol doesn't require, or it skips synchronization).
6. Re-run the test suite. Go to step 2. On subsequent failures, you have the context loaded — only load additional references as new bug classes appear.

## Budget

This phase has a bounded budget (wall-clock and/or test-suite invocations). When the budget runs out, exit cleanly with whatever state you have — emit the verdict described below.

## Verdict

Emit one of these as the LAST non-empty line of your output:

- `PHASE_VALIDATION:pass` — the test suite ran cleanly with `0 failures, 0 errors` on the last invocation.
- `PHASE_VALIDATION:fail` — the test suite did not pass on the last invocation. Include a one-paragraph summary of what's still failing and what you tried.

## Output

- The module source (`<impl-root>/src/<name>/module.clj`) in its final state.
- The test sources (`<impl-root>/test/...`) in their final state.
- The verdict line.

## Do NOT

- Do NOT delete failing test cases to make the suite pass.
- Do NOT escalate to "this needs a plan revision" — the design is settled by this point. If a test failure exposes a design-level problem, fix it in the module if at all possible. If the design is genuinely wrong, emit `PHASE_VALIDATION:fail` with a clear explanation.
- Do NOT add new test namespaces to extend coverage. Phase 5 owned coverage; this phase is convergence.
