import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, writeFile, rm, realpath} from 'node:fs/promises';
import {resolve, dirname, basename} from 'node:path';
import {tmpdir} from 'node:os';
import {randomBytes, createCipheriv, createDecipheriv} from 'node:crypto';
import {hash, canonical, inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {sealSnapshot} from './single-database-lifecycle-snapshot.mjs';
import {
  restoreSnapshot, validateRestoreConfiguration, restoreOriginalFiles, checkOriginalFiles,
} from './single-database-lifecycle-restore.mjs';

// Coordinator unit doubles only, NOT JDBC/DM8 recovery evidence. actualJdbc and
// DM8_NATIVE_EXPORT are required contract tags on self-owned unit data, not claims
// that a driver or dimp ran. No CLI, Docker, Maven, old package or model is used.
const schemas = ['CONPROD', 'CONAUTH', 'ENFPROD', 'ENFAUTH', 'ADJPROD', 'ADJAUTH'];
const accounts = schemas.flatMap(s => [s, `${s}_SVC`]);
const sourceTargetId = hash('owned-restore-unit-source');
const targetId = hash('owned-restore-unit-independent-destination');
const schemaCategories = ['objects', 'columns', 'constraints', 'foreignKeys', 'indexes', 'sequences', 'views', 'triggers', 'dependencies', 'rows'];
const accountCategories = ['systemPrivileges', 'roleGrants', 'tableGrants', 'columnGrants', 'effectiveGrantSubjects', 'effectiveGrants'];
const clone = value => structuredClone(value);
const zero = bytes => assert.ok(bytes.every(b => b === 0), 'owned sensitive buffer is zeroed');
const delay = ms => new Promise(r => setTimeout(r, ms));
function inventoryFor(id, populated = false, scopedAccounts = accounts) {
  const i = {
    format: 'SINGLE_DATABASE_LIVE_INVENTORY/v1', targetId: id,
    actualJdbc: true, complete: true, errors: [],
    schemas: schemas.map(schema => ({schema, ...Object.fromEntries(schemaCategories.map(k => [k, []]))})),
    accounts: scopedAccounts.map(name => ({name, exists: true, ...Object.fromEntries(accountCategories.map(k => [k, k === 'effectiveGrantSubjects' ? [name] : []]))})),
  };
  if (populated) {
    for (const s of i.schemas) {
      s.objects = [{name: 'OWNED_RECORD', type: 'TABLE'}];
      s.columns = [{table: 'OWNED_RECORD', name: 'ID', type: 'INTEGER'}];
      s.constraints = [{name: 'OWNED_PK', table: 'OWNED_RECORD', type: 'PRIMARY KEY'}];
      s.rows = [{table: 'OWNED_RECORD', count: 1, sha256: hash(`owned row ${s.schema}`)}];
    }
    for (const a of i.accounts) {
      a.systemPrivileges = ['CREATE SESSION'];
      a.tableGrants = [{schema: schemas[0], table: 'OWNED_RECORD', privilege: 'SELECT'}];
      a.effectiveGrants = clone(a.tableGrants);
    }
  }
  return i;
}
function approvalFor(id, action, until) {
  return {
    format: 'SINGLE_DATABASE_ACTION_APPROVAL/v1', targetId: id, action,
    owner: 'SELF_OWNED_UNIT_TEST', source: 'coordinator unit double only',
    approvedAt: new Date(Date.now() - 1000).toISOString(), expiresAt: until,
    currentMigration: false, connectionSwitch: false, deleteExisting: false,
  };
}
async function fixture(t, {sourceMutation, snapshotMutation, v2 = false} = {}) {
  const root = await realpath(await mkdtemp(resolve(tmpdir(), 'native-restore-unit-')));
  // Cleanup is restricted to this test's freshly allocated OS-temp directory.
  t.after(async () => {
    assert.equal(dirname(root).toLowerCase(), (await realpath(tmpdir())).toLowerCase());
    assert.ok(basename(root).startsWith('native-restore-unit-'));
    await rm(root, {recursive: true, force: true});
  });
  const until = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const originalDir = resolve(root, 'originals');
  await mkdir(originalDir);
  const originals = [];
  for (const [name, bytes] of [['evidence.txt', Buffer.from('Self-owned original evidence\n')], ['citation.bin', Buffer.from([0, 1, 9, 128, 255])]]) {
    const path = resolve(originalDir, name);
    await writeFile(path, bytes, {flag: 'wx'});
    originals.push({path, sha256: hash(bytes), licenseReference: 'SELF_OWNED_TEST_DATA_ONLY', useUntil: until});
  }
  const scopedAccounts = v2 ? [...accounts, 'CONAUTH_BAK', 'ENFAUTH_BAK', 'ADJAUTH_BAK'] : accounts;
  const writerId = hash('unit destination container writer');
  const url='jdbc:dm://unit-owned-db:5236';
  const deployment = v2 ? {schema:'SINGLE_DM8_DEPLOYMENT/v2',database:{containerId:targetId,volume:'agent-platform-unit-only',ownerTask:'IMP-SINGLE-DB-FINAL-BASELINE-01',jdbcUrl:url,memoryLimitBytes:1024,nanoCpuLimit:1000},domains:['consultation','enforcement','adjudication'].map((domain,n)=>({domain,issuer:`https://${domain}.example.test`,writerContainerIds:[hash('unit-writer-'+n)],business:{schema:schemas[n*2],jdbcUrl:url,ownerUser:schemas[n*2],runtimeUser:schemas[n*2]+'_SVC'},authority:{schema:schemas[n*2+1],jdbcUrl:url,ownerUser:schemas[n*2+1],runtimeUser:schemas[n*2+1]+'_SVC',backupUser:schemas[n*2+1]+'_BAK'}}))} : {domains:[{writerContainerIds:[writerId]}]};
  const source = inventoryFor(sourceTargetId, true, scopedAccounts);
  sourceMutation?.(source);
  const sourceApproval = {
    ...approvalFor(sourceTargetId, 'snapshot', until),
    writers: [{id: 'unit-source-writer', owner: 'UNIT', responsibilitySource: 'UNIT_ONLY'}],
    databaseRights: {owner: 'UNIT', licenseReference: 'SELF_OWNED_TEST_DATA_ONLY', purpose: 'restore coordinator unit test', useUntil: until, schemas},
    files: originals, currentAuthorityOutsideBackup: true,
    exportMethod: 'DM8_NATIVE_EXPORT', nativeExportKeyReference: 'unit-managed-native-key-v1', scope: 'SELF_OWNED_UNIT_ONLY',
  };
  snapshotMutation?.(sourceApproval);
  const secret = randomBytes(32);
  t.after(() => secret.fill(0));
  const packagePath = resolve(root, 'owned.snapshot');
  const native = Buffer.from('UNIT DOUBLE BYTES: NOT A DM8 EXPORT');
  let sealReleased = 0;
  const sealed = await sealSnapshot({
    approval: sourceApproval, inventory: async () => clone(source),
    exportDatabase: async () => native, files: originals, key: secret, output: packagePath, schemas, accounts: scopedAccounts, deployment,
    acquireFence: async () => ({assertHeld: async () => {}, release: async () => {sealReleased++;}}),
  });
  zero(native);
  assert.equal(sealReleased, 1);
  const empty = inventoryFor(targetId, false, scopedAccounts);
  const expected = {...clone(source), targetId};
  const nativeRestore = {
    user: 'RESTORE_OWNER', passwordEnv: 'UNIT_RESTORE_PASSWORD', keyEnv: 'UNIT_NATIVE_KEY',
    keyReference: 'unit-managed-native-key-v1', timeoutSeconds: 5,
    maintenanceLock: resolve(root, `single-database-maintenance-${targetId}.lock`),
    attemptMarker: resolve(root, `single-database-restore-${targetId}.json`),
  };
  const files = originals.map((f, n) => ({originalKey: f.path, relativePath: `restored/file-${n}.bin`, sha256: f.sha256, licenseReference: f.licenseReference, useUntil: until}));
  const c = {
    targetId, sourceTargetId, deployment, schemas: [...schemas], accounts: [...scopedAccounts],
    packagePath, packageSha256: sealed.sealedSha256, keyEnv: 'UNIT_PACKAGE_KEY', keyReference: 'unit-managed-package-key-v1',
    nativeRestore, files, fileRoot: resolve(root, 'new-file-root'),
    approval: {
      ...approvalFor(targetId, 'restore', until), sourceTargetId,
      configurationSha256: hash(canonical(deployment)), restoreConfigurationSha256: hash(canonical(nativeRestore)),
      packageSha256: sealed.sealedSha256, keyReference: 'unit-managed-package-key-v1',
      nativeExportKeyReference: nativeRestore.keyReference,
      databaseRights: clone(sourceApproval.databaseRights), files: clone(files),
      writerRegistryComplete: true, writerRegistryEvidence: 'unit-owned-writer-registry', maintenanceExclusivityEvidence: 'unit-owned-maintenance-exclusivity',
      writers: [
        ...deployment.domains.flatMap(d=>d.writerContainerIds).map(id=>({id,kind:'CONTAINER',owner:'UNIT',responsibilitySource:'unit-owned-writer-registry'})),
        {id: nativeRestore.maintenanceLock, kind: 'MAINTENANCE', owner: 'UNIT', responsibilitySource: 'unit-owned-maintenance-exclusivity'},
      ], emptyInventorySha256: inventoryDigest(empty),
    },
  };
  const f = {
    root, c, empty, source, expected, secret, sourceExpiresAt: sealed.metadata.expiresAt, events: [], order: [],
    releases: 0, heldChecks: 0, imports: 0, inventories: 0, fences: 0,
    importedBytes: undefined, filePayload: undefined, live: clone(empty),
  };
  const key = Buffer.from(secret);
  t.after(() => key.fill(0));
  f.args = {
    configuration: c, key,
    inventory: async () => {f.inventories++; f.order.push('inventory'); return clone(f.live);},
    acquireFence: async () => {
      f.fences++;
      return {assertHeld: async () => {f.heldChecks++;}, release: async () => {f.releases++;}};
    },
    importDatabase: async (_, bytes) => {
      f.order.push('import'); f.imports++; f.importedBytes = bytes;
      assert.equal(bytes.toString(), 'UNIT DOUBLE BYTES: NOT A DM8 EXPORT');
      assert.equal(f.events.at(-1).kind, 'IMPORT_STARTED');
      f.live = clone(expected);
    },
    restoreFiles: async (config, payload, current) => {
      f.order.push('files'); f.filePayload = payload;
      await restoreOriginalFiles(config, payload, current);
    },
    checkFiles: async config => {f.order.push('checkFiles'); await checkOriginalFiles(config);},
    journal: async event => {f.order.push(event.kind); f.events.push(clone(event));},
  };
  validateRestoreConfiguration(c);
  return f;
}
function assertClosed(f, {imports = 0, started = false, checksPassed = false} = {}) {
  assert.equal(f.imports, imports, 'no import retry within this coordinator call');
  assert.equal(f.releases, 1);
  zero(f.args.key);
  if (f.importedBytes) zero(f.importedBytes);
  if (f.filePayload) assert.ok(f.filePayload.every(p => p.bytes === ''));
  const failure = f.events.at(-1);
  assert.equal(failure.kind, 'FAILURE');
  assert.equal(failure.importStarted, started);
  assert.equal(failure.partialScenePreserved, true);
  assert.equal(failure.rollbackClaimed, false);
  assert.equal(f.events.filter(e => e.kind === 'IMPORT_STARTED').length, started ? 1 : 0);
  assert.equal(f.events.some(e => e.kind === 'RESTORE_CHECKS_PASSED'), checksPassed);
  assert.equal(f.events.some(e => e.kind === 'RESTORE_VERIFIED'), false, 'no journal event masquerades as a verified receipt');
}
async function reject(f, code, options) {
  await assert.rejects(() => restoreSnapshot(f.args), {message: code});
  assertClosed(f, options);
  assert.equal(f.events.at(-1).code, code);
}

test('unit coordinator success: nonempty six-schema inventory, exact privileges and necessary real files', async t => {
  const f = await fixture(t);
  const receipt = await restoreSnapshot(f.args);
  assert.equal(receipt.format, 'SINGLE_DATABASE_NATIVE_RESTORE_RECEIPT/v1');
  assert.equal(receipt.targetId, targetId);
  assert.equal(receipt.sourceTargetId, sourceTargetId);
  assert.equal(receipt.packageSha256, f.c.packageSha256);
  assert.equal(receipt.inventorySha256, inventoryDigest(f.expected));
  assert.equal(receipt.nativeImportCompleted, true);
  assert.equal(receipt.businessActivation, false);
  assert.equal(receipt.fullThreeDomainProductRecoveryProven, false);
  assert.ok(Date.parse(receipt.verifiedAt) <= Date.now());
  assert.deepEqual(receipt.fileDigests, f.c.files.map(({originalKey, relativePath, sha256}) => ({originalKey, relativePath, sha256})));
  assert.equal(f.imports, 1); assert.equal(f.inventories, 2); assert.equal(f.releases, 1);
  assert.deepEqual(f.order, ['ADMITTED', 'inventory', 'IMPORT_STARTED', 'import', 'IMPORT_RETURNED', 'files', 'inventory', 'checkFiles', 'RESTORE_CHECKS_PASSED']);
  for (const map of f.c.files) {
    const restored = await readFile(resolve(f.c.fileRoot, map.relativePath));
    assert.equal(hash(restored), map.sha256);
    assert.deepEqual(restored, await readFile(map.originalKey));
  }
  zero(f.args.key); zero(f.importedBytes);
  assert.ok(f.filePayload.every(p => p.bytes === ''));
});

// Configuration refusals must also clear the caller-supplied sensitive key,
// without acquiring a fence, importing or writing a journal event.
for (const [name, mutate, code] of [
  ['same source and destination', c => {c.sourceTargetId = c.targetId; c.approval.sourceTargetId = c.targetId;}, 'INDEPENDENT_EXACT_RESTORE_TARGET_REQUIRED'],
  ['wrong package key reference', c => {c.keyReference = 'different';}, 'EXACT_PACKAGE_AND_MANAGED_KEY_REQUIRED'],
  ['wrong native key reference', c => {c.nativeRestore.keyReference = 'different'; c.approval.restoreConfigurationSha256 = hash(canonical(c.nativeRestore));}, 'MANAGED_NATIVE_RESTORE_SECRETS_REQUIRED'],
  ['unapproved deployment', c => {c.deployment.domains[0].writerContainerIds[0] = hash('changed');}, 'APPROVED_RESTORE_CONFIGURATION_REQUIRED'],
  ['missing account in configured scope', c => {c.accounts.pop();}, 'EXACT_RESTORE_SCOPE_REQUIRED'],
  ['missing complete writer responsibility', c => {c.approval.writerRegistryComplete = false;}, 'COMPLETE_RESTORE_WRITER_RESPONSIBILITY_REQUIRED'],
  ['unregistered destination writer', c => {c.approval.writers[0].id = hash('unregistered');}, 'EXACT_REGISTERED_RESTORE_WRITERS_REQUIRED'],
  ['unbounded timeout', c => {c.nativeRestore.timeoutSeconds = 121; c.approval.restoreConfigurationSha256 = hash(canonical(c.nativeRestore));}, 'FINITE_RESTORE_TIMEOUT_REQUIRED'],
  ['file path traversal', c => {c.files[0].relativePath = '../escape'; c.approval.files = clone(c.files);}, 'NEW_INDEPENDENT_FILE_MAPPING_REQUIRED'],
  ['missing database rights', c => {delete c.approval.databaseRights;}, 'CURRENT_RESTORE_DATABASE_RIGHTS_REQUIRED'],
]) test(`unit pre-admission rejects ${name} without import or fence`, async t => {
  const f = await fixture(t); mutate(f.c);
  await assert.rejects(() => restoreSnapshot(f.args), {message: code});
  assert.equal(f.fences, 0); assert.equal(f.imports, 0); assert.deepEqual(f.events, []);
  zero(f.args.key);
});

for (const recompute of [false, true]) test(`unit owned package tampering fails ${recompute ? 'GCM authentication even with new approved digest' : 'approved SHA'} before writes`, async t => {
  const f = await fixture(t);
  const bytes = await readFile(f.c.packagePath); bytes[bytes.length - 1] ^= 1;
  await writeFile(f.c.packagePath, bytes);
  if (recompute) f.c.approval.packageSha256 = f.c.packageSha256 = hash(bytes);
  await reject(f, recompute ? 'PACKAGE_AUTHENTICATION_FAILED_NO_TARGET_WRITE' : 'PACKAGE_SHA_MISMATCH');
  assert.equal(f.inventories, 0);
});

test('unit wrong 32-byte package key fails authentication and zeroes key', async t => {
  const f = await fixture(t); f.args.key[0] ^= 1;
  await reject(f, 'PACKAGE_AUTHENTICATION_FAILED_NO_TARGET_WRITE');
});
for (const [name, snapshotMutation] of [
  ['native key reference', a => {a.nativeExportKeyReference = 'other-managed-native-key';}],
  ['native export method', a => {a.exportMethod = 'UNIT_NON_NATIVE';}],
  ['authority boundary', a => {a.currentAuthorityOutsideBackup = false;}],
]) test(`unit rejects authenticated source ${name} mismatch`, async t => {
  const f = await fixture(t, {snapshotMutation});
  await reject(f, 'EXACT_NATIVE_PACKAGE_AND_AUTHORITY_BOUNDARY_REQUIRED');
});


test('unit authenticated source inventory outside configured scope fails before target inventory/import', async t => {
  const f = await fixture(t);
  f.c.schemas[0] = 'OUTSIDE';
  f.c.approval.databaseRights.schemas = [...f.c.schemas];
  validateRestoreConfiguration(f.c);
  await reject(f, 'SIX_EXACT_SCHEMAS_REQUIRED');
  assert.equal(f.inventories, 0);
});

test('unit approved nonempty destination still refuses import', async t => {
  const f = await fixture(t);
  f.live = clone(f.expected);
  f.c.approval.emptyInventorySha256 = inventoryDigest(f.live);
  await reject(f, 'RESTORE_TARGET_NOT_EMPTY');
});
test('unit empty target inventory drift refuses import', async t => {
  const f = await fixture(t);
  f.live.accounts[0].systemPrivileges.push('EXTRA_PRIVILEGE');
  await reject(f, 'EMPTY_TARGET_INVENTORY_DRIFT');
});
for (const [name, change, code] of [
  ['missing account', i => {i.accounts.pop();}, 'TWELVE_EXACT_ACCOUNTS_REQUIRED'],
  ['nonexistent account', i => {i.accounts[0].exists = false;}, 'TWELVE_EXACT_ACCOUNTS_REQUIRED'],
  ['missing privilege inventory category', i => {delete i.accounts[0].effectiveGrants;}, 'ACCOUNT_PRIVILEGE_CATEGORY_MISSING'],
  ['incomplete target inventory', i => {i.complete = false;}, 'INCOMPLETE_OR_WRONG_LIVE_INVENTORY'],
]) test(`unit pre-import ${name} fails closed`, async t => {
  const f = await fixture(t); change(f.live);
  await reject(f, code);
});
for (const [name, change, code] of [
  ['missing restored account', i => {i.accounts.pop();}, 'TWELVE_EXACT_ACCOUNTS_REQUIRED'],
  ['missing restored privilege category', i => {delete i.accounts[0].tableGrants;}, 'ACCOUNT_PRIVILEGE_CATEGORY_MISSING'],
  ['lost restored privilege', i => {i.accounts[0].effectiveGrants = [];}, 'RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH'],
  ['empty restored data', i => {i.schemas[0].rows = [];}, 'RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH'],
  ['wrong restored object', i => {i.schemas[0].objects[0].name = 'OTHER';}, 'RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH'],
  ['wrong restored row SHA', i => {i.schemas[0].rows[0].sha256 = hash('changed row');}, 'RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH'],
]) test(`unit ${name} after import cannot produce success receipt`, async t => {
  const f = await fixture(t); const original = f.args.importDatabase;
  f.args.importDatabase = async (...args) => {await original(...args); change(f.live);};
  await reject(f, code, {imports: 1, started: true});
  assert.equal(hash(await readFile(resolve(f.c.fileRoot, f.c.files[0].relativePath))), f.c.files[0].sha256);
});

for (const code of ['NATIVE_IMPORT_FAILED', 'NATIVE_IMPORT_TIMEOUT']) test(`unit simulated ${code}: START before import, FAILURE, release/zero, no reissue`, async t => {
  const f = await fixture(t); const original = f.args.importDatabase;
  f.args.importDatabase = async (...args) => {
    await original(...args);
    if (code.endsWith('TIMEOUT')) await delay(5); // adapter timeout signal only; NOT a process watchdog test
    throw new Error(code);
  };
  await reject(f, code, {imports: 1, started: true});
  assert.deepEqual(f.events.map(e => e.kind), ['ADMITTED', 'IMPORT_STARTED', 'FAILURE']);
  assert.equal(f.order.indexOf('IMPORT_STARTED') < f.order.indexOf('import'), true);
  assert.equal(f.order.includes('files'), false);
});
test('unit START journal persistence failure prevents external import', async t => {
  const f = await fixture(t); const original = f.args.journal;
  f.args.journal = async event => {
    if (event.kind === 'IMPORT_STARTED') throw new Error('JOURNAL_START_FAILED');
    await original(event);
  };
  await reject(f, 'JOURNAL_START_FAILED');
  assert.deepEqual(f.events.map(e => e.kind), ['ADMITTED', 'FAILURE']);
});
test('unit caller-supplied durable START guard rejects second invocation without reimport', async t => {
  const f = await fixture(t); const original = f.args.journal;
  let startRecorded = false;
  f.args.journal = async event => {
    if (event.kind === 'ADMITTED' && startRecorded) throw new Error('PRIOR_IMPORT_REQUIRES_REVIEW');
    await original(event);
    if (event.kind === 'IMPORT_STARTED') startRecorded = true;
  };
  const importer = f.args.importDatabase;
  f.args.importDatabase = async (...args) => {await importer(...args); throw new Error('NATIVE_IMPORT_FAILED');};
  await reject(f, 'NATIVE_IMPORT_FAILED', {imports: 1, started: true});
  f.args.key = Buffer.from(f.secret);
  await assert.rejects(() => restoreSnapshot(f.args), {message: 'PRIOR_IMPORT_REQUIRES_REVIEW'});
  assert.equal(f.imports, 1); assert.equal(f.releases, 2); zero(f.args.key);
  assert.equal(f.events.filter(e => e.kind === 'IMPORT_STARTED').length, 1);
  assert.equal(f.events.at(-1).kind, 'FAILURE');
  assert.equal(f.events.at(-1).importStarted, false);
  // Cross-call durability is supplied by this journal double, not by the coordinator.
});

for (const [name, change, code] of [
  ['approval expiry', c => {c.approval.expiresAt = new Date(Date.now() - 1).toISOString();}, 'EXACT_CURRENT_ACTION_APPROVAL_REQUIRED'],
  ['database use expiry', c => {c.approval.databaseRights.useUntil = new Date(Date.now() - 1).toISOString();}, 'CURRENT_RESTORE_DATABASE_RIGHTS_REQUIRED'],
  ['original-file use expiry', c => {c.approval.files[0].useUntil = new Date(Date.now() - 1).toISOString();}, 'CURRENT_RESTORE_FILE_RIGHTS_REQUIRED'],
  ['database rights withdrawal', c => {delete c.approval.databaseRights.owner;}, 'CURRENT_RESTORE_DATABASE_RIGHTS_REQUIRED'],
]) test(`unit terminal ${name} after real file SHA check returns failure, not success`, async t => {
  const f = await fixture(t); const check = f.args.checkFiles;
  f.args.checkFiles = async c => {await check(c); change(c);};
  await reject(f, code, {imports: 1, started: true});
});
test('unit terminal real elapsed approval deadline closes after checkFiles', async t => {
  const f = await fixture(t); const check = f.args.checkFiles;
  f.args.checkFiles = async c => {
    await check(c);
    c.approval.expiresAt = new Date(Date.now() + 100).toISOString();
    await delay(150);
  };
  await reject(f, 'EXACT_CURRENT_ACTION_APPROVAL_REQUIRED', {imports: 1, started: true});
});
test('unit lost maintenance fence at terminal verification cannot return success', async t => {
  const f = await fixture(t); let revoked = false; const check = f.args.checkFiles;
  f.args.acquireFence = async () => ({
    assertHeld: async () => {if (revoked) throw new Error('RESTORE_FENCE_LOST');},
    release: async () => {f.releases++;},
  });
  f.args.checkFiles = async c => {await check(c); revoked = true;};
  await reject(f, 'RESTORE_FENCE_LOST', {imports: 1, started: true});
});
test('unit expiry during final checks-passed journal append rejects receipt and appends FAILURE', async t => {
  const f = await fixture(t); const journal = f.args.journal;
  f.args.journal = async event => {
    await journal(event);
    if (event.kind === 'RESTORE_CHECKS_PASSED') f.c.approval.expiresAt = new Date(Date.now() - 1).toISOString();
  };
  await reject(f, 'EXACT_CURRENT_ACTION_APPROVAL_REQUIRED', {imports: 1, started: true, checksPassed: true});
  assert.deepEqual(f.events.map(e => e.kind), ['ADMITTED', 'IMPORT_STARTED', 'IMPORT_RETURNED', 'RESTORE_CHECKS_PASSED', 'FAILURE']);
  // CHECKS_PASSED is an intermediate audit fact, not a success receipt.
  // The post-journal authority recheck must still fail closed.
});

test('unit missing necessary authenticated original set refuses import', async t => {
  const f = await fixture(t); f.c.files.pop(); f.c.approval.files = clone(f.c.files);
  await reject(f, 'CURRENT_SOURCE_PACKAGE_RIGHTS_REQUIRED');
});
test('unit source file license mismatch refuses import', async t => {
  const f = await fixture(t); f.c.files[0].licenseReference = 'OTHER'; f.c.approval.files = clone(f.c.files);
  await reject(f, 'EXACT_AUTHENTICATED_FILE_SET_REQUIRED');
});
test('unit production restoreOriginalFiles SHA refusal preserves already-restored partial scene and source bytes', async t => {
  const f = await fixture(t); const restore = f.args.restoreFiles;
  f.args.restoreFiles = async (c, payload, current) => {
    payload[1].bytes = Buffer.from('changed owned bytes').toString('base64');
    await restore(c, payload, current);
  };
  await reject(f, 'RESTORE_FILE_SHA_MISMATCH', {imports: 1, started: true});
  assert.equal(hash(await readFile(resolve(f.c.fileRoot, f.c.files[0].relativePath))), f.c.files[0].sha256);
  await assert.rejects(() => readFile(resolve(f.c.fileRoot, f.c.files[1].relativePath)), {code: 'ENOENT'});
  for (const map of f.c.files) assert.equal(hash(await readFile(map.originalKey)), map.sha256);
});
test('unit production checkOriginalFiles catches real restored-file corruption; partial scene not rolled back', async t => {
  const f = await fixture(t); const restore = f.args.restoreFiles;
  const corrupted = Buffer.from('owned corrupted destination');
  f.args.restoreFiles = async (...args) => {
    await restore(...args);
    await writeFile(resolve(f.c.fileRoot, f.c.files[0].relativePath), corrupted);
  };
  await reject(f, 'RESTORED_FILE_SHA_MISMATCH', {imports: 1, started: true});
  assert.deepEqual(await readFile(resolve(f.c.fileRoot, f.c.files[0].relativePath)), corrupted);
  assert.equal(hash(await readFile(f.c.files[0].originalKey)), f.c.files[0].sha256);
});
test('unit production file restoration rejects existing output root, never overwrites retained scene', async t => {
  const f = await fixture(t);
  await mkdir(f.c.fileRoot);
  const sentinel = resolve(f.c.fileRoot, 'retained-scene.txt');
  await writeFile(sentinel, 'self-owned prior scene', {flag: 'wx'});
  await assert.rejects(() => restoreSnapshot(f.args), {code: 'EEXIST'});
  assertClosed(f, {imports: 1, started: true});
  assert.equal(f.events.at(-1).code, 'RESTORE_OPERATION_FAILED');
  assert.equal(await readFile(sentinel, 'utf8'), 'self-owned prior scene');
});

test('unit extra approved necessary file mapping cannot bypass exact authenticated file set', async t => {
  const f = await fixture(t);
  f.c.files.push({...f.c.files[0], originalKey: resolve(f.root, 'extra-owned-source'), relativePath: 'restored/extra.bin'});
  f.c.approval.files = clone(f.c.files);
  await reject(f, 'EXACT_AUTHENTICATED_FILE_SET_REQUIRED');
});
test('unit own fresh source package expires at terminal check although destination approval stays current', async t => {
  const f = await fixture(t, {snapshotMutation: a => {a.expiresAt = new Date(Date.now() + 1000).toISOString();}});
  const check = f.args.checkFiles;
  f.args.checkFiles = async c => {
    await check(c);
    await delay(Math.max(0, Date.parse(f.sourceExpiresAt) - Date.now()) + 30);
  };
  await reject(f, 'CURRENT_SOURCE_PACKAGE_RIGHTS_REQUIRED', {imports: 1, started: true});
  assert.ok(Date.parse(f.c.approval.expiresAt) > Date.now());
});
for (const code of ['NATIVE_IMPORT_FAILED', 'NATIVE_IMPORT_TIMEOUT']) test(`unit FAILURE journal rejection preserves original ${code} object, releases fence and zeroes buffers`, async t => {
  const f = await fixture(t); const importer = f.args.importDatabase; const journal = f.args.journal;
  const originalError = new Error(code, {cause: new Error('owned adapter detail')});
  originalError.adapterCode = 'UNIT_ONLY';
  const journalError = new Error('JOURNAL_FAILURE_UNAVAILABLE');
  let failureAttempts = 0;
  f.args.importDatabase = async (...args) => {await importer(...args); throw originalError;};
  f.args.journal = async event => {
    if (event.kind === 'FAILURE') {
      failureAttempts++;
      assert.equal(event.code, code);
      assert.equal(event.importStarted, true);
      assert.equal(event.partialScenePreserved, true);
      assert.equal(event.rollbackClaimed, false);
      throw journalError;
    }
    await journal(event);
  };
  await assert.rejects(() => restoreSnapshot(f.args), error => {
    assert.equal(error, originalError, 'journal exception must not replace original import exception');
    assert.equal(error.message, code);
    assert.equal(error.adapterCode, 'UNIT_ONLY');
    assert.equal(error.failureJournalUnavailable, true);
    assert.equal(error.cause.message, 'owned adapter detail');
    return true;
  });
  assert.equal(failureAttempts, 1);
  assert.equal(f.imports, 1); assert.equal(f.releases, 1);
  assert.deepEqual(f.events.map(e => e.kind), ['ADMITTED', 'IMPORT_STARTED']);
  zero(f.args.key); zero(f.importedBytes);
  // Failed journal persistence is not represented as a durable FAILURE record.
});

test('unit missing restore port before admission also zeroes input key without fence or import', async t => {
  const f = await fixture(t); delete f.args.checkFiles;
  await assert.rejects(() => restoreSnapshot(f.args), {message: 'ACTUAL_RESTORE_PORTS_AND_KEY_REQUIRED'});
  zero(f.args.key);
  assert.equal(f.fences, 0); assert.equal(f.imports, 0); assert.deepEqual(f.events, []);
});



// Mutate only the just-created fixture package. Authenticate both the changed
// public metadata (AAD) and payload with its self-owned key; bind a fresh SHA
// so rejection tests the reader's semantic file-set contract, not SHA/GCM drift.
async function reauthenticateOwnedFreshPackage(f, mutate) {
  const sealed = await readFile(f.c.packagePath);
  assert.equal(hash(sealed), f.c.packageSha256);
  assert.equal(sealed.subarray(0, 8).toString(), 'XJSNAP02');
  const headerSize = sealed.readUInt32BE(8);
  const header = sealed.subarray(12, 12 + headerSize);
  const metadata = JSON.parse(header);
  assert.equal(metadata.targetId, sourceTargetId);
  assert.ok(Date.parse(metadata.expiresAt) > Date.now());
  const start = 12 + headerSize;
  const decipher = createDecipheriv('aes-256-gcm', f.secret, sealed.subarray(start, start + 12));
  decipher.setAAD(header);
  decipher.setAuthTag(sealed.subarray(start + 12, start + 28));
  let plain, changedPlain, content;
  try {
    plain = Buffer.concat([decipher.update(sealed.subarray(start + 28)), decipher.final()]);
    content = JSON.parse(plain);
    assert.equal(content.files.length, 2);
    assert.equal(metadata.fileDigests.length, 2);
    assert.deepEqual(content.files.map(v => v.originalKey), f.c.files.map(v => v.originalKey));
    mutate(content, metadata);
    changedPlain = Buffer.from(JSON.stringify(content));
    const changedHeader = Buffer.from(canonical(metadata));
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', f.secret, nonce);
    cipher.setAAD(changedHeader);
    const encrypted = Buffer.concat([cipher.update(changedPlain), cipher.final()]);
    const size = Buffer.alloc(4); size.writeUInt32BE(changedHeader.length);
    const changed = Buffer.concat([Buffer.from('XJSNAP02'), size, changedHeader, nonce, cipher.getAuthTag(), encrypted]);
    await writeFile(f.c.packagePath, changed);
    f.c.approval.packageSha256 = f.c.packageSha256 = hash(changed);
    validateRestoreConfiguration(f.c);
  } finally {
    plain?.fill(0); changedPlain?.fill(0);
    if (content) {
      content.native = '';
      for (const original of content.files) original.bytes = '';
    }
  }
}

for (const [name, mutate] of [
  ['payload repeats A and omits B with unchanged cardinality', content => {
    content.files[1] = clone(content.files[0]);
  }],
  ['metadata omits B', (_content, metadata) => {
    metadata.fileDigests.pop();
  }],
  ['metadata includes an extra unique original', (_content, metadata) => {
    metadata.fileDigests.push({...metadata.fileDigests[0], originalKey: `${metadata.fileDigests[0].originalKey}-extra-unit-only`});
  }],
  ['metadata repeats A and omits B with unchanged cardinality', (_content, metadata) => {
    metadata.fileDigests[1] = clone(metadata.fileDigests[0]);
  }],
  ['payload and metadata both repeat A and omit B', (content, metadata) => {
    content.files[1] = clone(content.files[0]);
    metadata.fileDigests[1] = clone(metadata.fileDigests[0]);
  }],
]) test(`unit fresh reauthenticated package rejects ${name} before inventory/import`, async t => {
  const f = await fixture(t);
  await reauthenticateOwnedFreshPackage(f, mutate);
  await reject(f, 'AUTHENTICATED_FILE_SET_MISMATCH');
  assert.equal(f.fences, 1);
  assert.equal(f.inventories, 0);
  assert.equal(f.importedBytes, undefined, 'no native bytes reach import double');
  assert.equal(f.filePayload, undefined, 'no plaintext originals reach file restoration');
  assert.deepEqual(f.events.map(e => e.kind), ['ADMITTED', 'FAILURE']);
  await assert.rejects(() => realpath(f.c.fileRoot), {code: 'ENOENT'});
});

test('unit release failure does not obscure original import failure or bypass sensitive cleanup',async t=>{
 const f=await fixture(t),acquire=f.args.acquireFence,original=new Error('NATIVE_IMPORT_FAILED');
 f.args.acquireFence=async (...args)=>{const lease=await acquire(...args),release=lease.release;lease.release=async()=>{await release();throw new Error('RELEASE_FAILED');};return lease;};
 f.args.importDatabase=async()=>{throw original;};
 await assert.rejects(()=>restoreSnapshot(f.args),e=>e===original&&e.fenceReleaseUnavailable===true&&e.fenceReleaseCause.message==='RELEASE_FAILED');
 zero(f.args.key);assert.equal(f.releases,1);assert.equal(f.events.at(-1).kind,'FAILURE');
});

test('v2 15-account snapshot through restore coordinator preserves all account grants and files (unit ports only)',async t=>{const f=await fixture(t,{v2:true});const receipt=await restoreSnapshot(f.args);assert.equal(f.live.accounts.length,15);assert.equal(f.imports,1);assert.equal(receipt.fullThreeDomainProductRecoveryProven,false);assert.equal(receipt.inventorySha256,inventoryDigest(f.expected));});
for(const [name,change] of [['12-account downgrade',c=>c.accounts=c.accounts.filter(a=>!a.endsWith('_BAK'))],['schema substitution',c=>c.schemas[0]='OTHER_SCHEMA'],['backup substitution',c=>c.accounts[c.accounts.indexOf('CONAUTH_BAK')]='OTHER_BAK']])test('v2 restore rejects '+name+' before fence or import',async t=>{const f=await fixture(t,{v2:true});change(f.c);await assert.rejects(restoreSnapshot(f.args),/EXACT_RESTORE_SCOPE/);assert.equal(f.fences,0);assert.equal(f.imports,0);zero(f.args.key);});

test('v2 protection rejects 12-account downgrade before native export (unit ports only)',async t=>{const f=await fixture(t,{v2:true});let exports=0,releases=0;const key=randomBytes(32);t.after(()=>key.fill(0));await assert.rejects(sealSnapshot({approval:{...approvalFor(sourceTargetId,'snapshot',f.c.approval.expiresAt),writers:[{id:'UNIT',owner:'UNIT',responsibilitySource:'UNIT'}],databaseRights:f.c.approval.databaseRights,files:[]},inventory:async()=>f.source,exportDatabase:async()=>{exports++;return Buffer.from('NOT_DM8');},files:[],key,output:resolve(f.root,'forbidden.snapshot'),schemas,accounts, deployment:f.c.deployment,acquireFence:async()=>({assertHeld:async()=>{},release:async()=>{releases++;}})}),/FIFTEEN_ACCOUNT_DEPLOYMENT_REQUIRED/);assert.equal(exports,0);assert.equal(releases,1);});

// A planned file cannot also be another planned file's parent directory.
for (const [name, paths] of [
  ['file before its descendant', ['files/archive', 'files/archive/item.bin']],
  ['descendant before its ancestor file', ['files/archive/item.bin', 'files/archive']],
  ...(process.platform === 'win32' ? [
    ['Windows case-equivalent files', ['Files/Evidence.bin', 'files/evidence.BIN']],
    ['Windows case-equivalent file ancestor', ['Files/Archive', 'files/archive/item.bin']],
  ] : []),
]) test(`unit file-tree preflight rejects ${name} before any external port`, async t => {
  const f = await fixture(t);
  f.c.files.forEach((file, n) => {file.relativePath = paths[n];});
  f.c.approval.files = clone(f.c.files);
  let caught;
  await assert.rejects(() => restoreSnapshot(f.args), error => {caught = error; return true;});
  assert.deepEqual({fences: f.fences, inventories: f.inventories, imports: f.imports, events: f.events.length},
    {fences: 0, inventories: 0, imports: 0, events: 0}, 'invalid file trees must not reach import or filesystem ports');
  assert.equal(caught.message, 'EXACT_RESTORE_FILE_MAPPING_REQUIRED');
  await assert.rejects(() => realpath(f.c.fileRoot), {code: 'ENOENT'});
  zero(f.args.key);
});
for (const name of (process.platform === 'win32' ? ['evidence.', 'CON', 'aux.txt', 'LPT1.bin'] : [])) {
  test(`unit Windows file mapping refuses ambiguous device or trailing-dot ${name}`, async t => {
    const f = await fixture(t);
    f.c.files[0].relativePath = `files/${name}`;
    f.c.approval.files = clone(f.c.files);
    await assert.rejects(() => restoreSnapshot(f.args), {message: 'NEW_INDEPENDENT_FILE_MAPPING_REQUIRED'});
    assert.equal(f.fences, 0); assert.equal(f.imports, 0); assert.equal(f.inventories, 0);
    assert.deepEqual(f.events, []); zero(f.args.key);
    await assert.rejects(() => realpath(f.c.fileRoot), {code: 'ENOENT'});
  });
}
for (const [name, paths] of [
  ['non-ancestor string prefix', ['files/archive', 'files/archive2/item.bin']],
  ['siblings in shared directory', ['files/evidence.bin', 'files/citation.bin']],
  ...(process.platform !== 'win32' ? [['POSIX different case targets', ['Files/Evidence.bin', 'files/evidence.bin']]] : []),
]) test(`unit file-tree preflight preserves ${name} and original bytes`, async t => {
  const f = await fixture(t);
  f.c.files.forEach((file, n) => {file.relativePath = paths[n];});
  f.c.approval.files = clone(f.c.files);
  const result = await restoreSnapshot(f.args);
  assert.equal(result.nativeImportCompleted, true); assert.equal(f.imports, 1);
  for (const map of f.c.files) {
    assert.deepEqual(await readFile(resolve(f.c.fileRoot, map.relativePath)), await readFile(map.originalKey));
  }
  zero(f.args.key);
});

for(const requested of [null,{}, {profileId:'DM8_8_1_4_170_RESTORE_MINIMAL_01'}])test('new restore profile request cannot fall through to legacy comparator '+JSON.stringify(requested),async t=>{
 const f=await fixture(t);f.c.recoveryCatalog=requested;await assert.rejects(()=>restoreSnapshot(f.args),{message:'EXACT_RECOVERY_CATALOG_CONFIGURATION_REQUIRED'});assert.equal(f.fences,0);assert.equal(f.imports,0);assert.equal(f.inventories,0);assert.deepEqual(f.events,[]);zero(f.args.key);
});
for(const field of ['metadataCatalog','payloadOrigin','inventoryCatalog'])test('authenticated nullable profile marker cannot bypass pre-import gate: '+field,async t=>{
 const f=await fixture(t);await reauthenticateOwnedFreshPackage(f,(content,metadata)=>{if(field==='metadataCatalog')metadata.recoveryCatalog=null;else if(field==='payloadOrigin')content.recoveryOrigin=null;else content.inventory.restoreCatalog=null;});
 await reject(f,'EXACT_RECOVERY_CATALOG_CONFIGURATION_REQUIRED');assert.equal(f.inventories,0);assert.equal(f.order.includes('files'),false);
});
