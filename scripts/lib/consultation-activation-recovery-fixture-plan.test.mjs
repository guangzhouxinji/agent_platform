import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createHash} from 'node:crypto';
import {compileFixturePlan73, fixtureCase73, createFixtureJournal73, FIXTURE73,SCHEMA_RECOVERY96,PARTIAL_RECOVERY97,assertPartialRecovery97,assertSchemaObjects96} from './consultation-activation-recovery-fixture-plan.mjs';
const root = process.cwd(), hash = s => createHash('sha256').update(s).digest('hex');
const plan = await compileFixturePlan73(root);
const request = name => ({schema: 'ACTR66_' + name, operation: 'CASE_' + name, previousOutcome: 'CLEAR'});
test('96 resume is closed to exact EMPTY and skips only the proven schema creation',()=>{
 const r={...request('EMPTY'),previousOutcome:SCHEMA_RECOVERY96.outcome},selected=fixtureCase73(plan,r);assert.equal(selected.journal[0].step,'P_CREATE_SUBJECT');assert.equal(selected.completedDdlStatements,14);
 for(const name of ['RECEIPT','EVENT'])assert.throws(()=>fixtureCase73(plan,{...request(name),previousOutcome:SCHEMA_RECOVERY96.outcome}));
});
test('96 native schema-only observation requires one exact self SCH and current target',()=>{
 const target='a'.repeat(64),value={format:'SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1',actualJdbc:true,targetId:target,accounts:[{name:'ACTR66_EMPTY',objects:[{OBJECT_NAME:'ACTR66_EMPTY',OBJECT_TYPE:'SCH'}]}]};assertSchemaObjects96(value,target);
 for(const mutate of [v=>v.targetId='b'.repeat(64),v=>v.actualJdbc=false,v=>v.accounts[0].objects=[],v=>v.accounts[0].objects.push({OBJECT_NAME:'OTHER',OBJECT_TYPE:'TABLE'}),v=>v.accounts[0].objects[0].OBJECT_NAME='OTHER',v=>v.accounts[0].objects[0].OBJECT_TYPE='TABLE',v=>v.accounts[0].objects[0].extra=true]){const bad=structuredClone(value);mutate(bad);assert.throws(()=>assertSchemaObjects96(bad,target));}
});
const result = name => {const c = fixtureCase73(plan, request(name)); return {outcome: c.outcome, completedDdlStatements: c.completedDdlStatements, committedSeedTransactions: c.committedSeedTransactions, vendorCode: c.vendorCode, guardSqlSha256: plan.migration[3].sqlSha256, sqlState: name === 'EMPTY' ? 'NONE' : '45000'};};
function complete(journal, name) {
  for (const step of fixtureCase73(plan, request(name)).journal) {
    journal.prepareRecord({...step, phase: 'PREPARED'}).acceptAfterSync();
    journal.prepareRecord({...step, phase: name !== 'EMPTY' && step.step === 'U_GUARD' ? 'REFUSED' : 'COMPLETED'}).acceptAfterSync();
  }
}
test('fixture73 deterministic plan equals exclusively generated frozen artifact, offline only', async () => {
  const bytes = Buffer.from(JSON.stringify(plan, null, 2) + '\n');
  assert.equal(hash(bytes), 'd1faa9f7d55d3a7cd75cb730475c82e5cbda7f7d0f650cb41ca666bc0957754e');
  assert.deepEqual(bytes, await fs.readFile(path.join(root, 'docs/evidence/activation-recovery-design-20261010/fixture-preparation73/fixture-plan73.json')));
  assert.equal(plan.executionAuthorized, false); assert.equal(plan.sqlExecuted, false);
  assert.equal(plan.prepare.length, 8); assert.equal(plan.cases.length, 3);
  for (const c of plan.cases) assert.equal(c.journal.filter(s => s.step.startsWith('P_')).length, 9);
  assert.equal(plan.cases[0].journal.length, 16);
  assert.equal(plan.cases[1].journal.length, 17);
});
test('fixture73 prefixes preserve exact missing-check phases and native columns', () => {
  assert.equal(Object.keys(plan.prefixes[0].tables).length, 0);
  assert.equal(plan.prefixes[2].tables.ID2_SUBJECT.columns.length, 10);
  assert.equal(plan.prefixes[4].tables.ID2_SUBJECT.columns.length, 11);
  assert.ok(!plan.prefixes[5].tables.ID2_SUBJECT.checks.CK_ID2_SUBJECT_ACTIVATION);
  assert.ok(plan.prefixes[6].tables.ID2_SUBJECT.checks.CK_ID2_SUBJECT_ACTIVATION);
  assert.ok(!plan.prefixes[6].tables.ID2_SUBJECT.checks.CK_ID2_SUBJECT_KIND);
  assert.ok(plan.prefixes[7].tables.ID2_SUBJECT.checks.CK_ID2_SUBJECT_KIND);
  assert.ok(!plan.prefixes[8].tables.ID2_SUBJECT_EVENT.checks.CK_ID2_SUBJECT_EVENT_OPERATION);
  assert.match(plan.prefixes[9].tables.ID2_SUBJECT_EVENT.checks.CK_ID2_SUBJECT_EVENT_OPERATION, /SERVICE_DISABLE/);
});
test('fixture73 fixed seed timestamps and expired hash do not issue a credential', () => {
  for (const s of plan.seeds) {
    assert.match(s.sql, /TIMESTAMP '2000-01-01 00:00:00'/);
    assert.equal(s.sqlSha256, hash(s.sql));
    assert.equal(s.commitSha256, hash('JDBC_COMMIT:' + s.step));
    assert.notEqual(s.commitSha256, hash('COMMIT;'));
  }
  assert.match(plan.seeds[0].sql, new RegExp("'" + '0'.repeat(64) + "'"));
  assert.ok(!plan.prepare.some(s => /GRANT|id2_key|id2_workload|id2_grant/.test(s.sql)));
});
for (const name of ['EMPTY', 'RECEIPT', 'EVENT']) test('fixture73 complete finite journal admits only final case result ' + name, () => {
  const journal = createFixtureJournal73(plan, request(name)); complete(journal, name); journal.assertResult(result(name));
});
for (const change of [r => r.operation = 'PREPARE', r => r.schema = 'CONPROD', r => r.operation = 'CASE_EVENT', r => r.previousOutcome = 'UNKNOWN', r => r.sql = 'SELECT 1', r => delete r.schema]) test('fixture73 rejects arbitrary or mismatched request ' + change.toString(), () => {
  const r = request('EMPTY'); change(r); assert.throws(() => fixtureCase73(plan, r), /FIXTURE_CASE/);
});
test('fixture73 failed fsync/no acceptance does not advance the state or admit result', () => {
  const journal = createFixtureJournal73(plan, request('EMPTY')), step = plan.cases[0].journal[0];
  const pending = journal.prepareRecord({...step, phase: 'PREPARED'});
  assert.throws(() => journal.prepareRecord({...step, phase: 'COMPLETED'}), /PHASE/);
  assert.throws(() => journal.assertResult(result('EMPTY')), /INCOMPLETE/);
  pending.acceptAfterSync();
  assert.throws(() => pending.acceptAfterSync(), /STALE/);
});
test('fixture73 commit has its own mandatory PREPARED and cannot be skipped', () => {
  const c = fixtureCase73(plan, request('RECEIPT')), journal = createFixtureJournal73(plan, request('RECEIPT'));
  for (const step of c.journal.slice(0, 10)) {
    journal.prepareRecord({...step, phase: 'PREPARED'}).acceptAfterSync();
    journal.prepareRecord({...step, phase: 'COMPLETED'}).acceptAfterSync();
  }
  assert.equal(c.journal[10].step, 'SEED_SUBJECT_COMMIT');
  assert.throws(() => journal.prepareRecord({...c.journal[11], phase: 'PREPARED'}), /STEP_SHA/);
  assert.throws(() => journal.prepareRecord({...c.journal[10], phase: 'COMPLETED'}), /PHASE/);
});
test('fixture73 UNKNOWN permanently blocks successors and reported success', () => {
  const c = plan.cases[0], journal = createFixtureJournal73(plan, request('EMPTY'));
  journal.prepareRecord({...c.journal[0], phase: 'PREPARED'}).acceptAfterSync();
  journal.prepareRecord({...c.journal[0], phase: 'UNKNOWN'}).acceptAfterSync();
  assert.throws(() => journal.prepareRecord({...c.journal[1], phase: 'PREPARED'}), /STOPPED/);
  assert.throws(() => journal.assertResult(result('EMPTY')), /INCOMPLETE/);
});
for (const field of ['outcome', 'completedDdlStatements', 'committedSeedTransactions', 'vendorCode', 'guardSqlSha256', 'sqlState']) test('fixture73 refuses result drift ' + field, () => {
  const journal = createFixtureJournal73(plan, request('RECEIPT')); complete(journal, 'RECEIPT');
  const r = result('RECEIPT'); r[field] = 'bad'; assert.throws(() => journal.assertResult(r), /MISMATCH/);
});
test('fixture73 wrong SHA, out-of-order step and unexpected guard refusal cannot advance', () => {
  const c = plan.cases[0], journal = createFixtureJournal73(plan, request('EMPTY'));
  assert.throws(() => journal.prepareRecord({...c.journal[0], sqlSha256: 'a'.repeat(64), phase: 'PREPARED'}), /STEP_SHA/);
  assert.throws(() => journal.prepareRecord({...c.journal[1], phase: 'PREPARED'}), /STEP_SHA/);
  journal.prepareRecord({...c.journal[0], phase: 'PREPARED'}).acceptAfterSync();
  journal.prepareRecord({...c.journal[0], phase: 'REFUSED'}).acceptAfterSync();
  assert.throws(() => journal.prepareRecord({...c.journal[1], phase: 'PREPARED'}), /STOPPED/);
  assert.throws(() => journal.assertResult(result('EMPTY')), /INCOMPLETE/);
});
for (const file of [...plan.sourceHashes.map(x => x.path), FIXTURE73.contract]) test('fixture73 full source pin fails closed on drift ' + file, async t => {
  const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'actr73-source-'));
  t.after(() => fs.rm(scratch, {recursive: true, force: true}));
  for (const f of [...plan.sourceHashes.map(x => x.path), FIXTURE73.contract]) {
    await fs.mkdir(path.dirname(path.join(scratch, f)), {recursive: true});
    await fs.copyFile(path.join(root, f), path.join(scratch, f));
  }
  await fs.appendFile(path.join(scratch, file), '\n-- component drift\n');
  await assert.rejects(compileFixturePlan73(scratch), /SOURCE_SHA_CHANGED/);
});

test('97 original history requires matching source return and resumes only EMPTY suffix',async()=>{
 assert.deepEqual(await assertPartialRecovery97(root),{originalDdlStatements:10,newExpectedDdlStatements:5,automaticRetryAllowed:false});
 const c=fixtureCase73(plan,{...request('EMPTY'),previousOutcome:PARTIAL_RECOVERY97.outcome});assert.equal(c.completedDdlStatements,5);assert.equal(c.journal[0].step,'V_ADD_NEW');assert.equal(c.journal.length,6);
 for(const n of ['EVENT','RECEIPT'])assert.throws(()=>fixtureCase73(plan,{...request(n),previousOutcome:PARTIAL_RECOVERY97.outcome}));
});
