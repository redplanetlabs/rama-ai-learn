# Attack

**Source:** atcoder — abc302 (2023-05-20)
**Difficulty:** easy

## Problem

There is an enemy with stamina A.  Every time you attack the enemy, its stamina reduces by B.
At least how many times do you need to attack the enemy to make its stamina 0 or less?

Input

The input is given from Standard Input in the following format:
A B

Output

Print the answer.

Constraints


- 1 \le A,B \le 10^{18`
- A and B are integers.

Sample Input 1

7 3

Sample Output 1

3

Attacking three times make the enemy's stamina -2.
Attacking only twice makes the stamina 1, so you need to attack it three times.

Sample Input 2

123456789123456789 987654321

Sample Output 2

124999999

Sample Input 3

999999999999999998 2

Sample Output 3

499999999999999999

## Contract

Implement a `deframafn` named `solve` in namespace `attack.solution`.

The function receives a single input string (stdin format) and returns
the output string.
Tests call it directly: `(solve input-string)`

### Constraint

All computation must use Rama dataflow code. You may call existing
Clojure functions (e.g. `conj`, `count`, `inc`) but must not define any
local functions (`defn`, `fn`, `#()`).  This constraint is specific to
this challenge, and overrides the more general advice given in any
skills.

## Test Commands

- Public challenge tests: `clojure -X:test`
- Private verification tests (post-challenge): `clojure -X:test-private`

## File Location

Write your solution to:
```
implementations/attack/src/attack/solution.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
