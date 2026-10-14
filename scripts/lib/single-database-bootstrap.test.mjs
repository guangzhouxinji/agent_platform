import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,appendFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {hash,canonical} from './single-database-lifecycle-runtime.mjs';
import {validateBootstrapManifest,checkBootstrapInventory,executeBootstrap} from './single-database-bootstrap.mjs';
const target='a'.repeat(64),names=['consultation','enforcement','adjudication'];
function fixture(full=false){
 const domains=names.map((domain,n)=>({domain,issuer:`https://identity.example.test/${domain}`,writerContainerIds:[],business:{schema:`BUS${n}`,jdbcUrl:'jdbc:dm://127.0.0.1:5492',ownerUser:`BUS${n}`,runtimeUser:`BUS${n}_SVC`},authority:{schema:`AUTH${n}`,jdbcUrl:'jdbc:dm://127.0.0.1:5492',ownerUser:`AUTH${n}`,runtimeUser:`AUTH${n}_SVC`}}));
 if(full)for(const d of domains)d.authority.backupUser=d.authority.schema+'_BAK';
 const accounts=domains.flatMap(d=>[d.business.ownerUser,d.business.runtimeUser,d.authority.ownerUser,d.authority.runtimeUser,...(full?[d.authority.backupUser]:[])]);
 const steps=accounts.flatMap(account=>[{kind:'create-user',account,passwordEnv:`SECRET_${account}`},{kind:'grant-system',account,privilege:'CREATE SESSION'},...(!account.endsWith('_SVC')&&!account.endsWith('_BAK')?[{kind:'grant-system',account,privilege:'CREATE TABLE'}]:[])]);
 const live={format:'SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1',targetId:target,actualJdbc:true,publicSystemPrivileges:[],publicRoles:[],accounts:accounts.map(name=>({name,users:[],systemPrivileges:[],roles:[],tableGrants:[],columnGrants:[],objects:[]}))};
 const manifest={format:'SINGLE_DATABASE_BOOTSTRAP/v1',targetId:target,deployment:{schema:full?'SINGLE_DM8_DEPLOYMENT/v2':'SINGLE_DM8_DEPLOYMENT/v1',database:{containerId:target,volume:'agent-platform-final-owned',ownerTask:'IMP-SINGLE-DB-FINAL-BASELINE-01',jdbcUrl:'jdbc:dm://127.0.0.1:5492',memoryLimitBytes:4294967296,nanoCpuLimit:2000000000},domains},initialInventorySha256:hash(canonical(live)),steps};
 const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId:target,action:'bootstrap',owner:'recorded-responsible-party',source:'recorded-current-original',approvedAt:'2026-10-08T00:00:00Z',expiresAt:'2099-10-08T00:00:00Z',currentMigration:false,connectionSwitch:false,deleteExisting:false,newEnvironmentOnly:true,manifestSha256:hash(canonical(manifest)),writers:[{id:'owned-maintenance',owner:'named-owner',responsibilitySource:'original-reference'}]};
 let calls=0,releases=0;
 const ports={manifest,approval,decisionCheck:async()=>{},assertTarget:async()=>{},acquireFence:async()=>({assertHeld:async()=>{},release:async()=>{releases++;}}),inventory:async()=>structuredClone(live),execute:async step=>{calls++;const a=live.accounts.find(a=>a.name===step.account);if(step.kind==='create-user')a.users=[{USERNAME:a.name,ACCOUNT_STATUS:'OPEN'}];else a.systemPrivileges.push({PRIVILEGE:step.privilege,ADMIN_OPTION:'NO'});}};
 return {manifest,approval,live,ports,get calls(){return calls;},get releases(){return releases;}};
}
const run=async(f)=>{f.ports.journalDir=await mkdtemp(path.join(os.tmpdir(),'single-bootstrap-'));return executeBootstrap(f.ports);};
test('explicit twelve accounts, three issuers and separate schema owners',()=>{const f=fixture();assert.equal(validateBootstrapManifest(f.manifest).accounts.length,12);checkBootstrapInventory(f.live,f.manifest,0);});
for(const [title,change,code] of [
 ['missing account',m=>m.steps=m.steps.filter(s=>s.account!=='BUS0_SVC'),'TWELVE_COMPLETE_ACCOUNTS_REQUIRED'],
 ['runtime DBA',m=>m.steps[4].privilege='DBA','LEAST_PRIVILEGE_ORDER_REQUIRED'],
 ['runtime RESOURCE',m=>m.steps.push({kind:'grant-system',account:'BUS0_SVC',privilege:'RESOURCE'}),'LEAST_PRIVILEGE_ORDER_REQUIRED'],
 ['runtime CREATE TABLE',m=>m.steps.push({kind:'grant-system',account:'BUS0_SVC',privilege:'CREATE TABLE'}),'LEAST_PRIVILEGE_ORDER_REQUIRED'],
 ['unknown operation',m=>m.steps.push({kind:'drop-user',account:'BUS0'}),'UNAPPROVED_BOOTSTRAP_OPERATION'],
 ['duplicate secret reference',m=>m.steps[3].passwordEnv=m.steps[0].passwordEnv,'EXACT_DISTINCT_ACCOUNT_SECRET_REFERENCES_REQUIRED'],
 ['nonmatching owner schema',m=>m.deployment.domains[0].business.ownerUser='OTHEROWNER','OWNER_SCHEMA_MUST_MATCH'],
 ['endpoint split',m=>m.deployment.domains[1].business.jdbcUrl='jdbc:dm://127.0.0.1:5301','SIX_SCHEMAS_ONE_ENDPOINT_REQUIRED']])test(title,()=>{const f=fixture();change(f.manifest);assert.throws(()=>validateBootstrapManifest(f.manifest),new RegExp(code));});
for(const [title,change,code] of [
 ['preexisting objects',i=>i.accounts[0].objects=[{OBJECT_NAME:'LEGACY'}],'BOOTSTRAP_NONEMPTY_OR_UNAPPROVED_GRANT'],
 ['preexisting user',i=>i.accounts[0].users=[{USERNAME:'BUS0',ACCOUNT_STATUS:'OPEN'}],'BOOTSTRAP_ACCOUNT_DRIFT'],
 ['role escalation',i=>i.accounts[0].roles=[{GRANTED_ROLE:'DBA'}],'BOOTSTRAP_NONEMPTY_OR_UNAPPROVED_GRANT'],
 ['public any table',i=>i.publicSystemPrivileges=[{PRIVILEGE:'SELECT ANY TABLE',ADMIN_OPTION:'NO'}],'PUBLIC_PRIVILEGE_REVIEW_REQUIRED'],
 ['public role',i=>i.publicRoles=[{GRANTED_ROLE:'RESOURCE'}],'PUBLIC_PRIVILEGE_REVIEW_REQUIRED'],
 ['missing inventory array',i=>delete i.accounts[0].columnGrants,'BOOTSTRAP_PARTIAL_INVENTORY'],
 ['duplicate account inventory',i=>i.accounts[1].name=i.accounts[0].name,'BOOTSTRAP_ACCOUNT_SET_MISMATCH'],
 ['wrong target',i=>i.targetId='b'.repeat(64),'BOOTSTRAP_ACTUAL_INVENTORY_REQUIRED'],
 ['admin privilege',i=>i.accounts[0].systemPrivileges=[{PRIVILEGE:'CREATE SESSION',ADMIN_OPTION:'YES'}],'BOOTSTRAP_PRIVILEGE_DRIFT']])test(title,()=>{const f=fixture();change(f.live);assert.throws(()=>checkBootstrapInventory(f.live,f.manifest,0),new RegExp(code));});
test('actual postconditions and repeat do not blindly replay DDL; ports not DM8 proof',async()=>{const f=fixture(),r=await run(f);assert.equal(r.applied,30);assert.equal(r.productInitialized,false);assert.equal(r.ddlAtomicityClaimed,false);const calls=f.calls;const repeat=await executeBootstrap(f.ports);assert.equal(repeat.applied,0);assert.equal(repeat.skipped,30);assert.equal(f.calls,calls);assert.equal(f.releases,2);});
test('failed non-atomic DDL scene retained; repeated attempt refused',async()=>{const f=fixture(),exec=f.ports.execute;let n=0;f.ports.execute=async s=>{await exec(s);if(++n===2)throw new Error('driver secret should not leak');};await assert.rejects(run(f),/BOOTSTRAP_FAILED_PARTIAL_PRESERVED/);const events=await readFile(path.join(f.ports.journalDir,'bootstrap.jsonl'),'utf8');assert.match(events,/"rollbackClaimed":false/);assert.ok(!events.includes('driver secret'));assert.ok(await readFile(path.join(f.ports.journalDir,'bootstrap-failure-1.json')));const calls=f.calls;await assert.rejects(executeBootstrap(f.ports),/PARTIAL_BOOTSTRAP_REQUIRES_EXPLICIT_RECOVERY/);assert.equal(f.calls,calls);});
test('tool exit zero without object/account change fails actual postcheck',async()=>{const f=fixture();f.ports.execute=async()=>({executedSteps:1});await assert.rejects(run(f),/BOOTSTRAP_FAILED_PARTIAL_PRESERVED/);});
test('manifest digest mismatch before write',async()=>{const f=fixture();f.approval.manifestSha256='b'.repeat(64);await assert.rejects(run(f),/EXACT_NEW_ENVIRONMENT_APPROVAL_REQUIRED/);assert.equal(f.calls,0);});
test('expired decision before write',async()=>{const f=fixture();f.approval.expiresAt='2026-10-07T00:00:00Z';await assert.rejects(run(f),/EXACT_CURRENT_ACTION_APPROVAL_REQUIRED/);assert.equal(f.calls,0);});
test('decision revocation during DDL preserves partial scene',async()=>{const f=fixture();f.ports.decisionCheck=async()=>{if(f.calls>0)throw Error('REVOKED');};await assert.rejects(run(f),/BOOTSTRAP_FAILED_PARTIAL_PRESERVED/);assert.equal(f.calls,1);});
test('initial observed structure differs from approved SHA',async()=>{const f=fixture();f.manifest.initialInventorySha256='b'.repeat(64);f.approval.manifestSha256=hash(canonical(f.manifest));await assert.rejects(run(f),/BOOTSTRAP_INITIAL_INVENTORY_SHA_MISMATCH/);assert.equal(f.calls,0);});
test('unregistered writers rejected without DDL',async()=>{const f=fixture();f.approval.writers=[];await assert.rejects(run(f),/COMPLETE_WRITER_RESPONSIBILITY_REQUIRED/);assert.equal(f.calls,0);});
test('reentry detects missing privilege, never fills it by guessing',async()=>{const f=fixture();await run(f);f.live.accounts[0].systemPrivileges=[];const calls=f.calls;await assert.rejects(executeBootstrap(f.ports),/BOOTSTRAP_PRIVILEGE_DRIFT/);assert.equal(f.calls,calls);});
test('journal tampering refused',async()=>{const f=fixture();await run(f);await appendFile(path.join(f.ports.journalDir,'bootstrap.jsonl'),'{}\n');await assert.rejects(executeBootstrap(f.ports),/BOOTSTRAP_JOURNAL_MISMATCH/);});


test('v2 bootstrap creates 15 exact independent accounts; backups only CREATE SESSION',async()=>{const f=fixture(true);assert.equal(validateBootstrapManifest(f.manifest).accounts.length,15);await run(f);checkBootstrapInventory(f.live,f.manifest,f.manifest.steps.length);assert.equal(f.live.accounts.length,15);for(const a of f.live.accounts.filter(a=>a.name.endsWith('_BAK')))assert.deepEqual(a.systemPrivileges,[{PRIVILEGE:'CREATE SESSION',ADMIN_OPTION:'NO'}]);});
test('v2 bootstrap refuses backup DDL privileges',()=>{const f=fixture(true);f.manifest.steps.push({kind:'grant-system',account:'AUTH0_BAK',privilege:'CREATE TABLE'});assert.throws(()=>validateBootstrapManifest(f.manifest),/LEAST_PRIVILEGE/);});

test('explicit exact failed-create prefix recovery preserves originals and never repeats CREATE',async()=>{
 const f=fixture(),oldExecute=f.ports.execute;let first=true;
 f.ports.execute=async s=>{await oldExecute(s);if(first){first=false;throw Error('NON_ATOMIC_POST_FAILURE');}};
 await assert.rejects(run(f),/BOOTSTRAP_FAILED_PARTIAL_PRESERVED/);
 const originalDir=f.ports.journalDir,configPath=path.join(originalDir,'original-config.json');
 const {writeFile}=await import('node:fs/promises');
 await writeFile(configPath,JSON.stringify({targetId:target,manifest:f.manifest}));
 const journalPath=path.join(originalDir,'bootstrap.jsonl'),scenePath=path.join(originalDir,'bootstrap-failure-0.json');
 const before=await readFile(journalPath);
 f.approval.recovery={format:'BOOTSTRAP_EXACT_CREATE_PREFIX_RECOVERY/v1',prefixSteps:1,sourceConfigurationPath:configPath,sourceConfigurationSha256:hash(await readFile(configPath)),sourceJournalPath:journalPath,sourceJournalSha256:hash(before),sourceScenePath:scenePath,sourceSceneSha256:hash(await readFile(scenePath))};
 f.manifest.initialInventorySha256=hash(canonical(f.live));f.approval.manifestSha256=hash(canonical(f.manifest));
 f.ports.journalDir=await mkdtemp(path.join(os.tmpdir(),'explicit-bootstrap-recovery-'));f.ports.execute=oldExecute;
 const r=await executeBootstrap(f.ports);assert.equal(r.skipped,1);assert.equal(f.calls,f.manifest.steps.length);assert.deepEqual(await readFile(journalPath),before);
 assert.match(await readFile(path.join(f.ports.journalDir,'bootstrap.jsonl'),'utf8'),/RECOVERY_BASELINE/);
 const calls=f.calls;const repeat=await executeBootstrap(f.ports);assert.equal(repeat.applied,0);assert.equal(f.calls,calls);
});
