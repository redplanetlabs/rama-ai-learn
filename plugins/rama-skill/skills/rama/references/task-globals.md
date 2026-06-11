# TaskGlobal Reference

A `TaskGlobal` is a per-task mutable object with a managed lifecycle. It holds non-durable, task-local resources — caches, external clients, ML models, connection pools — that must be initialized per task and cleaned up on shutdown.

Each task gets its own instance (serialized and deserialized independently). TaskGlobals do not transfer across tasks or partitioners. Referencing a task global var after a partitioner accesses the **destination** task's instance — the var always resolves to the instance of whatever task the dataflow is currently executing on.

A task global can be read and written to from any topology (stream, microbatch, query). They are not owned by topologies like PStates are.

## Formal model

```text
TaskGlobal ∈ Module
Owner(TaskGlobal) = Mod M          -- module-scoped, not topology-scoped

TaskGlobal : ∀ task ∈ {0..N-1}. prepareForTask(task, ctx) → instance_task
           : close() → Unit

TaskGlobalWithTick extends TaskGlobal :
           : getFrequencyMillis() → Long
           : tick() → Unit ! {Tick}
```

TaskGlobals are accessible from any topology within the owning module. They are not accessible cross-module (no mirror equivalent).

TaskGlobals can be **mutated** from any topology type — stream, microbatch, and query topologies alike. The "read-only" restriction on query topologies applies to PStates, not task globals. Tasks are single-threaded, so mutation is safe without locking wherever it happens.

## Declaration

`declare-object` values follow the same rules as constants embedded in dataflow code (see dataflow.md). Invalid values fail at module launch with `Object cache disallowed {:class ...}`.

```clojure
;; Plain value (no lifecycle)
(declare-object setup *my-val 42)

;; Object implementing TaskGlobalObject
(declare-object setup *tg (MyTaskGlobal. config))
```

First argument is always `setup` (not a topology var).

## Syntax (EBNF)

```ebnf
object-decl = '(declare-object' 'setup' var expr ')' ;
```

## Java interfaces

### `TaskGlobalObject` (`com.rpl.rama.integration.TaskGlobalObject`)

```java
public interface TaskGlobalObject {
  void prepareForTask(int taskId, TaskGlobalContext context);
  void close() throws IOException;
}
```

- `prepareForTask` — called during worker startup; specialize per task (e.g. set task-specific keys, open connections).
- `close` — called on worker shutdown; release resources. Critical for clean test teardown.

### `TaskGlobalObjectWithTick` (`com.rpl.rama.integration.TaskGlobalObjectWithTick`)

```java
public interface TaskGlobalObjectWithTick extends TaskGlobalObject {
  long getFrequencyMillis();
  void tick();
}
```

- `getFrequencyMillis` — tick interval in ms.
- `tick` — periodic callback (cache refresh, heartbeat, metrics flush).

### `TaskGlobalContext` (`com.rpl.rama.integration.TaskGlobalContext`)

Provided to `prepareForTask`:

| Method | Returns | Purpose |
|---|---|---|
| `getModuleInstanceInfo()` | `ModuleInstanceInfo` | Module identity and instance metadata |
| `getTaskGroup()` | `List<Integer>` | All task IDs sharing this task thread |

## Access from topology code

TaskGlobals declared via `declare-object` are available as logvars (`*tg`) in all topologies of the owning module.

```clojure
(declare-object setup *cache (MyCacheGlobal.))

(<<sources s
  (source> *events :> *data)
  ;; *cache is accessible directly — wrap Java interop in a defn
  (cache-lookup *cache *data :> *result))
```

## Access from outside owning topology

| Function | Purpose |
|---|---|
| `declared-object-task-global` | Access a `declare-object` task global from a different topology |
| `query-topology-task-global` | Access query topology's implicit task global |
| `this-module-query-topology-task-global` | Access current module's query topology task global |
| `this-module-pobject-task-global` | Access current module's PState object task global |

## Memory usage

TaskGlobals live on the JVM heap. Footprint = per-task size × tasks per worker.

- Cap anything keyed by an unbounded set (entity IDs, sessions, time). Use a fixed max, LRU, or TTL. No cap = leak.
- Use compact representations where possible, such as primitive arrays instead of persistent collections of boxed values.

## Resource sharing pattern

Multiple tasks on the same task thread can share a single resource (e.g. one DB connection per thread):

```java
public class SharedResourceTaskGlobal implements TaskGlobalObject {
  static ConcurrentHashMap<List, Closeable> shared = new ConcurrentHashMap<>();
  List resourceId;
  Closeable resource;
  boolean owner = false;

  @Override
  public void prepareForTask(int taskId, TaskGlobalContext ctx) {
    int threadId = Collections.min(ctx.getTaskGroup());
    resourceId = Arrays.asList(
      ctx.getModuleInstanceInfo().getModuleInstanceId(),
      threadId);
    if (shared.containsKey(resourceId)) {
      resource = shared.get(resourceId);
    } else {
      resource = openResource();
      shared.put(resourceId, resource);
      owner = true;
    }
  }

  @Override
  public void close() throws IOException {
    if (owner) {
      resource.close();
      shared.remove(resourceId);
    }
  }
}
```

Key: use `(apply min (.getTaskGroup ctx))` as thread identity to deduplicate.

## Usage hints

- **Not durable** — TaskGlobals are recreated on worker restart. Use PStates for durable state.
- **Not transferable** — a partitioner moves execution to another task; the destination task has its own TaskGlobal instance.
- **Serializable** — the constructor argument is serialized to each task via Java serialization. Keep it small. Types like `AtomicInteger` lack registered serializers — use primitive arrays (e.g. `long-array`) or plain values instead.
- **Do NOT mark deftype fields `^:unsynchronized-mutable` or `^:volatile-mutable`.** Those modifiers make the field private at the JVM level (not reflectively accessible), which breaks the reflection-based field reads Rama's serializer performs when shipping the TaskGlobal to each task. Instead, declare the field as a plain (immutable) reference to a mutable container — e.g. `[^HashMap state]` or `[^objects holder]` — and mutate the container's *contents* in your methods (`.clear`, `.put`, `aset`, etc.) rather than replacing the field with `set!`. Initialize per-task contents in `prepareForTask`. The deftype reference itself stays immutable; only what it points at changes.
- **Testing** — always implement `close()` to release resources; `InProcessCluster` calls it on `close!`.
- **Tick for polling** — use `TaskGlobalObjectWithTick` for periodic external system polling, cache invalidation, or metrics emission. Prefer tick depots when the periodic work needs to update PStates.
- **Plain values** — `(declare-object setup *v 42)` works for simple constants shared across all tasks. No interface needed.
