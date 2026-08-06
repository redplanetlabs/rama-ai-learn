# Social Graph and Fanout Challenge

Build a module that materializes the follower/followee relationships of a
social graph and processes posts into per-user timelines.

Follower counts span orders of magnitude, and **the distribution
is not fixed** — your module must be near-optimal for **any** social graph
distribution, not tuned to one. Three it must handle equally well (`%` = share
of accounts):

**Example A — heavy-tailed (most accounts tiny, a few celebrities):**

| Followers | % of accounts | Avg posts/day |
|---|---|---|
| < 100 | 93.6% | 2 |
| 100 – 1,000 | 6.0% | 3 |
| 1,000 – 10,000 | 0.35% | 10 |
| 10,000 – 100,000 | 0.04% | 10 |
| 100,000 – 1,000,000 | 0.009% | 10 |
| 1,000,000+ | 0.001% | 10 |

**Example B — mid-heavy (a large share of accounts have 10k–100k followers):**

| Followers | % of accounts | Avg posts/day |
|---|---|---|
| < 100 | 25% | 2 |
| 100 – 1,000 | 20% | 3 |
| 1,000 – 10,000 | 20% | 10 |
| 10,000 – 100,000 | 30% | 10 |
| 100,000 – 1,000,000 | 5% | 10 |

**Example C — flat (accounts spread evenly across sizes):**

| Followers | % of accounts | Avg posts/day |
|---|---|---|
| < 100 | 20% | 2 |
| 100 – 1,000 | 20% | 3 |
| 1,000 – 10,000 | 20% | 10 |
| 10,000 – 100,000 | 20% | 10 |
| 100,000 – 1,000,000 | 20% | 10 |

When an account posts, the post is fanned out: delivered to the timeline of
every follower of that account.

## The property you must satisfy

When many posts are processed concurrently, CPU usage across the cluster's tasks
must be even, and the total disk work must be **near-optimal** — within a small
constant factor of the least work any design could do for the same workload.
This must hold for **every** distribution above, and any other. A design tuned
to a single distribution — efficient on one but doing far more work than
necessary on another — does not satisfy this.

No account follows more than 5,000 others. Posts arrive at roughly 7,000 per
second, and follows and unfollows at around 100 per second.

## Constraints

1. Fanout delivery MUST NOT write to any PState or depot per follower.
   The write volume of O(followers × posts) is too expensive for durable
   storage. The per-follower writes during fanout can only be to
   in-memory state.

   In-memory state is lost on worker restart. You MUST find another way
   to achieve fault tolerance.
2. Fanout must be **balanced** across tasks.
3. Fanout must be **fair**. The delay a post's fanout imposes on any
   other post must be bounded — not proportional to the first post's
   follower count.
4. Fanout for every post must complete eventually — no permanent backlog.
5. Can assume a user does not post more than once every 5 seconds.

## Protocol

Your implementation must satisfy
`social-graph-and-fanout.protocol/SocialApp`. See
`src/social_graph_and_fanout/protocol.clj` for the contract.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module      <RamaModule instance>
 :wrap-client (fn [ipc] -> <SocialApp implementation>)}
```

- `:module` — your module.
- `:wrap-client` — given a started IPC cluster, returns a reified
  `social-graph-and-fanout.protocol/SocialApp` implementation.

You choose all internal depot/PState/topology names freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. Tests
call `(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `social-graph-and-fanout.module`.

## File Location

Write your solution to:
```
implementations/social-graph-and-fanout/src/social_graph_and_fanout/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
