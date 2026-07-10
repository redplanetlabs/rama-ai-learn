# Decompose: Sub-problem Planning

Partition the module into subsystems, each of which will get its own full plan→implement→test build cycle, in dependency order. Produce `DECOMPOSITION.json`.

**Default: ONE subsystem covering the whole module.** Splitting is the exception. A wrong split is far more expensive than no split — it forces later sessions to build against a boundary that doesn't hold.

## Inputs

- The user-facing spec (e.g. README, problem statement) — read it in full
- Any interfaces, type signatures, or API definitions that define the contract
- `<impl-root>/IMPLICIT_SPEC.md`
- This skill (`SKILL.md`)

## Split rules

Split ONLY when EVERY one of these holds for the proposed boundary:

- **(a) One-directional consumption.** Later subsystems read/extend what earlier ones materialize, never the reverse. The subsystems must form a dependency order.
- **(b) Property-shaped induced requirements.** Each later subsystem's needs on an earlier one are expressible as short, property-shaped requirements on the earlier subsystem. **A requirement states what and how well — never how.** It must be phrased as properties observable at the subsystem boundary — cost bounds (seeks, iterations, latency), balance bounds, consistency/visibility — and must NOT name a partitioning, placement, task, PState/depot shape, data structure, or algorithm, of EITHER subsystem. Litmus test: if only one design could satisfy the requirement as written, it is a design in disguise — rewrite it as the cost/balance property that design was meant to achieve. And **if stating what B needs from A requires designing B, do not split them.**
- **(c) Independently buildable.** Each subsystem is fully buildable and testable given only the subsystems before it.

There is NO cap on subsystem count, but every boundary must pass (a)–(c).

## Coverage requirements

Verify BOTH explicitly before writing the artifact:

- Every operation enumerated in `IMPLICIT_SPEC.md` is assigned to EXACTLY ONE subsystem's spec. A cross-cutting operation (one that touches several subsystems' state) is owned by the LATEST subsystem it touches — that cycle can read and extend everything built before it; whatever it needs from earlier subsystems goes into their specs as induced requirements.
- Every stated constraint/property in the spec is owned by at least one subsystem's spec. NOTHING may fall between subsystems.

## Steps

1. Read the spec, any interface definitions, and `IMPLICIT_SPEC.md` in full.
2. List candidate boundaries. For each, check rules (a)–(c) with a concrete scenario; record the ones you reject and why in the reasoning log.
3. For each surviving boundary, work out the induced requirements: for each later subsystem B that needs something from an earlier subsystem A, state — as a requirement on A, with concrete numbers — what A must support and how well. Justify each by B's WORKLOAD (rates, volumes, latency budgets), never by B's mechanism. Neither A's design nor B's design may appear.
4. Run the coverage requirements above. If anything is unowned, either assign it or collapse the split.
5. Write each subsystem's spec, then the artifact (see Output).

## Output

`<impl-root>/DECOMPOSITION.json` — a JSON array of subsystem objects, in dependency order:

```json
[{"name": "kebab-case-slug",
  "spec": "A complete problem statement for this subsystem's build cycle."},
 ...]
```

Each `"spec"` is a **self-contained mini-spec** — the problem statement its build cycle works from. It MUST contain:

- What to build, and the operations it owns (from `IMPLICIT_SPEC.md`).
- Every requirement and property the subsystem must satisfy — including those induced by later subsystems, stated as properties of THIS subsystem's data and behavior.
- The concrete workload numbers that apply (rates, size bounds, distributions, latency budgets), copied from the global spec. The building session must not have to re-derive workload facts.
- NO design of any later subsystem's mechanism.

Self-sufficiency test before finishing: could a session reading ONLY this `"spec"` (plus this skill) build the subsystem correctly? If not, the spec is incomplete.

A single-subsystem decomposition is one entry; its `"spec"` may defer to the full spec.

No verdict line. This phase is done when the artifact exists and parses as JSON.

## Orchestration routing

After this phase, the build routes on the decomposition:

- **One subsystem** → a single build cycle for the whole module, identical to a build without decomposition (unsuffixed artifact names). If the decomposition is absent or names a single subsystem, the build proceeds as one cycle.
- **n > 1 subsystems** → one full plan→implement→test cycle per subsystem, in the listed order. Each cycle builds against its entry's `"spec"` and uses slug-suffixed validation artifacts; module source and tests are shared and accumulate.

## Do NOT

- Do NOT split to make subsystems "smaller" or "cleaner" — split only when rules (a)–(c) all hold.
- Do NOT put design in ANY spec — not this subsystem's, not an earlier one's induced requirements. Decomposition produces requirements; the plan phases produce designs, using costing machinery (alternatives, partitioning tables) that this phase does not perform. A design embedded here bypasses every quality gate the process has.
- Do NOT write induced requirements that prescribe a later subsystem's mechanism. If you can't state the need as a property of the earlier subsystem's data, do not split.
- Do NOT omit workload numbers from a `"spec"` — a spec whose builder must consult the global spec for rates and bounds is incomplete.
- Do NOT leave any operation or stated constraint unassigned.
- Do NOT design PStates, depots, or topologies in this phase — that is the plan phase's job, once per subsystem.
