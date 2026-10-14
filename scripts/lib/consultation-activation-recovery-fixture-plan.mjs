import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';

export const FIXTURE73 = Object.freeze({
  format: 'ACTR66_FIXED_PREPARATION_PLAN/v1',
  contract: 'docs/evidence/activation-recovery-design-20261010/entry-admission68/fixture-contract68.json',
  contractSha256: '3c97aa455f8a212ddc445b6d8dd896db5d3303b23aec4cf03027474b4ca6ee34',
  root: '/jdbc/activation-validation97',
  plan: 'docs/evidence/activation-recovery-design-20261010/fixture-preparation73/fixture-plan73.json',
  planSha256: 'd1faa9f7d55d3a7cd75cb730475c82e5cbda7f7d0f650cb41ca666bc0957754e',
});
const pins = Object.freeze([
  ['db/dm8/V032__product_identity.sql', '7a025bd04bdf8b73a22e503148fc38e901b9ca1ed33bf412363992417c26aded'],
  ['db/dm8/V046__product_workload_subject.sql', 'da746a24b53fb458523cef825122d643212b711c8f0163d7f744d88a7d6400d4'],
  ['db/dm8/V107__product_activation_recovery.sql', '1b36de0c01ad6b5fd1bba76a0a4e43f3050974d1f66101f41d2cb90cbf042cde'],
  ['db/dm8/U107__product_activation_recovery.sql', '858ce5557dfbe449485abc85f91a1e47b89aff2d3083aec691e9118cb0e72fb6'],
]);
const sha = value => createHash('sha256').update(value).digest('hex');
export const SCHEMA_RECOVERY96 = Object.freeze({
  outcome:'RECONCILED_SCHEMA_ONLY_3976',
  attempt:{path:'docs/evidence/activation-recovery-design-20261010/entry-admission68/run-3976b3ce-6226-48e9-aa88-1b08b9fe064b/attempt.jsonl',sha256:'f2bccb156bf57894de3f1376b0e7d30e6628dc59b970ba3bb0c7682c9815d629'},
  returned:{path:'docs/evidence/activation-recovery-design-20261010/continuous-fence75/reconcile94-680eb06d-b1df-46d8-ab30-2c9c20e57ee1/result.json',sha256:'85f24db53a158162b629be1b5813fa7eea84f7a569606ab6a496ea6e1a6180e0'},
});
export const PARTIAL_RECOVERY97=Object.freeze({
 outcome:'RECONCILED_V_DROP_OLD_6F13',
 attempt:{path:'docs/evidence/activation-recovery-design-20261010/entry-admission68/run-6f13c815-4944-4951-8016-66941ffed355/attempt.jsonl',sha256:'168dd6ff6abc6ffbab7589f55e5f396e53adabdbcddb4460b352e56efdfc4339'},
 returned:{path:'docs/evidence/activation-recovery-design-20261010/continuous-fence75/reconcile97-ff27f507-aaf6-48f2-8d3b-d302e5b1a4bd/result.json',sha256:'85f24db53a158162b629be1b5813fa7eea84f7a569606ab6a496ea6e1a6180e0'},
 proof:{path:'docs/evidence/activation-recovery-design-20261010/continuous-fence75/reconcile97-ff27f507-aaf6-48f2-8d3b-d302e5b1a4bd/proof.json',sha256:'fd9afc1c2bddfcb038d98b5233905849ecf2f0e8fdeb93d68bc14b0e8f18b449'},
});
const require = (ok, code) => { if (!ok) throw Error(code); };
const clone = value => JSON.parse(JSON.stringify(value));
const action = (step, sql) => ({step, sql, sqlSha256: sha(sql)});
const split = text => text.split(';').filter(x => x.trim()).map(x => x.trim() + ';');
const time = "TIMESTAMP '2000-01-01 00:00:00'";
const id = "'actr66-fixture'", issuer = "'urn:actr66:fixture'";
const normalize = s => s.replace(/\s+/g, '').toUpperCase();

async function pinned(root, relative, pin) {
  let file = root;
  for (const part of relative.split('/')) {
    file = path.join(file, part);
    const stat = await fs.lstat(file);
    require(!stat.isSymbolicLink() && await fs.realpath(file) === file, 'FIXTURE_PLAN_PATH_REDIRECT');
  }
  const handle = await fs.open(file, 'r');
  try {
    require((await handle.stat()).isFile(), 'FIXTURE_PLAN_REGULAR_FILE_REQUIRED');
    const bytes = Buffer.alloc(65537);
    let size = 0;
    while (size < bytes.length) {
      const result = await handle.read(bytes, size, bytes.length - size, null);
      if (!result.bytesRead) break;
      size += result.bytesRead;
    }
    require(size <= 65536 && sha(bytes.subarray(0, size)) === pin, 'FIXTURE_PLAN_SOURCE_SHA_CHANGED');
    return bytes.subarray(0, size).toString('utf8');
  } finally { await handle.close(); }
}

// Only whole-file pinned SQL is interpreted. This is not a general SQL parser.
export async function compileFixturePlan73(workspaceRoot) {
  const root = path.resolve(workspaceRoot);
  require(await fs.realpath(root) === root, 'FIXTURE_PLAN_ROOT_REDIRECT');
  const sources = await Promise.all(pins.map(([file, pin]) => pinned(root, file, pin)));
  const contract = JSON.parse(await pinned(root, FIXTURE73.contract, FIXTURE73.contractSha256));
  require(contract.format === 'ACTR66_FIXED_FIXTURE_CONTRACT/v1', 'FIXTURE_PLAN_CONTRACT_FORMAT');
  const [v32, v46, v107] = sources.slice(0, 3).map(split);
  require(v32[0].startsWith('CREATE TABLE id2_subject (') && v32[1].startsWith('CREATE TABLE id2_subject_event ('), 'FIXTURE_PLAN_BASE_LAYOUT');
  require(v46.length >= 6 && v46.slice(0, 6).every(s => s.startsWith('ALTER TABLE id2_subject')), 'FIXTURE_PLAN_ALTER_LAYOUT');
  const separator = /\r?\n\/\r?\n/g, match = separator.exec(sources[3]);
  require(match && !separator.exec(sources[3]), 'FIXTURE_PLAN_INVERSE_SEPARATOR');
  const block = sources[3].slice(sources[3].indexOf('DECLARE'), match.index).trim();
  const inverse = [block, ...split(sources[3].slice(match.index + match[0].length))];
  require(v107.length === 3 && inverse.length === 4, 'FIXTURE_PLAN_MIGRATION_LAYOUT');
  const prepare = [action('P_CREATE_SUBJECT', v32[0]), action('P_CREATE_EVENT', v32[1]), ...v46.slice(0, 6).map((sql, i) => action(['P_ADD_KIND', 'P_DROP_ACTIVATION', 'P_ADD_ACTIVATION', 'P_ADD_KIND_CHECK', 'P_DROP_OPERATION', 'P_ADD_OLD_OPERATION'][i], sql))];
  const migration = [...v107.map((sql, i) => action(['V_DROP_OLD', 'V_ADD_NEW', 'V_CREATE_RECEIPT'][i], sql)), ...inverse.map((sql, i) => action(['U_GUARD', 'U_DROP_RECEIPT', 'U_DROP_OPERATION', 'U_ADD_OLD_OPERATION'][i], sql))];
  const seeds = [
    action('SEED_SUBJECT', `INSERT INTO id2_subject (issuer,subject_id,login_name,password_hash,activation_sha,activation_expires_at,status,credential_version,created_at,disabled_at,subject_kind) VALUES (${issuer},${id},${id},NULL,'${'0'.repeat(64)}',${time},'PENDING',1,${time},NULL,'HUMAN');`),
    action('SEED_RECEIPT', `INSERT INTO id2_activation_recovery_receipt (issuer,actor_ref,request_id,subject_id,expected_version,result_version,activation_expires_at,occurred_at) VALUES (${issuer},${id},${id},${id},1,2,${time},${time});`),
    action('SEED_EVENT', `INSERT INTO id2_subject_event (event_id,issuer,subject_id,operation,before_version,after_version,actor_ref,occurred_at) VALUES (${id},${issuer},${id},'REISSUE',1,2,${id},${time});`),
  ].map(x => ({...x, commitStep: x.step + '_COMMIT', commitSha256: sha('JDBC_COMMIT:' + x.step)}));
  const oldActivation = v32[0].match(/CONSTRAINT ck_id2_subject_activation CHECK \(([\s\S]*?)\),\s*CONSTRAINT ck_id2_subject_version/)[1];
  const oldVariants = [normalize(oldActivation), normalize(oldActivation.replace(/([a-z_]+) IS NOT NULL/g, 'NOT ($1 IS NULL )'))];
  const prefixes = Array.from({length: 10}, (_, index) => {
    const tables = index >= 3 ? ['ID2_SUBJECT', 'ID2_SUBJECT_EVENT'] : index >= 2 ? ['ID2_SUBJECT'] : [];
    const metadata = Object.fromEntries(tables.map(t => [t, clone(contract.tables[t])]));
    if (metadata.ID2_SUBJECT) {
      const s = metadata.ID2_SUBJECT;
      if (index < 4) s.columns = s.columns.slice(0, 10);
      if (index < 7) delete s.checks.CK_ID2_SUBJECT_KIND;
      if (index === 5) delete s.checks.CK_ID2_SUBJECT_ACTIVATION;
      else if (index < 6) s.checks.CK_ID2_SUBJECT_ACTIVATION = oldVariants;
    }
    if (metadata.ID2_SUBJECT_EVENT && index !== 8) metadata.ID2_SUBJECT_EVENT.checks.CK_ID2_SUBJECT_EVENT_OPERATION = `OPERATION IN ('REGISTER','ACTIVATE','DISABLE'${index === 9 ? ",'SERVICE_REGISTER','SERVICE_DISABLE'" : ''})`;
    return {index, schemaExists: index > 0, tables: metadata, allRowsZero: true};
  });
  const cases = ['EMPTY', 'RECEIPT', 'EVENT'].map(name => {
    const schema = 'ACTR66_' + name, empty = name === 'EMPTY';
    const create = action('P_CREATE_SCHEMA', `CREATE SCHEMA "${schema}" AUTHORIZATION SYSDBA;`);
    const addSeed = seed => [{step: seed.step, sqlSha256: seed.sqlSha256}, {step: seed.commitStep, sqlSha256: seed.commitSha256}];
    const journal = [create, ...prepare, ...(!empty ? addSeed(seeds[0]) : []), ...migration.slice(0, 3), ...(!empty ? addSeed(seeds[name === 'RECEIPT' ? 1 : 2]) : []), ...(empty ? migration.slice(3) : migration.slice(3, 4))].map(({step, sqlSha256}) => ({step, sqlSha256}));
    return {operation: 'CASE_' + name, schema, create, journal, completedDdlStatements: empty ? 15 : 12, committedSeedTransactions: empty ? 0 : 2, outcome: empty ? 'CASE_EMPTY_REVERSE_COMPLETED' : 'CASE_HISTORY_REVERSE_REFUSED', vendorCode: empty ? 0 : -20001};
  });
  return {format: FIXTURE73.format, sourceHashes: pins.map(([file, pin]) => ({path: file, sha256: pin})), fixtureSha256: FIXTURE73.contractSha256, prepare, migration, seeds, prefixes, cases, executionAuthorized: false, sqlExecuted: false, automaticRetryAllowed: false};
}

export function fixtureCase73(plan, request) {
  const partial=request?.previousOutcome===PARTIAL_RECOVERY97.outcome,resumed=request?.previousOutcome===SCHEMA_RECOVERY96.outcome||partial;
  require(request && Object.keys(request).sort().join(',') === 'operation,previousOutcome,schema' && (request.previousOutcome === 'CLEAR'||resumed&&request.schema==='ACTR66_EMPTY'&&request.operation==='CASE_EMPTY'), 'FIXTURE_CASE_EXACT_REQUEST');
  const selected = plan.cases.find(x => x.operation === request.operation && x.schema === request.schema);
  require(selected, 'FIXTURE_CASE_SCHEMA_BINDING');
  const result=clone(selected);
  if(resumed){const done=partial?10:1;result.journal=result.journal.slice(done);result.completedDdlStatements-=done;}
  return result;
}

export async function assertSchemaRecovery96(root){
 const lines=(await pinned(root,SCHEMA_RECOVERY96.attempt.path,SCHEMA_RECOVERY96.attempt.sha256)).trim().split(/\r?\n/).map(JSON.parse);
 require(lines.length===3&&lines.every(r=>r.runId==='3976b3ce-6226-48e9-aa88-1b08b9fe064b'&&r.schema==='ACTR66_EMPTY')&&lines[0].step==='P_CREATE_SCHEMA'&&lines[0].phase==='PREPARED'&&lines[1].step==='P_CREATE_SCHEMA'&&lines[1].phase==='COMPLETED'&&lines[0].sqlSha256===lines[1].sqlSha256&&lines[2].type==='ERROR'&&lines[2].code==='PREFIX_EXTRA_OBJECTS_FORBIDDEN','EXACT_SCHEMA_ONLY_HISTORY_REQUIRED');
 const returned=JSON.parse(await pinned(root,SCHEMA_RECOVERY96.returned.path,SCHEMA_RECOVERY96.returned.sha256));
 require(returned.entryLockRetired===true&&returned.controllerLockRetired===true&&returned.currentSourceHealthy===true&&returned.partialSchemaPreserved==='ACTR66_EMPTY'&&returned.originalCaseSucceeded===false,'EXACT_SCHEMA_ONLY_RETURN_REQUIRED');
 return {originalDdlStatements:1,automaticRetryAllowed:false};
}

export function assertSchemaObjects96(value,targetId){
 require(value?.format==='SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1'&&value.actualJdbc===true&&value.targetId===targetId&&value.accounts?.length===1&&value.accounts[0].name==='ACTR66_EMPTY','EXACT_SCHEMA_OBSERVATION_REQUIRED');
 require(value.accounts[0].objects?.length===1&&value.accounts[0].objects[0].OBJECT_NAME==='ACTR66_EMPTY'&&value.accounts[0].objects[0].OBJECT_TYPE==='SCH'&&Object.keys(value.accounts[0].objects[0]).sort().join(',')==='OBJECT_NAME,OBJECT_TYPE','EXACT_SCHEMA_ONLY_OBJECT_REQUIRED');
}

// Validate first, persist+fsync externally, then accept. No ACK may precede acceptance.
export function createFixtureJournal73(plan, request) {
  const selected = fixtureCase73(plan, request);
  let index = 0, prepared = null, failed = false, revision = 0;
  const phases = [];
  return {
    prepareRecord(message) {
      require(!failed, 'FIXTURE_JOURNAL_STOPPED');
      const expected = selected.journal[index];
      require(expected && message.step === expected.step && message.sqlSha256 === expected.sqlSha256, 'FIXTURE_JOURNAL_STEP_SHA');
      require(message.phase === 'PREPARED' ? prepared === null : prepared === message.step && ['COMPLETED', 'REFUSED', 'UNKNOWN'].includes(message.phase), 'FIXTURE_JOURNAL_PHASE');
      require(message.phase !== 'REFUSED' || prepared === message.step, 'FIXTURE_JOURNAL_REFUSAL');
      const captured = clone(message), version = revision;
      let accepted = false;
      return {acceptAfterSync() {
        require(!accepted && revision === version, 'FIXTURE_JOURNAL_STALE_ACCEPT');
        accepted = true; revision++;
        if (captured.phase === 'PREPARED') prepared = captured.step;
        else {prepared = null; index++; phases.push(captured.phase); if (captured.phase === 'UNKNOWN' || captured.phase === 'REFUSED' && !(captured.step === 'U_GUARD' && selected.vendorCode === -20001)) failed = true;}
      }};
    },
    assertResult(message) {
      require(!failed && prepared === null && index === selected.journal.length, 'FIXTURE_RESULT_INCOMPLETE_JOURNAL');
      require(message.outcome === selected.outcome && message.completedDdlStatements === selected.completedDdlStatements && message.committedSeedTransactions === selected.committedSeedTransactions && message.vendorCode === selected.vendorCode && message.guardSqlSha256 === plan.migration[3].sqlSha256 && (selected.vendorCode === 0 ? message.sqlState === 'NONE' : typeof message.sqlState === 'string' && /^(?:[A-Z0-9]{5}|UNKNOWN)$/.test(message.sqlState)), 'FIXTURE_RESULT_MISMATCH');
      require(phases.every((phase, i) => phase === (i === phases.length - 1 && selected.vendorCode === -20001 ? 'REFUSED' : 'COMPLETED')), 'FIXTURE_RESULT_JOURNAL_MISMATCH');
    },
  };
}

export async function assertPartialRecovery97(root){
 await assertSchemaRecovery96(root);
 const records=(await pinned(root,PARTIAL_RECOVERY97.attempt.path,PARTIAL_RECOVERY97.attempt.sha256)).trim().split(/\r?\n/).map(JSON.parse);
 const plan=JSON.parse(await pinned(root,FIXTURE73.plan,FIXTURE73.planSha256)),expected=[...plan.prepare,plan.migration[0]];
 require(records.length===18&&records.every((r,i)=>r.runId==='6f13c815-4944-4951-8016-66941ffed355'&&r.schema==='ACTR66_EMPTY'&&r.type==='JOURNAL'&&r.step===expected[Math.floor(i/2)].step&&r.sqlSha256===expected[Math.floor(i/2)].sqlSha256&&r.phase===(i%2?'COMPLETED':'PREPARED')),'EXACT_PARTIAL_HISTORY_REQUIRED');
 const returned=JSON.parse(await pinned(root,PARTIAL_RECOVERY97.returned.path,PARTIAL_RECOVERY97.returned.sha256)),proof=JSON.parse(await pinned(root,PARTIAL_RECOVERY97.proof.path,PARTIAL_RECOVERY97.proof.sha256));
 require(returned.entryLockRetired&&returned.controllerLockRetired&&returned.currentSourceHealthy&&returned.originalCaseSucceeded===false&&proof.failedRun==='6f13c815-4944-4951-8016-66941ffed355'&&proof.originalBaselineMatched&&proof.twoCurrentInventoriesEqual&&proof.oldSixTargetUnchanged,'EXACT_PARTIAL_RETURN_REQUIRED');
 return {originalDdlStatements:10,newExpectedDdlStatements:5,automaticRetryAllowed:false};
}
