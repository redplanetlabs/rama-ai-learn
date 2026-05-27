# Dataflow Reference

Rama dataflow can express anything Clojure can, with different syntax. Many Clojure special forms and macros (`let`, `if`, `loop`, `and`, `or` etc.) are replaced by dataflow equivalents — see the equivalents table below. All `clojure.core` functions work in operation position. Conditions use Clojure truthy semantics — anything other than `nil` and `false` is true. This applies to `<<if`, `<<cond`, and any other dataflow conditional construct. This reference covers the dataflow language: operation position rules, control flow, graph control, branching/unification, loops, destructuring, execution contexts, and `deframafn`/`deframaop`.

## Formal model

### Operation position

```text
op ∈ OpPosition ::= NamedVar        -- top-level def, defn, deframafn, deframaop
                  | RamaBuiltin      -- ops/*, aggs/*, identity, etc.
                  | ClojureCoreFn    -- pure functions from clojure.core
                  | Partial          -- (partial f args...)

op ∉ OpPosition ::= FnLiteral       -- fn, #()
                  | Keyword          -- :k (keyword-as-function)
                  | LogVar           -- *var
                  | HostParam        -- non-var params in defgenerator/deframafn
                  | JavaInterop      -- .method, Class/static
                  | SpecialForm      -- let, do, def, if, etc.
```

### Unification and shadowing

```text
(Shadow)
Γ ⊢ e :> *x : τ₂       *x : τ₁ ∈ Γ
────────────────────────────────────
Γ' = Γ[*x ↦ τ₂]                       -- τ₁ no longer accessible

(Unify)
∀i. *x : τᵢ ∈ Γᵢ       τ₁ = τ₂ = ... = τₙ
────────────────────────────────────────────
*x : τ ∈ ⋂i Γi

(Unify-Drop)
∃i. *x ∉ Γᵢ
────────────────────────────────────────────
*x ∉ ⋂i Γi
```

`Γout = ⋂i Γi` applies after `unify>`, `<<if`, `<<cond`, `<<switch`.

```text
(ShadowIf)
Γ ⊢ <<shadowif(*x, pred, new-val)
────────────────────────────
if pred(*x) → *x = new-val in continuation
else        → *x unchanged in continuation
```

Example: `(<<shadowif *v nil? 5)` — if `*v` is nil, it becomes 5; otherwise unchanged.

### Anchors and hooks

- `anchor> <a>` stores current continuation environment into `A[a]`.
- `hook> <a>` moves attachment point to `A[a]`.

These are **graph-control constructs** (ASG wiring), not state effects.

### Execution context validity

```text
                    TopoBody  QueryBody  FnBody  OpBody  BatchBlock  ClientCode
Suspend (§6.1)         ✓         ✓        ✗       ✓      ✓(pre-agg)    ✗
Partitioners           ✓         ✓        ✗       ✓      ✓(pre-agg)    ✗
<<batch                ✗(1)      impl     ✗       ✗        —           ✗
Clojure specials       ✗         ✗        ✗       ✗        ✗           ✓
ops/vget               ✓         ✓        ✗       ✗        ✓           ✗
local-transform>       ✓         ✗(2)     ✗       ✗        ✓(mb)       ✗
```

Context establishment:

```text
deframafn body  ⟹  c = FnBody      -- Suspend not allowed on path to :> emit (but allowed elsewhere, e.g. inside <<atomic)
deframaop body  ⟹  c = OpBody      -- Suspend allowed anywhere (may repartition)
```

### Loop emit semantics

```text
loop<- [*vars :> *out] body
⊢ *out is bound after loop  iff  body contains (:> expr) on some reachable execution path
```

### `<<if` branch constraint

```text
(If-Else)
<<if(cond, then-body [, else> else-body])
⊢ |else>| ≤ 1 per <<if                    -- at most one else> marker
⊢ nested <<if scopes are independent      -- inner else> binds to inner <<if
```

## Clojure to dataflow equivalents

Dataflow can express anything Clojure can. Every Clojure form has a dataflow equivalent:

| Clojure | Rama dataflow | Example |
|---------|--------------|---------|
| `(let [a (+ 1 2)] ...)` | `(+ 1 2 :> *a)` | `:>` binds the result — `*a` is in scope for all downstream code |
| `if` / `when` / `cond` | `<<if` / `<<cond` / `<<switch` | Conditional branches, all unify scope afterward |
| `do` | Sequential statements | Implicit — just write statements one after another |
| `fn` / `#()` | `<<ramafn` / `<<ramaop` | Anonymous operations in dataflow |
| `loop` / `recur` | `loop<-` / `continue>` | `recur>` for tail-call in `deframafn` |
| `for` / `doseq` | `ops/explode` | Emit each element — downstream runs per emit |
| `and` / `or` | `and>` / `or>` | Short-circuit logical ops |
| `.method` | Wrap in `defn` | Java interop needs a named callable |

### Nested expressions

Any expression that emits to `:>` can be nested inside another expression, no matter how many times it emits to `:>`. Rama evaluates inner expressions first and passes the result to the outer form. This includes `or>`, `and>`, function calls, and comparisons. These are equivalent:

```clojure
;; Nested — inner expression evaluated inline
(<<if (or> (< *a 10) *b)
  (identity "small" :> *label))

;; Explicit binding — same behavior
(< *a 10 :> *v1)
(or> *v1 *b :> *v2)
(<<if *v2
  (identity "small" :> *label))
```

Nesting works anywhere you could put a variable: `(+ (* *x *x) (* *y *y) :> *sum-sq)`, `(filter> (> *amount 0))`, `(<<cond (case> (= *level :high)) ...)`.

These are also equivalent even though `ops/range>` emits many times:

```clojure
;; Nested
(println "Val:" (ops/range> 0 3))

;; Explicit
(ops/range> 0 3 :> *v)
(println "Val:" *v)
```

Each of these prints three times.


## Yielding

Because tasks are single-threaded, long-running synchronous work blocks all other processing on that task. Two tools allow topology code to yield the task thread cooperatively:

### yield-if-overtime

`(yield-if-overtime)` checks if the current event has exceeded `worker.event.target.max.millis` (default 5ms). If so, it suspends the current event and lets other pending events run. The suspended event resumes later on the same task.

`yield-if-overtime` is only needed for long stretches of purely synchronous work. Async boundaries already yield the task thread implicitly — partitioners, `completable-future>`, `local-select>` on mirror PStates, and `depot-partition-append!` with ack level `:append-ack` or `:ack` all suspend the event naturally. If a loop body contains any of these, `yield-if-overtime` is unnecessary.

Use in loops that do significant synchronous work per iteration with no async boundaries:

```clojure
(loop<- [*remaining *items :> *done]
  (yield-if-overtime)
  (<<if (empty? *remaining)
    (:> true)
   (else>)
    (first *remaining :> *item)
    (local-transform> [(keypath *item) (termval *data)] $$pstate)    
    (continue> (rest *remaining))))
```

### :allow-yield? true

Pass `{:allow-yield? true}` as an option to `local-select>` or `select>` when the read may iterate over many entries in a subindexed structure, and the PState in question is local (selects on mirror PStates automatically use this option on the other module). This allows the traversal to suspend mid-iteration if it exceeds the time budget, yielding the task thread to other events:

```clojure
(local-select> [(keypath *id) ALL]
               $$large-subindexed-pstate
               {:allow-yield? true}
               :> *entry)

(local-select> [(keypath *id) (subselect MAP-KEYS)]
               $$large-subindexed-pstate
               {:allow-yield? true}
               :> *keys-vector)
```

Without `:allow-yield?`, a `local-select>` that iterates thousands of subindexed entries holds the task thread for the entire scan. With it, the scan pauses periodically to let other work proceed. `:allow-yield?` works with every navigator as every navigator has a special "yielded select" implementation which is able to suspend at any point.

The emits of the `local-select>` with `:allow-yield?` are **identical** as not having the `:allow-yield?`, as the suspended query continues on a stable snapshot of the PState (the internal snapshot process is extremely fast – basically free).

### Tradeoffs

When an event yields, other events can run on the same task and modify PStates. This means PState reads before and after a yield point are NOT atomic — another event could change the data between them. Only use yielding when the operation can tolerate interleaved modifications to other PStates from other events. Writes between commit points (partitioners) are still atomic and unaffected by any yields in between.

## Operation position restrictions

Only named, resolvable callables are valid in operation position (see §5.7). Common violations and fixes:

- **`fn` / `#()` literals**: use `<<ramafn`.
- **Keywords as functions** (`(:k *map :> *v)`): use `(get *map :k :> *v)`.
- **Logvars as functions** (`(*vec 0 :> *elem)`): use `(first *vec :> *a)`, `(nth *vec 2 :> *c)`, etc.
- **Host parameters as functions** (`(my-map key :> *v)` in `defgenerator`/`deframafn`): use `(get my-map key default :> *v)`.
- **Java interop** (`.method`): wrap in a `defn`.
- **Clojure special forms** (`let`, `do`, `def`, etc.): use dataflow equivalents from the table above.
- Troubleshooting signal: `Keywords cannot be used operations in dataflow. Consider using 'get' instead.` — keyword-call form; replace with `get` or `ops/expand`.
- Troubleshooting signal: `Regular var *x cannot be used as an operation` — logvar in operation position; replace with named function call.

Note that static Java methods can be used in dataflow (e.g. `(System/currentTimeMillis :> *time)`).

## Control flow syntax (EBNF)

```ebnf
dataflow-stmt   = op-call | if-block | cond-block | switch-block | do-block
                | atomic-block | each-block | branch-block | loop-block
                | filter-stmt | assert-stmt | throw-stmt | short-circuit ;

(* Context-stratified statements — see §5.10: *)
topo-stmt       = dataflow-stmt | partitioner ;          (* TopoBody *)
query-stmt      = dataflow-stmt | partitioner ;          (* QueryBody — implicit batch *)
fn-stmt         = dataflow-stmt ;                        (* FnBody — no partitioners, no <<batch *)
batch-stmt      = dataflow-stmt | gen-source | agg-call | partitioner ;  (* BatchBlock *)

op-call         = '(' callable { arg } [ output-bind ] ')' ;
                (* output-bind and binding defined in Variable conventions *)

if-block        = '(<<if' expr { dataflow-stmt } [ '(else>)' { dataflow-stmt } ] ')' ;
shadowif-block  = '(<<shadowif' var pred value ')' ;
cond-block      = '(<<cond' { case-clause } [ default-clause ] ')' ;
                (* Every branch MUST be preceded by (case> pred) or (default>).
                   Bare predicates without case> are invalid and produce:
                   "Only one default> allowed and must after all case>" *)
case-clause     = '(case>' expr ')' { dataflow-stmt } ;
default-clause  = '(default>' [ ':unify' bool ] ')' { dataflow-stmt } ;
switch-default  = '(default>)' { dataflow-stmt } ;
switch-block    = '(<<switch' expr { switch-case } [ switch-default ] ')' ;
switch-case     = '(case>' value ')' { dataflow-stmt } ;
do-block        = '(<<do' { dataflow-stmt } ')' ;
atomic-block    = '(<<atomic' { dataflow-stmt } ')' ;
each-block      = '(<<each' { dataflow-stmt } ')' ;
branch-block    = '(<<branch' { dataflow-stmt } ')' ;

loop-block      = '(loop<-' '[' { var init-expr } ':>' { var } ']' { dataflow-stmt } ')' ;
continue-stmt   = '(continue>' { expr } ')' ;
recur-stmt      = '(recur>' { expr } ')' ;
self-call       = '(%self' { expr } [ output-bind ] ')' ;
emit-stmt       = '(:>' { expr } ')' ;

filter-stmt     = '(filter>' expr ')' ;
assert-stmt     = '(assert!' expr [ string ] ')' ;
throw-stmt      = '(throw!' expr ')' ;
short-circuit   = '(and>' { expr } [ ':>' binding ] ')' | '(or>' { expr } [ ':>' binding ] ')' ;
ifexpr-call     = '(ifexpr' expr expr [ expr ] ':>' binding ')' ;
subst-block     = '(<<with-substitutions' '{' { '#''' symbol expr } '}' { dataflow-stmt } ')' ;
compile-exec    = '(?<-' { dataflow-stmt } ')' ;

(* named output streams on op calls: *)
named-stream-bind = ':' label '>' anchor-var var { var } ;
(* e.g. (route-op *x :ok> <ok> *v :err> <err> *e) *)
```

## Control flow quick reference

| Construct | Syntax |
|---|---|
| Bind | `(op ... :> *x)` |
| Pure expression | `(+ *a *b :> *sum)` |
| If/else | `(<<if pred ... (else>) ...)` |
| Shadow if (conditional default) | `(<<shadowif *var pred val)` |
| Primitive if (named streams) | `(if> cond :then> ... :else> ...)` |
| If expression | `(ifexpr cond then else :> *v)` |
| Cond | `(<<cond (case> pred1) ... (case> pred2) ... (default>) ...)` |
| Cond (no-unify default) | `(<<cond ... (default> :unify false) (throw! ...))` |
| Switch | `(<<switch expr (case> val1) ... (case> val2) ... (default>) ...)` |
| Do block | `(<<do ...)` |
| Atomic block | `(<<atomic ...)` |
| Each (per-element iteration) | `(<<each ...)` |
| Branch (parallel) | `(<<branch ...)` |
| Loop | `(loop<- [*i 0 :> *out] ... (continue> (inc *i)))` |
| Recur (ramafn tail-call) | `(recur> *new-args)` |
| Self-invocation (recursion) | `(%self *arg1 *arg2 :> *result)` |
| Filter (terminate if false) | `(filter> pred)` |
| Assert (throw if false) | `(assert! pred)` |
| Short-circuit and/or | `(and> *a *b :> *v)`, `(or> *a *b :> *v)` |
| Throw | `(throw! (Exception. "msg"))` |
| Substitutions (testing) | `(<<with-substitutions {#'my-fn mock-fn} ...)` |
| Compile/execute block | `(?<- (op1 ...) (op2 ...))` |
| Compile/execute with return | `(?<- ... (:clj> *result))` |

### Destructuring

```clojure
(identity {:a 1 :b 2} :> {:keys [*a *b] :as *m})
(identity {:k [7 8 9]} :> {[*a *b *c] :k})
;; String-key binding: {binding key} form
(identity {"a" 1 "b" 2} :> {*a "a" *b "b"})
```

## Graph control syntax (EBNF)

```ebnf
anchor-def      = '(anchor>' anchor-var ')' ;
hook-to         = '(hook>' anchor-var ')' ;
unify           = '(unify>' anchor-var { anchor-var } ')' ;

(* named output stream binding on an op call: *)
named-stream    = '(' callable { arg } ':' label '>' anchor-var var { var }
                  { ':' label '>' anchor-var var { var } } ')' ;
inline-hook     = '(' callable { arg } ':>>' { dataflow-stmt } ')' ;
```

| Construct | Syntax |
|---|---|
| Named output stream bind | `(op ... :a> *x :err> *e :>)` |
| Inline hook | `(op ... :>> ...)` |
| Define anchor | `(anchor> <root>)` |
| Reattach branch | `(hook> <root>)` |
| Merge branches | `(unify> <a> <b>)` |

## State modeling and dataflow notes

- Prefer `term` to read-modify-write.
- Clojure macros (`->`, `->>`, etc.) expand before Rama compilation and are valid in dataflow code.
- Nested expressions can capture single `:>` emits: `(* (- 10 4) (+ 1 2) :> *res)`.
- Constants in dataflow must be serializable (basic types, records, functions, `RamaSerializable`).

## Control flow construct descriptions

- `<<atomic`: waits for all synchronous work in its block before continuing; async boundaries (partitioners) do not block.
- `<<shadowif`: conditionally shadows a variable's value — `(<<shadowif *v pred new-val)` replaces `*v` with `new-val` if `(pred *v)` is true, otherwise `*v` is unchanged.
- `<<branch`: parallel execution branches; containing code continues after all branches complete.
- `<<with-substitutions`: replaces var references during dataflow compilation. Idiomatic way to access module-scoped PStates in `deframafn`/`deframaop` bodies — prevents accidentally transferring a PState partition reference across a partitioner boundary. PStates can also be passed as parameters, which is fine as long as no partitioner in the body causes the PState var to be accessed on a different task.
- `?<-`: compiles and runs a dataflow block. `:clj>` output stream returns values to Clojure. Test and repl only.
- `continue>`: loop iteration. `recur>`: tail-call recursion within `ramafn` bodies (no ramaop invokes between start and callsite).
- `%self`: implicit variable for recursive self-invocation. Available in `deframafn`, `deframaop`, `<<ramafn`, `<<ramaop`, `def<-`, query topologies. Regular call (pushes frame, non-tail OK). Rename with `(:%self-name %my-name)`.

## Loop and branching patterns

- `loop<-` termination: the `(else>)` branch must emit via `(:> *acc)` to produce loop output (§5.11). Missing emit → `*out` is `nil`.
- Bounded iteration pattern:
```clojure
(loop<- [*i *start *acc 0 :> *total]
  (<<if (<= *i *end)
    (+ *acc *i :> *new-acc)
    (continue> (inc *i) *new-acc)
    (else>)
    (:> *acc)))
```
- `<<if` with `else>` — branches unify, so vars bound on all branches are available after:
```clojure
(<<if (> *age 18)
  (identity "adult" :> *label)
  (else>)
  (identity "minor" :> *label))
;; *label is bound here on both branches
```
- `<<cond` — every branch requires a `(case> pred)` marker; bare predicates are invalid. All branches must bind the same vars for unification:
```clojure
;; Correct — each branch has (case>) or (default>)
(<<cond
  (case> (>= *score 90))
  (identity "gold" :> *tier)
  (case> (>= *score 50))
  (identity "silver" :> *tier)
  (default>)
  (identity "bronze" :> *tier))

;; WRONG — bare predicates without case> cause "Only one default>" error
;; (<<cond
;;   (>= *score 90)              ← missing (case> ...)
;;   (identity "gold" :> *tier)
;;   ...)
```
- `<<if` nesting constraint: see §5.12. Prefer `<<cond` for multi-way branching.
- Deeply nested control flow: variables bound inside nested `<<if`/`<<do`/`loop<-` may not be available in outer scopes — unification rule (§5.3). Prefer flat structure.

## Branching and unification patterns

- Use Rama branching operators (`<<if`, `<<cond`, `<<switch`) instead of Clojure special forms inside segments.
- Only variables present and type-compatible on **all** branches survive after unification (see §5.3 Unify/Unify-Drop).
- `<<shadowif` conditionally replaces a variable's value in-place — it does not create branches or affect unification (see §5.3 ShadowIf).
- When a follow-up step depends on a conditional result, either:
  - emit equivalent bindings in every branch, or
  - place the conditional update inside branch-local side-effecting operations so no cross-branch attach point is required.
- Troubleshooting signal: `Attach point missing needed logvar: *x` usually means `*x` is not available at a downstream attach point (often right before/after a partitioner). Common cause: binding `*x` only inside branch-local logic and then using it outside the branch. Fix by ensuring the var is produced compatibly on all branches, moving the dependent work inside each branch, or computing the routing value in a plain `defn` before the partitioner.
- More generally, `Attach point missing needed logvar` usually means a logvar was bound only inside one control-flow branch and used after merge; compute downstream logic inside each branch, or ensure the variable is produced in every branch before unification.

## Control flow troubleshooting

- `and>`/`or>` bindings in nested `<<if`: branch-local variables violate unification (§5.3). Avoid branch-local variables; use filter/continue patterns.
- Clojure `cond` vs Rama `<<cond`: `Cannot use cond in dataflow code` — use `<<cond` with `case>` branches.
- `Syntax error reading source ... Unmatched delimiter` near `<<if`/`(else>)`: parentheses are mismatched. Check that `(else>)` is a standalone form (not nested inside another expression) and that the closing `)` for `<<if` is in the right place.
- `Wrong number of args to subblock: else`: this is a parenthesization error, NOT a form count limit. Both the then and else branches of `<<if` accept any number of statements. Check for misplaced parens — `(else>)` must be at the same nesting level as the then-branch statements.
- `loop<-` appears to "do nothing": missing `(:> ...)` emit on termination path (§5.11).

## Execution contexts

**Dataflow is dataflow.** All dataflow operations (`local-select>`, `local-transform>`, `<<if`, `loop<-`, etc.) work the same everywhere — in topology bodies, `deframafn`, `deframaop`, `<<ramafn`, `<<ramaop`. There is no restricted subset of operations for any context.

The only restriction is on **suspend effects** (partitioners, `<<batch`, async calls) in `deframafn`:

- **`deframafn`**: The path from input to the single `:>` emit must be synchronous (no suspend effects). However, a `deframafn` *can* use suspend effects elsewhere in its body — e.g., `(<<atomic ... suspending-operations)` is valid. It can also emit to other named output streams freely and in any order, just like a `deframaop`. Returns exactly one value via `:>`. Callable from both dataflow and plain Clojure.
- **`deframaop`**: No restrictions on suspend effects. Can repartition, read mirrors, invoke async anywhere. Emits zero or more tuples on named streams. Callable only from dataflow.
- **`<<atomic`**: suppresses suspension within its block — all work completes synchronously on the current task before continuing. Partitioners inside create child events but do not suspend the parent.
- `ops/vget` requires topology runtime; use `nth`/`first`/`second` in `FnBody`/`OpBody`.

## deframafn/deframaop troubleshooting

- `deframafn` requires explicit emit via `(:> *result)`.
- `deframafn` variable naming: all parameters must use `*` prefix. `(deframafn f [*x] ...)` not `(deframafn f [x] ...)`.
- `deframafn` calling other `deframafn`: valid and idiomatic — synchronous call returning one value via `:>`.
- `ops/vget` invalid in FnBody (§5.10): "Arity 2 not implemented". Use `nth`, `first`, `second`.
- `ops/explode` zero-emit on empty inputs: see §Built-in operations.

---

## Async / External Integration

| Construct | Syntax |
|---|---|
| Completable future | `(completable-future> %callback ...)` |
| Yield if overtime | `(yield-if-overtime)` |

`completable-future>` wraps an async external call; the topology suspends until the `CompletableFuture` delivers, then emits the result. Failure/timeout propagates to topology retry.

`yield-if-overtime` checks if the current event exceeds `worker.event.target.max.millis` (default 5ms) and suspends, allowing other events to proceed.

---

## Worked Examples

### Effectful update with routing + ack return

```clojure
(<<sources s
  (source> *events :> [*user-id *delta])
  (|hash *user-id)
  (local-select> (keypath *user-id) $$balances :> *b0)
  (or> *b0 0 :> *b)
  (+ *b *delta :> *b1)
  (local-transform> [(keypath *user-id) (termval *b1)] $$balances)
  (ack-return> *b1))
```

### Named streams + anchors + unification

```clojure
(deframaop route-op [*x]
  (:ok> *x)
  (:err> {:msg "bad" :x *x}))

(?<-
  (route-op 10 :ok> <ok> *v :err> <err> *e)
  (hook> <ok>)
  (identity :ok :> *tag)
  (hook> <err>)
  (identity :err :> *tag)
  (unify> <ok> <err>)
  (println *tag))
```
