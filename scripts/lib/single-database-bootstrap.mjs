import {hasNativePrivileges,assertNativeAccountRoles,nativeAccountDefaultsSha256} from './single-database-native-privileges.mjs';
import {open, readFile, mkdir, unlink} from 'node:fs/promises';
import path from 'node:path';
import {validateDeploymentConfiguration} from './single-database-lifecycle.mjs';
import {hash,canonical,admission} from './single-database-lifecycle-runtime.mjs';
const fail=c=>{throw new Error(c);}, SHA=/^[a-f0-9]{64}$/;
const exact=(v,ks)=>{if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).some(k=>!ks.includes(k))||ks.some(k=>!Object.hasOwn(v,k)))fail('BOOTSTRAP_FIELDS_INVALID');};
export function validateBootstrapManifest(m) {
  exact(m,['format','targetId','deployment','initialInventorySha256','steps']);
  if(m.format!=='SINGLE_DATABASE_BOOTSTRAP/v1'||!SHA.test(m.initialInventorySha256))fail('BOOTSTRAP_FORMAT_INVALID');
  validateDeploymentConfiguration(m.deployment);
  if(m.targetId!==m.deployment.database.containerId)fail('BOOTSTRAP_TARGET_MISMATCH');
  const expected=new Map();
  for(const d of m.deployment.domains)for(const kind of ['business','authority']) {
    const b=d[kind];if(b.ownerUser!==b.schema)fail('OWNER_SCHEMA_MUST_MATCH');
    expected.set(b.ownerUser,'owner');expected.set(b.runtimeUser,'runtime');
    if(kind==='authority'&&m.deployment.schema==='SINGLE_DM8_DEPLOYMENT/v2')expected.set(b.backupUser,'backup');
  }
  if(!Array.isArray(m.steps)||!m.steps.length)fail('BOOTSTRAP_STEPS_REQUIRED');
  const created=new Set(),privileges=new Map(),passwordRefs=new Set();
  for(const s of m.steps) {
    if(s.kind==='create-user') {
      exact(s,['kind','account','passwordEnv',...(s.nativeDefaultsSha256?['nativeDefaultsSha256']:[])]);
      if(s.nativeDefaultsSha256&&s.nativeDefaultsSha256!==nativeAccountDefaultsSha256)fail('EXACT_NATIVE_ACCOUNT_DEFAULTS_REQUIRED');
      if(!expected.has(s.account)||created.has(s.account)||!/^[A-Z][A-Z0-9_]{0,127}$/.test(s.passwordEnv)||passwordRefs.has(s.passwordEnv))fail('EXACT_DISTINCT_ACCOUNT_SECRET_REFERENCES_REQUIRED');
      created.add(s.account);passwordRefs.add(s.passwordEnv);privileges.set(s.account,new Set(s.nativeDefaultsSha256?['CREATE SESSION']:[]));
    } else if(s.kind==='grant-system') {
      exact(s,['kind','account','privilege']);
      const allowed=expected.get(s.account)==='owner'?['CREATE SESSION','CREATE TABLE','CREATE VIEW','CREATE SEQUENCE','CREATE INDEX']:['CREATE SESSION'];
      if(!created.has(s.account)||!allowed.includes(s.privilege)||privileges.get(s.account).has(s.privilege))fail('LEAST_PRIVILEGE_ORDER_REQUIRED');
      privileges.get(s.account).add(s.privilege);
    } else fail('UNAPPROVED_BOOTSTRAP_OPERATION');
  }
  if(created.size!==expected.size||[...privileges.values()].some(p=>!p.has('CREATE SESSION')))fail('TWELVE_COMPLETE_ACCOUNTS_REQUIRED');
  return {accounts:[...expected.keys()].sort(),privileges};
}
export function checkBootstrapInventory(i,m,completed) {
  const {accounts}=validateBootstrapManifest(m);
  if(i?.format!=='SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1'||i.targetId!==m.targetId||i.actualJdbc!==true||!Array.isArray(i.accounts)||i.accounts.length!==accounts.length||!Array.isArray(i.publicSystemPrivileges)||!Array.isArray(i.publicRoles))fail('BOOTSTRAP_ACTUAL_INVENTORY_REQUIRED');
  // PUBLIC is an effective privilege source: no permissive admin/system/role inheritance.
  const native=hasNativePrivileges(i);
  if(!native&&(i.publicRoles.length||i.publicSystemPrivileges.some(p=>p.PRIVILEGE!=='CREATE SESSION'||p.ADMIN_OPTION!=='NO')))fail('PUBLIC_PRIVILEGE_REVIEW_REQUIRED');
  if(new Set(i.accounts.map(a=>a.name)).size!==accounts.length||i.accounts.some(a=>!accounts.includes(a.name)))fail('BOOTSTRAP_ACCOUNT_SET_MISMATCH');
  const created=new Set(),grants=new Map();
  for(const s of m.steps.slice(0,completed)){if(s.kind==='create-user'){created.add(s.account);grants.set(s.account,s.nativeDefaultsSha256?['CREATE SESSION']:[]);}else if(s.kind==='grant-system')grants.get(s.account).push(s.privilege);}
  for(const a of i.accounts) {
    if(!['users','systemPrivileges','roles','tableGrants','columnGrants','objects'].every(k=>Array.isArray(a[k])))fail('BOOTSTRAP_PARTIAL_INVENTORY');
    if(native)assertNativeAccountRoles(a.roles,created.has(a.name)?['PUBLIC']:[]);
    if((native? a.objects.length!==(created.has(a.name)?1:0)||a.objects.some(o=>o.OBJECT_TYPE!=='SCH'||o.OBJECT_NAME!==a.name):a.objects.length)||(!native&&a.roles.length)||a.tableGrants.length||a.columnGrants.length)fail('BOOTSTRAP_NONEMPTY_OR_UNAPPROVED_GRANT');
    if(a.users.length!==(created.has(a.name)?1:0))fail('BOOTSTRAP_ACCOUNT_DRIFT');
    if(a.users.some(u=>u.USERNAME!==a.name||u.ACCOUNT_STATUS!=='OPEN'))fail('BOOTSTRAP_ACCOUNT_STATE_DRIFT');
    const want=(grants.get(a.name)||[]).sort(),actual=a.systemPrivileges.map(p=>p.PRIVILEGE).sort();
    if(canonical(want)!==canonical(actual)||a.systemPrivileges.some(p=>p.ADMIN_OPTION!=='NO'))fail('BOOTSTRAP_PRIVILEGE_DRIFT');
  }
  return i;
}
/** Typed admin port, never application Service. Port tests are not real DM8 evidence. */
export async function executeBootstrap({manifest:m,approval,decisionCheck,assertTarget,acquireFence,inventory,execute,journalDir,now=()=>Date.now()}) {
  validateBootstrapManifest(m);
  const current=async()=>{admission(approval,m.targetId,'bootstrap',now());if(approval.manifestSha256!==hash(canonical(m))||approval.newEnvironmentOnly!==true)fail('EXACT_NEW_ENVIRONMENT_APPROVAL_REQUIRED');await decisionCheck();await assertTarget();};
  await current();
  if(!Array.isArray(approval.writers)||!approval.writers.length||approval.writers.some(w=>!w.id||!w.owner||!w.responsibilitySource))fail('COMPLETE_WRITER_RESPONSIBILITY_REQUIRED');
  const lease=await acquireFence(approval.writers);if(!lease?.assertHeld||!lease?.release)fail('BOOTSTRAP_FENCE_REQUIRED');
  let lock;
  try {
    await mkdir(journalDir,{recursive:true});lock=await open(path.join(journalDir,'bootstrap.lock'),'wx');
    const journal=path.join(journalDir,'bootstrap.jsonl');let events=[];
    try{events=(await readFile(journal,'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);}catch(e){if(e.code!=='ENOENT')throw e;}
    let previousSha256='',completed=0,started=false;
    const recovery=approval.recovery;
    if(recovery){
      if(recovery.format!=='BOOTSTRAP_EXACT_CREATE_PREFIX_RECOVERY/v1'||recovery.prefixSteps!==1)fail('EXACT_PREFIX_RECOVERY_REQUIRED');
      const sourceBytes=await readFile(recovery.sourceConfigurationPath),journalBytes=await readFile(recovery.sourceJournalPath),sceneBytes=await readFile(recovery.sourceScenePath);
      if(hash(sourceBytes)!==recovery.sourceConfigurationSha256||hash(journalBytes)!==recovery.sourceJournalSha256||hash(sceneBytes)!==recovery.sourceSceneSha256)fail('RECOVERY_ORIGINAL_SOURCE_CHANGED');
      const old=JSON.parse(sourceBytes),sourceEvents=journalBytes.toString().trim().split('\n').map(JSON.parse);let head='';
      for(const event of sourceEvents){const {eventSha256,...body}=event;if(body.previousSha256!==head||hash(canonical(body))!==eventSha256||body.targetId!==m.targetId||body.manifestSha256!==hash(canonical(old.manifest)))fail('RECOVERY_ORIGINAL_JOURNAL_INVALID');head=eventSha256;}
      const first=old.manifest.steps[0],next=m.steps[0];
      if(old.targetId!==m.targetId||sourceEvents.length!==2||sourceEvents[0].kind!=='START'||sourceEvents[1].kind!=='FAILURE'||sourceEvents.some(e=>e.step!==0)||first.kind!=='create-user'||next.kind!==first.kind||first.account!==next.account||first.passwordEnv!==next.passwordEnv||sourceEvents[1].sceneSha256!==hash(canonical(JSON.parse(sceneBytes))))fail('EXACT_FAILED_CREATE_RECOVERY_REQUIRED');
      checkBootstrapInventory(JSON.parse(sceneBytes),m,1);completed=1;
    }
    for(const e of events) {
      const {eventSha256,...body}=e;
      if(body.previousSha256!==previousSha256||hash(canonical(body))!==eventSha256||body.targetId!==m.targetId||body.manifestSha256!==approval.manifestSha256)fail('BOOTSTRAP_JOURNAL_MISMATCH');
      if(body.kind==='RECOVERY_BASELINE'&&recovery&&e===events[0]&&body.step===1&&body.recoverySha256===hash(canonical(recovery))){} 
      else if(body.kind==='START'&&body.step===completed&&!started)started=true;
      else if(body.kind==='SUCCESS'&&body.step===completed&&started){started=false;completed++;}
      else if(body.kind!=='FAILURE')fail('BOOTSTRAP_JOURNAL_SEQUENCE_INVALID');
      previousSha256=eventSha256;
    }
    if(started||events.some(e=>e.kind==='FAILURE')||completed>m.steps.length)fail('PARTIAL_BOOTSTRAP_REQUIRES_EXPLICIT_RECOVERY');
    const append=async body=>{body={...body,targetId:m.targetId,manifestSha256:approval.manifestSha256,at:new Date(now()).toISOString(),previousSha256};previousSha256=hash(canonical(body));const f=await open(journal,'a');try{await f.writeFile(JSON.stringify({...body,eventSha256:previousSha256})+'\n');await f.sync();}finally{await f.close();}};
    await lease.assertHeld();await current();let live=checkBootstrapInventory(await inventory(),m,completed);
    if(!events.length&&hash(canonical(live))!==m.initialInventorySha256)fail('BOOTSTRAP_INITIAL_INVENTORY_SHA_MISMATCH');
    if(events.length&&events.at(-1).inventorySha256!==hash(canonical(live)))fail('BOOTSTRAP_REENTRY_INVENTORY_DRIFT');
    if(!events.length&&recovery)await append({kind:'RECOVERY_BASELINE',step:completed,recoverySha256:hash(canonical(recovery)),inventorySha256:hash(canonical(live)),rollbackClaimed:false});
    const skipped=completed;
    for(;completed<m.steps.length;completed++) {
      await current();await lease.assertHeld();
      const before=checkBootstrapInventory(await inventory(),m,completed);
      if(hash(canonical(before))!==hash(canonical(live)))fail('BOOTSTRAP_BETWEEN_STEP_DRIFT');
      await append({kind:'START',step:completed,inventorySha256:hash(canonical(live))});
      try {
        await execute(m.steps[completed]);await current();await lease.assertHeld();
        live=checkBootstrapInventory(await inventory(),m,completed+1);await current();await lease.assertHeld();
        await append({kind:'SUCCESS',step:completed,inventorySha256:hash(canonical(live))});
      } catch {
        let scene;try{scene=await inventory();}catch{scene={unavailable:true};}
        const file=path.join(journalDir,`bootstrap-failure-${completed}.json`),f=await open(file,'wx');try{await f.writeFile(JSON.stringify(scene)+'\n');await f.sync();}finally{await f.close();}
        await append({kind:'FAILURE',step:completed,sceneSha256:hash(canonical(scene)),rollbackClaimed:false});fail('BOOTSTRAP_FAILED_PARTIAL_PRESERVED');
      }
    }
    await current();await lease.assertHeld();live=checkBootstrapInventory(await inventory(),m,completed);
    if(events.length&&completed===skipped&&events.at(-1).inventorySha256!==hash(canonical(live)))fail('BOOTSTRAP_REENTRY_INVENTORY_DRIFT');
    return {format:'SINGLE_DATABASE_BOOTSTRAP_RECEIPT/v1',targetId:m.targetId,applied:completed-skipped,skipped,inventorySha256:hash(canonical(live)),journalHead:previousSha256,ddlAtomicityClaimed:false,productInitialized:false};
  }finally{if(lock){await lock.close();await unlink(path.join(journalDir,'bootstrap.lock'));}await lease.release();}
}
