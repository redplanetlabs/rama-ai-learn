# Decompose: Sub-problem Planning

Split one large problem into an ordered sequence of build stages, each doing a SUBSET of the full spec. Produce `DECOMPOSITION.json`.

**Split hard parts apart.** The purpose of decomposition is focus: a build cycle should face ONE hard problem at a time. When the problem contains multiple hard parts, you MUST separate them. A problem with a single hard core stays ONE stage. Parts too trivial to deserve a build cycle fold into the part that uses them. Order the stages so later ones build on the state earlier ones create.

Inputs: the user-facing spec, any interface/contract files, `<impl-root>/IMPLICIT_SPEC.md`, this skill.

## Principles

**1. The full spec is the only spec.** Every build cycle reads the full spec; every requirement, number, table, distribution, edge case, and bound lives there and ONLY there. A stage's scope duplicates NOTHING from the full spec — no restated requirements, no copied numbers, no paraphrases. Where a scope must invoke a requirement, it points at it (a section name or short quoted phrase). A paraphrase drops information, and a dropped clause silently unbinds a requirement.

**2. A scope says WHAT to figure out, never HOW.** Each stage's scope lists:
- The operations of the full spec the stage owns. Each operation belongs to exactly one stage; a cross-cutting operation goes to the latest stage it touches.
- The state the stage must figure out — which PStates, depots, and query topologies it must design, named by role. State whose only consumers are later stages belongs to the stage that WRITES it: that state is the stage's deliverable interface, and figuring it out is this stage's work, not the consumer's.

A scope names purpose, never design: it may say what a piece of state is for, pointing at the full-spec requirements that bind it ("the state supporting <full-spec property> for later stages"); it never prescribes schemas, keys, partitioning, placement, topology types, dataflow, or any other mechanism choice.

**3. Requirements bind by reference.** Later stages' needs are never rewritten into earlier scopes as new requirements — the full spec already states them; the earlier scope points to the full-spec requirements its state must make achievable. Never judge whether a requirement can be satisfied — that is the build cycles' job, and their gates enforce the spec. Reject a boundary only when a stage's deliverable cannot be stated as a scope, never because the work looks hard.

## Output

`<impl-root>/DECOMPOSITION.json` — a JSON array of `{"name": "kebab-case-slug", "scope": "..."}` in dependency order. A single-stage decomposition is one entry whose scope is the whole spec.

## Checklist

Verify each item before finishing; record failures and fixes in the reasoning log.

- Every hard part of the problem has its own stage; no trivial part left unfolded.
- NOTHING duplicated: scan every scope for requirement text, numbers, tables, distributions, edge cases, or bounds restated from the full spec — replace each with a pointer to the spec.
- Every operation owned exactly once; the scopes together cover every operation of the full spec.
- Every piece of state a later stage consumes appears in the scope of the earlier stage that writes it, with a pointer to the full-spec requirements that bind it.
- No design anywhere: no schemas, keys, partitioning, placement, topology types, dataflow, or mechanism named as a choice.
- No boundary rejected for looking hard to satisfy.

This phase is done when the artifact exists, parses as JSON, and every check passes. No verdict line.
