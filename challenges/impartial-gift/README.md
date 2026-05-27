# Impartial Gift

**Source:** atcoder — abc302 (2023-05-20)
**Difficulty:** medium

## Problem

Takahashi has decided to give one gift to Aoki and one gift to Snuke.
There are N candidates of gifts for Aoki,
and their values are A_1, A_2, \ldots,A_N.
There are M candidates of gifts for Snuke,
and their values are B_1, B_2, \ldots,B_M.  
Takahashi wants to choose gifts so that the difference in values of the two gifts is at most D.
Determine if he can choose such a pair of gifts.  If he can, print the maximum sum of values of the chosen gifts.

Input

The input is given from Standard Input in the following format:
N M D
A_1 A_2 \ldots A_N
B_1 B_2 \ldots B_M

Output

If he can choose gifts to satisfy the condition,
print the maximum sum of values of the chosen gifts.
If he cannot satisfy the condition, print -1.

Constraints


- 1\leq N,M\leq 2\times 10^5
- 1\leq A_i,B_i\leq 10^{18`
- 0\leq D \leq 10^{18`
- All values in the input are integers.

Sample Input 1

2 3 2
3 10
2 5 15

Sample Output 1

8

The difference of values of the two gifts should be at most 2.
If he gives a gift with value 3 to Aoki and another with value 5 to Snuke, the condition is satisfied, achieving the maximum possible sum of values.
Thus, 3+5=8 should be printed.

Sample Input 2

3 3 0
1 3 3
6 2 7

Sample Output 2

-1

He cannot choose gifts to satisfy the condition.
Note that the candidates of gifts for a person may contain multiple gifts with the same value.

Sample Input 3

1 1 1000000000000000000
1000000000000000000
1000000000000000000

Sample Output 3

2000000000000000000

Note that the answer may not fit into a 32-bit integer type.

Sample Input 4

8 6 1
2 5 6 5 2 1 7 9
7 2 5 5 2 4

Sample Output 4

14

## Contract

Implement a `deframafn` named `solve` in namespace `impartial-gift.solution`.

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
implementations/impartial-gift/src/impartial_gift/solution.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
