import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, cpSync, readFileSync, existsSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { main } from './evaluate-consultation-task-quality.mjs';
import { RETAINED_TITLE_ARTIFACTS } from './lib/consultation-professional-review.mjs';
const manifestPath = 'governance/evaluations/consultation-task-quality-v1.json';
function fixture(t) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'con-quality-cli-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  for (const relative of [manifestPath, ...Object.values(RETAINED_TITLE_ARTIFACTS).map(a=>a.path), ...manifest.cases.flatMap(c => Object.values(c.artifacts).map(a => a.path))]) {
    const target = path.join(root, relative);
    mkdirSync(path.dirname(target), { recursive: true });
    cpSync(relative, target);
  }
  mkdirSync(path.join(root, 'docs/evidence/new-report'), { recursive: true });
  return root;
}
function run(root, args) {
  const lines = [], errors = [];
  return { code: main(root, args, s => lines.push(s), s => errors.push(s)), lines, errors };
}
test('read-only verify passes only mechanical scope and full acceptance stays incomplete', t => {
  const root = fixture(t), result = run(root, ['--verify']);
  assert.equal(result.code, 0);
  const report = JSON.parse(result.lines[0]);
  assert.equal(report.mechanicalSummary.denominator, 2);
  assert.equal(report.overallAcceptance, 'INCOMPLETE');
  assert.equal(report.professionalAccepted, false);
  assert.equal(report.currentAuthorization, 'NOT_EVALUATED');
});
test('professional acceptance never becomes green from mechanical evidence', t => {
  const root = fixture(t);
  assert.equal(run(root, ['--require-professional']).code, 2);
});
test('new report contains input hashes but not copied answer or credentials, cannot overwrite', t => {
  const root = fixture(t), relative = 'docs/evidence/new-report/result.json';
  assert.equal(run(root, ['--report', relative]).code, 0);
  const bytes = readFileSync(path.join(root, relative)), report = JSON.parse(bytes);
  assert.equal(report.inputFiles.length, 17);
  assert.equal(report.professionalAccepted, false);
  assert.equal(report.cases[0].counts.allQueryTermHits, 1);
  assert.ok(!bytes.toString().includes('access_token'));
  assert.ok(!bytes.toString().includes('"answer":'));
  assert.equal(run(root, ['--report', relative]).code, 1);
  assert.deepEqual(readFileSync(path.join(root, relative)), bytes);
});
test('CLI rejects unknown, duplicate, incomplete and conflicting options before output', t => {
  const root = fixture(t);
  for (const args of [['--unknown'], ['--verify', '--verify'], ['--report'], ['--report', '--verify'], ['--verify', '--report', 'docs/evidence/new-report/x.json']]) {
    assert.equal(run(root, args).code, 1);
  }
  assert.equal(existsSync(path.join(root, 'docs/evidence/new-report/x.json')), false);
});
test('report cannot replace receipts or write outside approved evidence directory', t => {
  const root = fixture(t);
  const manifest = JSON.parse(readFileSync(path.join(root, manifestPath), 'utf8'));
  const receipt = manifest.cases[0].artifacts.run.path;
  const bytes = readFileSync(path.join(root, receipt));
  for (const relative of [receipt, '../escape.json', 'requirements/x.json', 'docs/evidence/../x.json', 'docs\\evidence\\x.json', 'docs/evidence/new-report/existing.json:stream.json', 'docs/evidence/new-report./x.json', path.join(root, 'absolute.json')]) {
    assert.equal(run(root, ['--report', relative]).code, 1);
  }
  assert.deepEqual(readFileSync(path.join(root, receipt)), bytes);
});
test('missing evidence fails closed and retains denominator, report still records failure', t => {
  const root = fixture(t);
  const manifest = JSON.parse(readFileSync(path.join(root, manifestPath), 'utf8'));
  // Mutate a temporary copy only; repository artifacts are immutable.
  writeFileSync(path.join(root, manifest.cases[0].artifacts.run.path), '{}');
  const relative = 'docs/evidence/new-report/failed.json';
  assert.equal(run(root, ['--report', relative]).code, 1);
  const report = JSON.parse(readFileSync(path.join(root, relative), 'utf8'));
  assert.equal(report.mechanicalSummary.denominator, 2);
  assert.equal(report.mechanicalSummary.failed, 1);
  assert.equal(report.cases.length, 2);
});

test('professional review index writes only references, keeps rubric unapproved', t => {
  const root = fixture(t), relative = 'docs/evidence/new-report/review.json';
  const result = run(root, ['--review-packet', relative]);
  assert.equal(result.code, 0);
  const packet = JSON.parse(readFileSync(path.join(root, relative), 'utf8'));
  assert.equal(packet.schema, 'CONSULTATION_PROFESSIONAL_REVIEW_INDEX/v1');
  assert.equal(packet.denominator, 2);
  assert.deepEqual(packet.cases.map(c => c.candidateIndex.length), [20, 0]);
  assert.equal(packet.professionalContract.rubricVersion, null);
  assert.equal(packet.professionalAccepted, false);
  assert.equal(packet.overallAcceptance, 'INCOMPLETE');
  assert.equal(packet.currentAuthorization, 'NOT_EVALUATED');
  assert.equal(run(root, ['--review-packet', relative]).code, 1);
});
test('review generation refuses missing evidence without leaving a false ready artifact', t => {
  const root = fixture(t), relative = 'docs/evidence/new-report/rejected-review.json';
  const manifest = JSON.parse(readFileSync(path.join(root, manifestPath), 'utf8'));
  writeFileSync(path.join(root, manifest.cases[0].artifacts.workspace.path), '{}');
  assert.equal(run(root, ['--review-packet', relative]).code, 1);
  assert.equal(existsSync(path.join(root, relative)), false);
});
test('review output cannot mix with verification or report, or consume fake rubric approval', t => {
  const root = fixture(t), relative = 'docs/evidence/new-report/no-output.json';
  for (const args of [
    ['--verify', '--review-packet', relative],
    ['--report', relative, '--review-packet', relative],
    ['--review-packet'], ['--review-packet', '--require-professional'],
    ['--review-packet', relative, '--review-packet', relative],
    ['--review-packet', relative, '--rubric', 'approved.json'],
    ['--review-packet', relative, '--professional-approved'],
    ['--review-packet', 'requirements/forbidden.json'],
    ['--review-packet', 'docs/evidence/new-report/file.json:stream.json'],
  ]) assert.equal(run(root, args).code, 1);
  assert.equal(existsSync(path.join(root, relative)), false);
});
test('professional gate stays nonzero even when an engineering review index is written', t => {
  const root = fixture(t), relative = 'docs/evidence/new-report/professional-pending.json';
  assert.equal(run(root, ['--review-packet', relative, '--require-professional']).code, 2);
  const packet = JSON.parse(readFileSync(path.join(root, relative), 'utf8'));
  assert.equal(packet.professionalAccepted, false);
});
