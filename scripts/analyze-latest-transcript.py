#!/usr/bin/env python3
"""Analyze transcripts from the latest challenge run.

Usage:
  python3 scripts/analyze-latest-transcript.py [--phase P [--attempt K]] [command] [args...]

Transcript layout (auto-detected):
  latest-transcripts/ is populated by `bash scripts/docker-copy-transcript.sh`.
  Override the directory with the TRANSCRIPTS_DIR environment variable.

  Workflow mode:  latest-transcripts/ contains a wf_* directory holding
                  per-phase subagent transcripts (agent-*.jsonl) plus
                  journal.jsonl (started/result events per agentId, in
                  execution order). The loose *.jsonl next to it is the
                  top-level session transcript.
  Legacy mode:    latest-transcripts/ contains flat *-phaseN[-attemptK].jsonl
                  files. Without --phase, reads latest-transcript.jsonl in
                  the repo root.

Phase selection:
  --phase P                Select one phase.
                           Workflow mode: P is the phase number parsed from the
                             "THIS PHASE:" line of each agent's prompt. Retries
                             of a phase produce multiple agents; default is the
                             LAST attempt in journal order. Older workflow runs
                             may also match `P.lens` (reviewer) or `scribe-P`.
                           Legacy mode: reads *-phaseP*.jsonl (highest attempt).
  --attempt K              Pick attempt K of the phase (1-based).
                           Workflow mode: Kth agent with that phase number in
                             journal order.
                           Legacy mode: attempt 1 is the file without
                             `-attempt` in the name.

  Without --phase, workflow-mode commands operate on ALL phase agents
  concatenated in journal order (so module/final-write replay Write + Edits
  across every phase — any phase can edit module.clj).

Commands:
  summary              - Workflow mode: per-agent one-liner table (phase,
                         attempt, duration, size, verdict) + final result;
                         with --phase, duration/cost/turns/stop/result for
                         that agent. Legacy mode: run result, cost, duration,
                         turn count.
  plan                 - Show PLAN.md content
  validation           - Show PLAN_VALIDATION.md content
  implicit-spec        - Show IMPLICIT_SPEC.md content
  impl-validation      - Show IMPLEMENTATION_VALIDATION.md content
  test-validation      - Show TEST_VALIDATION.md content
  errors               - Show all compilation/test errors
  thinking <query>     - Search thinking blocks for a keyword/phrase
  thinking-blocks      - Show raw structure of every thinking block
  reasoning            - Show reasoning-log appends (REASONING.md or reasoning/phaseN-attemptK.md)
  confusions           - Show only CONFUSION entries from reasoning-log appends
  writes <query>       - Search Write tool calls for a keyword
  edits <query>        - Search Edit tool calls for a keyword
  module               - Show the final module.clj content (replays Write + Edits)
  module all           - Show every Write of module.clj (history, no Edit replay)
  final-write <name>   - Reconstruct any file's final state by replaying Write + Edits
  test-runs            - List all test runs and their results
  reads                - List all Read tool calls
  todos                - Show all TodoWrite calls with todo state
  compaction           - Show compaction points and what happened before/after
  timeline             - Show high-level timeline of actions
  tool-results <query> - Search tool result content for a keyword
  search <query>       - Search ALL content (text, thinking, tool use, tool results) for a keyword
  run-overview         - Workflow mode: table of all phase agents in journal
                         order with start/end/duration and gap-before.
                         Legacy mode: table of all transcripts in
                         latest-transcripts/ sorted by start time.
"""

import json
import sys
import re
import os
import glob
from datetime import datetime

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TRANSCRIPT = os.path.join(REPO_ROOT, 'latest-transcript.jsonl')
LATEST_TRANSCRIPTS_DIR = os.environ.get(
    'TRANSCRIPTS_DIR', os.path.join(REPO_ROOT, 'latest-transcripts'))

def resolve_transcript_path(phase=None, attempt=None):
    """Resolve which transcript file to read (legacy flat layout).

    - phase=None: returns latest-transcript.jsonl in the repo root.
    - phase=N: finds *-phase{N}*.jsonl in latest-transcripts/.
      With attempt=None, picks the highest-numbered attempt.
      With attempt=K, picks exactly that attempt (attempt 1 is the file
      without `-attempt` in the name)."""
    if phase is None:
        return DEFAULT_TRANSCRIPT

    if not os.path.isdir(LATEST_TRANSCRIPTS_DIR):
        sys.stderr.write(
            f"ERROR: --phase requires {LATEST_TRANSCRIPTS_DIR}/ to exist. "
            f"Populate it with `bash scripts/docker-copy-transcript.sh`.\n")
        sys.exit(1)

    if attempt is not None and attempt < 1:
        sys.stderr.write(f"ERROR: --attempt must be >= 1 (got {attempt}).\n")
        sys.exit(2)

    if attempt is None:
        # Pick highest-numbered attempt.
        pattern = os.path.join(LATEST_TRANSCRIPTS_DIR, f'*-phase{phase}*.jsonl')
        candidates = glob.glob(pattern)
        if not candidates:
            sys.stderr.write(
                f"ERROR: no transcript matching --phase {phase} in {LATEST_TRANSCRIPTS_DIR}/.\n")
            sys.exit(1)
        def attempt_of(path):
            m = re.search(r'-attempt(\d+)\.jsonl$', path)
            return int(m.group(1)) if m else 1
        candidates.sort(key=attempt_of, reverse=True)
        return candidates[0]

    # Specific attempt requested.
    if attempt == 1:
        # attempt 1 has no `-attempt` suffix. Match `*-phaseN.jsonl` exactly,
        # not `*-phaseN-attempt2.jsonl`.
        pattern = os.path.join(LATEST_TRANSCRIPTS_DIR, f'*-phase{phase}.jsonl')
    else:
        pattern = os.path.join(LATEST_TRANSCRIPTS_DIR, f'*-phase{phase}-attempt{attempt}.jsonl')
    candidates = glob.glob(pattern)
    if not candidates:
        sys.stderr.write(
            f"ERROR: no transcript matching --phase {phase} --attempt {attempt} "
            f"in {LATEST_TRANSCRIPTS_DIR}/.\n")
        sys.exit(1)
    return candidates[0]

def load(path=None):
    if path is None:
        path = DEFAULT_TRANSCRIPT
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]

def cmd_summary(lines, args):
    for line in lines:
        if line.get('type') == 'result':
            print(f"Duration: {line.get('duration_ms', 0)/1000:.0f}s ({line.get('duration_ms', 0)/60000:.1f}m)")
            print(f"Cost: ${line.get('total_cost_usd', 0):.2f}")
            print(f"Turns: {line.get('num_turns')}")
            print(f"Stop: {line.get('stop_reason')}")
            result = line.get('result', '')
            print(f"Result: {result[:300]}")
            break

def cmd_plan(lines, args):
    _show_write(lines, 'PLAN.md')

def cmd_validation(lines, args):
    _show_write(lines, 'PLAN_VALIDATION')

def cmd_implicit_spec(lines, args):
    _show_write(lines, 'IMPLICIT_SPEC')

def cmd_impl_validation(lines, args):
    _show_write(lines, 'IMPLEMENTATION_VALIDATION')

def cmd_test_validation(lines, args):
    _show_write(lines, 'TEST_VALIDATION')

def cmd_module(lines, args):
    """Show final module.clj. Use 'module all' to see every write (history)."""
    if args and args[0] == 'all':
        _show_write(lines, 'module.clj', require='implementation', last_only=False)
        return
    final = _final_file_content(lines, 'module.clj', require='implementation')
    if final is None:
        print("(no module.clj writes found)")
        return
    i, fp, content = final
    print(f"=== FINAL {fp} ({len(content)} chars, last touched at line {i}) ===")
    print(content)

def cmd_final_write(lines, args):
    """Reconstruct final state of any file by replaying Write + Edits."""
    if not args:
        print("Usage: final-write <name-substring>")
        return
    name = args[0]
    final = _final_file_content(lines, name)
    if final is None:
        print(f"(no Write of {name} found)")
        return
    i, fp, content = final
    print(f"=== FINAL {fp} ({len(content)} chars, last touched at line {i}) ===")
    print(content)

def _final_file_content(lines, name, require=None):
    """Replay Write + Edits for files matching name to get final content."""
    files = {}  # fp -> (last-line-index, content)
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') != 'tool_use':
                continue
            t = block.get('name')
            if t not in ('Write', 'Edit'):
                continue
            inp = block.get('input', {})
            fp = inp.get('file_path', '')
            if name not in fp or (require is not None and require not in fp):
                continue
            if t == 'Write':
                files[fp] = (i, inp.get('content', ''))
            elif t == 'Edit' and fp in files:
                _, content = files[fp]
                old_s = inp.get('old_string', '')
                new_s = inp.get('new_string', '')
                if not old_s or old_s not in content:
                    continue
                if inp.get('replace_all'):
                    content = content.replace(old_s, new_s)
                else:
                    content = content.replace(old_s, new_s, 1)
                files[fp] = (i, content)
    if not files:
        return None
    # Return the file with the latest activity
    latest_fp = max(files, key=lambda fp: files[fp][0])
    last_i, content = files[latest_fp]
    return (last_i, latest_fp, content)

def _final_module_content(lines):
    """Return (line_index, file_path, content) for final module.clj write, or None."""
    last = None
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') in ('Write', 'Edit'):
                fp = block.get('input', {}).get('file_path', '')
                if 'module.clj' in fp and 'implementation' in fp:
                    if block.get('name') == 'Write':
                        last = (i, fp, block['input'].get('content', ''))
                    elif last is not None:
                        # apply edit on top of last content
                        old_s = block['input'].get('old_string', '')
                        new_s = block['input'].get('new_string', '')
                        replace_all = block['input'].get('replace_all', False)
                        if old_s in last[2]:
                            new_content = last[2].replace(old_s, new_s) if replace_all else last[2].replace(old_s, new_s, 1)
                            last = (i, last[1], new_content)
    return last

def _show_write(lines, name, require=None, last_only=False):
    matches = []
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'Write':
                fp = block.get('input', {}).get('file_path', '')
                if name in fp and (require is None or require in fp):
                    content = block['input'].get('content', '')
                    matches.append((i, fp, content))
    if last_only and matches:
        matches = [matches[-1]]
    for i, fp, content in matches:
        print(f"=== LINE {i} {fp} ({len(content)} chars) ===")
        print(content)
        print()

def cmd_errors(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_result':
                c = block.get('content', '')
                if isinstance(c, str) and any(kw in c for kw in ['FAIL in', 'ERROR in', 'Syntax error', 'Unable to resolve', 'CompilerException', 'ClassCastException', 'NullPointerException', 'IllegalArgumentException']):
                    # Skip reference file reads that happen to contain these strings
                    if c.startswith('1\t#'):
                        continue
                    print(f"=== LINE {i} ===")
                    print(c[:500])
                    print()

def cmd_thinking(lines, args):
    if not args:
        print("Usage: thinking <keyword>")
        return
    query = ' '.join(args)
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'thinking':
                text = block.get('thinking', '')
                if not text:
                    continue
                match = re.search(query, text, re.IGNORECASE)
                if match:
                    idx = match.start()
                    start = max(0, idx - 400)
                    end = min(len(text), idx + 600)
                    print(f"=== LINE {i} at {idx}/{len(text)} ===")
                    print(text[start:end])
                    print()

# Matches both reasoning-log layouts: the legacy shared REASONING.md and the
# workflow-mode per-agent files (implementations/<ch>/reasoning/phaseN-attemptK.md).
_REASONING_PATH_RE = re.compile(r'REASONING\.md|reasoning/phase\d+-attempt\d+\.md')

def _reasoning_appends(lines):
    """Yield (line_index, label, text) for every reasoning-log append in the
    transcript (legacy REASONING.md or per-agent reasoning/ files): Bash
    heredoc/echo appends plus Write/Edit tool calls."""
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') != 'tool_use':
                continue
            name = block.get('name')
            inp = block.get('input', {})
            if name == 'Bash':
                cmd = inp.get('command', '')
                if not _REASONING_PATH_RE.search(cmd):
                    continue
                m = re.search(r"<<\s*'?(\w+)'?\n(.*?)\n\1", cmd, re.DOTALL)
                yield (i, 'Bash', m.group(2) if m else cmd)
            elif name in ('Write', 'Edit'):
                fp = inp.get('file_path', '')
                if not _REASONING_PATH_RE.search(fp):
                    continue
                content = inp.get('content') or inp.get('new_string', '')
                yield (i, f"{name} {fp}", content)

def cmd_reasoning(lines, args):
    """Show reasoning-log appends made during this transcript. Legacy runs use
    a shared REASONING.md (attribute entries to phases by running per-phase via
    --phase N); workflow runs use per-agent reasoning/phaseN-attemptK.md files,
    which are self-attributing."""
    found = False
    for i, label, text in _reasoning_appends(lines):
        found = True
        print(f"=== LINE {i} ({label}) ===")
        print(text)
        print()
    if not found:
        print("(no reasoning-log activity in this transcript)")

def cmd_confusions(lines, args):
    """Show only reasoning-log appends containing CONFUSION entries — the
    places where the agent reported being confused or uncertain."""
    found = False
    for i, label, text in _reasoning_appends(lines):
        if 'CONFUSION' not in text:
            continue
        found = True
        print(f"=== LINE {i} ({label}) ===")
        print(text)
        print()
    if not found:
        print("(no CONFUSION entries in this transcript)")

def cmd_thinking_blocks(lines, args):
    """Show raw structure of every thinking block: keys and a content preview
    for each field, so empty/summarized thinking can be diagnosed."""
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') != 'thinking':
                continue
            print(f"=== LINE {i} keys={sorted(block.keys())} ===")
            for k, v in block.items():
                if k == 'type':
                    continue
                s = v if isinstance(v, str) else json.dumps(v)
                print(f"  {k}: ({len(s)} chars) {s[:300]}")
            print()

def cmd_writes(lines, args):
    query = ' '.join(args) if args else ''
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'Write':
                fp = block.get('input', {}).get('file_path', '')
                content = block['input'].get('content', '')
                if not query or query.lower() in fp.lower() or query.lower() in content.lower():
                    print(f"LINE {i}: Write {fp} ({len(content)} chars)")
                    if query and query.lower() in content.lower():
                        idx = content.lower().find(query.lower())
                        start = max(0, idx - 100)
                        end = min(len(content), idx + 300)
                        print(f"  ...{content[start:end]}...")
                    print()

def cmd_edits(lines, args):
    query = ' '.join(args) if args else ''
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'Edit':
                fp = block.get('input', {}).get('file_path', '')
                old = block['input'].get('old_string', '')
                new = block['input'].get('new_string', '')
                if not query or query.lower() in fp.lower() or query.lower() in old.lower() or query.lower() in new.lower():
                    print(f"LINE {i}: Edit {fp}")
                    print(f"  old: {old[:200]}")
                    print(f"  new: {new[:200]}")
                    print()

def cmd_test_runs(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'Bash':
                cmd = block.get('input', {}).get('command', '')
                if 'clojure -X:test' in cmd:
                    print(f"LINE {i}: {cmd[:150]}")
                    # Find result
                    for j in range(i+1, min(i+5, len(lines))):
                        msg2 = lines[j].get('message', {})
                        for b2 in msg2.get('content', []):
                            if not isinstance(b2, dict):
                                continue
                            c = b2.get('content', '')
                            if isinstance(c, str) and ('assertions' in c or 'FAIL' in c or 'ERROR' in c or 'Syntax error' in c):
                                result_lines = c.strip().split('\n')
                                for rl in result_lines[-5:]:
                                    print(f"  {rl}")
                                break
                    print()

def cmd_reads(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'Read':
                fp = block.get('input', {}).get('file_path', '')
                print(f"LINE {i}: {fp}")

def cmd_todos(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'TodoWrite':
                todos = block.get('input', {}).get('todos', [])
                print(f"=== LINE {i} ===")
                for todo in todos:
                    status = todo.get('status', 'pending')
                    icon = {'completed': '✓', 'in_progress': '→', 'pending': ' '}.get(status, '?')
                    print(f"  {icon} {todo.get('content', '')}")
                print()

def cmd_compaction(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'text':
                text = block.get('text', '')
                if 'continued from a previous conversation' in text:
                    print(f"=== COMPACTION at LINE {i} ===")
                    # Show 5 timeline entries before
                    print("\nBefore:")
                    before = []
                    for j in range(max(0, i-10), i):
                        entry = _timeline_entry(j, lines[j])
                        if entry:
                            before.append(entry)
                    for e in before[-5:]:
                        print(f"  {e}")
                    # Show 5 timeline entries after
                    print("\nAfter:")
                    after_count = 0
                    for j in range(i+1, min(i+15, len(lines))):
                        entry = _timeline_entry(j, lines[j])
                        if entry:
                            print(f"  {entry}")
                            after_count += 1
                            if after_count >= 5:
                                break
                    print()

def _timeline_entry(i, line):
    msg = line.get('message', {})
    entries = []
    for block in msg.get('content', []):
        if not isinstance(block, dict):
            continue
        t = block.get('type', '')
        if t == 'text':
            text = block.get('text', '')
            if len(text) > 30:
                entries.append(f"LINE {i} TEXT: {text[:150]}")
        elif t == 'tool_use':
            name = block.get('name', '')
            inp = block.get('input', {})
            if name == 'Write':
                entries.append(f"LINE {i} WRITE: {inp.get('file_path', '')}")
            elif name == 'Read':
                entries.append(f"LINE {i} READ: {inp.get('file_path', '')}")
            elif name == 'Bash':
                entries.append(f"LINE {i} BASH: {inp.get('command', '')[:100]}")
            elif name == 'Edit':
                entries.append(f"LINE {i} EDIT: {inp.get('file_path', '')}")
            elif name == 'Skill':
                entries.append(f"LINE {i} SKILL: {inp.get('skill', '')}")
            elif name == 'TodoWrite':
                entries.append(f"LINE {i} TODO: ({len(inp.get('todos', []))} items)")
            else:
                entries.append(f"LINE {i} {name}")
        elif t == 'thinking':
            text = block.get('thinking', '')
            entries.append(f"LINE {i} THINKING: ({len(text)} chars)")
    return entries[0] if entries else None

def cmd_tool_results(lines, args):
    if not args:
        print("Usage: tool-results <keyword>")
        return
    query = ' '.join(args)
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_result':
                c = block.get('content', '')
                if isinstance(c, str) and re.search(query, c, re.IGNORECASE):
                    print(f"=== LINE {i} ===")
                    idx = re.search(query, c, re.IGNORECASE).start()
                    start = max(0, idx - 200)
                    end = min(len(c), idx + 400)
                    print(c[start:end])
                    print()

def cmd_search(lines, args):
    if not args:
        print("Usage: search <keyword>")
        return
    query = ' '.join(args)
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            t = block.get('type', '')
            text = None
            label = None
            if t == 'text':
                text = block.get('text', '')
                label = 'TEXT'
            elif t == 'thinking':
                text = block.get('thinking', '')
                label = 'THINKING'
            elif t == 'tool_use':
                text = json.dumps(block.get('input', {}))
                label = f"TOOL:{block.get('name', '')}"
            elif t == 'tool_result':
                text = block.get('content', '')
                if not isinstance(text, str):
                    text = json.dumps(text) if text else ''
                label = 'RESULT'
            if text and re.search(query, text, re.IGNORECASE):
                match = re.search(query, text, re.IGNORECASE)
                idx = match.start()
                start = max(0, idx - 200)
                end = min(len(text), idx + 400)
                print(f"=== LINE {i} {label} ===")
                print(text[start:end])
                print()

def cmd_timeline(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            t = block.get('type', '')
            if t == 'text':
                text = block.get('text', '')
                if len(text) > 30:
                    print(f"LINE {i} TEXT: {text[:150]}")
            elif t == 'tool_use':
                name = block.get('name', '')
                inp = block.get('input', {})
                if name == 'Write':
                    print(f"LINE {i} WRITE: {inp.get('file_path', '')}")
                elif name == 'Read':
                    print(f"LINE {i} READ: {inp.get('file_path', '')}")
                elif name == 'Bash':
                    cmd = inp.get('command', '')[:100]
                    print(f"LINE {i} BASH: {cmd}")
                elif name == 'Edit':
                    print(f"LINE {i} EDIT: {inp.get('file_path', '')}")
                elif name == 'Skill':
                    print(f"LINE {i} SKILL: {inp.get('skill', '')}")
                elif name == 'TodoWrite':
                    todos = inp.get('todos', [])
                    done = sum(1 for t in todos if t.get('status') == 'completed')
                    prog = sum(1 for t in todos if t.get('status') == 'in_progress')
                    pend = sum(1 for t in todos if t.get('status') == 'pending')
                    print(f"LINE {i} TODO: {done}✓ {prog}→ {pend}○")
                else:
                    print(f"LINE {i} {name}")
            elif t == 'thinking':
                text = block.get('thinking', '')
                print(f"LINE {i} THINKING: ({len(text)} chars)")

def _parse_ts(ts):
    return datetime.fromisoformat(ts.replace('Z', '+00:00'))

def _first_last_timestamps(path):
    """Scan a transcript file for its first and last record timestamps."""
    first = None
    last = None
    with open(path) as f:
        for line in f:
            try:
                o = json.loads(line)
            except Exception:
                continue
            ts = o.get('timestamp')
            if ts:
                if first is None:
                    first = ts
                last = ts
    return first, last

def _print_duration_footer(rows):
    """rows: list of (first_ts, last_ts). Print the sum/wall/gap footer."""
    total_dur = sum(int((_parse_ts(l) - _parse_ts(f)).total_seconds()) for f, l in rows)
    run_start = _parse_ts(min(f for f, _ in rows))
    run_end = _parse_ts(max(l for _, l in rows))
    wall = int((run_end - run_start).total_seconds())
    print()
    print(f'sum of phase durations: {total_dur}s ({total_dur/60:.1f}m)')
    print(f'wall clock first->last: {wall}s ({wall/60:.1f}m)')
    print(f'gap total (non-phase):  {wall - total_dur}s ({(wall - total_dur)/60:.1f}m)')

def cmd_run_overview(lines, args):
    """Legacy mode: scan latest-transcripts/, print one row per flat file
    sorted by first timestamp."""
    if not os.path.isdir(LATEST_TRANSCRIPTS_DIR):
        sys.stderr.write(
            f"ERROR: {LATEST_TRANSCRIPTS_DIR}/ does not exist. "
            f"Populate it with `bash scripts/docker-copy-transcript.sh`.\n")
        sys.exit(1)
    files = sorted(glob.glob(os.path.join(LATEST_TRANSCRIPTS_DIR, '*.jsonl')))
    if not files:
        sys.stderr.write(f"ERROR: no transcripts in {LATEST_TRANSCRIPTS_DIR}/.\n")
        sys.exit(1)
    rows = []
    for path in files:
        first, last = _first_last_timestamps(path)
        if first is None:
            continue
        name = os.path.basename(path)
        m = re.search(r'-(phase\d+(?:-attempt\d+)?)\.jsonl$', name)
        label = m.group(1) if m else name
        rows.append((first, last, label))
    rows.sort()
    print(f'{"phase":24s} {"start":>9s} {"end":>9s} {"dur":>7s} {"gap-before":>11s}')
    prev_end = None
    for first, last, label in rows:
        t1 = _parse_ts(first)
        t2 = _parse_ts(last)
        dur = int((t2 - t1).total_seconds())
        gap = int((t1 - _parse_ts(prev_end)).total_seconds()) if prev_end else 0
        prev_end = last
        print(f'{label:24s} {first[11:19]} {last[11:19]} {dur:6d}s {gap:10d}s')
    _print_duration_footer([(f, l) for f, l, _ in rows])


# ---- Workflow mode (wf_* directory layout) ----
#
# A workflow run stores one transcript per phase agent:
#   latest-transcripts/wf_<id>/agent-<id>.jsonl   per-phase transcript
#   latest-transcripts/wf_<id>/journal.jsonl      started/result events
# Retries of a phase produce multiple agents with the same phase number;
# journal order determines attempt numbering.

def detect_wf_dir():
    """Return the newest wf_* dir under latest-transcripts/, or None if the
    layout is legacy (no wf_* dir)."""
    if not os.path.isdir(LATEST_TRANSCRIPTS_DIR):
        return None
    wf_dirs = [d for d in glob.glob(os.path.join(LATEST_TRANSCRIPTS_DIR, 'wf_*'))
               if os.path.isdir(d)]
    if not wf_dirs:
        return None
    def latest_mtime(d):
        files = glob.glob(os.path.join(d, '*.jsonl'))
        return max((os.path.getmtime(f) for f in files), default=0)
    return max(wf_dirs, key=latest_mtime)

def classify_agent(path):
    """Read the first user message from an agent transcript and classify it.
    Returns a dict with:
      phase_num: int or None
      phase_label: str  (e.g. "Phase 0: Implicit Spec")
      role: 'work' | 'reviewer' | 'scribe'
      lens: str or None  (reviewers in old workflow runs)"""
    info = {'phase_num': None, 'phase_label': '?', 'role': 'work', 'lens': None}
    try:
        with open(path) as f:
            for line in f:
                try:
                    rec = json.loads(line)
                except Exception:
                    continue
                if rec.get('type') != 'user':
                    continue
                content = rec.get('message', {}).get('content', '')
                if not isinstance(content, str):
                    continue

                # Scribe agents (old workflow runs)
                if content.startswith('You are writing a single validation artifact'):
                    info['role'] = 'scribe'
                    for num, key, label in ((2, 'plan-validation', 'Plan Validation'),
                                            (4, 'impl-validation', 'Impl Validation'),
                                            (6, 'test-validation', 'Test Validation')):
                        if key in content:
                            info['phase_num'] = num
                            info['phase_label'] = f'Phase {num}: {label} (scribe)'
                            break
                    return info

                for l in content.split('\n'):
                    if 'THIS PHASE:' in l:
                        phase_text = l.strip().replace('THIS PHASE:', '').strip()
                        info['phase_label'] = phase_text[:80]
                        m = re.search(r'Phase\s+(\d+)', phase_text)
                        if m:
                            info['phase_num'] = int(m.group(1))
                    if 'YOUR LENS:' in l:
                        info['lens'] = l.strip().replace('YOUR LENS:', '').strip()[:60]
                        info['role'] = 'reviewer'
                return info
    except Exception:
        pass
    return info

def _journal_order(wf_dir):
    """Read journal.jsonl and return agent IDs in started order."""
    jpath = os.path.join(wf_dir, 'journal.jsonl')
    if not os.path.exists(jpath):
        return []
    order = []
    with open(jpath) as f:
        for line in f:
            try:
                rec = json.loads(line)
                if rec.get('type') == 'started':
                    order.append(rec['agentId'])
            except Exception:
                continue
    return order

def _journal_results(wf_dir):
    """Read journal.jsonl and return {agentId: result_text}."""
    jpath = os.path.join(wf_dir, 'journal.jsonl')
    if not os.path.exists(jpath):
        return {}
    results = {}
    with open(jpath) as f:
        for line in f:
            try:
                rec = json.loads(line)
                if rec.get('type') == 'result':
                    r = rec.get('result', '')
                    if not isinstance(r, str):
                        r = json.dumps(r)
                    results[rec['agentId']] = r
            except Exception:
                continue
    return results

def build_agent_index(wf_dir):
    """Ordered list of agent info dicts for the workflow run.
    Each entry: {path, agent_id, phase_num, phase_label, role, lens, size,
    mtime, attempt}. Ordered by journal started order (mtime fallback);
    attempt numbers count retries of the same phase in that order."""
    agents = []
    for fname in sorted(os.listdir(wf_dir)):
        if not fname.endswith('.jsonl') or fname == 'journal.jsonl':
            continue
        path = os.path.join(wf_dir, fname)
        agent_id = fname.replace('agent-', '').replace('.jsonl', '')
        info = classify_agent(path)
        info['path'] = path
        info['agent_id'] = agent_id
        info['size'] = os.path.getsize(path)
        info['mtime'] = os.path.getmtime(path)
        agents.append(info)
    journal_order = _journal_order(wf_dir)
    if journal_order:
        order_map = {aid: i for i, aid in enumerate(journal_order)}
        agents.sort(key=lambda a: (order_map.get(a['agent_id'], len(order_map)), a['mtime']))
    else:
        agents.sort(key=lambda a: a['mtime'])
    counts = {}
    for a in agents:
        key = (a['phase_num'], a['role'], a.get('lens'))
        counts[key] = counts.get(key, 0) + 1
        a['attempt'] = counts[key]
    return agents

def _match_phase(agents, phase_spec):
    """All agents matching a --phase spec, in journal order.
    Specs: "3" (phase number; primary), "4.dataflow" (reviewer lens, old
    runs), "scribe-2" (scribe, old runs)."""
    m = re.match(r'scribe[- ]?(\d+)$', phase_spec)
    if m:
        num = int(m.group(1))
        return [a for a in agents if a['phase_num'] == num and a['role'] == 'scribe']
    if '.' in phase_spec:
        head, lens_q = phase_spec.split('.', 1)
        try:
            num = int(head)
        except ValueError:
            return []
        lens_q = lens_q.lower()
        return [a for a in agents
                if a['phase_num'] == num
                and a.get('lens') and lens_q in a['lens'].lower()]
    try:
        num = int(phase_spec)
    except ValueError:
        return []
    matched = [a for a in agents if a['phase_num'] == num and a['role'] == 'work']
    if not matched:
        matched = [a for a in agents if a['phase_num'] == num]
    return matched

def select_agents(agents, phase_spec, attempt):
    """Resolve --phase/--attempt to the agents a command operates on.
    No --phase: all agents in journal order. With --phase: exactly one
    agent — attempt K (1-based, journal order), default the LAST attempt."""
    if phase_spec is None:
        return agents
    matched = _match_phase(agents, phase_spec)
    if not matched:
        sys.stderr.write(f"ERROR: no agent matching --phase {phase_spec} in workflow run.\n")
        sys.exit(1)
    if attempt is None:
        return [matched[-1]]
    if attempt < 1 or attempt > len(matched):
        sys.stderr.write(
            f"ERROR: --attempt {attempt} out of range for --phase {phase_spec} "
            f"({len(matched)} attempt(s)).\n")
        sys.exit(1)
    return [matched[attempt - 1]]

def load_agents(agents):
    """Concatenate transcript lines from agents in journal order."""
    all_lines = []
    for a in agents:
        all_lines.extend(load(a['path']))
    return all_lines

def _verdict_of(result_text):
    """Best-effort verdict extraction from a journal result payload."""
    if not result_text:
        return ''
    try:
        r = json.loads(result_text)
        if isinstance(r, dict) and 'verdict' in r:
            return str(r['verdict'])
    except (json.JSONDecodeError, TypeError):
        pass
    m = re.search(r'PHASE_VALIDATION:([\w-]+)', result_text)
    if m:
        return m.group(1)
    if 'ARTIFACT:' in result_text:
        return 'produced'
    return ''

def wf_summary(agents, wf_dir):
    """Workflow-mode `summary` (no --phase): one line per agent in journal
    order, then the final journal result."""
    results = _journal_results(wf_dir)
    print(f'{"#":>2s}  {"phase":42s} {"att":>3s} {"dur":>7s} {"size":>6s}  {"verdict"}')
    print('-' * 100)
    rows = []
    for i, a in enumerate(agents):
        first, last = _first_last_timestamps(a['path'])
        if first and last:
            dur = f"{int((_parse_ts(last) - _parse_ts(first)).total_seconds())}s"
        else:
            dur = 'n/a'
        lens_suffix = f' [{a["lens"][:20]}]' if a.get('lens') else ''
        phase_col = f'{a["phase_label"]}{lens_suffix}'[:42]
        size_kb = f'{a["size"] / 1024:.0f}k'
        verdict = _verdict_of(results.get(a['agent_id'], ''))
        print(f'{i:2d}  {phase_col:42s} {a["attempt"]:3d} {dur:>7s} {size_kb:>6s}  {verdict}')
    ordered_results = [results[a['agent_id']] for a in agents if a['agent_id'] in results]
    if ordered_results:
        head = ordered_results[-1].strip().split('\n')[0]
        print()
        print(f'final result: {head[:300]}')

def wf_agent_summary(agent, wf_dir):
    """Workflow-mode `summary --phase P`: per-agent details from its own
    transcript lines. Per-agent transcripts may lack a `result` record with
    usage — cost/stop print as n/a in that case."""
    lines = load(agent['path'])
    print(f"Phase: {agent['phase_label']} (attempt {agent['attempt']})")
    print(f"Agent: {agent['agent_id']}")
    first, last = _first_last_timestamps(agent['path'])
    if first and last:
        dur = (_parse_ts(last) - _parse_ts(first)).total_seconds()
        print(f"Duration: {dur:.0f}s ({dur/60:.1f}m)")
    else:
        print("Duration: n/a")
    result_rec = next((l for l in lines if l.get('type') == 'result'), None)
    if result_rec is not None:
        cost = result_rec.get('total_cost_usd')
        print(f"Cost: ${cost:.2f}" if cost is not None else "Cost: n/a")
        print(f"Turns: {result_rec.get('num_turns')}")
        print(f"Stop: {result_rec.get('stop_reason')}")
        print(f"Result: {str(result_rec.get('result', ''))[:300]}")
    else:
        turns = sum(1 for l in lines if l.get('type') == 'assistant')
        print("Cost: n/a")
        print(f"Turns: {turns} (assistant messages)")
        print("Stop: n/a")
        result_text = _journal_results(wf_dir).get(agent['agent_id'], '')
        if result_text:
            print(f"Result: {result_text[:300]}")
        else:
            print("Result: (no journal result for this agent)")

def wf_run_overview(agents, wf_dir):
    """Workflow-mode `run-overview`: one row per phase agent in journal order
    with start/end/duration and gap-before, plus the duration-sum footer."""
    rows = []
    print(f'{"#":>2s}  {"phase":42s} {"att":>3s} {"start":>9s} {"end":>9s} {"dur":>7s} {"gap-before":>11s}')
    prev_end = None
    for i, a in enumerate(agents):
        first, last = _first_last_timestamps(a['path'])
        lens_suffix = f' [{a["lens"][:20]}]' if a.get('lens') else ''
        phase_col = f'{a["phase_label"]}{lens_suffix}'[:42]
        if first is None:
            print(f'{i:2d}  {phase_col:42s} {a["attempt"]:3d} {"(no timestamps)":>9s}')
            continue
        t1 = _parse_ts(first)
        t2 = _parse_ts(last)
        dur = int((t2 - t1).total_seconds())
        gap = int((t1 - _parse_ts(prev_end)).total_seconds()) if prev_end else 0
        prev_end = last
        rows.append((first, last))
        print(f'{i:2d}  {phase_col:42s} {a["attempt"]:3d} {first[11:19]} {last[11:19]} {dur:6d}s {gap:10d}s')
    if not rows:
        sys.stderr.write(f"ERROR: no agent transcripts with timestamps in {wf_dir}.\n")
        sys.exit(1)
    _print_duration_footer(rows)

def run_workflow_mode(wf_dir, cmd_name, cmd_args, phase_spec, attempt):
    agents = build_agent_index(wf_dir)
    if not agents:
        sys.stderr.write(f"ERROR: no agent-*.jsonl transcripts in {wf_dir}.\n")
        sys.exit(1)
    if cmd_name == 'run-overview':
        wf_run_overview(agents, wf_dir)
        return
    selected = select_agents(agents, phase_spec, attempt)
    if cmd_name == 'summary':
        if phase_spec is None:
            wf_summary(agents, wf_dir)
        else:
            wf_agent_summary(selected[0], wf_dir)
        return
    # All other commands run on transcript lines: one agent's lines with
    # --phase, or every agent concatenated in journal order without it (so
    # module/final-write replay Write + Edits across all phases).
    COMMANDS[cmd_name](load_agents(selected), cmd_args)


COMMANDS = {
    'summary': cmd_summary,
    'plan': cmd_plan,
    'validation': cmd_validation,
    'implicit-spec': cmd_implicit_spec,
    'impl-validation': cmd_impl_validation,
    'test-validation': cmd_test_validation,
    'errors': cmd_errors,
    'thinking': cmd_thinking,
    'thinking-blocks': cmd_thinking_blocks,
    'reasoning': cmd_reasoning,
    'confusions': cmd_confusions,
    'writes': cmd_writes,
    'edits': cmd_edits,
    'module': cmd_module,
    'final-write': cmd_final_write,
    'test-runs': cmd_test_runs,
    'reads': cmd_reads,
    'todos': cmd_todos,
    'compaction': cmd_compaction,
    'timeline': cmd_timeline,
    'tool-results': cmd_tool_results,
    'search': cmd_search,
    'run-overview': cmd_run_overview,
}

# Legacy-mode commands that scan latest-transcripts/ themselves and do not
# need a single transcript loaded up front.
MULTI_TRANSCRIPT_COMMANDS = {'run-overview'}

if __name__ == '__main__':
    args = sys.argv[1:]
    phase = None
    attempt = None
    while args and args[0].startswith('--'):
        if args[0] == '--phase':
            if len(args) < 2:
                sys.stderr.write("ERROR: --phase requires an argument\n")
                sys.exit(2)
            phase = args[1]
            args = args[2:]
        elif args[0] == '--attempt':
            if len(args) < 2:
                sys.stderr.write("ERROR: --attempt requires an argument\n")
                sys.exit(2)
            try:
                attempt = int(args[1])
            except ValueError:
                sys.stderr.write(f"ERROR: --attempt must be an integer (got {args[1]!r})\n")
                sys.exit(2)
            args = args[2:]
        else:
            break
    if attempt is not None and phase is None:
        sys.stderr.write("ERROR: --attempt requires --phase\n")
        sys.exit(2)
    if not args or args[0] not in COMMANDS:
        print(__doc__)
        sys.exit(1)

    wf_dir = detect_wf_dir()
    if wf_dir is not None:
        run_workflow_mode(wf_dir, args[0], args[1:], phase, attempt)
    elif args[0] in MULTI_TRANSCRIPT_COMMANDS:
        COMMANDS[args[0]](None, args[1:])
    else:
        transcript_path = resolve_transcript_path(phase, attempt)
        lines = load(transcript_path)
        COMMANDS[args[0]](lines, args[1:])
