import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync, unlinkSync, symlinkSync, existsSync } from 'node:fs';
import { dirname, join, resolve, relative, isAbsolute } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { buildConsultationReviewPacket, RETAINED_TITLE_ARTIFACTS } from './lib/consultation-professional-review.mjs';
import { evaluateConsultationSuite } from './lib/consultation-task-quality.mjs';

// These temporary mutations are engineering counterexamples, never new business samples.
const workspaceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = 'governance/evaluations/consultation-task-quality-v1.json';
const ids = ['CON-QUALITY-ROTOR-01', 'CON-QUALITY-EMPTY-01'];
const roles = ['run', 'workspace', 'approval', 'admission', 'recoveryBefore', 'recoveryAfter', 'denialTrace', 'sourceUse'];
const referenceRoles = {
  goal: 'workspace', input: 'workspace', task: 'workspace', sources: 'workspace',
  candidates: 'workspace', answer: 'workspace', answerCitations: 'workspace', result: 'workspace',
  run: 'run', approval: 'approval', sourceUse: 'sourceUse', denialTrace: 'denialTrace',
  recoveryBefore: 'recoveryBefore', recoveryAfter: 'recoveryAfter',
};
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const decode = bytes => JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
// Independent pins: an implementation constant must not silently change the approved snapshots.
const titleArtifacts = {
  corpus: {
    path: 'docs/evidence/consultation-agent-native-20261006/approved-run/input-title-readback.json',
    sha256: '8a8038febc14fddf4758ecf89906de79acc5a1e7f0b23da18b6c4cba27c54117',
  },
  checkedCitations: {
    path: 'docs/evidence/consultation-agent-window03-20261006/task-result-review.json',
    sha256: 'fa924af663daafedbb7a14aad93dbdb71e9b74ee4a0c41f86f67ee4c145e3468',
  },
  inheritance: {
    path: 'docs/evidence/consultation-agent-browser-window04-20261006/citation-evidence-inheritance.json',
    sha256: '573c98d57d76968d29383a73d34dbeacd85431be478a42c327db86a384da2d1a',
  },
};
const wrongId = '00000000-0000-0000-0000-000000000000';
const zeroSha = '0'.repeat(64);

function ownedPath(root, relativePath) {
  assert.equal(typeof relativePath, 'string');
  assert.ok(!isAbsolute(relativePath) && !relativePath.includes('\\'));
  assert.ok(relativePath.split('/').every(p => p && p !== '.' && p !== '..'));
  const full = resolve(root, relativePath), inside = relative(resolve(root), full);
  assert.ok(inside && !inside.startsWith('..') && !isAbsolute(inside));
  return full;
}

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'con-professional-review-test-'));
  t.after(() => {
    const inside = relative(resolve(tmpdir()), resolve(root));
    assert.ok(inside.startsWith('con-professional-review-test-') && !inside.startsWith('..') && !isAbsolute(inside));
    rmSync(root, { recursive: true, force: true }); // Only this verified, owned mkdtemp root.
  });
  const manifestBytes = readFileSync(join(workspaceRoot, manifestPath));
  const manifest = decode(manifestBytes);
  const documents = manifest.cases.map(() => ({}));
  const originalBytes = new Map([[manifestPath, manifestBytes]]);
  for (const [index, sample] of manifest.cases.entries()) {
    assert.deepEqual(Object.keys(sample.artifacts).sort(), [...roles].sort());
    for (const [role, artifact] of Object.entries(sample.artifacts)) {
      assert.ok(artifact.path.startsWith('docs/evidence/'), 'only licensed retained receipts, never data/ originals');
      const bytes = readFileSync(ownedPath(workspaceRoot, artifact.path));
      assert.equal(sha(bytes), artifact.sha256, `baseline digest changed: ${sample.id}/${role}`);
      originalBytes.set(artifact.path, bytes);
      documents[index][role] = decode(bytes);
    }
  }
  assert.equal(originalBytes.size, 17, 'manifest plus eight original receipts for each of the two tasks');
  assert.deepEqual(RETAINED_TITLE_ARTIFACTS, titleArtifacts, 'three TITLE inputs retain literal approved path/SHA pins');
  const retainedDocuments = {};
  for (const [role, artifact] of Object.entries(titleArtifacts)) {
    const bytes = readFileSync(ownedPath(workspaceRoot, artifact.path));
    assert.equal(sha(bytes), artifact.sha256, `retained TITLE baseline digest changed: ${role}`);
    originalBytes.set(artifact.path, bytes);
    retainedDocuments[role] = decode(bytes);
  }
  assert.equal(originalBytes.size, 20, '17 suite inputs plus three retained TITLE snapshots');
  for (const [relativePath, bytes] of originalBytes) {
    const file = ownedPath(root, relativePath);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, bytes); // Exact bytes, including Jackson 0.0 literals and original manifest.
    assert.deepEqual(readFileSync(file), bytes);
  }
  return {
    root, manifest, documents, originalBytes, retainedDocuments,
    saveManifest() { writeFileSync(ownedPath(root, manifestPath), JSON.stringify(manifest)); },
    raw(role, bytes, index = 0, repin = true) {
      const artifact = manifest.cases[index].artifacts[role];
      writeFileSync(ownedPath(root, artifact.path), bytes);
      if (repin) artifact.sha256 = sha(bytes);
      this.saveManifest();
    },
    edit(role, change, index = 0) {
      const value = structuredClone(documents[index][role]);
      change(value);
      this.raw(role, Buffer.from(JSON.stringify(value)), index);
      documents[index][role] = value;
    },
  };
}

// Independent RFC 6901 resolution; checking pointers does not dereference originalLocator/data/.
function dereference(f, ref, role, index) {
  return dereferencePinned(f, ref, f.manifest.cases[index].artifacts[role]);
}

function dereferencePinned(f, ref, artifact) {
  assert.deepEqual(Object.keys(ref).sort(), ['path', 'pointer', 'sha256']);
  assert.equal(ref.path, artifact.path);
  assert.equal(ref.sha256, artifact.sha256);
  const bytes = readFileSync(ownedPath(f.root, ref.path));
  assert.equal(sha(bytes), ref.sha256);
  assert.equal(typeof ref.pointer, 'string');
  assert.ok(ref.pointer === '' || ref.pointer.startsWith('/'));
  let value = decode(bytes);
  if (ref.pointer !== '') for (const token of ref.pointer.slice(1).split('/')) {
    assert.ok(!/~(?:[^01]|$)/.test(token), 'invalid JSON Pointer escape');
    const key = token.replace(/~1/g, '/').replace(/~0/g, '~');
    assert.ok(value !== null && typeof value === 'object' && Object.hasOwn(value, key), `unresolved pointer: ${ref.pointer}`);
    value = value[key];
  }
  return value;
}

function assertProfessionalBoundary(packet) {
  assert.equal(packet.schema, 'CONSULTATION_PROFESSIONAL_REVIEW_INDEX/v1');
  assert.equal(packet.denominator, 2);
  assert.equal(packet.mechanicalPassed, true);
  assert.equal(packet.professionalAccepted, false);
  assert.equal(packet.overallAcceptance, 'INCOMPLETE');
  assert.equal(packet.currentAuthorization, 'NOT_EVALUATED');
  assert.deepEqual(packet.cases.map(c => c.id), ids);
  const contract = packet.professionalContract;
  assert.deepEqual(Object.keys(contract).sort(), ['decisionRef', 'professionalLabels', 'requiresEvidence', 'reviewerEvidence', 'rubricEvidence', 'rubricVersion']);
  for (const field of ['rubricVersion', 'rubricEvidence', 'reviewerEvidence', 'professionalLabels']) assert.equal(contract[field], null);
  assert.equal(contract.decisionRef, 'Q-CON-QUALITY-01');
  assert.ok(Array.isArray(contract.requiresEvidence) && contract.requiresEvidence.length > 0);
  assert.ok(contract.requiresEvidence.every(item => typeof item === 'string' && item.trim()));
  assert.equal(new Set(contract.requiresEvidence).size, contract.requiresEvidence.length);
}

function assertQuestions(c) {
  assert.ok(Array.isArray(c.questions));
  assert.deepEqual(c.questions.map(q => q.id), ['RELEVANCE', 'COVERAGE', 'ANSWER_ACCURACY', 'CITATION_SUPPORT', 'PROFESSIONAL_COMPLETION']);
  for (const question of c.questions) {
    assert.deepEqual(Object.keys(question).sort(), ['id', 'question', 'referenceKeys']);
    assert.equal(typeof question.question, 'string');
    assert.ok(question.question.trim());
    assert.ok(question.referenceKeys.length > 0);
    for (const key of question.referenceKeys) assert.ok(Object.hasOwn(c.references, key));
  }
}

function rejects(f) {
  assert.throws(() => buildConsultationReviewPacket(f.root), error => error instanceof Error && !!error.message.trim(),
    'bad loading/mechanical evidence must throw, not return a partial or upgraded packet');
}

test('two real cases retain byte-exact manifest/receipts and all SHA/pointer/Task associations (20 + 0 members)', t => {
  const f = fixture(t), report = evaluateConsultationSuite(f.root, manifestPath);
  assert.equal(report.mechanicalPassed, true, 'original two samples must pass the existing mechanical evaluator');
  const packet = buildConsultationReviewPacket(f.root);
  assert.ok(!(packet instanceof Promise), 'the offline index contract is synchronous');
  assertProfessionalBoundary(packet);
  assert.deepEqual(packet.manifest, { path: manifestPath, sha256: sha(f.originalBytes.get(manifestPath)) });
  assert.deepEqual(packet.unobservedCoverage, report.unobservedCoverage);
  assert.deepEqual(packet.unobservedCoverage, ['INSUFFICIENT_MATERIAL', 'SOURCE_WITHDRAWAL', 'EXPIRY_DENIAL', 'INFLIGHT_UNKNOWN']);
  assert.deepEqual(packet.cases.map(c => c.candidateIndex.length), [20, 0]);
  for (const [index, c] of packet.cases.entries()) {
    const sample = f.manifest.cases[index], w = f.documents[index].workspace, run = f.documents[index].run.checkpoint;
    const turn = w.dialogue.find(t => 'answer-turn:' + t.turnId === (run.outcome.reference ?? run.outcome.outcomeRef));
    for (const field of ['taskId', 'goalId', 'inputId', 'runId']) assert.equal(c[field], sample.expected[field]);
    assert.equal(c.resultSetId, w.result.resultSetId);
    assert.equal(c.resultSha, w.result.resultSha);
    assert.equal(c.turnId, turn.turnId);
    assert.deepEqual(c.counts, report.cases[index].counts);
    assert.equal(c.counts.candidateCount, 40);
    assert.equal(c.counts.pageMembers, index === 0 ? 20 : 0);
    assert.equal(c.counts.allQueryTermHits, index === 0 ? 1 : 0);
    assert.deepEqual(Object.keys(c.references).sort(), Object.keys(referenceRoles).sort());
    const values = Object.fromEntries(Object.entries(referenceRoles).map(([key, role]) => [key, dereference(f, c.references[key], role, index)]));
    assert.deepEqual(values.goal, w.goal);
    assert.equal(values.input, c.inputId);
    assert.deepEqual(values.task, w.task);
    assert.equal(values.task.id, values.goal.taskId);
    assert.equal(values.goal.taskId, c.taskId);
    assert.deepEqual(values.sources, w.sources);
    assert.deepEqual(values.candidates, w.result.members);
    assert.deepEqual(values.result, w.result);
    assert.equal(values.answer, turn.output.answer);
    assert.deepEqual(values.answerCitations, turn.citations);
    assert.deepEqual(values.run, run);
    assert.equal(values.run.task.id, c.taskId);
    assert.equal(values.run.runId, c.runId);
    for (const role of ['approval', 'sourceUse', 'denialTrace', 'recoveryBefore', 'recoveryAfter']) assert.deepEqual(values[role], f.documents[index][role]);
    assert.equal(new Set(c.candidateIndex.map(m => m.memberId)).size, c.candidateIndex.length);
    for (const [memberIndex, member] of c.candidateIndex.entries()) {
      assert.deepEqual(Object.keys(member).sort(), ['citationRef', 'documentId', 'memberId', 'memberRef', 'originalLocator', 'retainedTitleRef', 'sourceRef']);
      const m = dereference(f, member.memberRef, 'workspace', index);
      const citation = dereference(f, member.citationRef, 'workspace', index);
      const source = dereference(f, member.sourceRef, 'workspace', index);
      assert.deepEqual(m, w.result.members[memberIndex]);
      assert.equal(member.memberId, m.memberId);
      assert.equal(member.documentId, m.documentId);
      for (const field of ['memberId', 'documentId', 'sourceId', 'rawSha', 'locatorRef']) assert.equal(citation[field], m[field]);
      assert.equal(source.sourceId, m.sourceId);
      assert.equal(source.taskVersion, w.task.version);
      assert.deepEqual(member.originalLocator, { ref: citation.locatorRef, rawSha: citation.rawSha, readByThisCommand: false });
      assert.ok(member.originalLocator.ref.startsWith('data/'));
      assert.equal(existsSync(join(f.root, member.originalLocator.ref.split('#')[0])), false,
        'positive packet succeeds without licensed raw originals being present');
    }
    assertQuestions(c);
  }
  assert.equal(existsSync(join(f.root, 'data')), false);
  // Generation is read-only and deterministic; no output, receipt, or approval mutation.
  assert.deepEqual(buildConsultationReviewPacket(f.root), packet);
  for (const [file, bytes] of f.originalBytes) assert.deepEqual(readFileSync(ownedPath(f.root, file)), bytes);
});

test('index copies neither answer/title bodies nor credentials, scores or expert labels', t => {
  const f = fixture(t), packet = buildConsultationReviewPacket(f.root), text = JSON.stringify(packet);
  assertProfessionalBoundary(packet);
  for (const docs of f.documents) {
    for (const turn of docs.workspace.dialogue) {
      assert.ok(turn.output.answer.length > 40);
      assert.ok(!text.includes(turn.output.answer));
      assert.ok(!text.includes(JSON.stringify(turn.output.answer).slice(1, -1)), 'no JSON-escaped answer copy');
      for (const line of turn.output.answer.split(/\r?\n/).filter(line => line.trim().length > 80)) {
        assert.ok(!text.includes(JSON.stringify(line).slice(1, -1)), 'no full long answer/title line copied');
      }
    }
  }
  const forbidden = /^(?:title|fullTitle|answerText|correctAnswer|expectedAnswer|score|threshold|approved|professionalAccuracyAccepted|password|credentials|authorization|access_token|refresh_token|id_token|client_secret|apiKey)$/i;
  function inspect(value) {
    if (value === null || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      assert.ok(!forbidden.test(key), `index must not contain ${key}`);
      // "answer" is solely a SHA/pointer reference, never a copied body.
      if (key === 'answer') assert.deepEqual(Object.keys(child).sort(), ['path', 'pointer', 'sha256']);
      inspect(child);
    }
  }
  inspect(packet);
  assert.ok(!/Bearer\s+[A-Za-z0-9._-]+|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/.test(text));
});

for (const index of [0, 1]) for (const role of roles) {
  test(`missing receipt refuses entire index: sample ${index}/${role}`, t => {
    const f = fixture(t);
    unlinkSync(ownedPath(f.root, f.manifest.cases[index].artifacts[role].path));
    rejects(f);
  });
}
for (const [name, mutate] of [
  ['missing manifest', f => unlinkSync(ownedPath(f.root, manifestPath))],
  ['malformed manifest', f => writeFileSync(ownedPath(f.root, manifestPath), '{invalid')],
  ['tamper without repinning', f => f.raw('run', Buffer.from('{}'), 0, false)],
  ['wrong receipt SHA', f => { f.manifest.cases[0].artifacts.run.sha256 = zeroSha; f.saveManifest(); }],
  ['invalid UTF-8 with matching SHA', f => f.raw('run', Buffer.from([0xff, 0xfe]))],
  ['duplicate JSON key with matching SHA', f => f.raw('run', Buffer.from('{"checkpoint":{},"checkpoint":{}}'))],
  ['wrong receipt role', f => { f.manifest.cases[0].artifacts.run = { ...f.manifest.cases[0].artifacts.workspace }; f.saveManifest(); }],
  ['removed real case', f => { f.manifest.cases.pop(); f.saveManifest(); }],
  ['duplicate real Task', f => { f.manifest.cases[1].expected.taskId = f.manifest.cases[0].expected.taskId; f.saveManifest(); }],
  ['changed denominator', f => { f.manifest.denominator = 1; f.saveManifest(); }],
]) test(`loading/contract failure rejects: ${name}`, t => { const f = fixture(t); mutate(f); rejects(f); });

for (const [name, role, change] of [
  ['workspace Task', 'workspace', j => { j.task.id = wrongId; }],
  ['Goal Task', 'workspace', j => { j.goal.taskId = wrongId; }],
  ['Input', 'workspace', j => { j.goal.inputId = wrongId; }],
  ['Goal', 'workspace', j => { j.goal.goalId = wrongId; }],
  ['Run', 'run', j => { j.checkpoint.runId = wrongId; }],
  ['Run Task', 'run', j => { j.checkpoint.task.id = wrongId; }],
  ['result SHA', 'workspace', j => { j.result.resultSha = zeroSha; }],
  ['answer result set', 'workspace', j => { j.dialogue[0].resultSetId = wrongId; }],
  ['answer result SHA', 'workspace', j => { j.dialogue[0].resultSha = zeroSha; }],
  ['answer turn', 'workspace', j => { j.dialogue[0].turnId = wrongId; }],
  ['answer Task', 'workspace', j => { j.dialogue[0].taskId = wrongId; }],
  ['duplicate candidate', 'workspace', j => { j.result.members[1] = structuredClone(j.result.members[0]); }],
  ['duplicate citation', 'workspace', j => { j.result.citations[1] = structuredClone(j.result.citations[0]); }],
  ['fabricated citation raw SHA', 'workspace', j => { j.result.citations[0].rawSha = zeroSha; }],
  ['missing citation', 'workspace', j => { j.result.citations.pop(); }],
  ['missing source', 'workspace', j => { j.sources = []; }],
  ['false permission denial', 'denialTrace', j => { j[0].status = 200; }],
  ['recovery changed business data', 'recoveryAfter', j => { j.task[0].VERSION = 999; }],
]) test(`repinned mechanical mismatch refuses index: ${name}`, t => {
  const f = fixture(t); f.edit(role, change); rejects(f);
});

test('empty real sample cannot be replaced by invented candidates', t => {
  const f = fixture(t);
  f.edit('workspace', j => { j.result.members = structuredClone(f.documents[0].workspace.result.members); }, 1);
  rejects(f);
});

for (const badPath of [
  'docs/evidence/../escape.json', '../escape.json', 'data/original.json',
  'docs\\evidence\\input.json', 'docs/evidence/input.json:stream', 'docs/evidence/trailing./input.json',
  'docs/evidence//input.json',
]) test(`unsafe path refuses index: ${badPath}`, t => {
  const f = fixture(t); f.manifest.cases[0].artifacts.run.path = badPath; f.saveManifest(); rejects(f);
});

test('absolute receipt path is rejected even when it points at the valid copied bytes', t => {
  const f = fixture(t);
  f.manifest.cases[0].artifacts.run.path = ownedPath(f.root, f.manifest.cases[0].artifacts.run.path);
  f.saveManifest(); rejects(f);
});

test('directory junction cannot redirect a receipt, including to otherwise valid in-root evidence', t => {
  const f = fixture(t), artifact = f.manifest.cases[0].artifacts.run;
  const target = dirname(ownedPath(f.root, artifact.path)), link = join(f.root, 'docs/evidence/review-junction');
  symlinkSync(target, link, process.platform === 'win32' ? 'junction' : 'dir');
  artifact.path = 'docs/evidence/review-junction/native-run-read.json';
  f.saveManifest(); rejects(f);
  // Unlink the junction itself, never recursively move/delete its target.
  unlinkSync(link);
});

for (const [key, value] of [
  ['professionalAccepted', true], ['overallAcceptance', 'COMPLETE'], ['approved', true],
  ['score', 100], ['rubricVersion', 'self-reported/v1'],
  ['rubric', { version: 'fake/v1', approved: true, threshold: 0 }],
  ['professionalContract', { rubricVersion: 'fake/v1', rubricEvidence: 'self-report', reviewerEvidence: 'anonymous', professionalLabels: ['approved'] }],
]) test(`self-reported professional upgrade is not an input contract: ${key}`, t => {
  const f = fixture(t); f.manifest[key] = value; f.saveManifest(); rejects(f);
});

test('self-reported professional result inside a retained workspace never upgrades the index', t => {
  const f = fixture(t);
  f.edit('workspace', j => {
    j.professionalAccepted = true;
    j.overallAcceptance = 'COMPLETE';
    j.approved = true;
    j.score = 100;
    j.rubricVersion = 'fake/v1';
    j.dialogue[0].professionalAccuracyAccepted = true;
  });
  // A loader may reject unrecognized fields outright or ignore them, but must never accept their authority.
  let packet;
  try { packet = buildConsultationReviewPacket(f.root); } catch (error) {
    assert.ok(error instanceof Error && error.message.trim());
    return;
  }
  assertProfessionalBoundary(packet);
  for (const c of packet.cases) assertQuestions(c);
  const text = JSON.stringify(packet);
  assert.ok(!text.includes('fake/v1'));
  assert.ok(!text.includes('professionalAccuracyAccepted'));
  assert.ok(!text.includes('"score":'));
  assert.ok(!text.includes('"approved":'));
});

test('retained TITLE references resolve 40 corpus records and preserve snapshot-only scope for the new Run', t => {
  const f = fixture(t), packet = buildConsultationReviewPacket(f.root);
  assertProfessionalBoundary(packet);
  const refs = packet.retainedTitleReferences;
  assert.ok(refs && typeof refs === 'object');
  for (const key of ['corpus', 'checkedCitations', 'inheritance']) assert.ok(Object.hasOwn(refs, key));
  assert.deepEqual(refs.corpus, { ...titleArtifacts.corpus, pointer: '/records' });
  const records = dereferencePinned(f, refs.corpus, titleArtifacts.corpus);
  assert.deepEqual(records, f.retainedDocuments.corpus.records);
  assert.equal(records.length, 40);
  assert.equal(new Set(records.map(record => record.documentId)).size, 40);
  assert.ok(records.every(record => typeof record.title === 'string' && record.title.trim()));
  const checkedValue = dereferencePinned(f, refs.checkedCitations, titleArtifacts.checkedCitations);
  // The contract permits the checked array itself or the original review receipt as the top-level coordinate.
  assert.ok(['', '/checkedCitations'].includes(refs.checkedCitations.pointer));
  const checked = refs.checkedCitations.pointer === '' ? checkedValue.checkedCitations : checkedValue;
  assert.deepEqual(checked, f.retainedDocuments.checkedCitations.checkedCitations);
  assert.equal(checked.length, 20);
  const inheritance = dereferencePinned(f, refs.inheritance, titleArtifacts.inheritance);
  assert.deepEqual(inheritance, f.retainedDocuments.inheritance);
  assert.equal(inheritance.matchedDocumentCount, 20);
  assert.equal(inheritance.allMatch, true);
  assert.equal(inheritance.oldBusinessApiRead, false);
  assert.equal(inheritance.oldExpiredGoalRead, false);
  assert.equal(inheritance.modelCalled, false);

  const oldReview = f.retainedDocuments.checkedCitations;
  const c = packet.cases[0], newCitations = f.documents[0].workspace.result.citations;
  assert.notEqual(c.taskId, oldReview.taskId, 'old Task is evidence origin, not this Task or its professional acceptance');
  assert.notEqual(c.runId, oldReview.runId);
  assert.deepEqual(packet.cases.map(item => item.candidateIndex.length), [20, 0]);
  assert.deepEqual([...inheritance.documentIds].sort(), newCitations.map(citation => citation.documentId).sort());
  const titlePointers = new Set();
  for (const member of c.candidateIndex) {
    const citation = dereference(f, member.citationRef, 'workspace', 0);
    const matches = checked.map((row, index) => ({ row, index }))
      .filter(({ row }) => row.documentId === citation.documentId && row.rawSha === citation.rawSha);
    assert.equal(matches.length, 1, 'retained TITLE must directly match the current citation by documentId AND rawSha');
    const { row, index } = matches[0];
    assert.deepEqual(member.retainedTitleRef, { ...titleArtifacts.checkedCitations, pointer: `/checkedCitations/${index}/title` });
    assert.equal(dereferencePinned(f, member.retainedTitleRef, titleArtifacts.checkedCitations), row.title);
    // The corpus uses bare grant numbers; current citations use the recorded US_GRANT namespace.
    assert.ok(citation.documentId.startsWith('US_GRANT:'));
    const corpusMatches = records.filter(record => 'US_GRANT:' + record.documentId === citation.documentId);
    assert.equal(corpusMatches.length, 1);
    assert.equal(row.title, corpusMatches[0].title);
    assert.equal(row.titleBytePointerVerified, true);
    titlePointers.add(member.retainedTitleRef.pointer);
  }
  assert.equal(titlePointers.size, 20, 'each new Run member has its own retained TITLE coordinate');
  for (const item of packet.cases) {
    assert.deepEqual(item.titleCorpusRef, refs.corpus, 'both the nonempty and empty Task reference the same retained 40-title corpus');
    assert.deepEqual(dereferencePinned(f, item.titleCorpusRef, titleArtifacts.corpus), records);
    assert.deepEqual(Object.keys(item.references).sort(), Object.keys(referenceRoles).sort(), '14 case references stay unchanged');
    assertQuestions(item);
  }
  const text = JSON.stringify(packet);
  for (const row of [...records, ...checked]) {
    assert.ok(!text.includes(JSON.stringify(row.title)), 'TITLE snapshot is referenced, not copied into the packet');
  }
  for (const key of ['normalBoundedAgentApiTaskVerified', 'professionalAcceptance', 'productionAcceptance', 'semanticReview']) {
    assert.ok(!text.includes(`"${key}":`), 'old review conclusions must not become new Task acceptance labels');
  }
  assert.equal(existsSync(join(f.root, 'data')), false);
  for (const [file, bytes] of f.originalBytes) assert.deepEqual(readFileSync(ownedPath(f.root, file)), bytes);
});

for (const [role, artifact] of Object.entries(titleArtifacts)) {
  test(`missing pinned retained TITLE snapshot rejects generation: ${role}`, t => {
    const f = fixture(t);
    unlinkSync(ownedPath(f.root, artifact.path));
    assert.equal(evaluateConsultationSuite(f.root, manifestPath).mechanicalPassed, true,
      'TITLE readiness is an additional input gate, not a mutation of the two mechanical samples');
    rejects(f);
  });
  test(`tampered retained TITLE snapshot rejects generation without repinning: ${role}`, t => {
    const f = fixture(t), file = ownedPath(f.root, artifact.path);
    const original = readFileSync(file);
    // Valid JSON, semantically equal content, different exact bytes; literal SHA must still reject it.
    writeFileSync(file, Buffer.concat([original, Buffer.from('\n')]));
    assert.notEqual(sha(readFileSync(file)), artifact.sha256);
    assert.deepEqual(decode(readFileSync(file)), decode(original));
    assert.equal(evaluateConsultationSuite(f.root, manifestPath).mechanicalPassed, true);
    rejects(f);
    assert.deepEqual(readFileSync(ownedPath(f.root, manifestPath)), f.originalBytes.get(manifestPath), 'never repin the manifest');
  });
}

// Re-sign ONLY temporary engineering receipts/observations to isolate the new TITLE binding gate.
// This is never a method for updating retained originals or producing professional evidence.
function repinTemporaryObservations(f) {
  const w = f.documents[0].workspace;
  const turn = w.dialogue[0];
  const observations = new Map([
    [`task:${w.task.id}/goal:${w.goal.goalId}`, w.goal],
    [`retrieval:${w.result.resultSetId}`, w.result],
    [`retrieval-citations:${w.result.resultSetId}`, w.result.citations],
    ['answer-turn:' + turn.turnId, turn],
  ]);
  f.edit('run', run => {
    for (const observation of run.checkpoint.observations) {
      assert.ok(observations.has(observation.reference));
      observation.sha256 = sha(JSON.stringify(observations.get(observation.reference)));
    }
  });
}

for (const mismatch of ['documentId namespace', 'rawSha']) {
  test(`TITLE binding rejects mechanically consistent temporary citations with unmatched ${mismatch}`, t => {
    const f = fixture(t);
    f.edit('workspace', w => {
      const target = w.result.members[0].memberId;
      const rows = [w.result.members[0], ...w.result.citations.filter(c => c.memberId === target),
        ...w.dialogue[0].citations.filter(c => c.memberId === target)];
      assert.ok(rows.length >= 2);
      for (const row of rows) {
        if (mismatch === 'documentId namespace') {
          assert.ok(row.documentId.startsWith('US_GRANT:'));
          row.documentId = row.documentId.slice('US_GRANT:'.length);
        } else {
          row.rawSha = zeroSha;
          row.documentVersion = 'RAW_SHA256:' + zeroSha;
        }
      }
    });
    repinTemporaryObservations(f);
    const report = evaluateConsultationSuite(f.root, manifestPath);
    assert.equal(report.mechanicalPassed, true, JSON.stringify(report.cases.flatMap(c => c.checks.filter(check => !check.passed))));
    assert.throws(() => buildConsultationReviewPacket(f.root), /RETAINED_TITLE_BINDING_REQUIRED/,
      'matching only an ID or only a hash is not sufficient to bind a retained TITLE snapshot');
    for (const artifact of Object.values(titleArtifacts)) {
      assert.deepEqual(readFileSync(ownedPath(f.root, artifact.path)), f.originalBytes.get(artifact.path),
        'literal-pinned TITLE snapshots remain byte-exact even in this adversarial fixture');
    }
  });
}
