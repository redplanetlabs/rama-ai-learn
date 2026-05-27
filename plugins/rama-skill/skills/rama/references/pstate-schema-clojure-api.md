# PState Schema Clojure API Reference

API functions in `com.rpl.rama` for schema declaration and PState
configuration.

## Schema Declaration

```clojure
(declare-pstate topology-handle $$name schema)
(declare-pstate topology-handle $$name schema opts-map)
```

`opts-map` keys: `:global?`, `:initial-value`, `:private?`,
`:key-partitioner`.

## Schema Constructors

```clojure
(map-schema key-class value-schema)
(map-schema key-class value-schema {:subindex? true})
(map-schema key-class value-schema {:subindex-options {:track-size? false}})

(set-schema value-class)                              ;; value must be a class
(set-schema value-class {:subindex? true})

(vector-schema element-schema)                        ;; element can be schema or class
(vector-schema element-schema {:subindex? true})

(fixed-keys-schema {:key1 schema1 :key2 schema2 ...})
```

Map literal `{K V}` is shorthand for `(map-schema K V)`.

## Subindex Options

```clojure
{:subindex? true}                                     ;; size tracking on (default)
{:subindex-options {:track-size? false}}               ;; disable size tracking
```

## PState Options

```clojure
{:global? true}          ;; single partition on task 0
{:initial-value v}       ;; starting value; top-level class schemas only
{:private? true}         ;; no external reads; throws on foreign access
{:key-partitioner f}     ;; (fn [num-partitions key] -> partition-idx)
```

## Runtime Config

```clojure
(set-launch-pstate-dynamic-option! "option.name" value)
```

## Mirror PState

```clojure
(mirror-pstate topology-handle $$name "com.mycompany.OtherModule" "$$name")
```

Four args: setup handle, local PState symbol, source module name (string),
source PState name (string). Queries route to source module.

## Sorted Range Navigators

For subindexed maps and sets (in `com.rpl.rama.path`):

```clojure
(sorted-map-range start end)
(sorted-map-range start end {:inclusive-start? false})
(sorted-map-range-from key limit)
(sorted-map-range-to key limit)

(sorted-set-range start end)
(sorted-set-range start end {:inclusive-start? false})
(sorted-set-range-from elem limit)
(sorted-set-range-to elem limit)
```

## Size Query

```clojure
(local-select> [(keypath k) (view count)] $$p :> *size)
```

O(1) with size tracking enabled, O(n) without.

## Sources

- [Declaring PStates](https://redplanetlabs.com/docs/~/clj-defining-modules.html#_declaring_pstates)
- [PState internals](https://redplanetlabs.com/docs/~/pstates.html#_declaring_pstates)
- [Clojure API](https://redplanetlabs.com/clojuredoc/index.html)
