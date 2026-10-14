import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {assertCurrentScope,createActivationRecoveryEntry,probeSourceReturnTls} from './consultation-activation-recovery-entry.mjs';
import {sealSnapshot,openSnapshot} from './single-database-lifecycle-snapshot.mjs';
import {acquireFence,exportDatabase,runNativeCommand,validateNativeConfiguration} from './single-database-lifecycle-native.mjs';
import {jdbcRequest,assertInventory,inventoryDigest,canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {readContinuation55} from './single-database-continuous-window-admission.mjs';
import {observeOwnedDm8Startup} from './single-database-dm8-startup-observation.mjs';
import {nativeDiagnostic} from './single-database-native-diagnostic.mjs';
import {parseTaskRows} from './plan-dependencies.mjs';
import {CONTINUOUS75 as C,DEFAULT_BRIDGE78,assertBridge78,assertWriterNetworks78,assertRepairTasks78,assertCurrentManifestTask75,assertHostResponsibility75,assertReadableHostProcesses75,hostResponsibilityDiagnostic75,assertOriginalResources75,fixedCase75,continuousCase75} from './consultation-activation-recovery-continuous-fence.mjs';
import {readProjectFacts76} from './consultation-project-resource-isolation.mjs';
import {SCHEMA_RECOVERY96,assertSchemaRecovery96,assertSchemaObjects96,PARTIAL_RECOVERY97,assertPartialRecovery97} from './consultation-activation-recovery-fixture-plan.mjs';

const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const TEMPLATE='docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/source-snapshot-config56.json';
const TEMPLATE_SHA='6170eab0136c1d9d3f6196e6684937a2d8ff6040a56fc2e9c5e1127fa4230c15';
const BUNDLE='docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74-06.json';
const SCOPE='docs/evidence/activation-recovery-design-20261010/scope-consumption70/scope-approval70.json';
const SCOPE_SHA='f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0';
const NATIVE='docs/evidence/single-database-installation-20261009/approved-execution/jdbc-native05';
const SOURCE_LOCK='docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46/single-database-maintenance-'+C.source+'.lock';
const TARGET_LOCK='docs/evidence/activation-recovery-design-20261010/entry-admission68/single-database-maintenance-'+C.target+'.lock';
const CTRL_LOCK=C.root+'/continuous-source-target.lock';
const require=(yes,code)=>{if(!yes)throw Error(code);};
const BRIDGE_SOURCES91=[
  ...['JdbcStep','JdbcEntry','FixtureGuard','FixtureSeed','FixturePrefix','FixtureEntry'].map(n=>'scripts/java/ConsultationActivationRecovery'+n+'.java'),
  'scripts/lib/consultation-activation-recovery-entry.mjs','scripts/lib/consultation-activation-recovery-fixture-plan.mjs',
  'db/dm8/V032__product_identity.sql','db/dm8/V046__product_workload_subject.sql','db/dm8/V107__product_activation_recovery.sql','db/dm8/U107__product_activation_recovery.sql',
  'docs/evidence/activation-recovery-design-20261010/fixture-preparation73/fixture-plan73.json',
].sort();
export async function assertBridgeSources91(bundle,readPinned){
  require(bundle?.format==='ACTR66_FIXTURE_ENTRY_BUNDLE/v1'&&Array.isArray(bundle.sourceHashes)&&canonical(bundle.sourceHashes.map(f=>f?.path).sort())===canonical(BRIDGE_SOURCES91),'CONTINUOUS_COMPLETE_BRIDGE_SOURCE_SET_REQUIRED');
  for(const f of bundle.sourceHashes){
    require(f&&Object.keys(f).length===2&&Object.hasOwn(f,'path')&&/^[a-f0-9]{64}$/.test(f.sha256),'CONTINUOUS_BRIDGE_SOURCE_PIN_REQUIRED');
    const bytes=await readPinned(f.path);
    require(Buffer.isBuffer(bytes)&&bytes.length<=4*1024*1024&&hash(bytes)===f.sha256,'CONTINUOUS_BRIDGE_SOURCE_CHANGED');
  }
}
export const safeCode75=e=>/^[A-Z][A-Z0-9_]{0,160}$/.test(e?.message||'')?e.message:'CONTINUOUS_PORT_REFUSED_OR_UNKNOWN';
export const continuousFailureRecord75=e=>{
  const native=nativeDiagnostic(e),host=hostResponsibilityDiagnostic75(e);
  return {code:safeCode75(e),...(native?{diagnostic:native}:{}),...(host?{hostDiagnostic:host}:{}),automaticRetryAllowed:false};
};
export async function deliverImportedStart86(command,request){
  require(request?.mode==='START_EXISTING_IMPORTED_ACTR86'&&request.targetNeverImported===false&&request.sourceId===C.source&&request.sourcePidZero===true,'CONTINUOUS_IMPORTED_START_REQUEST_REQUIRED');
  const input=Buffer.from(JSON.stringify(request)+'\n');require(input.length<4096,'CONTINUOUS_IMPORTED_START_INPUT_BOUND');
  try{
    await command(['attach','--sig-proxy=false',C.target],{input,timeoutMs:15000,killGraceMs:5000,env:Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('SINGLE_DB_')))});
    return {carrierClosed:true,termination:'EOF'};
  }catch(e){
    const d=nativeDiagnostic(e);
    require(d?.reason==='TIMEOUT'&&d.closeObserved===true&&d.pid>0,'CONTINUOUS_IMPORTED_START_CARRIER_UNCONFIRMED');
    return {carrierClosed:true,termination:'BOUNDED_INPUT_CARRIER',diagnostic:d};
  }finally{input.fill(0);}
}
async function checked(relative,{newFile=false}={}){
  require(typeof relative==='string'&&!path.isAbsolute(relative)&&!relative.includes('\\')&&relative.split('/').every(p=>p&&p!=='.'&&p!=='..'),'CONTINUOUS_PATH_REFUSED');
  let p=ROOT;require(await fs.realpath(p)===p,'CONTINUOUS_ROOT_REDIRECT');
  const parts=relative.split('/');for(let i=0;i<parts.length;i++){p=path.join(p,parts[i]);if(newFile&&i===parts.length-1)break;const s=await fs.lstat(p);require(!s.isSymbolicLink()&&await fs.realpath(p)===p,'CONTINUOUS_PATH_REDIRECT');}return p;
}
async function read(relative,pin){const p=await checked(relative),s=await fs.stat(p);require(s.isFile()&&s.size<=32*1024*1024,'CONTINUOUS_BOUNDED_FILE_REQUIRED');const b=await fs.readFile(p);if(pin)require(hash(b)===pin,'CONTINUOUS_INPUT_PIN_CHANGED');return b;}
async function absent(relative){try{await fs.lstat(await checked(relative,{newFile:true}));throw Error('CONTINUOUS_EXISTING_LOCK_OR_OUTPUT');}catch(e){if(e.code!=='ENOENT')throw e;}}
async function save(relative,value){const b=Buffer.isBuffer(value)?value:Buffer.from(JSON.stringify(value,null,2)+'\n'),h=await fs.open(await checked(relative,{newFile:true}),'wx',0o600);try{await h.writeFile(b);await h.sync();}finally{await h.close();}return {path:relative,sha256:hash(b)};}
const sortedMounts=mounts=>[...mounts].sort((a,b)=>canonical(a).localeCompare(canonical(b)));
export const resourceBinding75=c=>({id:c.Id,name:c.Name,image:c.Image,configSha256:hash(canonical(c.Config)),host:{...c.HostConfig,...(c.HostConfig.Mounts?{Mounts:sortedMounts(c.HostConfig.Mounts)}:{})},mounts:sortedMounts(c.Mounts),networkIds:Object.values(c.NetworkSettings.Networks||{}).map(n=>n.NetworkID).sort()});
const fullDigest=i=>hash(canonical({schemas:i.schemas,accounts:i.accounts,restoreCatalog:i.restoreCatalog,nativePrivileges:i.nativePrivileges}));

export async function observeContinuous75(){
  const facts=await readProjectFacts76({projectImpactScope:true});
  return {format:'ACTR75_READ_ONLY_OBSERVATION/v1',capturedAt:new Date().toISOString(),observerPid:process.pid,resources:facts.containers.map(c=>({id:c.Id,name:c.Name,image:c.Image,state:c.State,owner:c.Config.Labels.task,memory:c.HostConfig.Memory,nanoCpus:c.HostConfig.NanoCpus})),runningIds:facts.ownedRunning,allRunning:facts.allRunning,externalResources:facts.externalResources,hostProcesses:facts.hostProcesses,hostScopeObservation:facts.hostScopeObservation,unreadableHostPids:facts.hostProcesses.filter(p=>!p.commandSha256).map(p=>p.pid),physicalPermission:false,secretRead:false,leaseCreated:false};
}
export async function prepareContinuous75(operation='CASE_EMPTY'){
  const request=fixedCase75(operation),runId=randomUUID(),relative=C.root+'/run-'+runId;
  await absent(relative);await fs.mkdir(await checked(relative,{newFile:true}));
  const observation=await observeContinuous75();await save(relative+'/observation.json',observation);
  await save(relative+'/request.json',{format:'ACTR75_SINGLE_CASE_REQUEST/v1',runId,request,instructionId:'ARC-CON-U107-DM8-VALIDATION-D-01/continuous-fence-and-return-admission/01'});
  return {relative,runId,unreadableHostPids:observation.unreadableHostPids,requiresEvidenceBackedHostResponsibility:true,physicalPermission:false};
}

export async function productionContinuous75(relative){
  require(new RegExp('^'+C.root+'/run-[a-f0-9-]{36}$').test(relative),'CONTINUOUS_OWNED_RUN_REQUIRED');
  const input=JSON.parse(await read(relative+'/request.json'));fixedCase75(input.request?.operation);
  require(input.format==='ACTR75_SINGLE_CASE_REQUEST/v1'&&relative.endsWith(input.runId)&&canonical(input.request)===canonical(fixedCase75(input.request.operation)),'CONTINUOUS_REQUEST_BINDING');
  const template=JSON.parse(await read(TEMPLATE,TEMPLATE_SHA));
  const manifestBytes=await read(C.root+'/'+C.manifest),manifestDigest=hash(manifestBytes),manifest=JSON.parse(manifestBytes);
  require(manifest.format==='ACTR75_CONTINUOUS_SOURCE_BUNDLE/v1'&&manifest.bridge.sha256===C.bundleSha256,'CONTINUOUS_MANIFEST_REQUIRED');
  let originalFacts,originalBridge,sourceLease,host,hostRef,controllerMarker,closed=false,count=0,hostAuditCount=0,caseEntry,entryEnv,baseline,protection,keyHex;
  const sourceConfig=structuredClone(template),startedAt=Date.now();
  require(manifest.resourceBaseline?.path===C.root+'/current-resource-baseline75.json'&&/^[a-f0-9]{64}$/.test(manifest.resourceBaseline.sha256),'CONTINUOUS_RESOURCE_BASELINE_PIN_REQUIRED');
  const prior=JSON.parse(await read(manifest.resourceBaseline.path,manifest.resourceBaseline.sha256));
  const expiry=Math.min(startedAt+40*60*1000,Date.parse(C.deadline)-20*60*1000);
  const pins=async()=>{
    if(input.request.previousOutcome===SCHEMA_RECOVERY96.outcome)await assertSchemaRecovery96(ROOT);
    if(input.request.previousOutcome===PARTIAL_RECOVERY97.outcome)await assertPartialRecovery97(ROOT);
    await read(C.root+'/'+C.manifest,manifestDigest);
    await read(manifest.resourceBaseline.path,manifest.resourceBaseline.sha256);
    await read(TEMPLATE,TEMPLATE_SHA);const bundle=JSON.parse(await read(BUNDLE,C.bundleSha256));
    await assertBridgeSources91(bundle,read);await read(SCOPE,SCOPE_SHA);
    for(const f of manifest.sources)await read(f.path,f.sha256);
    for(const f of manifest.staged)await read(f.path,f.sha256);
  };
  const scope=()=>({format:'ACTR66_TRUSTED_FIXTURE_POLICY/v1',mode:'EXISTING_IMPORTED_TARGET',runId:input.runId,scopeApproval:{path:SCOPE,sha256:SCOPE_SHA},hostResponsibility:hostRef,sourceProtection:null,sourceReturnPlan:null,bundle:{path:BUNDLE,sha256:C.bundleSha256}});
  const observe=async options=>{
    const f=await readProjectFacts76({...options,projectImpactScope:true}),networks=JSON.parse((await cmd(['network','inspect',DEFAULT_BRIDGE78])).toString());
    require(networks.length===1,'CONTINUOUS_DEFAULT_BRIDGE_OBSERVATION_REQUIRED');const bridge=networks[0];assertBridge78(bridge,originalBridge??bridge);
    if(originalFacts)for(const c of f.containers.filter(c=>c.State.Running&&C.writers.includes(c.Id)))assertWriterNetworks78(c.Id,c.NetworkSettings.Networks,originalFacts.containers.find(x=>x.Id===c.Id).NetworkSettings.Networks,bridge);
    return {...f,bridge};
  };
  const facts=async()=>{
    readContinuation55(ROOT,'fresh-fenced-source-protection');await pins();
    const f=await observe();require(f.containers.length===9,'CONTINUOUS_NINE_RESOURCES_REQUIRED');
    assertOriginalResources75(f,prior);
    const by=new Map(f.containers.map(c=>[c.Id,c])),running=f.containers.filter(c=>c.State.Running);
    require(!(by.get(C.source).State.Running&&by.get(C.target).State.Running),'CONTINUOUS_TWO_ONLINE_DATABASES_REFUSED');
    require(running.reduce((n,c)=>n+c.HostConfig.Memory,0)<=7516192768&&running.reduce((n,c)=>n+c.HostConfig.NanoCpus,0)<=4000000000,'CONTINUOUS_BUDGET_REFUSED');
    require(canonical(f.ownedRunning.sort())===canonical(running.map(c=>c.Id).sort()),'CONTINUOUS_UNKNOWN_RUNNING_RESOURCE');
    require(canonical(Object.keys(f.network.Containers||{}).sort())===canonical(running.map(c=>c.Id).sort()),'CONTINUOUS_UNKNOWN_NETWORK_WRITER');
    if(sourceLease)await sourceLease.assertHeld();
    assertHostResponsibility75(f,host,sourceLease?.worker);
    if(originalFacts)for(const c of f.containers){const o=originalFacts.containers.find(x=>x.Id===c.Id);require(o&&canonical(resourceBinding75(c))===canonical(resourceBinding75(o)),'CONTINUOUS_RESOURCE_BINDING_CHANGED');}
    return f;
  };
  const byId=async id=>(await facts()).containers.find(c=>c.Id===id);
  const cmd=async(args,options={})=>runNativeCommand('docker',args,{timeoutMs:30000,maxBytes:4*1024*1024,...options});
  const stopped=async id=>{const c=await byId(id);require(c&&!c.State.Running&&!c.State.Restarting&&c.State.Pid===0,'CONTINUOUS_STOP_NOT_CONFIRMED');};
  const inventory=async(target,name)=>{
    if(target){await stopped(C.source);for(const id of C.writers)await stopped(id);}
    else{await stopped(C.target);await stopped(C.tool);}
    const cfg=structuredClone(template.inventoryJdbc);if(target){cfg.url='jdbc:dm://agent-platform-single-final-restore-20261009:5236';cfg.dockerJdbc.containerId=C.tool;cfg.dockerJdbc.targetId=C.target;}
    const i=await jdbcRequest(cfg,{action:'inventory',targetId:target?C.target:C.source,schemas:template.schemas,accounts:template.accounts,restoreCatalogProfile:template.recoveryCatalog.profileId},{captureRaw:b=>save(relative+'/'+name,b)});
    assertInventory(i,target?C.target:C.source,template.schemas,template.accounts,template.deployment);return i;
  };
  const probe=async phase=>{
    const source=phase!=='TARGET_IMPORTED',container=source?C.sourceTool:C.tool,url=source?template.inventoryJdbc.url:'jdbc:dm://agent-platform-single-final-restore-20261009:5236';
    const cp='/jdbc:/jdbc/DmJdbcDriver18-8.1.3.140.jar:/jdbc/jackson-annotations-2.17.2.jar:/jdbc/jackson-core-2.17.2.jar:/jdbc/jackson-databind-2.17.2.jar';
    const expected=manifest.staged.find(f=>f.path===NATIVE+'/ConsultationActivationRecoveryAdmissionProbe75.class');require(expected,'CONTINUOUS_PINNED_PROBE_REQUIRED');
    const proof=(await cmd(['exec',container,'sha256sum','/jdbc/ConsultationActivationRecoveryAdmissionProbe75.class'],{maxBytes:4096})).toString().trim();require(proof===expected.sha256+'  /jdbc/ConsultationActivationRecoveryAdmissionProbe75.class','CONTINUOUS_INSTALLED_PROBE_CHANGED');
    const bytes=await cmd(['exec','-i',...['ACTR75_PROBE_PHASE','LIFECYCLE_JDBC_URL','LIFECYCLE_JDBC_USER','LIFECYCLE_JDBC_PASSWORD'].flatMap(n=>['-e',n]),container,'/usr/bin/timeout','--signal=TERM','--kill-after=5s','65s','/opt/java/openjdk/bin/java','-Xmx128m','-cp',cp,'ConsultationActivationRecoveryAdmissionProbe75'],{timeoutMs:75000,maxBytes:65536,env:{...process.env,ACTR75_PROBE_PHASE:phase,LIFECYCLE_JDBC_URL:url,LIFECYCLE_JDBC_USER:'SYSDBA',LIFECYCLE_JDBC_PASSWORD:process.env.SINGLE_DB_ADMIN_PASSWORD}});
    const ref=await save(relative+'/probe-'+phase+'-'+(++count)+'.json',bytes),value=JSON.parse(bytes);
    require(value.format==='ACTR75_NATIVE_ADMISSION/v1'&&value.phase===phase,'CONTINUOUS_NATIVE_PROBE_REFUSED');return ref;
  };
  const ports={safeCode:safeCode75,
    admit:async()=>{
      // Visibility is checked before a responsibility file, scope, secrets or lease can hide it.
      const observed=await readProjectFacts76({projectImpactScope:true});assertReadableHostProcesses75(observed);
      host=JSON.parse(await read(relative+'/host-responsibility.json'));require(host.runId===input.runId,'CONTINUOUS_HOST_RUN_MISMATCH');
      hostRef={path:relative+'/host-responsibility.json',sha256:hash(await read(relative+'/host-responsibility.json'))};
      assertHostResponsibility75(observed,host);
      const tasks=parseTaskRows((await read('docs/08-总开发实现计划.md')).toString('utf8'));assertRepairTasks78(tasks);
      const task=tasks.find(t=>t.taskId==='IMP-CON-ACTR66-CONTINUOUS-FENCE-01');
      assertCurrentManifestTask75(task,manifestDigest);
      await assertCurrentScope(scope(),{workspaceRoot:ROOT});await pins();
      await save(relative+'/host-impact-admission92-'+process.pid+'-'+(++hostAuditCount)+'.json',observed.hostScopeObservation);
      originalFacts=await facts();originalBridge=originalFacts.bridge;for(const id of [C.target,C.tool])await stopped(id);
      for(const id of [C.source,C.sourceTool,...C.writers])require((await byId(id)).State.Running,'CONTINUOUS_ORIGINAL_ROLES_NOT_RUNNING');
      for(const lock of [SOURCE_LOCK,TARGET_LOCK,CTRL_LOCK])await absent(lock);
      require(process.env.SINGLE_DB_ADMIN_PASSWORD&&process.env.SINGLE_DB_FINAL_NATIVE48_KEY&&process.env.SINGLE_DB_FINAL_SNAPSHOT45_KEY,'CONTINUOUS_MANAGED_WORKER_ENV_REQUIRED');
    },
    acquireController:async()=>{
      controllerMarker=input.runId+' '+process.pid+' '+randomUUID();const lock=await fs.open(await checked(CTRL_LOCK,{newFile:true}),'wx',0o600);
      await lock.writeFile(controllerMarker);await lock.sync();
      const journal=await fs.open(await checked(relative+'/events.jsonl',{newFile:true}),'wx',0o600);
      return {journal:{appendAndSync:async e=>{await journal.writeFile(JSON.stringify(e)+'\n');await journal.sync();},close:()=>journal.close()},controller:{releaseAfterSourceReturn:async()=>{await facts();require((await read(CTRL_LOCK)).toString()===controllerMarker,'CONTINUOUS_CONTROLLER_OWNER_LOST');await lock.close();closed=true;await fs.unlink(await checked(CTRL_LOCK));},close:async()=>{if(!closed){await lock.close();closed=true;}}}};
    },
    assertCurrent:async step=>{
      // Return remains allowed after work expiry; it does not admit any new capture or case.
      if(!step.startsWith('RETURN_')&&!step.startsWith('STOP_TARGET')&&!step.startsWith('COMPLETE_SOURCE')&&step!=='RELEASE_CONTROLLER'&&step!=='TARGET_OLD_SIX_AFTER')require(Date.now()<expiry,'CONTINUOUS_WORK_WINDOW_EXPIRED');
      await facts();require((await read(CTRL_LOCK)).toString()===controllerMarker,'CONTINUOUS_CONTROLLER_OWNER_LOST');
    },
    stopExact:async id=>{let e;try{await cmd(['stop','--time','30',id],{timeoutMs:40000});}catch(x){e=x;}await stopped(id);return {commandError:e?safeCode75(e):null};},
    isRunning:async id=>(await byId(id)).State.Running,assertStopped:stopped,
    startExact:async id=>{
      if(id===C.target)await stopped(C.source);if(id===C.source){await stopped(C.target);await stopped(C.tool);}
      const before=await byId(id);require(!before.State.Running&&before.State.Pid===0,'CONTINUOUS_START_EXISTING_ONLY');
      let commandError;try{await cmd(['start',id]);}catch(e){commandError=e;}
      const deadline=new Date(Math.min(Date.now()+90000,Date.parse(C.deadline)-10*60*1000)).toISOString();
      if(id===C.target){
        require(protection?.runId===input.runId&&protection.sourceId===C.source&&/^[a-f0-9]{64}$/.test(protection.package?.sha256||''),'CONTINUOUS_CURRENT_IMPORTED_PROTECTION_REQUIRED');
        const request={mode:'START_EXISTING_IMPORTED_ACTR86',taskId:'IMP-SINGLE-DB-FINAL-BASELINE-01',packageSha256:protection.package.sha256,sourceId:C.source,sourcePidZero:true,restartProofSha256:'4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718',continuationApprovalSha256:'de9b2d73e1406ca59388a115c371e6813651e3866be074ddcd04e2e3375f71b6',targetNeverImported:false,runId:input.runId,importJournalSha256:'97bc275b613ce8a801d5ea64715c194a3e0244ea940f1f2a9fb65e0a9b3324e4',scopeApprovalSha256:SCOPE_SHA};
        await save(relative+'/target-start-input86.json',{request,carrier:await deliverImportedStart86(cmd,request)});
      }
      while(Date.now()<Date.parse(deadline)){
        const c=await byId(id);
        if(id===C.source||id===C.target){const obs=observeOwnedDm8Startup(c,{targetId:id,previousStartedAt:before.State.StartedAt,deadline});require(obs.phase!=='NEW_INSTANCE_EXITED','CONTINUOUS_NEW_DATABASE_EXITED');if(obs.readyMayBeChecked){const log=await cmd(['logs','--since',obs.logsSince,'--tail','100',id]);if(log.toString().includes('SYSTEM IS READY')&&(id!==C.target||log.toString().includes('SINGLE_FINAL_IMPORTED_ACTR86_START_'+input.runId)))return;}}
        else if(c.State.Running&&!c.State.Restarting&&c.State.Pid>0)return;
        await new Promise(r=>setTimeout(r,2000));
      }
      throw Error(commandError?'CONTINUOUS_START_UNKNOWN':'CONTINUOUS_START_READINESS_TIMEOUT');
    },
    sourceInventory:async()=>baseline=await inventory(false,'source-baseline.json'),
    targetInventory:async()=>inventory(true,'target-inventory-'+(++count)+'.json'),probe,
    captureSource:async({runId})=>{
      for(const id of C.writers)await stopped(id);
      await save(relative+'/source-bridge-baseline.json',originalBridge);
      const responsibility=await save(relative+'/maintenance-responsibility.json',{format:'ACTR75_CURRENT_MAINTENANCE_RESPONSIBILITY/v1',owner:'Codex',runId,sourceId:C.source,targetId:C.target,host:hostRef,writerIds:C.writers,sourcePurpose:template.approval.databaseRights,continuousWritersStopped:true,controllerLock:CTRL_LOCK});
      sourceConfig.packageOutput=await checked(relative+'/source-protection.xjsnap',{newFile:true});
      sourceConfig.approval.runId=runId;sourceConfig.approval.writerRegistryEvidence=await checked(responsibility.path);sourceConfig.approval.maintenanceExclusivityEvidence=await checked(responsibility.path);
      sourceConfig.approval.writers=sourceConfig.approval.writers.map(w=>({...w,owner:'Codex same approved ACTR75 run '+runId,responsibilitySource:sourceConfig.approval.maintenanceExclusivityEvidence}));
      validateNativeConfiguration(sourceConfig);const approval=await save(relative+'/source-approval.json',sourceConfig.approval);await save(relative+'/source-config.json',sourceConfig);
      const key=Buffer.from(process.env[sourceConfig.keyEnv]||'','base64');require(key.length===32,'CONTINUOUS_SNAPSHOT_KEY_FORMAT');
      let calls=0;
      try{
        const receipt=await sealSnapshot({approval:sourceConfig.approval,inventory:()=>inventory(false,'source-seal-'+(++calls)+'.json'),exportDatabase:()=>exportDatabase(sourceConfig),acquireFence:async writers=>{
          const lease=await acquireFence(sourceConfig,writers);sourceLease=lease;
          const release=async()=>{try{await lease.release();}finally{await lease.waitClosed();sourceLease=null;}};
          try{const identity=await lease.bindWorker();await save(relative+'/source-fence-worker.json',{runId,sourceId:C.source,toolId:C.sourceTool,...identity});await lease.assertHeld();await probe('SOURCE_HELD');await lease.assertHeld();}catch(e){await release();throw e;}return {...lease,release};
        },files:sourceConfig.files,key,output:sourceConfig.packageOutput,schemas:sourceConfig.schemas,accounts:sourceConfig.accounts,deployment:sourceConfig.deployment,recoveryCatalog:sourceConfig.recoveryCatalog});
        await save(relative+'/source-receipt.json',receipt);keyHex=key.toString('hex');
        protection={format:'ACTR66_CURRENT_SOURCE_PROTECTION/v1',runId,sourceId:C.source,capturedAt:receipt.metadata.createdAt,package:{path:relative+'/source-protection.xjsnap',sha256:receipt.sealedSha256},inventorySha256:receipt.metadata.inventorySha256,catalogSha256:receipt.metadata.recoveryCatalog.catalogSha256,keyEnv:sourceConfig.keyEnv,sourceApproval:approval};
        return protection;
      }finally{key.fill(0);}
    },
    validateProtection:async p=>{
      const key=Buffer.from(keyHex||'','hex');let opened;
      try{opened=await openSnapshot({file:await checked(p.package.path),key,expectedTargetId:C.source,expectedSha256:p.package.sha256});require(opened.metadata.approvalSha256===hash(canonical(sourceConfig.approval))&&opened.metadata.inventorySha256===inventoryDigest(baseline)&&fullDigest(opened.content.inventory)===fullDigest(baseline),'CONTINUOUS_FRESH_PROTECTION_DRIFT');}finally{key.fill(0);opened?.native?.fill(0);}
      await save(relative+'/source-protection.json',p);
    },
    assertSourceFenceReturned:async()=>{await absent(SOURCE_LOCK);const top=(await cmd(['top',C.sourceTool,'-eo','pid,comm'])).toString().trim().split(/\r?\n/);require(top.length===2&&/\bsleep\s*$/.test(top[1]),'CONTINUOUS_SOURCE_NATIVE_WORKER_NOT_RETURNED');await probe('SOURCE_IDLE');},
    createEntry:async()=>{
      if(input.request.previousOutcome===PARTIAL_RECOVERY97.outcome){
        const history=await assertPartialRecovery97(ROOT);await probe('TARGET_IMPORTED');await facts();
        const cfg=structuredClone(template.inventoryJdbc);cfg.url='jdbc:dm://agent-platform-single-final-restore-20261009:5236';cfg.dockerJdbc.containerId=C.tool;cfg.dockerJdbc.targetId=C.target;
        await jdbcRequest(cfg,{action:'bootstrap-inventory',targetId:C.target,accounts:['ACTR66_EMPTY']},{captureRaw:b=>save(relative+'/partial-native97.json',b)});
        await facts();await save(relative+'/partial-prefix97.json',{...history,original:PARTIAL_RECOVERY97.attempt,sourceReturned:PARTIAL_RECOVERY97.returned,proof:PARTIAL_RECOVERY97.proof,currentObservation:{path:relative+'/partial-native97.json',sha256:hash(await read(relative+'/partial-native97.json'))},javaPrefix8BeforeWriteRequired:true});
      }
      if(input.request.previousOutcome===SCHEMA_RECOVERY96.outcome){
        await assertSchemaRecovery96(ROOT);await probe('TARGET_IMPORTED');await facts();
        const cfg=structuredClone(template.inventoryJdbc);cfg.url='jdbc:dm://agent-platform-single-final-restore-20261009:5236';cfg.dockerJdbc.containerId=C.tool;cfg.dockerJdbc.targetId=C.target;
        const observation=await jdbcRequest(cfg,{action:'bootstrap-inventory',targetId:C.target,accounts:['ACTR66_EMPTY']},{captureRaw:b=>save(relative+'/schema-only-native96.json',b)});
        await facts();assertSchemaObjects96(observation,C.target);
        await save(relative+'/schema-only-prefix96.json',{original:SCHEMA_RECOVERY96.attempt,sourceReturned:SCHEMA_RECOVERY96.returned,currentObservation:{path:relative+'/schema-only-native96.json',sha256:hash(await read(relative+'/schema-only-native96.json'))},originalDdlStatements:1,newExpectedDdlStatements:14,automaticRetryAllowed:false});
      }
      const returnPlan=await save(relative+'/source-return-plan.json',{format:'ACTR66_SOURCE_RETURN_PLAN/v1',runId:input.runId,sourceId:C.source,targetId:C.target,writerIds:C.writers,fullInventoryAndStrictTlsRequired:true});
      const policy={...scope(),sourceProtection:{path:relative+'/source-protection.json',sha256:hash(await read(relative+'/source-protection.json'))},sourceReturnPlan:returnPlan};await save(relative+'/entry-policy.json',policy);
      entryEnv={...process.env,[sourceConfig.keyEnv]:keyHex};caseEntry=await createActivationRecoveryEntry(policy,{workspaceRoot:ROOT,env:entryEnv,observe});return caseEntry;
    },
    returnRequest:()=>save(relative+'/source-return-request.json',{format:'ACTR66_SOURCE_RETURN_REQUEST/v1',runId:input.runId,sourceId:C.source,ownerPid:process.pid}),
    assertWriterReturnSafe:async()=>{
      await stopped(C.target);await stopped(C.tool);await absent(SOURCE_LOCK);require(!sourceLease,'CONTINUOUS_SOURCE_NATIVE_WORKER_NOT_RETURNED');
      for(const id of [C.source,C.sourceTool]){const c=await byId(id);require(c.State.Running&&!c.State.Restarting&&c.State.Pid>0,'CONTINUOUS_SOURCE_NOT_READY_FOR_WRITER_RETURN');}
      const top=(await cmd(['top',C.sourceTool,'-eo','pid,comm'])).toString().trim().split(/\r?\n/);require(top.length===2&&/\bsleep\s*$/.test(top[1]),'CONTINUOUS_SOURCE_NATIVE_WORKER_NOT_RETURNED');
    },
    returnWriter:async id=>{
      const c=await byId(id);if(!c.State.Running)await ports.startExact(id);
      const expected=originalFacts.containers.find(x=>x.Id===id).NetworkSettings.Networks;
      const f=await facts(),current=f.containers.find(x=>x.Id===id).NetworkSettings.Networks;
      const changes=assertWriterNetworks78(id,current,expected,f.bridge);await save(relative+'/writer-return-'+id+'-'+(++count)+'.json',{id,changes,current,bridgeId:f.bridge.Id});
      if([C.writers[0],C.writers[2],C.writers[4]].includes(id)){
        const until=Date.now()+60000;while(Date.now()<until){const v=await byId(id),log=(await cmd(['logs','--since',v.State.StartedAt,'--tail','100',id])).toString();if(/Started Product(?:Consultation|Adjudication|Enforcement)Application in /.test(log))return;await new Promise(r=>setTimeout(r,2000));}throw Error('CONTINUOUS_WRITER_READY_UNPROVED');
      }
    },
    verifySourceReturn:async()=>{
      await stopped(C.target);await stopped(C.tool);for(const id of [C.source,C.sourceTool,...C.writers])require((await byId(id)).State.Running,'CONTINUOUS_SOURCE_ROLES_NOT_RETURNED');
      require(baseline,'CONTINUOUS_RETURN_BASELINE_UNAVAILABLE');const before=await inventory(false,'source-return-before.json');require(fullDigest(before)===fullDigest(baseline),'CONTINUOUS_SOURCE_RETURN_DRIFT');
      const caPath='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer',ca=await fs.readFile(caPath);require(hash(ca)==='ab658c0492f34775181a5a97cc46fd2ecf04bcf1ecbe08f073124fadd0eeb9f0','CONTINUOUS_PUBLIC_CA_CHANGED');
      const tls=await probeSourceReturnTls({caBytes:ca});await save(relative+'/source-return-tls.json',tls);
      const after=await inventory(false,'source-return-after.json');require(fullDigest(after)===fullDigest(baseline),'CONTINUOUS_SOURCE_RETURN_DRIFT');await facts();await save(relative+'/source-return-proof.json',{runId:input.runId,before:fullDigest(before),after:fullDigest(after),tls,sqlAcceptance:false});
    },
    recordFailure:async e=>save(relative+'/failure-'+(++count)+'.json',continuousFailureRecord75(e)),
  };
  const pipeline=continuousCase75(ports,{operation:input.request.operation,runId:input.runId});
  return {admit:()=>ports.admit(),execute:async()=>{try{return await pipeline.execute();}finally{keyHex=null;if(entryEnv)delete entryEnv[sourceConfig.keyEnv];}},runId:input.runId};
}
