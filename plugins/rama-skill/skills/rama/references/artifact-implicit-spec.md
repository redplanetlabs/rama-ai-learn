# Implicit Spec

<!-- Phase 0. Fill in before starting Phase 1 (Plan). After completing this, fill in PLAN.md. -->

Before designing any Rama-specific implementation, write out the implicit assumptions and expectations that a senior engineer would bring to this system based on the requirements and the domain.

For every operation the system exposes, fill in:
- **Latency**: Are the effects of this operation needed in single-digit milliseconds, or is hundreds of milliseconds / seconds acceptable?
- **Throughput**: What drives the volume of this operation and how does it scale with usage?
- **Consistency/correctness invariants**: What must always be true?
- **Data growth and scale**: Which collections are unbounded? What access patterns dominate? What needs efficient range access vs. point lookups?
- **Concurrency behavior**: What happens under concurrent writes to the same entity? What ordering guarantees matter?
- **Edge cases**: Also consider: empty inputs, boundary values, large ranges, missing keys, duplicate operations.
- **Entity state × write matrix**: For each entity that write operations target, list every state that entity can be in (e.g., "does not exist", "active", "completed"). Then for each write operation × entity state, write:

  ```
  Entity state x Write operation
    - related-read-op-1: what would it return after this and why
    - related-read-op-2: what would it return after this and why
  ```

  List every related read for EVERY row — not just the obvious or interesting ones. Without listing each read explicitly, it is easy to miss writes that lead to undesired application behavior that violates common sense. Think through how that write affects all related reads and if any inconsistency is created. ALWAYS assume all read operations can be called in every entity state — users query data in all states. Do NOT skip states because the spec is silent — the implicit spec exists to fill in what the protocol leaves unstated.

Ground these in the specific domain — don't list generic concerns. Every point must be tied to a concrete operation or behavior.

## Operations

<!-- Fill in for each operation -->

## Entity State × Write Matrix

<!-- Fill in for each entity -->
