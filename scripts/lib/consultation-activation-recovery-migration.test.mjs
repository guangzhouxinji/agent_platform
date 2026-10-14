import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, copyFileSync, rmSync, symlinkSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { planConsultationActivationRecoveryMigration as plan, PRIMARY_TARGET_ID } from './consultation-activation-recovery-migration.mjs';
const root=path.resolve(import.meta.dirname,'../..');
const base=()=>({format:'CON_ACTIVATION_MIGRATION_OBSERVATION/v1',targetId:PRIMARY_TARGET_ID,schema:'CONPROD',databaseVersion:'8.1.4.170',capturedAt:'2026-10-10T17:00:00.000Z',rawInventorySha256:'a'.repeat(64),direction:'FORWARD',operationConstraint:'OLD5',receiptStructure:'ABSENT',receiptCount:null,reissueCount:'0',unexpectedOperationCount:'0',lastStatementOutcome:'KNOWN'});
const run=(patch={})=>plan({...base(),...patch},{workspaceRoot:root});
function denied(r){assert.equal(r.executionAuthorized,false);assert.equal(r.migrationExecuted,false);assert.equal(r.metadataTruthProven,false);assert.ok(r.requires.includes('CURRENT_EXCLUSIVE_WRITER_FENCE'));assert.ok(r.requires.includes('DM8_PHYSICAL_ACCEPTANCE'));}
for (const [label,patch,decision,next] of [
 ['original schema',{operationConstraint:'OLD5'},'DROP_OLD_OPERATION','DROP_OLD_OPERATION'],
 ['after committed DROP',{operationConstraint:'ABSENT'},'ADD_RECOVERY_OPERATION','ADD_RECOVERY_OPERATION'],
 ['after ADD but before CREATE',{operationConstraint:'RECOVERY6'},'CREATE_RECEIPT','CREATE_RECEIPT'],
 ['empty new schema',{operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'0'},'SCHEMA_READY_NOT_RUNTIME_READY',null],
 ['new schema with receipt history',{operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'1'},'SCHEMA_READY_NOT_RUNTIME_READY',null],
 ['new schema with event history',{operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'0',reissueCount:'1'},'SCHEMA_READY_NOT_RUNTIME_READY',null],
 ['missing constraint and intact receipt history',{operationConstraint:'ABSENT',receiptStructure:'EXACT',receiptCount:'2',reissueCount:'2'},'ADD_RECOVERY_OPERATION','ADD_RECOVERY_OPERATION'],
 ['missing table despite event history',{operationConstraint:'RECOVERY6',reissueCount:'1'},'HOLD_HISTORY_WITH_MISSING_RECEIPT',null],
 ['old constraint and new receipt',{receiptStructure:'EXACT',receiptCount:'0'},'HOLD_INCONSISTENT_SCHEMA',null],
 ['unrecognized receipt',{receiptStructure:'OTHER',receiptCount:'0'},'HOLD_UNRECOGNIZED_STRUCTURE_OR_OPERATIONS',null],
 ['unrecognized constraint',{operationConstraint:'OTHER'},'HOLD_UNRECOGNIZED_STRUCTURE_OR_OPERATIONS',null],
 ['invalid existing operation',{unexpectedOperationCount:'1'},'HOLD_UNRECOGNIZED_STRUCTURE_OR_OPERATIONS',null],
 ['empty full reverse guard',{direction:'REVERSE',operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'0'},'GUARD_REVERSE_REQUIRED','GUARD_REVERSE_REQUIRED'],
 ['receipt history reverse refusal',{direction:'REVERSE',operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'1'},'COMMITTED_HISTORY_FORWARD_ONLY',null],
 ['REISSUE history reverse refusal',{direction:'REVERSE',operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'0',reissueCount:'1'},'COMMITTED_HISTORY_FORWARD_ONLY',null],
 ['BIGINT history never rounded',{direction:'REVERSE',operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'9007199254740993'},'COMMITTED_HISTORY_FORWARD_ONLY',null],
 ['reverse after missing receipt',{direction:'REVERSE',operationConstraint:'RECOVERY6'},'PARTIAL_REVERSE_NOT_ADMITTED',null],
 ['reverse after absent constraint',{direction:'REVERSE',operationConstraint:'ABSENT'},'PARTIAL_REVERSE_NOT_ADMITTED',null],
 ['reverse original schema already present',{direction:'REVERSE'},'ORIGINAL_SCHEMA_PRESENT',null],
 ['UNKNOWN cannot rerun DROP',{lastStatementOutcome:'UNKNOWN'},'REOBSERVE_REQUIRED',null],
 ['UNKNOWN cannot infer success from complete schema',{lastStatementOutcome:'UNKNOWN',operationConstraint:'RECOVERY6',receiptStructure:'EXACT',receiptCount:'0'},'REOBSERVE_REQUIRED',null]
])test(label,async()=>{const r=await run(patch);denied(r);assert.equal(r.decision,decision);assert.equal(r.nextStatement?.id??null,next);if(r.nextStatement){assert.match(r.nextStatement.sha256,/^[a-f0-9]{64}$/);assert.equal(Object.hasOwn(r.nextStatement,'sql'),false);}});
for (const [label,patch,code] of [
 ['other target',{targetId:'b'.repeat(64)},'EXACT_PRIMARY_CONPROD_REQUIRED'],
 ['other schema',{schema:'CONAUTH'},'EXACT_PRIMARY_CONPROD_REQUIRED'],
 ['SYSDBA schema',{schema:'SYSDBA'},'EXACT_PRIMARY_CONPROD_REQUIRED'],
 ['unsupported version',{databaseVersion:'8.1.4.171'},'DATABASE_VERSION_UNSUPPORTED'],
 ['numeric count precision',{reissueCount:9007199254740992},'DECIMAL_BIGINT_COUNT_REQUIRED'],
 ['negative count',{reissueCount:'-1'},'DECIMAL_BIGINT_COUNT_REQUIRED'],
 ['overflow count',{reissueCount:'9223372036854775808'},'DECIMAL_BIGINT_COUNT_REQUIRED'],
 ['leading zero',{reissueCount:'01'},'DECIMAL_BIGINT_COUNT_REQUIRED'],
 ['missing table with count',{receiptCount:'0'},'ABSENT_RECEIPT_REQUIRES_NULL_COUNT'],
 ['missing inventory digest',{rawInventorySha256:'wrong'},'RAW_INVENTORY_SHA_REQUIRED'],
 ['nonUTC clock',{capturedAt:'2026-10-11T01:00:00+08:00'},'UTC_OBSERVATION_TIME_REQUIRED'],
 ['invalid calendar date',{capturedAt:'2026-02-30T00:00:00.000Z'},'UTC_OBSERVATION_TIME_REQUIRED'],
 ['arbitrary SQL',{sql:'DROP TABLE id2_subject;'},'EXACT_OBSERVATION_FIELDS_REQUIRED'],
 ['authority injection',{executionAuthorized:true},'EXACT_OBSERVATION_FIELDS_REQUIRED'],
 ['neverImported shortcut',{targetNeverImported:true},'EXACT_OBSERVATION_FIELDS_REQUIRED']
])test('reject '+label,async()=>{await assert.rejects(run(patch),new RegExp(code));});
const tempParent=path.join(root,'tmp');mkdirSync(tempParent,{recursive:true});
function temporary(){return mkdtempSync(path.join(tempParent,'activation-migration66-'));}
function cleanup(p){const resolved=path.resolve(p);assert.ok(resolved.startsWith(path.resolve(tempParent)+path.sep));assert.match(path.basename(resolved),/^activation-migration66-/);rmSync(resolved,{recursive:true,force:true});}
test('controlled SQL digest drift rejects without weakening pins',async()=>{const d=temporary();try{mkdirSync(path.join(d,'db/dm8'),{recursive:true});for(const n of ['V107__product_activation_recovery.sql','U107__product_activation_recovery.sql'])copyFileSync(path.join(root,'db/dm8',n),path.join(d,'db/dm8',n));writeFileSync(path.join(d,'db/dm8/V107__product_activation_recovery.sql'),'SELECT 1;');await assert.rejects(plan(base(),{workspaceRoot:d}),/CONTROLLED_SQL_SHA_MISMATCH/);}finally{cleanup(d);}});
test('controlled SQL directory redirection rejects',async t=>{const d=temporary();try{try{symlinkSync(path.join(root,'db'),path.join(d,'db'),'junction');}catch(e){if(['EPERM','EACCES'].includes(e.code)){t.skip('OS denies creating a test-only link; not a DM8 test');return;}throw e;}await assert.rejects(plan(base(),{workspaceRoot:d}),/SQL_REDIRECT_REJECTED/);}finally{cleanup(d);}});
test('finite state matrix never emits destructive reverse or UNKNOWN SQL',async()=>{
 for(const direction of ['FORWARD','REVERSE'])for(const operationConstraint of ['OLD5','RECOVERY6','ABSENT','OTHER'])for(const receiptStructure of ['ABSENT','EXACT','OTHER'])for(const history of ['0','1'])for(const lastStatementOutcome of ['KNOWN','UNKNOWN']){
  const r=await run({direction,operationConstraint,receiptStructure,receiptCount:receiptStructure==='ABSENT'?null:history,reissueCount:history,lastStatementOutcome});denied(r);
  if(lastStatementOutcome==='UNKNOWN')assert.equal(r.nextStatement,null);
  if(direction==='REVERSE'&&r.nextStatement)assert.equal(r.nextStatement.id,'GUARD_REVERSE_REQUIRED');
  if(direction==='REVERSE'&&history==='1')assert.equal(r.nextStatement,null);
  if(r.nextStatement?.id==='DROP_OLD_OPERATION'){assert.equal(direction,'FORWARD');assert.equal(history,'0');assert.equal(operationConstraint,'OLD5');assert.equal(receiptStructure,'ABSENT');}
 }
});
test('CLI diagnosis is exclusive, read-only, bounded, workspace-only and contains no SQL batch',()=>{
 const d=temporary(),input=path.join(d,'observation.json'),output=path.join(d,'diagnosis.json');
 const cli=path.join(root,'scripts/prepare-consultation-activation-recovery-migration.mjs');
 const call=(i=input,o=output)=>spawnSync(process.execPath,[cli,'--observation',i,'--output',o],{cwd:root,encoding:'utf8',timeout:15000});
 try{
  writeFileSync(input,JSON.stringify(base()));const before=readFileSync(input);const a=call();assert.equal(a.status,0,a.stdout+a.stderr);const r=JSON.parse(readFileSync(output,'utf8'));denied(r);assert.equal(r.decision,'DROP_OLD_OPERATION');assert.deepEqual(readFileSync(input),before);
  const saved=readFileSync(output);assert.equal(call().status,1);assert.deepEqual(readFileSync(output),saved);
  assert.equal(call(input,input).status,1);assert.deepEqual(readFileSync(input),before);
  assert.equal(call(input,path.resolve(root,'../outside-workspace66.json')).status,1);
  writeFileSync(input,JSON.stringify({...base(),password:'SENTINEL_PRIVATE_NEVER_PRINT'}));const bad=call(input,path.join(d,'invalid.json'));assert.equal(bad.status,1);assert.ok(!(bad.stdout+bad.stderr).includes('SENTINEL_PRIVATE_NEVER_PRINT'));
  writeFileSync(input,' '.repeat(65537));assert.equal(call(input,path.join(d,'oversize.json')).status,1);
 }finally{cleanup(d);}
});
test('product/source execution dependencies are absent from entry',()=>{for(const rel of ['scripts/lib/consultation-activation-recovery-migration.mjs','scripts/prepare-consultation-activation-recovery-migration.mjs']){const s=readFileSync(path.join(root,rel),'utf8');assert.doesNotMatch(s,/from\s+['"](?:node:)?(?:child_process|http|https|net|tls)['"]|process\.env|fetch\(/);}});
