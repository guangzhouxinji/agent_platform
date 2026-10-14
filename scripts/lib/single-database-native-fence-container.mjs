import {withNativeStage} from './single-database-native-diagnostic.mjs';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {posix,win32} from 'node:path';
import {validateOwnedJdbcToolResource} from './single-database-jdbc-container.mjs';

const fail=code=>{throw new Error(code);};
const SHA=/^[a-f0-9]{64}$/;
const exec=promisify(execFile),workers=new WeakMap();
const windowsArgument=value=>!value||/[\s"]/.test(value)?'"'+value.replace(/(\\*)"/g,'$1$1\\"').replace(/(\\+)$/,'$1$1')+'"':value;
async function observeFenceWorker(pid){
  if(process.platform!=='win32'||!Number.isSafeInteger(pid)||pid<1)fail('NATIVE_FENCE_WORKER_OBSERVATION_REQUIRED');
  const script="$ErrorActionPreference='Stop';$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+pid+"';if(!$p -or !$p.CommandLine -or !$p.ExecutablePath){throw 'unreadable'};$sha=[Security.Cryptography.SHA256]::Create();@{pid=$p.ProcessId;parentId=$p.ParentProcessId;createdAt=$p.CreationDate.ToUniversalTime().ToString('o');name=$p.Name;executablePath=$p.ExecutablePath;commandSha256=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($p.CommandLine)))).Replace('-','').ToLowerInvariant()}|ConvertTo-Json -Compress";
  try{const r=await exec('powershell',['-NoProfile','-NonInteractive','-Command',script],{shell:false,windowsHide:true,timeout:10000,maxBuffer:8192});return JSON.parse(r.stdout);}catch{fail('NATIVE_FENCE_WORKER_OBSERVATION_REQUIRED');}
}
export function assertNativeFenceWorkerFacts(worker,facts,containerId){
  const check=workers.get(worker);if(!check)fail('TRUSTED_NATIVE_FENCE_WORKER_REQUIRED');
  return check(facts,containerId);
}
export const FENCE_SOURCE_SHA256='c4d2557a74f0002a2e9c4b8d10f2a126bb1f860584e1d5ce41bd5a4640d1b5fd';
export const DOCKER_FENCE_TRANSPORT='OWNED_DOCKER_JDK';

/** Approval/configuration may expire, but can never be replaced under a held connection. */
export function pinNativeFenceDecision(c) {
  const decision=()=>hash(canonical(Object.fromEntries(['native','approval','deployment','schemas','accounts','inventoryJdbc','targetId','recoveryCatalog'].map(k=>[k,c[k]??null]))));
  const original=decision();
  return ()=>{if(decision()!==original)fail('NATIVE_FENCE_ORIGINAL_DECISION_CHANGED');};
}

export function validateContainerFenceConfiguration(c) {
  const f=c?.native?.fence,p=f?.dockerFence;
  if(f?.transport!==DOCKER_FENCE_TRANSPORT||!p||Object.keys(p).some(k=>!['containerId','targetId','networkId','ownerTask','java','classpath','classFile','classSha256'].includes(k))||
    !['containerId','targetId','networkId','classSha256'].every(k=>SHA.test(p[k]||''))||!/^[A-Z][A-Z0-9-]+$/.test(p.ownerTask||'')||p.targetId!==c.targetId||p.ownerTask!==c.deployment?.database?.ownerTask)
    fail('EXACT_OWNED_CONTAINER_FENCE_REQUIRED');
  if(!Number.isSafeInteger(f.sessionTimeoutSeconds)||f.sessionTimeoutSeconds<30||f.sessionTimeoutSeconds>3600)fail('FINITE_CONTAINER_FENCE_SESSION_REQUIRED');
  if(c.approval?.javaFenceSha256!==FENCE_SOURCE_SHA256||c.approval?.javaFenceClassSha256!==p.classSha256)fail('PINNED_CONTAINER_FENCE_CODE_REQUIRED');
  if(!/^\/[A-Za-z0-9_./-]+\/java$/.test(p.java||'')||p.java.includes('..')||!/^\/[A-Za-z0-9_./-]+\/SingleDatabaseFence\.class$/.test(p.classFile||'')||p.classFile.includes('..')||
    typeof p.classpath!=='string'||p.classpath.split(':').some(v=>!/^\/[A-Za-z0-9_./-]+$/.test(v)||v.includes('..'))||p.classpath.split(':')[0]!==posix.dirname(p.classFile)||f.java!==p.java||f.classpath!==p.classpath)
    fail('PINNED_CONTAINER_FENCE_CLASSPATH_REQUIRED');
  if(!/^jdbc:dm:\/\/[A-Za-z0-9][A-Za-z0-9.-]*:5236$/.test(f.jdbcUrl||'')||f.jdbcUrl!==c.inventoryJdbc?.url||f.jdbcUrl!==c.deployment.database.jdbcUrl)fail('EXACT_CONTAINER_FENCE_JDBC_REQUIRED');
  return p;
}

/** Pure command builder: no Docker access and no password values in argv. */
export function containerFenceInvocation(c,{tool,db,classDigest}) {
  const p=validateContainerFenceConfiguration(c),f=c.native.fence;
  validateOwnedJdbcToolResource(p,tool,db);
  // Container JVM environment must not inject agents/options around the pinned main class.
  if((tool.Config.Env||[]).some(v=>/^(?:JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)=.+/.test(v)))fail('CONTAINER_FENCE_JVM_OPTIONS_FORBIDDEN');
  const alias=f.jdbcUrl.slice('jdbc:dm://'.length,-':5236'.length);
  if(!Object.values(db.NetworkSettings?.Networks||{}).some(n=>{
    // Current inspect metadata is authoritative; never mask malformed DNSNames with old Aliases.
    const names=Object.hasOwn(n,'DNSNames')?n.DNSNames:n.Aliases;
    return n.NetworkID===p.networkId&&Array.isArray(names)&&names.length>0&&
      names.every(name=>typeof name==='string'&&name.length>0)&&names.includes(alias);
  }))fail('EXACT_CONTAINER_FENCE_ALIAS_REQUIRED');
  if(classDigest!==p.classSha256)fail('CONTAINER_FENCE_CLASS_PIN_MISMATCH');
  return {command:'docker',args:['exec','-i',...['LIFECYCLE_FENCE_URL','LIFECYCLE_FENCE_USER','LIFECYCLE_FENCE_PASSWORD'].flatMap(n=>['-e',n]),p.containerId,
    '/usr/bin/timeout','--signal=TERM','--kill-after=5s',`${f.sessionTimeoutSeconds}s`,p.java,'-Xmx128m','-cp',p.classpath,'SingleDatabaseFence']};
}

/** Every metadata/class read is bounded by the supplied native command port. No retries/fallback. */
export async function prepareContainerFence(c,readCommand) {
  const p=validateContainerFenceConfiguration(c);
  const readStage=(stage,work)=>withNativeStage(stage,async()=>{
    try{return await work();}catch(e){if(/^[A-Z0-9_]+$/.test(e.message))throw e;fail('CONTAINER_FENCE_METADATA_UNAVAILABLE');}
  });
  const resources=await readStage('FENCE_METADATA_INSPECT',async()=>{
    const result=JSON.parse((await readCommand('docker',['inspect',p.containerId,p.targetId],{timeoutMs:10000,maxBytes:4*1024*1024,stage:'FENCE_METADATA_INSPECT'})).toString('utf8'));
    if(!Array.isArray(result)||result.length!==2)fail('EXACT_CONTAINER_FENCE_INSPECT_REQUIRED');
    // Ownership is checked before any exec, including the read-only class digest.
    containerFenceInvocation(c,{tool:result[0],db:result[1],classDigest:p.classSha256});
    return result;
  });
  return readStage('FENCE_CLASS_DIGEST',async()=>{
    const result=(await readCommand('docker',['exec',p.containerId,'sha256sum',p.classFile],{timeoutMs:10000,maxBytes:4096,stage:'FENCE_CLASS_DIGEST'})).toString('utf8');
    const match=/^([a-f0-9]{64})  (\/[A-Za-z0-9_./-]+)\r?\n?$/.exec(result);
    if(!match||match[2]!==p.classFile)fail('CONTAINER_FENCE_CLASS_PIN_MISMATCH');
    return containerFenceInvocation(c,{tool:resources[0],db:resources[1],classDigest:match[1]});
  });
}

/** One child / one JDBC connection. Transport does not change SHARE/CHECK/RELEASE semantics. */
export function persistentFenceProtocol(invocation,schemas,{env,spawnIO=spawn,timeoutMs=15000,observeWorker=observeFenceWorker}={}) {
  if(!Number.isSafeInteger(timeoutMs)||timeoutMs<1||timeoutMs>15000)fail('FINITE_FENCE_PROTOCOL_TIMEOUT_REQUIRED');
  const command=invocation.command,args=Object.freeze([...invocation.args]),invocationSha256=hash(canonical({command,args}));
  const expectedCommandSha256=hash([command,...args].map(windowsArgument).join(' '));
  const beforeSpawn=Date.now();let p;try{p=spawnIO(command,[...args],{shell:false,env,windowsHide:true});}catch{fail('ACTUAL_DATABASE_FENCE_LOST');}
  const afterSpawn=Date.now(),pid=p.pid,parentId=process.pid;
  let buffer='',pending=null,dead=false,sha,count,phase='NEW';
  let closeObserved=false,closeProof,exitDeadline,bound=null,bindingUsed=false;
  const closeWaiters=new Set();
  const waitClosed=()=>new Promise((ok,no)=>{
    if(closeObserved){ok(closeProof);return;}
    exitDeadline??=Date.now()+timeoutMs;
    const remaining=exitDeadline-Date.now();if(remaining<=0){no(Error('ACTUAL_DATABASE_FENCE_EXIT_UNCONFIRMED'));return;}
    const wait={ok,timer:setTimeout(()=>{closeWaiters.delete(wait);no(Error('ACTUAL_DATABASE_FENCE_EXIT_UNCONFIRMED'));},remaining)};
    closeWaiters.add(wait);
  });
  const liveWorker=()=>{
    if(dead||phase!=='HELD'||pending||closeObserved||!Number.isSafeInteger(pid)||pid<1||p.pid!==pid||p.exitCode!==null||p.signalCode!==null)fail('ACTUAL_DATABASE_FENCE_WORKER_NOT_HELD');
  };
  const worker=Object.freeze({bind:async()=>{
    liveWorker();if(bindingUsed)fail('NATIVE_FENCE_WORKER_ALREADY_BOUND');bindingUsed=true;
      const row=await observeWorker(pid);liveWorker();
      const name=win32.basename(command).replace(/\.exe$/i,'')+'.exe',created=Date.parse(row?.createdAt);
      if(row?.pid!==pid||row.parentId!==parentId||!Number.isFinite(created)||created<beforeSpawn||created>afterSpawn||row.name?.toLowerCase()!==name.toLowerCase()||win32.basename(row.executablePath??'').toLowerCase()!==name.toLowerCase()||row.commandSha256!==expectedCommandSha256)fail('NATIVE_FENCE_WORKER_BINDING_REFUSED');
      bound=Object.freeze({pid,parentId,createdAt:row.createdAt,commandSha256:row.commandSha256,invocationSha256});return bound;
  }});
  workers.set(worker,(facts,containerId)=>{
    liveWorker();
    if(!bound||facts?.selfPid!==parentId||command!=='docker'||args[0]!=='exec'||args[1]!=='-i'||args[8]!==containerId||args.at(-1)!=='SingleDatabaseFence')fail('SOURCE_NATIVE_FENCE_WORKER_SCOPE_REQUIRED');
    const rows=facts.hostProcesses?.filter(row=>row.pid===pid);
    if(rows?.length!==1||!['pid','parentId','createdAt','commandSha256'].every(k=>rows[0][k]===bound[k]))fail('NATIVE_FENCE_WORKER_IDENTITY_CHANGED');
    return bound;
  });
  const refuse=(code='ACTUAL_DATABASE_FENCE_LOST')=>{
    if(dead)return;
    dead=true;phase='LOST';if(pending){const wait=pending;pending=null;clearTimeout(wait.timer);wait.no(new Error(code));}
    try{p.kill();}catch{/* Exit still requires an observed close event. */}
  };
  p.stderr.resume();p.stderr.on('error',()=>refuse());p.stdin.on('error',()=>refuse());p.stdout.on('error',()=>refuse());p.stdout.on('end',()=>refuse());
  p.on('error',()=>refuse());p.on('close',(code,signal)=>{
    closeObserved=true;closeProof=Object.freeze({pid,code,signal:signal??null});
    for(const wait of closeWaiters){clearTimeout(wait.timer);wait.ok(closeProof);}closeWaiters.clear();refuse();
  });
  p.stdout.on('data',b=>{
    if(dead)return;
    buffer+=b.toString('utf8');if(Buffer.byteLength(buffer)>4096){refuse('ACTUAL_DATABASE_FENCE_REFUSED');return;}
    let end;
    while((end=buffer.indexOf('\n'))>=0){
      const line=buffer.slice(0,end).replace(/\r$/,'');buffer=buffer.slice(end+1);
      if(!pending){refuse('ACTUAL_DATABASE_FENCE_REFUSED');return;}
      const match=/^(HELD|RELEASED) ([a-f0-9]{64}) ([1-9][0-9]{0,3})$/.exec(line);
      if(!match||Number(match[3])>4096||match[1]!==pending.expected||(sha&&(sha!==match[2]||count!==Number(match[3])))){refuse('ACTUAL_DATABASE_FENCE_REFUSED');return;}
      // Reject trailing/duplicate unsolicited data before resolving the request.
      if(buffer.length){refuse('ACTUAL_DATABASE_FENCE_REFUSED');return;}
      const wait=pending;pending=null;clearTimeout(wait.timer);sha=match[2];count=Number(match[3]);phase=match[1];
      wait.ok({tableSetSha256:sha,tableCount:count});
    }
  });
  const request=(text,expected,required)=>new Promise((ok,no)=>{
    if(dead||pending||phase!==required){refuse();no(new Error('ACTUAL_DATABASE_FENCE_LOST'));return;}
    pending={ok,no,expected,timer:setTimeout(()=>refuse(),timeoutMs)};
    try{p.stdin.write(text+'\n',e=>{if(e)refuse();});}catch{refuse();}
  });
  return {
    acquire:()=>request(schemas.join(','),'HELD','NEW'),
    assertHeld:()=>request('CHECK','HELD','HELD'),
    release:async()=>{try{await request('RELEASE','RELEASED','HELD');}finally{try{p.stdin.end();}catch{}try{p.kill();}catch{}await waitClosed();}},
    abort:()=>refuse(),waitClosed,worker
  };
}

export async function openContainerFence(c,readCommand,{spawnIO=spawn,env=process.env,timeoutMs=15000}={}) {
  const f=c.native.fence,assertOriginal=pinNativeFenceDecision(c);
  if(!env[f.passwordEnv]||/[\r\n\0]/.test(env[f.passwordEnv]))fail('CURRENT_FENCE_SECRET_REQUIRED');
  const invocation=await prepareContainerFence(c,readCommand);assertOriginal();
  const protocol=persistentFenceProtocol(invocation,c.schemas,{spawnIO,timeoutMs,env:{...env,LIFECYCLE_FENCE_URL:f.jdbcUrl,LIFECYCLE_FENCE_USER:f.user,LIFECYCLE_FENCE_PASSWORD:env[f.passwordEnv]}});
  return {...protocol,assertHeld:async()=>{try{assertOriginal();await prepareContainerFence(c,readCommand);assertOriginal();return await protocol.assertHeld();}catch(e){protocol.abort();throw e;}}};
}
