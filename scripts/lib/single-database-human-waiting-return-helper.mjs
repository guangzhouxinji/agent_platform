import {open,lstat,realpath} from 'node:fs/promises';
import path from 'node:path';
import {hash,canonical,admission} from './single-database-lifecycle-runtime.mjs';
export const fail=code=>{throw new Error(code);};
const SHA=/^[a-f0-9]{64}$/;
export function returnConfiguration(c) {
  const r=c?.sourceReturn,a=c?.approval;
  if(r?.format!=='HUMAN_WAITING_SOURCE_RETURN/v1'||!['packageSha256','bindingSha256','operationEvidenceSha256','restoreTargetId','restoreToolId'].every(k=>SHA.test(r[k]||''))
      ||typeof r.ownerTask!=='string'||!r.ownerTask.trim()||!['packagePath','operationEvidencePath'].every(k=>typeof r[k]==='string'&&path.resolve(r[k])===r[k])
      ||r.packagePath===r.operationEvidencePath||a?.sourceReturnConfigurationSha256!==hash(canonical(r)))fail('EXACT_HUMAN_SOURCE_RETURN_APPROVAL_REQUIRED');
  const excluded=[c.targetId,...(a.writers||[]).map(w=>w.id),c.inventoryJdbc?.dockerJdbc?.containerId,c.native?.fence?.dockerFence?.containerId].filter(Boolean);
  if(r.restoreTargetId===r.restoreToolId||[r.restoreTargetId,r.restoreToolId].some(id=>excluded.includes(id)))fail('INDEPENDENT_STOPPED_RETURN_TARGETS_REQUIRED');
  if(!c.humanWaiting||a.humanWaitingConfigurationSha256!==hash(canonical(c.humanWaiting))||a.configurationSha256!==hash(canonical(c.deployment))
      ||a.exportMethod!=='DM8_NATIVE_EXPORT'||!Array.isArray(c.files)||!Array.isArray(a.files)||c.files.length!==a.files.length
      ||new Set(c.files.map(f=>f.path)).size!==c.files.length||new Set(a.files.map(f=>f.path)).size!==a.files.length)fail('EXACT_RETURN_SOURCE_CONFIGURATION_REQUIRED');
  return r;
}
export function returnCurrent(c,metadata,binding) {
  returnConfiguration(c);admission(c.approval,c.targetId,'snapshot');
  const a=c.approval,rights=a.databaseRights,at=Date.now();
  if(!rights?.owner||!rights.licenseReference||!rights.purpose||!Array.isArray(rights.schemas)||canonical([...rights.schemas].sort())!==canonical([...c.schemas].sort())
      ||!(Date.parse(rights.useUntil)>at)||a.files.some(f=>!f.licenseReference||!(Date.parse(f.useUntil)>at))||!(Date.parse(c.humanWaiting.backupUntil)>at))fail('CURRENT_RETURN_SOURCE_RIGHTS_REQUIRED');
  if(metadata&&(!(Date.parse(metadata.expiresAt)>at)||!(Date.parse(metadata.databaseRights?.useUntil)>at)))fail('RETURN_PACKAGE_EXPIRED');
  if(binding&&(!(Date.parse(binding.backupUntil)>at)||!(Date.parse(binding.executionUntil)>at)))fail('RETURN_EXECUTION_EXPIRED');
}
export async function exactReturnPath(value,current=()=>{},directory=false) {
  if(typeof value!=='string'||path.resolve(value)!==value)fail('RETURN_ABSOLUTE_PATH_REQUIRED');
  let cursor=path.parse(value).root;
  for(const part of value.slice(cursor.length).split(path.sep).filter(Boolean)){
    cursor=path.join(cursor,part);current();const info=await lstat(cursor);current();
    if(info.isSymbolicLink())fail('RETURN_PATH_REDIRECT');
    const actual=await realpath(cursor);current();if(actual!==cursor)fail('RETURN_PATH_REDIRECT');
  }
  const info=await lstat(value);current();if(directory?!info.isDirectory():!info.isFile())fail('RETURN_PATH_TYPE');return info;
}
export async function returnFile(file,sha,limit,current) {
  const info=await exactReturnPath(file,current);
  if(!SHA.test(sha||'')||!Number.isSafeInteger(info.size)||info.size<0||info.size>limit)fail('RETURN_FILE_CAPACITY_OR_PIN');
  current();const fd=await open(file,'r');let bytes;
  try {
    current();const held=await fd.stat();current();if(held.size!==info.size||held.dev!==info.dev||held.ino!==info.ino)fail('RETURN_FILE_CHANGED');
    bytes=Buffer.alloc(info.size);let position=0;
    while(position<bytes.length){const n=await fd.read(bytes,position,bytes.length-position,position);current();if(!n.bytesRead)fail('RETURN_FILE_CHANGED');position+=n.bytesRead;}
    const after=await fd.stat();current();if(after.size!==info.size||after.mtimeMs!==info.mtimeMs||hash(bytes)!==sha)fail('RETURN_FILE_CHANGED');
    const live=await exactReturnPath(file,current);current();
    if(live.dev!==info.dev||live.ino!==info.ino||live.size!==info.size||live.mtimeMs!==info.mtimeMs)fail('RETURN_FILE_CHANGED');
    return info.size;
  }finally{bytes?.fill(0);await fd.close();current();}
}
export async function returnOriginals(c,metadata,current) {
  const expected=metadata.fileDigests;
  if(!Array.isArray(expected)||expected.length!==c.files.length||expected.length>10000)fail('RETURN_EXACT_FILE_SET_REQUIRED');
  let total=0;
  for(const f of expected){
    const configured=c.files.filter(x=>x.path===f.originalKey&&x.sha256===f.sha256),approved=c.approval.files.filter(x=>x.path===f.originalKey&&x.sha256===f.sha256&&x.licenseReference===f.licenseReference);
    if(configured.length!==1||approved.length!==1)fail('RETURN_EXACT_FILE_SET_REQUIRED');
    total+=await returnFile(f.originalKey,f.sha256,128*1024*1024-total,current);current();
  }
  return hash(canonical(expected));
}
export function returnScene(c,scene) {
  const r=c.sourceReturn,ids=[r.restoreTargetId,r.restoreToolId];
  if(scene?.sourceTargetId!==c.targetId||!Array.isArray(scene.targets)||scene.targets.length!==2||new Set(scene.targets.map(t=>t.id)).size!==2
      ||ids.some(id=>!scene.targets.some(t=>t.id===id&&t.ownerTask===r.ownerTask&&t.running===false&&t.restarting===false&&t.pid===0)))fail('RETURN_TARGETS_NOT_PROVEN_STOPPED');
}
export function returnHistoricalSemantic(b) {
  const {originalStdout,stdoutSha256,workerProof,executionUntil,summary,...rest}=b;
  const {executionUntil:historicalExecution,...stableSummary}=summary;
  return canonical({...rest,summary:stableSummary});
}
