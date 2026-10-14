import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createSingleDatabaseMigrationPlan as plan } from './single-database-migration-plan.mjs';
import { main } from '../prepare-single-database-migrations.mjs';
const sha = b => createHash('sha256').update(b).digest('hex');
const targetId = '1'.repeat(64);
const ev = { reference: 'evidence/object-inspection-01', sha256: '2'.repeat(64) };
const sql = 'CREATE TABLE ITEM (ID INT);\n';
function fixture(sqlPath = 'db/dm8/V001__test.sql', sqlSha = sha(sql)) {
  const domains = ['consultation', 'enforcement', 'adjudication'].map((domain, n) => ({ domain, businessSchema: `D${n}_BIZ`, authoritySchema: `D${n}_AUTH` }));
  const inventory = domains.flatMap(d => [d.businessSchema, d.authoritySchema].map(schema => ({ schema, targetId, objects: [], evidence: { ...ev } })));
  const migrations = domains.flatMap(d => [['business', d.businessSchema], ['authority', d.authoritySchema]].map(([kind, schema]) => ({ schema, id: 'V001', domain: d.domain, kind, sqlPath, sha256: sqlSha, dependsOn: [], objects: ['ITEM'] })));
  return { format: 'SINGLE_DM8_MIGRATION_INPUT/v1', targetId, domains, inventory, migrations, applied: [] };
}
async function temporary(t) {
  const root = await mkdtemp(path.join(tmpdir(), 'single-db-forward-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'db/dm8'), { recursive: true });
  await writeFile(path.join(root, 'db/dm8/V001__test.sql'), sql);
  return root;
}
const opts = root => ({ workspaceRoot: root, expectedTargetId: targetId });
function applyFirst(x) {
  const m = x.migrations[0]; x.inventory[0].objects = [...m.objects];
  x.applied.push({ schema: m.schema, id: m.id, targetId, sha256: m.sha256, verifiedObjects: [...m.objects], objectVerification: { ...ev } });
}
test('six schemas, repeated IDs across schemas, actual SQL bytes and topology', async t => {
  const root = await temporary(t), x = fixture();
  x.migrations[0].dependsOn = [{ schema: x.migrations[5].schema, id: 'V001' }];
  const p = await plan(x, opts(root));
  assert.equal(p.pending.length, 6);
  assert.equal(p.pending[0].schema, x.migrations[5].schema);
  for (const k of ['migrationAuthorized', 'migrationExecuted', 'fullRecoveryProven', 'professionalApprovalVerified', 'operationsApprovalVerified']) assert.equal(p[k], false);
});
test('applied is verified and skipped; nonempty forward step with precheck/review', async t => {
  const root = await temporary(t), x = fixture(); applyFirst(x);
  x.migrations.push({ ...x.migrations[0], id: 'V002', objects: ['NEXT_ITEM'], dependsOn: [{ schema: x.migrations[0].schema, id: 'V001' }], prechecks: [{ object: 'ITEM', expected: 'present', evidence: { ...ev } }, { object: 'NEXT_ITEM', expected: 'absent', evidence: { ...ev } }], nonDestructiveReview: { ...ev } });
  const p = await plan(x, opts(root));
  assert.equal(p.skippedApplied.length, 1); assert.equal(p.pending.length, 6);
  assert.ok(!p.pending.some(m => m.schema === x.migrations[0].schema && m.id === 'V001'));
});
const negatives = [
  ['duplicate schema+id', x => x.migrations.push(structuredClone(x.migrations[0])), 'DUPLICATE_MIGRATION'],
  ['wrong domain binding', x => x.migrations[0].domain = 'enforcement', 'MIGRATION_BINDING_MISMATCH'],
  ['wrong schema role', x => x.migrations[0].kind = 'authority', 'MIGRATION_BINDING_MISMATCH'],
  ['duplicate schema', x => x.domains[1].businessSchema = x.domains[0].businessSchema, 'DUPLICATE_SCHEMA'],
  ['unknown domain', x => x.domains[0].domain = 'other', 'UNKNOWN_DOMAIN'],
  ['target mismatch', x => x.targetId = '3'.repeat(64), 'TARGET_ID_MISMATCH'],
  ['inventory target mismatch', x => x.inventory[0].targetId = '3'.repeat(64), 'INVENTORY_TARGET_MISMATCH'],
  ['missing inventory', x => x.inventory.pop(), 'SIX_INVENTORIES_REQUIRED'],
  ['missing inventory evidence', x => delete x.inventory[0].evidence, 'INVALID_FIELDS'],
  ['unknown dependency', x => x.migrations[0].dependsOn = [{ schema: 'UNKNOWN', id: 'V1' }], 'UNKNOWN_DEPENDENCY'],
  ['cycle', x => x.migrations[0].dependsOn = [{ schema: x.migrations[0].schema, id: 'V001' }], 'DEPENDENCY_CYCLE'],
  ['applied dependency missing', x => { applyFirst(x); x.migrations[0].dependsOn = [{ schema: x.migrations[1].schema, id: 'V001' }]; }, 'APPLIED_DEPENDENCY_NOT_APPLIED'],
  ['unknown object', x => x.inventory[0].objects = ['ALIEN'], 'UNKNOWN_OBJECT'],
  ['existing objects no migration log', x => x.inventory[0].objects = ['ITEM'], 'UNLOGGED_EXISTING_OBJECT'],
  ['unknown migration', x => { applyFirst(x); x.applied[0].id = 'UNKNOWN'; }, 'UNKNOWN_APPLIED_MIGRATION'],
  ['conflicting applied SHA', x => { applyFirst(x); x.applied[0].sha256 = '4'.repeat(64); }, 'APPLIED_SHA_CONFLICT'],
  ['missing object verification', x => { applyFirst(x); delete x.applied[0].objectVerification; }, 'INVALID_FIELDS'],
  ['wrong verified objects', x => { applyFirst(x); x.applied[0].verifiedObjects = ['OTHER']; }, 'APPLIED_OBJECT_VERIFICATION_MISMATCH'],
  ['applied objects absent', x => { applyFirst(x); x.inventory[0].objects = []; }, 'APPLIED_OBJECT_VERIFICATION_MISMATCH'],
  ['duplicate applied', x => { applyFirst(x); x.applied.push(structuredClone(x.applied[0])); }, 'DUPLICATE_APPLIED_RECORD'],
  ['SQL SHA mismatch', x => x.migrations[0].sha256 = '4'.repeat(64), 'SQL_SHA_MISMATCH'],
  ['outside SQL', x => x.migrations[0].sqlPath = 'docs/Goal.sql', 'EXACT_DM8_SQL_PATH_REQUIRED'],
  ['reverse SQL never planned', x => x.migrations[0].sqlPath = 'db/dm8/U001__consultation_mvp_v0.sql', 'EXACT_DM8_SQL_PATH_REQUIRED'],
  ['SQL traversal', x => x.migrations[0].sqlPath = 'db/dm8/../Goal.sql', 'EXACT_DM8_SQL_PATH_REQUIRED'],
  ['credential field rejected', x => x.password = 'not-read', 'INVALID_FIELDS'],
  ['review hash invalid', x => x.migrations[0].nonDestructiveReview = { reference: 'evidence/review', sha256: 'invalid' }, 'INVALID_SHA256'],
  ['failed precheck', x => x.migrations[0].prechecks = [{ object: 'ITEM', expected: 'present', evidence: ev }], 'PRECHECK_FAILED'],
  ['nonempty no review', x => { applyFirst(x); x.migrations.push({ ...x.migrations[0], id: 'V002', objects: ['NEXT_ITEM'] }); }, 'NONEMPTY_SCHEMA_EVIDENCE_REQUIRED'],
  ['review alone no precheck', x => { applyFirst(x); x.migrations.push({ ...x.migrations[0], id: 'V002', objects: ['NEXT_ITEM'], nonDestructiveReview: ev }); }, 'NONEMPTY_SCHEMA_EVIDENCE_REQUIRED'],
  ['precheck alone no review', x => { applyFirst(x); x.migrations.push({ ...x.migrations[0], id: 'V002', objects: ['NEXT_ITEM'], prechecks: [{ object: 'ITEM', expected: 'present', evidence: ev }] }); }, 'NONEMPTY_SCHEMA_EVIDENCE_REQUIRED'],
];
for (const [name, mutate, code] of negatives) test(`reject: ${name}`, async t => {
  const root = await temporary(t), x = fixture(); mutate(x);
  await assert.rejects(plan(x, opts(root)), e => e.message === code);
});
test('SQL symlink refused', async t => {
  const root = await temporary(t);
  try { await symlink(path.join(root, 'db/dm8/V001__test.sql'), path.join(root, 'db/dm8/V002__link.sql')); }
  catch (e) { if (e.code === 'EPERM') { t.skip('Windows account lacks symlink privilege'); return; } throw e; }
  await assert.rejects(plan(fixture('db/dm8/V002__link.sql'), opts(root)), /SQL_SYMLINK_REJECTED/);
});
test('CLI real workspace SQL, tmpdir output, never overwrite, no output on failure', async t => {
  const tmp = await temporary(t);
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const sqlPath = 'db/dm8/V103__comparison_start_fence_presence.sql';
  const x = fixture(sqlPath, sha(await readFile(path.join(root, sqlPath))));
  const input = path.join(tmp, 'metadata.json'), output = path.join(tmp, 'manifest.json');
  await writeFile(input, JSON.stringify(x));
  const args = ['--input', input, '--output', output, '--target-id', targetId];
  await main(args); const before = await readFile(output);
  await assert.rejects(main(args), e => e.code === 'EEXIST');
  assert.deepEqual(await readFile(output), before);
  x.migrations[0].sha256 = '4'.repeat(64); await writeFile(input, JSON.stringify(x));
  const rejected = path.join(tmp, 'rejected.json');
  await assert.rejects(main(['--input', input, '--output', rejected, '--target-id', targetId]), /SQL_SHA_MISMATCH/);
  await assert.rejects(readFile(rejected), e => e.code === 'ENOENT');
});

test('forward ALTER on logged object allowed only with its present check and review', async t => {
  const root = await temporary(t), x = fixture(); applyFirst(x);
  const alter = 'ALTER TABLE ITEM ADD LABEL VARCHAR(80);\n';
  await writeFile(path.join(root, 'db/dm8/V002__alter.sql'), alter);
  x.migrations.push({ ...x.migrations[0], id: 'V002', sqlPath: 'db/dm8/V002__alter.sql', sha256: sha(alter),
    dependsOn: [{ schema: x.migrations[0].schema, id: 'V001' }],
    prechecks: [{ object: 'ITEM', expected: 'present', evidence: ev }], nonDestructiveReview: ev });
  const p = await plan(x, opts(root));
  assert.equal(p.pending.at(-1).id, 'V002'); assert.equal(p.skippedApplied.length, 1);
  x.migrations.at(-1).prechecks = [{ object: 'OTHER', expected: 'absent', evidence: ev }];
  await assert.rejects(plan(x, opts(root)), /EXISTING_OBJECT_PRECHECK_REQUIRED/);
});

test('CLI rejects Goal and credential inputs before parsing', async t => {
  const tmp = await temporary(t);
  for (const name of ['Goal.json', 'credential.json']) {
    const input = path.join(tmp, name); await writeFile(input, 'not even JSON');
    await assert.rejects(main(['--input', input, '--output', path.join(tmp, 'new.json'), '--target-id', targetId]), /METADATA_ONLY_INPUT_REQUIRED/);
  }
});
