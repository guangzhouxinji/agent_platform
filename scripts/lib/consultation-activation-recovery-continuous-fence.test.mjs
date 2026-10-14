import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {CONTINUOUS75 as C,DEFAULT_BRIDGE78,assertBridge78,assertWriterNetworks78,assertRepairTasks78,continuousCase75 as productionContinuousCase75,fixedCase75,oldSixDigest75,assertCurrentManifestTask75,assertHostResponsibility75,assertReadableHostProcesses75,hostResponsibilityDiagnostic75,assertOriginalResources75} from './consultation-activation-recovery-continuous-fence.mjs';
import {continuousFailureRecord75,assertBridgeSources91} from './consultation-activation-recovery-continuous-runtime.mjs';
import fs from 'node:fs/promises';

// Component scenarios use the approved historical window; explicit deadline cases override it.
const continuousCase75=(ports,options={})=>productionContinuousCase75(ports,{now:()=>new Date('2026-10-13T08:00:00Z'),...options});

test('91 current complete bridge source pins pass',async()=>{
  const bundle=JSON.parse(await fs.readFile('docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74-06.json'));
  await assertBridgeSources91(bundle,p=>fs.readFile(p));
});
test('91 stale original bridge rejects before controller lock or writer STOP',async()=>{
  const bundle=JSON.parse(await fs.readFile('docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74.json'));
  const c=component();c.ports.admit=()=>assertBridgeSources91(bundle,p=>fs.readFile(p));
  await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),/CONTINUOUS_BRIDGE_SOURCE_CHANGED/);
  assert.equal(c.calls.length,0);assert.equal(c.events.length,0);
});
for(const [name,mutate] of [
  ['missing',b=>b.sourceHashes.pop()],['duplicate',b=>b.sourceHashes[1]={...b.sourceHashes[0]}],
  ['unknown',b=>b.sourceHashes[0].path='requirements/unapproved.txt'],['invalid SHA',b=>b.sourceHashes[0].sha256='invalid'],
  ['wrong SHA',b=>b.sourceHashes[0].sha256='0'.repeat(64)],['extra field',b=>b.sourceHashes[0].extra=true],
])test('91 bridge refuses '+name,async()=>{
  const bundle=JSON.parse(await fs.readFile('docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74-06.json'));mutate(bundle);
  await assert.rejects(assertBridgeSources91(bundle,p=>fs.readFile(p)),/CONTINUOUS_(?:COMPLETE_BRIDGE_SOURCE_SET_REQUIRED|BRIDGE_SOURCE_PIN_REQUIRED|BRIDGE_SOURCE_CHANGED)/);
});
const RUN='75444444-5555-4666-8777-888888888888';
const inventory=()=>({actualJdbc:true,complete:true,targetId:C.target,schemas:Array.from({length:6},(_,n)=>({schema:'COMPONENT_'+n,rows:[]})),accounts:Array.from({length:15},(_,n)=>({name:'COMPONENT_'+n})),restoreCatalog:{databaseVersion:'8.1.4.170',categories:{}},nativePrivileges:{component:true}});
function component({fail,fsync,drift=false,returnFail=false,stopUnknown=false}={}){
  const active=new Set([C.source,C.sourceTool,...C.writers]),calls=[],events=[];let controllerReleased=false,closed=0,targetReads=0;
  const mark=(kind,fn=()=>{})=>async(...args)=>{calls.push({kind,args});if(fail===kind)throw Error('COMPONENT_'+kind+'_FAIL');return fn(...args);};
  const ports={
    safeCode:e=>e.message,admit:mark('ADMIT'),acquireController:mark('ACQUIRE',()=>({journal:{appendAndSync:async e=>{if(e.kind==='PREPARED'&&e.step===fsync)throw Error('COMPONENT_FSYNC_FAIL');events.push(e);},close:async()=>{closed++;}},controller:{releaseAfterSourceReturn:mark('RELEASE',()=>{controllerReleased=true;}),close:async()=>{closed++;}}})),
    assertCurrent:async step=>{assert.ok(!(active.has(C.source)&&active.has(C.target)),'two online component DBs');calls.push({kind:'GUARD',args:[step]});},
    stopExact:mark('STOP',id=>{if(stopUnknown&&id===C.target)throw Error('COMPONENT_STOP_UNKNOWN');active.delete(id);}),
    startExact:mark('START',id=>{assert.ok(!(id===C.source&&active.has(C.target)));assert.ok(!(id===C.target&&active.has(C.source)));active.add(id);}),
    isRunning:async id=>active.has(id),assertStopped:async id=>assert.ok(!active.has(id)),
    assertWriterReturnSafe:mark('RETURN_SAFE',()=>{assert.ok(!active.has(C.target));assert.ok(!active.has(C.tool));assert.ok(active.has(C.source));assert.ok(active.has(C.sourceTool));}),
    sourceInventory:mark('SOURCE_INVENTORY',()=>({componentSource:true})),probe:mark('PROBE'),
    captureSource:mark('CAPTURE',()=>{for(const id of C.writers)assert.ok(!active.has(id));return {componentProtection:true};}),
    validateProtection:mark('PROTECTION'),assertSourceFenceReturned:mark('FENCE_RETURN'),
    targetInventory:mark('TARGET_INVENTORY',()=>{const i=inventory();if(drift&&++targetReads===2)i.nativePrivileges.drift=true;return i;}),
    createEntry:mark('ENTRY',()=>({run:mark('CASE',request=>{for(const id of C.writers)assert.ok(!active.has(id));assert.ok(!active.has(C.source));assert.ok(active.has(C.target));return {outcome:request.operation==='CASE_EMPTY'?'CASE_EMPTY_REVERSE_COMPLETED':'CASE_HISTORY_REVERSE_REFUSED',vendorCode:request.operation==='CASE_EMPTY'?0:-20001};}),releaseAfterSourceReturn:mark('RETURN_PROOF',()=>{for(const id of [C.source,C.sourceTool,...C.writers])assert.ok(active.has(id));assert.ok(!active.has(C.target));assert.ok(!active.has(C.tool));if(returnFail)throw Error('COMPONENT_TLS_FAIL');return {sourceReturned:true};})})),
    returnWriter:mark('RETURN_WRITER',id=>{assert.ok(!active.has(C.target));active.add(id);}),returnRequest:mark('RETURN_REQUEST',()=>({component:true})),verifySourceReturn:mark('RETURN_WITHOUT_ENTRY',()=>{if(returnFail)throw Error('COMPONENT_INVENTORY_FAIL');for(const id of [C.source,C.sourceTool,...C.writers])assert.ok(active.has(id));}),
  };
  return {ports,calls,events,active,released:()=>controllerReleased,closed:()=>closed};
}
for(const operation of ['CASE_EMPTY','CASE_RECEIPT','CASE_EVENT'])test('continuous75 component complete fixed case '+operation,async()=>{
  const c=component(),pipeline=continuousCase75(c.ports,{operation,runId:RUN}),r=await pipeline.execute();assert.equal(r.sourceReturned,true);assert.equal(r.controllerReleased,true);assert.equal(r.automaticRetryAllowed,false);
  assert.equal(c.calls.filter(x=>x.kind==='CAPTURE').length,1);assert.equal(c.calls.filter(x=>x.kind==='CASE').length,1);assert.equal(c.closed(),2);
  const capture=c.calls.findIndex(x=>x.kind==='CAPTURE'),finish=c.calls.findIndex(x=>x.kind==='RETURN_PROOF');assert.ok(capture<finish);assert.ok(!c.calls.slice(capture,finish).some(x=>x.kind==='START'&&C.writers.includes(x.args[0])));
  assert.ok(c.calls.findIndex(x=>x.kind==='RETURN_REQUEST')<finish);
  assert.equal(c.events.filter(x=>x.kind==='PREPARED'&&x.step==='FIXED_CASE').length,1);await assert.rejects(pipeline.execute(),/ALREADY_USED/);
});
for(const fail of ['ADMIT','ACQUIRE','SOURCE_INVENTORY','PROBE','CAPTURE','PROTECTION','FENCE_RETURN','START','TARGET_INVENTORY','ENTRY','CASE'])test('continuous75 component failure preserves scene '+fail,async()=>{
  const c=component({fail}),pipeline=continuousCase75(c.ports,{runId:RUN});await assert.rejects(pipeline.execute());assert.equal(c.released(),false);assert.ok(!c.calls.filter(x=>x.kind===fail).slice(1).length||fail==='START');
  if(['ADMIT','ACQUIRE'].includes(fail))assert.equal(c.calls.filter(x=>x.kind==='STOP').length,0);
  if(fail==='CASE'){assert.ok(!c.active.has(C.target));assert.ok(c.active.has(C.source));assert.equal(c.calls.filter(x=>x.kind==='CASE').length,1);}
});
for(const fsync of ['STOP_WRITER_'+C.writers[1],'FRESH_SOURCE_CAPTURE','FIXED_CASE','RELEASE_CONTROLLER'])test('continuous75 PREPARED fsync cannot enable action '+fsync,async()=>{
  const c=component({fsync});await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute());const key=fsync==='FRESH_SOURCE_CAPTURE'?'CAPTURE':fsync==='FIXED_CASE'?'CASE':fsync==='RELEASE_CONTROLLER'?'RELEASE':'STOP';assert.equal(c.calls.filter(x=>x.kind===key).length,0);assert.equal(c.released(),false);
});
test('continuous75 old-six drift still stops target and returns source but keeps controller',async()=>{
  const c=component({drift:true});await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),/IMPORTED_OLD_SIX_CHANGED/);assert.ok(!c.active.has(C.target));assert.ok(c.active.has(C.source));assert.equal(c.released(),false);
});
test('continuous75 unknown target stop is not resent and cannot start source',async()=>{
  const c=component({stopUnknown:true});await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute());assert.equal(c.calls.filter(x=>x.kind==='STOP'&&x.args[0]===C.target).length,1);assert.ok(!c.active.has(C.source));assert.equal(c.released(),false);
});
test('continuous75 failed actual return proof keeps controller',async()=>{
  const c=component({returnFail:true});await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute());assert.equal(c.released(),false);assert.ok(c.events.some(e=>e.kind==='RETURN_UNKNOWN'));
});
test('continuous75 concurrent and repeated call cannot create two controllers',async()=>{
  const c=component(),p=continuousCase75(c.ports,{runId:RUN}),first=p.execute();await assert.rejects(p.execute(),/ALREADY_USED/);await first;assert.equal(c.calls.filter(x=>x.kind==='ACQUIRE').length,1);
});
test('continuous75 unknown case and malformed run refuse before ports',()=>{
  assert.throws(()=>fixedCase75('PREPARE'),/CASE_REFUSED/);assert.throws(()=>continuousCase75({}, {operation:'DROP_SCHEMA'}),/CASE_REFUSED/);assert.throws(()=>continuousCase75({}, {runId:'-'.repeat(36)}),/RUN_ID/);
});
test('continuous75 start requires sixty-minute protection and return reserve',async()=>{
  const c=component();await assert.rejects(continuousCase75(c.ports,{runId:RUN,now:()=>new Date('2026-10-13T09:01:00Z')}).execute(),/START_RESERVE/);assert.equal(c.calls.length,0);
});
test('continuous75 exact old-six evidence rejects incomplete data',()=>{const i=inventory();i.schemas.pop();assert.throws(()=>oldSixDigest75(i));});
test('95 old-six directory row order is not a metadata change and raw stays untouched',()=>{
 const a=inventory();a.restoreCatalog.categories={nativeObjects:{columns:['ID','NAME'],rows:[{ID:'2',NAME:'B'},{ID:'1',NAME:'A'},{ID:'1',NAME:'A'}]}};
 const raw=JSON.stringify(a),b=structuredClone(a);b.restoreCatalog.categories.nativeObjects.rows.reverse();assert.equal(oldSixDigest75(a),oldSixDigest75(b));assert.equal(JSON.stringify(a),raw);
});
for(const [name,mutate] of [
 ['value',i=>i.restoreCatalog.categories.nativeObjects.rows[0].NAME='CHANGED'],
 ['missing row',i=>i.restoreCatalog.categories.nativeObjects.rows.pop()],
 ['extra duplicate',i=>i.restoreCatalog.categories.nativeObjects.rows.push({...i.restoreCatalog.categories.nativeObjects.rows[0]})],
 ['column',i=>i.restoreCatalog.categories.nativeObjects.columns.push('EXTRA')],
 ['category',i=>i.restoreCatalog.categories.other={columns:[],rows:[]}],
 ['row attribute',i=>i.restoreCatalog.categories.nativeObjects.rows[0].EXTRA='UNKNOWN'],
])test('95 real old-six directory '+name+' remains drift',()=>{
 const a=inventory();a.restoreCatalog.categories={nativeObjects:{columns:['ID','NAME'],rows:[{ID:'1',NAME:'A'},{ID:'2',NAME:'B'}]}};const b=structuredClone(a);mutate(b);assert.notEqual(oldSixDigest75(a),oldSixDigest75(b));
});
const manifestDigest='5ef7e74521c0f76494c61edd554dd23b89dfb478d4466cb1f8fbc35f280d6e4c';
const manifestTask=(status='已完成',target='evidence/activation-recovery-design-20261010/continuous-fence75/'+C.manifest,digest=manifestDigest)=>({状态:status,证据:`[固定manifest](${target}) SHA=${digest}`});
test('continuous75 accepts the exact docs-relative manifest evidence link',()=>assertCurrentManifestTask75(manifestTask(),manifestDigest));
test('continuous75 rejects a workspace-relative or suffix-spoofed manifest link',()=>{
  assert.throws(()=>assertCurrentManifestTask75(manifestTask('已完成','docs/evidence/activation-recovery-design-20261010/continuous-fence75/'+C.manifest),manifestDigest),/CURRENT_SOURCE_MANIFEST_GATE/);
  assert.throws(()=>assertCurrentManifestTask75(manifestTask('已完成','not-evidence/activation-recovery-design-20261010/continuous-fence75/'+C.manifest),manifestDigest),/CURRENT_SOURCE_MANIFEST_GATE/);
});
test('continuous75 manifest gate preserves exact status and digest checks',()=>{
  assert.throws(()=>assertCurrentManifestTask75(manifestTask('进行中'),manifestDigest),/CURRENT_SOURCE_MANIFEST_GATE/);
  assert.throws(()=>assertCurrentManifestTask75(manifestTask('已完成',undefined,'a'.repeat(64)),manifestDigest),/CURRENT_SOURCE_MANIFEST_GATE/);
});
const worker={pid:33744,parentId:37004,createdAt:'2026-10-09T04:22:49.7418980Z',commandSha256:'a'.repeat(64)};
const responsibility={format:'ACTR66_HOST_WRITER_RESPONSIBILITY/v1',owner:'Codex',records:[{...worker,role:'NON_DB_PROCESS'}]};
test('continuous75 unreadable current host has fixed refusal diagnostics',()=>{
  let error;try{assertHostResponsibility75({selfPid:1,hostProcesses:[{...worker,name:'node',commandSha256:null,commandLine:'secret --token exposed',environment:{PASSWORD:'secret'}}]},responsibility);}catch(e){error=e;}
  assert.match(error.message,/UNREADABLE_HOST_33744/);
  assert.deepEqual(hostResponsibilityDiagnostic75(error),{format:'ACTR84_HOST_RESPONSIBILITY_DIAGNOSTIC/v1',reason:'UNREADABLE',observed:{pid:33744,parentId:37004,createdAt:worker.createdAt,name:'node',commandSha256:null},declared:{...worker,name:null,role:'NON_DB_PROCESS'}});
  const serialized=JSON.stringify(continuousFailureRecord75(error));assert.doesNotMatch(serialized,/secret|token|PASSWORD|commandLine/);assert.match(serialized,/hostDiagnostic/);assert.equal(continuousFailureRecord75(error).automaticRetryAllowed,false);
});
test('continuous75 unreadable pre-responsibility check uses the same trusted diagnostic',()=>{
  let error;try{assertReadableHostProcesses75({hostProcesses:[{...worker,name:'pwsh',commandSha256:null}]});}catch(e){error=e;}
  assert.equal(hostResponsibilityDiagnostic75(error).reason,'UNREADABLE');assert.equal(hostResponsibilityDiagnostic75(error).declared,null);
});
test('continuous75 unregistered host records the observed tuple without a declaration',()=>{
  let error;try{assertHostResponsibility75({selfPid:1,hostProcesses:[{...worker,name:'node'}]},{...responsibility,records:[]});}catch(e){error=e;}
  const diagnostic=hostResponsibilityDiagnostic75(error);assert.match(error.message,/UNREGISTERED_HOST_33744/);assert.equal(diagnostic.reason,'UNREGISTERED');assert.equal(diagnostic.observed.name,'node');assert.equal(diagnostic.declared,null);
});
test('continuous75 same-pid declared identity drift is distinguished',()=>{
  const observed={...worker,name:'java',commandSha256:'b'.repeat(64)};let error;try{assertHostResponsibility75({selfPid:1,hostProcesses:[observed]},responsibility);}catch(e){error=e;}
  const diagnostic=hostResponsibilityDiagnostic75(error);assert.match(error.message,/UNREGISTERED_HOST_33744/);assert.equal(diagnostic.reason,'DECLARED_IDENTITY_MISMATCH');assert.equal(diagnostic.observed.commandSha256,'b'.repeat(64));assert.equal(diagnostic.declared.commandSha256,'a'.repeat(64));
});
test('continuous75 arbitrary errors cannot forge host diagnostics',()=>assert.equal(hostResponsibilityDiagnostic75(Object.assign(Error('CONTINUOUS_UNREGISTERED_HOST_33744'),{hostDiagnostic:{reason:'UNREGISTERED'}})),null));
for(const property of ['pid','parentId','createdAt','commandSha256'])test('continuous75 host responsibility cannot waive drift '+property,()=>{
  const w={...worker,[property]:property==='createdAt'?'2026-10-10T00:00:00Z':property==='commandSha256'?'b'.repeat(64):2};assert.throws(()=>assertHostResponsibility75({selfPid:1,hostProcesses:[w]},responsibility));
});
test('continuous75 exact evidenced host is accepted',()=>assertHostResponsibility75({selfPid:1,hostProcesses:[worker]},responsibility));
test('continuous75 missing source records cannot approve arbitrary current resources',()=>assert.throws(()=>assertOriginalResources75({containers:[]},{records:[]})));
const resourceRecord=(id,overrides={})=>({id,name:'/resource-'+id,image:'sha256:'+id.padEnd(64,'0'),labels:{task:'fixed'},user:'1000:1000',command:['run'],entrypoint:['sh'],memoryBytes:1024,nanoCpus:1000,readonlyRootfs:true,restartPolicy:{Name:'no',MaximumRetryCount:0},ports:{},mounts:[{Type:'bind',Source:'C:/fixed',Destination:'/fixed',Mode:'',RW:false,Propagation:'rprivate'}],networks:{fixed:{NetworkID:'a'.repeat(64)}},...overrides});
const resourceContainer=r=>({Id:r.id,Name:r.name,Image:r.image,Config:{Labels:r.labels,User:r.user,Cmd:r.command,Entrypoint:r.entrypoint},HostConfig:{Memory:r.memoryBytes,NanoCpus:r.nanoCpus,ReadonlyRootfs:r.readonlyRootfs,RestartPolicy:r.restartPolicy,PortBindings:r.ports},Mounts:r.mounts,NetworkSettings:{Networks:r.networks}});
const approvedNine=()=>Array.from({length:9},(_,i)=>resourceRecord(String(i+1)));
const approvedBaseline=records=>({format:'ACTR75_APPROVED_CURRENT_RESOURCE_BASELINE/v1',records});
test('continuous75 exact nine approved resource records pass',()=>{const records=approvedNine();assertOriginalResources75({containers:records.map(resourceContainer)},approvedBaseline(records));});
test('continuous75 semantically identical mount ordering is accepted',()=>{const records=approvedNine();records[8].mounts=[...records[8].mounts,{Type:'volume',Source:'/volume',Destination:'/data',Mode:'z',RW:true,Propagation:''}];const containers=records.map(resourceContainer);containers[8].Mounts=[...containers[8].Mounts].reverse();assertOriginalResources75({containers},approvedBaseline(records));});
test('continuous75 missing target or tool record is refused',()=>{const records=approvedNine();assert.throws(()=>assertOriginalResources75({containers:records.slice(0,8).map(resourceContainer)},approvedBaseline(records.slice(0,8))),/APPROVED_RESOURCE_RECORDS_REQUIRED/);});
for(const [field,value,code] of [['image','sha256:'+'f'.repeat(64),'IDENTITY_CHANGED'],['mounts',[],'CONFIGURATION_CHANGED'],['memoryBytes',2048,'CONFIGURATION_CHANGED'],['networks',{fixed:{NetworkID:'b'.repeat(64)}},'NETWORK_CHANGED']])test('continuous75 approved resource drift is refused for '+field,()=>{
  const records=approvedNine(),containers=records.map(resourceContainer);const changed={...records[8],[field]:value};containers[8]=resourceContainer(changed);assert.throws(()=>assertOriginalResources75({containers},approvedBaseline(records)),new RegExp(code));
});
test('continuous75 default CLI refuses unknown operations before Docker, files or secret use',()=>{
  for(const args of [['unknown'],['prepare','DROP'],['execute','outside/root'],['prepare','CASE_EMPTY','extra']]){
    assert.throws(()=>execFileSync(process.execPath,['scripts/execute-consultation-activation-recovery-continuous.mjs',...args],{cwd:process.cwd(),encoding:'utf8',stdio:'pipe'}),e=>{assert.match(e.stderr,/CONTINUOUS_/);assert.doesNotMatch(e.stderr,/password|secret value/i);return true;});
  }
});

for(const failedIndex of [0,2,4])test('independent writer return preserves errors and still returns other roles '+failedIndex,async()=>{
 const c=component(),original=c.ports.returnWriter;c.ports.returnWriter=async id=>{if(id===C.writers[failedIndex])throw Error('COMPONENT_ROLE_FAIL');return original(id);};
 await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),e=>{assert.equal(e.sourceReturned,false);assert.equal(e.returnErrors.length,1);return true;});
 for(const id of C.writers)assert.equal(c.active.has(id),id!==C.writers[failedIndex]);assert.equal(c.released(),false);
 assert.equal(c.calls.filter(x=>x.kind==='RETURN_PROOF').length,0);assert.equal(c.calls.filter(x=>x.kind==='RETURN_SAFE').length,5);
 assert.deepEqual(c.events.find(e=>e.kind==='RESULT').returnErrors,[{id:C.writers[failedIndex],code:'COMPONENT_ROLE_FAIL'}]);
});
test('multiple role errors are retained separately without repeating failed starts',async()=>{
 const c=component(),attempts=[];c.ports.returnWriter=async id=>{attempts.push(id);if([C.writers[0],C.writers[3]].includes(id))throw Error('COMPONENT_ROLE_FAIL');c.active.add(id);};
 await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),e=>e.returnErrors.length===2);
 assert.deepEqual(attempts,C.writers);assert.equal(c.released(),false);
});
test('unsafe global return guard forbids all writer side effects',async()=>{
 const c=component();c.ports.assertWriterReturnSafe=async()=>{throw Error('COMPONENT_GLOBAL_UNSAFE');};
 await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),/GLOBAL_UNSAFE/);assert.equal(c.calls.filter(x=>x.kind==='RETURN_WRITER').length,0);assert.equal(c.released(),false);
});
test('global safety becoming unknown after one role stops remaining return actions',async()=>{
 const c=component();let guards=0;c.ports.assertWriterReturnSafe=async()=>{if(++guards===2)throw Error('COMPONENT_GLOBAL_UNSAFE');};
 await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),/GLOBAL_UNSAFE/);assert.deepEqual(c.calls.filter(x=>x.kind==='RETURN_WRITER').map(x=>x.args[0]),[C.writers[0]]);
});
test('return PREPARED fsync failure forbids that role and all remaining actions',async()=>{
 const c=component({fsync:'RETURN_WRITER_'+C.writers[0]});await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),/FSYNC_FAIL/);assert.equal(c.calls.filter(x=>x.kind==='RETURN_WRITER').length,0);assert.equal(c.released(),false);
});
test('return COMPLETED fsync failure is global rather than a recoverable role error',async()=>{
 const c=component(),acquire=c.ports.acquireController;c.ports.acquireController=async()=>{const result=await acquire(),append=result.journal.appendAndSync;result.journal.appendAndSync=async e=>{if(e.kind==='COMPLETED'&&e.step==='RETURN_WRITER_'+C.writers[0])throw Error('COMPONENT_COMPLETION_FSYNC_FAIL');await append(e);};return result;};
 await assert.rejects(continuousCase75(c.ports,{runId:RUN}).execute(),/COMPLETION_FSYNC_FAIL/);assert.equal(c.calls.filter(x=>x.kind==='RETURN_WRITER').length,1);assert.equal(c.released(),false);
});
const bridgeFixture=(id=C.writers[1],ip='172.17.0.3')=>({Id:DEFAULT_BRIDGE78,Name:'bridge',Driver:'bridge',Scope:'local',Internal:false,EnableIPv6:false,IPAM:{Driver:'default',Config:[{Subnet:'172.17.0.0/16',Gateway:'172.17.0.1'}]},Containers:{[id]:{IPv4Address:ip+'/16',MacAddress:'02:42:ac:11:00:03'}}});
const writerNetwork=(ip='172.17.0.3')=>({project:{NetworkID:'a'.repeat(64),IPAddress:'172.25.0.5',Gateway:'172.25.0.1',Aliases:['fixed'],IPAMConfig:{IPv4Address:'172.25.0.5'}},bridge:{NetworkID:DEFAULT_BRIDGE78,IPAddress:ip,Gateway:'172.17.0.1',IPPrefixLen:16,IPAMConfig:null,GlobalIPv6Address:'',IPv6Gateway:'',MacAddress:'02:42:ac:11:00:03'}});
const countedBridge85=()=>({...bridgeFixture(),Status:{IPAM:{Subnets:{'172.17.0.0/16':{IPsInUse:4,DynamicIPsAvailable:65532}}}}});
test('default bridge stop/start allocation counters change without configuration drift',()=>{
 const before=countedBridge85(),stopped=structuredClone(before);
 stopped.Containers={};stopped.Status.IPAM.Subnets['172.17.0.0/16']={IPsInUse:3,DynamicIPsAvailable:65533};
 assertBridge78(stopped,before);assertBridge78(before,stopped);
});
for(const [name,change]of [
 ['unexplained allocation',b=>b.Status.IPAM.Subnets['172.17.0.0/16']={IPsInUse:5,DynamicIPsAvailable:65531}],
 ['capacity mismatch',b=>b.Status.IPAM.Subnets['172.17.0.0/16'].DynamicIPsAvailable=65531],
 ['fractional allocation',b=>b.Status.IPAM.Subnets['172.17.0.0/16'].IPsInUse=4.5],
 ['unknown status field',b=>b.Status.Unknown=true],
 ['unknown counter field',b=>b.Status.IPAM.Subnets['172.17.0.0/16'].Unknown=1],
 ['unknown subnet',b=>b.Status.IPAM.Subnets['172.18.0.0/16']={IPsInUse:0,DynamicIPsAvailable:65536}],
 ['invalid endpoint address',b=>b.Containers[C.writers[1]].IPv4Address='172.18.0.3/16'],
 ['gateway endpoint',b=>b.Containers[C.writers[1]].IPv4Address='172.17.0.1/16'],
 ['configuration drift with valid counters',b=>b.Options={changed:'true'}],
 ['status disappears',b=>delete b.Status]
])test('default bridge counter normalization rejects '+name,()=>{
 const before=countedBridge85(),current=structuredClone(before);change(current);assert.throws(()=>assertBridge78(current,before));
});
for(const id of [C.writers[1],C.writers[3]])test('only exact existing publisher bridge address may be dynamically returned '+id,()=>{
 const before=writerNetwork(),after=writerNetwork('172.17.0.2'),bridge=bridgeFixture(id,'172.17.0.2');assertBridge78(bridge,bridgeFixture(id));
 assert.deepEqual(assertWriterNetworks78(id,after,before,bridge),[{id,networkId:DEFAULT_BRIDGE78,previousAddress:'172.17.0.3',currentAddress:'172.17.0.2'}]);
});
for(const [name,change]of [
 ['project IP',n=>n.project.IPAddress='172.25.0.9'],['alias',n=>n.project.Aliases=['different']],['gateway',n=>n.bridge.Gateway='172.17.0.9'],['network ID',n=>n.bridge.NetworkID='b'.repeat(64)],['missing network',n=>delete n.project],['added network',n=>n.foreign={}],['IPAM',n=>n.bridge.IPAMConfig={IPv4Address:'172.17.0.3'}],['other subnet',n=>n.bridge.IPAddress='172.18.0.2'],['network address',n=>n.bridge.IPAddress='172.17.0.0'],['broadcast',n=>n.bridge.IPAddress='172.17.255.255'],['gateway address',n=>n.bridge.IPAddress='172.17.0.1'],['invalid IPv4',n=>n.bridge.IPAddress='172.17.0.999'],['multicast MAC',n=>n.bridge.MacAddress='01:42:ac:11:00:03']
])test('writer network return refuses '+name,()=>{const before=writerNetwork(),after=structuredClone(before);change(after);assert.throws(()=>assertWriterNetworks78(C.writers[1],after,before,bridgeFixture()));});
test('ordinary application bridge drift and wrong observed endpoint are refused',()=>{
 assert.throws(()=>assertWriterNetworks78(C.writers[0],writerNetwork('172.17.0.2'),writerNetwork(),bridgeFixture()),/NETWORK_NOT_RETURNED/);
 assert.throws(()=>assertWriterNetworks78(C.writers[1],writerNetwork('172.17.0.2'),writerNetwork(),bridgeFixture()),/ENDPOINT_CHANGED/);
});
for(const [name,change]of [['driver',b=>b.Driver='overlay'],['ID',b=>b.Id='b'.repeat(64)],['IPv6',b=>b.EnableIPv6=true],['subnet',b=>b.IPAM.Config[0].Subnet='172.18.0.0/16'],['gateway',b=>b.IPAM.Config[0].Gateway='172.17.0.9'],['options',b=>b.Options={changed:'true'}]])test('default bridge configuration refuses '+name,()=>{const old=bridgeFixture(),current=structuredClone(old);change(current);assert.throws(()=>assertBridge78(current,old),/BRIDGE_CONTRACT_CHANGED/);});
test('default bridge duplicate allocations are refused',()=>{const bridge=bridgeFixture();bridge.Containers.other={...bridge.Containers[C.writers[1]]};assert.throws(()=>assertBridge78(bridge),/DUPLICATE_ADDRESS/);});
test('ordinary JSON cannot impersonate a trusted native fence worker',()=>{assert.throws(()=>assertHostResponsibility75({selfPid:1,hostProcesses:[]},responsibility,{pid:1}),/TRUSTED_NATIVE/);});
const repairTasks=()=>['FIX-CON-ACTR78-WORKER-RETURN-01','REC-CON-ACTR78-FAILED-CONTROLLER-01'].map(taskId=>({taskId,状态:'已完成'}));
test('physical execution requires both exact repair and failed-controller reconciliation task exits',()=>assertRepairTasks78(repairTasks()));
for(const state of ['进行中','阻塞','未开始'])test('physical execution rejects unresolved reconciliation '+state,()=>{const tasks=repairTasks();tasks[1]['状态']=state;assert.throws(()=>assertRepairTasks78(tasks),/REPAIR_AND_RECONCILIATION_REQUIRED/);});
test('physical execution rejects missing, duplicate or similarly named repair rows',()=>{
 assert.throws(()=>assertRepairTasks78(repairTasks().slice(0,1)),/REPAIR_AND_RECONCILIATION_REQUIRED/);
 const tasks=repairTasks();assert.throws(()=>assertRepairTasks78([...tasks,tasks[0]]),/REPAIR_AND_RECONCILIATION_REQUIRED/);tasks[0].taskId+='-FAKE';assert.throws(()=>assertRepairTasks78(tasks),/REPAIR_AND_RECONCILIATION_REQUIRED/);
});
