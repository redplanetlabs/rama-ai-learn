# Decompose: Sub-problem Planning

Turn one large problem into a sequence of smaller complete problems, each getting its own full plan→implement→test build cycle. Produce `DECOMPOSITION.json`.

**Split hard parts apart.** The purpose of decomposition is focus: a build cycle should face ONE hard problem at a time. When the problem contains multiple hard parts, you MUST separate them — and make each independently buildable by writing requirements (principle 2): REQUIREMENTS, not design. A problem with a single hard core stays ONE sub-problem. Parts too trivial to deserve a build cycle fold into the part that uses them. Order the parts so later ones build on the state earlier ones create.

Inputs: the user-facing spec, any interface/contract files, `<impl-root>/IMPLICIT_SPEC.md`, this skill.

## Principles

**1. A decomposition is a sequence of complete specs.** Each sub-problem's spec is a standalone problem statement: the operations it owns (each operation of the whole belongs to exactly one part; a cross-cutting operation goes to the latest part it touches), their full contracts and edge cases, every requirement it must satisfy, and the workload numbers — all copied in. A build cycle reads only its spec; the parts together must satisfy the whole spec. Decomposition must not LOSE information: every detail of the whole spec — numbers, tables, example distributions, edge cases, exact bounds — appears verbatim in every spec it applies to. Summarizing is losing: a description of a table is not the table.

**2. Dependence is absorbed by requirements.** Sub-problems are never independent — later parts build on earlier parts' state. The specs absorb that dependence: everything a later part demands of earlier state is written into the EARLIER spec as a definite requirement — one obligation, never alternatives — stating what the data must support and how well, under the consuming workload's numbers. Every constraint of the whole spec is owned by the part whose work determines it. This is what makes a dependent part independently buildable.

**3. Specs demand; they never design.** No spec names or assumes a mechanism — its own or another part's. A requirement is justified by workload, never by a strategy some part might use. Symmetrically, never judge whether a requirement can be satisfied — that is the build cycles' job, and their gates enforce the spec. Reject a boundary only when a demand cannot be written as a requirement, never because the requirement looks hard.

## Output

`<impl-root>/DECOMPOSITION.json` — a JSON array of `{"name": "kebab-case-slug", "spec": "..."}` in dependency order. A single-sub-problem decomposition is one entry; its spec may defer to the full spec.

## Checklist

Verify each item before finishing; record failures and fixes in the reasoning log.

- Every hard part of the problem has its own sub-problem.
- Each spec is buildable from itself plus this skill alone.
- No information lost: walk the whole spec detail by detail — every number, table, example distribution, edge case, and stated bound appears verbatim in every spec it applies to. Nothing summarized away.
- Every operation owned exactly once; every constraint owned by the part whose work determines it.
- Every cross-part demand appears in the earlier spec as one definite requirement — no alternatives, no "or".
- No mechanism named or assumed anywhere; no requirement justified by another part's imagined strategy.
- No boundary rejected for looking hard to satisfy; no trivial part left unfolded.

This phase is done when the artifact exists, parses as JSON, and every check passes. No verdict line.
