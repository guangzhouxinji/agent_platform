import fs from 'node:fs/promises';
import path from 'node:path';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {CONTINUOUS75 as C,assertOriginalResources75,assertHostResponsibility75,assertWriterNetworks78,assertBridge78} from './consultation-activation-recovery-continuous-fence.mjs';

export const RECONCILIATION81=Object.freeze({
  task:'REC-CON-ACTR78-FAILED-CONTROLLER-01',
  bundle:'reconciliation82-manifest.json',
  failedRun:'7fec27da-6018-49ed-934c-08c67979d144',
  marker:'7fec27da-6018-49ed-934c-08c67979d144 44916 1f50d164-2cbe-40db-8320-a38fecc506fb',
  markerSha256:'d1482d5908144b2817f7796910f92fc32eda02443925ebd32febabfb2e3f18f0',
  identity:Object.freeze({dev:'3838357650',ino:'2251799815217101',birthtimeNs:'1791701918323075400',ctimeNs:'1791701918323075400',size:'79'}),
  sourcePid:60521,sourceStartedAt:'2026-10-10T13:35:51.942783827Z',
  baseline:C.root+'/run-7fec27da-6018-49ed-934c-08c67979d144/source-baseline.json',
  baselineSha256:'88c90427ae8714315d19ab06282cd5fdb3f4eab8e19b8bad276631c9e4f9a625',
  controller:C.root+'/continuous-source-target.lock',
  exclusive:C.root+'/failed-controller-reconciliation.lock',
});
const require=(yes,code)=>{if(!yes)throw Error(code);};
export const reconciliationCode81=e=>/^[A-Z][A-Z0-9_]{0,160}$/.test(e?.message??'')?e.message:'RECONCILIATION_UNKNOWN_FAILURE';
export const lockIdentity81=s=>Object.fromEntries(Object.keys(RECONCILIATION81.identity).map(k=>[k,String(s[k])]));
export function assertFailedMarker81(bytes,identity){
  require(Buffer.isBuffer(bytes)&&bytes.toString()===RECONCILIATION81.marker&&hash(bytes)===RECONCILIATION81.markerSha256,'RECONCILIATION_ORIGINAL_MARKER_CHANGED');
  require(canonical(identity)===canonical(RECONCILIATION81.identity),'RECONCILIATION_ORIGINAL_FILE_IDENTITY_CHANGED');
}
export function assertReconciliationTasks81(tasks){
  for(const id of ['ARC-CON-ACTR77-FAILURE-RETURN-D-01','FIX-CON-ACTR78-WORKER-RETURN-01','REC-CON-ACTR77-SAFE-RETURN-01']){
    const rows=tasks.filter(t=>t.taskId===id);require(rows.length===1&&rows[0]['状态']==='已完成','RECONCILIATION_DEPENDENCIES_REQUIRED');
  }
  const rows=tasks.filter(t=>t.taskId===RECONCILIATION81.task);
  require(rows.length===1&&['准备中','进行中'].includes(rows[0]['状态']),'RECONCILIATION_CURRENT_TASK_REQUIRED');
}
export function assertReconciliationBundle81(tasks,digest){
  assertReconciliationTasks81(tasks);
  const text=Object.values(tasks.find(t=>t.taskId===RECONCILIATION81.task)).join(' ');
  const links=[...text.matchAll(/\]\(([^()\s]+)\)/g)].map(m=>m[1]);
  require(links.includes(C.root.replace(/^docs\//,'')+'/'+RECONCILIATION81.bundle)&&(text.match(/\b[a-f0-9]{64}\b/g)??[]).includes(digest),'RECONCILIATION_CURRENT_BUNDLE_REFERENCE_REQUIRED');
}
export const PREVIOUS_RECONCILIATION81=Object.freeze({
  runId:'5f2f3828-04fc-4bd4-848d-5134fef8c3c8',ownerPid:39324,
  marker:'{"runId":"5f2f3828-04fc-4bd4-848d-5134fef8c3c8","ownerPid":39324,"failedRun":"7fec27da-6018-49ed-934c-08c67979d144"}\n',
  sha256:'8b6cdb19f83f4e1f1b78c0e8ec703cad27295228a1aa6c6d7fbdaaf55240d37a',
  identity:Object.freeze({dev:'3838357650',ino:'1688849861848710',birthtimeNs:'1791711470808749300',ctimeNs:'1791711470808749300',size:'117'}),
  failureSha256:'625444866a84795d7ed7b4c9a8a326bafc394a64b4e87e2ddc37893ca6c399a8',
});
export function assertPreviousExclusive81(bytes,identity,failure,owners){
  const p=PREVIOUS_RECONCILIATION81;
  require(Buffer.isBuffer(bytes)&&bytes.toString()===p.marker&&hash(bytes)===p.sha256&&canonical(identity)===canonical(p.identity),'RECONCILIATION_PREVIOUS_EXCLUSIVE_CHANGED');
  require(failure?.format==='ACTR81_RECONCILIATION_FAILURE/v1'&&failure.runId===p.runId&&failure.code==='RECONCILIATION_PUBLIC_CA_REDIRECT'&&failure.automaticRetryAllowed===false&&failure.originalCaseSucceeded===false,'RECONCILIATION_PREVIOUS_FAILURE_NOT_REVIEWED');
  require(Array.isArray(owners)&&owners.length===0,'RECONCILIATION_PREVIOUS_OWNER_LIVE');
}
export const PUBLIC_CA81=Object.freeze({logical:'C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer',physical:'C:/Users/60329/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer',sha256:'ab658c0492f34775181a5a97cc46fd2ecf04bcf1ecbe08f073124fadd0eeb9f0'});
export function assertPublicCaReference81(ref){
  require(ref?.format==='ACTR82_PUBLIC_CA_REFERENCE/v1'&&ref.logical===PUBLIC_CA81.logical&&ref.sha256===PUBLIC_CA81.sha256&&[PUBLIC_CA81.logical,PUBLIC_CA81.physical].includes(ref.path),'RECONCILIATION_EXACT_PUBLIC_CA_BINDING_REQUIRED');
}
export function assertReconciliationFacts81(facts,prior,host,bridge,selectedOwners){
  assertOriginalResources75(facts,prior);assertHostResponsibility75(facts,host);assertBridge78(bridge);
  require(Array.isArray(selectedOwners)&&selectedOwners.length===0,'RECONCILIATION_SELECTED_OWNER_STILL_LIVE');
  const live=[C.source,C.sourceTool,...C.writers],by=new Map(facts.containers.map(c=>[c.Id,c]));
  for(const id of live){const s=by.get(id).State;require(s.Running===true&&s.Restarting===false&&Number.isSafeInteger(s.Pid)&&s.Pid>0,'RECONCILIATION_ORIGINAL_ROLE_NOT_RUNNING');}
  for(const id of [C.target,C.tool]){const s=by.get(id).State;require(s.Running===false&&s.Restarting===false&&s.Pid===0,'RECONCILIATION_TARGET_MUST_REMAIN_STOPPED');}
  const source=by.get(C.source).State;
  require(source.Pid===RECONCILIATION81.sourcePid&&source.StartedAt===RECONCILIATION81.sourceStartedAt,'RECONCILIATION_SOURCE_RESTARTED');
  require(canonical([...facts.ownedRunning].sort())===canonical([...live].sort())&&canonical(Object.keys(facts.network.Containers??{}).sort())===canonical([...live].sort()),'RECONCILIATION_UNKNOWN_PROJECT_WRITER');
  require(live.reduce((n,id)=>n+by.get(id).HostConfig.Memory,0)<=7516192768&&live.reduce((n,id)=>n+by.get(id).HostConfig.NanoCpus,0)<=4000000000,'RECONCILIATION_RESOURCE_BUDGET_EXCEEDED');
  for(const id of C.writers)assertWriterNetworks78(id,by.get(id).NetworkSettings.Networks,prior.records.find(r=>r.id===id).networks,bridge);
}
export function completeSourceDigest81(i){
  require(i?.actualJdbc===true&&i.complete===true&&i.targetId===C.source&&i.schemas?.length===6&&i.accounts?.length===15&&i.restoreCatalog&&i.nativePrivileges&&!(i.errors?.length),'RECONCILIATION_COMPLETE_SOURCE_REQUIRED');
  return hash(canonical({schemas:i.schemas,accounts:i.accounts,restoreCatalog:i.restoreCatalog,nativePrivileges:i.nativePrivileges}));
}
export function assertSourceEquals81(actual,baseline){
  require(completeSourceDigest81(actual)===completeSourceDigest81(baseline),'RECONCILIATION_SOURCE_INVENTORY_CHANGED');
}
export function assertReconciliationTls81(proof){
  const expected=['/app/consultation','/.well-known/openid-configuration','/login'];
  require(proof?.format==='ACTR66_CURRENT_PUBLIC_TLS_GET/v1'&&proof.requests?.length===3&&new Set(proof.requests.map(r=>r.path)).size===3,'RECONCILIATION_STRICT_TLS_REQUIRED');
  require(expected.every(p=>proof.requests.some(r=>r.path===p&&r.status===200&&r.clientCertificateCount===0&&r.serverSha256==='de4f652f8f99db36e7b7049fab104de0c0918a0e68dcc2dbd26fad6451374f06'&&/^[a-f0-9]{64}$/.test(r.bodySha256)&&Number.isSafeInteger(r.bytes)&&r.bytes>=0&&r.bytes<=1048576)),'RECONCILIATION_STRICT_TLS_REQUIRED');
}
export async function checkedReconciliationPath81(root,relative,{newFile=false}={}){
  require(typeof relative==='string'&&!path.isAbsolute(relative)&&!relative.includes('\\')&&relative.split('/').every(p=>p&&p!=='.'&&p!=='..'),'RECONCILIATION_PATH_REFUSED');
  root=path.resolve(root);require(await fs.realpath(root)===root,'RECONCILIATION_ROOT_REDIRECT');
  let current=root;const parts=relative.split('/');
  for(let n=0;n<parts.length;n++){
    current=path.join(current,parts[n]);if(newFile&&n===parts.length-1)break;
    const s=await fs.lstat(current);require(!s.isSymbolicLink()&&await fs.realpath(current)===current,'RECONCILIATION_PATH_REDIRECT');
  }
  return current;
}
export async function readReconciliationFile81(root,relative,pin,max=32*1024*1024){
  const file=await fs.open(await checkedReconciliationPath81(root,relative),'r');
  try{
    const before=await file.stat({bigint:true});require(before.isFile()&&before.size<=BigInt(max),'RECONCILIATION_BOUNDED_FILE_REQUIRED');
    const bytes=await file.readFile();const after=await file.stat({bigint:true});
    require(bytes.length<=max&&canonical(lockIdentity81(before))===canonical(lockIdentity81(after)),'RECONCILIATION_FILE_CHANGED_DURING_READ');
    if(pin)require(hash(bytes)===pin,'RECONCILIATION_PIN_CHANGED');return bytes;
  }finally{await file.close();}
}
export async function writeReconciliationFile81(root,relative,value){
  const bytes=Buffer.isBuffer(value)?value:Buffer.from(JSON.stringify(value,null,2)+'\n');
  const handle=await fs.open(await checkedReconciliationPath81(root,relative,{newFile:true}),'wx',0o600);
  try{await handle.writeFile(bytes);await handle.sync();}finally{await handle.close();}
  return {path:relative,sha256:hash(bytes)};
}
export async function readOriginalController81(root){
  const p=await checkedReconciliationPath81(root,RECONCILIATION81.controller),h=await fs.open(p,'r');
  try{
    const s=await h.stat({bigint:true});require(s.isFile()&&s.size===79n,'RECONCILIATION_ORIGINAL_REGULAR_LOCK_REQUIRED');
    const bytes=await h.readFile(),identity=lockIdentity81(s);
    assertFailedMarker81(bytes,identity);
    require(canonical(lockIdentity81(await fs.lstat(p,{bigint:true})))===canonical(identity)&&canonical(lockIdentity81(await h.stat({bigint:true})))===canonical(identity),'RECONCILIATION_LOCK_PATH_CHANGED');
    return {bytes,identity};
  }finally{await h.close();}
}
export async function archiveOwnedExclusive81(root,relative,bytes,identity){
  require(new RegExp('^'+C.root+'/reconcile-[a-f0-9-]{36}$').test(relative),'RECONCILIATION_OWNED_MUTEX_ARCHIVE_REQUIRED');
  const same=async()=>{
    require((await readReconciliationFile81(root,RECONCILIATION81.exclusive,hash(bytes))).equals(bytes),'RECONCILIATION_EXCLUSIVE_OWNER_CHANGED');
    require(canonical(lockIdentity81(await fs.lstat(await checkedReconciliationPath81(root,RECONCILIATION81.exclusive),{bigint:true})))===canonical(identity),'RECONCILIATION_EXCLUSIVE_FILE_REPLACED');
  };
  await same();const ref=await writeReconciliationFile81(root,relative+'/original-reconciliation.lock',bytes);
  const destination=await checkedReconciliationPath81(root,relative+'/retired-reconciliation.lock',{newFile:true});
  try{await fs.lstat(destination);throw Error('RECONCILIATION_MUTEX_ARCHIVE_EXISTS');}catch(e){if(e.code!=='ENOENT')throw e;}
  await same();await fs.rename(await checkedReconciliationPath81(root,RECONCILIATION81.exclusive),destination);
  const retired=await fs.lstat(destination,{bigint:true});
  require(String(retired.dev)===identity.dev&&String(retired.ino)===identity.ino&&String(retired.birthtimeNs)===identity.birthtimeNs,'RECONCILIATION_MUTEX_ARCHIVE_IDENTITY_CHANGED');
  await readReconciliationFile81(root,relative+'/retired-reconciliation.lock',ref.sha256);return ref;
}

// The internal ports cannot be selected from CLI input; failure never enables a retry.
export function createFailedControllerReconciliation81(ports){
  let used=false;
  return {execute:async()=>{
    require(!used,'RECONCILIATION_ATTEMPT_ALREADY_USED');used=true;let exclusive;
    try{
      await ports.assertCurrent('ADMIT');exclusive=await ports.acquireExclusive();
      await ports.assertCurrent('BEFORE_SOURCE_READ');
      const baseline=await ports.baseline(),before=await ports.inventory('before');assertSourceEquals81(before,baseline);
      await ports.assertCurrent('BEFORE_TLS');const tls=await ports.tls();assertReconciliationTls81(tls);
      await ports.assertCurrent('AFTER_TLS');const after=await ports.inventory('after');assertSourceEquals81(after,baseline);
      await ports.assertCurrent('BEFORE_ARCHIVE');const archive=await ports.archive({before:completeSourceDigest81(before),after:completeSourceDigest81(after),tls});
      await ports.assertCurrent('BEFORE_RETIRE');await ports.retire(archive);
      const result={sourceInventoryCompared:true,controllerLockRetired:true,originalCaseSucceeded:false,sqlWrites:0,resourceMutations:0};
      await ports.recordSuccess(result);await exclusive.release();return result;
    }catch(e){try{await ports.recordFailure(reconciliationCode81(e));}catch{}throw e;}
    finally{await ports.clearSecret();await exclusive?.close();}
  }};
}
