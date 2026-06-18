# Unbalanced Social Graph Challenge

Build a module that materializes the follower/followee relationships of a
social graph. The graph is **heavily unbalanced** — follower counts and posting
rates span orders of magnitude:

| Followers | % of accounts | Avg posts/day |
|---|---|---|
| < 100 | 93.6% | 2 |
| 100 – 1,000 | 6.0% | 3 |
| 1,000 – 10,000 | 0.35% | 10 |
| 10,000 – 100,000 | 0.04% | 10 |
| 100,000 – 1,000,000 | 0.009% | 10 |
| 1,000,000+ | 0.001% | 10 |

This module exists to be **consumed by a `fanout` module**. When an account
posts, the fanout module reads that account's followers from your module and
delivers the post to each one. Fanout runs across every task in the cluster
and must stay balanced no matter whose post it is processing.

## The property you must satisfy

An unbalanced social graph must be processable in a **balanced** way across
the cluster. When a post is fanned out to an account's followers, CPU usage
must be spread roughly evenly across the cluster's tasks — and it must stay
that way whether the poster is a small account with a handful of followers or
a celebrity with millions. No single task may become a hotspot.

No account follows more than 5,000 others. Posts arrive at roughly 7,000 per
second, and follows and unfollows at around 100 per second.

The fanout module does **not** call the `SocialGraph` protocol. It reads your
depots and PStates directly. So you must design your depots and PStates to enable
balanced and resource-efficient fanout.


## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module      <RamaModule instance>
 :wrap-client (fn [ipc] -> <SocialGraph implementation>)}
```

- `:module` — your module.
- `:wrap-client` — given a started IPC cluster, returns a reified
  `unbalanced-social-graph.protocol/SocialGraph` implementation.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. Tests
call `(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `unbalanced-social-graph.module`.

## File Location

Write your solution to:
```
implementations/unbalanced-social-graph/src/unbalanced_social_graph/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
