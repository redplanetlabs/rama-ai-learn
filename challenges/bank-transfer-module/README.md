# Bank Transfer Module Challenge

Build a banking module tracking balance and transfer history. Implementation must be fault-tolerant and never double process or fail to process successful appends of deposits or transfers. The implementation can assume the same transfer ID will never be appended twice.

## Protocol

Your implementation must satisfy the `BankTransferModule` protocol defined in `src/bank_transfer_module/protocol.clj`.
User IDs are strings.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <BankTransferModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `BankTransferModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring on that protocol for implementation requirements. Tests call `(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `bank-transfer-module.module`.

## File Location

Write your solution to:
```
implementations/bank-transfer-module/src/bank_transfer_module/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
