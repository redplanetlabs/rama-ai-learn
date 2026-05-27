# Auction Module Challenge

Build an auction system with listings, bids, expiration, and notifications of sales, wins, and losses.

## Protocol

Your implementation must satisfy the `AuctionModule` protocol defined in `src/auction_module/protocol.clj`. The implementation must use simulated time from rama-helpers when checking for the current time to check which auctions have expired.

**"Seller IDs" must be of type `Long`.**

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <AuctionModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `AuctionModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring on that protocol for implementation requirements. Tests call `(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `auction-module.module`.

## File Location

Write your solution to:
```
implementations/auction-module/src/auction_module/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
