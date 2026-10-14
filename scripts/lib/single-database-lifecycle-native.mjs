import {nativeStage,recordNativeFailure,withNativeStage} from './single-database-native-diagnostic.mjs';
import {spawn} from 'node:child_process';
import {readFile,open,realpath,unlink} from 'node:fs/promises';
import {resolve,dirname,basename} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {assertLiveOwnedTarget} from './single-database-lifecycle-target.mjs';
import {canonical,hash,admission} from './single-database-lifecycle-runtime.mjs';
import {validateDeploymentConfiguration,deploymentAccounts} from './single-database-lifecycle.mjs';
import {DOCKER_FENCE_TRANSPORT,FENCE_SOURCE_SHA256,pinNativeFenceDecision,validateContainerFenceConfiguration,openContainerFence,persistentFenceProtocol} from './single-database-native-fence-container.mjs';
const fail=code=>{throw new Error(code);};
const ID=/^[a-f0-9]{64}$/, IDENT=/^[A-Z][A-Z0-9_]{0,63}$/, ENV=/^[A-Z][A-Z0-9_]{0,100}$/;
const MAX=128*1024*1024, active=new WeakMap();
const fenceSource=resolve(dirname(fileURLToPath(import.meta.url)),'../java/SingleDatabaseFence.java');

export function validateNativeConfiguration(c,writers=c?.approval?.writers) {
  const n=c?.native;
  if(!n||c.approval?.exportMethod!=='DM8_NATIVE_EXPORT'||c.targetId!==c.deployment?.database?.containerId||!ID.test(c.targetId||''))fail('EXACT_NATIVE_TARGET_REQUIRED');
  if(!Array.isArray(c.schemas)||c.schemas.length!==6||new Set(c.schemas).size!==6||c.schemas.some(s=>!IDENT.test(s)))fail('SIX_NATIVE_SCHEMAS_REQUIRED');
  if(!IDENT.test(n.user||'')||!ENV.test(n.passwordEnv||'')||!ENV.test(n.exportKeyEnv||'')||n.passwordEnv===n.exportKeyEnv||!n.exportKeyReference||n.exportKeyReference!==c.approval.nativeExportKeyReference)fail('MANAGED_NATIVE_CREDENTIALS_REQUIRED');
  if(!Number.isSafeInteger(n.timeoutSeconds)||n.timeoutSeconds<5||n.timeoutSeconds>120)fail('FINITE_NATIVE_TIMEOUT_REQUIRED');
  const f=n.fence;
  if(!f||!f.java||!f.classpath||!f.user||!IDENT.test(f.user)||!ENV.test(f.passwordEnv||'')||f.jdbcUrl!==c.inventoryJdbc?.url||!ID.test(c.approval.javaFenceSha256||''))fail('PINNED_FENCE_JDBC_REQUIRED');
  if(f.transport!==undefined&&f.transport!==DOCKER_FENCE_TRANSPORT)fail('UNSUPPORTED_NATIVE_FENCE_TRANSPORT');
  if(f.transport===DOCKER_FENCE_TRANSPORT)validateContainerFenceConfiguration(c);
  else if(f.dockerFence)fail('EXPLICIT_CONTAINER_FENCE_TRANSPORT_REQUIRED');
  if(c.deployment?.schema==='SINGLE_DM8_DEPLOYMENT/v2'||c.accounts?.length===15){
    if(c.deployment?.schema!=='SINGLE_DM8_DEPLOYMENT/v2')fail('FIFTEEN_ACCOUNT_DEPLOYMENT_REQUIRED');
    validateDeploymentConfiguration(c.deployment);
    const schemas=c.deployment.domains.flatMap(d=>[d.business.schema,d.authority.schema]),accounts=deploymentAccounts(c.deployment);
    if(canonical([...schemas].sort())!==canonical([...c.schemas].sort())||!Array.isArray(c.accounts)||canonical([...accounts].sort())!==canonical([...c.accounts].sort())||c.inventoryJdbc.url!==c.deployment.database.jdbcUrl||c.approval.configurationSha256!==hash(canonical(c.deployment)))fail('EXACT_V2_NATIVE_DEPLOYMENT_SCOPE_REQUIRED');
    if(accounts.includes(f.user)||['SYSAUDITOR','SYSSSO'].includes(f.user))fail('SEPARATE_MAINTENANCE_FENCE_ACCOUNT_REQUIRED');
    if(f.user==='SYSDBA') {
      const admin=c.approval.administratorFence;
      if(!admin||admin.targetId!==c.targetId||admin.user!==f.user||admin.passwordEnv!==f.passwordEnv||admin.purpose!=='SINGLE_DATABASE_PROTECTION_FENCE'||!admin.owner||!admin.source)fail('SEPARATE_MAINTENANCE_FENCE_ACCOUNT_REQUIRED');
    }
  }
  if(c.approval.nativeConfigurationSha256!==hash(canonical(n)))fail('APPROVED_NATIVE_CONFIGURATION_REQUIRED');
  if(!c.approval.writerRegistryComplete||!c.approval.writerRegistryEvidence||!c.approval.maintenanceExclusivityEvidence)fail('COMPLETE_WRITER_AND_MAINTENANCE_EVIDENCE_REQUIRED');
  if(!Array.isArray(writers)||!writers.length||canonical(writers)!==canonical(c.approval.writers))fail('EXACT_APPROVED_WRITER_SET_REQUIRED');
  const expected=c.deployment.domains.flatMap(d=>d.writerContainerIds);
  if(new Set(expected).size!==expected.length||!expected.length||expected.some(id=>!ID.test(id)))fail('NONEMPTY_EXACT_STOPPED_WRITERS_REQUIRED');
  const dockerWriters=writers.filter(w=>w.kind==='CONTAINER');
  if(canonical(dockerWriters.map(w=>w.id).sort())!==canonical([...expected].sort())||writers.some(w=>!w.owner||!w.responsibilitySource||!['CONTAINER','MAINTENANCE'].includes(w.kind)))fail('COMPLETE_REGISTERED_WRITER_SET_REQUIRED');
  if(!writers.some(w=>w.kind==='MAINTENANCE'&&w.id===n.maintenanceLock&&w.responsibilitySource===c.approval.maintenanceExclusivityEvidence)||!n.maintenanceLock||resolve(n.maintenanceLock)!==n.maintenanceLock||basename(n.maintenanceLock)!=='single-database-maintenance-'+c.targetId+'.lock')fail('EXCLUSIVE_MAINTENANCE_LOCK_REQUIRED');
  return n;
}

export function validateCurrentNativeAdmission(c) {
  admission(c.approval,c.targetId,'snapshot');
  const rights=c.approval.databaseRights;
  if(!rights?.owner||!rights.licenseReference||!rights.purpose||!Array.isArray(rights.schemas)||canonical([...rights.schemas].sort())!==canonical([...c.schemas].sort())||!(Date.parse(rights.useUntil)>Date.now())||!Array.isArray(c.approval.files)||c.approval.files.some(f=>!f.licenseReference||!(Date.parse(f.useUntil)>Date.now())))fail('CURRENT_NATIVE_DATA_RIGHTS_REQUIRED');
}
/** Bounded failure settlement for only the child returned by this invocation. */
export function runNativeCommand(executable,args,{input,maxBytes=1024*1024,timeoutMs=30000,env,
  killGraceMs=5000,spawnIO=spawn,unavailableCode='NATIVE_PROCESS_UNAVAILABLE',
  failureCode='NATIVE_PROCESS_REFUSED_OR_TIMED_OUT',stage}={}) {
  try{stage=nativeStage(stage);}catch(e){return Promise.reject(e);}
  const startedAt=Date.now(),started=performance.now();
  if(!Number.isSafeInteger(timeoutMs)||timeoutMs<1||timeoutMs>135000||
     !Number.isSafeInteger(killGraceMs)||killGraceMs<1||killGraceMs>5000||
     !Number.isSafeInteger(maxBytes)||maxBytes<1||maxBytes>MAX)
    return Promise.reject(new Error('FINITE_NATIVE_COMMAND_LIMITS_REQUIRED'));
  return new Promise((ok,no)=>{
    let p;try{p=spawnIO(executable,args,{shell:false,env:env||process.env});}
    catch{return no(recordNativeFailure(new Error(unavailableCode),{stage,startedAt,started,reason:'SPAWN_ERROR',notStarted:true}));}
    let chunks=[],size=0,stderrBytes=0,failed=false,settled=false,timer,hardTimer;
    let reason='UNKNOWN_EXIT',exitCode=null,exitSignal=null,closeObserved=false;
    const wipe=()=>{for(const chunk of chunks)chunk.fill(0);chunks=[];};
    const finish=(bytes,code)=>{
      if(settled){bytes?.fill(0);return;}
      settled=true;clearTimeout(timer);clearTimeout(hardTimer);wipe();
      if(code){bytes?.fill(0);no(recordNativeFailure(new Error(code),{stage,startedAt,started,reason,stdoutBytes:size,stderrBytes,exitCode,signal:exitSignal,pid:p.pid,closeObserved}));}else ok(bytes);
    };
    const kill=signal=>{try{p.kill(signal);}catch{/* Failure stays unknown; never infer native completion. */}};
    const stop=(why='STREAM_ERROR')=>{
      if(settled||failed)return;
      failed=true;reason=why;wipe();
      hardTimer=setTimeout(()=>{kill('SIGKILL');finish(null,failureCode);},killGraceMs);
      kill('SIGTERM');
    };
    timer=setTimeout(()=>stop('TIMEOUT'),timeoutMs);
    p.stdout.on('data',b=>{
      const chunk=Buffer.isBuffer(b)?b:Buffer.from(b);
      if(settled||failed){chunk.fill(0);return;}
      size+=chunk.length;
      if(size>maxBytes){chunk.fill(0);stop('OUTPUT_LIMIT');}else chunks.push(chunk);
    });
    p.stdout.on('error',()=>stop());p.stderr.on('data',b=>{stderrBytes=Math.min(Number.MAX_SAFE_INTEGER,stderrBytes+b.length);if(Buffer.isBuffer(b))b.fill(0);});p.stderr.on('error',()=>stop());p.stdin.on('error',()=>stop());
    p.on('error',()=>{if(!settled&&!failed){reason='SPAWN_ERROR';finish(null,unavailableCode);}});
    p.on('close',(code,signal)=>{
      if(settled)return;
      closeObserved=true;exitCode=code;exitSignal=signal;
      if(!failed)reason=signal?'SIGNAL_EXIT':Number.isInteger(code)&&code!==0?'NONZERO_EXIT':'UNKNOWN_EXIT';
      if(failed||code!==0||signal)finish(null,failureCode);else finish(Buffer.concat(chunks));
    });
    try{p.stdin.end(input);}catch{stop();}
  });
}
const command=runNativeCommand;
async function stoppedWriters(c) {
  const ids=c.deployment.domains.flatMap(d=>d.writerContainerIds);
  const result=JSON.parse((await command('docker',['inspect',...ids],{stage:'WRITER_INSPECT'})).toString('utf8'));
  if(result.length!==ids.length)fail('WRITER_INSPECT_SET_MISMATCH');
  for(const d of c.deployment.domains)for(const id of d.writerContainerIds){
    const w=result.find(w=>w.Id===id);
    if(!w||w.State?.Running!==false||w.State?.Restarting||w.Config?.Labels?.['agent-platform.domain']!==d.domain)fail('WRITER_NOT_ACTUALLY_STOPPED');
  }
}
async function databaseFence(c) {
  const n=c.native,f=n.fence;
  if(f.transport===DOCKER_FENCE_TRANSPORT)return openContainerFence(c,command);
  if(!process.env[f.passwordEnv])fail('CURRENT_FENCE_SECRET_REQUIRED');
  const env={...process.env,LIFECYCLE_FENCE_URL:f.jdbcUrl,LIFECYCLE_FENCE_USER:f.user,LIFECYCLE_FENCE_PASSWORD:process.env[f.passwordEnv]};
  return persistentFenceProtocol({command:f.java,args:['-Xmx128m','--class-path',f.classpath,fenceSource]},c.schemas,{env});
}

export function validateNativeExportKey(key) {
  // A stronger managed format than the tool's minimum, not a claim about arbitrary key entropy.
  if(typeof key!=='string'||!/^[A-Za-z0-9]{44,48}$/.test(key)||!/[A-Z]/.test(key)||!/[a-z]/.test(key)||!/[0-9]/.test(key))fail('MANAGED_NATIVE_EXPORT_KEY_FORMAT_REQUIRED');
  return true;
}
export function selectNativeExportFile(base,listing) {
  if(!/^single-native-[a-f0-9-]{36}\.dmp$/.test(base))fail('NEW_NATIVE_NAME_REQUIRED');
  if(!Buffer.isBuffer(listing)||listing.length>4096)fail('NATIVE_EXPORT_FILE_REQUIRED');
  const lines=listing.toString('utf8').trim().split(/\r?\n/);
  const match=lines.length===1&&new RegExp('^('+base.slice(0,-4)+'-[0-9]{1,10}\\.dmp) f ([1-9][0-9]*)$').exec(lines[0]);
  if(!match)fail('NATIVE_EXPORT_FILE_REQUIRED');
  const bytes=Number(match[2]);if(!Number.isSafeInteger(bytes)||bytes<1||bytes>MAX)fail('NATIVE_EXPORT_FILE_CAPACITY');
  return {name:match[1],bytes};
}

export async function settleNativeFenceExit(db,{unlock,closeLock}) {
  try{if(db)await db.waitClosed();}catch(e){await closeLock();throw e;}
  await unlock();
}

export async function acquireFence(c,writers) {
  const n=validateNativeConfiguration(c,writers);validateCurrentNativeAdmission(c);
  validateNativeExportKey(process.env[n.exportKeyEnv]);
  const assertOriginal=pinNativeFenceDecision(c),maintenanceLock=n.maintenanceLock;
  await withNativeStage('TARGET_INSPECT',()=>assertLiveOwnedTarget(c));await stoppedWriters(c);
  const sourceSha=hash(await readFile(fenceSource));
  if(sourceSha!==FENCE_SOURCE_SHA256||sourceSha!==c.approval.javaFenceSha256)fail('FENCE_SOURCE_SHA_MISMATCH');
  assertOriginal();
  if(await realpath(dirname(maintenanceLock))!==dirname(maintenanceLock))fail('MAINTENANCE_LOCK_PARENT_REDIRECT');
  let lock;try{lock=await open(maintenanceLock,'wx',0o600);}catch{fail('MAINTENANCE_ALREADY_HELD_OR_UNAVAILABLE');}
  const marker=randomUUID();let db,held=false;
  const unlock=async()=>{await lock.close();if(await readFile(maintenanceLock,'utf8')===marker)await unlink(maintenanceLock);else fail('MAINTENANCE_FENCE_LOST');};
  const settle=()=>settleNativeFenceExit(db,{unlock,closeLock:()=>lock.close()});
  // Only the lock created by this process is released; an existing/stale lock is never stolen.
  try {
    await lock.writeFile(marker);
    assertOriginal();db=await withNativeStage('FENCE_OPEN',()=>databaseFence(c));assertOriginal();const proof=await withNativeStage('FENCE_ACQUIRE',()=>db.acquire());held=true;assertOriginal();validateCurrentNativeAdmission(c);
    const lease={proof,worker:db.worker,bindWorker:async()=>{await lease.assertHeld();return db.worker.bind();},waitClosed:()=>db.waitClosed(),nativeExportStarted:false,assertHeld:async()=>{
      try {
        if(!held)fail('FENCE_NOT_HELD');
        assertOriginal();
        validateNativeConfiguration(c,writers);validateCurrentNativeAdmission(c);
        if(await readFile(maintenanceLock,'utf8')!==marker)fail('MAINTENANCE_FENCE_LOST');
        await withNativeStage('TARGET_INSPECT',()=>assertLiveOwnedTarget(c));await stoppedWriters(c);await withNativeStage('FENCE_ASSERT',()=>db.assertHeld());validateCurrentNativeAdmission(c);
        assertOriginal();
      }catch(e){held=false;active.delete(c);db.abort();await db.waitClosed();throw e;}
    },release:async()=>{held=false;active.delete(c);try{await withNativeStage('FENCE_RELEASE',()=>db.release());}finally{await settle();}}};
    active.set(c,lease);await lease.assertHeld();return lease;
  }catch(e){held=false;db?.abort();active.delete(c);await settle();throw e;}
}

// No caller-controlled shell text, native flags, filename, target port or full-database scope.
export function nativeExportInvocation(c,fileName) {
  const n=validateNativeConfiguration(c);
  if(!/^single-native-[a-f0-9-]{36}\.dmp$/.test(fileName))fail('NEW_NATIVE_NAME_REQUIRED');
  const script='set -eu; IFS= read -r p; IFS= read -r k; test ! -e "/tmp/$1"; for f in "/tmp/${1%.dmp}-"*.dmp; do test ! -e "$f"; test ! -L "$f"; done; timeout -k 5 "$2" /opt/dmdbms/bin/dexp "$3/$p@127.0.0.1:5236" "SCHEMAS=($4)" "FILE=${1%.dmp}-%U.dmp" DIRECTORY=/tmp ENCRYPT=Y "ENCRYPT_PASSWORD=$k" GRANTS=Y DROP=N WITH_UR=N PARALLEL=1 TABLE_PARALLEL=1 NOLOGFILE=Y FILESIZE=128M FILENUM=1';
  return ['exec','-i',c.targetId,'sh','-c',script,'native-export',fileName,String(n.timeoutSeconds),n.user,c.schemas.join(',')];
}
export function verifyNativeExportLog(bytes) {
  const startedAt=Date.now(),started=performance.now();
  try {
    const text=bytes.toString('utf8');
    if(!/(?:成功终止导出,\s*没有出现警告|successfully.*(?:without warnings|no warnings))/i.test(text))fail('NATIVE_WARNING_FREE_COMPLETION_REQUIRED');
    if(/(?:警告|错误|errors?|warnings?)\s*[:：]\s*[1-9]/i.test(text)||/(?:failed|失败)\s*[:：]?\s*[1-9]/i.test(text))fail('NATIVE_EXPORT_WARNING_OR_ERROR');
  }catch(e){throw recordNativeFailure(e,{stage:'NATIVE_EXPORT',startedAt,started});}
}
export function verifyNativeExportBytes(bytes) {
  if(!Buffer.isBuffer(bytes)||!bytes.length)throw recordNativeFailure(new Error('EMPTY_NATIVE_EXPORT'),{stage:'NATIVE_READ'});
  return bytes;
}
export async function exportDatabase(c) {
  const n=validateNativeConfiguration(c),lease=active.get(c);
  if(!lease)fail('ACTUAL_HELD_FENCE_REQUIRED');if(lease.nativeExportStarted)fail('NATIVE_EXPORT_ATTEMPT_ALREADY_STARTED');await lease.assertHeld();
  const password=process.env[n.passwordEnv],key=process.env[n.exportKeyEnv];
  validateNativeExportKey(key);
  if(!password||!key||/[\r\n\0]/.test(password+key)||Buffer.byteLength(key)<32)fail('MANAGED_NATIVE_EXPORT_SECRETS_REQUIRED');
  lease.nativeExportStarted=true;
  const name='single-native-'+randomUUID()+'.dmp',input=Buffer.from(password+'\n'+key+'\n');let log,bytes;
  try {
    log=await command('docker',nativeExportInvocation(c,name),{input,timeoutMs:(n.timeoutSeconds+10)*1000,stage:'NATIVE_EXPORT'});
    verifyNativeExportLog(log);await lease.assertHeld();
    await withNativeStage('NATIVE_READ',async()=>{
      let listing;
      try {
        listing=await command('docker',['exec',c.targetId,'find','/tmp','-maxdepth','1','-name',name.slice(0,-4)+'-*.dmp','-printf','%f %y %s\n'],{maxBytes:4096,timeoutMs:10000,stage:'NATIVE_READ'});
        const file=selectNativeExportFile(name,listing);
        bytes=await command('docker',['exec',c.targetId,'cat','/tmp/'+file.name],{maxBytes:MAX,timeoutMs:30000,stage:'NATIVE_READ'});
        verifyNativeExportBytes(bytes);if(bytes.length!==file.bytes)fail('NATIVE_EXPORT_FILE_CHANGED');
      }finally{listing?.fill(0);}
    });
    await lease.assertHeld();return bytes;
  }catch(e){bytes?.fill(0);throw e;}finally{input.fill(0);log?.fill(0);}
}
