# Full-Spec Review

A final, whole-spec gate that runs after the last build cycle's tests pass — ALWAYS, even when the module was built as a single subsystem.

**This is ONE session that both finds defects and fixes them.** Review the whole module against the whole spec, fix everything you find, verify the fixes, then look again. Repeat until a full pass turns up nothing new and the suite is green. There is no separate fix session and no round limit — you are done when the work is done, not when a counter runs out.

**You are an adversarial reviewer with fresh eyes and no attachment to the implementation. The default verdict is FAIL.** Emit pass only after a full pass over the whole spec finds nothing.

## Inputs

Read all of these in full before writing anything, even if you have seen them before:

- The user-facing spec (e.g. README, problem statement)
- Every interface or contract file the spec references
- `<impl-root>/IMPLICIT_SPEC.md`
- `<impl-root>/DECOMPOSITION.json` (when present)
- The ENTIRE module source
- The ENTIRE test suite
- This skill (`SKILL.md`)

## Process

Loop these three steps until step 1 finds nothing:

**1. Hunt.** Check the whole original spec — not any single subsystem's slice:

- **Every operation the spec defines**: implemented, and its full contract exercised by tests.
- **Every numbered constraint/property in the spec**: satisfied by the module and covered by at least one test. Quote the constraint verbatim; cite the module lines and the test that exercise it.
- **Cross-subsystem interactions and seams** (when the module was decomposed): requirements no single subsystem fully owned are where gaps hide. Trace at least one concrete scenario across each seam.
- **Fault-tolerance, concurrency, and performance requirements**, not just functional ones.
- **Boundary and malformed inputs on every operation**: a suite that enumerates a class of invalid inputs and omits one member of that class is a gap, not coverage.

Run the test suite and REPL checks (foreground, generous timeout) to confirm or dismiss a suspicion. A green suite is not evidence of correctness — it is the starting point, not the finding.

**2. Fix.** Apply every item you found. Prefer module fixes; change a test only when it asserts something the spec does not require. Then run the full suite and iterate until it passes with `0 failures, 0 errors`.

**3. Re-verify.** Confirm each fix actually holds, by the evidence you would demand of someone else's fix — not by the fact that you wrote it. A fix can introduce a defect worse than the one it closed, so treat your own changes as new surface to attack in the next hunt.

Then hunt again. Each pass starts from the whole spec, not from what you changed.

## Output

Write `<impl-root>/FULL_SPEC_REVIEW.md`, recording the whole session:

```markdown
# Full-Spec Review

Verdict: pass | fail
Summary: <one paragraph — what you hunted, what you found, what you fixed>

## Items found and fixed
| Location | Why it violated the spec | Fix applied | Evidence it holds |
|---|---|---|---|

## Items outstanding
<!-- omit this section when the verdict is pass -->
| Location | Why it violates the spec | Required fix | Why it is unresolved |
|---|---|---|---|
```

Every row names a concrete location (file + form/section) and quotes the spec clause it bears on.

## Verdict

Emit one of these as the LAST non-empty line of your output:

- `PHASE_VALIDATION:pass` — a full pass over the whole spec found nothing new, every item found this session is fixed and verified, and the full suite is green.
- `PHASE_VALIDATION:fail` — anything else. Fill in "Items outstanding" with what remains and what you know about it.

## Do NOT

- Do NOT default to PASS. The default is FAIL.
- Do NOT stop at the first clean-looking pass over a subsystem — the unit under review is the whole module against the whole spec.
- Do NOT report a green suite as a result. It is a precondition for the verdict, not evidence for it.
- Do NOT paraphrase a constraint to make the implementation satisfy it. Quote the spec verbatim.
- Do NOT delete or weaken failing tests to get green, and do NOT extend coverage with new test namespaces — closing a coverage gap means testing the uncovered case, not opening new scope.
- Do NOT trust your own fix because you wrote it. Re-verify it as adversarially as you found the defect.
