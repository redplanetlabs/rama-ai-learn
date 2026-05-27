# Phase 0: Derive Implicit Spec

Produce `IMPLICIT_SPEC.md` capturing the operations, entities, latency requirements, and edge cases implied by the user-facing spec but not explicitly enumerated.

## Inputs

- The user-facing spec (e.g. README, problem statement)
- Any protocols, type signatures, or interfaces that define the contract
- This skill (`SKILL.md`)

## Steps

1. Copy the template if it doesn't already exist:
   ```bash
   cp <skill-root>/references/artifact-implicit-spec.md <impl-root>/IMPLICIT_SPEC.md
   ```
2. Read `references/artifact-implicit-spec.md` — the template contains all instructions for what to fill in.
3. Fill in `IMPLICIT_SPEC.md`. Cover every operation, every entity, every state transition, every edge case the spec implies. The spec is rarely complete; the implicit spec captures what the implementation must handle even when not stated directly.

## Output

`<impl-root>/IMPLICIT_SPEC.md`, fully filled in. Empty sections are not acceptable.

## Do NOT

- Do NOT design PStates, depots, or topologies in this phase. Phase 0 is requirements analysis only — the design happens in Phase 1.
- Do NOT proceed to Phase 1 until `IMPLICIT_SPEC.md` is complete.
