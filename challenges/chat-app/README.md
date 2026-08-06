# Chat App Challenge

Build the backend of a Slack-like chat product: user registration and
profiles, presence, rooms with membership, room messages, threads,
and per-user derived views — unread counts, a mentions inbox, and a
recent-threads feed.

## Workload

- 10,000,000 users; 1,000,000 rooms.
- A user is a member of at most 1,000 rooms. Rooms range from a handful
  of members to 50,000.
- Messages and thread replies arrive at roughly 2,000 per second
  combined, spread across rooms. Message content contains at most 10
  @-mentions.
- A thread accumulates at most 1,000 distinct participants.
- **Heartbeats arrive at roughly 100,000 per second.** Online users
  heartbeat every ~60 seconds.
- register!, create-room!, join-room!, leave-room! each arrive at
  roughly 100 per second. mark-room-read! arrives at roughly 2,000 per
  second.
- Reads are the dominant load: get-room-page, get-unread-counts,
  get-presence, and get-recent-threads each run at thousands per
  second.

## Protocol

Your implementation must satisfy `chat-app.protocol/ChatApp`. See
`src/chat_app/protocol.clj` for the contract — every docstring bound
(visibility, ordering, pagination, fixed-work, latency) is part of the
spec.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module      <RamaModule instance>
 :wrap-client (fn [ipc] -> <ChatApp implementation>)}
```

- `:module` — your module.
- `:wrap-client` — given a started IPC cluster, returns a reified
  `chat-app.protocol/ChatApp` implementation.

You choose all internal depot/PState/topology names freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`.
Clients call `(harness/wait-for-processing! client)` after writes to
block until all asynchronous processing has completed.

## Namespace

Your solution must be in namespace `chat-app.module`.

## File Location

Write your solution to:
```
implementations/chat-app/src/chat_app/module.clj
```

## nREPL

Start an nREPL with the project classpath:

```bash
clj -M:nrepl
```
