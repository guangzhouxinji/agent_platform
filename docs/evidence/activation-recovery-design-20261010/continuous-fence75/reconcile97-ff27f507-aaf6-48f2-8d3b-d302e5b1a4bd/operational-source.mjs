import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {CONTINUOUS75 as C,assertCurrentManifestTask75,assertOriginalResources75,assertHostResponsibility75,assertBridge78,assertWriterNetworks78,oldSixDigest75} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-activation-recovery-continuous-fence.mjs';
import {readProjectFacts76} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-project-resource-isolation.mjs';
import {resourceBinding75,continuousFailureRecord75} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-activation-recovery-continuous-runtime.mjs';
import {canonical,hash,jdbcRequest,assertInventory} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/single-database-lifecycle-runtime.mjs';
import {assertCurrentScope,probeSourceReturnTls} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-activation-recovery-entry.mjs';
import {parseTaskRows} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/plan-dependencies.mjs';
import {completeSourceDigest81,assertReconciliationTls81,assertPublicCaReference81,PUBLIC_CA81,lockIdentity81,readReconciliationFile81,writeReconciliationFile81} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/consultation-failed-controller-reconciliation.mjs';
const root='D:/daima/xinji/agent_platform';process.chdir(root);
const failed='6f13c815-4944-4951-8016-66941ffed355',old=C.root+'/run-'+failed;
const entry='docs/evidence/activation-recovery-design-20261010/entry-admission68';
const runId=randomUUID(),dir=C.root+'/reconcile97-'+runId,mutex=C.root+'/failed-controller-reconciliation.lock';
const locks=[
 {path:entry+'/single-database-maintenance-'+C.target+'.lock',destination:entry+'/run-'+failed+'/retired-entry97.lock',marker:failed+' bb031f5e-c0e1-48b7-ad37-41cb06b5714a\n',sha256:'f88dcc0ee34685da3ad67c3db71f683942f1e8193d7dc3cb8c518d60f93b4288',identity:{dev:'3838357650',ino:'281474978568432',birthtimeNs:'1791809077975531100',ctimeNs:'1791809077975531100',size:'74'}},
 {path:C.root+'/continuous-source-target.lock',destination:old+'/retired-controller97.lock',marker:failed+' 39160 fc56cfb4-796a-43f4-b630-0691bd71a419',sha256:'60e2af61ddda4424be0767b46d36d11c86321ba78f74018ced58ae42aa2fcf3d',identity:{dev:'3838357650',ino:'3659174699096049',birthtimeNs:'1791808016215733400',ctimeNs:'1791808016216242900',size:'79'}},
];
const require=(yes,code)=>{if(!yes)throw Error(code);},exec=promisify(execFile);
const read=(p,pin,max)=>readReconciliationFile81(root,p,pin,max),save=(p,v)=>writeReconciliationFile81(root,p,v);
const command=async(name,args)=>(await exec(name,args,{shell:false,windowsHide:true,timeout:30000,maxBuffer:1024*1024,env:Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('SINGLE_DB_')))})).stdout;
const absent=async p=>{try{await fs.lstat(p);throw Error('EXPECTED_ABSENT');}catch(e){if(e.code!=='ENOENT')throw e;}};
const manifestSha='9ab4cd9b07db295eb7b9d2c460a6537d9292621323a4e2575134f31010ee0fa3';
const manifest=JSON.parse(await read(C.root+'/manifest75-17.json',manifestSha));
const prior=JSON.parse(await read(manifest.resourceBaseline.path,manifest.resourceBaseline.sha256));
const template=JSON.parse(await read('docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/source-snapshot-config56.json','6170eab0136c1d9d3f6196e6684937a2d8ff6040a56fc2e9c5e1127fa4230c15'));
const sourceBytes=await fs.readFile(fileURLToPath(import.meta.url)),sourceSha=hash(sourceBytes);
const originalNames=(await fs.readdir(old)).sort(),eventBytes=await read(old+'/events.jsonl');
const attemptPath=entry+'/run-'+failed+'/attempt.jsonl',attemptBytes=await read(attemptPath);
const events=eventBytes.toString().trim().split('\n').map(JSON.parse),result=events.at(-1);
const attempts=attemptBytes.toString().trim().split('\n').map(JSON.parse);
require(result.kind==='RESULT'&&result.code==='ENTRY_EXIT_UNKNOWN_LOCK_PRESERVED'&&result.returnCode==='BOUNDED_PUBLIC_CA_REQUIRED'&&result.sourceReturned===false&&result.controllerReleased===false,'EXACT_FAILED_RESULT_REQUIRED');
const completedSteps=['P_CREATE_SUBJECT','P_CREATE_EVENT','P_ADD_KIND','P_DROP_ACTIVATION','P_ADD_ACTIVATION','P_ADD_KIND_CHECK','P_DROP_OPERATION','P_ADD_OLD_OPERATION','V_DROP_OLD'];
require(attempts.length===18&&attempts.every((e,i)=>e.runId===failed&&e.schema==='ACTR66_EMPTY'&&e.type==='JOURNAL'&&e.step===completedSteps[Math.floor(i/2)]&&e.phase===(i%2?'COMPLETED':'PREPARED')),'EXACT_PARTIAL_CASE_REQUIRED');
for(const step of ['STOP_TARGET_TOOL','STOP_TARGET','RETURN_SOURCE','RETURN_SOURCE_TOOL',...C.writers.map(id=>'RETURN_WRITER_'+id)])require(events.filter(e=>e.step===step&&e.kind==='PREPARED').length===1&&events.filter(e=>e.step===step&&e.kind==='COMPLETED').length===1&&!events.some(e=>e.step===step&&e.kind==='UNKNOWN'),'SERVICE_RETURN_NOT_PROVEN');
const baselineBytes=await read(old+'/source-baseline.json'),baseline=JSON.parse(baselineBytes);assertInventory(baseline,C.source,template.schemas,template.accounts,template.deployment);
const targets=await Promise.all(['target-inventory-5.json','target-inventory-8.json'].map(n=>read(old+'/'+n)));
const catalogRows94=inventory=>{
 const normalized=structuredClone(inventory);
 for(const category of Object.values(normalized.restoreCatalog.categories)){
  require(Array.isArray(category.columns)&&Array.isArray(category.rows),'COMPLETE_CATALOG_ROWS_REQUIRED');
  category.rows.sort((a,b)=>canonical(a).localeCompare(canonical(b),'en'));
 }
 return normalized;
};
const targetOrderProof={beforeRawSha256:hash(targets[0]),afterRawSha256:hash(targets[1]),beforeRawDigest:oldSixDigest75(JSON.parse(targets[0])),afterRawDigest:oldSixDigest75(JSON.parse(targets[1])),canonicalRecordMultisetSha256:oldSixDigest75(catalogRows94(JSON.parse(targets[0]))),onlyCatalogRowOrderNormalized:true,duplicatesPreserved:true,columnsAndEveryOtherFieldUnchanged:true};
require(targetOrderProof.canonicalRecordMultisetSha256===oldSixDigest75(catalogRows94(JSON.parse(targets[1]))),'ORIGINAL_SIX_CHANGED');
require(!Object.keys(process.env).some(k=>k.startsWith('SINGLE_DB_')),'INHERITED_SECRET_REFUSED');
await fs.mkdir(dir);await save(dir+'/operational-source.mjs',sourceBytes);await save(dir+'/target-row-order-proof.json',targetOrderProof);
let exclusive,exclusiveIdentity,secret,host,hostRef,initial,bridge,sequence=0,success=false;
const retired=new Set();
const exactLock=async l=>{
 const p=retired.has(l.path)?l.destination:l.path;
 require((await read(p,l.sha256,128)).toString()===l.marker,'EXACT_LOCK_MARKER_CHANGED');
 const identity=lockIdentity81(await fs.lstat(p,{bigint:true}));
 require((retired.has(l.path)?['dev','ino','birthtimeNs','size']:Object.keys(l.identity)).every(k=>identity[k]===l.identity[k]),'EXACT_LOCK_IDENTITY_CHANGED');
};
const guard=async phase=>{
 require(hash(await fs.readFile(fileURLToPath(import.meta.url)))===sourceSha,'RECOVERY_SOURCE_CHANGED');
 await read(C.root+'/manifest75-17.json',manifestSha);for(const p of [...manifest.sources,...manifest.staged])await read(p.path,p.sha256);
 const tasks=parseTaskRows((await read('docs/08-总开发实现计划.md')).toString());
 assertCurrentManifestTask75(tasks.find(t=>t.taskId==='IMP-CON-ACTR66-CONTINUOUS-FENCE-01'),manifestSha);
 require(tasks.find(t=>t.taskId==='FIX-CON-ACTR97-RETURN-AND-ENTRY-01')?.['状态']==='进行中','RECOVERY_TASK_NOT_ACTIVE');
 for(const l of locks)await exactLock(l);
 require(hash(await read(old+'/events.jsonl'))===hash(eventBytes)&&hash(await read(attemptPath))===hash(attemptBytes)&&hash(await read(old+'/source-baseline.json'))===hash(baselineBytes),'ORIGINAL_FAILURE_CHANGED');
 const allowed=[...originalNames,...(retired.has(locks[1].path)?['retired-controller97.lock']:[])].sort();
 require(canonical((await fs.readdir(old)).sort())===canonical(allowed),'ORIGINAL_OUTPUT_SET_CHANGED');
 for(let i=0;i<2;i++)require(hash(await read(old+'/target-inventory-'+(i===0?5:8)+'.json'))===hash(targets[i]),'TARGET_INVENTORY_CHANGED');
 await absent('docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46/single-database-maintenance-'+C.source+'.lock');
 const dead=await command('pwsh',['-NoProfile','-NonInteractive','-Command',"@(Get-CimInstance Win32_Process -Filter 'ProcessId=39160 OR ProcessId=36076').Count"]);require(dead.trim()==='0','FAILED_OWNER_ALIVE');
 const f=await readProjectFacts76({projectImpactScope:true}),b=JSON.parse(await command('docker',['network','inspect','bridge']))[0];
 if(!host){const original=JSON.parse(await read(old+'/host-responsibility.json')),parent=f.hostProcesses.find(p=>p.pid===process.ppid);require(parent&&parent.commandSha256&&/^(pwsh|powershell)\.exe$/i.test(parent.name),'RECOVERY_PARENT_UNKNOWN');host={...original,runId,records:[...original.records.filter(p=>p.pid!==process.ppid),{...parent,role:'CONTROLLER_LAUNCHER'}]};hostRef=await save(dir+'/host-responsibility.json',host);}
 assertOriginalResources75(f,prior);assertHostResponsibility75(f,host);assertBridge78(b);
 const live=[C.source,C.sourceTool,...C.writers],by=new Map(f.containers.map(c=>[c.Id,c]));
 for(const id of live){const c=by.get(id);require(c.State.Running&&!c.State.Restarting&&c.State.Pid>0,'SOURCE_ROLE_NOT_READY');}
 for(const id of [C.target,C.tool])require(!by.get(id).State.Running&&by.get(id).State.Pid===0,'TARGET_OR_TOOL_NOT_STOPPED');
 require(by.get(C.source).State.Pid===93277&&by.get(C.source).State.StartedAt==='2026-10-12T12:58:04.696089565Z','SOURCE_EPOCH_CHANGED');
 require(canonical(f.ownedRunning.sort())===canonical([...live].sort())&&canonical(Object.keys(f.network.Containers).sort())===canonical([...live].sort())&&live.reduce((n,id)=>n+by.get(id).HostConfig.Memory,0)<=7516192768&&live.reduce((n,id)=>n+by.get(id).HostConfig.NanoCpus,0)<=4000000000,'CURRENT_RESOURCE_OR_BUDGET_CHANGED');
 for(const id of C.writers)assertWriterNetworks78(id,by.get(id).NetworkSettings.Networks,prior.records.find(r=>r.id===id).networks,b);
 const top=(await command('docker',['top',C.sourceTool,'-eo','pid,comm'])).trim().split(/\r?\n/);require(top.length===2&&/\bsleep\s*$/.test(top[1]),'SOURCE_TOOL_NOT_IDLE');
 if(!initial){initial=f;bridge=b;}else{for(const c of f.containers){const previous=initial.containers.find(p=>p.Id===c.Id);require(canonical(c.State)===canonical(previous.State)&&canonical(resourceBinding75(c))===canonical(resourceBinding75(previous)),'RESOURCE_CHANGED_DURING_RETURN');}require(canonical(b)===canonical(bridge),'BRIDGE_CHANGED_DURING_RETURN');}
 await assertCurrentScope({format:'ACTR66_TRUSTED_FIXTURE_POLICY/v1',mode:'EXISTING_IMPORTED_TARGET',runId,scopeApproval:{path:'docs/evidence/activation-recovery-design-20261010/scope-consumption70/scope-approval70.json',sha256:'f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0'},hostResponsibility:hostRef,sourceProtection:null,sourceReturnPlan:null,bundle:{path:manifest.bridge.path,sha256:C.bundleSha256}},{workspaceRoot:root});
 if(exclusive)require((await read(mutex)).toString()===runId+' '+process.pid+'\n'&&canonical(lockIdentity81(await fs.lstat(mutex,{bigint:true})))===canonical(exclusiveIdentity),'RECOVERY_MUTEX_CHANGED');
 await save(dir+'/current-'+(++sequence)+'.json',{phase,resources:f.containers.map(c=>({id:c.Id,state:c.State,bindingSha256:hash(canonical(resourceBinding75(c)))})),hostProcesses:f.hostProcesses,hostScopeObservation:f.hostScopeObservation,sourceToolIdle:true,originalOwnersDead:true});
};
try{
 await guard('READ_ONLY_ADMISSION');await absent(mutex);exclusive=await fs.open(mutex,'wx',0o600);await exclusive.writeFile(runId+' '+process.pid+'\n');await exclusive.sync();exclusiveIdentity=lockIdentity81(await exclusive.stat({bigint:true}));
 await guard('BEFORE_SECRET');secret=await command('pwsh',['-NoProfile','-NonInteractive','-File',root+'/scripts/lib/consultation-failed-controller-secret.ps1']);require(secret.length>0&&secret.length<4096,'MANAGED_SECRET_UNAVAILABLE');
 await guard('AFTER_SECRET_HELPER');
 const inventory=async name=>{await guard('BEFORE_'+name);process.env.SINGLE_DB_ADMIN_PASSWORD=secret;try{return assertInventory(await jdbcRequest(template.inventoryJdbc,{action:'inventory',targetId:C.source,schemas:template.schemas,accounts:template.accounts,restoreCatalogProfile:template.recoveryCatalog.profileId},{captureRaw:b=>save(dir+'/'+name+'.json',b)}),C.source,template.schemas,template.accounts,template.deployment);}finally{delete process.env.SINGLE_DB_ADMIN_PASSWORD;}};
 const before=await inventory('source-before'),caRef=JSON.parse(await command('pwsh',['-NoProfile','-NonInteractive','-File',root+'/scripts/lib/consultation-failed-controller-public-ca.ps1']));assertPublicCaReference81(caRef);await save(dir+'/public-ca-binding.json',caRef);
 const st=await fs.lstat(caRef.path);require(st.isFile()&&!st.isSymbolicLink()&&st.size<=65536&&(await fs.realpath(caRef.path)).replaceAll('\\','/')===caRef.path,'PUBLIC_CA_REDIRECT');const ca=await fs.readFile(caRef.path);require(hash(ca)===PUBLIC_CA81.sha256,'PUBLIC_CA_PIN_CHANGED');
 await guard('BEFORE_TLS');const tls=await probeSourceReturnTls({caBytes:ca});assertReconciliationTls81(tls);await save(dir+'/tls.json',tls);
 const after=await inventory('source-after');require(completeSourceDigest81(before)===completeSourceDigest81(after)&&completeSourceDigest81(after)===completeSourceDigest81(baseline),'FULL_SOURCE_RETURN_CHANGED');
 await guard('BEFORE_ARCHIVE');for(const [i,l] of locks.entries())await save(dir+'/original-lock-'+i+'.lock',await read(l.path,l.sha256));
 await save(dir+'/proof.json',{failedRun:failed,sourceInventorySha256:completeSourceDigest81(after),twoCurrentInventoriesEqual:true,originalBaselineMatched:true,oldSixTargetUnchanged:true,originalCaseSucceeded:false,originalSourceReturnedResultUnchanged:true,partialSchemaPreserved:'ACTR66_EMPTY',locks,tls,operationalSourceSha256:sourceSha,sqlWrites:0,resourceMutations:0});
 for(const l of locks){await guard('BEFORE_RETIRE');await absent(l.destination);await save(dir+'/retire-prepared-'+retired.size+'.json',{path:l.path,destination:l.destination,sha256:l.sha256});await fs.rename(l.path,l.destination);retired.add(l.path);await exactLock(l);await absent(l.path);await save(dir+'/retire-completed-'+retired.size+'.json',{path:l.path,destination:l.destination,sha256:l.sha256});}
 await guard('COMPLETE');await save(dir+'/result.json',{entryLockRetired:true,controllerLockRetired:true,currentSourceHealthy:true,partialSchemaPreserved:'ACTR66_EMPTY',originalCaseSucceeded:false,originalSourceReturnedResultUnchanged:true,sqlWrites:0,resourceMutations:0,automaticRetryAllowed:false});
 await exclusive.close();exclusive=null;await fs.unlink(mutex);success=true;console.log(JSON.stringify({directory:dir,entryLockRetired:true,controllerLockRetired:true,originalCaseSucceeded:false,partialSchemaPreserved:'ACTR66_EMPTY'}));
}catch(e){await save(dir+'/failure.json',{...continuousFailureRecord75(e),retiredLocks:[...retired],automaticRetryAllowed:false});console.error('RECOVERY_REFUSED; EVIDENCE='+dir);process.exitCode=1;}
finally{secret=null;delete process.env.SINGLE_DB_ADMIN_PASSWORD;if(exclusive)await exclusive.close();if(!success)console.error('LOCK_SCENE_PRESERVED');}
