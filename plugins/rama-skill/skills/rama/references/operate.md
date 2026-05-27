# Operating Rama Clusters

Reference: https://redplanetlabs.com/docs/~/operating-rama.html#gsc.tab=0

## Cluster Architecture

Three daemon types:
- **Conductor** -- coordinates module operations, serves Cluster UI
- **Supervisor** -- runs on each worker node, manages worker processes
- **Workers** -- JVM processes executing module code

External dependency: **Zookeeper** for cluster metadata and leader election (via internal "Metastore" abstraction).

Requirements: UNIX (Linux/macOS), Java 8/11/17/21, Python 3. The `rama` CLI script must be from the same release version as the cluster. Client Rama version must match cluster version (same major and minor).

Release contents: `rama` (CLI script), `rama.jar`, `lib/` (Rama + dependency jars; add extra jars here for classpath), `rama.yaml`, `log4j2.properties`, `logs/`.

## Cluster Setup

### Conductor `rama.yaml`

```yaml
zookeeper.servers:            # required
  - "zk-host-1"
  - "zk-host-2"
local.dir: "/data/rama"       # recommended (default: local-rama-data/)
# Optional:
# license.dir: "/path/to/licenses"
# zookeeper.port: 2000        (default)
# zookeeper.root: "rama"      (default)
# conductor.port: 1973        (default)
# cluster.ui.port: 8888       (default)
# conductor.child.opts: "-Xmx1024m" (default)
# cluster.metadata: "prod-cluster"  (displayed in Cluster UI banner)
```

Launch: `./rama conductor`

### Supervisor `rama.yaml`

```yaml
conductor.host: "conductor-host"  # required
zookeeper.servers:                 # required
  - "zk-host-1"
  - "zk-host-2"
local.dir: "/data/rama"           # recommended
# Optional:
# zookeeper.port: 2000            (default)
# zookeeper.root: "rama"          (default)
# conductor.port: 1973            (default)
# supervisor.port.range: [3000, 4000] (default; use large range)
# supervisor.host: auto-detected (InetAddress.getLocalHost)
# supervisor.child.opts: "-Xmx256m"  (default)
# supervisor.labels: []           (for heterogeneous clusters)
```

Launch: `./rama supervisor`

Use process supervision (systemd/monit) for both daemons. Configure Supervisor's process manager to NOT kill child processes if Supervisor crashes -- workers keep running independently (Supervisor crash has no effect on running workers).

Configure worker nodes with **swap space** -- module updates temporarily run two sets of workers on the same nodes.

### Logs

Logs in `logs/` of each unpacked release. Configure via `log4j2.properties`. Also viewable through Cluster UI from Conductor, Supervisor, or Worker pages. Use Log4j `Logger` class for custom logging in module code (goes to worker log files).

### Single-Node Setup

Run Zookeeper, Conductor, and one Supervisor on the same node. Dev Zookeeper: `./rama devZookeeper` (not for production). They share the same `rama.yaml` since each uses an independent sub-directory within `local.dir`.

### Licensing

Free license: up to 2 Supervisor nodes. Conductor/Zookeeper don't count. License specifies max nodes and valid date range. Commands run on Conductor node only:

```
./rama upsertLicense --licensePath /path/to/license.edn
./rama cleanupLicenses
./rama licenseInfo
```

License states: active, expired, superseded, future. Active license = max nodes across all valid licenses. During module transitions, only new instance workers count toward limit. Expiring license shows a banner warning on every Cluster UI page. If no valid license exists, cluster auto-shuts down (no data loss); add license and restart Conductor to resume.

### Internal/External Hostnames

For environments like AWS where internal and external hostnames differ:

```yaml
conductor.host:
  internal: "10.0.0.1"
  external: "54.1.2.3"
zookeeper.servers:
  - internal: "10.0.0.2"
    external: "54.1.2.4"
```

Daemons always use internal hostnames. Clients choose via `RamaClusterManager.open()` (external) or `.openInternal()` (internal). Same `rama.yaml` can be shared across all daemons and clients.

## Rama CLI Commands

To use CLI outside a cluster, configure `conductor.host` in `rama.yaml` in the same directory as the `rama` script.

| Command | Purpose |
|---|---|
| `rama conductor` | Launch a Conductor daemon |
| `rama supervisor` | Launch a Supervisor daemon |
| `rama deploy --action launch ...` | Launch new module |
| `rama deploy --action update ...` | Update existing module |
| `rama deploy --action upgrade ...` | Upgrade module to new Rama version |
| `rama scaleExecutors ...` | Scale workers/threads/replication |
| `rama destroy <moduleName>` | Destroy module (fails if dependents exist) |
| `rama moduleStatus <moduleName>` | Print module status |
| `rama taskGroupsStatus <moduleName> <instanceId>` | Print task group status for a module instance |
| `rama pauseTopology <moduleName> <topoName>` | Pause microbatch topology |
| `rama resumeTopology <moduleName> <topoName>` | Resume microbatch topology |
| `rama setOption <module,topology,pstate,depot> <moduleName> [<objectName>] <optionName> <valueJSON>` | Set dynamic option |
| `rama shutdownCluster` | Graceful cluster shutdown: completes in-flight processing, stops all workers but not Conductor/Supervisor processes (Conductor node only) |
| `rama disallowLeaders <supervisorId>` | Move leaders off node (one node at a time only) |
| `rama allowLeaders <supervisorId>` | Re-allow leaders on node |
| `rama supportBundle [--maxLogSizePerNode <GB>]` | Collect logs + metadata zip |
| `rama monitoringConfig ...` | Configure monitoring retention |
| `rama confValue <configName>` | Print configured value from local release |
| `rama upsertLicense --licensePath <path>` | Add license (Conductor node only) |
| `rama cleanupLicenses` | Remove expired licenses (Conductor node only) |
| `rama licenseInfo` | Print all installed license information |
| `rama runClj <ns> <jar>+` | Run Clojure ns with Rama on classpath |
| `rama runJava <class> <jar>+` | Run Java class with Rama on classpath |
| `rama devZookeeper` | Launch dev Zookeeper (not for production) |
| `rama help` | Show all available CLI commands |

Add `--useInternalHostnames` on commands that support it (destroy, moduleStatus, taskGroupsStatus, pauseTopology, resumeTopology, setOption, supportBundle).

## Launching Modules

```
rama deploy \
  --action launch \
  --jar target/app.jar \
  --module com.mycompany.MyModule \
  --tasks 64 \
  --threads 16 \
  --workers 8 \
  --replicationFactor 3
```

Key constraints:
- `--tasks` must be power of 2
- tasks >= threads >= workers
- `--replicationFactor` optional (default 1); recommended higher for fault-tolerance
- Total JVM processes = replicationFactor x workers
- Two task thread replicas never share a node
- Power-of-2 threads/workers = perfectly balanced load; non-power-of-2 causes some skew
- Recommended: no more than 32 tasks per thread

For Clojure modules, `--module` is the namespace-qualified var: `com.mycompany/FooModule`.

Module name defaults to module class name, but can be overridden via `getModuleName`. Most CLI commands take module name, not class.

Can target specific supervisor labels for heterogeneous clusters.

### Uberjar

The jar must contain module code + all dependencies not provided by Rama. Set `rama` dependency scope to `provided` to exclude it from the uberjar (prevents 100x+ size bloat).

### Worker Configurations

Set via `--configOverrides overrides.yaml` (path to YAML file):

```yaml
worker.child.opts: "-Xmx8192m"    # default: "-Xmx4096m"
custom.serializations:
  - "com.myapp.MySerialization"
```

Config overrides are per module instance -- must be re-specified on each update. Not inherited.

### Dynamic Options

Four levels: module, topology, depot, PState. More specific overrides less specific (CSS-like cascade). A depot option checks: depot-level -> module-level -> default.

Set on launch:

```
--moduleOptions 'replication.streaming.timeout.millis=20000;pstate.batch.buffer.limit=10'
--topologyOptions 'myTopo,topology.combiner.limit=99'
--depotOptions '*depot,depot.max.fetch=251'
--pstateOptions '$$mb,pstate.reactivity.queue.limit=499;$$stream,pstate.reactivity.queue.limit=89'
```

Syntax: module options use `key=value;key=value`. Other levels use `entity,key=value;entity,key=value`.

Also modifiable at runtime via Cluster UI or `rama setOption`. Dynamic options are set at the module level (persist across module instances), unlike config overrides which are per instance.

## Isolation Scheduler

Default "dev scheduler" spreads workers across all nodes, sharing nodes between modules. For production, use the **isolation scheduler** to dedicate nodes per module:

```yaml
# Conductor rama.yaml
conductor.assignment.mode:
  type: isolation
  modules:
    com.mycompany.Module1: 4
    com.mycompany.AnotherModule: 8
    monitoring: 2
```

Only listed modules can be launched. Requires Conductor restart on config change. Deploy of unlisted module or insufficient free nodes throws exception.

Transitioning dev->isolation: existing modules unaffected until next update. Run no-op `scaleExecutors` (no changed values) to move a module to isolated nodes without code change.

Recommendation: dev scheduler for development cluster, isolation scheduler for production.

## Updating Modules

```
rama deploy \
  --action update \
  --jar app-1.2.1.jar \
  --module com.mycompany.MyModule \
  --configOverrides overrides.yaml
```

- Parallelism settings (tasks/threads/workers) carry over unchanged
- Config overrides do NOT carry over -- must re-specify each time
- Supervisor label must also be re-specified if applicable
- Can add/remove topologies, PStates, depots; migrate PState schemas
- Deploy validates changes (e.g. rejects removing PState/depot depended on by another module)
- Deleting PStates/depots requires `--objectsToDelete '$$p,*depot,$$anotherPState'` (must match exactly)
- Cannot rename PStates/depots; naming determines carryover
- PState clients have zero downtime; reactive queries auto-resync
- Depot appends may buffer briefly clientside during transition
- Stream/microbatch processing offline briefly (2-30 seconds depending on PState count/size)
- Transitioning dev->isolation scheduler may force worker relocation to achieve isolation
- Module status cycles through intermediate states (`[:updating-next-assigned]`, `[:updating-prepare-handover]`, etc.) then returns to `[:running]`

### Node Decommissioning

```
rama deploy \
  --action update \
  --reassignSupervisors <supervisorId> \
  --jar app.jar \
  --module com.mycompany.MyModule
```

- Supervisor IDs found in Cluster UI "Supervisors" tab
- Comma-separated list for multiple supervisors
- Respects isolation scheduler; requires spare capacity
- No reliance on communicating with target node -- assumes worst case
- Kill Supervisor and workers on the bad node first to prevent future assignments
- Separate update needed per module with workers on that node

Use `--forceReassign` if the node is the sole ISR member for any task group (causes data loss for those partitions -- extremely unlikely with replication factor 3 and min-ISR 2).

## Scaling Modules

```
rama scaleExecutors \
  --module com.mycompany.MyModule \
  --threads 90 \
  --workers 30 \
  --replicationFactor 3
```

- `--module` is module name (not class)
- All flags optional; unspecified values preserved
- Config overrides DO carry over (unlike update)
- No-op scaling (no changed values) useful for dev->isolation scheduler transition
- Task count cannot be changed (first-class support planned)
- Scaling takes longer than update due to data copying between nodes

### When to Scale

- **Task groups load** > 70% -- scale soon (other threads/processes also use CPU)
- Microbatch **progress** and **size** lines diverging -- not keeping up
- Test on dev cluster to measure throughput vs. nodes; also mimic PState query and query topology load

### Task Planning

Tasks are the upper bound on parallelism. Choose in anticipation of 2-year growth. ~16 tasks per thread is reasonable. Tasks cannot be changed after launch.

Manual task rescaling workaround: launch new module with more tasks, mirror depots, replay from beginning, then redirect clients. Only works for deterministic processing. Alternative: special topology to repartition PStates directly. Both require careful coding and significant downtime.

## Self-Monitoring

Deploy the monitoring module:

```
rama deploy \
  --action launch \
  --systemModule monitoring \
  --tasks 32 \
  --threads 8 \
  --workers 4 \
  --replicationFactor 2
```

Uses `--systemModule` instead of `--module`; no `--jar` needed. Otherwise operates like any module. Rule of thumb: 1 monitoring worker per 70 other workers. Highly recommended on every cluster.

For isolation scheduler, reference as `monitoring` in the modules config.

Configure telemetry retention:

```
rama monitoringConfig --setRetention 60,2592000   # 30 days at minute granularity
rama monitoringConfig help                         # full retention options
```

Times in seconds: `<granularity>,<retention_window>`.

## Cluster UI

Default port 8888 on Conductor (`cluster.ui.port` config). Time window selector (hour to year). Auto-refresh button refreshes every 10 seconds when enabled.

### Main Page
- Module list with states (`[:running]`, `[:updating-...]`, `[:leadership-balancing]`)
- `[:leadership-balancing]` = Rama rebalancing leaders across workers after node outages
- Supervisors tab: node info, memory, GC, system load average
- Conductor tab: process telemetry, license info

### Module Page
- Module instances (usually one; multiple during update/scale transitions)
- Read target / append target (internal coordination flags)
- Module-level dynamic options (with hover descriptions)

### Module Instance Page
- Lists all depots, PStates (including internal), topologies
- Aggregated telemetry charts across all workers:
  - **Task groups load** -- % time executing vs. idle (key scaling metric)
  - **System event throughput/duration (sampled)** -- fine-grained event categories
  - Sampling rate adjustable via `worker.event.telemetry.sampling.rate` (high rates impact performance)

### Event Telemetry Categories
Hierarchical (e.g. `[:all :replication :leader :forwarding]`). Select any node in hierarchy via dropdown. Categories with `:topology-event` execute module code (e.g. `[:all :microbatch :whoToFollow :topology-event]`).

### Per-Entity Pages
- **Workers**: same telemetry scoped to individual worker (useful for detecting skew)
- **Depots/PStates/Topologies**: entity-specific telemetry
- Microbatch pages have Pause/Resume buttons

## Cluster Version Upgrade

### Patch Upgrade (e.g. 0.13.0 -> 0.13.1) -- Zero Downtime

Requires replication factor >= 2 for zero downtime. ~3-4 minutes per node. Skip `disallowLeaders`/`allowLeaders` steps if no replication.

For each Supervisor node:
1. `./rama disallowLeaders <supervisorId>`
2. Disable process supervision, `kill -9` all Supervisor and worker processes on node
3. Unpack new release, keep same `local.dir`
4. Re-enable process supervision, restart Supervisor
5. `./rama allowLeaders <supervisorId>`
6. Wait ~1 minute for leader rebalancing

Then upgrade Conductor: disable supervision, `kill -9` Conductor process, unpack new release (same `local.dir`), re-enable supervision, restart. Update all clients last.

### Atomic Upgrade (major/minor version change) -- Brief Downtime

Can be done in 5-10 minutes with preparation. Check release notes for special procedures.

1. `rama shutdownCluster` (Conductor node only) -- **must wait** for Conductor state `[:cluster-shutdown-complete]` on Cluster UI before proceeding
2. Kill Conductor/Supervisor processes, disable supervision
3. Unpack new release on all nodes, keep same `local.dir`
4. Start Conductor and Supervisors (enter "upgrade mode")
5. For each module: `rama deploy --action upgrade --jar app.jar --module ... --configOverrides overrides.yaml`
6. Monitoring module: `rama deploy --action upgrade --systemModule monitoring`
7. Upgrade all clients

Upgraded modules must be identical in PStates/depots/topologies to the prior version. Config overrides must be re-specified. Cluster UI shows which modules still need upgrading.

## Remote Clients

```java
// External hostnames
RamaClusterManager manager = RamaClusterManager.open();
// With explicit config (takes precedence over rama.yaml on classpath)
Map config = new HashMap();
config.put("conductor.host", "1.2.3.4");
RamaClusterManager manager = RamaClusterManager.open(config);

// Internal hostnames
RamaClusterManager manager = RamaClusterManager.openInternal();
```

Zero-arity versions read from `rama.yaml` on classpath. One-arity versions accept manual config (takes precedence). Configure `custom.serializations` here if using custom serializations.

Retrieve depot, PState, and query topology clients from the manager. Client Rama version must match cluster version (same major and minor).

---

## Module Management Functions

| Function | Purpose |
|---|---|
| `get-module-name` | Retrieve module name string from module var |
| `get-module-status` | Query module deployment status |
| `deployed-module-names` | List all deployed module names |

## Dynamic Options

Set at module launch time, overridable at deploy:

| Function | Scope |
|---|---|
| `set-launch-module-dynamic-option!` | Module-level options |
| `set-launch-depot-dynamic-option!` | Per-depot options |
| `set-launch-pstate-dynamic-option!` | Per-PState options |
| `set-launch-topology-dynamic-option!` | Per-topology options |

Each has a `*` variant (e.g., `set-launch-depot-dynamic-option!*`) for programmatic var specification.
