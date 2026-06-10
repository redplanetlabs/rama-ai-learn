#!/usr/bin/env python3
"""Analyze a challenge transcript for common patterns.

Usage:
  python3 scripts/analyze-latest-transcript.py [--phase N [--attempt K]] [command] [args...]

Transcript selection:
  Default:                       reads latest-transcript.jsonl in the repo root.
  --phase N:                     reads the latest attempt of phase N from
                                 latest-transcripts/ (highest-numbered attempt).
  --phase N --attempt K:         reads exactly attempt K of phase N. attempt 1 is
                                 the file without `-attempt` in the name.

  Populate `latest-transcripts/` with `bash scripts/docker-copy-transcript.sh`
  (default mode copies all transcripts of the most recent run).

Commands:
  summary              - Run result, cost, duration, turn count
  plan                 - Show PLAN.md content
  validation           - Show PLAN_VALIDATION.md content
  implicit-spec        - Show IMPLICIT_SPEC.md content
  impl-validation      - Show IMPLEMENTATION_VALIDATION.md content
  test-validation      - Show TEST_VALIDATION.md content
  errors               - Show all compilation/test errors
  thinking <query>     - Search thinking blocks for a keyword/phrase
  thinking-blocks      - Show raw structure of every thinking block
  reasoning            - Show REASONING.md appends made during the transcript
  confusions           - Show only CONFUSION entries from REASONING.md appends
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
  run-overview         - Table of all transcripts in latest-transcripts/ sorted by start
                         time, with duration and gap-before for each phase
"""

import json
import sys
import re
import os
import glob

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TRANSCRIPT = os.path.join(REPO_ROOT, 'latest-transcript.jsonl')
LATEST_TRANSCRIPTS_DIR = os.path.join(REPO_ROOT, 'latest-transcripts')

def resolve_transcript_path(phase=None, attempt=None):
    """Resolve which transcript file to read.

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
        return [json.loads(l) for l in f]

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

def _reasoning_appends(lines):
    """Yield (line_index, label, text) for every REASONING.md append in the
    transcript: Bash heredoc/echo appends plus Write/Edit tool calls."""
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if block.get('type') != 'tool_use':
                continue
            name = block.get('name')
            inp = block.get('input', {})
            if name == 'Bash':
                cmd = inp.get('command', '')
                if 'REASONING.md' not in cmd:
                    continue
                m = re.search(r"<<\s*'?(\w+)'?\n(.*?)\n\1", cmd, re.DOTALL)
                yield (i, 'Bash', m.group(2) if m else cmd)
            elif name in ('Write', 'Edit'):
                fp = inp.get('file_path', '')
                if 'REASONING.md' not in fp:
                    continue
                content = inp.get('content') or inp.get('new_string', '')
                yield (i, f"{name} {fp}", content)

def cmd_reasoning(lines, args):
    """Show REASONING.md appends made during this transcript. Phase sentinels
    are written by the runner (not the agent), so attribute entries to phases
    by running this per-phase via --phase N."""
    found = False
    for i, label, text in _reasoning_appends(lines):
        found = True
        print(f"=== LINE {i} ({label}) ===")
        print(text)
        print()
    if not found:
        print("(no REASONING.md activity in this transcript)")

def cmd_confusions(lines, args):
    """Show only REASONING.md appends containing CONFUSION entries — the
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
            if block.get('type') == 'tool_use' and block.get('name') == 'Bash':
                cmd = block.get('input', {}).get('command', '')
                if 'clojure -X:test' in cmd:
                    print(f"LINE {i}: {cmd[:150]}")
                    # Find result
                    for j in range(i+1, min(i+5, len(lines))):
                        msg2 = lines[j].get('message', {})
                        for b2 in msg2.get('content', []):
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
            if block.get('type') == 'tool_use' and block.get('name') == 'Read':
                fp = block.get('input', {}).get('file_path', '')
                print(f"LINE {i}: {fp}")

def cmd_todos(lines, args):
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
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

def cmd_run_overview(lines, args):
    """Scan latest-transcripts/, print one row per file sorted by first timestamp."""
    from datetime import datetime
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
        if first is None:
            continue
        name = os.path.basename(path)
        m = re.search(r'-(phase\d+(?:-attempt\d+)?)\.jsonl$', name)
        label = m.group(1) if m else name
        rows.append((first, last, label))
    rows.sort()
    def parse(ts):
        return datetime.fromisoformat(ts.replace('Z', '+00:00'))
    run_start = parse(rows[0][0])
    print(f'{"phase":24s} {"start":>9s} {"end":>9s} {"dur":>7s} {"gap-before":>11s}')
    prev_end = None
    total_dur = 0
    for first, last, label in rows:
        t1 = parse(first)
        t2 = parse(last)
        dur = int((t2 - t1).total_seconds())
        gap = int((t1 - parse(prev_end)).total_seconds()) if prev_end else 0
        prev_end = last
        total_dur += dur
        print(f'{label:24s} {first[11:19]} {last[11:19]} {dur:6d}s {gap:10d}s')
    wall = int((parse(rows[-1][1]) - run_start).total_seconds())
    print()
    print(f'sum of phase durations: {total_dur}s ({total_dur/60:.1f}m)')
    print(f'wall clock first->last: {wall}s ({wall/60:.1f}m)')
    print(f'gap total (non-phase):  {wall - total_dur}s ({(wall - total_dur)/60:.1f}m)')

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

# Commands that scan latest-transcripts/ themselves and do not need a single
# transcript loaded up front.
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
    if args[0] in MULTI_TRANSCRIPT_COMMANDS:
        COMMANDS[args[0]](None, args[1:])
    else:
        transcript_path = resolve_transcript_path(phase, attempt)
        lines = load(transcript_path)
        COMMANDS[args[0]](lines, args[1:])
