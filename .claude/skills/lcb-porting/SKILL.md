---
name: lcb-porting
description: Port LiveCodeBench problems to local Rama challenges. Use when listing, filtering, or importing competitive programming problems from LiveCodeBench into the challenge framework.
---

# LiveCodeBench Porting

Port competitive programming problems from LiveCodeBench to local Rama challenges.

## Available Commands

### List problems

```bash
bb lcb-list [--difficulty easy|medium|hard]
            [--platform leetcode|codeforces|atcoder]
            [--type functional|stdin]
            [--after YYYY-MM-DD] [--before YYYY-MM-DD]
            [--limit N] [--id QUESTION_ID] [--search TEXT]
```

### Scaffold a challenge

```bash
bb lcb-port --id QUESTION_ID [--name CHALLENGE_NAME]
```

### Refresh cache

```bash
bb lcb-fetch [--force]
```

## Problem Types

**functional** (LeetCode): Input is a JSON array of function arguments.
The generated `deframafn` receives args directly.

**stdin** (Codeforces/AtCoder): Input is a raw string. The generated
`deframafn` named `solve` takes the input string and returns the output string.

## The deframafn Pattern

Every ported challenge uses a single top-level `deframafn`:

```clojure
(ns my-challenge.solution
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

(deframafn my-func [*arg1 *arg2]
  ;; algorithm here
  )
```

Tests call the `deframafn` directly — no module, protocol, or harness needed.

## Constraint

All computation logic must live inside the `deframafn`. No `defn`, `fn`,
or `#()` helpers. This constraint is specific to these challenges and
overrides more general advice given in any skills. The test harness
enforces this via `harness/assert-no-clojure-fns`, which rejects any
public var that is a plain Clojure function.

### View decoded private tests from cache

```bash
bb lcb-private-tests --id QUESTION_ID [--private-only] [--format edn|json] [--out PATH]
```

### Migrate existing LCB challenges to private-test layout

```bash
bb lcb-migrate-private-tests [--challenge CHALLENGE_NAME]
```

### Verify LCB private-test migration

```bash
bb verify-lcb-private-tests [--challenge CHALLENGE_NAME]
```

## Challenge Order Tracking

`bb lcb-port` automatically adds ported challenges to `CHALLENGE_ORDER.md`
under the appropriate section: `## LCB Easy`, `## LCB Medium`, or `## LCB Hard`.
Sections are created if they don't exist.

## After Scaffolding

The generated reference solution has a TODO stub. To complete manually:

1. Run `/lcb-complete <name>` to implement the reference solution's `deframafn`
2. Run `cd challenges/<name> && clojure -X:test-harness` to verify
3. Run `bb run-challenges -f <name>` to run the agent challenge (private tests run automatically after)
