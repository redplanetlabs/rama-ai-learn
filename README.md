# rama-ai-learn

A harness for developing skill files for [Rama](https://redplanetlabs.com/), with the goal of one-shotting complex, scalable, and fault-tolerant backends. The harness runs an LLM through a series of implementation challenges, captures every transcript in full, and provides tooling for analyzing transcripts to inform edits to skill files. A challenge succeeds when the agent's implementation passes private tests covering correctness, performance, and fault-tolerance.

We're currently targeting the Clojure API, but a Java equivalent is planned.

For the project's motivation, design, and progress updates, see [the blog post series](https://blog.redplanetlabs.com/2026/05/28/teaching-llms-to-one-shot-complex-backends-at-scale-report-1/).

## What's in the repo

- `challenges/` — implementation challenges, each one a Rama module the agent has to build from a protocol contract.
- `plugins/rama-skill/skills/rama/` — the Rama skill content (an [agentskills.io](http://agentskills.io/)-format skill).
- `scripts/run_challenges.bb` — the orchestration runner that drives an agent through a challenge.
- `scripts/docker-*.sh` — Docker harness for running challenges in an isolated container.
- `scripts/analyze-latest-transcript.py` — tooling for inspecting transcripts after a run.

## Challenge structure

Each challenge is self-contained under `challenges/<name>/`. The agent gets a README and a protocol contract, while everything else (private tests, reference implementation) is encrypted while the run is in progress so the agent cannot read it.

A typical challenge looks like:

```
challenges/auction-module/
├── README.md                                # problem statement + constraints
├── src/
│   └── auction_module/
│       └── protocol.clj                     # the contract the agent must satisfy
├── test-resources/auction_module/module.clj # reference implementation (encrypted at run time)
├── test-private/                            # private functional + performance tests (encrypted)
└── deps.edn
```


## Running a challenge

The `CHALLENGE_KEY` environment variable encrypts reference solutions and private tests during a run so the agent can't read them.

```bash
CHALLENGE_KEY=<passphrase> bb run-challenges -f auction-module -m claude-opus-4-6 -r high -p
```

Each run produces one transcript per phase under `latest-transcripts/`.


## Docker workflow

Runs are usually executed inside a Docker container so the LLM has a clean, isolated environment with the tooling it needs (Clojure CLI, clj-kondo, Babashka, nREPL helpers, Claude Code CLI).

```bash
# Build the image once
bash scripts/docker-build.sh

# Start a long-running container (requires CLAUDE_CODE_OAUTH_TOKEN in the env)
bash scripts/docker-start.sh

# Copy the current repo into the running container
bash scripts/docker-copy-in.sh

# (From inside the container) run challenges
docker exec -it rama bash
CHALLENGE_KEY=<passphrase> bb run-challenges -f <challenge name> -m claude-opus-4-6 -r high -p

# After the run, copy transcripts back to the host
bash scripts/docker-copy-transcript.sh
```

The container mounts `~/.m2` and a named gitlibs volume so dependency caches persist across runs.

## Inspecting transcripts

After a run, transcripts can be analyzed via:

```bash
python3 scripts/analyze-latest-transcript.py run-overview
python3 scripts/analyze-latest-transcript.py --phase 3 module
python3 scripts/analyze-latest-transcript.py --phase 4 impl-validation
python3 scripts/analyze-latest-transcript.py thinking <keyword>
```

Run `python3 scripts/analyze-latest-transcript.py` with no arguments for the full command list.
