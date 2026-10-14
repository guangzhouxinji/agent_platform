import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,realpath} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import {hash,canonical} from './single-database-lifecycle-runtime.mjs';
import {nativeImportInvocation,verifyNativeImportLog,reserveNativeRestoreAttempt,requireCurrentRestoreDecision,importDatabase,requireNativeRestoreWindow} from './single-database-lifecycle-native-restore.mjs';
// Self-owned temporary files, no Docker or database commands. Not a DM8 recovery claim.
async function configuration(){
 const root=await realpath(await mkdtemp(resolve(tmpdir(),'single-native-consumer-'))),targetId='a'.repeat(64),sourceTargetId='b'.repeat(64);
 const schemas=['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'];const until=new Date(Date.now()+3600000).toISOString();
 const nativeRestore={user:'OWNED_IMPORT',passwordEnv:'UNIT_NATIVE_PASSWORD',keyEnv:'UNIT_NATIVE_KEY',keyReference:'unit-native-key',timeoutSeconds:15,maintenanceLock:resolve(root,'single-database-maintenance-'+targetId+'.lock'),attemptMarker:resolve(root,'single-database-restore-'+targetId+'.json')};
 const ids=['c','d','e'].map(v=>v.repeat(64)),deployment={database:{volume:'agent-platform-unit-restore'},domains:ids.map(id=>({writerContainerIds:[id]}))};
 const c={targetId,sourceTargetId,schemas,accounts:schemas.flatMap(s=>[s,s+'_SVC']),deployment,nativeRestore,packagePath:resolve(root,'new-owned-package'),packageSha256:'f'.repeat(64),keyEnv:'UNIT_PACKAGE_KEY',keyReference:'unit-sealed-key',files:[],fileRoot:resolve(root,'new-files')};
 c.approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId,sourceTargetId,action:'restore',owner:'SELF_OWNED_UNIT',source:'UNIT_FILE_CONTRACT_NOT_OPERATION_AUTHORIZATION',approvedAt:new Date(Date.now()-1000).toISOString(),expiresAt:until,currentMigration:false,connectionSwitch:false,deleteExisting:false,configurationSha256:hash(canonical(deployment)),restoreConfigurationSha256:hash(canonical(nativeRestore)),packageSha256:c.packageSha256,keyReference:c.keyReference,nativeExportKeyReference:nativeRestore.keyReference,writerRegistryComplete:true,writerRegistryEvidence:'unit-registry',maintenanceExclusivityEvidence:'unit-maintenance',writers:[...ids.map(id=>({id,kind:'CONTAINER',owner:'UNIT',responsibilitySource:'unit-registry'})),{id:nativeRestore.maintenanceLock,kind:'MAINTENANCE',owner:'UNIT',responsibilitySource:'unit-maintenance'}],emptyInventorySha256:'0'.repeat(64),databaseRights:{owner:'UNIT',licenseReference:'SELF_OWNED_UNIT',purpose:'file-only tests',useUntil:until,schemas},files:[]};
 return c;
}
test('native invocation fixes exact scope, finite timeout, IGNORE=N, no replace/full/credential argument',async()=>{
 const c=await configuration(),args=nativeImportInvocation(c,'single-restore-11111111-1111-1111-1111-111111111111.dmp');
 assert.deepEqual(args.slice(0,3),['exec','-i',c.targetId]);assert.match(args[5],/timeout -k 5/);assert.match(args[5],/IGNORE=N/);assert.match(args[5],/SCHEMAS=/);assert.match(args[5],/DUMMY=N/);assert.doesNotMatch(args.join(' '),/FULL=Y|APPEND|REPLACE|TRUNCATE|actual-secret/);
 for(const name of ['../old.dmp','existing.dmp','single-restore-x.dmp'])assert.throws(()=>nativeImportInvocation(c,name),/NEW_NATIVE_RESTORE_NAME_REQUIRED/);
});
test('warning-free installed dimp summary required even if process exit zero',()=>{
 verifyNativeImportLog(Buffer.from('成功终止导入, 没有出现警告'));
 for(const log of ['成功终止导入, 出现9个警告','completed','成功终止导入, 没有出现警告\n错误: 1'])assert.throws(()=>verifyNativeImportLog(Buffer.from(log)),/WARNING_FREE/);
});
test('actual import refuses before Docker when no live fence is held',async()=>{
 const c=await configuration();await assert.rejects(()=>importDatabase(c,Buffer.from('own encrypted unit bytes')),/ACTUAL_RESTORE_FENCE_REQUIRED/);
});
test('actual persistent attempt marker survives process boundaries; reuse is reconciliation-only',async()=>{
 const c=await configuration();await reserveNativeRestoreAttempt(c);const before=await readFile(c.nativeRestore.attemptMarker);
 const config=resolve(c.nativeRestore.attemptMarker,'..','config.json');await writeFile(config,JSON.stringify(c),{flag:'wx'});
 const moduleUrl=new URL('./single-database-lifecycle-native-restore.mjs',import.meta.url).href;
 const child=spawnSync(process.execPath,['--input-type=module','-e',`import {readFile} from 'node:fs/promises';import {reserveNativeRestoreAttempt} from ${JSON.stringify(moduleUrl)};try{await reserveNativeRestoreAttempt(JSON.parse(await readFile(process.argv[1])));process.exit(3);}catch(e){if(e.message!=='RESTORE_ATTEMPT_EXISTS_RECONCILE_REQUIRED')throw e;}`,config],{encoding:'utf8',timeout:10000});
 assert.equal(child.status,0,child.stderr);assert.deepEqual(await readFile(c.nativeRestore.attemptMarker),before);
 const mark=JSON.parse(before.toString());assert.equal(mark.automaticRetryAllowed,false);assert.equal(mark.packageSha256,c.packageSha256);
});
test('live decision original is reread, bound and never signed by the tool',async()=>{
 const c=await configuration();c.currentDecisionPath=resolve(c.fileRoot,'..','unit-original-decision.json');const original=Buffer.from(JSON.stringify(c.approval));await writeFile(c.currentDecisionPath,original,{flag:'wx'});c.approval.currentDecisionEvidenceSha256=hash(original);
 await requireCurrentRestoreDecision(c);assert.deepEqual(await readFile(c.currentDecisionPath),original);
 await writeFile(c.currentDecisionPath,JSON.stringify({...c.approval,revoked:true}));await assert.rejects(()=>requireCurrentRestoreDecision(c),/CHANGED_OR_REVOKED/);
 assert.equal(await readFile(c.currentDecisionPath,'utf8'),JSON.stringify({...c.approval,revoked:true}));
});
test('matching hash alone cannot substitute another owner or package decision',async()=>{
 const c=await configuration();c.currentDecisionPath=resolve(c.fileRoot,'..','unit-original-decision.json');const wrong=Buffer.from(JSON.stringify({...c.approval,owner:'OTHER'}));await writeFile(c.currentDecisionPath,wrong,{flag:'wx'});c.approval.currentDecisionEvidenceSha256=hash(wrong);
 await assert.rejects(()=>requireCurrentRestoreDecision(c),/BINDING_MISMATCH/);
});
test('CLI expired action refuses before target access or receipt creation',async()=>{
 const c=await configuration();c.approval.expiresAt='2000-01-01T00:00:00Z';const config=resolve(c.fileRoot,'..','expired.json'),receipt=resolve(c.fileRoot,'..','new-receipt.json'),journal=resolve(c.fileRoot,'..','new-journal.jsonl');await writeFile(config,JSON.stringify(c),{flag:'wx'});
 const result=spawnSync(process.execPath,['scripts/restore-single-database-lifecycle.mjs',config,receipt,journal],{encoding:'utf8',timeout:10000});
 assert.equal(result.status,1);assert.match(result.stderr,/EXACT_CURRENT_ACTION_APPROVAL_REQUIRED/);assert.doesNotMatch(result.stderr,/secret|password/i);await assert.rejects(()=>readFile(receipt),{code:'ENOENT'});await assert.rejects(()=>readFile(journal),{code:'ENOENT'});
});
test('CLI incomplete args has no action or package access',()=>{
 const r=spawnSync(process.execPath,['scripts/restore-single-database-lifecycle.mjs'],{encoding:'utf8',timeout:10000});assert.equal(r.status,2);assert.match(r.stderr,/Usage:/);
});

test('finite native window includes timeout margin and source package deadline, not only destination approval',async()=>{
 const c=await configuration();requireNativeRestoreWindow(c,{expiresAt:new Date(Date.now()+60000).toISOString(),databaseRights:c.approval.databaseRights,fileDigests:[]});
 assert.throws(()=>requireNativeRestoreWindow(c,{expiresAt:new Date(Date.now()+1000).toISOString(),databaseRights:c.approval.databaseRights,fileDigests:[]}),/SUFFICIENT_NATIVE_RESTORE_WINDOW_REQUIRED/);
 c.approval.databaseRights.useUntil=new Date(Date.now()+1000).toISOString();assert.throws(()=>requireNativeRestoreWindow(c),/SUFFICIENT_NATIVE_RESTORE_WINDOW_REQUIRED/);
});
