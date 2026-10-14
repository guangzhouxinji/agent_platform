import {randomUUID} from 'node:crypto';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {isIP} from 'node:net';
import {assertNativeFenceWorkerFacts} from './single-database-native-fence-container.mjs';
import {PARTIAL_RECOVERY97} from './consultation-activation-recovery-fixture-plan.mjs';

export const CONTINUOUS75=Object.freeze({
  source:'f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f',
  target:'78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481',
  sourceTool:'ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096',
  tool:'e6e40303d097075b4030d43c73158535e11b065a0f35a5dd16d6c8be9540105e',
  writers:Object.freeze(['9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f','6796847921a4c9542d8e2e2e7b203a72faae2a89d508ed6dd452ccb009baab2f','4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff','1bf91da6e10a789033932c7cd20aa806e7c6a81e3142509692247daa89af0df1','e0e5ddf6b3caac946c333ce8b581ebde6bbd8a6918b25a053e55b7b6c1a07449']),
  deadline:'2026-10-13T18:00:00+08:00',bundleSha256:'4c4736bdc795498b327d031f7650828c1d8a8860377d32cb1e5cf711d232e2f8',
  manifest:'manifest75-18.json',
  root:'docs/evidence/activation-recovery-design-20261010/continuous-fence75',
});
const require=(yes,code)=>{if(!yes)throw Error(code);};
const hostResponsibilityDiagnostics=new WeakMap();
const hostIdentity84=(value,includeRole=false)=>Object.freeze({
  pid:Number.isSafeInteger(value?.pid)&&value.pid>0?value.pid:null,
  parentId:Number.isSafeInteger(value?.parentId)&&value.parentId>=0?value.parentId:null,
  createdAt:typeof value?.createdAt==='string'&&value.createdAt.length<=64?value.createdAt:null,
  name:typeof value?.name==='string'&&value.name.length<=256?value.name:null,
  commandSha256:/^[a-f0-9]{64}$/.test(value?.commandSha256||'')?value.commandSha256:null,
  ...(includeRole?{role:['NON_DB_PROCESS','CONTROLLER_LAUNCHER'].includes(value?.role)?value.role:null}:{})
});
const hostResponsibilityError84=(reason,observed,declared=null)=>{
  const pid=Number.isSafeInteger(observed?.pid)&&observed.pid>0?observed.pid:'UNKNOWN';
  const prefix=reason==='UNREADABLE'?'CONTINUOUS_UNREADABLE_HOST_':'CONTINUOUS_UNREGISTERED_HOST_';
  const error=Error(prefix+pid);
  hostResponsibilityDiagnostics.set(error,Object.freeze({
    format:'ACTR84_HOST_RESPONSIBILITY_DIAGNOSTIC/v1',reason,
    observed:hostIdentity84(observed),declared:declared?hostIdentity84(declared,true):null
  }));
  return error;
};
export function hostResponsibilityDiagnostic75(error){
  const diagnostic=hostResponsibilityDiagnostics.get(error);
  return diagnostic?structuredClone(diagnostic):null;
}
export function assertReadableHostProcesses75(facts){
  for(const process of facts.hostProcesses){
    if(!(Number.isSafeInteger(process.pid)&&process.pid>0&&/^[a-f0-9]{64}$/.test(process.commandSha256||'')&&Number.isFinite(Date.parse(process.createdAt))))throw hostResponsibilityError84('UNREADABLE',process);
  }
}
const orderedMounts=mounts=>[...(mounts??[])].sort((a,b)=>`${a.Destination}\0${a.Type}\0${a.Source}`.localeCompare(`${b.Destination}\0${b.Type}\0${b.Source}`));
export function fixedCase75(operation){
  require(['CASE_EMPTY','CASE_RECEIPT','CASE_EVENT'].includes(operation),'CONTINUOUS_CASE_REFUSED');
  return Object.freeze({operation,schema:'ACTR66_'+operation.slice(5),previousOutcome:operation==='CASE_EMPTY'?PARTIAL_RECOVERY97.outcome:'CLEAR'});
}
export function oldSixDigest75(inventory){
  require(inventory?.actualJdbc===true&&inventory.complete===true&&inventory.targetId===CONTINUOUS75.target&&inventory.schemas?.length===6&&inventory.accounts?.length===15&&inventory.restoreCatalog?.databaseVersion==='8.1.4.170'&&inventory.nativePrivileges,'COMPLETE_IMPORTED_SIX_INVENTORY_REQUIRED');
  require(inventory.restoreCatalog.categories&&typeof inventory.restoreCatalog.categories==='object'&&!Array.isArray(inventory.restoreCatalog.categories),'COMPLETE_IMPORTED_CATALOG_REQUIRED');
  const categories=Object.fromEntries(Object.entries(inventory.restoreCatalog.categories).map(([name,category])=>{
    require(Array.isArray(category?.columns)&&Array.isArray(category?.rows),'COMPLETE_IMPORTED_CATALOG_ROWS_REQUIRED');
    return [name,{...category,rows:category.rows.map(canonical).sort()}];
  }));
  return hash(canonical({targetId:inventory.targetId,schemas:inventory.schemas,accounts:inventory.accounts,restoreCatalog:{...inventory.restoreCatalog,categories},nativePrivileges:inventory.nativePrivileges}));
}
export function assertCurrentManifestTask75(task,manifestDigest){
  const text=Object.values(task??{}).join(' ');
  const links=[...text.matchAll(/\]\(([^()\s]+)\)/g)].map(match=>match[1]);
  const digests=text.match(/\b[a-f0-9]{64}\b/g)??[];
  const planRelative=CONTINUOUS75.root.replace(/^docs\//,'')+'/'+CONTINUOUS75.manifest;
  require(task?.['状态']==='已完成'&&links.includes(planRelative)&&digests.includes(manifestDigest),'CONTINUOUS_CURRENT_SOURCE_MANIFEST_GATE_REQUIRED');
}
export function assertRepairTasks78(tasks){
  for(const id of ['FIX-CON-ACTR78-WORKER-RETURN-01','REC-CON-ACTR78-FAILED-CONTROLLER-01'])require(tasks.filter(t=>t.taskId===id).length===1&&tasks.find(t=>t.taskId===id)['状态']==='已完成','CONTINUOUS_FAILURE_REPAIR_AND_RECONCILIATION_REQUIRED');
}
export function assertHostResponsibility75(facts,responsibility,worker){
  require(responsibility?.format==='ACTR66_HOST_WRITER_RESPONSIBILITY/v1'&&responsibility.owner==='Codex'&&Array.isArray(responsibility.records),'CONTINUOUS_HOST_RESPONSIBILITY_REQUIRED');
  const records=new Map(responsibility.records.map(r=>[r.pid,r]));require(records.size===responsibility.records.length,'CONTINUOUS_DUPLICATE_HOST_RECORD');
  const own=worker?assertNativeFenceWorkerFacts(worker,facts,CONTINUOUS75.sourceTool):null;
  for(const p of facts.hostProcesses){
    const r=records.get(p.pid);
    if(!(Number.isSafeInteger(p.pid)&&p.pid>0&&/^[a-f0-9]{64}$/.test(p.commandSha256||'')&&Number.isFinite(Date.parse(p.createdAt))))throw hostResponsibilityError84('UNREADABLE',p,r);
    if(p.pid===facts.selfPid)continue;
    if(own&&p.pid===own.pid)continue;
    if(!r)throw hostResponsibilityError84('UNREGISTERED',p);
    if(!(r.parentId===p.parentId&&r.createdAt===p.createdAt&&r.commandSha256===p.commandSha256&&['NON_DB_PROCESS','CONTROLLER_LAUNCHER'].includes(r.role)))throw hostResponsibilityError84('DECLARED_IDENTITY_MISMATCH',p,r);
  }
}
export const DEFAULT_BRIDGE78='3fc351592a63a334572db0a34af42b586da11586350ffc4e6450835bc956c5bb';
export function assertBridge78(current,expected=current){
  const binding=network=>{
    const fixed=Object.fromEntries(Object.entries(network??{}).filter(([key])=>key!=='Containers'));
    if(Object.hasOwn(fixed,'Status')){
      const status=fixed.Status,subnets=status?.IPAM?.Subnets,counts=subnets?.['172.17.0.0/16'];
      require(canonical(Object.keys(status??{}))===canonical(['IPAM'])&&canonical(Object.keys(status?.IPAM??{}))===canonical(['Subnets'])&&canonical(Object.keys(subnets??{}))===canonical(['172.17.0.0/16'])&&canonical(Object.keys(counts??{}).sort())===canonical(['DynamicIPsAvailable','IPsInUse'])&&Number.isSafeInteger(counts.IPsInUse)&&Number.isSafeInteger(counts.DynamicIPsAvailable)&&counts.DynamicIPsAvailable>=0&&counts.IPsInUse===Object.keys(network.Containers??{}).length+3&&counts.IPsInUse+counts.DynamicIPsAvailable===65536,'CONTINUOUS_DEFAULT_BRIDGE_COUNTERS_REFUSED');
      // Docker reserves the network, gateway and broadcast addresses; endpoint counts vary on stop/start.
      fixed.Status={IPAM:{Subnets:{'172.17.0.0/16':{IPsInUse:0,DynamicIPsAvailable:0}}}};
    }
    return fixed;
  };
  require(current?.Id===DEFAULT_BRIDGE78&&current.Name==='bridge'&&current.Driver==='bridge'&&current.Scope==='local'&&current.Internal===false&&current.EnableIPv6===false&&current.IPAM?.Driver==='default'&&current.IPAM.Config?.length===1&&current.IPAM.Config[0].Subnet==='172.17.0.0/16'&&current.IPAM.Config[0].Gateway==='172.17.0.1'&&canonical(binding(current))===canonical(binding(expected)),'CONTINUOUS_DEFAULT_BRIDGE_CONTRACT_CHANGED');
  const addresses=Object.values(current.Containers??{}).map(c=>c.IPv4Address);
  require(new Set(addresses).size===addresses.length,'CONTINUOUS_DEFAULT_BRIDGE_DUPLICATE_ADDRESS');
  for(const address of addresses){
    const [ip,prefix,...extra]=typeof address==='string'?address.split('/'):[],parts=isIP(ip)===4?ip.split('.').map(Number):[];
    require(extra.length===0&&prefix==='16'&&parts.length===4&&parts[0]===172&&parts[1]===17&&((parts[2]<<8)+parts[3])>=2&&((parts[2]<<8)+parts[3])<=65534,'CONTINUOUS_DEFAULT_BRIDGE_ADDRESS_REFUSED');
  }
}
export function assertWriterNetworks78(id,current,expected,bridge){
  require(CONTINUOUS75.writers.includes(id)&&current&&expected&&canonical(Object.keys(current).sort())===canonical(Object.keys(expected).sort()),'CONTINUOUS_ORIGINAL_WRITER_NETWORK_NOT_RETURNED');
  const publisher=[CONTINUOUS75.writers[1],CONTINUOUS75.writers[3]].includes(id),changes=[];
  for(const name of Object.keys(expected)){
    const now=current[name],old=expected[name],dynamic=publisher&&name==='bridge'&&old.NetworkID===DEFAULT_BRIDGE78;
    const staticFields=value=>Object.fromEntries(Object.entries(value).filter(([k])=>!['EndpointID','MacAddress',...(dynamic?['IPAddress']:[])].includes(k)));
    require(canonical(staticFields(now))===canonical(staticFields(old)),'CONTINUOUS_ORIGINAL_WRITER_NETWORK_NOT_RETURNED');
    if(dynamic){
      assertBridge78(bridge);
      const ip=now.IPAddress,parts=isIP(ip)===4?ip.split('.').map(Number):[];
      require(parts.length===4&&parts[0]===172&&parts[1]===17&&((parts[2]<<8)+parts[3])>=2&&((parts[2]<<8)+parts[3])<=65534&&now.Gateway==='172.17.0.1'&&now.IPPrefixLen===16&&now.IPAMConfig===null&&!now.GlobalIPv6Address&&!now.IPv6Gateway,'CONTINUOUS_DEFAULT_BRIDGE_ADDRESS_REFUSED');
      const endpoint=bridge.Containers?.[id];
      require(endpoint?.IPv4Address===ip+'/16'&&endpoint.MacAddress===now.MacAddress&&/^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(now.MacAddress)&&!(parseInt(now.MacAddress.slice(0,2),16)&1)&&!/^00(?::00){5}$/.test(now.MacAddress),'CONTINUOUS_DEFAULT_BRIDGE_ENDPOINT_CHANGED');
      if(ip!==old.IPAddress)changes.push({id,networkId:DEFAULT_BRIDGE78,previousAddress:old.IPAddress,currentAddress:ip});
    }
  }
  return changes;
}
export function assertOriginalResources75(facts,prior){
  require(prior?.format==='ACTR75_APPROVED_CURRENT_RESOURCE_BASELINE/v1'&&prior?.records?.length===9&&facts.containers?.length===9,'CONTINUOUS_APPROVED_RESOURCE_RECORDS_REQUIRED');
  const records=new Map(prior.records.map(r=>[r.id,r]));require(records.size===9,'CONTINUOUS_DUPLICATE_RESOURCE');
  for(const c of facts.containers){const p=records.get(c.Id);
    require(p&&c.Name===p.name&&c.Image===p.image&&canonical(c.Config.Labels)===canonical(p.labels)&&c.Config.User===p.user&&canonical(c.Config.Cmd)===canonical(p.command)&&canonical(c.Config.Entrypoint)===canonical(p.entrypoint),'CONTINUOUS_APPROVED_RESOURCE_IDENTITY_CHANGED');
    require(c.HostConfig.Memory===p.memoryBytes&&c.HostConfig.NanoCpus===p.nanoCpus&&c.HostConfig.ReadonlyRootfs===p.readonlyRootfs&&canonical(c.HostConfig.RestartPolicy)===canonical(p.restartPolicy)&&canonical(c.HostConfig.PortBindings)===canonical(p.ports)&&canonical(orderedMounts(c.Mounts))===canonical(orderedMounts(p.mounts)),'CONTINUOUS_APPROVED_RESOURCE_CONFIGURATION_CHANGED');
    require(canonical(Object.keys(c.NetworkSettings.Networks).sort())===canonical(Object.keys(p.networks).sort())&&Object.keys(p.networks).every(n=>c.NetworkSettings.Networks[n].NetworkID===p.networks[n].NetworkID),'CONTINUOUS_APPROVED_NETWORK_CHANGED');
  }
}

// Ports are internal production adapters, never request-supplied SQL, commands or "verified" flags.
export function continuousCase75(ports,{operation='CASE_EMPTY',runId=randomUUID(),now=()=>new Date()}={}){
  const request=fixedCase75(operation);require(/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(runId),'CONTINUOUS_RUN_ID_REQUIRED');
  let used=false,pending=false;
  const execute=async()=>{
    require(!used&&!pending,'CONTINUOUS_RUN_ALREADY_USED');used=true;pending=true;
    let journal,controller,entry,result,sourceInventory,targetInventory,protection,error,returnError,returned=false,released=false;
    const stopped=new Set(),attemptedStop=new Set(),returnErrors=[];let targetTouched=false,sourceTouched=false;
    const event=async(kind,data={})=>journal.appendAndSync({kind,runId,at:now().toISOString(),...data});
    const action=async(step,fn)=>{
      await ports.assertCurrent(step);await event('PREPARED',{step});
      try{const value=await fn();await event('COMPLETED',{step});return value;}
      catch(e){try{await event('UNKNOWN',{step,code:ports.safeCode(e)});}catch{}throw e;}
    };
    const stop=async(id,step)=>{
      require(!attemptedStop.has(id),'CONTINUOUS_STOP_NOT_RETRIED');attemptedStop.add(id);
      await action(step,()=>ports.stopExact(id));stopped.add(id);
    };
    try {
      require(now().getTime()<Date.parse(CONTINUOUS75.deadline)-60*60*1000,'CONTINUOUS_START_RESERVE_REQUIRED');
      await ports.admit({runId,request});
      ({journal,controller}=await ports.acquireController({runId,request}));
      for(const id of [CONTINUOUS75.writers[1],CONTINUOUS75.writers[3],CONTINUOUS75.writers[0],CONTINUOUS75.writers[2],CONTINUOUS75.writers[4]])await stop(id,'STOP_WRITER_'+id);
      sourceInventory=await action('SOURCE_BASELINE',()=>ports.sourceInventory());
      await action('SOURCE_IDLE_PROBE',()=>ports.probe('SOURCE_IDLE'));
      protection=await action('FRESH_SOURCE_CAPTURE',()=>ports.captureSource({runId,sourceInventory}));
      await action('AUTHENTICATED_PROTECTION_BINDING',()=>ports.validateProtection(protection,{runId,sourceInventory}));
      await action('SOURCE_FENCE_PROCESS_RETURNED',()=>ports.assertSourceFenceReturned());
      await stop(CONTINUOUS75.sourceTool,'STOP_SOURCE_TOOL');
      sourceTouched=true;await stop(CONTINUOUS75.source,'STOP_SOURCE');
      targetTouched=true;await action('START_IMPORTED_TARGET',()=>ports.startExact(CONTINUOUS75.target));
      await action('START_TARGET_TOOL',()=>ports.startExact(CONTINUOUS75.tool));
      await action('TARGET_IMPORTED_PROBE',()=>ports.probe('TARGET_IMPORTED'));
      targetInventory=await action('TARGET_OLD_SIX_BEFORE',()=>ports.targetInventory());oldSixDigest75(targetInventory);
      entry=await action('CREATE_FIXED_CASE_ENTRY',()=>ports.createEntry({runId,request,protection}));
      result=await action('FIXED_CASE',()=>entry.run(request));
    }catch(e){error=e;if(ports.recordFailure)try{await ports.recordFailure(e);}catch{}}
    finally {
      if(controller){
        try {
          if(targetTouched){
            // On failure, this is diagnostic read evidence only; it never enables another case.
            if(targetInventory&&await ports.isRunning(CONTINUOUS75.target)){
              const after=await action('TARGET_OLD_SIX_AFTER',()=>ports.targetInventory());
              require(oldSixDigest75(after)===oldSixDigest75(targetInventory),'IMPORTED_OLD_SIX_CHANGED');
            }
          }
        }catch(e){error??=e;}
        try {
          if(targetTouched){
            for(const [id,step]of [[CONTINUOUS75.tool,'STOP_TARGET_TOOL'],[CONTINUOUS75.target,'STOP_TARGET']]){
              if(await ports.isRunning(id)){require(!attemptedStop.has(id),'UNKNOWN_STOP_SCENE_PRESERVED');await stop(id,step);}
              await ports.assertStopped(id);
            }
          }
          if(sourceTouched||stopped.has(CONTINUOUS75.sourceTool)){
            await ports.assertStopped(CONTINUOUS75.target);await ports.assertStopped(CONTINUOUS75.tool);
            if(!await ports.isRunning(CONTINUOUS75.source))await action('RETURN_SOURCE',()=>ports.startExact(CONTINUOUS75.source));
            if(!await ports.isRunning(CONTINUOUS75.sourceTool))await action('RETURN_SOURCE_TOOL',()=>ports.startExact(CONTINUOUS75.sourceTool));
          }
          for(const id of CONTINUOUS75.writers)if(stopped.has(id)||attemptedStop.has(id)&&!await ports.isRunning(id)){
            const step='RETURN_WRITER_'+id;
            // Guard and journal failures are global; only a role's operation is isolated.
            await ports.assertCurrent(step);await ports.assertWriterReturnSafe();await event('PREPARED',{step});
            let roleError;try{await ports.returnWriter(id);}catch(e){roleError=e;}
            await event(roleError?'UNKNOWN':'COMPLETED',{step,...(roleError?{code:ports.safeCode(roleError)}:{})});
            if(roleError){returnError??=roleError;returnErrors.push({id,code:ports.safeCode(roleError)});}
          }
          if(returnError)throw returnError;
          if(entry){await action('COMPLETE_SOURCE_RETURN_AND_TARGET_LEASE_RELEASE',async()=>entry.releaseAfterSourceReturn(await ports.returnRequest({runId})));returned=true;}
          else{await action('COMPLETE_SOURCE_RETURN',()=>ports.verifySourceReturn({sourceInventory,protection,runId}));returned=true;}
          if(!error){await event('SOURCE_RETURNED');await action('RELEASE_CONTROLLER',()=>controller.releaseAfterSourceReturn());released=true;}
        }catch(e){returnError=e;try{await event('RETURN_UNKNOWN',{code:ports.safeCode(e)});}catch{}}
        try{await event('RESULT',{caseOutcome:result?.outcome??'NO_CASE_RESULT',caseCompleted:!!result&&!error,sourceReturned:returned,controllerReleased:released,code:error?ports.safeCode(error):returnError?ports.safeCode(returnError):'NONE',returnCode:returnError?ports.safeCode(returnError):'NONE',returnErrors,automaticRetryAllowed:false});}finally{await journal.close();await controller.close();}
      }
      pending=false;
    }
    if(error||returnError){const failure=Error(error?ports.safeCode(error):ports.safeCode(returnError));failure.sourceReturned=returned;failure.controllerReleased=released;failure.returnErrors=returnErrors;failure.automaticRetryAllowed=false;throw failure;}
    return {runId,...result,sourceReturned:returned,controllerReleased:released,automaticRetryAllowed:false};
  };
  return Object.freeze({execute,runId,request});
}
