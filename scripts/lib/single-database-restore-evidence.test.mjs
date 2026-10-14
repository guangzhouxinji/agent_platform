import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { writeRestoreComparisonEvidence as write } from './single-database-restore-evidence.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const sourceBytes = Buffer.from('{"unit":"owned unit0 source"}');
const destinationBytes = Buffer.from('{"unit":"owned unit0 destination"}');
async function fixture(t) {
  const parent = await fs.realpath(os.tmpdir());
  const root = await fs.mkdtemp(path.join(parent, 'restore-evidence-unit-'));
  t.after(async () => {
    // Delete only this explicitly named owned unit-fixture root, never production evidence.
    assert.equal(path.dirname(path.resolve(root)), parent);
    assert.ok(path.basename(root).startsWith('restore-evidence-unit-'));
    await fs.rm(root, { recursive: true, force: true });
  });
  return { root, directory: path.join(root, 'evidence'), sourceBytes, destinationBytes,
    compare: () => ({ passed: true, code: 'UNIT_EQUAL', differences: [] }) };
}
const readReport = async receipt => JSON.parse(await fs.readFile(receipt.comparisonPath, 'utf8'));
async function assertRaw(receipt, source, destination) {
  assert.deepEqual(await fs.readFile(receipt.sourcePath), source);
  assert.deepEqual(await fs.readFile(receipt.destinationPath), destination);
  assert.equal(receipt.sourceSha256, hash(source)); assert.equal(receipt.destinationSha256, hash(destination));
  const reportBytes = await fs.readFile(receipt.comparisonPath);
  assert.equal(receipt.reportSha256, hash(reportBytes));
  const report = JSON.parse(reportBytes.toString());
  assert.equal(report.sourceSha256, hash(source)); assert.equal(report.destinationSha256, hash(destination));
}

test('owned unit0: success preserves noncanonical JSON bytes and actual raw/report SHAs', async t => {
  const f = await fixture(t);
  f.sourceBytes = Buffer.from(' { "b" : 2, "a":1 }\r\n');
  f.destinationBytes = Buffer.from('\n{"a":1,"b":2}\t');
  f.compare = ({ source, destination }) => { assert.deepEqual(source, destination); return { passed: true }; };
  const receipt = await write(f); assert.equal(receipt.complete, true); assert.equal(receipt.passed, true);
  await assertRaw(receipt, f.sourceBytes, f.destinationBytes);
});
test('async compare sees both original files before execution', async t => {
  const f = await fixture(t);
  f.compare = async ({ source, destination }) => {
    assert.equal(source.unit, 'owned unit0 source'); assert.equal(destination.unit, 'owned unit0 destination');
    assert.deepEqual(await fs.readFile(path.join(f.directory, 'source-inventory.json')), f.sourceBytes);
    assert.deepEqual(await fs.readFile(path.join(f.directory, 'target-inventory.json')), f.destinationBytes);
    await Promise.resolve(); return { passed: false, code: 'UNIT_DIFFERENT' };
  };
  const r = await write(f); assert.equal(r.complete, true); assert.equal(r.passed, false); assert.equal(r.code, 'UNIT_DIFFERENT');
});
test('source parse failure retains BOTH raws and complete failed receipt', async t => {
  const f = await fixture(t); f.sourceBytes = Buffer.from('{"invalid":');
  f.compare = () => assert.fail('compare must not run');
  const r = await write(f); assert.equal(r.passed, false); assert.equal(r.code, 'RESTORE_EVIDENCE_JSON_INVALID');
  await assertRaw(r, f.sourceBytes, f.destinationBytes);
});
test('destination parse failure retains BOTH raws', async t => {
  const f = await fixture(t); f.destinationBytes = Buffer.from('not json OWNED_UNIT_PRIVATE_MARKER');
  const r = await write(f); assert.equal(r.code, 'RESTORE_EVIDENCE_JSON_INVALID');
  await assertRaw(r, f.sourceBytes, f.destinationBytes);
  assert.ok(!(await fs.readFile(r.comparisonPath, 'utf8')).includes('OWNED_UNIT_PRIVATE_MARKER'));
});
test('invalid UTF8 is refused after preserving raw bytes', async t => {
  const f = await fixture(t); f.sourceBytes = Buffer.from([123,34,120,34,58,34,0xff,34,125]);
  const r = await write(f); assert.equal(r.code, 'RESTORE_EVIDENCE_JSON_INVALID'); await assertRaw(r, f.sourceBytes, f.destinationBytes);
});
test('sync compare throw creates sanitized failed report', async t => {
  const f = await fixture(t); f.compare = () => { throw new Error('owned unit secret in error正文'); };
  const r = await write(f); assert.equal(r.code, 'RESTORE_EVIDENCE_COMPARE_FAILED'); await assertRaw(r, f.sourceBytes, f.destinationBytes);
  assert.ok(!(await fs.readFile(r.comparisonPath, 'utf8')).includes('owned unit secret in error正文'));
});
test('async compare rejection creates sanitized failed report', async t => {
  const f = await fixture(t); f.compare = async () => { throw { message: 'UNIT_SECRET', code: 'UNIT_SECRET' }; };
  const r = await write(f); assert.equal(r.code, 'RESTORE_EVIDENCE_COMPARE_FAILED'); assert.equal(r.passed, false);
});
test('unknown report fields, values, raw mapping identities never leak; no console output', async t => {
  const f = await fixture(t), marker = 'OWNED_UNIT_SECRET_PAYLOAD';
  f.compare = () => ({ passed: false, code: 'UNIT_DIFFERENT', message: marker, value: marker,
    differences: [{ path: '/restoreCatalog/categories/nativeObjects/rows/0/INFO3', value: marker, sourceValue: marker,
      destinationValue: marker, count: 1, sourceSha256: hash(sourceBytes) }, { path: '/'+marker, value: marker }],
    mappingEvidence: [{ sha256: hash(destinationBytes), sourceName: marker, content: marker }, { content: marker }] });
  const saved = { log: console.log, error: console.error, warn: console.warn }, calls = [];
  for (const key of Object.keys(saved)) console[key] = (...args) => calls.push(args);
  let r; try { r = await write(f); } finally { Object.assign(console, saved); }
  assert.equal(calls.length, 0);
  const body = await fs.readFile(r.comparisonPath, 'utf8'); assert.ok(!body.includes(marker));
  const report = JSON.parse(body); assert.equal(report.differenceCount, 2); assert.equal(report.redactedDifferenceCount, 1);
  assert.equal(report.redactedMappingCount, 1); assert.deepEqual(report.mappingEvidence, [{ sha256: hash(destinationBytes) }]);
  assert.equal(report.differences[0].count, 1); assert.equal(report.differences[0].sourceSha256, hash(sourceBytes));
});
test('difference hashes/counts are strict; invalid values omitted', async t => {
  const f = await fixture(t); f.compare = () => ({ passed: false, differences: [{ path: '/schemas/0/count',
    hash: 'not-hash', sha256: hash(sourceBytes), count: -1, sourceCount: Number.MAX_SAFE_INTEGER + 1, destinationCount: 0 }] });
  const report = await readReport(await write(f));
  assert.deepEqual(report.differences, [{ path: '/schemas/0/count', sha256: hash(sourceBytes), destinationCount: 0 }]);
});
test('invalid result cannot be silently marked passed', async t => {
  const f = await fixture(t); f.compare = () => ({ passed: 'yes', code: 'UNIT_EQUAL' });
  const r = await write(f); assert.equal(r.passed, false); assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
});
test('non-code error text refused and not copied', async t => {
  const f = await fixture(t); f.compare = () => ({ passed: true, code: 'user password: UNIT_SECRET' });
  const r = await write(f); assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
  assert.ok(!(await fs.readFile(r.comparisonPath, 'utf8')).includes('UNIT_SECRET'));
});
test('getters/toJSON are not invoked', async t => {
  const f = await fixture(t); let accessed = false;
  f.compare = () => ({ get passed() { accessed = true; throw Error('UNIT_SECRET'); }, toJSON() { accessed = true; } });
  const r = await write(f); assert.equal(accessed, false); assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
});
test('oversized difference collection saves fixed failure rather than truncating success', async t => {
  const f = await fixture(t); f.compare = () => ({ passed: true, differences: Array(10001).fill({ path: '/' }) });
  const r = await write(f); assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID'); assert.equal(r.passed, false);
});
test('existing directory rejected and existing evidence never overwritten', async t => {
  const f = await fixture(t); const r = await write(f), bytes = await fs.readFile(r.comparisonPath);
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_DIRECTORY_REFUSED' });
  assert.deepEqual(await fs.readFile(r.comparisonPath), bytes);
});
test('missing parent is not recursively created', async t => {
  const f = await fixture(t); f.directory = path.join(f.root, 'missing', 'evidence');
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_DIRECTORY_REFUSED' });
  await assert.rejects(fs.stat(path.join(f.root, 'missing')), { code: 'ENOENT' });
});
test('relative directory rejected before any write', async t => {
  const f = await fixture(t); f.directory = 'owned-unit-relative';
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_INPUT_INVALID' });
});
test('noncanonical dot-dot directory refused', async t => {
  const f = await fixture(t); f.directory = f.root + path.sep + 'x' + path.sep + '..' + path.sep + 'evidence';
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_INPUT_INVALID' });
});
test('symlink/junction immediate parent rejected', async t => {
  const f = await fixture(t), alias = path.join(f.root, 'alias');
  await fs.symlink(f.root, alias, process.platform === 'win32' ? 'junction' : 'dir');
  f.directory = path.join(alias, 'evidence');
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_DIRECTORY_REFUSED' });
  await assert.rejects(fs.stat(path.join(f.root, 'evidence')), { code: 'ENOENT' });
});
test('symlink/junction ancestor rejected even when immediate parent is ordinary', async t => {
  const f = await fixture(t), real = path.join(f.root, 'real'), alias = path.join(f.root, 'alias');
  await fs.mkdir(real); await fs.mkdir(path.join(real, 'child'));
  await fs.symlink(real, alias, process.platform === 'win32' ? 'junction' : 'dir');
  f.directory = path.join(alias, 'child', 'evidence');
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_DIRECTORY_REFUSED' });
});
test('existing file cannot be used as evidence directory', async t => {
  const f = await fixture(t); await fs.writeFile(f.directory, 'OWNED_UNIT_KEEP');
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_DIRECTORY_REFUSED' });
  assert.equal(await fs.readFile(f.directory, 'utf8'), 'OWNED_UNIT_KEEP');
});
test('only Buffer inputs are accepted', async t => {
  const f = await fixture(t); f.sourceBytes = new Uint8Array([123,125]);
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_INPUT_INVALID' });
  f.sourceBytes = sourceBytes; f.destinationBytes = '{}';
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_INPUT_INVALID' });
});
test('source and destination 32MiB+1 capacities refused before directory creation', async t => {
  const f = await fixture(t), oversized = Buffer.alloc(32 * 1024 * 1024 + 1);
  await assert.rejects(write({ ...f, sourceBytes: oversized }), { message: 'RESTORE_EVIDENCE_RAW_LIMIT' });
  await assert.rejects(write({ ...f, destinationBytes: oversized }), { message: 'RESTORE_EVIDENCE_RAW_LIMIT' });
  await assert.rejects(fs.stat(f.directory), { code: 'ENOENT' });
});
test('exact 32MiB raw accepted byte-for-byte', async t => {
  const f = await fixture(t); f.sourceBytes = Buffer.alloc(32 * 1024 * 1024, 0x20); f.sourceBytes.write('{}');
  const r = await write(f); assert.equal(r.passed, true); await assertRaw(r, f.sourceBytes, f.destinationBytes);
});
test('caller buffer mutations after invocation do not change saved raw snapshots', async t => {
  const f = await fixture(t); f.sourceBytes = Buffer.from(sourceBytes); const expected = Buffer.from(f.sourceBytes);
  const pending = write(f); f.sourceBytes.fill(0x58);
  const r = await pending; await assertRaw(r, expected, f.destinationBytes);
});
test('extra directory file preserved, never overwritten or removed', async t => {
  const f = await fixture(t); const extra = path.join(f.directory, 'other-evidence.json');
  f.compare = async () => { await fs.writeFile(extra, 'OWNED_UNIT_EXTRA', { flag: 'wx' }); return { passed: true }; };
  const r = await write(f); assert.equal(r.complete, true); assert.equal(await fs.readFile(extra, 'utf8'), 'OWNED_UNIT_EXTRA');
});
test('report CreateNew collision throws fixed I/O error, retains raws and existing report', async t => {
  const f = await fixture(t), report = path.join(f.directory, 'comparison.json');
  f.compare = async () => { await fs.writeFile(report, 'OWNED_UNIT_EXISTING_REPORT', { flag: 'wx' }); return { passed: true }; };
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_IO_FAILED' });
  assert.deepEqual(await fs.readFile(path.join(f.directory, 'source-inventory.json')), sourceBytes);
  assert.deepEqual(await fs.readFile(path.join(f.directory, 'target-inventory.json')), destinationBytes);
  assert.equal(await fs.readFile(report, 'utf8'), 'OWNED_UNIT_EXISTING_REPORT');
});
test('report I/O failure after compare throw is NOT a complete failed evidence receipt', async t => {
  const f = await fixture(t); f.compare = async () => {
    await fs.mkdir(path.join(f.directory, 'comparison.json')); throw new Error('UNIT_SECRET');
  };
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_IO_FAILED' });
  assert.deepEqual(await fs.readFile(path.join(f.directory, 'target-inventory.json')), destinationBytes);
});

test('saved raw tampering cannot yield a complete receipt with stale SHA', async t => {
  const f = await fixture(t);
  f.compare = async () => {
    await fs.writeFile(path.join(f.directory, 'source-inventory.json'), Buffer.alloc(sourceBytes.length, 0x58));
    return { passed: true };
  };
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_IO_FAILED' });
  await assert.rejects(fs.stat(path.join(f.directory, 'comparison.json')), { code: 'ENOENT' });
});
test('directory replacement after compare refused; original owned raw files kept', async t => {
  const f = await fixture(t), moved = path.join(f.root, 'preserved-original');
  f.compare = async () => { await fs.rename(f.directory, moved); await fs.mkdir(f.directory); return { passed: true }; };
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_IO_FAILED' });
  assert.deepEqual(await fs.readFile(path.join(moved, 'source-inventory.json')), sourceBytes);
  await assert.rejects(fs.stat(path.join(f.directory, 'comparison.json')), { code: 'ENOENT' });
});
test('large safe collections are preserved within the report byte cap', async t => {
  const f = await fixture(t), pointer = '/' + Array(72).fill('rows/0').join('/'), digest = hash(sourceBytes);
  f.compare = () => ({ passed: true, differences: Array(1000).fill({ path: pointer,
    hash: digest, sha256: digest, sourceSha256: digest, destinationSha256: digest,
    count: Number.MAX_SAFE_INTEGER, sourceCount: Number.MAX_SAFE_INTEGER, destinationCount: Number.MAX_SAFE_INTEGER }),
    mappingEvidence: Array(1000).fill({ sha256: digest }) });
  const receipt = await write(f), bytes = await fs.readFile(receipt.comparisonPath);
  assert.ok(bytes.length <= 4 * 1024 * 1024); assert.equal(receipt.reportSha256, hash(bytes));
  const report = JSON.parse(bytes.toString());
  assert.equal(report.differences.length, 1000); assert.equal(report.mappingEvidence.length, 1000);
  assert.deepEqual(await fs.readFile(path.join(f.directory, 'target-inventory.json')), destinationBytes);
});
test('invalid comparator refused before directory creation', async t => {
  const f = await fixture(t); f.compare = null;
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_INPUT_INVALID' });
  await assert.rejects(fs.stat(f.directory), { code: 'ENOENT' });
});

test('complete owned-unit 2288 one-to-one mappings retain all exact IDs and identity SHAs', async t => {
  const f = await fixture(t);
  const mappings = Array.from({ length: 2288 }, (_, i) => ({ sourceId: String(i + 1), targetId: String(i + 100001),
    identitySha256: hash(Buffer.from(`owned unit mapping ${i}`)) }));
  f.compare = async () => ({ passed: true, code: 'UNIT_MAPPING_EQUAL', mappingEvidence: mappings });
  const receipt = await write(f), report = await readReport(receipt);
  assert.equal(report.mappingEvidence.length, 2288); assert.equal(report.redactedMappingCount, 0);
  assert.deepEqual(report.mappingEvidence, mappings); await assertRaw(receipt, f.sourceBytes, f.destinationBytes);
});
test('full mapping projection drops many additional private fields without touching getters', async t => {
  const f = await fixture(t), marker = 'OWNED_UNIT_PRIVATE_MAPPING'; let accessed = false;
  f.compare = () => ({ passed: true, mappingEvidence: [{ sourceId: '123', targetId: '456', identitySha256: hash(sourceBytes),
    tableName: marker, sourceValue: marker, targetValue: marker, nested: { password: marker },
    get secret() { accessed = true; throw new Error(marker); } }] });
  const r = await write(f), bytes = await fs.readFile(r.comparisonPath, 'utf8');
  assert.equal(accessed, false); assert.ok(!bytes.includes(marker));
  assert.deepEqual(JSON.parse(bytes).mappingEvidence, [{ sourceId: '123', targetId: '456', identitySha256: hash(sourceBytes) }]);
});
test('mapping positive canonical decimal ID boundaries and invalid SHA fail closed', async t => {
  const f = await fixture(t); let n = 0;
  for (const invalid of ['0', '-1', '+1', '01', '1.0', '1e2', '', '9'.repeat(31), 1, null]) {
    const options = { ...f, directory: path.join(f.root, `invalid-${n++}`), compare: () => ({ passed: true,
      mappingEvidence: [{ sourceId: invalid, targetId: '2', identitySha256: hash(sourceBytes) }] }) };
    assert.equal((await write(options)).code, 'RESTORE_EVIDENCE_REPORT_INVALID');
  }
  const good = await write({ ...f, directory: path.join(f.root, 'max-id'), compare: () => ({ passed: true,
    mappingEvidence: [{ sourceId: '9'.repeat(30), targetId: '8'.repeat(30), identitySha256: hash(sourceBytes) }] }) });
  assert.equal(good.passed, true);
  const invalidHash = await write({ ...f, directory: path.join(f.root, 'bad-sha'), compare: () => ({ passed: true,
    mappingEvidence: [{ sourceId: '1', targetId: '2', identitySha256: 'not-a-sha' }] }) });
  assert.equal(invalidHash.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
});
test('duplicate source or target IDs refuse non-one-to-one mapping', async t => {
  const f = await fixture(t), identitySha256 = hash(sourceBytes);
  for (const [name, mappings] of [['source', [{ sourceId: '1', targetId: '2', identitySha256 }, { sourceId: '1', targetId: '3', identitySha256 }]],
    ['target', [{ sourceId: '1', targetId: '2', identitySha256 }, { sourceId: '3', targetId: '2', identitySha256 }]]]) {
    const r = await write({ ...f, directory: path.join(f.root, name), compare: () => ({ passed: true, mappingEvidence: mappings }) });
    assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID'); assert.equal(r.passed, false);
  }
});
test('both source and target SHA preserved; business-valued path/values are not leaked', async t => {
  const f = await fixture(t), marker = 'OWNED_UNIT_BUSINESS_TABLE_PRIVATE';
  const diffs = [
    { path: '/schemas/0/constraints/0/SEARCH_CONDITION', sourceSha256: hash(sourceBytes), targetSha256: hash(destinationBytes), sourceValue: marker },
    { path: '/schemas/0/indexes/0/UNIQUENESS', sourceSha256: hash(sourceBytes), targetSha256: hash(destinationBytes), targetValue: marker },
    { path: `/schemas/0/rows/${marker}/count`, value: marker },
    { path: '/schemas/0/columns/0/CHAR_LENGTH', sourceSha256: hash(sourceBytes), targetSha256: hash(destinationBytes) },
    { path: '/schemas/0/constraints/0/LAST_CHANGE', count: 1 },
    { path: '/schemas/0/constraintColumns/0/POSITION', count: 2 }
  ];
  f.compare = () => ({ passed: false, differences: diffs });
  const r = await write(f), bytes = await fs.readFile(r.comparisonPath, 'utf8'), report = JSON.parse(bytes);
  assert.ok(!bytes.includes(marker)); assert.equal(report.differences.length, 5); assert.equal(report.redactedDifferenceCount, 1);
  assert.equal(report.differences[0].sourceSha256, hash(sourceBytes)); assert.equal(report.differences[0].targetSha256, hash(destinationBytes));
});
test('strict Error own-data uppercase code is preserved without error fields', async t => {
  const f = await fixture(t); f.compare = () => {
    const error = new Error('RESTORE_NATIVE_FLAG_MISMATCH'); error.details = 'OWNED_UNIT_PRIVATE_ERROR_BODY'; throw error;
  };
  const r = await write(f); assert.equal(r.code, 'RESTORE_NATIVE_FLAG_MISMATCH'); assert.equal(r.passed, false);
  assert.ok(!(await fs.readFile(r.comparisonPath, 'utf8')).includes('OWNED_UNIT_PRIVATE_ERROR_BODY'));
});
test('exception accessors, inherited/plain-object messages, long codes and prose are not copied', async t => {
  const f = await fixture(t); let getterCalled = false; let index = 0;
  const accessor = new Error(); Object.defineProperty(accessor, 'message', { get() { getterCalled = true; return 'PRIVATE_CODE'; } });
  const inherited = new Error(); delete inherited.message;
  for (const error of [accessor, inherited, { message: 'PRIVATE_CODE' }, new Error('A'.repeat(81)), new Error('CODE: private body'), new Error('')]) {
    const r = await write({ ...f, directory: path.join(f.root, `throw-${index++}`), compare: async () => { throw error; } });
    assert.equal(r.code, 'RESTORE_EVIDENCE_COMPARE_FAILED');
  }
  assert.equal(getterCalled, false);
});
test('10000 differences are kept individually, not truncated or merged', async t => {
  const f = await fixture(t), differences = Array.from({ length: 10000 }, (_, i) => ({ path: `/schemas/0/columns/${i}/STATUS`, count: i }));
  f.compare = () => ({ passed: false, code: 'UNIT_DIFFERENCES', differences });
  const report = await readReport(await write(f));
  assert.equal(report.differenceCount, 10000); assert.equal(report.redactedDifferenceCount, 0); assert.deepEqual(report.differences, differences);
});
test('10001 mappings refuse success rather than drop full mapping evidence', async t => {
  const f = await fixture(t); f.compare = () => ({ passed: true, mappingEvidence: Array(10001).fill({ sha256: hash(sourceBytes) }) });
  const r = await write(f); assert.equal(r.passed, false); assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
});
test('safe report above 4MiB throws fixed I/O error and retains raw evidence', async t => {
  const f = await fixture(t), pointer = '/' + Array(72).fill('rows/0').join('/'), digest = hash(sourceBytes);
  f.compare = () => ({ passed: true, differences: Array(10000).fill({ path: pointer, sourceSha256: digest, targetSha256: digest }) });
  await assert.rejects(write(f), { message: 'RESTORE_EVIDENCE_IO_FAILED' });
  assert.deepEqual(await fs.readFile(path.join(f.directory, 'source-inventory.json')), sourceBytes);
  assert.deepEqual(await fs.readFile(path.join(f.directory, 'target-inventory.json')), destinationBytes);
  await assert.rejects(fs.stat(path.join(f.directory, 'comparison.json')), { code: 'ENOENT' });
});

const unitTransforms = (times = []) => ({ targetIdentity: true, generatedNames: 1270, objectIds: 2288,
  selfReferenceGraphs: 4, constraintCreationTimes: times });
test('original inventory digests and all 1006 creation-time hash triples are retained', async t => {
  const f = await fixture(t), sourceInventorySha256 = hash(Buffer.from('owned unit source inventory digest')),
    targetInventorySha256 = hash(Buffer.from('owned unit target inventory digest'));
  const times = Array.from({ length: 1006 }, (_, i) => ({ identitySha256: hash(Buffer.from(`owned unit identity ${i}`)),
    sourceSha256: hash(Buffer.from(`owned unit source time ${i}`)), targetSha256: hash(Buffer.from(`owned unit target time ${i}`)) }));
  const transforms = unitTransforms(times);
  f.compare = () => ({ passed: true, sourceInventorySha256, targetInventorySha256, transforms, actualNativeRestoreProven: true });
  const r = await write(f), report = await readReport(r);
  assert.equal(report.sourceInventorySha256, sourceInventorySha256); assert.equal(report.targetInventorySha256, targetInventorySha256);
  assert.notEqual(report.sourceSha256, sourceInventorySha256); assert.notEqual(report.destinationSha256, targetInventorySha256);
  assert.deepEqual(report.transforms, transforms); assert.equal(report.transforms.constraintCreationTimes.length, 1006);
  assert.equal(report.actualNativeRestoreProven, false); assert.equal(r.actualNativeRestoreProven, false);
});
test('invalid or one-sided original inventory digests refuse success', async t => {
  const f = await fixture(t); let i = 0;
  for (const partial of [{ sourceInventorySha256: hash(sourceBytes) }, { sourceInventorySha256: 'raw body', targetInventorySha256: hash(destinationBytes) },
    { sourceInventorySha256: hash(sourceBytes), targetInventorySha256: 42 }]) {
    const r = await write({ ...f, directory: path.join(f.root, `digest-${i++}`), compare: () => ({ passed: true, ...partial }) });
    assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID'); assert.equal(r.actualNativeRestoreProven, false);
  }
});
test('transform unknown fields or time plaintext are rejected without leaking or invoking getters', async t => {
  const f = await fixture(t), marker = 'OWNED_UNIT_DATE_OR_SECRET'; let accessed = false; let i = 0;
  const validTime = { identitySha256: hash(sourceBytes), sourceSha256: hash(sourceBytes), targetSha256: hash(destinationBytes) };
  const extraTransforms = { ...unitTransforms(), get privateField() { accessed = true; return marker; } };
  for (const transforms of [extraTransforms, unitTransforms([{ ...validTime, sourceDate: marker }]),
    unitTransforms([{ ...validTime, get targetDate() { accessed = true; return marker; } }])]) {
    const r = await write({ ...f, directory: path.join(f.root, `extra-${i++}`), compare: () => ({ passed: true, transforms }) });
    assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
    assert.ok(!(await fs.readFile(r.comparisonPath, 'utf8')).includes(marker));
  }
  assert.equal(accessed, false);
});
test('transform boolean/count/hash types fail closed', async t => {
  const f = await fixture(t); let i = 0;
  const invalid = [{ ...unitTransforms(), targetIdentity: 'true' }, { ...unitTransforms(), generatedNames: -1 },
    { ...unitTransforms(), objectIds: 1.5 }, { ...unitTransforms(), selfReferenceGraphs: Number.MAX_SAFE_INTEGER + 1 },
    { ...unitTransforms(), generatedNames: '1270' }, unitTransforms([{ identitySha256: 'invalid', sourceSha256: hash(sourceBytes), targetSha256: hash(destinationBytes) }])];
  for (const transforms of invalid) {
    const r = await write({ ...f, directory: path.join(f.root, `type-${i++}`), compare: () => ({ passed: true, transforms }) });
    assert.equal(r.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
  }
});
test('10000 creation-time proofs preserved; 10001 refused without truncation', async t => {
  const f = await fixture(t), item = { identitySha256: hash(sourceBytes), sourceSha256: hash(sourceBytes), targetSha256: hash(destinationBytes) };
  const r = await write({ ...f, directory: path.join(f.root, 'times-max'), compare: () => ({ passed: true, transforms: unitTransforms(Array(10000).fill(item)) }) });
  const report = await readReport(r); assert.equal(report.transforms.constraintCreationTimes.length, 10000);
  assert.deepEqual(report.transforms.constraintCreationTimes[9999], item);
  const refused = await write({ ...f, directory: path.join(f.root, 'times-over'), compare: () => ({ passed: true, transforms: unitTransforms(Array(10001).fill(item)) }) });
  assert.equal(refused.passed, false); assert.equal(refused.code, 'RESTORE_EVIDENCE_REPORT_INVALID');
});
test('actualNativeRestoreProven false forced on every failure report and receipt', async t => {
  const f = await fixture(t); f.sourceBytes = Buffer.from('invalid unit JSON');
  const parse = await write(f); assert.equal(parse.actualNativeRestoreProven, false); assert.equal((await readReport(parse)).actualNativeRestoreProven, false);
  const thrown = await write({ ...f, sourceBytes, directory: path.join(f.root, 'throw'), compare: () => { throw new Error('UNIT_COMPARE_REFUSED'); } });
  assert.equal(thrown.actualNativeRestoreProven, false); assert.equal((await readReport(thrown)).actualNativeRestoreProven, false);
});
