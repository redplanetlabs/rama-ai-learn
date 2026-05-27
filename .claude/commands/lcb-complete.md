---
description: Complete an LCB-ported challenge reference implementation
arguments:
  - name: challenge_name
    description: Name of the challenge to complete
    required: true
---

# LCB Complete Command

Complete the reference implementation for a challenge scaffolded by
`bb lcb-port`.

## Argument Validation

The challenge name is: $ARGUMENTS

First, verify the challenge exists by checking if `challenges/$ARGUMENTS/README.md` exists.

**If the challenge does not exist or $ARGUMENTS is empty:**

List available challenges by finding all directories under `challenges/` that contain a README.md file. Display them as:

```
Available challenges:
- <challenge-name>: <first line of README.md after the title>
```

Then stop and wait for the user to run the command again with a valid challenge name.

**If the challenge exists:**

Proceed with the workflow below.

## Step 1: Read Requirements

Read `challenges/$ARGUMENTS/README.md` to understand:
- The problem statement and examples
- The required `deframafn` name and arguments
- The constraint (pure Rama dataflow, no `defn`/`fn`/`#()`)

## Step 2: Read the Scaffolded Reference Solution

Read `challenges/$ARGUMENTS/test-resources/` to find the solution file.
The scaffolded reference may contain:
- A TODO stub needing full implementation
- A working implementation using `defn-` helpers that violates the
  dataflow constraint

In either case, replace the body with pure Rama dataflow code (using
`loop<-`, `<<if`, `ops/*`, etc.) that satisfies the constraint.

## Step 3: Implement the Reference Solution

Write the reference implementation using only Rama dataflow constructs
inside the `deframafn`. No `defn`, `fn`, or `#()` helpers.

**Note:** The test harness includes an automated assertion
(`harness/assert-no-clojure-fns`) that verifies no public var in the
solution namespace holds a plain Clojure function. Any `defn` in the
solution namespace will cause a test failure.

## Step 4: Verify with Test Harness

```bash
cd challenges/$ARGUMENTS && timeout 360 clojure -X:test-harness
```

**If tests fail:**
1. Analyze the test output to understand the failure
2. Fix the implementation based on the error
3. Increment the attempt counter
4. If attempts < 5: Go back to Step 4
5. If attempts >= 5: Stop and report the failure

Maintain an attempt counter starting at 1 before the first test run.

**If tests pass:** Proceed to Step 5.

## Step 5: Review and Refactor

Run the `rama-dataflow-critic` agent on the reference solution file to
review the Rama dataflow code for correctness, idiomatic usage, and
potential issues.

If the review identifies actionable improvements (e.g. flattening
nested `<<if` to `<<cond`, extracting helper `deframafn`s, sharing
constants), apply them and re-run the test harness to confirm tests
still pass.

## Step 6: Update .lsp/config.edn Source Paths

Add the challenge and implementation source directories to
`.lsp/config.edn` `:source-paths` if not already present:

- `"challenges/$ARGUMENTS/src"`
- `"implementations/$ARGUMENTS/src"`

## Step 7: Commit the Challenge

Stage all new and modified files under `challenges/$ARGUMENTS/`,
`implementations/$ARGUMENTS/`, `.lsp/config.edn` (if modified), and
`CHALLENGE_ORDER.md` (if modified), then commit with:

```
feat: add $ARGUMENTS challenge
```

## Structured Output

**IMPORTANT:** As the very last line of your output, you MUST print a
structured result line in this exact format:

```
LCB_COMPLETE_RESULT:status=pass,iterations=N
```

- `status` is `pass` if tests passed, `fail` otherwise
- `iterations` is the final value of the attempt counter

Examples:
```
LCB_COMPLETE_RESULT:status=pass,iterations=1
LCB_COMPLETE_RESULT:status=fail,iterations=5
```
