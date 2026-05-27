# diagnose-stream-runtime-fail

A Rama module with a streaming topology is already deployed to the running cluster.

Your job is to append a value to the depot and wait for processing to complete successfully.

Diagnose the cause of any issues you have doing this, and writ the diagnosis to:

`@./implementations/diagnose-stream-runtime-fail/answer.md`

## What to do

1. Connect to the running cluster from an external JVM client.
2. Get a handle to depot `*payments` in module `com.rpl.challenges.stream-ack-fail/StreamAckFailModule`.
3. Append the data below using `foreign-append!` with `:ack`:

```clojure
{:user-id "u1" :amount "abc"}
```

4. Use the conductor Web UI/API and runtime diagnostics to determine the cause of any failure.
6. Write the diagnosis to `@./implementations/diagnose-stream-runtime-fail/answer.md`.

## Important

- Do **not** modify code.
- Do **not** redeploy anything.
- Diagnose the already-deployed runtime only.

## Notes

- The module is already deployed successfully.
- The topology is a **streaming** topology.
- This challenge is about Rama `:ack` behavior for stream processing completion, not merely depot durability.
- The conductor Web UI/API is available on port `8888`.
- For host-side JVM clients against the containerised local cluster, you may need
  `-Djdk.net.hosts.file=/tmp/rama-hosts` with entries mapping `zookeeper`,
  `conductor`, and `supervisor` to `127.0.0.1`, because ZooKeeper can publish
  internal container hostnames that do not resolve from the host.

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
