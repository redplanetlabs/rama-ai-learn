# Phases

Building a Rama module follows a phased process. Each phase produces a required artifact; skipping artifacts leads to wrong PState schemas, wasted disk I/O, and topologies that need to be rewritten.

| Phase | Doc | Artifact |
|---|---|---|
| 0 | [`phase-0-implicit-spec.md`](phase-0-implicit-spec.md) | `IMPLICIT_SPEC.md` |
| decompose | [`phase-decompose.md`](phase-decompose.md) | `DECOMPOSITION.json` |
| 1 | [`phase-1-plan.md`](phase-1-plan.md) | `PLAN.md` |
| 2 | [`phase-2-plan-validate.md`](phase-2-plan-validate.md) | `PLAN_VALIDATION.md` (verdict: pass/minor-fail/major-fail) |
| build | [`phase-build.md`](phase-build.md) | module source, `IMPLEMENTATION_VALIDATION.md`, tests, `TEST_VALIDATION.md`; passing suite (verdict: pass/fail) |
| full-spec-review | [`phase-full-spec-review.md`](phase-full-spec-review.md) | `FULL_SPEC_REVIEW.md` (verdict: pass/fail; a fix session runs between failed reviews) |

The `build` phase does the implementation and testing work in one session, following [`phase-3-implement.md`](phase-3-implement.md), [`phase-4-impl-validate.md`](phase-4-impl-validate.md), [`phase-5-tests.md`](phase-5-tests.md), [`phase-6-test-validate.md`](phase-6-test-validate.md), and [`phase-7-finish.md`](phase-7-finish.md) in order — those docs describe each step's work but are no longer run as separate sessions.

## Execution model

Phases 0, decompose, 1, and 2 each run as a fresh-context session that reads one phase doc and stops. Their fresh-context isolation is intentional for the design stages — an agent that plans and validates in one session anchors on its early choices. The `build` phase is then one long-running session that implements, validates, tests, and iterates to a green suite.

The decompose stage (after Phase 0) partitions the module into one or more subsystems in dependency order — see `phase-decompose.md`; each subsystem is a scoped subset of the full spec (the full spec stays the sole source of requirements), and the default is one subsystem covering the whole module. Each subsystem runs Phase 1 (plan) and Phase 2 (plan-validation), then its `build` session (`phase-build.md`), producing each step's artifact. Each subsystem builds on the subsystems before it. On multi-subsystem builds the artifacts carry a `-<subsystem>` suffix; the module source and test namespaces are shared and accumulate, and each subsystem's build runs the full suite so earlier subsystems' tests stay green.

After the last subsystem's build passes, the full-spec-review stage ALWAYS runs (even for a single-subsystem build): an adversarial fresh-context audit of the entire module and test suite against the entire original spec, with bounded review→fix rounds — see `phase-full-spec-review.md`. The overall build passes only when every subsystem's build passes AND the full-spec review passes.

Phase 2 emits a three-way verdict (`pass` / `minor-fail` / `major-fail`): on major-fail the calling system re-invokes Phase 1; on minor-fail the validator fixes the plan directly and proceeds; on pass it proceeds. The `build` phase emits a binary verdict (`pass` / `fail`).

## Cross-phase rules

- Every phase produces a visible artifact. If a phase produces no artifact, the phase has been skipped.
- Validation phases (2, 4, 6) default to FAIL. PASS only after explicit scenario-tracing.
- Worker restart does NOT replay depot history. Topologies resume from their committed offset. Non-durable state (TaskGlobals, in-memory caches) is lost on restart or module update — acceptable when rebuildable from durable state, or when the spec tolerates losing it.
- PState write volume per source event must be bounded by inputs the application controls. If a write scales with the size of an unbounded external set (recipients, subscribers, members, etc.), PState is the wrong storage class — use a TaskGlobal or a different design.

The skill-wide design rules (always-on production design, single-threaded task model, cooperative multitasking, performance costs, the "never trade X for code simplicity" rules) live in `SKILL.md`. Read it before starting Phase 0.
