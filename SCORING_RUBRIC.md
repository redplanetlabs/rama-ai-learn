# Rama Challenge Scoring Rubric

Scores range from 1 (poor) to 5 (excellent).

## Dimensions

### 1. Structural Alignment (all challenges)

Measures how closely the approach maps to the reference implementation's
structure. This dimension penalises unnecessary complexity, not valid
alternatives. A different but equivalent approach should score 4-5; a
more complex approach that achieves the same result should score lower.

| Score | Anchor |
|-------|--------|
| 5 | Approach is structurally equivalent to the reference or simpler. Any differences are purely stylistic. |
| 4 | Minor structural difference (e.g., one extra intermediate PState, or a slightly different path decomposition) with no complexity cost. |
| 3 | Noticeably more complex than the reference (extra topology, additional indirection layer, more states to reason about) but arrives at a correct result. |
| 2 | Substantially more complex than the reference in a way that introduces risk or maintenance burden: redundant topologies, unnecessary coordination, extra depot. |
| 1 | Approach is architecturally divergent from the reference in a way that is harder to reason about, harder to extend, or requires more code to accomplish the same thing. |
| 0 | Reference implementation is not available. Cannot score alignment. |

## Composite Score

The composite score is the Structural Alignment score.
