# Phases

Building a Rama module follows a phased process. Each phase produces a required artifact; skipping artifacts leads to wrong PState schemas, wasted disk I/O, and topologies that need to be rewritten.

| Phase | Doc | Artifact |
|---|---|---|
| 0 | [`phase-0-implicit-spec.md`](phase-0-implicit-spec.md) | `IMPLICIT_SPEC.md` |
| 1 | [`phase-1-plan.md`](phase-1-plan.md) | `PLAN.md` |
| 2 | [`phase-2-plan-validate.md`](phase-2-plan-validate.md) | `PLAN_VALIDATION.md` (verdict: pass/fail) |
| 3 | [`phase-3-implement.md`](phase-3-implement.md) | module source file |
| 4 | [`phase-4-impl-validate.md`](phase-4-impl-validate.md) | `IMPLEMENTATION_VALIDATION.md` (verdict: pass/minor-fail/major-fail) |
| 5 | [`phase-5-tests.md`](phase-5-tests.md) | test source files |
| 6 | [`phase-6-test-validate.md`](phase-6-test-validate.md) | `TEST_VALIDATION.md` (verdict: pass/minor-fail/major-fail) |
| 7 | [`phase-7-finish.md`](phase-7-finish.md) | passing tests (agent runs tests in a loop) |

## Execution model

One phase per agent session, except Phase 7 which is a single long-running session in which the agent iterates on tests until they pass. Each phase is a fresh context, reads exactly one phase doc, and stops. The fresh-context isolation for phases 0–6 is intentional — agents that walk multiple phases in one session anchor on early design decisions and fail to revise them when later thinking surfaces problems.

Validation phases (2, 4, 6) emit a verdict as the last line of agent output. Phase 2's verdict is binary (`pass` / `fail`); on fail the calling system re-invokes Phase 1. Phases 4 and 6 emit a three-way verdict (`pass` / `minor-fail` / `major-fail`):

- `pass`: advance to the next phase.
- `minor-fail`: re-invoke the prior phase to apply the fix, then skip this validation phase next time around. The fix is small enough that another fresh-context adversarial review is not worth the round-trip cost.
- `major-fail`: re-invoke the prior phase to apply the fix, then re-run this validation phase. The fix changes architecture; the new architecture needs its own review.

## Cross-phase rules

- Every phase produces a visible artifact. If a phase produces no artifact, the phase has been skipped.
- Validation phases (2, 4, 6) default to FAIL. PASS only after explicit scenario-tracing.
- Worker restart does NOT replay depot history. Topologies resume from their committed offset. Any non-durable state (TaskGlobals, in-memory caches) must have a concrete rebuild path that runs on worker restart, cited in the plan.
- PState write volume per source event must be bounded by inputs the application controls. If a write scales with the size of an unbounded external set (recipients, subscribers, members, etc.), PState is the wrong storage class — use a TaskGlobal or a different design.

The skill-wide design rules (always-on production design, single-threaded task model, cooperative multitasking, performance costs, the "never trade X for code simplicity" rules) live in `SKILL.md`. Read it before starting Phase 0.
