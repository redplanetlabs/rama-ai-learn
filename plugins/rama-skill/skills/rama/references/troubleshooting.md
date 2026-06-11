# Troubleshooting

Common errors and their fixes. When you see an error you don't understand, check this reference first.

## Dataflow compilation errors

### "Unable to resolve symbol: let* in this context"

A Clojure macro that expands to `let*` was used in dataflow code. The most common culprits are `or`, `and`, `when-let`, and `when-some` — these are Clojure macros that expand to `let*` internally. Replace with Rama dataflow equivalents:

- `or` → `or>`
- `and` → `and>`
- `when-let` → use `<<if` with explicit binding

```clojure
;; WRONG — or expands to let*
(<<if (or (nil? *current) (> *amount *current)) ...)

;; RIGHT — use or> which is a Rama dataflow operation
(<<if (or> (nil? *current) (> *amount *current)) ...)
```

### "Only one default> allowed and must after all case>"

`<<cond` requires `(case> pred)` markers for each branch. Bare predicates are not valid:

```clojure
;; WRONG
(<<cond (= *x :a) body... (= *x :b) body...)

;; RIGHT
(<<cond (case> (= *x :a)) body... (case> (= *x :b)) body...)
```

### "Wrong number of args to subblock: else"

Parenthesization error in `<<if`/`(else>)`. Both then and else branches accept any number of statements. `(else>)` must be a standalone form at the same nesting level as the then-branch statements:

```clojure
;; WRONG — else> wrapping body
(<<if *pred
  (do-something)
  (else>
    (do-other)))

;; RIGHT — else> is standalone, body follows at same level
(<<if *pred
  (do-something)
  (else>)
  (do-other))
```

## Runtime / serialization errors

### "Serializer not defined for type class rpl.rama.durable.RocksDBWrapper"

You selected a subindexed structure as a whole value — for example, navigating to `(keypath *id)` on a PState where the value at that key is a subindexed map, set, or vector. Subindexed structures are backed by RocksDB and cannot be treated as plain values. They cannot be serialized across tasks, returned from query topologies, or passed through partitioners.

Navigate INTO the subindexed structure instead. Use `ALL`, `MAP-VALS`, `MAP-KEYS`, `sorted-map-range`, `sorted-set-range-from-start`, or other range/element navigators to iterate individual elements, which are plain serializable values:

```clojure
;; WRONG — selects the subindexed map itself (RocksDB-backed, not serializable)
(local-select> (keypath *id) $$items :> *whole-map)

;; RIGHT — iterates elements within the subindexed map
(local-select> [(keypath *id) ALL] $$items :> [*k *v])

;; RIGHT — range scan within the subindexed map
(local-select> [(keypath *id) (sorted-map-range-from-start 100)] $$items :> [*k *v])
```

This applies everywhere subindexed values cross a serialization boundary: direct PState queries, query topology results, partitioner hops, mirror reads, and `select>` (which has an implicit partitioner).

### "ValueSchemaMismatchException ... schema Long, value Integer"

PState key and value schemas are strict about numeric types: a `Long` schema rejects an `Integer` value at write time. Common sources of Integers in dataflow: `(count ...)` and other Java APIs returning `int`. Coerce with `(long ...)` before writing:

```clojure
;; WRONG — count returns Integer, schema is Long
(local-transform> [(keypath *k) (termval (count *items))] $$p)

;; RIGHT
(local-transform> [(keypath *k) (termval (long (count *items)))] $$p)
```

### "Object cache disallowed {:class ...}"

A constant of an unsupported type is embedded in dataflow code. Constants embedded in dataflow must be Java primitives (numbers, booleans, chars, strings, etc.) or Clojure's immutable data structures (vectors, maps, sets, lists) containing such values. Java arrays (e.g. `long-array`, `object-array`) and any other object type fail at module launch with this error. The same rules apply to `declare-object` values (see task-globals.md).

Work around by calling a Clojure function that returns the constant:

```clojure
(def SOME-CONSTANT (SomeObject.))

;; WRONG — non-primitive constant embedded in dataflow
(some-operation SOME-CONSTANT :> *result)

;; RIGHT — a Clojure fn call produces the value at runtime
(defn some-constant [] SOME-CONSTANT)
...
(some-constant :> *obj)
(some-operation *obj :> *result)
```

## Module declaration errors

### "Unable to resolve classname: MyModule"

`defmodule` creates a var holding a module instance, not a Java class. Do NOT use `(MyModule.)` or `(new MyModule)`. Use the bare var:

```clojure
;; WRONG
(get-module-name (MyModule.))

;; RIGHT
(get-module-name MyModule)
```
