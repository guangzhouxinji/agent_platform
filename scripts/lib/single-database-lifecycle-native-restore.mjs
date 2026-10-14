import {runNativeCommand} from './single-database-lifecycle-native.mjs';
import {readFile,open,realpath,unlink,lstat} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {assertLiveOwnedTarget} from './single-database-lifecycle-target.mjs';
import {validateRestoreConfiguration,assertRestoreCurrent} from './single-database-lifecycle-restore.mjs';
import {hash,canonical} from './single-database-lifecycle-runtime.mjs';
const fail=code=>{throw new Error(code);},active=new WeakMap(),MAX=128*1024*1024;
// Docker stdout/stderr is never included in public failures: tool diagnostics may contain secrets.
export function runNativeRestoreCommand(args,options={}) {
 return runNativeCommand('docker',args,{...options,
  unavailableCode:'NATIVE_RESTORE_PROCESS_UNAVAILABLE',
  failureCode:'NATIVE_RESTORE_NONZERO_OR_TIMEOUT_PARTIAL_PRESERVED'});
}
const command=runNativeRestoreCommand;
export function nativeImportInvocation(c,name) {
 const n=validateRestoreConfiguration(c);
 if(!/^single-restore-[a-f0-9-]{36}\.dmp$/.test(name))fail('NEW_NATIVE_RESTORE_NAME_REQUIRED');
 const script='set -eu; IFS= read -r p; IFS= read -r k; test -f "/tmp/$1"; timeout -k 5 "$2" /opt/dmdbms/bin/dimp "$3/$p@127.0.0.1:5236" "SCHEMAS=($4)" "FILE=$1" DIRECTORY=/tmp "ENCRYPT_PASSWORD=$k" IGNORE=N GRANTS=Y PARALLEL=1 TABLE_PARALLEL=1 NOLOGFILE=Y DUMMY=N';
 return ['exec','-i',c.targetId,'sh','-c',script,'native-restore',name,String(n.timeoutSeconds),n.user,c.schemas.join(',')];
}
export function verifyNativeImportLog(bytes) {
 const text=bytes.toString('utf8');
 if(!/(?:成功终止导入,\s*没有出现警告|successfully.*(?:without warnings|no warnings))/i.test(text)||/(?:警告|错误|errors?|warnings?)\s*[:：]\s*[1-9]/i.test(text))fail('NATIVE_IMPORT_WARNING_FREE_COMPLETION_REQUIRED');
}
export async function requireCurrentRestoreDecision(c) {
 assertRestoreCurrent(c);
 if(!c.currentDecisionPath||resolve(c.currentDecisionPath)!==c.currentDecisionPath||!c.approval.currentDecisionEvidenceSha256)fail('ACTUAL_CURRENT_RESTORE_DECISION_REFERENCE_REQUIRED');
 if(await realpath(c.currentDecisionPath)!==c.currentDecisionPath)fail('CURRENT_RESTORE_DECISION_REDIRECT');
 const bytes=await readFile(c.currentDecisionPath);let decision;
 try{if(hash(bytes)!==c.approval.currentDecisionEvidenceSha256)fail('CURRENT_RESTORE_DECISION_CHANGED_OR_REVOKED');decision=JSON.parse(bytes);}finally{bytes.fill(0);}
 const {currentDecisionEvidenceSha256,...expected}=c.approval;
 if(canonical(decision)!==canonical(expected))fail('CURRENT_RESTORE_DECISION_BINDING_MISMATCH');
 assertRestoreCurrent(c);
}
async function stoppedWriters(c) {
 const ids=c.deployment.domains.flatMap(d=>d.writerContainerIds),bytes=await command(['inspect',...ids]);
 let result;try{result=JSON.parse(bytes);}finally{bytes.fill(0);}
 if(!Array.isArray(result)||result.length!==ids.length)fail('RESTORE_WRITER_INSPECT_SET_MISMATCH');
 for(const d of c.deployment.domains)for(const id of d.writerContainerIds){const w=result.find(v=>v.Id===id);if(!w||w.State?.Running!==false||w.State?.Restarting||w.Config?.Labels?.['agent-platform.domain']!==d.domain)fail('RESTORE_WRITER_NOT_ACTUALLY_STOPPED');}
}
export async function acquireFence(c) {
 const n=validateRestoreConfiguration(c),a=c.approval;
 // A container with a different ID but the source volume is not an independent restore target.
 if(c.deployment?.domains?.length!==3||c.deployment.domains.some(d=>!Array.isArray(d.writerContainerIds)||!d.writerContainerIds.length))fail('THREE_REGISTERED_NONEMPTY_RESTORE_WRITER_SETS_REQUIRED');
 if(!a.sourceVolume||a.sourceVolume===c.deployment.database.volume||a.nativeImportAllowed!==true||!a.nativeToolEvidence||!/^[a-f0-9]{64}$/.test(a.nativeToolHelpSha256||'')||!a.fileRoot||a.fileRoot!==c.fileRoot)fail('EXACT_NATIVE_IMPORT_DATA_DDL_AND_FILE_TARGET_APPROVAL_REQUIRED');
 await requireCurrentRestoreDecision(c);await assertLiveOwnedTarget(c);await stoppedWriters(c);
 const help=await command(['exec',c.targetId,'/opt/dmdbms/bin/dimp','HELP']);try{if(hash(help)!==a.nativeToolHelpSha256)fail('CURRENT_NATIVE_TOOL_VERSION_MISMATCH');}finally{help.fill(0);}
 await requireCurrentRestoreDecision(c);
 if(await realpath(dirname(n.maintenanceLock))!==dirname(n.maintenanceLock)||await realpath(dirname(n.attemptMarker))!==dirname(n.attemptMarker))fail('RESTORE_LOCK_PARENT_REDIRECT');
 // Files are never staged in an old directory, even when the package has no originals.
 try{await lstat(c.fileRoot);fail('NEW_RESTORE_FILE_ROOT_REQUIRED');}catch(e){if(e.code!=='ENOENT')throw e;}
 let lock;try{lock=await open(n.maintenanceLock,'wx',0o600);}catch{fail('RESTORE_MAINTENANCE_ALREADY_HELD');}
 const token=randomUUID();let held=true;
 const release=async()=>{held=false;active.delete(c);try{if(await readFile(n.maintenanceLock,'utf8')===token)await unlink(n.maintenanceLock);else fail('RESTORE_MAINTENANCE_LOST');}finally{await lock.close();}};
 try {
  await lock.writeFile(token);await lock.sync();
  // Permanent attempt marker prevents blind reuse after staging, partial DDL or unknown timeout.
  await reserveNativeRestoreAttempt(c);
  const lease={importStarted:false,assertHeld:async()=>{if(!held)fail('RESTORE_FENCE_NOT_HELD');await requireCurrentRestoreDecision(c);if(await readFile(n.maintenanceLock,'utf8')!==token)fail('RESTORE_MAINTENANCE_LOST');await assertLiveOwnedTarget(c);await stoppedWriters(c);await requireCurrentRestoreDecision(c);},release};active.set(c,lease);await lease.assertHeld();return lease;
 }catch(e){await release();if(e.code==='EEXIST')fail('RESTORE_ATTEMPT_EXISTS_RECONCILE_REQUIRED');throw e;}
}
export async function reserveNativeRestoreAttempt(c) {
 const n=validateRestoreConfiguration(c);
 if(await realpath(dirname(n.attemptMarker))!==dirname(n.attemptMarker))fail('RESTORE_ATTEMPT_PARENT_REDIRECT');
 let marker;try{marker=await open(n.attemptMarker,'wx',0o600);}catch(e){if(e.code==='EEXIST')fail('RESTORE_ATTEMPT_EXISTS_RECONCILE_REQUIRED');throw e;}
 try{await marker.writeFile(JSON.stringify({format:'SINGLE_DATABASE_NATIVE_RESTORE_ATTEMPT/v1',targetId:c.targetId,sourceTargetId:c.sourceTargetId,packageSha256:c.packageSha256,createdAt:new Date().toISOString(),automaticRetryAllowed:false})+'\n');await marker.sync();}finally{await marker.close();}
}
async function appendScene(c,event) {
 const file=c.nativeRestore.attemptMarker;if(await realpath(file)!==file)fail('RESTORE_ATTEMPT_REDIRECT');
 const first=JSON.parse((await readFile(file,'utf8')).split('\n')[0]);
 if(first.targetId!==c.targetId||first.packageSha256!==c.packageSha256)fail('RESTORE_ATTEMPT_BINDING_MISMATCH');
 const fd=await open(file,'a');try{await fd.writeFile(JSON.stringify({...event,at:new Date().toISOString()})+'\n');await fd.sync();}finally{await fd.close();}
}
export function requireNativeRestoreWindow(c,metadata) {
 assertRestoreCurrent(c,metadata);
 const deadlines=[c.approval.expiresAt,c.approval.databaseRights.useUntil,...c.approval.files.map(f=>f.useUntil),metadata?.expiresAt].filter(Boolean).map(Date.parse);
 if(Math.min(...deadlines)-Date.now()<=(c.nativeRestore.timeoutSeconds+15)*1000)fail('SUFFICIENT_NATIVE_RESTORE_WINDOW_REQUIRED');
}
export async function importDatabase(c,native,metadata) {
 const n=validateRestoreConfiguration(c),lease=active.get(c);
 if(!lease)fail('ACTUAL_RESTORE_FENCE_REQUIRED');if(lease.importStarted)fail('NATIVE_RESTORE_ATTEMPT_ALREADY_STARTED');
 await lease.assertHeld();requireNativeRestoreWindow(c,metadata);
 if(!Buffer.isBuffer(native)||!native.length||native.length>MAX)fail('BOUNDED_AUTHENTICATED_NATIVE_REQUIRED');
 if(metadata?.targetId!==c.sourceTargetId||metadata.exportMethod!=='DM8_NATIVE_EXPORT'||metadata.nativeExportKeyReference!==n.keyReference||metadata.nativeBytes!==native.length||metadata.nativeSha256!==hash(native))fail('EXACT_AUTHENTICATED_NATIVE_INPUT_REQUIRED');
 const password=process.env[n.passwordEnv],key=process.env[n.keyEnv];if(!password||!key||/[\r\n\0]/.test(password+key)||Buffer.byteLength(key)<32)fail('MANAGED_NATIVE_RESTORE_SECRETS_REQUIRED');
 lease.importStarted=true;const name='single-restore-'+randomUUID()+'.dmp';let log,stage;const secrets=Buffer.from(password+'\n'+key+'\n');
 try {
  // Native dump is already encrypted; create a fresh file without replacing a prior failed scene.
  await appendScene(c,{kind:'NATIVE_STAGE_STARTED',nativeFile:name});
  stage=await command(['exec','-i',c.targetId,'sh','-c','set -eu; umask 077; set -C; cat > "/tmp/$1"','native-stage',name],{input:native,timeoutMs:30000});await lease.assertHeld();
  const staged=await command(['exec',c.targetId,'sha256sum','/tmp/'+name]);try{if(staged.toString('utf8').trim()!==hash(native)+'  /tmp/'+name)fail('STAGED_NATIVE_SHA_MISMATCH');}finally{staged.fill(0);}
  await lease.assertHeld();
  await appendScene(c,{kind:'NATIVE_IMPORT_STARTED',nativeFile:name});await lease.assertHeld();requireNativeRestoreWindow(c,metadata);
  log=await command(nativeImportInvocation(c,name),{input:secrets,timeoutMs:(n.timeoutSeconds+15)*1000});verifyNativeImportLog(log);await lease.assertHeld();
  await appendScene(c,{kind:'NATIVE_TOOL_RETURNED',nativeFile:name,logSha256:hash(log)});await lease.assertHeld();
  return {nativeExitCode:0,warningFree:true,logSha256:hash(log),partialSceneRemoved:false};
 }catch(e){try{await appendScene(c,{kind:'NATIVE_FAILURE',nativeFile:name,partialScenePreserved:true,automaticRetryAllowed:false});}catch{e.failureSceneUnavailable=true;}throw e;}finally{secrets.fill(0);log?.fill(0);stage?.fill(0);}
}
