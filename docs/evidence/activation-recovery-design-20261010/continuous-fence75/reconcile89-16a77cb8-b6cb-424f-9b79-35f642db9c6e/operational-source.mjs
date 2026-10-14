import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {CONTINUOUS75 as C,assertCurrentManifestTask75,assertOriginalResources75,assertHostResponsibility75,assertBridge78,assertWriterNetworks78} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-activation-recovery-continuous-fence.mjs';
import {readProjectFacts76} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-project-resource-isolation.mjs';
import {resourceBinding75,continuousFailureRecord75} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-activation-recovery-continuous-runtime.mjs';
import {canonical,hash,jdbcRequest,assertInventory} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/single-database-lifecycle-runtime.mjs';
import {assertCurrentScope,probeSourceReturnTls} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-activation-recovery-entry.mjs';
import {parseTaskRows} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/plan-dependencies.mjs';
import {assertReconciliationFacts81,completeSourceDigest81,assertReconciliationTls81,assertPublicCaReference81,PUBLIC_CA81,lockIdentity81,readReconciliationFile81,writeReconciliationFile81} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-failed-controller-reconciliation.mjs';
const root='D:/daima/xinji/agent_platform';process.chdir(root);
const old=C.root+'/run-5f3c0797-c89c-402e-9d07-36c6d2f4cbc8',runId=randomUUID(),dir=C.root+'/reconcile89-'+runId;
const marker='5f3c0797-c89c-402e-9d07-36c6d2f4cbc8 44768 a2d131db-6c9d-4070-a452-1fd4ccafa372';
const markerSha='b92918af818252e3996c995c91a268c6e19e521ae1fb11e4c69db0fb815bdbd6';
const identity={dev:'3838357650',ino:'1407374885353965',birthtimeNs:'1791784667464799700',ctimeNs:'1791784667465799700',size:'79'};
const reviewed85=[
 {pid:35376,parentId:33412,name:'cmd.exe',createdAt:'2026-10-12T06:02:13.9274700Z',commandSha256:'da4ffa6b86162a46a6eafc1b00be87ea4f0d72afcd8116d9cad4c3cba174f7f7'},
 {pid:41976,parentId:33412,name:'cmd.exe',createdAt:'2026-10-12T06:02:13.8694360Z',commandSha256:'a395d3c719e143921d1cba89202ff26a3b35726d041e6b3ef5d1cdde207aaf1b'},
 {pid:44608,parentId:35376,name:'node.exe',createdAt:'2026-10-12T06:02:14.0507520Z',commandSha256:'f956211ca0ab50ebdf87e37a1e8af5af74024fbb5bad7b9922a1c26561735b49'},
 {pid:33620,parentId:41976,name:'node.exe',createdAt:'2026-10-12T06:02:13.9619510Z',commandSha256:'cb1f3c5dfb07756034a291eb952a87de900771acedb0332de3635399e6dd97f1'}
];
const lock=C.root+'/continuous-source-target.lock',mutex=C.root+'/failed-controller-reconciliation.lock';
const require=(ok,code)=>{if(!ok)throw Error(code);},exec=promisify(execFile);
const read=(p,pin,max)=>readReconciliationFile81(root,p,pin,max),save=(p,v)=>writeReconciliationFile81(root,p,v);
const command=async(name,args)=> (await exec(name,args,{shell:false,windowsHide:true,timeout:30000,maxBuffer:1024*1024,env:Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('SINGLE_DB_')))})).stdout;
const absent=async p=>{try{await fs.lstat(p);throw Error('EXPECTED_ABSENT_'+p.split('/').at(-1).replaceAll('-','_'));}catch(e){if(e.code!=='ENOENT')throw e;}};
const manifestSha='990ffe8abac3c55353c8c3e72751655c798553874fbe9ed662dec5cba32d6a7b';
const manifest=JSON.parse(await read(C.root+'/manifest75-11.json',manifestSha));
const prior=JSON.parse(await read(manifest.resourceBaseline.path,manifest.resourceBaseline.sha256));
const template=JSON.parse(await read('docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/source-snapshot-config56.json','6170eab0136c1d9d3f6196e6684937a2d8ff6040a56fc2e9c5e1127fa4230c15'));
const sourceBytes=await fs.readFile(fileURLToPath(import.meta.url)),sourceSha=hash(sourceBytes);
const originalNames=(await fs.readdir(old)).sort(),eventBytes=await read(old+'/events.jsonl'),returnBytes=await read(old+'/source-service-safe-return89b.jsonl');
const events=eventBytes.toString().trim().split('\n').map(JSON.parse),returned=returnBytes.toString().trim().split('\n').map(JSON.parse).at(-1);
require(events.at(-1).kind==='RESULT'&&events.at(-1).caseOutcome==='NO_CASE_RESULT'&&!events.at(-1).caseCompleted&&!events.at(-1).sourceReturned&&!events.at(-1).controllerReleased&&events.at(-1).code==='CONTINUOUS_UNREGISTERED_HOST_41976'&&!events.some(e=>e.step==='STOP_SOURCE'||e.step==='START_IMPORTED_TARGET'||e.step==='FIXED_CASE'),'EXACT_CAPTURE_FAILURE_REQUIRED');
require(returned.kind==='SERVICE_RETURN_COMPLETED'&&canonical(returned.restored)===canonical([...C.writers])&&returned.sourceEpochUnchanged&&returned.originalFailedLockPreserved,'FIVE_ROLE_RETURN_NOT_PROVEN');
const baselineBytes=await read(old+'/source-baseline.json'),baseline=JSON.parse(baselineBytes);assertInventory(baseline,C.source,template.schemas,template.accounts,template.deployment);
for(const name of ['source-return-proof.json','source-protection.json','case-result.json','entry-policy.json'])require(!originalNames.includes(name),'UNEXPECTED_DATABASE_ACTION_OUTPUT');
require(!Object.keys(process.env).some(k=>k.startsWith('SINGLE_DB_')),'INHERITED_MANAGED_ENV_REFUSED');
await fs.mkdir(dir);
await save(dir+'/operational-source.mjs',sourceBytes);
let exclusive,exclusiveIdentity,secret,initial,host,hostRef,bridge,sequence=0,retirementAttempted=false,success=false;
const summary=f=>({hostProcesses:f.hostProcesses,resources:f.containers.map(c=>({id:c.Id,state:c.State,networks:c.NetworkSettings.Networks,bindingSha256:hash(canonical(resourceBinding75(c)))})),allRunning:f.allRunning});
const exactLock=async()=>{
 const b=await read(lock,markerSha,79);require(b.toString()===marker,'FAILED_MARKER_CHANGED');
 require(canonical(lockIdentity81(await fs.lstat(lock,{bigint:true})))===canonical(identity),'FAILED_LOCK_IDENTITY_CHANGED');
};
const guard=async phase=>{
 require(hash(await fs.readFile(fileURLToPath(import.meta.url)))===sourceSha,'OPERATIONAL_SOURCE_CHANGED');
 await read(C.root+'/manifest75-11.json',manifestSha);for(const p of [...manifest.sources,...manifest.staged])await read(p.path,p.sha256);
 const tasks=parseTaskRows((await read('docs/08-总开发实现计划.md')).toString());
 assertCurrentManifestTask75(tasks.find(t=>t.taskId==='IMP-CON-ACTR66-CONTINUOUS-FENCE-01'),manifestSha);
 require(tasks.find(t=>t.taskId==='REC-CON-ACTR89-SOURCE-SERVICE-RETURN-01')?.['状态']==='进行中','RECONCILIATION_TASK_GATE_REQUIRED');
 await exactLock();require(hash(await read(old+'/events.jsonl'))===hash(eventBytes)&&hash(await read(old+'/source-service-safe-return89b.jsonl'))===hash(returnBytes),'FAILURE_EVIDENCE_CHANGED');
 require(canonical((await fs.readdir(old)).sort())===canonical(originalNames),'FAILED_RUN_OUTPUTS_CHANGED');
 for(const p of ['docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46/single-database-maintenance-'+C.source+'.lock','docs/evidence/activation-recovery-design-20261010/entry-admission68/single-database-maintenance-'+C.target+'.lock'])await absent(p);
 const owners=JSON.parse(await command('pwsh',['-NoProfile','-NonInteractive','-Command',"ConvertTo-Json -InputObject @(Get-CimInstance Win32_Process -Filter 'ProcessId=44768 OR ProcessId=38828 OR ProcessId=7968' | Select-Object ProcessId,ParentProcessId) -Compress"]));
 const f=await readProjectFacts76(),b=JSON.parse(await command('docker',['network','inspect','bridge']))[0];
 if(!host){
  const originalHost=JSON.parse(await read(old+'/host-responsibility.json'));
  const parent=f.hostProcesses.find(p=>p.pid===process.ppid);require(parent&&parent.commandSha256&&/^(pwsh|powershell)\.exe$/i.test(parent.name),'CONTROLLER_IDENTITY_UNKNOWN');
  host={...originalHost,runId,records:[...originalHost.records.filter(p=>p.pid!==process.ppid),...reviewed85.map(p=>({...p,role:'NON_DB_PROCESS'})),{...parent,role:'CONTROLLER_LAUNCHER'}]};
  hostRef=await save(dir+'/host-responsibility.json',host);
 }
 assertOriginalResources75(f,prior);assertHostResponsibility75(f,host);assertBridge78(b);require(owners.length===0,'ORIGINAL_OWNER_ALIVE');
 const live=[C.source,C.sourceTool,...C.writers],by=new Map(f.containers.map(c=>[c.Id,c]));
 for(const id of live){const c=by.get(id);require(c.State.Running&&!c.State.Restarting&&c.State.Pid>0,'ORIGINAL_ROLE_NOT_RUNNING');}
 for(const id of [C.target,C.tool])require(!by.get(id).State.Running&&by.get(id).State.Pid===0,'TARGET_NOT_STOPPED');
 require(by.get(C.source).State.Pid===80626&&by.get(C.source).State.StartedAt==='2026-10-12T05:17:25.555794417Z','SOURCE_EPOCH_CHANGED');
 require(canonical(f.ownedRunning.sort())===canonical([...live].sort())&&canonical(Object.keys(f.network.Containers).sort())===canonical([...live].sort())&&live.reduce((n,id)=>n+by.get(id).HostConfig.Memory,0)<=7516192768&&live.reduce((n,id)=>n+by.get(id).HostConfig.NanoCpus,0)<=4000000000,'RESOURCE_OR_BUDGET_CHANGED');
 for(const id of C.writers)assertWriterNetworks78(id,by.get(id).NetworkSettings.Networks,prior.records.find(r=>r.id===id).networks,b);
 const top=(await command('docker',['top',C.sourceTool,'-eo','pid,comm'])).trim().split(/\r?\n/);require(top.length===2&&/\bsleep\s*$/.test(top[1]),'SOURCE_TOOL_NOT_IDLE');
 if(!initial){initial=f;bridge=b;}
 else{for(const c of f.containers){const old=initial.containers.find(x=>x.Id===c.Id);require(canonical(c.State)===canonical(old.State)&&canonical(resourceBinding75(c))===canonical(resourceBinding75(old)),'RESOURCE_CHANGED_DURING_RECONCILIATION');}require(canonical(b)===canonical(bridge),'BRIDGE_CHANGED_DURING_RECONCILIATION');}
 await assertCurrentScope({format:'ACTR66_TRUSTED_FIXTURE_POLICY/v1',mode:'EXISTING_IMPORTED_TARGET',runId,scopeApproval:{path:'docs/evidence/activation-recovery-design-20261010/scope-consumption70/scope-approval70.json',sha256:'f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0'},hostResponsibility:hostRef,sourceProtection:null,sourceReturnPlan:null,bundle:{path:'docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74.json',sha256:C.bundleSha256}},{workspaceRoot:root});
 if(exclusive){require((await read(mutex)).toString()===runId+' '+process.pid+'\n'&&canonical(lockIdentity81(await fs.lstat(mutex,{bigint:true})))===canonical(exclusiveIdentity),'EXCLUSIVE_OWNER_CHANGED');}
 await save(dir+'/current-'+(++sequence)+'.json',{phase,...summary(f),deadOwners:owners,sourceToolIdle:true});
};
try{
 await guard('READ_ONLY_ADMISSION');await absent(mutex);
 exclusive=await fs.open(mutex,'wx',0o600);await exclusive.writeFile(runId+' '+process.pid+'\n');await exclusive.sync();exclusiveIdentity=lockIdentity81(await exclusive.stat({bigint:true}));
 await guard('BEFORE_SECRET');
 secret=await command('pwsh',['-NoProfile','-NonInteractive','-File',root+'/scripts/lib/consultation-failed-controller-secret.ps1']);require(secret.length>0&&secret.length<4096,'MANAGED_SECRET_UNAVAILABLE');
 await guard('AFTER_SECRET_HELPER');
 const inventory=async name=>{
  await guard('BEFORE_'+name.toUpperCase());process.env.SINGLE_DB_ADMIN_PASSWORD=secret;
  try{return assertInventory(await jdbcRequest(template.inventoryJdbc,{action:'inventory',targetId:C.source,schemas:template.schemas,accounts:template.accounts,restoreCatalogProfile:template.recoveryCatalog.profileId},{captureRaw:b=>save(dir+'/'+name+'.json',b)}),C.source,template.schemas,template.accounts,template.deployment);}finally{delete process.env.SINGLE_DB_ADMIN_PASSWORD;}
 };
 const before=await inventory('source-before'),caRef=JSON.parse(await command('pwsh',['-NoProfile','-NonInteractive','-File',root+'/scripts/lib/consultation-failed-controller-public-ca.ps1']));
 assertPublicCaReference81(caRef);await save(dir+'/public-ca-binding.json',caRef);
 const st=await fs.lstat(caRef.path);require(st.isFile()&&!st.isSymbolicLink()&&st.size<=65536&&(await fs.realpath(caRef.path)).replaceAll('\\','/')===caRef.path,'PUBLIC_CA_REDIRECT');
 const ca=await fs.readFile(caRef.path);require(hash(ca)===PUBLIC_CA81.sha256,'PUBLIC_CA_PIN_CHANGED');
 await guard('BEFORE_TLS');const tls=await probeSourceReturnTls({caBytes:ca});assertReconciliationTls81(tls);await save(dir+'/tls.json',tls);
 const after=await inventory('source-after');require(completeSourceDigest81(before)===completeSourceDigest81(after)&&completeSourceDigest81(after)===completeSourceDigest81(baseline),'CURRENT_SOURCE_INVENTORY_CHANGED');await read(old+'/source-baseline.json',hash(baselineBytes));
 await guard('BEFORE_ARCHIVE');await save(dir+'/original-controller.lock',await read(lock,markerSha));
 await save(dir+'/proof.json',{failedRun:'5f3c0797-c89c-402e-9d07-36c6d2f4cbc8',sourceInventorySha256:completeSourceDigest81(after),twoCurrentInventoriesEqual:true,originalBaselineMatched:true,originalCaseSucceeded:false,originalSourceReturnedResultUnchanged:true,tls,markerSha256:markerSha,identity,operationalSourceSha256:sourceSha,sqlWrites:0,resourceMutations:0});
 await read(dir+'/original-controller.lock',markerSha);await guard('FINAL_RETIRE');await absent(old+'/retired-controller89.lock');
 retirementAttempted=true;await fs.rename(lock,old+'/retired-controller89.lock');
 const retired=lockIdentity81(await fs.lstat(old+'/retired-controller89.lock',{bigint:true}));require(['dev','ino','birthtimeNs','size'].every(k=>retired[k]===identity[k]),'RETIRED_IDENTITY_CHANGED');await read(old+'/retired-controller89.lock',markerSha);await absent(lock);
 await save(dir+'/result.json',{controllerLockRetired:true,currentSourceHealthy:true,originalCaseSucceeded:false,originalSourceReturnedResultUnchanged:true,sqlWrites:0,resourceMutations:0,automaticRetryAllowed:false});
 require((await read(mutex)).toString()===runId+' '+process.pid+'\n'&&canonical(lockIdentity81(await fs.lstat(mutex,{bigint:true})))===canonical(exclusiveIdentity),'EXCLUSIVE_OWNER_CHANGED');await exclusive.close();exclusive=null;await fs.unlink(mutex);success=true;
 console.log(JSON.stringify({directory:dir,controllerLockRetired:true,originalCaseSucceeded:false}));
}catch(e){await save(dir+'/failure.json',{...continuousFailureRecord75(e),retirementAttempted,automaticRetryAllowed:false});console.error('RECONCILIATION_REFUSED; EVIDENCE='+dir);process.exitCode=1;}
finally{secret=null;delete process.env.SINGLE_DB_ADMIN_PASSWORD;if(exclusive)await exclusive.close();if(!success)console.error('LOCK_SCENE_PRESERVED');}
