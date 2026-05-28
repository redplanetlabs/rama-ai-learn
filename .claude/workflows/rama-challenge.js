export const meta = {
  name: 'rama-challenge',
  description: 'Phased Rama module build for any challenge (phases 0-7, verdict-driven loops, adversarial validation panels)',
  whenToUse: 'Implement a Rama challenge end-to-end following the rama skill phased process. User says "run rama-challenge for <name>" or "implement the <name> challenge".',
  phases: [
    { title: 'Phase 0: Implicit Spec', detail: 'derive IMPLICIT_SPEC.md from README + protocol' },
    { title: 'Phase 1: Plan', detail: 'design PStates/depots/topologies into PLAN.md' },
    { title: 'Phase 2: Plan Validation', detail: '2-reviewer adversarial panel; fail loops back to Phase 1' },
    { title: 'Phase 3: Implement', detail: 'write module.clj; compile + lint clean' },
    { title: 'Phase 4: Impl Validation', detail: '2-reviewer adversarial code panel; minor/major-fail loops back to Phase 3' },
    { title: 'Phase 5: Tests', detail: 'write tests covering protocol + implicit spec' },
    { title: 'Phase 6: Test Validation', detail: '2-reviewer adversarial test panel; minor/major-fail loops back to Phase 5' },
    { title: 'Phase 7: Finish', detail: 'run clojure -X:test in a loop until 0 failures, 0 errors' },
  ],
}

// ---- resolve challenge name from the prompt ----
// The workflow runtime passes the user's prompt as `context.userPrompt`.
// Extract the challenge name from it, or fall back to env var.
function resolveChallengeName() {
  // Try env var first (set by Babashka runner)
  if (typeof process !== 'undefined' && process.env && process.env.CHALLENGE_NAME) {
    return process.env.CHALLENGE_NAME
  }
  // Fall back: caller must have set it. Abort if missing.
  throw new Error('CHALLENGE_NAME environment variable is required. Set it before running this workflow.')
}

const CH = resolveChallengeName()
const CH_UNDER = CH.replace(/-/g, '_')
const IMPL = `implementations/${CH}`
const SK = 'plugins/rama-skill/skills/rama'
const CHDIR = `challenges/${CH}`

const PLAN_MAX_ITERS = 3
const IMPL_MAX_ITERS = 3
const TEST_MAX_ITERS = 3
const FINISH_MAX_ATTEMPTS = 2

// ---- shared preamble ----
const PREAMBLE = `You are an autonomous agent executing ONE phase of a phased Rama module build for the "${CH}" challenge. Working directory is the project root.

ABSOLUTE RULES
- Do ONLY the phase described below. Do NOT run later phases. Do NOT run the test suite unless this is Phase 7. Do NOT try to "finish the whole thing."
- Do NOT read or open ANY file under ${CHDIR}/test-resources/ or ${CHDIR}/test-private/ (private/encrypted reference material). Do NOT read any other challenge directory under challenges/.
- impl-root = ${IMPL}    skill-root = ${SK}

PRE-FLIGHT (do first, every phase)
1. Read ${CHDIR}/README.md and all protocol/source files under ${CHDIR}/src/ — this is the contract. Identify all protocol methods (writes and reads), their argument types, and return types.
2. Read the Rama skill ${SK}/SKILL.md and obey its production-design rules: every task is single-threaded with atomic multi-PState access; use cooperative multitasking (:allow-yield?, yield-if-overtime) on large reads/loops; worker restart does NOT replay depot history; never trade fault-tolerance or I/O efficiency for code simplicity; never delete data the spec does not say to delete; minimize RocksDB seeks and network roundtrips.

CONTRACT RULES
- The solution namespace MUST be ${CH}.module at ${IMPL}/src/${CH_UNDER}/module.clj.
- create-module must return {:module <RamaModule instance> :wrap-client (fn [ipc] -> <protocol impl>)}.
- The wrap-client result must reify BOTH the challenge protocol AND rama-challenges.harness/Synchronizable (implement wait-for-processing! per its docstring: track cumulative append counts and call com.rpl.rama.test/wait-for-microbatch-processed-count for each microbatch topology; no-op for full-ack streams).
- Read the protocol file to determine the exact interface methods, argument types, and return types.

PROJECT RULES
- rama-helpers is on the classpath but optional — use only if it genuinely helps.
- The test harness auto-detects stream vs microbatch and synchronizes both. Choose topology by DOMAIN requirements (fault tolerance, exactly-once, latency), never to satisfy test synchronization.
`

// ---- retry blocks ----
const RETRY_PLAN = `\nRETRY: ${IMPL}/PLAN_VALIDATION.md exists with FAIL items from an adversarial review. Read it in full. Revise PLAN.md to address EVERY FAIL item explicitly, and update the "Rejected alternatives" section to record what was tried and why it was rejected. Do not regress items that already passed.\n`
const RETRY_IMPL = `\nRETRY: ${IMPL}/IMPLEMENTATION_VALIDATION.md exists with FAIL items from an adversarial code review. Read it in full and fix EVERY FAIL item in the module source. Do not regress passing items. Re-verify compile + lint after editing.\n`
const RETRY_TEST = `\nRETRY: ${IMPL}/TEST_VALIDATION.md exists with FAIL items from an adversarial test review. Read it in full and fix EVERY FAIL item in the test sources. Do not regress passing coverage.\n`

// ---- phase prompt builders ----
function p0() {
  return PREAMBLE + `
THIS PHASE: Phase 0 — Derive Implicit Spec.
1. Import clj-kondo configs: run  bash scripts/import-kondo-configs.sh ${CH}  from the project root.
2. Read ${SK}/references/phase-0-implicit-spec.md and follow it exactly.
3. Ensure ${IMPL}/ exists (mkdir -p) and copy the template if absent:  cp ${SK}/references/artifact-implicit-spec.md ${IMPL}/IMPLICIT_SPEC.md
4. Fill in EVERY section of ${IMPL}/IMPLICIT_SPEC.md. For each protocol operation (both writes and reads): latency, throughput, consistency/correctness invariants, data growth/scale, concurrency behavior, edge cases. Then the full Entity State x Write matrix for every entity type, listing for every state x write combination what every related read returns and why. No empty sections.
Do NOT design PStates/depots/topologies here — requirements analysis only.
OUTPUT: ${IMPL}/IMPLICIT_SPEC.md fully filled in. End your reply with one line: ARTIFACT: ${IMPL}/IMPLICIT_SPEC.md  followed by a 1-sentence summary.`
}

function p1(retry) {
  return PREAMBLE + `
THIS PHASE: Phase 1 — Plan (design only; write NO topology/ETL/query code).
Read ${SK}/references/phase-1-plan.md and follow Steps 1-5, reading every sub-reference it cites (app-design.md, pstate-schema.md, pstate-schema-clojure-api.md, patterns.md, depot-design.md, query-topologies.md; plus unique-ids.md / scheduling.md / task-globals.md only if relevant).
Inputs: ${IMPL}/IMPLICIT_SPEC.md (read it), README, protocol.
Copy template if absent:  cp ${SK}/references/artifact-plan.md ${IMPL}/PLAN.md  — then fill in EVERY section.
${retry ? RETRY_PLAN : ''}
For each read operation in the protocol: how is it answered in one foreign-select or one query topology invocation? Justify I/O cost (seeks + iterations). For collections that can grow unbounded (>100 per entity), decide subindexing.
For each write operation: how is it processed? If it touches multiple partitions, how is cross-partition atomicity guaranteed? If it involves non-idempotent state changes, how is exactly-once guaranteed under retry/restart?
Topology choice with justification (default microbatch; state what, if anything, requires a stream). Note that exactly-once and cross-partition atomic writes are microbatch properties.
Depot design: partition by the key matching the primary PState key to colocate processing with writes and balance load across tasks.
OUTPUT: ${IMPL}/PLAN.md fully filled in (no skipped sections). End with: ARTIFACT: ${IMPL}/PLAN.md  + 1-sentence summary.`
}

function p3(retry) {
  return PREAMBLE + `
THIS PHASE: Phase 3 — Implement. This is the ONLY phase that writes module code. Adhere to ${IMPL}/PLAN.md exactly; do NOT silently redesign (if the plan is genuinely wrong, implement it as written and note the issue — the orchestrator handles re-planning).
Read ${SK}/references/phase-3-implement.md and follow Steps 1-5. Read the references it cites for your design: dataflow.md, paths.md, troubleshooting.md, and microbatch.md and/or stream.md, plus aggregators.md / batch.md / task-globals.md / query-topologies.md as the plan requires.
Write the full module to ${IMPL}/src/${CH_UNDER}/module.clj:
- ns with [com.rpl.rama :refer :all] and [com.rpl.rama.path :refer :all] (and ops/aggs as needed),
- defmodule with depot, PState and topology-handle declarations,
- ETL topology bodies (prefer +compound + aggregators over select-compute-transform; correct partition alignment before every local-select>/local-transform>; commit boundaries before any stream depot-partition-append!),
- any query topology bodies (last partitioner |origin; issue only the meaningful reads),
- the foreign-client wrapper: a create-module fn returning {:module ... :wrap-client ...} where wrap-client reifies the challenge protocol AND rama-challenges.harness/Synchronizable. Each client write op uses exactly ONE foreign-append!.
${retry ? RETRY_IMPL : ''}
Step 5 (MANDATORY before finishing):
- Compile: from ${CHDIR}/ run  clojure -M -e "(require '${CH}.module :reload)"  and resolve EVERY compile error.
- Lint: run clj-kondo on ${IMPL}/src/${CH_UNDER}/module.clj and resolve every error and warning, if clj-kondo is available.
OUTPUT: ${IMPL}/src/${CH_UNDER}/module.clj compiling cleanly. End with: ARTIFACT: ${IMPL}/src/${CH_UNDER}/module.clj  + a 1-sentence summary of the PState/topology design used.`
}

function p5(retry) {
  return PREAMBLE + `
THIS PHASE: Phase 5 — Write Tests. Do NOT run the test suite (that is Phase 7).
Read ${SK}/references/testing.md and ${SK}/references/phase-5-tests.md and follow them.
Write tests under ${IMPL}/test/ (e.g. namespace ${CH}.module-test at ${IMPL}/test/${CH_UNDER}/module_test.clj):
- Drive the module via create-module: launch (:module result) with com.rpl.rama.test/create-ipc + launch-module!, then exercise behavior through the protocol impl from (:wrap-client result) or directly via foreign-depot/foreign-pstate/foreign-query.
- Cover every protocol method with positive AND negative cases, and every edge case in ${IMPL}/IMPLICIT_SPEC.md.
- Assert real invariants from the domain (not just happy-path smoke tests).
- SYNCHRONIZE every write before the read that depends on it using com.rpl.rama.test/wait-for-microbatch-processed-count with your module name + topology name + cumulative count (counts are cumulative across the whole IPC). Do NOT import or reference rama-challenges.harness anywhere (not in requires, imports, or fully-qualified calls). If the module uses tick depots, use rama-challenges.shared/REPLACE-TICK-DEPOTS (read its docstring).
- Minimize IPC launches: one with-open block covering multiple disjoint-key testing sections.
${retry ? RETRY_TEST : ''}
Verify each test ns loads: from ${CHDIR}/ run  clojure -M -e "(require '${CH}.module-test :reload)"  and fix load errors.
OUTPUT: test sources under ${IMPL}/test/. End with: ARTIFACT: ${IMPL}/test/...  + 1-sentence summary.`
}

function p7(attempt) {
  return PREAMBLE + `
THIS PHASE: Phase 7 — Finish. Run the Phase 5 tests against the Phase 3 module and iterate until they pass.
${attempt > 1 ? '\nNOTE: a previous finish session ended without passing. Re-read the current module and test sources and continue from the current state.\n' : ''}
Pre-loop: if ${IMPL}/TEST_VALIDATION.md exists with FAIL items, read it and apply each fix to the test sources before the first run.
Loop:
1. Run the test suite: from ${CHDIR}/ run  clojure -X:test  (you may add JVM opts to silence the JNI/RocksDB load warnings; they are harmless noise). Capture stdout+stderr.
2. If output contains  0 failures, 0 errors  the suite passed — stop.
3. Otherwise read the failure carefully, find the ROOT cause (load the rama skill + references/troubleshooting.md + the relevant subsystem reference as needed), edit the MODULE source (preferred) or a test, and re-run. Prefer module fixes; only change a test if it is clearly wrong (asserts behavior the protocol does not require, or skips synchronization). Do NOT delete failing tests. Do NOT add new test namespaces.
Budget: up to ~12 test-suite runs. When the budget is exhausted, stop with whatever state you have.
Read ${SK}/references/phase-7-finish.md for the full procedure.
Return structured output: verdict pass only if the final run showed 0 failures, 0 errors; otherwise fail with a summary of what is still failing and what you tried, plus the last ~30 lines of the final test output.`
}

// ---- validation panel config ----
const VAL_CFG = {
  plan: {
    title: 'Phase 2: Plan Validation',
    phaseDoc: `${SK}/references/phase-2-plan-validate.md`,
    template: `${SK}/references/artifact-plan-validation.md`,
    artifact: `${IMPL}/PLAN_VALIDATION.md`,
    kind: 'binary',
    target: `${IMPL}/PLAN.md`,
    targetDesc: 'the design plan',
    lenses: [
      { key: 'fault-tolerance', focus: 'FAULT TOLERANCE & CORRECTNESS. For each write operation in the protocol, trace concretely: worker/process restart mid-topology; a topology retry re-applying the write; cross-partition writes where entities are on different tasks and processing fails partway; two concurrent clients writing the same entity; event ordering across partitioner hops. For EACH scenario, state whether every read operation still returns correct, exactly-once results, and whether state can be corrupted or double-applied.' },
      { key: 'coverage-io', focus: 'SPEC COVERAGE & I/O EFFICIENCY. Enumerate EVERY protocol operation and EVERY IMPLICIT_SPEC constraint/edge case and trace each against the plan. Check: any query topology issues only meaningful (non-empty) reads and handles a variable read-count dynamically; PState schemas use no Object type, use fixed-keys-schema vs definterface correctly, and subindex any per-entity collection that can exceed ~100 entries; exactly one foreign-append! per client write; depot/PState partitioning colocates the core reads and balances storage/compute across tasks.' },
    ],
  },
  impl: {
    title: 'Phase 4: Impl Validation',
    phaseDoc: `${SK}/references/phase-4-impl-validate.md`,
    template: `${SK}/references/artifact-impl-validation.md`,
    artifact: `${IMPL}/IMPLEMENTATION_VALIDATION.md`,
    kind: 'tri',
    target: `${IMPL}/src/${CH_UNDER}/module.clj`,
    targetDesc: 'the module source',
    lenses: [
      { key: 'dataflow', agentType: 'rama-dataflow-critic', focus: 'DATAFLOW CORRECTNESS & IDIOM. Verify partition alignment before every local-select>/local-transform>; stream idempotency and retry-duplicate risk; a (|direct (ops/current-task-id)) commit boundary before any depot-partition-append! in a stream topology; +compound/aggregators instead of select-compute-transform; no redundant <<if/conditional branches or consecutive keypaths; no unnecessary nil->val; :allow-yield? on subindexed iterations that can exceed ~100; no hand-rolled reimplementation of com.rpl.rama.ops built-ins.' },
      { key: 'conformance', focus: 'PLAN & SPEC CONFORMANCE + FAULT TOLERANCE. Does the code match PLAN.md and satisfy the protocol/spec exactly? Check: every non-subindexed inner collection has an application-enforced size cap, else it must be subindexed; exactly one foreign-append! per client op (extra depot writes server-side via depot-partition-append!); cross-partition partial-failure safety; every write operation exactly-once under retry and worker restart; any TaskGlobal has a named durable rebuild path; wrap-client reifies BOTH the challenge protocol and Synchronizable and wait-for-processing! actually waits for each microbatch topology.' },
    ],
  },
  test: {
    title: 'Phase 6: Test Validation',
    phaseDoc: `${SK}/references/phase-6-test-validate.md`,
    template: `${SK}/references/artifact-test-validation.md`,
    artifact: `${IMPL}/TEST_VALIDATION.md`,
    kind: 'tri',
    target: `${IMPL}/test/`,
    targetDesc: 'the test sources',
    lenses: [
      { key: 'coverage', focus: 'IMPLICIT-SPEC COVERAGE. Read IMPLICIT_SPEC.md and verify every edge case and every entity-state x write combination is exercised by a cited deftest/testing block. Any uncovered case is a FAIL. Pay special attention to: default/zero/empty return values, error/failure cases, boundary conditions, and interactions between operations.' },
      { key: 'rigor', focus: 'TEST RIGOR & STRUCTURE. Check: every write is synchronized before the dependent read via com.rpl.rama.test (NO rama-challenges.harness import anywhere); IPC launches minimized (one with-open over disjoint-key scenarios; each extra deftest justified by shared mutable state); test namespaces load cleanly (imports, record-constructor :refer entries, no private-ns dependency); tests assert real domain invariants rather than only the happy path.' },
    ],
  },
}

function reviewerPrompt(cfg, lens) {
  const kindLine = cfg.kind === 'binary'
    ? 'Your verdict is pass or fail. DEFAULT is fail; pass ONLY after explicit scenario-tracing shows zero violations.'
    : 'Your verdict is pass, minor-fail, or major-fail. DEFAULT is major-fail; pass ONLY after explicit code/coverage tracing shows zero violations. Mark each failure minor (fixable by editing specific existing lines) or major (needs restructuring); when unsure, major.'
  return PREAMBLE + `
THIS PHASE: ${cfg.title}. You are ONE member of a 2-person adversarial review panel reviewing ${cfg.targetDesc}. You were written by no one with a stake in shipping; your job is to find every way it fails the spec.
${kindLine}
Read ${cfg.phaseDoc} and the checklist template ${cfg.template} (these define the categories of checks). Read ${IMPL}/IMPLICIT_SPEC.md and ${cfg.target} in full. Re-read README + protocol and quote constraints verbatim when checking.
YOUR LENS: ${lens.focus}
Method: for each check in your lens, state the constraint verbatim, trace a concrete scenario (real entity IDs, values, partition/task counts, retry & worker-restart timing), cite the exact line/section it applies to, and state whether it actually holds. Apply the self-consistency rule: if you wrote that something is "a gap" / "not ideal" / "a tradeoff", that check is a FAIL.
Do NOT write or edit ANY file — you are a panel member; the orchestrator merges all panelists and writes the consolidated artifact. Return ONLY structured output: your verdict, a short summary, and every failure you found (empty failures array if you pass).`
}

function scribePrompt(cfg, verdict, reviewsJson) {
  return `You are writing a single validation artifact file for the "${CH}" Rama build. Working directory is the project root. Do NOT modify any file except the one artifact named below.
Read the template ${cfg.template} to match its exact section structure and check headings.
Write the consolidated artifact to ${cfg.artifact}. An adversarial review panel of 2 independent reviewers (distinct lenses) produced the findings below; the orchestrator computed the overall verdict: ${verdict}.

PANEL OUTPUT (JSON array; each element = one reviewer with verdict, summary, failures):
${reviewsJson}

Instructions:
- Reproduce every section/check from the template.
- For each FAIL finding from any reviewer, write a clearly-marked FAIL entry under the matching check, including: the check name, the verbatim quote, the location/reference, why it fails, and the exact change required for the next retry. Merge duplicate findings from both reviewers into one entry (note when both flagged it).
- For checks with no findings, mark PASS with a one-line evidence note drawn from the reviewers' summaries.
- The file's overall verdict MUST be: ${verdict}.
- End the file with exactly this line: PHASE_VALIDATION:${verdict}
End your reply with: ARTIFACT: ${cfg.artifact}`
}

// ---- schemas ----
const FAIL_PLAN = {
  type: 'object', additionalProperties: false,
  required: ['check', 'spec_quote', 'plan_reference', 'why', 'required_fix'],
  properties: {
    check: { type: 'string' },
    spec_quote: { type: 'string' },
    plan_reference: { type: 'string' },
    why: { type: 'string' },
    required_fix: { type: 'string' },
  },
}
const PLAN_REVIEW_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['verdict', 'summary', 'failures'],
  properties: {
    verdict: { type: 'string', enum: ['pass', 'fail'] },
    summary: { type: 'string' },
    failures: { type: 'array', items: FAIL_PLAN },
  },
}
const FAIL_TRI = {
  type: 'object', additionalProperties: false,
  required: ['check', 'location', 'why', 'required_fix', 'severity'],
  properties: {
    check: { type: 'string' },
    location: { type: 'string' },
    why: { type: 'string' },
    required_fix: { type: 'string' },
    severity: { type: 'string', enum: ['minor', 'major'] },
  },
}
const TRI_REVIEW_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['verdict', 'summary', 'failures'],
  properties: {
    verdict: { type: 'string', enum: ['pass', 'minor-fail', 'major-fail'] },
    summary: { type: 'string' },
    failures: { type: 'array', items: FAIL_TRI },
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

// ---- verdict merge ----
function mergeVerdict(cfg, reviews) {
  const valid = reviews.filter(Boolean)
  if (cfg.kind === 'binary') {
    if (!valid.length) return 'fail'
    return valid.some(r => r.verdict === 'fail') ? 'fail' : 'pass'
  }
  if (!valid.length) return 'major-fail'
  const rank = { pass: 0, 'minor-fail': 1, 'major-fail': 2 }
  let max = 0
  for (const r of valid) max = Math.max(max, rank[r.verdict] == null ? 2 : rank[r.verdict])
  return ['pass', 'minor-fail', 'major-fail'][max]
}

function reviewsJsonFor(reviews) {
  return JSON.stringify(
    reviews.map((r, i) => ({
      reviewer: i + 1,
      verdict: r ? r.verdict : '(errored)',
      summary: r ? r.summary : 'reviewer agent errored or was skipped',
      failures: r ? r.failures : [],
    })),
    null, 2,
  )
}

// Run one validation phase: panel -> merge -> scribe. Returns the merged verdict.
async function validate(cfg) {
  phase(cfg.title)
  const schema = cfg.kind === 'binary' ? PLAN_REVIEW_SCHEMA : TRI_REVIEW_SCHEMA
  const reviews = await parallel(cfg.lenses.map(lens => () => {
    const opts = { schema, phase: cfg.title, label: cfg.title.split(':')[0].toLowerCase().replace(/\s+/g, '') + ':' + lens.key }
    if (lens.agentType) opts.agentType = lens.agentType
    return agent(reviewerPrompt(cfg, lens), opts)
  }))
  const verdict = mergeVerdict(cfg, reviews)
  const nFail = reviews.filter(Boolean).reduce((a, r) => a + (r.failures ? r.failures.length : 0), 0)
  log(`${cfg.title}: verdict=${verdict} (${nFail} finding(s) across panel)`)
  await agent(scribePrompt(cfg, verdict, reviewsJsonFor(reviews)), { phase: cfg.title, label: cfg.title.split(':')[0].toLowerCase().replace(/\s+/g, '') + ':scribe' })
  return verdict
}

// ===================== MAIN FLOW =====================
const result = { challenge: CH, phases: {} }

// --- Phase 0 ---
phase('Phase 0: Implicit Spec')
await agent(p0(), { phase: 'Phase 0: Implicit Spec', label: 'phase0:implicit-spec' })

// --- Phase 1 + 2 loop ---
phase('Phase 1: Plan')
await agent(p1(false), { phase: 'Phase 1: Plan', label: 'phase1:plan' })
let planVerdict = 'fail'
let planIter = 0
while (planIter < PLAN_MAX_ITERS) {
  planVerdict = await validate(VAL_CFG.plan)
  if (planVerdict === 'pass') break
  planIter++
  if (planIter >= PLAN_MAX_ITERS) {
    log(`Phase 2 still FAIL after ${PLAN_MAX_ITERS} plan iterations — proceeding to implementation with the best plan so far.`)
    break
  }
  phase('Phase 1: Plan')
  await agent(p1(true), { phase: 'Phase 1: Plan', label: 'phase1:plan-retry' + planIter })
}
result.phases.plan = { iterations: planIter + 1, finalVerdict: planVerdict }

// --- Phase 3 + 4 loop ---
phase('Phase 3: Implement')
await agent(p3(false), { phase: 'Phase 3: Implement', label: 'phase3:implement' })
let implVerdict = 'major-fail'
let implIter = 0
let proceedToTests = false
while (!proceedToTests) {
  implVerdict = await validate(VAL_CFG.impl)
  if (implVerdict === 'pass') { proceedToTests = true; break }
  implIter++
  if (implIter >= IMPL_MAX_ITERS) {
    log(`Phase 4 still ${implVerdict} after ${IMPL_MAX_ITERS} implement iterations — proceeding to tests; Phase 7 will exercise the module against real runs.`)
    break
  }
  phase('Phase 3: Implement')
  await agent(p3(true), { phase: 'Phase 3: Implement', label: 'phase3:implement-retry' + implIter })
  if (implVerdict === 'minor-fail') {
    log('Phase 4 minor-fail: fix applied in Phase 3; skipping re-validation per phase model.')
    proceedToTests = true
    break
  }
}
result.phases.impl = { iterations: implIter + 1, finalVerdict: implVerdict }

// --- Phase 5 + 6 loop ---
phase('Phase 5: Tests')
await agent(p5(false), { phase: 'Phase 5: Tests', label: 'phase5:tests' })
let testVerdict = 'major-fail'
let testIter = 0
let proceedToFinish = false
while (!proceedToFinish) {
  testVerdict = await validate(VAL_CFG.test)
  if (testVerdict === 'pass') { proceedToFinish = true; break }
  testIter++
  if (testIter >= TEST_MAX_ITERS) {
    log(`Phase 6 still ${testVerdict} after ${TEST_MAX_ITERS} test iterations — proceeding to Phase 7.`)
    break
  }
  phase('Phase 5: Tests')
  await agent(p5(true), { phase: 'Phase 5: Tests', label: 'phase5:tests-retry' + testIter })
  if (testVerdict === 'minor-fail') {
    log('Phase 6 minor-fail: fix applied in Phase 5; skipping re-validation; Phase 7 will also read TEST_VALIDATION.')
    proceedToFinish = true
    break
  }
}
result.phases.tests = { iterations: testIter + 1, finalVerdict: testVerdict }

// --- Phase 7 ---
phase('Phase 7: Finish')
let finishVerdict = 'fail'
let finishAttempts = 0
let finishOut = null
while (finishVerdict !== 'pass' && finishAttempts < FINISH_MAX_ATTEMPTS) {
  finishAttempts++
  finishOut = await agent(p7(finishAttempts), { schema: FINISH_SCHEMA, phase: 'Phase 7: Finish', label: 'phase7:finish#' + finishAttempts })
  finishVerdict = finishOut && finishOut.verdict === 'pass' ? 'pass' : 'fail'
  if (finishVerdict === 'pass') break
  log(`Phase 7 attempt ${finishAttempts}: ${finishOut ? 'tests not yet green' : 'agent errored'}.` + (finishAttempts < FINISH_MAX_ATTEMPTS ? ' Starting a fresh finish session.' : ' Budget exhausted.'))
}
result.phases.finish = { attempts: finishAttempts, finalVerdict: finishVerdict, summary: finishOut ? finishOut.summary : null }

result.overall = finishVerdict === 'pass' ? 'PASS — test suite green' : 'INCOMPLETE — see Phase 7 summary'
log(`DONE [${CH}]: ${result.overall}`)
return result
