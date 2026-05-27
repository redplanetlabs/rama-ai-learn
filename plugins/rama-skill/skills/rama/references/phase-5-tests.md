# Phase 5: Write Tests

Write tests covering every protocol method and every edge case from `IMPLICIT_SPEC.md`. Tests will be validated in Phase 6 and run in Phase 7.

## Inputs

- `<impl-root>/IMPLICIT_SPEC.md`
- The module source
- This skill

## Steps

1. Read `references/testing.md` for test patterns, InProcessCluster setup, and partition-aware testing.
2. Write thorough tests of the implementation:
   - Cover every protocol method or public API with positive and negative cases
   - Test edge cases identified in `IMPLICIT_SPEC.md` (empty inputs, boundary values, missing keys, large ranges)
   - Verify correctness invariants (e.g., aggregates are consistent, balances don't go negative, ordering preserved across retries)
   - Use `com.rpl.rama.test` functions directly (`create-ipc`, `launch-module!`, `foreign-depot`, `foreign-pstate`, `foreign-query`, etc.)
   - Synchronize writes before reads: for microbatch, use `wait-for-microbatch-processed-count` after appends
3. Verify each test namespace compiles cleanly by loading it (REPL eval or `clojure -M -e "(require 'your.test-ns :reload)"`). Unresolved imports, missing `:refer` entries on Java record constructors (e.g. `->FooRecord`), or other namespace-load errors must be fixed here. A load-failure surfacing later costs a full retry cycle.

## Output

Test source files under `<impl-root>/test/`.

## Do NOT

- Do NOT run the test suite in this phase. Test runs happen in Phase 7. Writing tests and running tests are separate phases so flaky test failures don't pollute the test-writing context.
- Do NOT fill in `TEST_VALIDATION.md` in this phase — that is Phase 6's artifact.
- Do NOT write smoke tests that only confirm the happy path. Tests must catch real implementation mistakes — boundary values, retry behavior, ordering invariants, edge cases from the implicit spec.
