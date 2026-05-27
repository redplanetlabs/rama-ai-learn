# Test Validation

<!-- Phase 6. Fill in after tests are written in Phase 5. -->

Review the test source files. For each check, state pass or fail with evidence. Then emit one of three verdicts at the end of this artifact, per the rubric below.

## Minimize IPC launches
<!-- Each create-ipc + launch-module! adds 30+ seconds. Default to one deftest with testing blocks for organization. Justify every additional deftest by naming the specific shared mutable state that would interfere — "different operation" or "different concern" is not a valid justification. Scenarios on disjoint keys do not interfere and belong in one deftest. -->

## Implicit spec coverage
<!-- Read IMPLICIT_SPEC.md and verify every edge case and entity state × write combination is tested. List each one and the test that covers it. If any are missing, add tests. -->

## Synchronization
<!-- Every write that precedes a read must be followed by `(harness/wait-for-processing! client)` before the read. Verify no write-then-read sequences skip the wait. -->

## Test namespaces compile
<!-- Every test namespace must load cleanly. Verify imports, `:refer` entries on record constructors (e.g. `->FooRecord`), and that no test depends on a private namespace. -->

## Verdict

Emit exactly one of `pass`, `minor-fail`, or `major-fail`, with a one-sentence justification.

- **pass**: every check above passed.
- **minor-fail**: at least one check failed, but every failure is fixable by editing specific lines in existing test namespaces.
- **major-fail**: at least one failure requires more significant changes or restructuring. Pick `major-fail` whenever you are unsure between minor and major.

Pick the strongest category that applies to any single failure. If one finding is major, the verdict is `major-fail` even if other failures are minor.
