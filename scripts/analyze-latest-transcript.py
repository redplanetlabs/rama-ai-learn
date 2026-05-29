#!/usr/bin/env python3
"""Analyze transcripts from a rama-challenge workflow run.

Usage:
  python3 scripts/analyze-latest-transcript.py [--phase P] [command] [args...]

Transcript location:
  Reads from latest-transcripts/ (populated by docker-copy-transcript.sh).
  The directory contains:
    wf_*/agent-*.jsonl     per-phase subagent transcripts
    wf_*/journal.jsonl     workflow journal (agent started/result events)
    *.jsonl                top-level session transcript (optional)

Phase selection:
  --phase P               Select a specific phase. P can be:
                            0-7          phase number (e.g. --phase 3)
                            2.fault      validation lens (e.g. --phase 4.dataflow)
                            scribe-2     scribe agent for a validation phase
                            If multiple agents match (retries), uses the last one.

Commands:
  summary              - Overview of all phases: agent, phase, verdict, duration
  plan                 - Show PLAN.md content (from Phase 1 agent)
  validation           - Show PLAN_VALIDATION.md content (from Phase 2 scribe)
  implicit-spec        - Show IMPLICIT_SPEC.md content (from Phase 0 agent)
  impl-validation      - Show IMPLEMENTATION_VALIDATION.md content (from Phase 4 scribe)
  test-validation      - Show TEST_VALIDATION.md content (from Phase 6 scribe)
  errors               - Show all compilation/test errors
  thinking <query>     - Search thinking blocks for a keyword/phrase
  writes <query>       - Search Write tool calls for a keyword
  edits <query>        - Search Edit tool calls for a keyword
  module               - Show the final module.clj content (replays Write + Edits)
  module all           - Show every Write of module.clj (history, no Edit replay)
  final-write <name>   - Reconstruct any file's final state by replaying Write + Edits
  test-runs            - List all test runs and their results
  reads                - List all Read tool calls
  timeline             - Show high-level timeline of actions
  tool-results <query> - Search tool result content for a keyword
  search <query>       - Search ALL content (text, thinking, tool use, tool results)
"""

import json
import sys
import re
import os
import glob
from datetime import datetime

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LATEST_TRANSCRIPTS_DIR = os.path.join(REPO_ROOT, 'latest-transcripts')


# ---- Agent discovery and phase mapping ----

def find_wf_dir():
    """Find the workflow directory under latest-transcripts/."""
    if not os.path.isdir(LATEST_TRANSCRIPTS_DIR):
        sys.stderr.write(
            f"ERROR: {LATEST_TRANSCRIPTS_DIR}/ does not exist.\n"
            f"Populate it with: bash scripts/docker-copy-transcript.sh\n")
        sys.exit(1)
    wf_dirs = glob.glob(os.path.join(LATEST_TRANSCRIPTS_DIR, 'wf_*'))
    if not wf_dirs:
        sys.stderr.write(f"ERROR: no wf_* directory in {LATEST_TRANSCRIPTS_DIR}/.\n")
        sys.exit(1)
    # Pick the one with the most recent file
    def latest_mtime(d):
        files = glob.glob(os.path.join(d, '*.jsonl'))
        return max((os.path.getmtime(f) for f in files), default=0)
    return max(wf_dirs, key=latest_mtime)


def classify_agent(path):
    """Read the first user message from an agent transcript and classify it.
    Returns a dict with:
      phase_num: int or None (0-7)
      phase_label: str  (e.g. "Phase 0: Implicit Spec")
      role: 'work' | 'reviewer' | 'scribe'
      lens: str or None  (for reviewers)
    """
    info = {'phase_num': None, 'phase_label': '?', 'role': 'work', 'lens': None}
    try:
        with open(path) as f:
            for line in f:
                rec = json.loads(line)
                if rec.get('type') != 'user':
                    continue
                content = rec.get('message', {}).get('content', '')
                if not isinstance(content, str):
                    continue

                # Scribe agents
                if content.startswith('You are writing a single validation artifact'):
                    info['role'] = 'scribe'
                    # Extract which validation from the template path
                    if 'plan-validation' in content:
                        info['phase_num'] = 2
                        info['phase_label'] = 'Phase 2: Plan Validation (scribe)'
                    elif 'impl-validation' in content:
                        info['phase_num'] = 4
                        info['phase_label'] = 'Phase 4: Impl Validation (scribe)'
                    elif 'test-validation' in content:
                        info['phase_num'] = 6
                        info['phase_label'] = 'Phase 6: Test Validation (scribe)'
                    return info

                # Work and reviewer agents — look for THIS PHASE:
                for l in content.split('\n'):
                    if 'THIS PHASE:' in l:
                        phase_text = l.strip().replace('THIS PHASE:', '').strip()
                        info['phase_label'] = phase_text[:80]

                        # Extract phase number
                        m = re.search(r'Phase\s+(\d)', phase_text)
                        if m:
                            info['phase_num'] = int(m.group(1))

                        # Reviewer detection
                        if 'adversarial review panel' in phase_text.lower() or 'adversarial' in content[:500].lower():
                            info['role'] = 'reviewer'

                    if 'YOUR LENS:' in l:
                        info['lens'] = l.strip().replace('YOUR LENS:', '').strip()[:60]
                        info['role'] = 'reviewer'

                return info
    except Exception:
        pass
    return info


def build_agent_index(wf_dir):
    """Build an ordered list of agent info dicts for the workflow run.
    Each entry: {path, agent_id, phase_num, phase_label, role, lens, size, mtime}
    Sorted by file mtime of the .jsonl (approximates execution order)."""
    agents = []
    for fname in os.listdir(wf_dir):
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
    # Sort by journal order: use started events if available, else mtime
    journal_order = _journal_order(wf_dir)
    if journal_order:
        order_map = {aid: i for i, aid in enumerate(journal_order)}
        agents.sort(key=lambda a: order_map.get(a['agent_id'], 999))
    else:
        agents.sort(key=lambda a: a['mtime'])
    return agents


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
                    if isinstance(r, dict):
                        r = json.dumps(r)
                    results[rec['agentId']] = r
            except Exception:
                continue
    return results


def resolve_agent(agents, phase_spec):
    """Resolve a --phase spec to one or more agent info dicts.
    phase_spec can be:
      "3"          -> Phase 3 work agent(s)
      "4.dataflow" -> Phase 4 reviewer with 'dataflow' in lens
      "scribe-2"   -> Phase 2 scribe
    Returns a list of matching agents (last match is typically the retry you want)."""
    if phase_spec is None:
        return agents

    # scribe-N
    m = re.match(r'scribe[- ]?(\d)', phase_spec)
    if m:
        num = int(m.group(1))
        return [a for a in agents if a['phase_num'] == num and a['role'] == 'scribe']

    # N.lens
    if '.' in phase_spec:
        parts = phase_spec.split('.', 1)
        try:
            num = int(parts[0])
        except ValueError:
            return []
        lens_q = parts[1].lower()
        return [a for a in agents
                if a['phase_num'] == num and a['role'] == 'reviewer'
                and a.get('lens') and lens_q in a['lens'].lower()]

    # Plain number
    try:
        num = int(phase_spec)
    except ValueError:
        return []
    return [a for a in agents if a['phase_num'] == num and a['role'] == 'work']


def load(path):
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]


def load_agents(agents):
    """Load and concatenate transcript lines from multiple agents."""
    all_lines = []
    for a in agents:
        all_lines.extend(load(a['path']))
    return all_lines


# ---- Commands ----

def cmd_summary(agents, args, wf_dir):
    results = _journal_results(wf_dir)
    print(f"{'#':>2s}  {'agent_id':12s}  {'role':8s}  {'phase':50s}  {'size':>8s}  {'verdict'}")
    print("-" * 110)
    for i, a in enumerate(agents):
        result_text = results.get(a['agent_id'], '')
        # Try to extract verdict from structured JSON results
        verdict = ''
        try:
            r = json.loads(result_text)
            if isinstance(r, dict) and 'verdict' in r:
                verdict = r['verdict']
        except (json.JSONDecodeError, TypeError):
            pass
        if not verdict:
            # Check for PHASE_VALIDATION in result text
            m = re.search(r'PHASE_VALIDATION:([\w-]+)', result_text)
            if m:
                verdict = m.group(1)
            elif 'ARTIFACT:' in result_text:
                verdict = 'produced'

        lens_suffix = f' [{a["lens"][:30]}]' if a.get('lens') else ''
        phase_col = f'{a["phase_label"][:50]}{lens_suffix}'[:50]
        size_kb = f'{a["size"] / 1024:.0f}k'
        print(f'{i:2d}  {a["agent_id"][:12]:12s}  {a["role"]:8s}  {phase_col:50s}  {size_kb:>8s}  {verdict}')


def cmd_plan(agents, args, wf_dir):
    matched = [a for a in agents if a['phase_num'] == 1 and a['role'] == 'work']
    if not matched:
        print("(no Phase 1 agent found)")
        return
    _show_write(load(matched[-1]['path']), 'PLAN.md')


def cmd_validation(agents, args, wf_dir):
    matched = [a for a in agents if a['phase_num'] == 2 and a['role'] == 'scribe']
    if not matched:
        # Fall back to any phase 2 agent
        matched = [a for a in agents if a['phase_num'] == 2]
    if not matched:
        print("(no Phase 2 agent found)")
        return
    _show_write(load(matched[-1]['path']), 'PLAN_VALIDATION')


def cmd_implicit_spec(agents, args, wf_dir):
    matched = [a for a in agents if a['phase_num'] == 0 and a['role'] == 'work']
    if not matched:
        print("(no Phase 0 agent found)")
        return
    _show_write(load(matched[-1]['path']), 'IMPLICIT_SPEC')


def cmd_impl_validation(agents, args, wf_dir):
    matched = [a for a in agents if a['phase_num'] == 4 and a['role'] == 'scribe']
    if not matched:
        matched = [a for a in agents if a['phase_num'] == 4]
    if not matched:
        print("(no Phase 4 agent found)")
        return
    _show_write(load(matched[-1]['path']), 'IMPLEMENTATION_VALIDATION')


def cmd_test_validation(agents, args, wf_dir):
    matched = [a for a in agents if a['phase_num'] == 6 and a['role'] == 'scribe']
    if not matched:
        matched = [a for a in agents if a['phase_num'] == 6]
    if not matched:
        print("(no Phase 6 agent found)")
        return
    _show_write(load(matched[-1]['path']), 'TEST_VALIDATION')


def cmd_module(agents, args, wf_dir):
    """Show final module.clj. Use 'module all' to see every write (history)."""
    # Collect from all phase 3 agents + phase 7 (which may edit module)
    matched = [a for a in agents if a['phase_num'] in (3, 7) and a['role'] == 'work']
    if not matched:
        print("(no Phase 3/7 agent found)")
        return
    lines = load_agents(matched)
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


def cmd_final_write(agents, args, wf_dir):
    """Reconstruct final state of any file by replaying Write + Edits across all agents."""
    if not args:
        print("Usage: final-write <name-substring>")
        return
    name = args[0]
    lines = load_agents(agents)
    final = _final_file_content(lines, name)
    if final is None:
        print(f"(no Write of {name} found)")
        return
    i, fp, content = final
    print(f"=== FINAL {fp} ({len(content)} chars, last touched at line {i}) ===")
    print(content)


def cmd_errors(agents, args, wf_dir):
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
        if block.get('type') == 'tool_result':
            c = block.get('content', '')
            if isinstance(c, str) and any(kw in c for kw in [
                'FAIL in', 'ERROR in', 'Syntax error', 'Unable to resolve',
                'CompilerException', 'ClassCastException',
                'NullPointerException', 'IllegalArgumentException'
            ]):
                if c.startswith('1\t#'):
                    continue
                print(f"=== LINE {i} ===")
                print(c[:500])
                print()


def cmd_thinking(agents, args, wf_dir):
    if not args:
        print("Usage: thinking <keyword>")
        return
    query = ' '.join(args)
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
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


def cmd_writes(agents, args, wf_dir):
    query = ' '.join(args) if args else ''
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
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


def cmd_edits(agents, args, wf_dir):
    query = ' '.join(args) if args else ''
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
        if block.get('type') == 'tool_use' and block.get('name') == 'Edit':
            fp = block.get('input', {}).get('file_path', '')
            old = block['input'].get('old_string', '')
            new = block['input'].get('new_string', '')
            if not query or query.lower() in fp.lower() or query.lower() in old.lower() or query.lower() in new.lower():
                print(f"LINE {i}: Edit {fp}")
                print(f"  old: {old[:200]}")
                print(f"  new: {new[:200]}")
                print()


def cmd_test_runs(agents, args, wf_dir):
    matched = [a for a in agents if a['phase_num'] == 7 and a['role'] == 'work']
    if not matched:
        matched = agents
    lines = load_agents(matched)
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if not isinstance(block, dict):
                continue
            if block.get('type') == 'tool_use' and block.get('name') == 'Bash':
                cmd = block.get('input', {}).get('command', '')
                if 'clojure -X:test' in cmd or 'clojure -X:test-harness' in cmd:
                    print(f"LINE {i}: {cmd[:150]}")
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


def cmd_reads(agents, args, wf_dir):
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
        if block.get('type') == 'tool_use' and block.get('name') == 'Read':
            fp = block.get('input', {}).get('file_path', '')
            print(f"LINE {i}: {fp}")


def cmd_timeline(agents, args, wf_dir):
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
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
                print(f"LINE {i} BASH: {inp.get('command', '')[:100]}")
            elif name == 'Edit':
                print(f"LINE {i} EDIT: {inp.get('file_path', '')}")
            elif name == 'Skill':
                print(f"LINE {i} SKILL: {inp.get('skill', '')}")
            else:
                print(f"LINE {i} {name}")
        elif t == 'thinking':
            text = block.get('thinking', '')
            print(f"LINE {i} THINKING: ({len(text)} chars)")


def cmd_tool_results(agents, args, wf_dir):
    if not args:
        print("Usage: tool-results <keyword>")
        return
    query = ' '.join(args)
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
        if block.get('type') == 'tool_result':
            c = block.get('content', '')
            if isinstance(c, str) and re.search(query, c, re.IGNORECASE):
                print(f"=== LINE {i} ===")
                idx = re.search(query, c, re.IGNORECASE).start()
                start = max(0, idx - 200)
                end = min(len(c), idx + 400)
                print(c[start:end])
                print()


def cmd_search(agents, args, wf_dir):
    if not args:
        print("Usage: search <keyword>")
        return
    query = ' '.join(args)
    lines = load_agents(agents)
    for i, block in _iter_blocks(lines):
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


# ---- Helpers ----

def _iter_blocks(lines):
    """Iterate over (line_index, block) for all content blocks in lines,
    skipping non-dict blocks (plain strings from user messages)."""
    for i, line in enumerate(lines):
        msg = line.get('message', {})
        for block in msg.get('content', []):
            if isinstance(block, dict):
                yield i, block


def _show_write(lines, name, require=None, last_only=False):
    matches = []
    for i, block in _iter_blocks(lines):
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


def _final_file_content(lines, name, require=None):
    """Replay Write + Edits for files matching name to get final content."""
    files = {}
    for i, block in _iter_blocks(lines):
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
    latest_fp = max(files, key=lambda fp: files[fp][0])
    last_i, content = files[latest_fp]
    return (last_i, latest_fp, content)


def _timeline_entry(i, line):
    msg = line.get('message', {})
    for block in msg.get('content', []):
        if not isinstance(block, dict):
            continue
        t = block.get('type', '')
        if t == 'text':
            text = block.get('text', '')
            if len(text) > 30:
                return f"LINE {i} TEXT: {text[:150]}"
        elif t == 'tool_use':
            name = block.get('name', '')
            inp = block.get('input', {})
            if name == 'Write':
                return f"LINE {i} WRITE: {inp.get('file_path', '')}"
            elif name == 'Read':
                return f"LINE {i} READ: {inp.get('file_path', '')}"
            elif name == 'Bash':
                return f"LINE {i} BASH: {inp.get('command', '')[:100]}"
            elif name == 'Edit':
                return f"LINE {i} EDIT: {inp.get('file_path', '')}"
            elif name == 'Skill':
                return f"LINE {i} SKILL: {inp.get('skill', '')}"
            else:
                return f"LINE {i} {name}"
        elif t == 'thinking':
            text = block.get('thinking', '')
            return f"LINE {i} THINKING: ({len(text)} chars)"
    return None


# ---- Command registry ----

COMMANDS = {
    'summary': cmd_summary,
    'plan': cmd_plan,
    'validation': cmd_validation,
    'implicit-spec': cmd_implicit_spec,
    'impl-validation': cmd_impl_validation,
    'test-validation': cmd_test_validation,
    'errors': cmd_errors,
    'thinking': cmd_thinking,
    'writes': cmd_writes,
    'edits': cmd_edits,
    'module': cmd_module,
    'final-write': cmd_final_write,
    'test-runs': cmd_test_runs,
    'reads': cmd_reads,
    'timeline': cmd_timeline,
    'tool-results': cmd_tool_results,
    'search': cmd_search,
}

# Commands that operate on all agents regardless of --phase
GLOBAL_COMMANDS = {'summary'}

if __name__ == '__main__':
    args = sys.argv[1:]
    phase_spec = None

    while args and args[0].startswith('--'):
        if args[0] == '--phase':
            if len(args) < 2:
                sys.stderr.write("ERROR: --phase requires an argument\n")
                sys.exit(2)
            phase_spec = args[1]
            args = args[2:]
        else:
            break

    if not args or args[0] not in COMMANDS:
        print(__doc__)
        sys.exit(1)

    cmd_name = args[0]
    cmd_args = args[1:]

    wf_dir = find_wf_dir()
    all_agents = build_agent_index(wf_dir)

    if cmd_name in GLOBAL_COMMANDS:
        COMMANDS[cmd_name](all_agents, cmd_args, wf_dir)
    else:
        selected = resolve_agent(all_agents, phase_spec)
        if not selected:
            if phase_spec:
                sys.stderr.write(f"ERROR: no agent matching --phase {phase_spec}\n")
                sys.exit(1)
            selected = all_agents
        COMMANDS[cmd_name](selected, cmd_args, wf_dir)
