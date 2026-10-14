import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { canonical, hash, inventoryDigest, foreignKeyDependencies } from './single-database-lifecycle-runtime.mjs';
import { sealSnapshot } from './single-database-lifecycle-snapshot.mjs';
import { restoreSnapshot } from './single-database-lifecycle-restore.mjs';
import { compareRestoreInventories } from './single-database-restore-comparison.mjs';
import { writeRestoreComparisonEvidence } from './single-database-restore-evidence.mjs';

// Production orchestration/authentication/comparison code with owned unit ports ONLY.
// The native bytes below are not a DM8 export/import, and no Docker/JDBC process is invoked.
const workspace = await fs.realpath(path.resolve('.'));
const evidenceRoot = path.join(workspace, 'docs/evidence/single-database-installation-20261009/approved-execution/restore-profile51');
const originalBytes = await fs.readFile(path.join(evidenceRoot, 'historical-lossless-unit-input51.json'));
const originalHash = hash(originalBytes);
const historical = JSON.parse(originalBytes.toString('utf8'));
delete historical.unitEvidenceScope; // In-memory metadata projection; original file remains untouched.
const approved = JSON.parse(await fs.readFile(path.join(evidenceRoot, 'readonly-profile-config51.json'), 'utf8'));
const clone = value => structuredClone(value);
const targetId = hash('OWNED PROFILE ADMISSION UNIT INDEPENDENT TARGET');
const nativeText = 'OWNED UNIT NATIVE BUFFER: NOT DM8 EXPORT OR DIMP';
function refresh(inventory) {
  if (Object.hasOwn(inventory, 'inventorySha256')) inventory.inventorySha256 = inventoryDigest(inventory);
  if (Object.hasOwn(inventory, 'foreignKeyDependencies')) inventory.foreignKeyDependencies = foreignKeyDependencies(inventory);
  if (Object.hasOwn(inventory, 'restoreCatalogSha256')) inventory.restoreCatalogSha256 = hash(canonical(inventory.restoreCatalog));
  return inventory;
}
function approvalFor(id, action, until) {
  return { format: 'SINGLE_DATABASE_ACTION_APPROVAL/v1', targetId: id, action, owner: 'OWNED_UNIT',
    source: 'Explicitly owned fixture; production code exercise, not real restoration',
    approvedAt: new Date(Date.now() - 1000).toISOString(), expiresAt: until,
    currentMigration: false, connectionSwitch: false, deleteExisting: false };
}
async function fixture(t, { legacy = false, sourceChange } = {}) {
  const root = await fs.mkdtemp(path.join(workspace, '.restore-profile-admission-unit-'));
  t.after(async () => {
    assert.equal(path.dirname(path.resolve(root)), workspace);
    assert.ok(path.basename(root).startsWith('.restore-profile-admission-unit-'));
    assert.equal(await fs.realpath(root), root);
    assert.equal((await fs.lstat(root)).isSymbolicLink(), false);
    await fs.rm(root, { recursive: true, force: true });
    assert.equal(hash(await fs.readFile(path.join(evidenceRoot, 'historical-lossless-unit-input51.json'))), originalHash);
  });
  const until = new Date(Date.now() + 3600000).toISOString();
  const source = clone(historical); sourceChange?.(source); refresh(source);
  if (legacy) { delete source.restoreCatalog; delete source.restoreCatalogSha256; delete source.fullNativeForeignKeys; }
  const recoveryCatalog = clone(approved.recoveryCatalog);
  const deployment = clone(approved.deployment); deployment.database.containerId = targetId;
  deployment.domains.forEach((domain, i) => { domain.writerContainerIds = [hash(`OWNED UNIT PROFILE WRITER ${i}`)]; });
  const schemas = [...approved.schemas], accounts = [...approved.accounts];
  assert.equal(accounts.length, 15); assert.equal(schemas.length, 6);
  const sourceApproval = { ...approvalFor(source.targetId, 'snapshot', until),
    writers: [{ id: hash('OWNED UNIT SOURCE WRITER'), owner: 'OWNED_UNIT', responsibilitySource: 'OWNED_UNIT_ONLY' }],
    databaseRights: { owner: 'OWNED_UNIT', licenseReference: 'OWNED_HISTORICAL_METADATA_PROJECTION',
      purpose: 'owned orchestration admission test', useUntil: until, schemas },
    files: [], currentAuthorityOutsideBackup: true, exportMethod: 'DM8_NATIVE_EXPORT',
    nativeExportKeyReference: 'unit-native-key', scope: 'OWNED_UNIT_ONLY',
    ...(!legacy ? { recoveryCatalogConfigurationSha256: hash(canonical(recoveryCatalog)) } : {}) };
  const key = randomBytes(32); t.after(() => key.fill(0));
  const packagePath = path.join(root, 'owned.snapshot');
  const state = { imports: 0, fences: 0, releases: 0, events: [], sealReleases: 0, rawCalls: 0, rawReceipts: [] };
  const sourceDeployment = clone(deployment); sourceDeployment.database.containerId = source.targetId;
  const sealed = await sealSnapshot({ approval: sourceApproval, inventory: async () => clone(source),
    exportDatabase: async () => Buffer.from(nativeText), files: [], key, output: packagePath,
    schemas, accounts, deployment: sourceDeployment, ...(!legacy ? { recoveryCatalog } : {}),
    acquireFence: async () => ({ assertHeld: async () => {}, release: async () => { state.sealReleases++; } }) });
  assert.equal(state.sealReleases, 1);
  assert.equal(sealed.metadata.nativeExportKeyReference, 'unit-native-key');
  assert.equal(Boolean(sealed.metadata.recoveryCatalog), !legacy);
  const empty = clone(historical); empty.targetId = targetId;
  for (const schema of empty.schemas) for (const key of Object.keys(schema))
    if (Array.isArray(schema[key]) && key !== 'nativeSchemaObjects') schema[key] = [];
  for (const account of empty.accounts) for (const key of Object.keys(account))
    if (Array.isArray(account[key])) account[key] = [];
  for (const [name, category] of Object.entries(empty.restoreCatalog.categories)) {
    if (name === 'viewDefinitions') continue; // Retain complete installed view bodies and column metadata.
    category.rows = name === 'hiddenDependencyCount' || name === 'hiddenObjectInfoCount' ? [{ ROW_COUNT: '0' }] : [];
  }
  refresh(empty);
  const expected = refresh({ ...clone(historical), targetId });
  const nativeRestore = { user: 'RESTORE_OWNER', passwordEnv: 'UNIT_PROFILE_PASSWORD', keyEnv: 'UNIT_NATIVE_KEY',
    keyReference: 'unit-native-key', timeoutSeconds: 5,
    maintenanceLock: path.join(root, `single-database-maintenance-${targetId}.lock`),
    attemptMarker: path.join(root, `single-database-restore-${targetId}.json`) };
  const recoveryComparisonDirectory = path.join(root, 'comparison-evidence');
  const configuration = { targetId, sourceTargetId: source.targetId, deployment, schemas, accounts,
    packagePath, packageSha256: sealed.sealedSha256, keyEnv: 'UNIT_PACKAGE_KEY', keyReference: 'unit-package-key',
    nativeRestore, files: [], fileRoot: path.join(root, 'restored-files'), recoveryCatalog, recoveryComparisonDirectory,
    approval: { ...approvalFor(targetId, 'restore', until), sourceTargetId: source.targetId,
      configurationSha256: hash(canonical(deployment)), restoreConfigurationSha256: hash(canonical(nativeRestore)),
      packageSha256: sealed.sealedSha256, keyReference: 'unit-package-key', nativeExportKeyReference: 'unit-native-key',
      databaseRights: clone(sourceApproval.databaseRights), files: [],
      writerRegistryComplete: true, writerRegistryEvidence: 'OWNED_UNIT_REGISTRY', maintenanceExclusivityEvidence: 'OWNED_UNIT_EXCLUSIVITY',
      writers: [...deployment.domains.flatMap(d => d.writerContainerIds).map(id => ({ id, kind: 'CONTAINER', owner: 'OWNED_UNIT', responsibilitySource: 'OWNED_UNIT_REGISTRY' })),
        { id: nativeRestore.maintenanceLock, kind: 'MAINTENANCE', owner: 'OWNED_UNIT', responsibilitySource: 'OWNED_UNIT_EXCLUSIVITY' }],
      emptyInventorySha256: inventoryDigest(empty), recoveryCatalogConfigurationSha256: hash(canonical(recoveryCatalog)), recoveryComparisonDirectory } };
  state.live = clone(empty);
  const ports = { configuration, key: Buffer.from(key), inventory: async () => clone(state.live),
    acquireFence: async () => { state.fences++; return { assertHeld: async () => {}, release: async () => { state.releases++; } }; },
    importDatabase: async (_c, bytes, metadata) => {
      state.imports++; assert.equal(bytes.toString(), nativeText); assert.equal(metadata.nativeExportKeyReference, 'unit-native-key');
      assert.equal(state.events.at(-1).kind, 'IMPORT_STARTED');
      state.live = clone(expected); state.afterImportChange?.(state.live); refresh(state.live);
    },
    restoreFiles: async (_c, files) => { assert.deepEqual(files, []); }, checkFiles: async () => {},
    journal: async event => { state.events.push(clone(event)); },
    readInventoryBytes: async inventory => Buffer.from(JSON.stringify(inventory ?? state.live) + '\n'),
    writeComparisonEvidence: async ({ sourceBytes, destinationBytes, origin, forceFailureCode }) => {
      state.rawCalls++;
      const receipt = await writeRestoreComparisonEvidence({ directory: recoveryComparisonDirectory, sourceBytes, destinationBytes,
        compare: ({ source, destination }) => {
          if (forceFailureCode) throw new Error(forceFailureCode);
          return compareRestoreInventories({ source, destination, origin, sourceTargetId: configuration.sourceTargetId,
            targetId: configuration.targetId, schemas, accounts, deployment });
        } });
      state.rawReceipts.push(receipt); return receipt;
    } };
  t.after(() => ports.key.fill(0));
  return { state, ports, configuration, source, empty, expected, root };
}
const importStarts = state => state.events.filter(e => e.kind === 'IMPORT_STARTED').length;
async function rawReport(f) {
  const receipt = f.state.rawReceipts.at(-1); assert.ok(receipt?.complete);
  const source = await fs.readFile(receipt.sourcePath), destination = await fs.readFile(receipt.destinationPath), bytes = await fs.readFile(receipt.comparisonPath);
  assert.equal(hash(source), receipt.sourceSha256); assert.equal(hash(destination), receipt.destinationSha256);
  assert.equal(hash(bytes), receipt.reportSha256);
  return { report: JSON.parse(bytes), source: JSON.parse(source), destination: JSON.parse(destination) };
}

test('production seal + orchestrator + raw evidence + comparator succeed with owned native double, not dimp', async t => {
  const f = await fixture(t), receipt = await restoreSnapshot(f.ports), saved = await rawReport(f);
  assert.equal(f.state.imports, 1); assert.equal(importStarts(f.state), 1); assert.equal(f.state.releases, 1);
  assert.equal(receipt.fullThreeDomainProductRecoveryProven, false); assert.equal(receipt.businessActivation, false);
  assert.equal(saved.report.passed, true); assert.equal(saved.report.actualNativeRestoreProven, false);
  assert.equal(saved.report.mappingEvidence.length, 2288); assert.equal(saved.report.transforms.selfReferenceGraphs, 4);
  assert.equal(saved.report.sourceInventorySha256, inventoryDigest(f.source));
  assert.equal(saved.report.targetInventorySha256, inventoryDigest(f.expected));
  assert.equal(receipt.comparisonReportSha256, hash(await fs.readFile(f.state.rawReceipts[0].comparisonPath)));
  assert.deepEqual(saved.destination, f.expected);
});
for (const kind of ['data', 'permissions']) test(`${kind} difference preserves raw failure after exactly one IMPORT_STARTED, no automatic retry`, async t => {
  const f = await fixture(t);
  f.state.afterImportChange = kind === 'data'
    ? live => { live.schemas.find(s => s.rows.length).rows[0].contentSha256 = hash('OWNED UNIT DATA DIFF'); }
    : live => { const grant = live.accounts.find(a => a.tableGrants.length).tableGrants[0]; grant.GRANTABLE = grant.GRANTABLE === 'Y' ? 'N' : 'Y'; };
  await assert.rejects(restoreSnapshot(f.ports), { message: 'RESTORE_SEMANTIC_DIFFERENCES' });
  assert.equal(f.state.imports, 1); assert.equal(importStarts(f.state), 1); assert.equal(f.state.rawCalls, 1);
  const saved = await rawReport(f); assert.equal(saved.report.passed, false); assert.ok(saved.report.differences.length > 0);
  assert.equal(saved.report.actualNativeRestoreProven, false);
  const failure = f.state.events.find(e => e.kind === 'FAILURE');
  assert.equal(failure.importStarted, true); assert.equal(failure.partialScenePreserved, true); assert.equal(failure.rollbackClaimed, false);
});
test('authenticated profile package cannot fall back when recovery configuration is absent', async t => {
  const f = await fixture(t); delete f.configuration.recoveryCatalog;
  await assert.rejects(restoreSnapshot(f.ports), { message: 'EXACT_RECOVERY_CATALOG_CONFIGURATION_REQUIRED' });
  assert.equal(f.state.imports, 0); assert.equal(importStarts(f.state), 0);
});
test('profile approval/configuration pins must match before fence and import', async t => {
  const f = await fixture(t); delete f.configuration.approval.recoveryCatalogConfigurationSha256;
  await assert.rejects(restoreSnapshot(f.ports), { message: 'APPROVED_RECOVERY_COMPARISON_CONFIGURATION_REQUIRED' });
  assert.equal(f.state.fences, 0); assert.equal(f.state.imports, 0);
});
test('source version/view drift is refused at sealing; target view drift preserves failed raw before import', async t => {
  await assert.rejects(fixture(t, { sourceChange: source => { source.restoreCatalog.databaseVersion = '8.1.4.171'; } }), /PROFILE_OR_VERSION/);
  await assert.rejects(fixture(t, { sourceChange: source => { source.restoreCatalog.categories.viewDefinitions.rows.find(v => v.VIEW_NAME === 'DBA_TABLES').TEXT += ' /* OWNED UNIT VIEW DRIFT */'; } }), { message: 'RESTORE_CATALOG_VIEW_DEFINITION_DRIFT' });
  const targetView = await fixture(t); targetView.state.live.restoreCatalog.categories.viewDefinitions.rows[0].TEXT += ' /* OWNED UNIT TARGET DRIFT */';
  await assert.rejects(restoreSnapshot(targetView.ports), { message: 'RESTORE_CATALOG_VIEW_DEFINITION_DRIFT' });
  assert.equal(targetView.state.imports, 0); assert.equal(importStarts(targetView.state), 0);
  assert.equal(targetView.state.rawCalls, 1);
  const saved = await rawReport(targetView);
  assert.equal(saved.report.passed, false);
  assert.equal(saved.report.code, 'RESTORE_OPERATION_FAILED_BEFORE_POST_CAPTURE');
  assert.equal(saved.report.actualNativeRestoreProven, false);
  assert.deepEqual(saved.source, targetView.source);
  assert.deepEqual(saved.destination, targetView.state.live);
});
test('old v2 no-profile authenticated package cannot be adopted by new-profile configuration', async t => {
  const f = await fixture(t, { legacy: true });
  await assert.rejects(restoreSnapshot(f.ports), { message: 'EXACT_AUTHENTICATED_RESTORE_PROFILE_REQUIRED' });
  assert.equal(f.state.imports, 0); assert.equal(importStarts(f.state), 0);
});
test('each missing raw evidence port refuses before fence', async t => {
  for (const name of ['writeComparisonEvidence', 'readInventoryBytes']) {
    const f = await fixture(t); delete f.ports[name];
    await assert.rejects(restoreSnapshot(f.ports), { message: 'RESTORE_PROFILE_RAW_EVIDENCE_PORTS_REQUIRED' });
    assert.equal(f.state.fences, 0); assert.equal(f.state.imports, 0); assert.equal(importStarts(f.state), 0);
  }
});
