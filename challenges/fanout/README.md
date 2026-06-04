# Fanout Challenge

Build a module that stores account profiles, processes posts, and
materializes per-user timelines. Your module runs **alongside** the
provided `fanout.social-graph` module.

Assume the social graph is heavily unbalanced, with most users having less than a hundred followers,
and some having millions.

## What you are given

`fanout.social-graph/SocialGraphModule` (in `src/fanout/social_graph.clj`)
is launched alongside your module by the test harness. Read the module source
to determine how to use it. This module should be used for consuming the social
graph from your module.

## Protocol

Your implementation must satisfy `fanout.protocol/Fanout`. See
`src/fanout/protocol.clj` for the contract.

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

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module      <RamaModule instance>
 :wrap-client (fn [ipc] -> <Fanout implementation>)}
```

- `:module` — your module. The test harness launches
  `fanout.social-graph/SocialGraphModule` alongside it; do not include
  it here.
- `:wrap-client` — given a started IPC cluster, returns a reified
  `Fanout` implementation.

You choose all internal depot/PState/topology names freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `fanout.module`.

## File Location

Write your solution to:
```
implementations/fanout/src/fanout/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
