# Rama PState Migrations

Migrate PState schemas as part of a module update. Migrations take
effect immediately — all reads return migrated values after the update.
Rama applies the migration function on read while migrating on disk in
the background. The migration does not delay the module update.

## Axioms

1. **Single API** — `migrated` wraps a schema location: `(new-schema, migration-id, fn, [options])`
2. **Read-time application** — fn applied on every read until disk migration completes; module update not delayed
3. **Idempotency invariant** — fn must be idempotent; will be called on already-migrated values
4. **Full-value constraint** — only complete values can be migrated (top-level map values, subindexed structure values); never keys, never inside non-subindexed nesting, never inside fixed-keys fields
5. **Migration ID semantics** — same ID → resume; changed ID → restart
6. **Type preservation** — fn must return compatible persistent types (`IPersistentMap`, not `HashMap`)
7. **Explicit options** — structural changes require explicit option declarations (`migrate-to-subindexed`, `fixed-key-additions`, `fixed-key-removals`) even when inferable
8. **Implicit migration** — widening type changes need no `migrated` wrapper; narrowing is rejected
9. **Completion protocol** — remove `migrated` wrappers only after Cluster UI confirms; premature removal → rejected update
10. **Fallback** — if not expressible via `migrated`, recompute PState from depot data

## Migration API

The entire migration API is `migrated`:

```clojure
(declare-pstate s $$p
  {Long (migrated String "mig-id" str)})
```

Parameters:
1. **New schema** for the location
2. **Migration ID** — determines resume vs restart behavior on
   subsequent module updates
3. **Migration function** — transforms old values to the new schema

### Idempotency Requirement

Migration functions **must be idempotent**. Rama applies the function on
every read until the disk migration completes, so it may be called on
already-migrated values.

## Migration Types

### Value Type Change

```clojure
;; Migrate map values from Long to String
;; Idempotent: checks type before transforming
(declare-pstate s $$p
  {Long (migrated String "mig-id"
          (fn [o]
            (if (string? o) o (str o "!"))))})
```

### Migrate to Subindexed

Convert a non-subindexed location to a subindexed structure. Requires
the `migrate-to-subindexed` option.

```clojure
;; Set example
(declare-pstate s $$p
  {Integer (migrated
             (set-schema String {:subindex? true})
             "mig-id"
             (fn [num]
               (if (set? num)
                 num
                 (into #{} (map str (range num)))))
             [(migrate-to-subindexed)])})

;; Vector example
(declare-pstate s $$p
  {Long (migrated
          (vector-schema String {:subindex? true})
          "vec-mig"
          identity
          [(migrate-to-subindexed)])})
```

### Fixed-Keys: Add and Remove Keys

Use `fixed-key-additions` and `fixed-key-removals` options. Rama
requires explicit options even though it could infer the changes.

```clojure
(declare-pstate s $$users
  {String (migrated
            (fixed-keys-schema
              {:age Long
               :location String
               :score Long})
            "fk-mig"
            (fn [m]
              (if (contains? m :score)
                m
                (-> m
                    (dissoc :occupation)
                    (assoc :score 10)
                    (update :location clojure.string/lower-case))))
            [(fixed-key-additions #{:score})
             (fixed-key-removals #{:occupation})])})
```

## Valid Migration Locations

Only **full values** can be migrated — top-level map values or values
of subindexed structures.

### Valid

```clojure
;; Top-level map value
(declare-pstate s $$p
  {Long (migrated String "mig" str)})

;; Value of a subindexed map
(declare-pstate s $$p
  {String (map-schema Long
            (migrated String "mig" str)
            {:subindex? true})})
```

### Invalid

```clojure
;; Cannot migrate inside a non-subindexed nested map
;; (the value is part of a greater serialized value)
(declare-pstate s $$p
  {String (map-schema Long
            (migrated String "mig" str))})  ;; INVALID

;; Cannot migrate inside fixed-keys — migrate the whole fixed-keys
(declare-pstate s $$p
  {String (fixed-keys-schema
            {:age Long
             :loc (migrated String "mig" str)})})  ;; INVALID

;; Cannot migrate keys of maps or values of sets
(declare-pstate s $$p
  {String (set-schema
            (migrated String "mig" str)
            {:subindex? true})})  ;; INVALID
```

**Workaround for non-subindexed nesting:** Move the migration up to the
complete value:

```clojure
(declare-pstate s $$p
  {String (migrated
            (map-schema Long String)
            "mig"
            (fn [m]
              (reduce-kv
                (fn [acc k v] (assoc acc k (str v)))
                m m)))})
```

If a migration is not supported by the API, recompute the PState from
depot data instead.

## Nested Migrations

Multiple migrations can exist on the same PState, including nested
within one another:

```clojure
(declare-pstate s $$movie-reviews
  {String (migrated
            (fixed-keys-schema
              {:audience (map-schema String String {:subindex? true})
               :critics (map-schema String
                          (migrated String "nested-mig"
                            clojure.string/lower-case)
                          {:subindex? true})})
            "top-mig"
            (fn [m]
              (if (contains? m :aliens)
                (dissoc m :aliens)
                m))
            [(fixed-key-removals #{:aliens})])})
```

Migration IDs are tracked per location independently.

## Mid-Migration Module Updates

You do not need to wait for a migration to finish before updating the
module again.

- **Same migration ID** → migration continues where it left off
- **Changed migration ID** → migration restarts from the beginning

Reasons to restart: bug in migration code, tweaked transformation
logic, or migrating additional parts of the PState.

A restarted migration function must handle values in any state — some
already migrated (possibly by a prior version), some not yet migrated.

Migration functions must preserve compatible data structure types for
downstream topology code (e.g., return `IPersistentMap`, not
`HashMap`).

## Migration Telemetry

The Cluster UI provides migration progress information:

- **Main / module page** — shows if any migrations are in-progress
- **Module instance page** — which PStates are in-progress or done
- **PState page** — which partitions are still migrating, plus
  time-series charts for:
  - Estimated total progress (migrated top-level keys vs total)
  - Migrated top-level keys over time
  - Migration paths applied

Progress is an estimate based on approximate top-level key counts.

## Completing a Migration

When the Cluster UI shows a migration as complete, remove the `migrated`
wrappers from the PState schema on the next module update:

```clojure
;; Before: with migration
(declare-pstate s $$p
  {Long (migrated String "mig-id" str)})

;; After: migration complete, remove wrapper
(declare-pstate s $$p
  {Long String})
```

Removing `migrated` wrappers before the migration is complete will cause
Rama to reject the module update.

## Migration Rate Control

Dynamic options control migration pace:

- `topology.stream.migration.max.paths.per.batch`
- `topology.stream.migration.max.paths.per.second`
- `topology.microbatch.migration.max.paths.per.batch`
- `topology.microbatch.migration.max.paths.per.second`

Migrations add approximately 10% task thread load by default. If spare
capacity is unavailable before deploying, scale the module first.

## Testing Migrations

Use IPC (in-process cluster) to test module updates. Override
`getModuleName` so both module versions coexist on the classpath,
allowing you to deploy v1, populate data, then deploy v2 and verify
migrated reads.

```clojure
;; Give v2 the same module name as v1
(defmodule MyModuleV2 [setup topologies]
  (clojure.core/declare-pstate ...)
  ...)

(defn get-module-name [_] "MyModule")

;; In test: launch v1, add data, update to v2, verify reads
```

## Implicit Migrations

Schema changes to equivalent or more general types need no `migrated`
wrapper and incur no background disk work:

- `Integer` → `Object`
- `java.util.Map` → `(map-schema Object Object)`
- `(map-schema String Object)` → `java.util.Map`

Narrowing types (e.g., `java.util.Map` → `(map-schema String Object)`)
is rejected — Rama cannot verify original key types.

## Clojure API Cheatsheet

| Function | Signature | Purpose |
|---|---|---|
| `migrated` | `(migrated new-schema migration-id migration-fn)` | Declare migration at a schema location |
| `migrated` | `(migrated new-schema migration-id migration-fn [opts])` | With migration options |
| `migrate-to-subindexed` | `(migrate-to-subindexed)` | Option: convert to subindexed structure |
| `fixed-key-additions` | `(fixed-key-additions #{keys})` | Option: declare added fixed keys |
| `fixed-key-removals` | `(fixed-key-removals #{keys})` | Option: declare removed fixed keys |

All from `com.rpl.rama`. Options are passed as a vector in the 4th arg to `migrated`.

## References

- [Clojure API: migrated](https://redplanetlabs.com/clojuredoc/com.rpl.rama.html#var-migrated)
- [Clojure API: migrate-to-subindexed](https://redplanetlabs.com/clojuredoc/com.rpl.rama.html#var-migrate-to-subindexed)
- [Clojure API: fixed-key-additions](https://redplanetlabs.com/clojuredoc/com.rpl.rama.html#var-fixed-key-additions)
- [Clojure API: fixed-key-removals](https://redplanetlabs.com/clojuredoc/com.rpl.rama.html#var-fixed-key-removals)
- [PState Migrations (defining modules)](https://redplanetlabs.com/docs/~/clj-defining-modules.html#_pstate_migrations)
- [PState Migrations (PStates doc)](https://redplanetlabs.com/docs/~/pstates.html#_migrations)
