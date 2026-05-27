# Mirror Reference

A mirror imports a reference from a source module into the current module. No data is stored locally — all operations route to the source module. Mirrors enable inter-module composition without data copying.

Three mirror types exist:
- `MirrorDepot` — subscribe to or append into a foreign depot
- `MirrorPState` — read a foreign PState (no writes)
- `MirrorQuery` — invoke a foreign query topology

## Formal model

```text
MirrorDepot  : Depot α s V   →  Depot α M (Mirror s)
MirrorPState : PState o s κ ν V  →  PState o s κ ν (Mirror M)
MirrorQuery  : Query α β s V →  Query α β M (Mirror s)
```

Access restrictions induced by `Mirror`:

```text
Mirror M ⊢ PState   : {ReadExt}              -- no Write, no ReadInt
Mirror M ⊢ Depot    : {Append, ReadExt}       -- append routes to source
Mirror M ⊢ Query    : {Invoke}                -- invocation routes to source
```

Mirror read — ReadExt only, no writes:

```text
(Mirror-Read)
Γ ⊢ $$p : PState _ s κ ν Public    -- mirror from source module s
---------------------------------------------
Γ; o; M; m ⊢ local-select>($$p, path) : ν ! {ReadExt $$p}   -- async boundary
Γ; o; M; m ⊢ local-transform>($$p, ...) : ⊥                  -- REJECTED
```

Declaration scope:

```text
Γ_M ⊢ mirror-pstate(setup, $$p, s, name)   ⟹ $$p : PState _ s κ ν Public ∈ Γ_M
                                               access($$p) = {ReadExt}
Γ_M ⊢ mirror-depot(setup, *d, s, name)     ⟹ *d : Depot α s Public ∈ Γ_M
                                               access(*d) = {Append, ReadExt}
Γ_M ⊢ mirror-query(setup, *q, s, name)     ⟹ *q : Query α β s Public ∈ Γ_M
                                               access(*q) = {Invoke}
```

### Partition routing

```text
N_M = task-count(M)              -- tasks in referencing module
N_s = partition-count(s, entity) -- partitions in source module for entity

route_mirror : Partitioner × Value × Mirror → TaskId_s
route_mirror(|hash$$ $$m, k) = home_s($$m, k)    -- routes to source partition space
                              = hash(k) mod N_s

all_mirror : Mirror → Set TaskId_s
all_mirror(|all$$ $$m) = {0..N_s - 1}

-- When N_M ≠ N_s, each task t ∈ M maps to
-- {p ∈ 0..N_s-1 | assigned(t, p)} partitions in s (zero or more)
```

### Mirror immutability law

Mirror bindings permit `{ReadExt, Append, Invoke}` only — never `Write`.

### Suspension

Mirror operations are async boundaries. `local-select>` on a mirror suspends the current continuation and resumes on callback from the source module.

```text
Source                   Target              Effect
─────────────────────────────────────────────────────────
Mirror PState read       source module task  ReadExt, Suspend
```

## Declaration syntax

```ebnf
mirror-decl = '(mirror-depot' 'setup' depot-var string string ')'
            | '(mirror-pstate' 'setup' pstate-var string string ')'
            | '(mirror-query' 'setup' var string string ')' ;
```

| Construct | Syntax |
|---|---|
| Mirror depot | `(mirror-depot setup *d "ns/Mod" "*depot")` |
| Mirror PState | `(mirror-pstate setup $$p "ns/Mod" "$$p")` |
| Mirror query | `(mirror-query setup *q "ns/Mod" "query-name")` |

First argument is always `setup`. The two string arguments are the source module name and the entity name within that module.

All `mirror-*` symbols are module-scoped in `Γ_M` — accessible throughout the entire `defmodule` body regardless of lexical nesting.

Each has a `*` variant (e.g. `mirror-depot*`) for programmatic var specification via a string name.

## Mirror partitioners

Mirror partitioners route to the source module's partition space (not the current module's tasks).

```ebnf
mirror-hash   = '(|hash$$' pstate-var var ')' ;
mirror-all    = '(|all$$' pstate-var ')' ;
mirror-direct = '(|direct$$' pstate-var expr ')' ;
mirror-global = '(|global$$' pstate-var ')' ;
mirror-path   = '(|path$$' pstate-var var ')' ;
mirror-custom = '(|custom$$' pstate-var fn-ref { arg } ')' ;
```

| Partitioner | Syntax |
|---|---|
| Mirror hash | `(\|hash$$ $$mirror *k)` |
| Mirror all partitions | `(\|all$$ $$mirror)` |
| Mirror direct | `(\|direct$$ $$mirror *task-id)` |
| Mirror global | `(\|global$$ $$mirror)` |
| Mirror path | `(\|path$$ $$mirror *path)` |
| Custom (PState-scoped) | `(\|custom$$ $$mirror my-fn *args)` |

## Usage patterns

### Mirror PState reads

Use `select>` (auto-repartitions) or `|hash$$ $$mirror *k` + `local-select>` (manual partition routing). Writes are not permitted.

`local-select>` on a mirror is an **async boundary** (unlike colocated PState reads), so sequential mirror reads may see temporally inconsistent snapshots.

```clojure
;; Auto-repartition read
(select> (keypath *k) $$mirror :> *val)

;; Manual partition routing
(|hash$$ $$mirror *k)
(local-select> (keypath *k) $$mirror :> *val)
```

### Mirror depot appends

Use `depot-partition-append!` with `|hash$$ $$mirror *k`.

Stream subscriptions on mirror depots do not coordinate ack with the source module — `:ack` may return before remote topologies finish processing.

```clojure
(|hash$$ $$mirror-depot *k)
(depot-partition-append! *mirror-depot *val :ack)
```

### Mirror query invocation

Use `invoke-query` with the mirror var. Identical to colocated invocation.

```clojure
(invoke-query *mirror-query *arg :> *result)
```

## Ack semantics with mirrors

```text
AckScope ::= Own            -- depot owned by current module
           | Mirror s       -- depot mirrored from source module s
```

```text
(Ack-Mirror-Stream)
foreign-append!(d : Depot α M (Mirror s), val, :ack) returns
⊢ Depot(s): Committed
⊬ event-tree(s, val) complete              -- source topology may still be processing
```

Read-after-write on mirror:

```text
(RAW-Mirror)
foreign-append!(d : Depot α M (Mirror s), val, :ack) returns
⊬ foreign-select-one(path, $$p_s) observes val's effects -- source event tree may be incomplete
```

Ack visibility summary:

```text
                    Own / Stream     Own / Microbatch    Mirror s / Stream
nil                 —                —                   —
:append-ack         Depot: Committed Depot: Committed    Depot(s): Committed
                    PState: Pending  PState: Pending     PState(s): Pending
:ack                Depot: Committed Depot: Committed    Depot(s): Committed
                    PState: Visible  PState: Pending(!)  PState(s): Pending(!)
```

`:ack` on a **mirror** depot only confirms the append reached the source depot — the source module's topologies may not have finished processing. The ack scope does not cross module boundaries.

## Dependency validation

Rama validates mirror dependencies at launch, update, and destroy:
- `launch(M)` fails if source module `s` is not running
- `destroy(s)` fails if any module holds a mirror to `s` (unless dependents are updated first)
- Circular dependencies are permitted through staged updates

## Declaration patterns

- `mirror-depot`, `mirror-pstate`, `mirror-query`: first arg is `setup`
- Only **public** entities can be mirrored. Private PStates and disallowed depots reject mirrors.
