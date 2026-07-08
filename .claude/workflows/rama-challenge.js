export const meta = {
  name: 'rama-challenge',
  description: 'Phased Rama module build for any challenge (phases 0-7, single-validator gates, verdict-driven routing)',
  whenToUse: 'Implement a Rama challenge end-to-end following the rama skill phased process. User says "run rama-challenge for <name>" or "implement the <name> challenge".',
  phases: [
    { title: 'Phase 0: Implicit Spec', detail: 'derive IMPLICIT_SPEC.md from README + protocol' },
    { title: 'Phase 1: Plan', detail: 'design PStates/depots/topologies into PLAN.md' },
    { title: 'Phase 2: Plan Validation', detail: 'adversarial plan review; minor-fail fixes PLAN.md in place, major-fail loops back to Phase 1' },
    { title: 'Phase 3: Implement', detail: 'write module.clj; compile + lint clean' },
    { title: 'Phase 4: Impl Validation', detail: 'adversarial code review; minor/major-fail loops back to Phase 3' },
    { title: 'Phase 5: Tests', detail: 'write tests covering protocol + implicit spec' },
    { title: 'Phase 6: Test Validation', detail: 'adversarial test review; minor-fail proceeds to Phase 7, major-fail loops back to Phase 5' },
    { title: 'Phase 7: Finish', detail: 'run clojure -X:test in a loop until 0 failures, 0 errors' },
  ],
}

// ---- resolve challenge name from the workflow argument ----
// The workflow is invoked as `/rama-challenge <name>`; the runtime injects
// the argument text as the global `args`.
function resolveChallengeName() {
  if (typeof args === 'string' && args.trim()) {
    return args.trim()
  }
  throw new Error('Challenge name required. Invoke the workflow as `/rama-challenge <challenge-name>`.')
}

const CH = resolveChallengeName()
const CH_UNDER = CH.replace(/-/g, '_')
const IMPL = `implementations/${CH}`
const SK = 'plugins/rama-skill/skills/rama'
const CHDIR = `challenges/${CH}`

// Max consecutive validation fails per gate (2, 4, 6). Both minor-fail and
// major-fail count; a gate's counter resets to 0 on pass (and, for gate 2,
// on minor-fail, since minor-fail proceeds forward). When a 4th consecutive
// fail lands at a gate, the run stops as an overall failure — it does NOT
// proceed with a known-bad artifact.
const VALIDATION_RETRY_CAP = 3

// ---- phase dispatch table ----
const PHASES = {
  0: { title: 'Phase 0: Implicit Spec', slug: 'phase0:implicit-spec', doc: 'phase-0-implicit-spec.md', artifact: `${IMPL}/IMPLICIT_SPEC.md` },
  1: { title: 'Phase 1: Plan', slug: 'phase1:plan', doc: 'phase-1-plan.md', artifact: `${IMPL}/PLAN.md` },
  2: { title: 'Phase 2: Plan Validation', slug: 'phase2:plan-validate', doc: 'phase-2-plan-validate.md', artifact: `${IMPL}/PLAN_VALIDATION.md` },
  3: { title: 'Phase 3: Implement', slug: 'phase3:implement', doc: 'phase-3-implement.md', artifact: `${IMPL}/src/${CH_UNDER}/module.clj` },
  4: { title: 'Phase 4: Impl Validation', slug: 'phase4:impl-validate', doc: 'phase-4-impl-validate.md', artifact: `${IMPL}/IMPLEMENTATION_VALIDATION.md` },
  5: { title: 'Phase 5: Tests', slug: 'phase5:tests', doc: 'phase-5-tests.md', artifact: `${IMPL}/test/ (test sources)` },
  6: { title: 'Phase 6: Test Validation', slug: 'phase6:test-validate', doc: 'phase-6-test-validate.md', artifact: `${IMPL}/TEST_VALIDATION.md` },
  7: { title: 'Phase 7: Finish', slug: 'phase7:finish', doc: 'phase-7-finish.md', artifact: 'passing tests; module + tests modified in place' },
}

// ---- shared preamble (ported from the retired /challenge-phase command) ----
function preamble(n, k) {
  return `You are an autonomous agent executing ONE phase of a phased Rama module build for the "${CH}" challenge. The orchestrating workflow invoked you with a fresh context. Do this phase only — do NOT run any later phase, do NOT run the test suite unless this is Phase 7, do NOT try to "finish the whole thing." Working directory is the project root.

impl-root = ${IMPL}
skill-root = ${SK}

REASONING LOG — FIRST ACTION (before anything else)
Your reasoning log for this session is ${IMPL}/reasoning/phase${n}-attempt${k}.md — a per-phase, per-attempt file. Every phase agent gets its own file; do NOT write to any other reasoning file. Your very first action in this session must be to create it with its header (run from the project root):

  mkdir -p ${IMPL}/reasoning
  cat >> ${IMPL}/reasoning/phase${n}-attempt${k}.md <<EOF
# PHASE ${n} attempt ${k} — $(date '+%Y-%m-%d %H:%M:%S')

EOF

As you work, append your reasoning AT EACH DECISION POINT — the alternatives you weighed, why you rejected them, and which constraint drove the choice. Write entries as you decide, not as a summary at the end; a retrospective summary loses the dead ends, and the dead ends are the point.

ALWAYS log anything you are confused or uncertain about, AT THE MOMENT of confusion: an API whose behavior you can't predict, documentation that seems ambiguous or contradictory, an error you don't understand, a constraint you aren't sure how to satisfy. Log it even if — especially if — you resolve the confusion moments later, and note what resolved it. Confusion entries are the single most valuable content in this file: they identify exactly where the skill documentation failed you. Mark them with a "CONFUSION:" prefix.

- Append with:  cat >> ${IMPL}/reasoning/phase${n}-attempt${k}.md <<'EOF' ... EOF
- The file is append-only: do NOT rewrite it and do NOT edit or delete prior entries.
- You MAY read earlier sessions' files under ${IMPL}/reasoning/ for prior context.
- This phase is NOT complete until your reasoning file has at least one entry beyond the header. Validation phases (2, 4, 6, 7): record the reasoning behind your verdict before emitting it.

PRE-FLIGHT (every phase, after the sentinel)
1. Verify ${CHDIR}/README.md exists. If not, list the available challenge directories and stop.
2. Read ${CHDIR}/README.md and any protocol/source files it references under ${CHDIR}/src/ — this is the contract.
3. Do NOT read or open ANY file under ${CHDIR}/test-resources/ or ${CHDIR}/test-private/ — those are private. Do NOT read any other challenge directory under challenges/.
4. Read the Rama skill ${SK}/SKILL.md and obey its production rules.

PRODUCTION DESIGN (always on)
You are building a production Rama module. It will be deployed under real conditions — node failures, processing retries, concurrent clients, high throughput. Tests evaluate fault-tolerance and performance, not just functional correctness. Do NOT cut corners because "the test won't hit this case" — that produces unacceptable production behavior.
Any specification in the README or protocol is non-negotiable.

PROJECT-SPECIFIC RULES
- Do NOT run any command with run_in_background. You are running headlessly: ending your turn ends the session, and background-task completion notifications will NEVER arrive. A backgrounded test run is orphaned and the phase fails with no verdict. Run long commands (test suites, REPL checks) in the foreground with an explicit generous timeout.
- rama-helpers is available but not necessarily needed. Use only if relevant and helpful.
- The test harness does NOT influence topology choice. The challenge harness (wait-for-processing*) auto-detects stream vs microbatch and synchronizes correctly for both. Choose topology based on domain requirements, not test synchronization patterns.
- Phase 5 (tests) constraints: Do NOT use rama-challenges.harness in tests — not in requires, imports, or fully-qualified calls. If the module uses tick depots, use rama-challenges.shared/REPLACE-TICK-DEPOTS (already on classpath); read its docstring for usage.

PRODUCTION DEPLOYMENT
After the full phased build passes tests, this module will be:
1. Deployed to a multi-node Rama cluster with 64+ tasks across 8+ workers
2. Serving production traffic from multiple application servers running concurrent clients
3. Operating continuously for months, accumulating unbounded data over time
4. Subject to node failures, network partitions, and processing retries during normal operation
The module must handle all of these correctly. There is no opportunity to fix issues after deployment.`
}

// ---- retry blocks (used when a prior phase re-runs after a validation fail) ----
function retryBlock(n) {
  const snapshot = `RETRY: this phase is being re-run because a downstream validation phase emitted FAIL items. FIRST (right after creating your reasoning file), snapshot the current implementation by running from the project root:

  bash scripts/save-attempt.sh ${CH}

Then address the validation findings:`
  if (n === 1) {
    return `${snapshot}
${IMPL}/PLAN_VALIDATION.md exists with FAIL entries from Phase 2. Read it in full. Revise ${IMPL}/PLAN.md to address EVERY FAIL item explicitly. Update the "Rejected alternatives" section to record what was tried and rejected. Do not regress items that already passed.`
  }
  if (n === 3) {
    return `${snapshot}
${IMPL}/IMPLEMENTATION_VALIDATION.md exists with FAIL entries from Phase 4. Read it in full. Revise the module source to address EVERY FAIL item. Do not regress items that already passed.`
  }
  if (n === 5) {
    return `${snapshot}
${IMPL}/TEST_VALIDATION.md exists with FAIL entries from Phase 6. Read it in full. Revise the test sources to address EVERY FAIL item. Do not regress passing coverage.`
  }
  return ''
}

// ---- verdict instructions ----
const GATE_VERDICT_TEXT = `VERDICT
The phase doc requires you to emit one of PHASE_VALIDATION:pass, PHASE_VALIDATION:minor-fail, or PHASE_VALIDATION:major-fail as the LAST non-empty line of your output — do that. Default to major-fail; pass ONLY after the criteria in the phase doc are met.
ALSO return your final result as structured output matching the requested schema:
- verdict: "pass" | "minor-fail" | "major-fail" — identical to the PHASE_VALIDATION line
- summary: 2-4 sentences describing the decisive findings behind the verdict`

const FINISH_VERDICT_TEXT = `VERDICT
The phase doc requires you to emit PHASE_VALIDATION:pass or PHASE_VALIDATION:fail as the LAST non-empty line of your output — do that. Emit pass ONLY if the final test-suite run showed 0 failures, 0 errors.
ALSO return your final result as structured output matching the requested schema:
- verdict: "pass" | "fail" — identical to the PHASE_VALIDATION line
- summary: what passed, or what is still failing and what you tried
- test_output_tail: the last ~30 lines of the final test-suite output`

// ---- prompt assembly ----
function buildPrompt(n, k) {
  const info = PHASES[n]
  const parts = [preamble(n, k)]
  parts.push(`THIS PHASE: ${info.title}`)
  if (n === 0) {
    parts.push(`Before the phase work, import clj-kondo configs (run from the project root):

  bash scripts/import-kondo-configs.sh ${CH}`)
  }
  if (k > 1 && (n === 1 || n === 3 || n === 5)) {
    parts.push(retryBlock(n))
  }
  parts.push(`PHASE DISPATCH
Read ${SK}/references/${info.doc} and follow it exactly. Do NOT read any other phase doc. Substitute <impl-root> with ${IMPL} and <skill-root> with ${SK} in any command the doc gives.
OUTPUT ARTIFACT: ${info.artifact}`)
  if (n === 2 || n === 4 || n === 6) parts.push(GATE_VERDICT_TEXT)
  if (n === 7) parts.push(FINISH_VERDICT_TEXT)
  return parts.join('\n\n')
}

// ---- structured-output schemas ----
const GATE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['verdict', 'summary'],
  properties: {
    verdict: { type: 'string', enum: ['pass', 'minor-fail', 'major-fail'] },
    summary: { type: 'string' },
  },
}
const FINISH_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['verdict', 'summary', 'test_output_tail'],
  properties: {
    verdict: { type: 'string', enum: ['pass', 'fail'] },
    summary: { type: 'string' },
    test_output_tail: { type: 'string' },
  },
}

// ===================== MAIN FLOW =====================
// Routing (mirrors the previous runner's phase-loop!):
//   0 → 1 → 2
//   2 pass|minor-fail → 3 (minor-fail: validator already fixed PLAN.md; reset gate counter)
//   2 major-fail      → 1 retry
//   3 → 4 (unless skip-4 flag set, then 3 → 5, clearing the flag)
//   4 pass       → 5
//   4 minor-fail → 3 retry, then skip 4 (fix too small to re-validate)
//   4 major-fail → 3 retry, then 4 re-runs
//   5 → 6
//   6 pass       → 7
//   6 minor-fail → 7 directly (phase 7's pre-loop absorbs TEST_VALIDATION fixes)
//   6 major-fail → 5 retry, then 6 re-runs
//   7 pass → overall PASS; anything else → overall fail
// Gate cap: a 4th consecutive fail at any gate stops the run as a failure.

const attempts = { 0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0, 7: 0 }
const gateFails = { 2: 0, 4: 0, 6: 0 }
const gateVerdicts = { 2: [], 4: [], 6: [] }
let skip4 = false
let finishOut = null
let overall = null
let stopReason = null

function labelFor(n, k) {
  return PHASES[n].slug + (k > 1 ? '-retry' + k : '')
}

async function runPhaseAgent(n) {
  attempts[n] += 1
  const k = attempts[n]
  const info = PHASES[n]
  phase(info.title)
  const opts = { phase: info.title, label: labelFor(n, k) }
  if (n === 2 || n === 4 || n === 6) opts.schema = GATE_SCHEMA
  if (n === 7) opts.schema = FINISH_SCHEMA
  return agent(buildPrompt(n, k), opts)
}

let phaseId = 0
run: while (true) {
  // One-shot skip for phase 4 (after a gate-4 minor-fail on the prior round).
  if (phaseId === 4 && skip4) {
    skip4 = false
    log('Phase 4 skipped: the prior minor-fail fix is too small to re-validate — proceeding to Phase 5.')
    phaseId = 5
    continue
  }

  const out = await runPhaseAgent(phaseId)

  switch (phaseId) {
    // Non-validation phases: advance once the phase agent returns.
    case 0: phaseId = 1; break
    case 1: phaseId = 2; break
    case 3: phaseId = 4; break
    case 5: phaseId = 6; break

    case 2: {
      const v = out && out.verdict
      gateVerdicts[2].push(v || '(none)')
      if (v === 'pass' || v === 'minor-fail') {
        gateFails[2] = 0
        log(`Phase 2 verdict: ${v}${v === 'minor-fail' ? ' — validator fixed PLAN.md directly; no re-plan' : ''} → Phase 3`)
        phaseId = 3
      } else if (v === 'major-fail') {
        const fails = ++gateFails[2]
        if (fails <= VALIDATION_RETRY_CAP) {
          log(`Phase 2 verdict: major-fail (consecutive fail ${fails}) → Phase 1 retry`)
          phaseId = 1
        } else {
          stopReason = `Phase 2 failed validation ${fails} times consecutively`
          break run
        }
      } else {
        stopReason = `Phase 2 did not return a valid verdict (got ${JSON.stringify(v)})`
        break run
      }
      break
    }

    case 4: {
      const v = out && out.verdict
      gateVerdicts[4].push(v || '(none)')
      if (v === 'pass') {
        gateFails[4] = 0
        log('Phase 4 verdict: pass → Phase 5')
        phaseId = 5
      } else if (v === 'minor-fail' || v === 'major-fail') {
        const fails = ++gateFails[4]
        if (fails <= VALIDATION_RETRY_CAP) {
          if (v === 'minor-fail') {
            skip4 = true
            log(`Phase 4 verdict: minor-fail (consecutive fail ${fails}) → Phase 3 retry, then skip Phase 4 (fix too small to re-validate)`)
          } else {
            log(`Phase 4 verdict: major-fail (consecutive fail ${fails}) → Phase 3 retry, then Phase 4 re-runs`)
          }
          phaseId = 3
        } else {
          stopReason = `Phase 4 failed validation ${fails} times consecutively`
          break run
        }
      } else {
        stopReason = `Phase 4 did not return a valid verdict (got ${JSON.stringify(v)})`
        break run
      }
      break
    }

    case 6: {
      const v = out && out.verdict
      gateVerdicts[6].push(v || '(none)')
      if (v === 'pass') {
        gateFails[6] = 0
        log('Phase 6 verdict: pass → Phase 7')
        phaseId = 7
      } else if (v === 'minor-fail' || v === 'major-fail') {
        const fails = ++gateFails[6]
        if (fails <= VALIDATION_RETRY_CAP) {
          if (v === 'minor-fail') {
            log(`Phase 6 verdict: minor-fail (consecutive fail ${fails}) → Phase 7 directly (its pre-loop absorbs TEST_VALIDATION fixes)`)
            phaseId = 7
          } else {
            log(`Phase 6 verdict: major-fail (consecutive fail ${fails}) → Phase 5 retry, then Phase 6 re-runs`)
            phaseId = 5
          }
        } else {
          stopReason = `Phase 6 failed validation ${fails} times consecutively`
          break run
        }
      } else {
        stopReason = `Phase 6 did not return a valid verdict (got ${JSON.stringify(v)})`
        break run
      }
      break
    }

    // Phase 7 (finish): single session; its verdict decides the overall result.
    case 7: {
      finishOut = out || null
      const v = out && out.verdict
      if (v === 'pass') {
        overall = 'PASS — test suite green'
        log('Phase 7 verdict: pass — test suite green')
      } else {
        stopReason = 'Phase 7 (finish) did not get the test suite passing'
        log(`Phase 7 verdict: ${v || '(none)'} — tests not green`)
      }
      break run
    }
  }
}

if (!overall) {
  overall = `INCOMPLETE — ${stopReason || 'run ended before Phase 7'}`
}

const result = {
  challenge: CH,
  phases: {
    spec: { attempts: attempts[0] },
    plan: { attempts: attempts[1] },
    planValidation: { attempts: attempts[2], verdicts: gateVerdicts[2] },
    implement: { attempts: attempts[3] },
    implValidation: { attempts: attempts[4], verdicts: gateVerdicts[4] },
    tests: { attempts: attempts[5] },
    testValidation: { attempts: attempts[6], verdicts: gateVerdicts[6] },
    finish: {
      attempts: attempts[7],
      verdict: finishOut ? finishOut.verdict : null,
      summary: finishOut ? finishOut.summary : null,
    },
  },
  overall,
}

log(`DONE [${CH}]: ${overall}`)
return result
