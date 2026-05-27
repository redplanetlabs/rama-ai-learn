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

## Module declaration errors

### "Unable to resolve classname: MyModule"

`defmodule` creates a var holding a module instance, not a Java class. Do NOT use `(MyModule.)` or `(new MyModule)`. Use the bare var:

```clojure
;; WRONG
(get-module-name (MyModule.))

;; RIGHT
(get-module-name MyModule)
```
