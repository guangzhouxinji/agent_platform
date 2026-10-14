import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

// Verifies preserved real HTTP/DM8 artifacts, not generated candidates or model responses.
const [directory, report] = process.argv.slice(2);
assert(directory && report, 'Exact evidence directory and new report path required');
const root = resolve(directory), inputs = [];
function read(name) {
  const bytes = readFileSync(join(root, name));
  inputs.push({ path: name, sha256: createHash('sha256').update(bytes).digest('hex') });
  return JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
}
const task = read('task-receipt-v697.json');
const firstProposal = read('model-proposal-v699.json');
const clarification = read('model-proposal-v700.json');
const modified = read('model-proposal-v704.json');
const selection = read('model-selection-v703.json');
const selectionHistory = read('model-history-selection-v712.json');
const boundaries = read('model-boundaries-v711.json');
assert.equal(boundaries.taskId, task.taskId);
assert(boundaries.passed && boundaries.checks.length === 7 && boundaries.checks.every(c => c.passed));
assert.equal(selection.selectionId, selectionHistory.selectionId);
assert.equal(selection.revision, selectionHistory.revision);
assert.deepEqual(selection.members, selectionHistory.members);
const expectedProposals = [firstProposal, clarification, modified];
for (const turn of expectedProposals) {
  assert.equal(turn.taskId, task.taskId);
  assert.equal(turn.httpStatus, 200);
  assert.equal(turn.readStatus, 200);
  assert.equal(turn.response.modelId, 'deepseek-flash');
  assert.equal(turn.response.promptVersion, 'RETRIEVAL_DIALOGUE/v3');
  assert.equal(turn.humanCostMeasurement, 'NOT_OBSERVED');
  assert.equal(turn.humanElapsedMs, null);
  assert(!('confirmed' in turn.response.output));
}
const context = read(`model-audit-v713/${clarification.response.turnId}-request.json`);
assert.equal(context.scope, 'PUBLIC_NONCONFIDENTIAL_RETRIEVAL_ONLY');
assert(context.previousPublicProposals.length > 0 && context.previousPublicProposals.length <= 2);
assert(JSON.stringify(context.previousPublicProposals).includes(firstProposal.response.output.query));
const rounds = [];
for (const [resultName, pageName, explanationName, goalName] of [
  ['patent-search-result-v701.json', 'model-search-page-v703.json', 'model-explanation-v702.json', 'goal-receipt-v701.json'],
  ['patent-search-result-v705.json', 'model-search-page-v715.json', 'model-explanation-v706.json', 'goal-receipt-v705.json']
]) {
  const result = read(resultName), page = read(pageName), explanation = read(explanationName), goal = read(goalName);
  assert.equal(goal.taskId, task.taskId);
  assert.equal(explanation.request.expectedTaskVersion, goal.taskVersion);
  assert.equal(explanation.httpStatus, 200);
  assert.equal(explanation.readStatus, 200);
  assert.equal(explanation.response.resultSetId, result.resultSetId);
  assert.equal(explanation.response.resultSha, result.resultSha);
  assert.equal(page.resultSetId, result.resultSetId);
  assert.equal(page.resultSha, result.resultSha);
  assert.equal(result.resultCount, 40);
  assert.equal(page.members.length, 20);
  assert.equal(page.confidenceStatus, 'LOW_CONFIDENCE');
  assert.equal(explanation.response.promptVersion, 'RETRIEVAL_DIALOGUE/v3');
  const turnId = explanation.response.turnId;
  const request = read(`model-audit-v713/${turnId}-request.json`);
  const provider = read(`model-audit-v713/${turnId}-provider.json`);
  const retained = read(`model-audit-v713/${turnId}-output.json`);
  assert.equal(provider.model, 'deepseek-flash');
  assert.equal(provider.object, 'chat.completion');
  assert(provider.id && provider.usage?.total_tokens > 0);
  assert.deepEqual(retained, explanation.response.output);
  const rawOutput = JSON.parse(provider.choices[0].message.content);
  if (rawOutput.type === 'json_object') delete rawOutput.type;
  assert.deepEqual(rawOutput, retained);
  assert.equal(request.methodVersion, result.methodVersion);
  assert.equal(request.totalCandidates, 40);
  assert.equal(request.members.length, 20);
  assert.equal(request.confidence, 'LOW_CONFIDENCE');
  for (const input of request.members) {
    const actual = page.members.find(m => m.memberId === input.memberId);
    assert(actual);
    for (const field of ['documentId', 'documentVersion', 'ordinal', 'lexicalRank', 'semanticRank', 'fusionScore', 'rerankScore', 'rerankRank'])
      assert.deepEqual(input[field], actual[field], `Actual retrieval field mismatch: ${field}`);
  }
  const ids = retained.citations;
  assert(ids.length > 0 && new Set(ids).size === ids.length);
  assert.equal(explanation.response.citations.length, ids.length);
  for (const citation of explanation.response.citations) {
    assert(ids.includes(citation.memberId));
    const actual = page.citations.find(c => c.memberId === citation.memberId);
    assert.deepEqual(citation, actual);
    assert.equal(citation.sourceId, 'PATENTSVIEW_ZENODO_2024');
    assert.equal(citation.documentVersion, `RAW_SHA256:${citation.rawSha}`);
    assert.deepEqual(Object.keys(citation.fieldLocators).sort(), ['CLAIMS', 'TITLE']);
    const ref = citation.locatorRef.split('#')[0];
    const recordBytes = readFileSync(resolve(ref));
    assert.equal(createHash('sha256').update(recordBytes).digest('hex'), citation.rawSha);
    for (const locator of Object.values(citation.fieldLocators)) {
      const match = locator.match(/#json-pointer=([^&]+)&utf8=(\d+):(\d+)$/);
      assert(match, 'Exact UTF-8 locator required');
      const record = JSON.parse(recordBytes.toString('utf8'));
      const value = record[match[1].slice(1)];
      // Source locator bounds include the exact serialized JSON string, including escaping/quotes.
      assert.equal(JSON.parse(recordBytes.subarray(Number(match[2]), Number(match[3])).toString('utf8')), value);
    }
  }
  rounds.push({ taskVersion: goal.taskVersion, inputId: goal.inputId, resultSetId: result.resultSetId,
    fencingToken: result.fencingToken, resultSha: result.resultSha, citationCount: ids.length,
    modelTurnId: turnId, automatedElapsedMs: explanation.automatedElapsedMs });
}
assert.equal(rounds[0].taskVersion, 2);
assert.equal(rounds[1].taskVersion, 3);
assert.notEqual(rounds[0].inputId, rounds[1].inputId);
assert.notEqual(rounds[0].resultSha, rounds[1].resultSha);
assert(rounds[1].fencingToken > rounds[0].fencingToken);
for (const name of readdirSync(join(root, 'model-audit-v713')).filter(n => n.endsWith('-request.json'))) {
  const request = read(`model-audit-v713/${name}`);
  for (const field of ['taskId', 'organizationId', 'actorSubject', 'access_token', 'authorization', 'apiKey', 'claimsText', 'fullText'])
    assert(!JSON.stringify(request).includes(`"${field}"`), `Unexpected model input field ${field}`);
}
writeFileSync(resolve(report), JSON.stringify({ verifiedAt: new Date().toISOString(), taskId: task.taskId,
  passed: true, scope: 'REAL_MODEL_CURRENT_ENVIRONMENT_TECHNICAL_GOLDEN_JOB', rounds,
  securityChecks: boundaries.checks, selectionHistoryPreserved: true, actualRawLocatorCheck: true,
  humanElapsedMs: null, humanCostMeasurement: 'NOT_OBSERVED',
  humanActionsRequiringObservation: ['review clarification', 'confirm first conditions', 'check cited source',
    'select document', 'modify conditions', 'confirm next conditions', 'review next explanation'],
  limitations: ['not performance acceptance', 'not professional comparison', 'not production deployment acceptance',
    'not complete consultation business acceptance', 'no measured human/V1 cost'], inputs }, null, 2), { flag: 'wx' });
console.log(`Real model golden verified: ${rounds.length} rounds, exact raw citations, ${boundaries.checks.length} security checks; human cost NOT_OBSERVED`);
