# Full-Spec Review

A final, whole-spec gate that runs after the last build cycle's tests pass — ALWAYS, even when the module was built as a single subsystem. It has two session types: the **review session** (adversarial audit of the entire module + test suite against the entire original spec) and the **fix session** (apply the review's required fixes and get the suite green). The orchestrator alternates them: review → (on fail) fix → fresh review, up to a bounded number of rounds.

## Review session

**You are an adversarial reviewer with fresh eyes and no attachment to the implementation. The default verdict is FAIL.** Emit pass only after actively hunting for gaps and finding none.

### Inputs

- The user-facing spec (e.g. README, problem statement) — read it in full, even if you have seen it before
- Every interface or contract file the spec references
- `<impl-root>/IMPLICIT_SPEC.md`
- `<impl-root>/DECOMPOSITION.json` (when present)
- The ENTIRE module source
- The ENTIRE test suite
- This skill (`SKILL.md`)

### Checks

Check the whole original spec — not any single subsystem's slice:

- **Every operation the spec defines**: implemented, and its full contract exercised by tests.
- **Every numbered constraint/property in the spec**: satisfied by the module and covered by at least one test. Quote the constraint verbatim; cite the module lines and the test that exercise it.
- **Cross-subsystem interactions and seams** (when the module was decomposed): requirements no single subsystem fully owned are where gaps hide. Trace at least one concrete scenario across each seam.
- **Fault-tolerance, concurrency, and performance requirements**, not just functional ones.

You MAY run the test suite and REPL checks (foreground, generous timeout) to confirm or dismiss a suspicion.

### Output

Write `<impl-root>/FULL_SPEC_REVIEW.md` from scratch — overwrite any prior round's file; this is a fresh review, not an append:

```markdown
# Full-Spec Review

Verdict: pass | fail
Summary: <one paragraph>

## Items
<!-- omit this section when the verdict is pass -->
| Location | Why it violates the spec | Required fix |
|---|---|---|
```

Every item must name a concrete location (file + form/section), why it violates the spec, and the required fix. On fail, a fix session will apply exactly what this table says — write each fix precisely enough to be applied without re-deriving your analysis.

### Verdict

Emit one of these as the LAST non-empty line of your output:

- `PHASE_VALIDATION:pass` — no genuine gaps found after the checks above.
- `PHASE_VALIDATION:fail` — at least one item in `FULL_SPEC_REVIEW.md`.

## Fix session

Runs after a failed review. This is a convergence session in the style of the finish phase:

1. Read `<impl-root>/FULL_SPEC_REVIEW.md`.
2. Apply EVERY "Required fix" item, in the module and/or the tests. Prefer module fixes; change a test only when it asserts something the spec does not require. Do NOT delete or weaken tests to get green.
3. Run the full test suite (foreground, generous timeout). Iterate until it passes with `0 failures, 0 errors`.

### Verdict

Emit one of these as the LAST non-empty line of your output:

- `PHASE_VALIDATION:pass` — all required fixes applied AND the full suite is green.
- `PHASE_VALIDATION:fail` — the suite did not pass on the last invocation. Include a one-paragraph summary of what's still failing.

A fresh review session re-runs after this session either way — do not claim a pass you cannot defend.

## Orchestration routing

- Review **pass** → overall build result: PASS.
- Review **fail** → a fix session runs, then the review re-runs fresh. Up to 3 review→fix rounds; if the review still fails after the last allowed fix, the overall build result is FAIL.
- The review verdict is the sole gate — the fix session's verdict is telemetry (a bad fix is caught by the re-review).

## Do NOT

- Do NOT default to PASS. The default is FAIL.
- Do NOT limit the review to what changed recently or to one subsystem — the unit under review is the whole module against the whole spec.
- Do NOT paraphrase a constraint to make the implementation satisfy it. Quote the spec verbatim.
- Do NOT (in the fix session) delete failing tests, skip required fixes, or extend coverage with new test namespaces — the fix session is convergence, not new scope.
