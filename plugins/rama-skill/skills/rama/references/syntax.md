# Rama Dataflow: Syntax Mapping

Maps the formal model to concrete Rama Clojure syntax.

---

## Variable conventions

```ebnf
var          = '*' identifier ;
pstate-var   = '$$' identifier ;
frag-var     = '%' identifier ;
unground-var = '**' identifier ;
delayed-var  = '*__' identifier ;
anchor-var   = '<' identifier '>' ;
depot-var    = '*' identifier ;        (* by convention, depots use *name *)
output-bind  = ':>' binding { binding } ;
binding      = var | vec-destructure | map-destructure ;
vec-destructure = '[' { binding } ']' ;
map-destructure = '{' { ':keys' '[' { var } ']' | binding keyword | binding string
                       | '[' { binding } ']' keyword | ':as' var } '}' ;
named-output = ':' label '>' var { var } ;  (* e.g. :ok> *v, :err> *e *)
label        = identifier ;
```

- `*var` — value binding
- `$$pstate` — PState reference
- `%frag` — anonymous operation (ramafn/ramaop)
- `**unground` — outer join variable (nullable, for batch outer joins)
- `*__delayed` — delayed unground (ground until unground var introduced)

---

## Namespaces

Rama functionality is split across several namespaces. `[com.rpl.rama :refer :all]` provides the core API (defmodule, declare-depot, declare-pstate, topologies, partitioners, dataflow macros, foreign client functions). The `ops`, `aggs`, and `path` namespaces must be required separately.

```clojure
(ns my.module
  (:require
   [com.rpl.rama :as r :refer :all]       ;; core API
   [com.rpl.rama.aggs :as aggs]           ;; aggregators: +count, +sum, +vec-agg, etc.
   [com.rpl.rama.ops :as ops]             ;; built-in ops: explode, expand, range>, etc.
   [com.rpl.rama.path :refer :all])       ;; path navigators: keypath, term, termval, STAY, etc.
  (:import
   [com.rpl.rama.helpers ModuleUniqueIdPState])) ;; Java helpers (when needed)
```

Idiomatic usage:
- `com.rpl.rama` — `:refer :all` plus `:as r` for explicit calls (e.g. `r/foreign-append!`)
- `com.rpl.rama.path` — `:refer :all` (navigators read better unqualified)
- `com.rpl.rama.ops` — `:as ops` (qualified: `ops/explode`)
- `com.rpl.rama.aggs` — `:as aggs` (qualified: `aggs/+count`)
- `com.rpl.rama.test` — `:as rtest` (qualified: `rtest/create-ipc`)

Troubleshooting: `Unable to resolve symbol: termval` (or `keypath`, `STAY`, etc.) means path navigators were used unqualified but `com.rpl.rama.path` was not brought into scope. Fix by adding `:refer :all` to the path ns or qualifying calls consistently (e.g. `p/termval`, `p/keypath`, `p/STAY`).

---

## Module and topology declarations

```ebnf
defmodule     = '(defmodule' symbol [ options-map ] '[setup topologies]' body ')' ;
module        = '(module' [ options-map ] '[setup topologies]' body ')' ;
options-map   = '{' ':module-name' string '}' ;
body          = { depot-decl | pstate-decl | object-decl | tick-decl
                | mirror-decl | topology-def | query-topo-def | dynamic-opt } ;

depot-decl    = '(declare-depot' 'setup' depot-var partitioner [ depot-opts ] ')'
              | '(declare-depot*' 'setup' string partitioner [ depot-opts ] ')' ;
partitioner   = ':random' | '(hash-by' fn-or-kw ')' | ':disallow'
              | '(defdepotpartitioner' '[' var var ']' body ')' ;
depot-opts    = '{' { ':global?' bool } '}' ;

tick-decl     = '(declare-tick-depot' 'setup' depot-var millis ')' ;

pstate-decl   = '(declare-pstate' topo-var pstate-var schema [ pstate-opts ] ')'
              | '(declare-pstate*' topo-var string schema [ pstate-opts ] ')' ;
schema        = map-schema | '(value-schema' type ')' | '(vector-schema' type [ sub-opts ] ')'
              | '(set-schema' type [ sub-opts ] ')' | '(list-schema' type [ sub-opts ] ')'
              | '(map-schema' type schema [ sub-opts ] ')'
              | '(fixed-keys-schema' { key schema } [ sub-opts ] ')' ;
map-schema    = '{' type type '}' ;
type          = jvm-class ;                  (* resolvable JVM class symbol — see schema well-formedness *)
sub-opts      = '{' ':subindex?' 'true' '}' ;
pstate-opts   = '{' { ':global?' bool | ':private?' bool | ':initial-value' value
                     | ':key-partitioner' fn } '}' ;

object-decl   = '(declare-object' 'setup' var expr ')' ;

topology-def  = '(let' '[' topo-var '(stream-topology' 'topologies' string ')' ']' body ')'
              | '(let' '[' topo-var '(microbatch-topology' 'topologies' string ')' ']' body ')' ;
query-topo-def = '(<<query-topology' 'topologies' string sig body '(|origin)' ')' ;
sig           = '[' { var } ':>' binding ']' ;

mirror-decl   = '(mirror-depot' 'setup' depot-var string string ')'
              | '(mirror-pstate' 'setup' pstate-var string string ')'
              | '(mirror-query' 'setup' var string string ')' ;

dynamic-opt   = '(set-launch-module-dynamic-option!' 'setup' option-string value ')'
              | '(set-launch-depot-dynamic-option!' 'setup' depot-name-string option-string value ')'
              | '(set-launch-pstate-dynamic-option!' 'setup' pstate-name-string option-string value ')'
              | '(set-launch-topology-dynamic-option!' 'setup' topology-name-string option-string value ')' ;
(* Option names are dotted strings, NOT keywords. set-launch-topology-dynamic-option!
   takes the topology NAME as a string, not the topology object. Example:
   (set-launch-topology-dynamic-option!
     setup "etl" "topology.microbatch.phase.timeout.millis" 60000) *)
```

| Construct | Syntax |
|---|---|
| Module | `(defmodule M [setup topologies] ...)` |
| Module with explicit name | `(defmodule M {:module-name "M"} [setup topologies] ...)` |
| Anonymous module (tests) | `(module [setup topologies] ...)` |
| Depot (random) | `(declare-depot setup *d :random)` |
| Depot (hash) | `(declare-depot setup *d (hash-by :k))` |
| Depot (internal, no client appends) | `(declare-depot setup *d :disallow)` |
| Depot (global, single partition) | `(declare-depot setup *d :random {:global? true})` |
| Depot (custom partitioner) | `(declare-depot setup *d (defdepotpartitioner ...))` |
| Tick depot | `(declare-tick-depot setup *tick 1000)` |
| PState | `(declare-pstate topo $$p {String Long})` |
| PState (value schema) | `(declare-pstate topo $$p (value-schema Long))` |
| Task global | `(declare-object setup *obj (MyTaskGlobal.))` |
| Stream topology | `(let [s (stream-topology topologies "s")] ...)` |
| Microbatch topology | `(let [mb (microbatch-topology topologies "mb")] ...)` |
| Query topology | `(<<query-topology topologies "q" [*in ... :> *out] ... (\|origin))` |
| Mirror depot | `(mirror-depot setup *d "ns/Mod" "*depot")` |
| Mirror PState | `(mirror-pstate setup $$p "ns/Mod" "$$p")` |
| Mirror query | `(mirror-query setup *q "ns/Mod" "query-name")` |

All `declare-*` and `mirror-*` forms define their symbol argument in lexical scope. Each has a `*` variant (e.g. `declare-depot*`) for programmatic var specification.

### Schema well-formedness

```text
jvm-class     ::= String | Long | Integer | Double | Boolean | Object
                | clojure.lang.Keyword | java.util.UUID | ...
                -- must be a resolvable JVM class symbol
                -- NOT: bare Keyword (unresolvable), {} (empty map literal), nil
```

Schema type rules:
- Schema entries must be resolvable JVM class symbols. `Keyword` → use `clojure.lang.Keyword`. `{}` → use `Object`.
- Narrow schemas (e.g. `{String String}`) reject mismatched writes. Use `Object` for heterogeneous values.

### `:initial-value` rules

```text
:initial-value availability by schema type:

value-schema       ✗  (no options map accepted; Syntax error macroexpanding)
{K V}  (map)       ✗  (Top-level maps cannot have an init value)
vector-schema      ✓
set-schema         ✓
fixed-keys-schema  ✓  (per-key defaults via schema)
```

### PState nil semantics

**`keypath` on absent key navigates to nil (multiplicity 1).** This is distinct from navigators that produce 0 values (`must`, `FIRST`, `set-elem` on absent targets). See `references/paths.md` for navigator multiplicity.

```text
local-select>([(keypath k)] $$p)  where k absent  →  nil     (keypath navigates to nil)
local-select>(STAY $$p)           where $$p is value-schema, never written  →  nil
local-select>([(must k)] $$p)     where k absent  →  0 emits (branch dies)
foreign-select-one([(keypath k)] p)  where k absent  →  nil  (keypath navigates to nil)
```

**`foreign-select-one` contract**: path must navigate to exactly one value. Throws if path produces 0 or >1 values. Safe with `keypath` (always multiplicity 1); unsafe with `must`, `ALL`, `FIRST` on potentially empty data.

Nil-handling patterns (from most to least preferred):

```text
Pattern                          Where           Example
──────────────────────────────────────────────────────────────────────
nil->val in path                 topology write  (nil->val 0) before (term inc)
or> after local-select>          topology read   (or> *v default :> *v)
(or result default)              client read     (or (foreign-select-one ...) default)
```

Nil-safe counter increment: `(local-transform> [(keypath *k) (nil->val 0) (term inc)] $$counts)`.

### Navigator arity constraints

```text
(term f)        -- f is a unary transform function; exactly one argument
(termval v)     -- v is a value-expr (§State interaction EBNF); NOT a navigator
```

`term` takes exactly one argument — a Clojure function or Rama function. To close over dataflow variables, use `<<ramafn` or `partial`:
```clojure
;; Option 1: <<ramafn (preferred — closes over *amount)
(<<ramafn %add [*cur] (:> (+ *cur *amount)))
(local-transform> [(keypath *id) :qty (nil->val 0) (term %add)] $$p)

;; Option 2: partial
(partial + *amount :> *add-fn)
(local-transform> [(keypath *id) :qty (nil->val 0) (term *add-fn)] $$p)
```

### Custom depot partitioner

```clojure
(declare-depot setup *d
  (defdepotpartitioner [*data *num-partitions]
    (mod (hash *data) *num-partitions :> *idx)
    (:> *idx)))
```

---

## Built-in operations (ops/*)

```ebnf
explode         = '(ops/explode' var ':>' binding ')' ;
explode-indexed = '(ops/explode-indexed' var ':>' binding binding ')' ;
explode-map     = '(ops/explode-map' var ':>' binding binding ')' ;
expand          = '(ops/expand' var ':>' binding { binding } ')' ;
range-op        = '(ops/range>' expr expr ':>' binding ')' ;
vget            = '(ops/vget' var expr ':>' binding ')' ;
sum-op          = '(ops/sum' var var ':>' binding ')' ;
current-task    = '(ops/current-task-id' ':>' binding ')' ;
current-mb-id   = '(ops/current-microbatch-id' ':>' binding ')' ;
current-topo    = '(ops/current-topology-type' ':>' binding ')' ;
current-rng     = '(ops/current-random-source' ':>' binding ')' ;
module-info     = '(ops/module-instance-info' ':>' binding ')' ;
```

Operation signatures and emit cardinality:

```text
ops/explode         : Seq α → {:> α}              emits |xs| tuples (0 for empty)
ops/explode-indexed : Seq α → {:> (Long, α)}      emits |xs| tuples (0 for empty)
ops/explode-map     : Map κ ν → {:> (κ, ν)}       emits |m| tuples (0 for empty)
ops/expand          : Tuple → {:> (τ1, ..., τn)}   emits 1 tuple
ops/range>          : (Long, Long) → {:> Long}     emits (end - start) tuples (0 when start ≥ end)
identity            : α → {:> α}                   emits 1 tuple
ops/vget            : (Vec α, Long) → {:> α}       emits 1 tuple (TopoBody/QueryBody/BatchBlock only, §5.10)
ops/sum             : (Num, Num) → {:> Num}         emits 1 tuple
```

Key property: operations that emit **0 tuples** cause all downstream code in that branch to be skipped entirely — no writes, no side effects. For `ops/explode` on an empty collection, this means any `local-transform>` or `depot-partition-append!` after it will not execute. Initialize defaults before the explode path or in a separate branch if writes must happen regardless.

| Operation | Syntax |
|---|---|
| Explode sequence | `(ops/explode *xs :> *x)` |
| Explode with index | `(ops/explode-indexed *xs :> *i *x)` |
| Explode map entries | `(ops/explode-map *m :> *k *v)` |
| Expand tuple to vars | `(ops/expand *tuple :> *a *b *c)` — **avoid in `<<sources` blocks** (causes `Syntax error compiling`); use `(first *tuple :> *a)` / `(second *tuple :> *b)` instead |
| Range | `(ops/range> 0 10 :> *i)` |
| Get element by index | `(ops/vget *vec *idx :> *v)` |
| Sum | `(ops/sum *a *b :> *s)` |
| Current task ID | `(ops/current-task-id :> *tid)` |
| Current microbatch ID | `(ops/current-microbatch-id :> *mbid)` |
| Current topology type | `(ops/current-topology-type :> *type)` |
| Current random source | `(ops/current-random-source :> *rng)` |
| Module instance info | `(ops/module-instance-info :> *info)` |
| Identity passthrough | `(identity *x :> *y)` |

---

## Custom operations

```ebnf
deframaop       = '(deframaop' symbol '[' { var } ']' { topo-stmt } ')' ;  (* OpBody: Suspend allowed *)
deframafn       = '(deframafn' symbol '[' { var } ']' { fn-stmt } ')' ;   (* FnBody: no Suspend *)
anon-ramafn     = '(<<ramafn' frag-var '[' { var } ']' { fn-stmt } ')' ;  (* FnBody *)
anon-ramaop     = '(<<ramaop' frag-var '[' { var } ']' { topo-stmt } ')' ; (* OpBody *)
efficient-ramafn = '(ramafn>' '[' { var } ']' '(:>' expr ')' ')' ;         (* FnBody *)

block-segmacro  = '(defbasicblocksegmacro' symbol '[' { param } ']' { form } ')' ;
single-segmacro = '(defbasicsegmacro' symbol '[' { param } ']' { form } ')' ;
block-consuming = '(defblock' '<<' symbol '[' label-param block-param ']' { form } ')' ;

(* deframaop (OpBody): Suspend allowed, multi-emit on named streams: (:ok> val) (:err> val) *)
(* deframafn (FnBody): no Suspend, emits exactly once via (:> val), callable from Clojure *)
(* %self is implicit in all fragments; invoke for recursion: (%self *arg :> *result) *)
(* :%self-name renames the implicit self-reference: (:%self-name %recurse) *)
```

| Construct | Syntax |
|---|---|
| Rama operation (Suspend ok, multi-emit) | `(deframaop name [*args] (:> val) (:stream> val))` |
| Rama function (no Suspend, single emit) | `(deframafn name [*details] (process *details :> *result) (:> *result))` |
| Java-style operation | `(defoperation name [out> :>] [*args] (out> val))` |
| Anonymous ramafn | `(<<ramafn %f [*x] (:> (inc *x)))` |
| Anonymous ramaop | `(<<ramaop %op [*x] (:> *x) (:> (inc *x)))` |
| Efficient anonymous ramafn | `(ramafn> [*x] (:> (inc *x)))` |
| Block segmacro | `(defbasicblocksegmacro name [args] block ...)` |
| Single segmacro | `(defbasicsegmacro name [args] ...)` |
| Block-consuming segmacro | `(defblock <<name [label block] ...)` |
| Generator | `(defgenerator g [args] (batch<- [*out] ...))` |

### Segmacro utilities

| Utility | Purpose |
|---|---|
| `block>` | Splice segment sequences |
| `seg#` | Distinguish nested segments from plain vectors |
| `:++fields` | Provide field vectors dynamically |
| `gen-anchorvar` | Generate unique anchor var |
| `gen-anyvar` / `gen-anyvars` | Generate unique value var(s) |
| `gen-fragvar` | Generate unique fragment var |
| `gen-pstatevar` | Generate unique PState var |
| `source-segments-as-data` | Convert source forms to evaluable Clojure data |

### Java API integration

| Construct | Syntax |
|---|---|
| Splice Java block | `(java-macro! (.method java-obj *args))` |
| Export Clojure block to Java | `(java-block<- (op1 ...) (op2 ...))` |

---

## Partitioners

```ebnf
partitioner     = hash-part | all-part | global-part | origin-part | shuffle-part
                | direct-part | path-part | custom-part
                | mirror-hash | mirror-all | mirror-direct | mirror-global
                | mirror-path | mirror-custom ;

hash-part       = '(|hash' var ')' ;
all-part        = '(|all)' ;
global-part     = '(|global)' ;
origin-part     = '(|origin)' ;
shuffle-part    = '(|shuffle)' ;
direct-part     = '(|direct' expr ')' ;
path-part       = '(|path' pstate-var var ')' ;
custom-part     = '(|custom' fn-ref { arg } ')' ;

mirror-hash     = '(|hash$$' pstate-var var ')' ;
mirror-all      = '(|all$$' pstate-var ')' ;
mirror-direct   = '(|direct$$' pstate-var expr ')' ;
mirror-global   = '(|global$$' pstate-var ')' ;
mirror-path     = '(|path$$' pstate-var var ')' ;
mirror-custom   = '(|custom$$' pstate-var fn-ref { arg } ')' ;
```

| Partitioner | Syntax |
|---|---|
| Hash | `(\|hash *k)` |
| All tasks | `(\|all)` |
| Global (task 0) | `(\|global)` |
| Origin (query return) | `(\|origin)` |
| Shuffle (round-robin) | `(\|shuffle)` |
| Direct (explicit task) | `(\|direct *task-id)` |
| Path (PState key-partitioner) | `(\|path $$p *k)` |
| Mirror hash | `(\|hash$$ $$mirror *k)` |
| Mirror all partitions | `(\|all$$ $$mirror)` |
| Mirror direct | `(\|direct$$ $$mirror *task-id)` |
| Mirror global | `(\|global$$ $$mirror)` |
| Mirror path | `(\|path$$ $$mirror *path)` |
| Custom | `(\|custom my-fn *args)` |
| Custom (PState-scoped) | `(\|custom$$ $$mirror my-fn *args)` |

Variable transfer and partition ordering: see §6.
## State interaction

See `references/paths.md` for formal path expression semantics and navigator reference.

```ebnf
local-select    = '(local-select>' path pstate-var [ select-opts ] ':>' binding ')' ;
local-select-sub = '(local-select>' '(subselect' path ')' pstate-var ':>' binding ')' ;
select          = '(select>' path pstate-var [ select-opts ] ':>' binding ')' ;
select-opts     = '{' ':allow-yield?' bool '}' ;

local-transform = '(local-transform>' path-with-term pstate-var ')' ;
local-clear     = '(local-clear>' pstate-var ')' ;

compound        = '(+compound' pstate-var compound-agg ')' ;
compound-agg    = '{' { key-expr agg-expr } '}' ;

depot-append-imp = '(depot-partition-append!' depot-var expr ack-level ')' ;
ack-return      = '(ack-return>' expr ')' ;

path-capture    = '(path>' { navigator } ':>' binding ')' ;
compiled-path   = '(compiled-path' { navigator } ')' ;

invoke-query    = '(invoke-query' string { arg } ':>' binding ')'
                | '(invoke-query' var { arg } ':>' binding ')' ;

path            = navigator { navigator } ;
navigator       = '(keypath' { expr } ')'
                | '(subselect' path ')'
                | '(submap' expr ')'
                | '(subset' expr ')'
                | '(nthpath' { expr } ')'
                | '(term' fn ')'
                | '(termval' value-expr ')'             (* value-expr: var | literal | fn-call; excludes navigators *)
                | '(view' fn { arg } ')'
                | '(nil->val' expr ')'
                | '(nil->list)' | '(nil->set)' | '(nil->vector)'
                | '(set-elem' expr ')'
                | '(map-key' expr ')'
                | '(pred' fn ')' | '(pred<' v ')' | '(pred>' v ')'
                | '(pred<=' v ')' | '(pred>=' v ')' | '(pred=' v ')'
                | '(filterer' path ')'
                | '(sorted-map-range' expr expr [ range-opts ] ')'
                | '(sorted-map-range-from' expr expr [ range-opts ] ')'
                | '(sorted-set-range' expr expr [ range-opts ] ')'
                | '(srange' expr expr ')'
                | '(transformed' path fn ')'
                | '(collect' path ')' | '(collect-one' path ')'
                | '(multi-path' { path } ')' | '(cond-path' { path path } ')'
                | 'ALL' | 'FIRST' | 'LAST' | 'MAP-KEYS' | 'MAP-VALS'
                | 'BEGINNING' | 'END' | 'BEFORE-ELEM' | 'AFTER-ELEM'
                | 'STAY' | 'STOP' | 'NONE>' | 'INDEXED-VALS' | 'VAL' ;
range-opts      = '{' ':inclusive-start?' bool ':inclusive-end?' bool '}' ;
```

| Construct | Syntax |
|---|---|
| Read colocated partition | `(local-select> path $$p :> *v)` |
| Read colocated (subselect) | `(local-select> (subselect path) $$p :> *v)` |
| Read with auto-repartition | `(select> path $$p :> *v)` |
| Read with yield | `(select> path $$p {:allow-yield? true} :> *v)` |
| Write colocated partition | `(local-transform> [path (termval *v)] $$p)` |
| Write composite map (multi-logvar) | `(identity {"k1" *a "k2" *b} :> *m) (local-transform> [(keypath *k) (termval *m)] $$p)` |
| Delete entry | `(local-transform> [(keypath *k) NONE>] $$p)` |
| Add to set (aggregator) | `(+compound $$s {*k (aggs/+set-agg *v)})` |
| Remove from set | `(local-transform> [(keypath *k) (set-elem *v) NONE>] $$s)` |
| Clear PState to initial | `(local-clear> $$p)` |
| Aggregated write | `(+compound $$p {...})` |
| Depot partition append | `(depot-partition-append! *depot *data *ack-type)` |
| Capture compiled path (dataflow) | `(path> (keypath *k) AFTER-ELEM (termval 3) :> *path)` |
| Pre-compiled path (foreign client) | `(compiled-path (keypath k))` → use with `compiled-foreign-select-one` etc. |
| Ack return | `(ack-return> *v)` |
| Query invoke in topology | `(invoke-query "q" *arg :> *res)` |

## Source and data ingress

```ebnf
sources-block   = '(<<sources' topo-var { source-form } ')' ;
source-form     = stream-source | microbatch-source | subsource ;
stream-source   = '(source>' depot-var [ source-opts ] ':>' binding ')' ;
microbatch-source = '(source>' depot-var [ source-opts ] ':>' frag-var ')' ;
source-opts     = '{' { ':start-from' start-from | ':retry-mode' retry-mode
                       | ':source-id' string | ':ack-return-agg' agg-expr } '}' ;
start-from      = ':end' | ':beginning'
                | '(offset-ago' number unit ')'
                | '(offset-after-timestamp-millis' number ')' ;
unit            = ':records' | ':days' | ':months' ;
retry-mode      = ':individual' | ':all-after' | ':none' ;
subsource       = '(<<subsource' var { type-clause } ')' ;
type-clause     = class-name '(' dataflow-body ')' ;

(* microbatch emit: *)
microbatch-emit = '(' frag-var ':>' binding ')' ;
```

| Construct | Syntax |
|---|---|
| Subscribe topology to depots | `(<<sources topo ...)` |
| Stream source | `(source> *depot :> *data)` |
| Source with options | `(source> *depot {:start-from :beginning :retry-mode :all-after} :> *data)` |
| Source with ID (multi-source) | `(source> *depot {:source-id "myId"} :> *data)` |
| Ack return aggregation | `(source> *depot {:ack-return-agg (combiner +)} :> *data)` |
| Microbatch source | `(source> *depot :> %microbatch)` |
| Emit microbatch items | `(%microbatch :> *data)` |
| Subsource (type dispatch) | `(<<subsource *data TypeA (...) TypeB (...))` |

---

## Control flow

See [dataflow reference](references/dataflow.md) for control flow EBNF syntax, quick-reference table, destructuring, and context-stratified statement rules.
