# Time Series Module Hard Challenge

Build a time-series module with range statistics where both small and large time-range queries are answered efficiently.

## Protocol

Your implementation must satisfy the `TimeSeriesModuleHard` protocol defined in `src/time_series_module_hard/protocol.clj`:


### Behavior

- the implementation must support efficient large-range queries

### Efficiency requirement

This challenge has a performance requirement in addition to a correctness requirement.

`get-stats-for-range` must be efficient for all range sizes — from a single minute to multiple years. Queries should not do more disk reads than necessary.

## Acceptance criteria

Your implementation must satisfy all of the following:

- `get-stats-for-range` returns the correct aggregate for any half-open minute range `[start-minute-bucket, end-minute-bucket)`.

In other words, the challenge requires both:

- correct results
- efficient large-range query behavior

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <TimeSeriesModuleHard implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `TimeSeriesModuleHard` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must also reify `rama-challenges.harness/Synchronizable`:

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring on that protocol for implementation requirements. Tests call `(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `time-series-module-hard.module`.

## File Location

Write your solution to:
```
implementations/time-series-module-hard/src/time_series_module_hard/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
