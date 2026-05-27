# Rama Depot Migrations

Migrate depot records to new values or remove them as part of a module
update. A depot migration takes effect instantly regardless of depot
size — Rama applies the migration function on read until values are
migrated on disk in the background.

## Axioms

1. **Instant effect** — migration applies on-read immediately; background disk migration is eventual
2. **Offset invariance** — migrations never change record offsets; tombstones preserve position
3. **Idempotence** — migration function must be idempotent; new appends also pass through it
4. **ID = identity** — same migration ID → resume; changed ID → restart, reverting prior work
5. **Tombstone transparency** — topologies and foreign reads skip tombstones; range reads may return fewer records
6. **Migration scope persistence** — migration function runs on all new appends until `:migration` option is removed
7. **Two-log atomicity** — old and new logs coexist; Rama atomically switches when caught up

## Migration API

Use `depot-migration` in the options map of `declare-depot`:

```clojure
(declare-depot setup *depot :random
  {:migration (depot-migration "my-migration-id"
                (fn [record]
                  (if (= record 10)
                    DEPOT-TOMBSTONE
                    (str record))))})
```

Parameters:
1. **Migration ID** — determines resume vs restart behavior on
   subsequent module updates
2. **Migration function** — takes a depot record, returns the new value
   or `DEPOT-TOMBSTONE` to remove it

## Offset Preservation

Depot migrations never change the offsets of records. This applies to
both transformed and tombstoned records.

## DEPOT-TOMBSTONE

Return `DEPOT-TOMBSTONE` from the migration function to remove a record.
A small tombstone value is written in place. Topologies and foreign
depot reads automatically skip tombstones.

Because offsets are preserved, a foreign depot read for a range of
offsets can return fewer records than the requested range size.

## Migration ID: Restart vs Resume

The migration ID controls behavior when the module is updated
mid-migration:

- **Same ID** → migration continues where it left off
- **Changed ID** → migration restarts from the beginning

When a migration restarts before completing, the depot values revert as
if the first migration never happened.

## Idempotence Required

As of Rama 1.5.0, the depot migration function **must be idempotent**.
The migration function is also applied to new appends during the
migration (see below), so it may run on values that are already in the
new format.

## New Appends During Migration

The migration function applies to **all new appends** until the
`:migration` option is removed from the module definition. This is why
idempotence is required — new appends in the new format also pass
through the migration function.

Clients must append values in the new format once the module update
deploys.

## Post-Migration Cleanup

After the migration completes on disk, update the module again to
remove the `:migration` option from `declare-depot`. Until removed,
the migration function continues to run on every new append.

## Two-Log Mechanism

Depot migrations capture the end offset of each partition at the time of
the module update. The migration iterates through the original log and
materializes a new log with migrated values. Once the migration catches
up, Rama atomically switches to the new log and deletes the original.

**Disk space:** Two logs exist simultaneously during migration, so disk
usage is temporarily higher.

## Clojure API Cheatsheet

| Symbol | Signature / Type | Purpose |
|--------|-----------------|---------|
| `depot-migration` | `(depot-migration migration-id migration-fn)` | Specify migration in `declare-depot` options |
| `DEPOT-TOMBSTONE` | constant | Return from migration fn to delete a record |
| `:migration` | option key in `declare-depot` | Attach migration to a depot |

## References

- [Clojure API: depot-migration](https://redplanetlabs.com/clojuredoc/com.rpl.rama.html#var-depot-migration)
- [Clojure API: DEPOT-TOMBSTONE](https://redplanetlabs.com/clojuredoc/com.rpl.rama.html#var-DEPOT-TOMBSTONE)
- [Depot Migrations (defining modules)](https://redplanetlabs.com/docs/~/clj-defining-modules.html#_depot_migrations)
- [Depot Migrations (depots doc)](https://redplanetlabs.com/docs/~/depots.html#_migrations)
