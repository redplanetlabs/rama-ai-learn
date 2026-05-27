# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

engage nucleus:
[mu tao] | [Δ λ ∞/0 ε/φ Σ/μ c/h] | OODA
Human ∘ AI

λ challenge(name, skill) -> implementation -> transcript_analysis -> skill_update
λ skill(x)   terse | concise | precise | complete

## Project Purpose

This project builds an agentskills.io-compliant SKILL.md for Rama
development through iterative implementation challenges. An agent
implements challenges; we analyze the resulting transcripts to identify
skill gaps and update `skills/rama/SKILL.md` accordingly.

## Values

- Full introspection of tokens, tool use, agent use, etc.
- Full timings



## Development Commands

```bash
# Start REPL with nREPL
clj -M:nrepl

# Start basic REPL
clj
```

### Challenge Tests

Run from the challenge directory:

```bash
cd challenges/simple-streaming

# Run challenge tests (tests agent implementation)
clojure -X:test

# Run harness validation tests (tests the test harness using reference impl)
clojure -X:test-harness
```

### Running Challenges

The `CHALLENGE_KEY` environment variable is **required** for `bb run-challenges`.
It encrypts reference solutions and private tests so the agent cannot read them.

```bash
# Run all challenges
CHALLENGE_KEY=<passphrase> bb run-challenges

# Run a single batch
CHALLENGE_KEY=<passphrase> bb run-challenges --batch 3

# Run with a specific agent/model
CHALLENGE_KEY=<passphrase> bb run-challenges --batch 1 --agent claude --model sonnet
```

### Challenge Encryption

Reference solutions and private test files (`test-resources/`, `test-private/`) are
encrypted during challenge runs so agents cannot read them.

**How it works:**
- `bb run-challenges` automatically encrypts each challenge's private files immediately
  before the agent starts, and decrypts them immediately after the agent finishes (or if
  the run throws an exception). The runner will exit with an error if `CHALLENGE_KEY` is
  not set.
- The agent therefore never has access to plaintext reference solutions or private tests.
- Encryption uses AES-256-CBC. The key is derived from the passphrase via SHA-256.
  Each file is encrypted independently with a fresh random IV; the IV is prepended to the
  ciphertext and the whole thing is Base64-encoded into a `.enc` sidecar file.

**Batch encrypt/decrypt (outside of a run):**

```bash
# Encrypt everything
CHALLENGE_KEY=<passphrase> bb encrypt-challenges

# Encrypt one challenge
CHALLENGE_KEY=<passphrase> bb encrypt-challenges --challenge attack

# Encrypt a whole batch
CHALLENGE_KEY=<passphrase> bb encrypt-challenges --batch 6

# Decrypt (same scope flags apply)
CHALLENGE_KEY=<passphrase> bb decrypt-challenges --batch 6
```

## Dependencies

Rama artifacts require the Red Planet Labs Maven repository configured in `deps.edn`:
- `https://nexus.redplanetlabs.com/repository/maven-public-releases`

## Git and GitHub

The remote `ghe` points to `ghe.internal.redplanetlabs.com`. This GHE instance has API incompatibilities with `gh` CLI commands (e.g., `gh pr create` fails due to unsupported `draft` parameter).

Use `gh api` with `--hostname ghe.internal.redplanetlabs.com` for GHE operations:

```bash
# Create PR
gh api --hostname ghe.internal.redplanetlabs.com repos/rpl/rama-ai-learn/pulls \
  -X POST -f title="..." -f head="branch" -f base="master" -f body="..." --jq '.html_url'

# List PRs
gh api --hostname ghe.internal.redplanetlabs.com repos/rpl/rama-ai-learn/pulls
```

## Codex Integration

Codex skills are in `.codex/skills/`. The `rama` symlink points to `skills/rama/SKILL.md`.
Run challenges with: `bb run-challenges --batch N --agent codex --verbose`

## Skill Configuration

Both `.claude/skills/`, `.codex/skills/`, `.pi/agent/skills/` and
`.psi/agent/skills/` are project-local, not in home directories. Shared
skill content (e.g., `skills/rama/SKILL.md`) is referenced via relative
symlinks.

## Architecture

- `skills/rama/SKILL.md` - agentskills.io skill definition with YAML frontmatter (`name`, `description`) and markdown guidance
- Skill file provides context for AI assistants working with Rama's depots, PStates, and topologies

## Skill Writing Principles

When editing SKILL.md or reference files, follow these principles to make
agents reliably follow instructions (based on agentskills.io spec and
empirical research):

- **Process at the top, reference below.** Agents attend to what comes first.
  SKILL.md should lead with workflow steps, not reference material.
- **Every phase must produce a visible artifact.** Steps that produce no
  output get skipped. If a phase has no required artifact, the agent
  will bypass it.
- **Negative constraints block bypass paths.** "Do NOT write code until
  the plan exists" is stronger than "write a plan first." Include WHY
  the shortcut is dangerous.
- **Defaults must be explicit and require justification to deviate.**
  "Default: microbatch. If stream instead, state what application API
  requires it" forces the agent to justify deviation. "Choose stream or
  microbatch" lets the agent pick whichever it already decided.
- **Advisory language gets ignored.** "Consider", "prefer", "you might
  want to" are all weak. Use imperative: "Read X", "Write Y", "Do NOT Z".
- **Examples teach more than rules.** If the skeleton code shows
  `{String Object}`, agents will copy it. Every example must embody
  the rules.
- **Cite references imperatively.** "Read references/app-design.md"
  is an instruction. "References: app-design.md" is a bibliography
  that gets ignored.

### Generic-only rule for skill content

Files under `plugins/rama-skill/` (SKILL.md and any reference under
`references/`) must be generic Rama documentation. They will ship to
unrelated projects via `bb publish-skill`, so a developer who has never
seen this repo's challenges must understand them.

When editing any file under `plugins/rama-skill/`:

- Do NOT reference any challenge name (e.g. `fanout`, `auction-module`,
  `time-series-module-hard`, `social-graph`, `bank-transfer-module`,
  `who-to-follow-hard`, `content-moderation`, etc.).
- Do NOT reference any specific protocol method (e.g. `get-timeline`,
  `bid!`, `transfer!`).
- Do NOT reference any specific PState, depot, or topology name from
  a challenge (e.g. `$$partitioned-followers`, `*follow-depot`).
- Examples must use generic placeholder names (e.g. `$$accounts`,
  `*events`, `MyModule`).

If a sentence wouldn't make sense to someone who has never seen this
repo's challenges, delete or rephrase it. After making any edit under
`plugins/rama-skill/`, re-read the diff and apply this test before
moving on.

## Protocol-Based Challenge Pattern

Challenges use a protocol-based interface. Each challenge has:
- `src/<name>/protocol.clj` — protocol definition (the contract)
- `src/<name>/test_support.clj` — tests using protocol + `with-module` from `lib/harness/`
- `test-resources/<name>/module.clj` — reference impl with `create-module` returning `{:module :wrap-client}`
- `implementations/<name>/src/<name>/module.clj` — agent impl (same `create-module` contract)

## Style

Prefer to use the following to require rama:

``` clojure
(ns my.ns
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.path :refer :all]))
```

## FILES

what does the ai agent need to be maximally effective?

CLAUDE.md - this filex
README.md - User documentation
STATE.md - now (what is true)
PLAN.md - next (what should happen)
LEARNING.md - past (what was discovered)

## GOAL

Complete all tasks PLAN.md to get to COMPLETE.

## Tasks

### Running challenges

| Task | Description |
|---|---|
| `bb run-challenges` | Run coding challenges against an AI agent (`-a claude/codex`, `-m model`, `-r reasoning`, `-b batch`, `-f glob`) |
| `bb run-qa` | Run Q&A challenges against an AI agent (`-a claude/codex`, `-m model`, `-r reasoning`, `-f glob`) |

### Q&A challenge pipeline

| Task | Description |
|---|---|
| `bb qa-extract-corpus <name>` | Extract claims from corpus for a question (run once, verify, commit) |
| `bb qa-extract <name>` | Extract claims from an agent's answer (per-run) |
| `bb qa-score <name>` | Score answer claims against corpus claims (precision/recall/F1) |

### Challenge development

| Task | Description |
|---|---|
| `bb encrypt-challenges` | Encrypt test-resources and test-private files (`CHALLENGE_KEY` required) |
| `bb decrypt-challenges` | Decrypt test-resources and test-private files (`CHALLENGE_KEY` required) |
| `bb clean-implementations` | Remove all agent implementations under `implementations/` |

### LiveCodeBench

| Task | Description |
|---|---|
| `bb lcb-fetch` | Fetch and cache LiveCodeBench problems from HuggingFace |
| `bb lcb-list` | List cached problems with optional filters |
| `bb lcb-port` | Scaffold a Rama challenge from an LCB problem |

### Analysis

| Task | Description |
|---|---|
| `bb analyze-transcript` | Analyze a challenge transcript file (auto-detects Claude vs Codex) |
| `bb format-transcript` | Format a Claude Code JSONL transcript for readable display |

#### Script invocation rule

Settings.json's allowlist matches **relative paths from the repo root**
with the explicit interpreter prefix. Never use absolute paths or omit
the prefix — the rule won't match and a permission prompt will fire.

Canonical forms:

```bash
# Always run from repo root. Add `cd /Users/nathanmarz/code/rama-ai-learn &&`
# if your working directory is elsewhere.
python3 scripts/analyze-latest-transcript.py <command> [args...]
bash scripts/docker-copy-transcript.sh
```

If the analyze script doesn't have a command for what you need, **add
one to the script** rather than working around it with `grep` /
`jq` / inline `python3 -c`. The script is the supported interface
for transcript analysis.

### Verification

| Task | Description |
|---|---|
| `bb verify-wait-for-processing` | Verify tests use explicit wait-for-processing after writes |
| `bb error-catalog` | Run error-catalog tests to verify Rama troubleshooting signals |
| `bb error-diagnostics` | Report line-info quality for each cataloged Rama error |

### Publishing

| Task | Description |
|---|---|
| `bb publish-skill` | Copy skill to rama-skills repo and create a PR |

## JDK warnings

To remove:

``` c
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::loadLibrary has been called by org.rocksdb.RocksDB in an unnamed
```

add the following java options:
```
--add-opens java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```
