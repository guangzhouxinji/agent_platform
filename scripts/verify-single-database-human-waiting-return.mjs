#!/usr/bin/env node
import {open,readFile,lstat} from 'node:fs/promises';
import path from 'node:path';
import {hash,canonical,jdbcRequest} from './lib/single-database-lifecycle-runtime.mjs';
import {acquireFence,runNativeCommand,validateNativeConfiguration,validateCurrentNativeAdmission} from './lib/single-database-lifecycle-native.mjs';
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {createHumanWaitingInspector} from './lib/single-database-human-waiting-source.mjs';
import {verifyHumanWaitingSourceReturn} from './lib/single-database-human-waiting-return.mjs';
import {exactReturnPath,returnFile,returnConfiguration,returnCurrent,fail} from './lib/single-database-human-waiting-return-helper.mjs';
const [configurationPath,receiptPath,journalPath]=process.argv.slice(2);
if(!configurationPath||!receiptPath||!journalPath||process.argv.length!==5){
  console.error('Usage: node scripts/verify-single-database-human-waiting-return.mjs CONFIG.json NEW_RECEIPT.json NEW_JOURNAL.jsonl');process.exit(2);
}
let receiptFile,journalFile,configuration,key,receiptCreated=false;
const safeCode=e=>/^[A-Z0-9_]+$/.test(e.message)?e.message:'RETURN_INPUT_OR_ADAPTER_FAILURE';
async function persist(fd,value){
  const bytes=Buffer.from(JSON.stringify(value,null,2)+'\n');
  await fd.truncate(0);let offset=0;
  while(offset<bytes.length){const n=await fd.write(bytes,offset,bytes.length-offset,offset);if(!n.bytesWritten)fail('RETURN_EVIDENCE_WRITE_FAILED');offset+=n.bytesWritten;}
  await fd.sync();
}
async function invalidate(){if(receiptFile)await persist(receiptFile,{format:'HUMAN_WAITING_SOURCE_RETURN_FAILURE/v1',verificationOnly:true,businessActivation:false,passed:false,successfulReceiptInvalidated:true});}
try {
  const input=path.resolve(configurationPath),receipt=path.resolve(receiptPath),journal=path.resolve(journalPath);
  const info=await exactReturnPath(input);if(info.size>1024*1024)fail('RETURN_CONFIGURATION_CAPACITY');
  const bytes=await readFile(input),configurationSha=hash(bytes);configuration=JSON.parse(bytes.toString('utf8'));
  const c=configuration,r=returnConfiguration(c);validateNativeConfiguration(c,c.approval.writers);validateCurrentNativeAdmission(c);
  const root=c.humanWaiting.evidenceRoot;
  if(typeof root!=='string'||path.resolve(root)!==root)fail('RETURN_PROTECTED_ROOT_REQUIRED');
  const inputs=[input,r.packagePath,r.operationEvidencePath,...c.files.map(f=>f.path),c.humanWaiting.java.path,c.humanWaiting.jar.path];
  const within=(parent,child)=>{const relative=path.relative(parent,child);return relative===''||relative!=='..'&&!relative.startsWith('..'+path.sep)&&!path.isAbsolute(relative);};
  const same=(a,b)=>process.platform==='win32'?a.toLowerCase()===b.toLowerCase():a===b;
  if(same(receipt,journal)||[receipt,journal].some(p=>within(root,p)||inputs.some(f=>same(f,p))))fail('RETURN_NEW_UNPROTECTED_OUTPUTS_REQUIRED');
  for(const output of [receipt,journal]){
    await exactReturnPath(path.dirname(output),()=>{},true);
    try{await lstat(output);fail('RETURN_NEW_OUTPUT_REQUIRED');}catch(e){if(e.code!=='ENOENT')throw e;}
  }
  journalFile=await open(journal,'wx',0o600);receiptFile=await open(receipt,'wx',0o600);receiptCreated=true;
  await persist(receiptFile,{format:'HUMAN_WAITING_SOURCE_RETURN_PENDING/v1',verificationOnly:true,businessActivation:false});
  let sequence=0;
  const journalPort=async event=>{
    const row=JSON.stringify({sequence:++sequence,occurredAt:new Date().toISOString(),...event})+'\n';
    await journalFile.writeFile(row);await journalFile.sync();
  };
  const outputUnchanged=async()=>{
    for(const [file,fd] of [[receipt,receiptFile],[journal,journalFile]]){
      await exactReturnPath(file);const live=await lstat(file),held=await fd.stat();
      if(live.ino!==held.ino||live.dev!==held.dev)fail('RETURN_OUTPUT_REDIRECT');
    }
  };
  const pinnedConfiguration=async()=>{
    returnCurrent(c);await returnFile(input,configurationSha,1024*1024,()=>returnCurrent(c));await outputUnchanged();returnCurrent(c);
  };
  const scene=async()=>{
    await pinnedConfiguration();await assertLiveOwnedTarget(c);
    const writerIds=c.approval.writers.filter(w=>w.kind==='CONTAINER').map(w=>w.id),ids=[r.restoreTargetId,r.restoreToolId,...writerIds];
    if(new Set(ids).size!==ids.length)fail('RETURN_SCENE_ID_COLLISION');
    const records=JSON.parse((await runNativeCommand('docker',['inspect',...ids],{timeoutMs:10000,maxBytes:4*1024*1024,stage:'TARGET_INSPECT'})).toString('utf8'));
    if(!Array.isArray(records)||records.length!==ids.length||new Set(records.map(v=>v.Id)).size!==ids.length)fail('RETURN_SCENE_INCOMPLETE');
    for(const id of ids){
      const row=records.find(v=>v.Id===id);
      if(!row||row.State?.Running!==false||row.State?.Restarting!==false||row.State?.Pid!==0)fail('RETURN_TARGET_OR_WRITER_RUNNING');
      if([r.restoreTargetId,r.restoreToolId].includes(id)&&row.Config?.Labels?.task!==r.ownerTask)fail('RETURN_TARGET_OWNER_CHANGED');
      if(writerIds.includes(id)){
        const domain=c.deployment.domains.find(d=>d.writerContainerIds.includes(id));
        if(!domain||row.Config?.Labels?.['agent-platform.domain']!==domain.domain)fail('RETURN_WRITER_SCOPE_CHANGED');
      }
    }
    await pinnedConfiguration();
    return {sourceTargetId:c.targetId,targets:records.filter(v=>[r.restoreTargetId,r.restoreToolId].includes(v.Id)).map(v=>({id:v.Id,ownerTask:v.Config.Labels.task,running:v.State.Running,restarting:v.State.Restarting,pid:v.State.Pid}))};
  };
  key=Buffer.from(process.env[c.keyEnv]||'','base64');
  await verifyHumanWaitingSourceReturn({configuration:c,key,inspect:createHumanWaitingInspector(c,c.approval),
    inventory:async()=>{await pinnedConfiguration();await assertLiveOwnedTarget(c);return jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,deployment:c.deployment,...(c.recoveryCatalog?{restoreCatalogProfile:c.recoveryCatalog.profileId}:{})});},
    acquireFence,assertScene:scene,journal:journalPort,
    publish:async(value,{assertCurrent})=>{
      try{
        await assertCurrent();await outputUnchanged();await persist(receiptFile,value);await assertCurrent();
        await journalPort({kind:'RETURN_RECEIPT_DURABLE',receiptSha256:hash(canonical(value)),verificationOnly:true,businessActivation:false});
        await assertCurrent();return {invalidate};
      }
      catch(e){await invalidate();throw e;}
    }});
  console.log(JSON.stringify({passed:true,receipt,journal,verificationOnly:true,businessActivation:false}));
}catch(e){
  if(receiptCreated)try{await invalidate();}catch{/* Failed evidence persistence is never reported as success. */}
  if(journalFile)try{await journalFile.writeFile(JSON.stringify({kind:'RETURN_CLI_FAILURE',code:safeCode(e),occurredAt:new Date().toISOString()})+'\n');await journalFile.sync();}catch{/* Preserve existing evidence without retrying operations. */}
  console.error(JSON.stringify({passed:false,code:safeCode(e),verificationOnly:true,businessActivation:false}));process.exitCode=1;
}finally{
  key?.fill(0);
  for(const fd of [receiptFile,journalFile])if(fd)try{await fd.close();}catch{process.exitCode=1;}
}
