import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {parseTaskRows} from './plan-dependencies.mjs';
import {assertCurrentScope,probeSourceReturnTls,ENTRY68} from './consultation-activation-recovery-entry.mjs';
import {CONTINUOUS75 as C,DEFAULT_BRIDGE78} from './consultation-activation-recovery-continuous-fence.mjs';
import {readProjectFacts76} from './consultation-project-resource-isolation.mjs';
import {assertInventory,jdbcRequest,canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {RECONCILIATION81 as R,PREVIOUS_RECONCILIATION81 as P,assertPreviousExclusive81,archiveOwnedExclusive81,lockIdentity81,PUBLIC_CA81,assertPublicCaReference81,assertReconciliationBundle81,assertReconciliationFacts81,readOriginalController81,readReconciliationFile81 as readFile,writeReconciliationFile81 as saveFile,checkedReconciliationPath81 as checked,createFailedControllerReconciliation81} from './consultation-failed-controller-reconciliation.mjs';

const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..'),exec=promisify(execFile);
const MANIFEST=C.root+'/'+R.bundle;
const TEMPLATE='docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/source-snapshot-config56.json';
const TEMPLATE_SHA='6170eab0136c1d9d3f6196e6684937a2d8ff6040a56fc2e9c5e1127fa4230c15';
const SOURCE_LOCK='docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46/single-database-maintenance-'+C.source+'.lock';
const TARGET_LOCK='docs/evidence/activation-recovery-design-20261010/entry-admission68/single-database-maintenance-'+C.target+'.lock';
const require=(yes,code)=>{if(!yes)throw Error(code);};
const read=(relative,pin,max)=>readFile(ROOT,relative,pin,max),save=(relative,value)=>saveFile(ROOT,relative,value);
const command=async(name,args,max=65536)=>exec(name,args,{shell:false,windowsHide:true,timeout:30000,maxBuffer:max,env:Object.fromEntries(Object.entries(process.env).filter(([key])=>!key.startsWith('SINGLE_DB_')))});
async function absent(relative){
  try{await fs.lstat(await checked(ROOT,relative,{newFile:true}));throw Error('RECONCILIATION_EXISTING_OUTPUT_OR_NATIVE_LOCK');}
  catch(e){if(e.code!=='ENOENT')throw e;}
}
const summary=f=>({capturedAt:new Date().toISOString(),selfPid:f.selfPid,hostProcesses:f.hostProcesses,resources:f.containers.map(c=>({id:c.Id,state:c.State,networks:c.NetworkSettings.Networks})),ownedRunning:f.ownedRunning,externalResources:f.externalResources});
export async function createProductionReconciliation81(){
  const manifestBytes=await read(MANIFEST),manifest=JSON.parse(manifestBytes);
  require(manifest.format==='ACTR81_FIXED_RECONCILIATION_BUNDLE/v1'&&manifest.tests.failed===0&&manifest.tests.passed===manifest.tests.tests&&manifest.tests.tests>0,'RECONCILIATION_TESTED_BUNDLE_REQUIRED');
  const pins=async()=>{require(hash(await read(MANIFEST))===hash(manifestBytes),'RECONCILIATION_MANIFEST_CHANGED');for(const f of manifest.files)await read(f.path,f.sha256);};
  await pins();assertReconciliationBundle81(parseTaskRows((await read('docs/08-总开发实现计划.md')).toString()),hash(manifestBytes));
  const continuous=JSON.parse(await read(C.root+'/'+C.manifest,'735b4f2a7826a43564a6affa8c05dbd72643a373ebb067087179ae2bbbe3d75e'));
  const prior=JSON.parse(await read(continuous.resourceBaseline.path,continuous.resourceBaseline.sha256));
  const template=JSON.parse(await read(TEMPLATE,TEMPLATE_SHA)),runId=randomUUID(),relative=C.root+'/reconcile-'+runId;
  await fs.mkdir(await checked(ROOT,relative,{newFile:true}));
  const initial=await readProjectFacts76();await save(relative+'/observation.json',summary(initial));
  const initialBridge=JSON.parse((await command('docker',['network','inspect',DEFAULT_BRIDGE78],1024*1024)).stdout)[0];
  await save(relative+'/bridge-observation.json',initialBridge);
  const baselineBytes=await read(R.baseline,R.baselineSha256),baseline=JSON.parse(baselineBytes);
  assertInventory(baseline,C.source,template.schemas,template.accounts,template.deployment);
  let hostBytes,host,secretValue,secretLoaded=false,observations=0,exclusive,exclusiveBytes,exclusiveIdentity,closed=false,retirementAttempted=false;
  const ensureHost=async()=>{
    const bytes=await read(relative+'/host-responsibility.json',undefined,1024*1024);
    if(hostBytes)require(hash(bytes)===hash(hostBytes),'RECONCILIATION_HOST_RESPONSIBILITY_CHANGED');
    else{hostBytes=bytes;host=JSON.parse(bytes);require(host.runId===runId,'RECONCILIATION_HOST_RUN_MISMATCH');}
  };
  const assertCurrent=async phase=>{
    await pins();await ensureHost();assertReconciliationBundle81(parseTaskRows((await read('docs/08-总开发实现计划.md')).toString()),hash(manifestBytes));
    const hostRef={path:relative+'/host-responsibility.json',sha256:hash(hostBytes)};
    await assertCurrentScope({format:'ACTR66_TRUSTED_FIXTURE_POLICY/v1',mode:'EXISTING_IMPORTED_TARGET',runId,scopeApproval:{path:'docs/evidence/activation-recovery-design-20261010/scope-consumption70/scope-approval70.json',sha256:'f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0'},hostResponsibility:hostRef,sourceProtection:null,sourceReturnPlan:null,bundle:{path:'docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74.json',sha256:C.bundleSha256}});
    const facts=await readProjectFacts76();
    const bridge=JSON.parse((await command('docker',['network','inspect',DEFAULT_BRIDGE78],1024*1024)).stdout);
    require(bridge.length===1,'RECONCILIATION_SINGLE_DEFAULT_BRIDGE_REQUIRED');
    const staticNetwork=n=>Object.fromEntries(Object.entries(n).filter(([k])=>k!=='Containers'));
    require(canonical(staticNetwork(bridge[0]))===canonical(staticNetwork(initialBridge)),'RECONCILIATION_DEFAULT_BRIDGE_CHANGED');
    const owners=JSON.parse((await command('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-Command',"$ErrorActionPreference='Stop';ConvertTo-Json -InputObject @(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -in @(44916,41388)} | Select-Object ProcessId,ParentProcessId) -Compress"])).stdout);
    assertReconciliationFacts81(facts,prior,host,bridge[0],owners);
    for(const id of [C.source,C.sourceTool,...C.writers]){
      const current=facts.containers.find(c=>c.Id===id).State,original=initial.containers.find(c=>c.Id===id).State;
      require(current.Pid===original.Pid&&current.StartedAt===original.StartedAt,'RECONCILIATION_ROLE_RESTARTED_DURING_ATTEMPT');
    }
    require(facts.network.Id===ENTRY68.network,'RECONCILIATION_PROJECT_NETWORK_CHANGED');
    for(const lock of [SOURCE_LOCK,TARGET_LOCK])await absent(lock);
    const top=(await command('docker',['top',C.sourceTool,'-eo','pid,comm'])).stdout.trim().split(/\r?\n/);
    require(top.length===2&&/\bsleep\s*$/.test(top[1]),'RECONCILIATION_SOURCE_TOOL_NOT_IDLE');
    await readOriginalController81(ROOT);
    if(exclusive){
      require(hash(await read(R.exclusive))===hash(exclusiveBytes),'RECONCILIATION_EXCLUSIVE_OWNER_CHANGED');
      const s=await fs.lstat(await checked(ROOT,R.exclusive),{bigint:true});
      require(String(s.dev)===exclusiveIdentity.dev&&String(s.ino)===exclusiveIdentity.ino&&String(s.birthtimeNs)===exclusiveIdentity.birthtimeNs,'RECONCILIATION_EXCLUSIVE_FILE_REPLACED');
    }
    await save(relative+'/current-'+(++observations)+'.json',{phase,...summary(facts),selectedLiveOwners:owners,sourceToolIdle:true,nativeLocksAbsent:true});
  };
  const pipeline=createFailedControllerReconciliation81({
    assertCurrent,
    acquireExclusive:async()=>{
      let previousExists=false;try{await fs.lstat(await checked(ROOT,R.exclusive,{newFile:true}));previousExists=true;}catch(e){if(e.code!=='ENOENT')throw e;}
      if(previousExists){
        const oldRelative=C.root+'/reconcile-'+P.runId;
        const failure=JSON.parse(await read(oldRelative+'/failure.json',P.failureSha256));
        const bytes=await read(R.exclusive,P.sha256),identity=lockIdentity81(await fs.lstat(await checked(ROOT,R.exclusive),{bigint:true}));
        const dead=async()=>JSON.parse((await command('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-Command',"$ErrorActionPreference='Stop';ConvertTo-Json -InputObject @(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq 39324} | Select-Object ProcessId,ParentProcessId) -Compress"])).stdout);
        assertPreviousExclusive81(bytes,identity,failure,await dead());
        await read(oldRelative+'/source-before.json',R.baselineSha256);
        for(const name of ['source-after.json','tls.json','source-return-proof.json','original-controller.lock','retired-controller.lock','result.json'])await absent(oldRelative+'/'+name);
        await assertCurrent('RETURN_PREVIOUS_OWNED_MUTEX');assertPreviousExclusive81(await read(R.exclusive,P.sha256),lockIdentity81(await fs.lstat(await checked(ROOT,R.exclusive),{bigint:true})),failure,await dead());
        await readOriginalController81(ROOT);
        const archive=await archiveOwnedExclusive81(ROOT,oldRelative,bytes,identity);
        await save(relative+'/previous-mutex-return.json',{format:'ACTR82_EXACT_PREVIOUS_MUTEX_RETURN/v1',previousRunId:P.runId,previousOwnerPid:P.ownerPid,originalControllerUnchanged:true,previousInventoryRawEqualsBaseline:true,archive,identity,sqlWrites:0,resourceMutations:0});
      }
      exclusiveBytes=Buffer.from(JSON.stringify({runId,ownerPid:process.pid,failedRun:R.failedRun})+'\n');
      exclusive=await fs.open(await checked(ROOT,R.exclusive,{newFile:true}),'wx',0o600);
      await exclusive.writeFile(exclusiveBytes);await exclusive.sync();const s=await exclusive.stat({bigint:true});
      exclusiveIdentity=lockIdentity81(s);
      return {close:async()=>{
        if(!closed){
          await exclusive.close();closed=true;
          if(!retirementAttempted){
            await readOriginalController81(ROOT);
            await archiveOwnedExclusive81(ROOT,relative,exclusiveBytes,exclusiveIdentity);
          }
        }
      },release:async()=>{
        require(hash(await read(R.exclusive))===hash(exclusiveBytes),'RECONCILIATION_EXCLUSIVE_OWNER_CHANGED');
        const actual=await fs.lstat(await checked(ROOT,R.exclusive),{bigint:true});
        require(String(actual.ino)===exclusiveIdentity.ino&&String(actual.dev)===exclusiveIdentity.dev&&String(actual.birthtimeNs)===exclusiveIdentity.birthtimeNs,'RECONCILIATION_EXCLUSIVE_FILE_REPLACED');
        await exclusive.close();closed=true;await fs.unlink(await checked(ROOT,R.exclusive));
      }};
    },
    baseline:async()=>JSON.parse(await read(R.baseline,R.baselineSha256)),
    inventory:async phase=>{
      if(!secretLoaded){
        require(!process.env.SINGLE_DB_ADMIN_PASSWORD&&!process.env.SINGLE_DB_FINAL_NATIVE48_KEY&&!process.env.SINGLE_DB_FINAL_SNAPSHOT45_KEY,'RECONCILIATION_UNEXPECTED_INHERITED_SECRET');
        let value;
        try{value=(await command('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-File',path.join(ROOT,'scripts/lib/consultation-failed-controller-secret.ps1')],65536)).stdout;}
        catch{throw Error('RECONCILIATION_MANAGED_SECRET_UNAVAILABLE');}
        require(value.length>0&&value.length<4096,'RECONCILIATION_MANAGED_SECRET_UNAVAILABLE');secretValue=value;value=null;secretLoaded=true;
        // Recheck all live gates after the secret helper has exited, before JDBC starts.
        await assertCurrent('AFTER_MANAGED_SECRET');
      }
      require(!process.env.SINGLE_DB_ADMIN_PASSWORD,'RECONCILIATION_UNEXPECTED_INHERITED_SECRET');
      process.env.SINGLE_DB_ADMIN_PASSWORD=secretValue;
      try{
        const inventory=await jdbcRequest(template.inventoryJdbc,{action:'inventory',targetId:C.source,schemas:template.schemas,accounts:template.accounts,restoreCatalogProfile:template.recoveryCatalog.profileId},{captureRaw:bytes=>save(relative+'/source-'+phase+'.json',bytes)});
        return assertInventory(inventory,C.source,template.schemas,template.accounts,template.deployment);
      }finally{delete process.env.SINGLE_DB_ADMIN_PASSWORD;}
    },
    tls:async()=>{
      let ref;
      try{ref=JSON.parse((await command('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-File',path.join(ROOT,'scripts/lib/consultation-failed-controller-public-ca.ps1')])).stdout);}
      catch{throw Error('RECONCILIATION_PUBLIC_CA_BINDING_UNAVAILABLE');}
      assertPublicCaReference81(ref);await save(relative+'/public-ca-binding.json',ref);
      const caPath=ref.path;
      const s=await fs.lstat(caPath);require(s.isFile()&&!s.isSymbolicLink()&&s.size<=65536&&await fs.realpath(caPath)===path.resolve(caPath),'RECONCILIATION_PUBLIC_CA_REDIRECT');
      const ca=await fs.readFile(caPath);require(hash(ca)===PUBLIC_CA81.sha256,'RECONCILIATION_PUBLIC_CA_CHANGED');
      const tls=await probeSourceReturnTls({caBytes:ca});await save(relative+'/tls.json',tls);return tls;
    },
    archive:async proof=>{
      const original=await readOriginalController81(ROOT);
      const raw=await save(relative+'/original-controller.lock',original.bytes);
      const archived=await read(relative+'/original-controller.lock',R.markerSha256,79);
      require(archived.equals(original.bytes),'RECONCILIATION_ARCHIVE_CHANGED');
      await save(relative+'/source-return-proof.json',{format:'ACTR81_SOURCE_RETURN_PROOF/v1',runId,failedRun:R.failedRun,baseline:{path:R.baseline,sha256:R.baselineSha256},originalIdentity:original.identity,archive:raw,...proof});
      return raw;
    },
    retire:async archive=>{
      require(archive.path===relative+'/original-controller.lock'&&archive.sha256===R.markerSha256,'RECONCILIATION_EXACT_ARCHIVE_REQUIRED');
      await read(archive.path,archive.sha256,79);await readOriginalController81(ROOT);
      const destination=relative+'/retired-controller.lock';await absent(destination);
      await assertCurrent('FINAL_RETIRE');await readOriginalController81(ROOT);
      // A unique owned run directory and exclusive lock prevent cooperating writers from racing retirement.
      retirementAttempted=true;
      await fs.rename(await checked(ROOT,R.controller),await checked(ROOT,destination,{newFile:true}));
      const s=await fs.lstat(await checked(ROOT,destination),{bigint:true});
      require(String(s.dev)===R.identity.dev&&String(s.ino)===R.identity.ino&&String(s.birthtimeNs)===R.identity.birthtimeNs,'RECONCILIATION_RETIRED_FILE_IDENTITY_CHANGED');
      await read(destination,R.markerSha256,79);await absent(R.controller);
    },
    recordSuccess:result=>save(relative+'/result.json',{format:'ACTR81_RECONCILIATION_RESULT/v1',runId,taskId:R.task,failedRun:R.failedRun,...result,singleDatabaseABCComplete:false}),
    recordFailure:code=>save(relative+'/failure.json',{format:'ACTR81_RECONCILIATION_FAILURE/v1',runId,code,automaticRetryAllowed:false,originalCaseSucceeded:false}),
    clearSecret:async()=>{secretValue=null;secretLoaded=false;},
  });
  return {relative,runId,execute:pipeline.execute,admit:()=>assertCurrent('VALIDATE_ONLY')};
}
