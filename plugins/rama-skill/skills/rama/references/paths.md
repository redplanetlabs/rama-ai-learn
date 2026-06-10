# Rama Path Expressions — Formal Specification

Rama paths are composable traversal optics over nested data structures.
A path is a vector of navigators; each navigator focuses on zero or
more locations within a structure, supporting both reading (select) and
writing (transform).

## Core Model


### Contexts


Paths are used in three contexts:

**Regular Clojure context** — standard Specter operations on in-memory data structures:
- `(select path structure)` — returns vector of all navigated values
- `(select-one! path structure)` — returns one value (error if zero or multiple)
- `(select-first path structure)` — returns first value found
- `(multi-transform path structure)` — transform with inline `term`/`termval` at terminal positions

**Rama dataflow context with PStates** — reads/writes PState data on the current task:
- `(local-select> path $$pstate :> *val)` — select from colocated PState, emits per navigated value
- `(local-select> path $$pstate opts :> *val)` — with options like `{:allow-yield? true}`
- `(local-transform> path $$pstate)` — transform colocated PState in place
- `(select> path $$pstate :> *val)` — select with automatic partition routing based on leading `keypath` (adds network hop)

Note that since dataflow `local-select>` emits per navigated value, there's no need for an equivalent to Clojure's `select-one!`. To emit a sequence of navigated values in dataflow, use `subselect` navigator.

**Foreign client context** — reads PState data from client code over the network:
- `(foreign-select path pstate)` — returns vector of navigated values, selects partition based on leading keypath
- `(foreign-select path pstate opts)` — with options like `{:pkey "alice"}`
- `(foreign-select-one path pstate)` — returns one value (error if zero or multiple)
- `(foreign-select-one path pstate opts)` — with options like `{:pkey "alice"}`

All three contexts use the same path syntax — the navigators are identical. The difference is how the path is executed (in-memory, local PState, or over the network).

Navigators work on any data structure, including PStates which are just durable data structures. There's no difference with subindexed structures. `fixed-keys-schema` locations are just plain maps when it comes to navigation. So a PState with schema like:

```clj
{String (fixed-keys-schema
          {:a (set-schema Long {:subindex? true})
           :b {Long Long}
           :c (map-schema Long Long {:subindex? true})
          })}
```

In dataflow, these are all valid paths on PStates with this schema:

```clj
[(keypath *a :b 3)]
[(keypath *a *k) ALL]
[(keypath "foo") :b MAP-VALS pos?]
```

### Navigator

A navigator `N` is a pair:

```
N = ⟨ select    : α → [β]
    , transform : (β → β|Void) → α → α ⟩
```

`select` returns zero or more focused values.
`transform` applies a function at each focused location, returning
the updated structure. `Void` signals element removal.

### Path Composition

A path `[N₁ N₂ … Nₖ]` composes sequentially:

### Nil semantics

`nil` is treated as an empty collection of whatever type the navigator expects — just like Clojure. This means navigators work naturally on `nil` without defensive wrapping, including but not limited to:

- `(keypath k)` on `nil` → `nil` (missing key in empty map)
- `(must k)` on `nil` → zero values (key absent)
- `MAP-VALS` on `nil` → zero values (no entries)
- `ALL` on `nil` → zero values (no elements)
- `(sorted-map-range start end)` on `nil` → empty submap

Do NOT add `nil->val` defensively around every navigation. Use `nil->val` only when you need a specific non-nil default for computation — e.g., `(nil->val 0)` before `(term inc)` because `inc` cannot take `nil`.

### Primitives: STAY, STOP, NONE>

- **`STAY`** — no-op, stays at the current value:
  ```clojure
  (select STAY {:a 1 :b 2})
  ;; => [{:a 1 :b 2}]

  (transform STAY inc 5)
  ;; => 6
  ```

- **`STOP`** — stops navigation, selects nothing:
  ```clojure
  (select STOP {:a 1 :b 2})
  ;; => []

  (transform [:a STOP] inc {:a 1})
  ;; => {:a 1}  (unchanged — nothing navigated)
  ```

- **`NONE>`** — special navigator that signals removal from the parent collection:
  ```clojure
  (multi-transform [:b NONE>] {:a 1 :b 2 :c 3})
  ;; => {:a 1 :c 3}

  (multi-transform [ALL even? NONE>] [1 2 3 4 5])
  ;; => [1 3 5]
  ```

## Navigator Taxonomy

### Value Navigators — existing sub-values

**`(keypath & ks)`** — navigate to value in an associative data structure by key (map or vector)

Examples:

```clojure
;; Keyword key — bare keyword is sugar for (keypath :a)
(select-one :a {:a 1 :b 2})
;; => 1

;; String key — must use explicit keypath
(select-one (keypath "url-1") {"url-1" 100 "url-2" 200})
;; => 100

;; Missing key navigates to nil
(select-one :x {:a 1})
;; => nil

;; Multi-key — navigates nested structures
(select-one [:a :b] {:a {:b 42}})
;; => 42

;; Mixed — string key needs keypath, keyword doesn't
(select-one [(keypath "user") :name] {"user" {:name "Alice"}})
;; => "Alice"

;; Transform at a key
(multi-transform [:a (term inc)] {:a 1 :b 2})
;; => {:a 2 :b 2}

;; Remove a key
(multi-transform [:b NONE>] {:a 1 :b 2 :c 3})
;; => {:a 1 :c 3}
```

A bare keyword in a path is equivalent to wrapping it in `keypath` — `[:a :b]` is the same as `[(keypath :a) (keypath :b)]`. This also works with variables with keyword values, but bare variables in paths are more expensive for the path compiler. Always wrap variables in explicit `keypath` — `(keypath *field)` not bare `*field`.

Prefer multi-arity — `[(keypath *a *b *c) ALL]` not `[(keypath *a) (keypath *b) (keypath *c) ALL]`.

**`(must & ks)`** — navigate to value in an associative data structure by key if it exists (map or vector), stops if absent

Examples:

```clojure
;; Key exists — navigates to value
(select-one (must :a) {:a 1 :b 2})
;; => 1

;; Key absent — selects nothing (unlike keypath which returns nil)
(select (must :x) {:a 1})
;; => []

;; Multi-key — stops if any key absent
(select-one (must :a :b) {:a {:b 42}})
;; => 42

(select (must :a :b) {:a {:c 3}})
;; => []

;; Remove only if key exists
(multi-transform [(must :a) NONE>] {:a 1 :b 2})
;; => {:b 2}
```

**`(nthpath & indices)`** — navigate to value by index in a sequential data structure (vector or list)

Examples:

```clojure
;; Single index
(select-one (nthpath 2) [:a :b :c :d])
;; => :c

;; Multi-index — navigates nested sequences
(select-one (nthpath 1 0) [[:x :y] [:z :w]])
;; => :z

;; Transform at an index
(multi-transform [(nthpath 0) (term inc)] [10 20 30])
;; => [11 20 30]

;; Remove element at index
(multi-transform [(nthpath 1) NONE>] [10 20 30])
;; => [10 30]
```

Prefer multi-arity — `(nthpath 1 2)` not `(nthpath 1) (nthpath 2)`.

**`FIRST`** — navigates to first element of a sequence. Stops on nil/empty.

Examples:

```clojure
(select-one FIRST [10 20 30])
;; => 10

(select FIRST [])
;; => []  (empty — nothing navigated)

(multi-transform [FIRST (term inc)] [10 20 30])
;; => [11 20 30]

;; Remove first element
(multi-transform [FIRST NONE>] [10 20 30])
;; => [20 30]
```

**`LAST`** — navigates to last element of a sequence. Stops on nil/empty.

Examples:

```clojure
(select-one LAST [10 20 30])
;; => 30

(select LAST [])
;; => []

(multi-transform [LAST (term inc)] [10 20 30])
;; => [10 20 31]

;; Remove last element
(multi-transform [LAST NONE>] [10 20 30])
;; => [10 20]
```

**`ALL`** — navigates to every element of a collection. On lists/vectors: each element. On maps: each `[key value]` map entry. On sets: each element.

Examples:

```clojure
;; Select all elements from a vector
(select ALL [10 20 30])
;; => [10 20 30]

;; Select all entries from a map (each is a [k v] pair)
(select ALL {:a 1 :b 2})
;; => [[:a 1] [:b 2]]

;; Transform every element
(multi-transform [ALL (term inc)] [10 20 30])
;; => [11 21 31]

;; Nested: select all values at key :x from a vector of maps
(select [ALL :x] [{:x 1 :y 2} {:x 3 :y 4}])
;; => [1 3]

;; Remove elements matching a predicate
(multi-transform [ALL odd? NONE>] [1 2 3 4 5])
;; => [2 4]

;; On nil — navigates to zero elements
(select ALL nil)
;; => []
```

**`MAP-VALS`** — navigates to every value in a map.

Examples:

```clojure
(select MAP-VALS {:a 1 :b 2 :c 3})
;; => [1 2 3]

;; Transform all values
(multi-transform [MAP-VALS (term inc)] {:a 1 :b 2})
;; => {:a 2 :b 3}

;; Remove entries where value matches predicate
(multi-transform [MAP-VALS (pred< 3) NONE>] {:a 1 :b 2 :c 3})
;; => {:c 3}

;; Nested: get all values from inner maps
(select [:a MAP-VALS] {:a {:x 1 :y 2}})
;; => [1 2]
```

**`MAP-KEYS`** — navigates to every key in a map.

Examples:

```clojure
(select MAP-KEYS {:a 1 :b 2 :c 3})
;; => [:a :b :c]

;; Transform all keys
(multi-transform [MAP-KEYS (term name)] {:a 1 :b 2})
;; => {"a" 1 "b" 2}
```

**`(map-key k)`** — navigates to the key itself (not its value) if it exists. Changing it renames the key, keeping the same value.

Examples:

```clojure
;; Rename a key
(multi-transform [(map-key :a) (termval :x)] {:a 1 :b 2})
;; => {:x 1 :b 2}
```

**`(set-elem e)`** — navigates to a set element if present; stops if absent.

Examples:

```clojure
;; Remove an element
(multi-transform [(set-elem :b) NONE>] #{:a :b :c})
;; => #{:a :c}

(multi-transform [(set-elem :a) (termval :y)] #{:a :b :c})
;; => #{:b :c :y}
```

**`INDEXED-VALS`** — navigates to `[index, value]` pairs for each element, starting at index 0.

**`(index-nav i)`** — navigates to the index position itself (not the value) if it exists. Changing the index moves the element to a new position.

**`NAME`** — navigates to the name portion of a keyword or symbol.

**`NAMESPACE`** — navigates to the namespace portion of a keyword or symbol.


### Write-only navigators

These are for inserting new elements.

**`AFTER-ELEM`** — append a single element to the end of a sequence.
```clojure
(multi-transform [AFTER-ELEM (termval :d)] [:a :b :c])
;; => [:a :b :c :d]
```

**`BEFORE-ELEM`** — prepend a single element to the beginning of a sequence.
```clojure
(multi-transform [BEFORE-ELEM (termval :z)] [:a :b :c])
;; => [:z :a :b :c]
```

**`END`** — empty subsequence at list end; for appending multiple elements at once.
```clojure
(multi-transform [END (termval [:d :e])] [:a :b :c])
;; => [:a :b :c :d :e]
```

**`BEGINNING`** — empty subsequence at list start; for prepending multiple elements at once.
```clojure
(multi-transform [BEGINNING (termval [:x :y])] [:a :b :c])
;; => [:x :y :a :b :c]
```

**`(before-index i)`** — insert a single element before index i.
```clojure
(multi-transform [(before-index 1) (termval :x)] [:a :b :c])
;; => [:a :x :b :c]
```

**`NONE-ELEM`** — conj a single element to a set.
```clojure
(multi-transform [NONE-ELEM (termval :d)] #{:a :b :c})
;; => #{:a :b :c :d}
```

### Filter Navigators — conditional continuation (0|1 multiplicity)

**Bare Clojure function or deframafn** in path position acts as predicate filter — continues navigation if the function returns truthy, stops if falsy:

```clojure
;; Filter elements of a vector
(select [ALL odd?] [1 2 3 4 5])
;; => [1 3 5]

;; Transform only matching elements
(multi-transform [ALL even? (term inc)] [1 2 3 4 5])
;; => [1 3 3 5 5]
```

**`(pred afn)`** — explicit predicate wrapper (same semantics as bare fn)

**`(pred= v)`** — continue if value equals v

**`(pred< v)`** — continue if value < v

**`(pred<= v)`** — continue if value <= v

**`(pred> v)`** — continue if value > v

**`(pred>= v)`** — continue if value >= v

**`(selected? sub-path)`** — continues navigation only if the sub-path finds something at the current value. Does not change the navigation position.

```clojure
;; Select maps that contain the value "foo"
(select [ALL (selected? MAP-VALS (pred= "foo"))] [{:a "foo" :b 1} {:a "bar" :q "a"} {:c "foo" :e "ab"}])
;; => [{:a "foo" :b 1} {:c "foo" :e "ab"}]

;; Select maps where :a > 2
(select [ALL (selected? :a (pred> 2))] [{:a 1} {:a 5} {:a 2} {:a 10}])
;; => [{:a 5} {:a 10}]
```

**`(not-selected? sub-path)`** — opposite of `selected?`. Continues navigation only if the sub-path finds nothing at the current value.

### Substructure Navigators — sub-collections with position memory

Navigate to a sub-collection as a single value. Transforms on the sub-collection map back to the original positions.

**`(srange from to)`** — contiguous sublist `[from, to)`.
```clojure
(select-one (srange 1 3) [:a :b :c :d :e])
;; => [:b :c]

(multi-transform [(srange 1 3) (termval [:x :y :z])] [:a :b :c :d :e])
;; => [:a :x :y :z :d :e]
```

**`(srange-dynamic start-fn end-fn)`** — sublist with bounds computed from the structure at runtime. `start-fn` receives the structure and returns the start index. `end-fn` receives the structure and the result of `start-fn`, and returns the end index.
```clojure
(select-one (srange-dynamic (fn [_] 1) (fn [s _] (dec (count s)))) [:a :b :c :d :e])
;; => [:b :c :d]
```

**`(filterer cond-path)`** — non-contiguous sublist of elements matching a condition. Remembers original positions — transforming the sub-list writes back to the correct locations.
```clojure
;; Reverse only the odd elements in place, even elements stay put
(transform (filterer odd?) reverse [1 2 3 4 5])
;; => [5 2 3 4 1]
```

**`(submap keys)`** — project specific keys from a map.
```clojure
(select-one (submap [:a :c]) {:a 1 :b 2 :c 3 :d 4})
;; => {:a 1 :c 3}
```

**`(subset elems)`** — navigate to subset containing given elements.

**`(continuous-subseqs bounds-fn)`** — navigates to every continuous subsequence where bounds-fn returns true for elements.

#### Sorted map navigators

Navigate to subranges of sorted maps. On subindexed PState maps: single disk seek + sequential scan.

**`(sorted-map-range start end)`** — subrange `[start, end)`. Options: `{:inclusive-start? bool :inclusive-end? bool}`. Default: start inclusive, end exclusive.
```clojure
(select-one (sorted-map-range 2 5) (sorted-map 1 :a 2 :b 3 :c 5 :e 7 :g))
;; => {2 :b 3 :c}
```

**`(sorted-map-range start end opts)`** — with explicit boundary options `{:inclusive-start? bool :inclusive-end? bool}`.

**`(sorted-map-range-from start)`** — from start key (inclusive) to end of map.

**`(sorted-map-range-from start opts)`** — with options `{:max-amt n :inclusive? bool}`. `:max-amt` scans **forward** from `start`: returns the first `n` entries at/after `start`, ascending.

**`(sorted-map-range-to end)`** — from beginning of map to end key (exclusive).

**`(sorted-map-range-to end opts)`** — with options `{:max-amt n :inclusive? bool}`. `:max-amt` scans **backward** from `end` (exclusive by default; `:inclusive? true` includes it): returns the `n` entries closest to `end` — the *last* `n` entries of the range, not the first `n` of the map. This makes it the tail-read navigator: use it to read the `n` entries nearest a cursor key without iterating the whole range.

```clojure
(select-one (sorted-map-range-from 2 {:max-amt 2}) (sorted-map 1 :a 2 :b 3 :c 5 :e 7 :g))
;; => {2 :b 3 :c}    ;; first 2 entries from 2, scanning forward
(select-one (sorted-map-range-to 7 {:max-amt 2}) (sorted-map 1 :a 2 :b 3 :c 5 :e 7 :g))
;; => {3 :c 5 :e}    ;; last 2 entries before 7 (end exclusive), scanning backward
```

**`(sorted-map-range-from-start max-amt)`** — first `max-amt` entries from beginning of map.

**`(sorted-map-range-to-end max-amt)`** — last `max-amt` entries from end of map.

#### Sorted set navigators

Same semantics as sorted map navigators, applied to sorted sets. On subindexed PState sets: single disk seek + sequential scan.

**`(sorted-set-range start end)`** — subrange `[start, end)`.

**`(sorted-set-range start end opts)`** — with options `{:inclusive-start? bool :inclusive-end? bool}`.

**`(sorted-set-range-from start)`** — from start element (inclusive) onward.

**`(sorted-set-range-from start opts)`** — with options `{:max-amt n :inclusive? bool}`. `:max-amt` scans **forward** from `start` (first `n` elements at/after `start`).

**`(sorted-set-range-to end)`** — up to end element (exclusive).

**`(sorted-set-range-to end opts)`** — with options `{:max-amt n :inclusive? bool}`. `:max-amt` scans **backward** from `end` (last `n` elements before `end`).

**`(sorted-set-range-from-start max-amt)`** — first `max-amt` elements.

**`(sorted-set-range-to-end max-amt)`** — last `max-amt` elements.

### View Navigators — computed value transformation

**`(nil->val v)`** — substitute default for nil

```clojure
;; Increment a counter that might be nil
(multi-transform [:a (nil->val 0) (term inc)] {})
;; => {:a 1}
```

**`NIL->LIST`** — navigates to `'()` if nil, else stays

**`NIL->SET`** — navigates to `#{}` if nil, else stays

**`NIL->VECTOR`** — navigates to `[]` if nil, else stays

**`(NONE->val v)`** — navigates to v if NONE; transform only

**`(view g)`** — navigates to the result of applying function `g` to the current value. Read-only transformation of what's seen downstream. Multi-arity: `(view g arg1 ...)` passes extra args to g. When used with `foreign-select`, `g` must be serializable (on module classpath).

```clojure
;; Select the count of a collection
(select-one [:a (view count)] {:a [1 2 3]})
;; => 3

;; Multi-arity: extra args passed to g
(select-one [:a (view get :x)] {:a {:x 42 :y 99}})
;; => 42
```

**`(multi-transformed path)`** — navigate to value after applying inline transforms specified via term/termval in path

### Value Collection — side-channel accumulation

Orthogonal to navigation. Collected values thread alongside as a vector of values.

**`(collect-one path)`** — collect exactly one value from `path` (throws if `path` navigates to zero or multiple values)

**`(collect path)`** — append vector of values navigated by `path` to accumulated values

**`VAL`** — collects currently navigated value (same as `(collect-one STAY)`)

**`(putval v)`** — append literal to collected values

**`DISPENSE`** — drop all collected values from this point forward in navigation

**`(with-fresh-collected path)`** — navigate with collected values reset to empty

If there are any collected values, select returns
`[[collected₁ collected₂ … navigated-value1] [collected₁2 collected₂2 … navigated-value2] ...]`.

### Control Navigators — flow manipulation

**`(if-path cond-path then-path else-path?)`** — branch based on whether `cond-path` navigates to anything. If it does, follow `then-path`; otherwise follow `else-path` (defaults to STOP).
```clojure
;; Increment :a if :b exists, otherwise increment :c
(multi-transform [(if-path (must :b) [:a (term inc)] [:c (term inc)])] {:a 1 :c 10})
;; => {:a 1 :c 11}

(multi-transform [(if-path (must :b) [:a (term inc)] [:c (term inc)])] {:a 1 :b 2 :c 10})
;; => {:a 2 :b 2 :c 10}
```

**`(cond-path C₁ P₁ C₂ P₂ …)`** — multi-branch, first matching condition wins. A condition is true if it navigates to anything. If no matches defaults to STOP.

**`(multi-path P₁ P₂ … Pₙ)`** — navigate all paths from the same starting point. Select returns the union of all results. Transform applies each path sequentially left-to-right.
```clojure
(select (multi-path :a :c) {:a 1 :b 2 :c 3})
;; => [1 3]

(multi-transform [(multi-path :a :c) (term inc)] {:a 1 :b 2 :c 3})
;; => {:a 2 :b 2 :c 4}
```

**`(subselect sub-path)`** — collects all values navigated by `sub-path` into a single list. Transforms on the list write back to the original positions.
```clojure
(select-one (subselect ALL odd?) [1 2 3 4 5])
;; => [1 3 5]

;; Sort only the odd elements, writing back to their original positions
(multi-transform [(subselect ALL odd?) (term sort)] [5 4 1 2 3])
;; => [1 4 3 2 5]
```
**`(continue-then-stay sub-path)`** — navigate sub-path first, then return to current element. Useful for post-order traversal with recursive paths.

### Terminal Navigators — transform path endings

Every transform path must end with exactly one:

**`(termval v)`** — replace with constant

**`(term f)`** — replace with result of running given function on value

**`NONE>`** — remove element from parent collection

### No-Read Optimizations

Certain terminal operations skip the read on the data structure entirely, which is important when the underlying data structure is RocksDB-based (top-level data structure in PState or subindexed structure):
- `keypath` + `termval` immediately = **write only, no read** (constant replacement doesn't need current value)
- `keypath` + `NONE>` = **delete only, no read**
- `set-elem` + `NONE>` = **delete only, no read**

But `keypath` + further navigation **does read** because it must load the value to navigate into it. This means `multi-path` into fields of a `fixed-keys-schema` triggers a read, even though each branch uses `termval`. When writing an entire record, prefer a single `termval` with the whole map over `multi-path` with per-field `termval`s:

```clojure
;; Causes a read — multi-path navigates into the loaded value
(local-transform> [(keypath *k1 *k2)
                   (multi-path [:field1 (termval *v1)] [:field2 (termval *v2)])]
                  $$pstate)

;; No read — keypath + termval directly
(local-transform> [(keypath *k1 *k2)
                   (termval {:field1 *v1 :field2 *v2})]
                  $$pstate)
```

## Rama Dataflow Integration

### Select — emit per navigated value

```clojure
(local-select> path $$pstate :> *v)
```
Zero values = zero emits = downstream does not execute.

### Transform — must end with term navigator

```clojure
(local-transform> [(keypath *k) (nil->val 0) (term inc)] $$p)
```

### Partition routing

`select>` extracts the first key, routes via PState partitioner,
then performs `local-select>` on the target partition.

### Yielding selects

With `:allow-yield? true` on a local PState, traversal may suspend/resume across events
but operates over a consistent PState snapshot.

Examples: `(local-select> MAP-KEYS $$p {:allow-yield? true} :> *k)` and `(local-select> (subselect MAP-KEYS) $$p {:allow-yield? true} :> *k)`

### Reactive proxy

`(foreign-proxy path pstate)` requires exactly one navigated value. Returns
`ProxyState` receiving fine-grained diffs. Only built-in navigators
support fine-grained diffs.

## Clojure Syntax Mapping

```clojure
;; Keywords as keypath shorthand
[:a :b :c]  ≡  [(keypath :a :b :c)]

;; Bare functions as predicate filter
[ALL even?]  ≡  [ALL (pred even?)]
```

### PState Pagination

```clojure
(with-page-size page-size & path)  ;; control page size for range queries
```

## Common Patterns

```clojure
;; Increment with default
[(keypath *k) (nil->val 0) (term inc)]

;; Set multiple fields
[(keypath *id) (multi-path [:field-a (termval *a)]
                           [:field-b (termval *b)])]

;; Append to nested list
[(keypath *id) :items AFTER-ELEM (termval *item)]

;; Delete from collection
[(keypath *id) NONE>]

;; Range query with filter
[(keypath *id) (sorted-map-range *start *end) ALL
 (selected? [LAST (pred> *threshold)])]

;; Aggregation via subselect
[(keypath *id) (sorted-map-range *start *end)
 (subselect MAP-VALS) (view ops/sum)]

;; Initialize nested structure
[(keypath *id) :items (nil->val []) AFTER-ELEM (termval *item)]

;; Conditional update
[(keypath *id) (if-path [:status (pred= :active)]
                 [:score (term inc)])]

;; Only if key exists
[(must *k) (term inc)]
```

## Navigator arity tips

- `(term f)` — `f` must be unary. For multi-arg transforms use a curried function: `(term (partial + *delta))`.
- Troubleshooting signal: `(term f *extra-arg)` → `ArityException: Wrong number of args (2)`.

## References

- [Paths documentation](https://redplanetlabs.com/docs/~/paths.html)
- [com.rpl.rama.path API](https://redplanetlabs.com/clojuredoc/com.rpl.rama.path.html)
