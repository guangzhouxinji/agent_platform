import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync, unlinkSync, symlinkSync } from 'node:fs';
import { dirname, join, resolve, relative, isAbsolute } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { evaluateConsultationSuite } from './lib/consultation-task-quality.mjs';

// Engineering mutations are never additional business samples or acceptance evidence.
const workspaceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = 'governance/evaluations/consultation-task-quality-v1.json';
const ids = ['CON-QUALITY-ROTOR-01', 'CON-QUALITY-EMPTY-01'];
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const decode = bytes => JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
const zeroSha = '0'.repeat(64);
const wrongId = '00000000-0000-0000-0000-000000000000';

function assertBoundary(report) {
  assert.equal(report.denominator, 2, 'failed/missing inputs must remain in the denominator');
  assert.deepEqual(report.cases.map(c => c.id).sort(), [...ids].sort());
  assert.equal(report.cases.length, 2, 'mutations must not create or remove business samples');
  assert.equal(report.professionalAccepted, false);
  assert.equal(report.overallAcceptance, 'INCOMPLETE');
  for (const c of report.cases) {
    assert.equal(typeof c.passed, 'boolean');
    assert.ok(Array.isArray(c.checks) && c.checks.length > 0);
    assert.equal(new Set(c.checks.map(check => check.id)).size, c.checks.length);
    for (const check of c.checks) {
      assert.equal(typeof check.passed, 'boolean');
      assert.equal(typeof check.reason, 'string');
      if (!check.passed) assert.ok(check.reason.trim(), 'failure needs a nonempty reason');
    }
    if (c.checks.some(check => !check.passed)) assert.equal(c.passed, false, 'passing checks cannot mask a failure');
  }
}

function fixture(change, inspect = () => {}) {
  const root = mkdtempSync(join(tmpdir(), 'consultation-task-quality-test-'));
  try {
    const manifest = decode(readFileSync(join(workspaceRoot, manifestPath)));
    const documents = manifest.cases.map(() => ({}));
    for (const [index, c] of manifest.cases.entries()) {
      for (const [role, artifact] of Object.entries(c.artifacts)) {
        const bytes = readFileSync(join(workspaceRoot, artifact.path));
        assert.equal(digest(bytes), artifact.sha256, `real input changed: ${c.id}/${role}`);
        documents[index][role] = decode(bytes);
        artifact.path = `docs/evidence/test-fixtures/${index}-${role}.json`;
        const path = join(root, artifact.path);
        mkdirSync(dirname(path), { recursive: true });
        writeFileSync(path, bytes); // Do not lose ObjectMapper's 0.0 literals on positive inputs.
      }
    }
    const f = {
      root, manifest, documents,
      edit(role, mutate, index = 0) {
        const value = structuredClone(documents[index][role]);
        mutate(value);
        const bytes = Buffer.from(JSON.stringify(value));
        const artifact = manifest.cases[index].artifacts[role];
        writeFileSync(join(root, artifact.path), bytes);
        artifact.sha256 = digest(bytes); // Re-pin file integrity, NOT the historical Observation.
        documents[index][role] = value;
      },
      raw(role, bytes, index = 0) {
        const artifact = manifest.cases[index].artifacts[role];
        writeFileSync(join(root, artifact.path), bytes);
        artifact.sha256 = digest(bytes);
      },
    };
    change(f);
    const path = join(root, manifestPath);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(manifest));
    const report = evaluateConsultationSuite(root, manifestPath);
    assert.ok(!(report instanceof Promise), 'module contract is synchronous');
    assertBoundary(report);
    inspect(report, f);
    return report;
  } finally {
    const inside = relative(resolve(tmpdir()), resolve(root));
    assert.ok(inside && !inside.startsWith('..') && !isAbsolute(inside));
    assert.ok(inside.startsWith('consultation-task-quality-test-'));
    rmSync(root, { recursive: true, force: true }); // Native fs, only this owned mkdtemp root.
  }
}

function assertFailure(report, checkId, index = 0, unaffected = true) {
  assert.equal(report.mechanicalPassed, false);
  const c = report.cases.find(item => item.id === ids[index]);
  assert.equal(c.passed, false);
  assert.ok(c.checks.some(check => !check.passed && (!checkId || check.id === checkId)),
    `expected ${checkId ?? 'a failed check'}; checks: ${JSON.stringify(c.checks)}`);
  if (unaffected) assert.equal(report.cases.find(item => item.id === ids[1 - index]).passed, true,
    'a single-case mutation must neither erase nor corrupt the other task');
}

function assertPositive(report) {
  assert.equal(report.mechanicalPassed, true);
  assert.ok(report.cases.every(c => c.passed && c.checks.every(check => check.passed)));
  for (const name of ['INSUFFICIENT_MATERIAL', 'SOURCE_WITHDRAWAL', 'EXPIRY_DENIAL', 'INFLIGHT_UNKNOWN']) {
    assert.ok(JSON.stringify(report.unobservedCoverage).includes(name), `${name} must not be invented as observed`);
  }
  for (const c of report.cases) {
    for (const dimension of ['completion', 'evidenceSupport', 'resultAccuracy', 'toolUse',
      'replanAbility', 'humanIntervention', 'errorRecovery', 'outputQuality']) {
      assert.ok(Object.hasOwn(c.metrics, dimension), `missing metric ${dimension}`);
    }
    assert.ok(JSON.stringify(c.metrics).includes('NOT_ASSESSED'));
    assert.ok(JSON.stringify(c.metrics).includes('NOT_OBSERVED'));
  }
}

test('two real retained tasks pass mechanical checks, never professional acceptance', () => {
  const report = evaluateConsultationSuite(workspaceRoot, manifestPath);
  assertBoundary(report);
  assertPositive(report);
  assert.deepEqual(report.cases.map(c => c.counts.allQueryTermHits), [1, 0]);
  for (const c of report.cases) assert.equal(c.currentAuthorization, 'NOT_EVALUATED');
  const manifest = decode(readFileSync(join(workspaceRoot, manifestPath)));
  const rotor = decode(readFileSync(join(workspaceRoot, manifest.cases[0].artifacts.workspace.path)));
  const empty = decode(readFileSync(join(workspaceRoot, manifest.cases[1].artifacts.workspace.path)));
  assert.equal(rotor.result.members.length, 20);
  assert.equal(empty.result.members.length, 0);
  assert.equal(empty.result.candidateCount, 40);
  assert.equal(empty.result.status, 'SUCCEEDED');
  assert.equal(empty.result.confidenceStatus, 'NO_RESULT');
  const inputs = new Map(report.inputFiles.map(input => [input.path, input.sha256]));
  for (const artifact of manifest.cases.flatMap(c => Object.values(c.artifacts))) {
    assert.equal(inputs.get(artifact.path), artifact.sha256);
  }
  assert.equal(inputs.get(manifestPath), digest(readFileSync(join(workspaceRoot, manifestPath))));
});

test('byte-preserving isolated positive fixtures have the same fixed denominator', () => {
  fixture(() => {}, assertPositive);
});

const mutations = [
  ['wrong checkpoint Task', 'run', j => { j.checkpoint.task.id = wrongId; }, 'identityBinding'],
  ['wrong workspace Input', 'workspace', j => { j.goal.inputId = wrongId; }, 'identityBinding'],
  ['wrong checkpoint Run', 'run', j => { j.checkpoint.runId = wrongId; }, 'identityBinding'],
  ['wrong Goal', 'workspace', j => { j.goal.goalId = wrongId; }, 'identityBinding'],
  ['wrong page result SHA', 'workspace', j => { j.result.resultSha = zeroSha; }, 'resultBinding'],
  ['wrong answer result SHA', 'workspace', j => { j.dialogue[0].resultSha = zeroSha; }, 'resultBinding'],
  ['wrong result set', 'workspace', j => { j.dialogue[0].resultSetId = wrongId; }, 'resultBinding'],
  ['duplicate actual member', 'workspace', j => { j.result.members[1] = structuredClone(j.result.members[0]); }, 'citationBinding'],
  ['duplicate result Citation', 'workspace', j => { j.result.citations.push(structuredClone(j.result.citations[0])); }, 'citationBinding'],
  ['missing member Citation', 'workspace', j => { j.result.citations.pop(); }, 'citationBinding'],
  ['forged Citation original SHA', 'workspace', j => { j.result.citations[0].rawSha = zeroSha; }, 'citationBinding'],
  ['forged Citation source', 'workspace', j => { j.result.citations[0].sourceId = 'FORGED_SOURCE'; }, 'citationBinding'],
  ['forged Citation source version', 'workspace', j => { j.result.citations[0].sourceVersion = 'forged'; }, 'citationBinding'],
  ['forged Citation document version', 'workspace', j => { j.result.citations[0].documentVersion = 'forged'; }, 'citationBinding'],
  ['forged Citation locator', 'workspace', j => { j.result.citations[0].locatorRef = 'forged'; }, 'citationBinding'],
  ['duplicate answer Citation', 'workspace', j => { j.dialogue[0].citations.push(structuredClone(j.dialogue[0].citations[0])); }, 'citationBinding'],
  ['answer cites a nonmember', 'workspace', j => { j.dialogue[0].citations[0].memberId = wrongId; }, 'citationBinding'],
  ['answer output cites a nonmember', 'workspace', j => { j.dialogue[0].output.citations[0] = wrongId; }, 'citationBinding'],
  ['duplicate output Citation', 'workspace', j => { j.dialogue[0].output.citations.push(j.dialogue[0].output.citations[0]); }, 'citationBinding'],
  ['forged Observation SHA', 'run', j => { j.checkpoint.observations[0].sha256 = zeroSha; }, 'observationHashes'],
  ['duplicate Observation', 'run', j => { j.checkpoint.observations.push(structuredClone(j.checkpoint.observations[0])); }, 'observationHashes'],
  ['same Observation ref with another SHA', 'run', j => { j.checkpoint.observations.push({ ...j.checkpoint.observations[0], sha256: zeroSha }); }, 'observationHashes'],
  ['illegal planned tool', 'run', j => { j.checkpoint.plan.steps[0].tool = 'write-official-decision'; }, 'toolScope'],
  ['illegal defined tool', 'run', j => { j.checkpoint.definition.skillTools.PatentSearch.push('write-permission'); }, 'toolScope'],
  ['tool Input from another Goal', 'run', j => { j.checkpoint.plan.steps[0].inputRef = `confirmed-input:${wrongId}`; }, 'toolScope'],
  ['wrong completion contract', 'run', j => { j.checkpoint.task.goal.completionContract = 'FORGED/v1'; }, 'completionContract'],
  ['wrong stop reason', 'run', j => { j.checkpoint.outcome.reason = 'NEED_HUMAN'; }, 'completionContract'],
  ['wrong candidate denominator', 'workspace', j => { j.result.candidateCount = 0; }, 'resultCoverage'],
  ['wrong historical admission task', 'admission', j => { j.taskId = wrongId; }, 'historicalAdmission'],
  ['wrong run denial path', 'denialTrace', j => { j[0].path = j[0].path.replace('/agent-runs/', '/invented-runs/'); }, 'permissionLoss'],
  ['wrong workspace denial Task', 'denialTrace', j => { j[1].path = j[1].path.replace(/\/tasks\/[^/]+/, `/tasks/${wrongId}`); }, 'permissionLoss'],
  ['successful request masquerades as denial', 'denialTrace', j => { j[0].status = 200; }, 'permissionLoss'],
  ['invalid denial observation time', 'denialTrace', j => { j[0].observedAt = 'not-a-time'; }, 'permissionLoss'],
  ['wrong denial method', 'denialTrace', j => { j[0].method = 'POST'; }, 'permissionLoss'],
];
for (const [name, role, mutate, check] of mutations) {
  test(`re-pinned mutation: ${name}`, () => assertFailure(fixture(f => f.edit(role, mutate)), check));
}

for (const [table, field] of [
  ['task', 'VERSION'], ['runs', 'HEAD_VERSION'], ['revisions', 'PAYLOAD_SHA'],
  ['attempts', 'INPUT_REVISION'], ['modelInvocationFences', 'REQUEST_SHA'],
  ['modelTurns', 'RESULT_SHA'], ['modelPayloadSummaries', 'sha256'],
]) {
  test(`terminal recovery detects changed ${table}/${field}`, () => {
    assertFailure(fixture(f => f.edit('recoveryAfter', j => { j[table][0][field] = 'forged'; })), 'recoveryLedger');
  });
}
for (const table of ['runs', 'attempts', 'modelInvocationFences', 'modelTurns']) {
  test(`terminal recovery rejects an extra ${table} row`, () => {
    assertFailure(fixture(f => f.edit('recoveryAfter', j => { j[table].push(structuredClone(j[table][0])); })), 'recoveryLedger');
  });
}
test('equal but foreign before/after recovery ledgers are not this task evidence', () => {
  assertFailure(fixture(f => {
    for (const role of ['recoveryBefore', 'recoveryAfter']) f.edit(role, j => { j.runs[0].RUN_ID = wrongId; });
  }), 'recoveryLedger');
});
test('equal but discontinuous recovery revisions are rejected', () => {
  assertFailure(fixture(f => {
    for (const role of ['recoveryBefore', 'recoveryAfter']) f.edit(role, j => { j.revisions[1].REVISION = '0'; });
  }), 'recoveryLedger');
});

for (const role of ['run', 'workspace', 'approval', 'admission', 'recoveryBefore', 'recoveryAfter', 'denialTrace', 'sourceUse']) {
  test(`missing ${role} retains the failed task and denominator`, () => {
    assertFailure(fixture(f => unlinkSync(join(f.root, f.manifest.cases[0].artifacts[role].path))), 'artifactIntegrity');
  });
}
test('wrong pinned SHA cannot be overridden by other passing checks', () => {
  assertFailure(fixture(f => { f.manifest.cases[0].artifacts.run.sha256 = zeroSha; }), 'artifactIntegrity');
});
test('invalid JSON with a correct pin fails closed without throwing', () => {
  assertFailure(fixture(f => f.raw('run', Buffer.from('{broken'))), 'artifactIntegrity');
});
for (const path of ['../outside.json', 'docs/evidence/../../../outside.json', 'docs/evidence/../outside.json',
  'docs/evidence/..\\..\\outside.json', 'requirements/example.json']) {
  test(`input path boundary rejects ${path}`, () => {
    assertFailure(fixture(f => { f.manifest.cases[0].artifacts.run.path = path; }), 'artifactIntegrity');
  });
}
test('absolute input paths are rejected even within the temporary root', () => {
  assertFailure(fixture(f => { f.manifest.cases[0].artifacts.run.path = join(f.root, f.manifest.cases[0].artifacts.run.path); }), 'artifactIntegrity');
});
test('a directory junction inside evidence cannot redirect reads', () => {
  assertFailure(fixture(f => {
    const target = join(f.root, 'docs/evidence/test-fixtures');
    const link = join(f.root, 'docs/evidence/link');
    symlinkSync(target, link, process.platform === 'win32' ? 'junction' : 'dir');
    f.manifest.cases[0].artifacts.run.path = 'docs/evidence/link/0-run.json';
  }), 'artifactIntegrity');
});

const invalidContracts = [
  ['reduced denominator', m => { m.denominator = 1; }],
  ['removed case', m => { m.cases.pop(); }],
  ['removed case ID', m => { m.caseIds.pop(); }],
  ['duplicate sample ID', m => { m.cases[1].id = m.cases[0].id; }],
  ['duplicate task', m => { m.cases[1].expected.taskId = m.cases[0].expected.taskId; }],
  ['duplicate run', m => { m.cases[1].expected.runId = m.cases[0].expected.runId; }],
  ['unknown schema', m => { m.schema = 'CONSULTATION_TASK_QUALITY/v999'; }],
  ['unknown version', m => { m.version = '999'; }],
  ['changed scope', m => { m.scope = 'LIVE_PRODUCT'; }],
  ['missing required coverage', m => { m.requiredCoverage.pop(); }],
  ['extra overall-success field', m => { m.professionalAccepted = true; }],
];
for (const [name, mutate] of invalidContracts) {
  test(`contract failure: ${name} yields two failed placeholders`, () => {
    const report = fixture(f => mutate(f.manifest));
    assert.equal(report.mechanicalPassed, false);
    assert.ok(report.cases.every(c => !c.passed && c.checks.some(check => !check.passed)));
  });
}
test('missing and malformed manifests both retain two failed placeholders', () => {
  fixture(() => {}, (_report, f) => {
    const path = join(f.root, manifestPath);
    for (const malformed of [null, '{invalid']) {
      if (malformed === null) unlinkSync(path); else writeFileSync(path, malformed);
      const report = evaluateConsultationSuite(f.root, manifestPath);
      assertBoundary(report);
      assert.equal(report.mechanicalPassed, false);
      assert.ok(report.cases.every(c => !c.passed));
    }
  });
});
test('empty-result task cannot silently replace candidate mother-set 40 with zero', () => {
  assertFailure(fixture(f => f.edit('workspace', j => { j.result.candidateCount = 0; }, 1)), 'resultCoverage', 1);
});
test('self-reported professional approval never promotes the output', () => {
  fixture(f => f.edit('workspace', j => {
    j.professionalAccepted = true;
    j.overallAcceptance = 'COMPLETE';
    j.dialogue[0].professionalAccuracyAccepted = true;
  }), report => {
    assert.equal(report.professionalAccepted, false);
    assert.equal(report.overallAcceptance, 'INCOMPLETE');
  });
});

for (let index = 0; index < 4; index++) {
  test(`each Observation SHA is independently checked: index ${index}`, () => {
    assertFailure(fixture(f => f.edit('run', j => { j.checkpoint.observations[index].sha256 = zeroSha; })), 'observationHashes');
  });
}
test('equal JS numeric values do not replace ObjectMapper original Observation bytes', () => {
  assertFailure(fixture(f => {
    const artifact = f.manifest.cases[0].artifacts.workspace;
    const original = readFileSync(join(f.root, artifact.path), 'utf8');
    const changed = original.replace(/("candidateCount"\s*:\s*)40(?=[,\s}])/, '$140.0');
    assert.ok(changed !== original, 'candidateCount original numeric bytes must change');
    assert.deepEqual(decode(Buffer.from(changed)), decode(Buffer.from(original)));
    f.raw('workspace', Buffer.from(changed));
  }), 'observationHashes');
});
test('an empty result never permits fabricated answer output citations', () => {
  assertFailure(fixture(f => f.edit('workspace', j => { j.dialogue[0].output.citations.push(wrongId); }, 1)), 'citationBinding', 1);
});


const toolMutations = [
  ['allowed tools in the wrong order', j => { [j.checkpoint.plan.steps[0], j.checkpoint.plan.steps[1]] = [j.checkpoint.plan.steps[1], j.checkpoint.plan.steps[0]]; }],
  ['duplicate allowed search replaces source-read', j => { Object.assign(j.checkpoint.plan.steps[1], { skill: 'PatentSearch', tool: 'search' }); }],
  ['duplicate allowed answer-draft replaces search', j => { Object.assign(j.checkpoint.plan.steps[0], { skill: 'AnswerDraft', tool: 'answer-draft' }); }],
  ['missing actual invocation', j => { j.checkpoint.invocations = 2; }],
  ['extra actual invocation', j => { j.checkpoint.invocations = 4; }],
  ['unfinished cursor', j => { j.checkpoint.cursor = 2; }],
  ['unobserved replanning claim', j => { j.checkpoint.replans = 1; }],
];
for (const [name, mutate] of toolMutations) {
  test(`fixed historical tool contract: ${name}`, () => {
    assertFailure(fixture(f => f.edit('run', mutate)), 'toolScope');
  });
}
for (const [name, mutate, expectedHits] of [
  ['lost actual all-term match', j => { j.result.members[0].matchedTerms.pop(); }, 0],
  ['invented second all-term match', j => { j.result.members[1].matchedTerms = ['soldering', 'blades', 'rotor']; }, 2],
  ['all-term hit in an unapproved field', j => { j.result.members[0].matchFields = ['CLAIMS']; }, 0],
]) {
  test(`result coverage recomputes ${name}`, () => {
    const report = fixture(f => f.edit('workspace', mutate));
    assertFailure(report, 'resultCoverage');
    assert.equal(report.cases[0].counts.allQueryTermHits, expectedHits, 'report the observed count, not the manifest expectation');
  });
}

