# Decompose: Sub-problem Planning

Split the module into sub-problems, each getting its own full plan→implement→test build cycle, in dependency order. Each sub-problem's spec must stand alone as a complete problem statement: a session reading only that spec builds that part correctly, and the parts together satisfy the whole spec. Produce `DECOMPOSITION.json`.

**Default: ONE subsystem.** Split only where the problem naturally layers — later sub-problems read and build on what earlier ones store, never the reverse. Do not split out trivial parts whose design is obvious; a build cycle is expensive — fold them into the sub-problem that uses them.

Inputs: the user-facing spec, any interface/contract files, `<impl-root>/IMPLICIT_SPEC.md`, this skill.

## Principles

1. **The burden stays with the data owner.** Every demand a later sub-problem places on an earlier one goes in the EARLIER spec, as a definite requirement: what must be readable, at what cost and balance, at the consumer's workload rates.
2. **A requirement is never an alternative.** "Or" has no place in a requirement — an either/or lets the data owner pass its burden downstream. State one obligation.
3. **Requirements come from workload numbers, never from an imagined design.** Do not assume a strategy the later sub-problem will use — if it needs a derived value, its plan phase can read the data. Never name a mechanism (PState, depot, partitioner, structure, strategy) of either sub-problem.
4. **Complete coverage.** Every operation in `IMPLICIT_SPEC.md` is owned by exactly one sub-problem (a cross-cutting operation goes to the latest it touches). Every stated constraint lives in the spec of the sub-problem whose design determines it.
5. **Self-sufficient specs.** Each spec carries: its operations with full contracts and edge cases; every requirement it must satisfy, including burdens induced by later sub-problems; all workload numbers copied in; and what earlier sub-problems provide, restated as the obligations their specs carry. No design anywhere — that is each cycle's plan phase.

## Output

`<impl-root>/DECOMPOSITION.json` — a JSON array of `{"name": "kebab-case-slug", "spec": "..."}` in dependency order. A single-subsystem decomposition is one entry; its spec may defer to the full spec.

## Validation checklist

Check every item before finishing; fix and re-check until all pass. Record failures and fixes in the reasoning log.

- Each spec passes the self-sufficiency test: buildable from that spec plus this skill alone.
- No requirement contains an alternative ("or") that shifts a burden between sub-problems.
- No requirement is justified by how a later sub-problem might work.
- No mechanism named anywhere.
- Every operation owned exactly once; every constraint owned by the sub-problem whose design determines it; nothing unowned.
- No trivial sub-problem that should be folded into its consumer.
- If the result is ONE subsystem for a spec with several distinct workloads: the reasoning log shows attempted boundary requirements that failed these principles — not an assertion of impossibility.

This phase is done when the artifact exists, parses as JSON, and every check passes. No verdict line.

## Orchestration routing

- **One subsystem** (or missing/malformed artifact) → a single build cycle for the whole module, identical to a build without decomposition (unsuffixed artifact names).
- **n > 1** → one full build cycle per sub-problem, in listed order; each cycle builds against its entry's `"spec"` with slug-suffixed validation artifacts; module source and tests are shared and accumulate.
