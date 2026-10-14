import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {CONTINUOUS75 as C,DEFAULT_BRIDGE78} from './consultation-activation-recovery-continuous-fence.mjs';
import {RECONCILIATION81 as R,PREVIOUS_RECONCILIATION81 as P,assertPreviousExclusive81,archiveOwnedExclusive81,lockIdentity81,PUBLIC_CA81,assertPublicCaReference81,assertFailedMarker81,assertReconciliationTasks81,assertReconciliationBundle81,assertReconciliationFacts81,completeSourceDigest81,assertSourceEquals81,assertReconciliationTls81,checkedReconciliationPath81,readReconciliationFile81,writeReconciliationFile81,createFailedControllerReconciliation81,reconciliationCode81} from './consultation-failed-controller-reconciliation.mjs';
import {hash,canonical} from './single-database-lifecycle-runtime.mjs';

const tls=()=>({format:'ACTR66_CURRENT_PUBLIC_TLS_GET/v1',requests:['/app/consultation','/.well-known/openid-configuration','/login'].map(p=>({path:p,status:200,serverSha256:'de4f652f8f99db36e7b7049fab104de0c0918a0e68dcc2dbd26fad6451374f06',bodySha256:'b'.repeat(64),bytes:20,clientCertificateCount:0}))});
const inventory=()=>({actualJdbc:true,complete:true,targetId:C.source,schemas:Array.from({length:6},(_,n)=>({schema:'UNIT_'+n,rows:[]})),accounts:Array.from({length:15},(_,n)=>({name:'UNIT_'+n})),restoreCatalog:{raw:{value:1}},nativePrivileges:{grantor:'EXACT_ORIGINAL'}});
const tasks=()=>['ARC-CON-ACTR77-FAILURE-RETURN-D-01','FIX-CON-ACTR78-WORKER-RETURN-01','REC-CON-ACTR77-SAFE-RETURN-01',R.task].map(taskId=>({taskId,'状态':taskId===R.task?'准备中':'已完成'}));
test('fixed marker accepts only original bytes and identity',()=>assertFailedMarker81(Buffer.from(R.marker),R.identity));
for(const name of Object.keys(R.identity))test('original identity drift refuses '+name,()=>assert.throws(()=>assertFailedMarker81(Buffer.from(R.marker),{...R.identity,[name]:'0'})));
for(const value of [R.marker+'\n',R.marker.replace('44916','44917'),'',R.marker+' '])test('marker altered refuses '+JSON.stringify(value.slice(-12)),()=>assert.throws(()=>assertFailedMarker81(Buffer.from(value),R.identity)));
test('dependencies and current task are required',()=>assertReconciliationTasks81(tasks()));
for(const id of tasks().map(t=>t.taskId))test('missing or duplicate task refuses '+id,()=>{
  assert.throws(()=>assertReconciliationTasks81(tasks().filter(t=>t.taskId!==id)));
  const t=tasks();t.push(t.find(x=>x.taskId===id));assert.throws(()=>assertReconciliationTasks81(t));
});
test('failed dependencies and completed reconciliation cannot be admitted',()=>{
  for(let n=0;n<4;n++){const t=tasks();t[n]['状态']=n===3?'已完成':'阻塞';assert.throws(()=>assertReconciliationTasks81(t));}
});
test('bundle admission requires exact plan-relative link and digest',()=>{
  const t=tasks(),digest='a'.repeat(64);t[3].evidence='[bundle]('+C.root.replace(/^docs\//,'')+'/'+R.bundle+') SHA='+digest;
  assertReconciliationBundle81(t,digest);assert.throws(()=>assertReconciliationBundle81(t,'c'.repeat(64)));
  t[3].evidence=t[3].evidence.replace('evidence/','docs/evidence/');assert.throws(()=>assertReconciliationBundle81(t,digest));
});
test('full source comparison covers catalog, raw grantors and all schema data',()=>{
  const b=inventory();assertSourceEquals81(structuredClone(b),b);
  for(const key of ['schemas','accounts','restoreCatalog','nativePrivileges']){const a=structuredClone(b);if(Array.isArray(a[key]))a[key][0].unexpected=true;else a[key].unexpected=true;assert.throws(()=>assertSourceEquals81(a,b));}
  const a=structuredClone(b);a.nativePrivileges.grantor='NORMALIZED';assert.throws(()=>assertSourceEquals81(a,b));
});
for(const [key,value] of [['actualJdbc',false],['complete',false],['targetId',C.target],['schemas',[]],['accounts',[]],['restoreCatalog',null],['nativePrivileges',null],['errors',['x']]])test('incomplete inventory refuses '+key,()=>{const a=inventory();a[key]=value;assert.throws(()=>completeSourceDigest81(a));});
test('strict TLS proof accepts fixed three public GETs',()=>assertReconciliationTls81(tls()));
for(const change of [p=>p.format='FAKE',p=>p.requests.pop(),p=>p.requests[0].path='/fake',p=>p.requests[0].status=302,p=>p.requests[0].clientCertificateCount=1,p=>p.requests[0].serverSha256='a'.repeat(64),p=>p.requests[0].bodySha256='',p=>p.requests[0].bytes=1048577,p=>p.requests[0].bytes=1.5])test('TLS false projection refuses '+change.toString(),()=>{const p=tls();change(p);assert.throws(()=>assertReconciliationTls81(p));});
function scenario({fail,drift,tlsBad}={}){
  const calls=[],b=inventory();let released=false,closed=false,cleared=false,retired=false;
  const fn=(name,value)=>async()=>{calls.push(name);if(fail===name)throw Error('UNIT_'+name+'_FAILED');return typeof value==='function'?value():value;};
  const ports={
    assertCurrent:async name=>fn(name)(),
    acquireExclusive:fn('ACQUIRE',{release:fn('RELEASE',()=>{released=true;}),close:fn('CLOSE',()=>{closed=true;})}),
    baseline:fn('BASELINE',b),inventory:async phase=>fn('READ_'+phase,()=>{const i=structuredClone(b);if(drift===phase)i.nativePrivileges.grantor='CHANGED';return i;})(),
    tls:fn('TLS',()=>tlsBad?{verified:true}:tls()),archive:fn('ARCHIVE',{unit:true}),retire:fn('RETIRE',()=>{retired=true;}),
    recordSuccess:fn('SUCCESS'),recordFailure:fn('FAILURE'),clearSecret:fn('CLEAR',()=>{cleared=true;}),
  };
  return {pipeline:createFailedControllerReconciliation81(ports),calls,state:()=>({released,closed,cleared,retired})};
}
test('read-only reconciliation follows full guards before exact retirement',async()=>{
  const s=scenario(),result=await s.pipeline.execute();assert.equal(result.originalCaseSucceeded,false);assert.equal(result.sqlWrites,0);
  assert.deepEqual(s.calls,['ADMIT','ACQUIRE','BEFORE_SOURCE_READ','BASELINE','READ_before','BEFORE_TLS','TLS','AFTER_TLS','READ_after','BEFORE_ARCHIVE','ARCHIVE','BEFORE_RETIRE','RETIRE','SUCCESS','RELEASE','CLEAR','CLOSE']);
  assert.deepEqual(s.state(),{released:true,closed:true,cleared:true,retired:true});await assert.rejects(s.pipeline.execute(),/ALREADY_USED/);
});
for(const fail of ['ADMIT','ACQUIRE','BEFORE_SOURCE_READ','BASELINE','READ_before','BEFORE_TLS','TLS','AFTER_TLS','READ_after','BEFORE_ARCHIVE','ARCHIVE','BEFORE_RETIRE','RETIRE','SUCCESS','RELEASE'])test('failure keeps original evidence and prevents retry '+fail,async()=>{
  const s=scenario({fail});await assert.rejects(s.pipeline.execute());assert.ok(s.state().cleared);assert.equal(s.state().released,false);
  if(!['SUCCESS','RELEASE'].includes(fail))assert.equal(s.state().retired,false);
  if(!['ADMIT','ACQUIRE'].includes(fail))assert.ok(s.state().closed);
  await assert.rejects(s.pipeline.execute(),/ALREADY_USED/);
});
for(const drift of ['before','after'])test('source drift refuses retirement '+drift,async()=>{const s=scenario({drift});await assert.rejects(s.pipeline.execute(),/INVENTORY_CHANGED/);assert.equal(s.state().retired,false);assert.equal(s.state().released,false);});
test('TLS failure cannot retire original lock',async()=>{const s=scenario({tlsBad:true});await assert.rejects(s.pipeline.execute(),/STRICT_TLS/);assert.equal(s.state().retired,false);});
test('concurrent calls cannot acquire two reconciliation locks',async()=>{
  const s=scenario(),first=s.pipeline.execute();await assert.rejects(s.pipeline.execute(),/ALREADY_USED/);await first;assert.equal(s.calls.filter(x=>x==='ACQUIRE').length,1);
});
function resources(){
  const ids=[C.source,C.sourceTool,...C.writers,C.target,C.tool];
  const records=ids.map((id,n)=>({id,name:'/UNIT_'+n,image:'sha256:unit',labels:{task:'UNIT'},user:'1000',command:['sleep'],entrypoint:null,memoryBytes:1024,nanoCpus:100,readonlyRootfs:true,restartPolicy:{Name:'no'},ports:{},mounts:[],networks:{unit:{NetworkID:'UNIT_NET',IPAddress:'10.0.0.'+(n+2)}}}));
  const containers=records.map((r,n)=>({Id:r.id,Name:r.name,Image:r.image,Config:{Labels:r.labels,User:r.user,Cmd:r.command,Entrypoint:r.entrypoint},HostConfig:{Memory:r.memoryBytes,NanoCpus:r.nanoCpus,ReadonlyRootfs:r.readonlyRootfs,RestartPolicy:r.restartPolicy,PortBindings:r.ports},Mounts:r.mounts,NetworkSettings:{Networks:r.networks},State:{Running:n<7,Restarting:false,Pid:n<7?n+1:0,StartedAt:R.sourceStartedAt}}));
  containers[0].State.Pid=R.sourcePid;
  const live=ids.slice(0,7),facts={containers,ownedRunning:live,network:{Containers:Object.fromEntries(live.map(id=>[id,{}]))},selfPid:1,hostProcesses:[]};
  const host={format:'ACTR66_HOST_WRITER_RESPONSIBILITY/v1',owner:'Codex',records:[]};
  const bridge={Id:DEFAULT_BRIDGE78,Name:'bridge',Driver:'bridge',Scope:'local',Internal:false,EnableIPv6:false,IPAM:{Driver:'default',Config:[{Subnet:'172.17.0.0/16',Gateway:'172.17.0.1'}]},Containers:{}};
  return {facts,prior:{format:'ACTR75_APPROVED_CURRENT_RESOURCE_BASELINE/v1',records},host,bridge};
}
test('fixed source-only resource facts are accepted',()=>{const s=resources();assertReconciliationFacts81(s.facts,s.prior,s.host,s.bridge,[]);});
for(const change of [s=>s.facts.containers[0].State.Pid++,s=>s.facts.containers[0].State.StartedAt='2026-10-11T00:00:00Z',s=>s.facts.containers[7].State.Running=true,s=>s.facts.containers[8].State.Pid=7,s=>s.facts.containers[1].State.Restarting=true,s=>s.facts.containers[2].State.Running=false,s=>s.facts.ownedRunning.push('UNKNOWN'),s=>s.facts.network.Containers.UNKNOWN={},s=>s.facts.hostProcesses.push({pid:44916,parentId:1,createdAt:R.sourceStartedAt,commandSha256:'a'.repeat(64)}),s=>s.bridge.Internal=true,s=>s.facts.containers[0].HostConfig.Memory=7516192769])test('unsafe source-only facts refuse '+change.toString(),()=>{const s=resources();change(s);assert.throws(()=>assertReconciliationFacts81(s.facts,s.prior,s.host,s.bridge,[]));});
test('live old owner refuses regardless of executable name',()=>{const s=resources();assert.throws(()=>assertReconciliationFacts81(s.facts,s.prior,s.host,s.bridge,[{ProcessId:41388}]),/OWNER_STILL_LIVE/);});
test('real filesystem exclusive outputs preserve original bytes and reject overwrite or wrong pin',async()=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'actr81-'));
  try{
    const bytes=Buffer.from(R.marker);await writeReconciliationFile81(root,'original.lock',bytes);
    assert.equal(hash(await readReconciliationFile81(root,'original.lock',R.markerSha256,79)),R.markerSha256);
    await assert.rejects(writeReconciliationFile81(root,'original.lock',Buffer.from('replace')),/EEXIST/);
    await assert.rejects(readReconciliationFile81(root,'original.lock','a'.repeat(64)),/PIN_CHANGED/);
    await assert.rejects(readReconciliationFile81(root,'original.lock',undefined,78),/BOUNDED_FILE/);
    assert.equal(canonical(await fs.readFile(path.join(root,'original.lock'))),canonical(bytes));
    for(const value of ['../original.lock','a/../original.lock','a\\b',path.resolve(root,'original.lock')])await assert.rejects(checkedReconciliationPath81(root,value),/PATH_REFUSED/);
  }finally{await fs.rm(root,{recursive:true,force:true});}
});
test('CLI unsupported modes refuse before observation or secrets',()=>{
  for(const mode of ['delete','execute arbitrary','prepare']){
    try{execFileSync(process.execPath,['scripts/execute-consultation-failed-controller-reconciliation.mjs',...mode.split(' ')],{encoding:'utf8',stdio:'pipe'});assert.fail('accepted invalid mode');}
    catch(e){assert.equal(e.status,1);assert.match(e.stderr,/FIXED_CLI_REQUIRED/);assert.doesNotMatch(e.stdout,/HOST_REVIEW/);}
  }
});
test('safe errors never echo secret or command text',()=>assert.equal(reconciliationCode81(Error('secret=TEST_SENTINEL')),'RECONCILIATION_UNKNOWN_FAILURE'));
const previousFailure=()=>({format:'ACTR81_RECONCILIATION_FAILURE/v1',runId:P.runId,code:'RECONCILIATION_PUBLIC_CA_REDIRECT',automaticRetryAllowed:false,originalCaseSucceeded:false});
test('previous mutex return admits only exact known failed bytes, identity and dead owner',()=>assertPreviousExclusive81(Buffer.from(P.marker),P.identity,previousFailure(),[]));
for(const key of Object.keys(P.identity))test('previous mutex identity cannot be normalized '+key,()=>assert.throws(()=>assertPreviousExclusive81(Buffer.from(P.marker),{...P.identity,[key]:'0'},previousFailure(),[])));
test('previous mutex rejects live owner, different failure and changed marker',()=>{
  assert.throws(()=>assertPreviousExclusive81(Buffer.from(P.marker),P.identity,previousFailure(),[{ProcessId:P.ownerPid}]));
  assert.throws(()=>assertPreviousExclusive81(Buffer.from(P.marker),P.identity,{...previousFailure(),code:'UNKNOWN'},[]));
  assert.throws(()=>assertPreviousExclusive81(Buffer.from(P.marker+' '),P.identity,previousFailure(),[]));
});
test('public CA accepts exact logical or approved Windows package path only',()=>{
  for(const p of [PUBLIC_CA81.logical,PUBLIC_CA81.physical])assertPublicCaReference81({format:'ACTR82_PUBLIC_CA_REFERENCE/v1',logical:PUBLIC_CA81.logical,path:p,sha256:PUBLIC_CA81.sha256});
  for(const p of ['C:/other/root-ca.cer',PUBLIC_CA81.physical.replace('OpenAI.Codex_2p2nqsd0c76g0','DifferentPackage'),PUBLIC_CA81.physical+'/extra'])assert.throws(()=>assertPublicCaReference81({format:'ACTR82_PUBLIC_CA_REFERENCE/v1',logical:PUBLIC_CA81.logical,path:p,sha256:PUBLIC_CA81.sha256}));
  assert.throws(()=>assertPublicCaReference81({format:'ACTR82_PUBLIC_CA_REFERENCE/v1',logical:PUBLIC_CA81.logical,path:PUBLIC_CA81.physical,sha256:'a'.repeat(64)}));
});
test('actual public CA helper resolves original physical reference without copying or secrets',()=>{
  if(process.platform!=='win32')return;
  const ref=JSON.parse(execFileSync('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-File','scripts/lib/consultation-failed-controller-public-ca.ps1'],{encoding:'utf8',windowsHide:true}));assertPublicCaReference81(ref);
});
test('actual owned mutex archive preserves bytes and physical identity',async()=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'actr82-')),relative=C.root+'/reconcile-'+P.runId,marker=Buffer.from(P.marker);
  try{
    await fs.mkdir(path.join(root,relative),{recursive:true});await writeReconciliationFile81(root,R.exclusive,marker);
    const original=lockIdentity81(await fs.lstat(path.join(root,R.exclusive),{bigint:true}));
    await archiveOwnedExclusive81(root,relative,marker,original);
    assert.equal(hash(await fs.readFile(path.join(root,relative,'original-reconciliation.lock'))),P.sha256);
    assert.equal(hash(await fs.readFile(path.join(root,relative,'retired-reconciliation.lock'))),P.sha256);
    assert.equal(String((await fs.lstat(path.join(root,relative,'retired-reconciliation.lock'),{bigint:true})).ino),original.ino);
    await assert.rejects(fs.lstat(path.join(root,R.exclusive)),/ENOENT/);
  }finally{await fs.rm(root,{recursive:true,force:true});}
});
for(const fail of ['identity','bytes','archiveExists','destinationExists'])test('mutex archive refuses '+fail+' without moving active mutex',async()=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'actr82-refuse-')),relative=C.root+'/reconcile-'+P.runId,marker=Buffer.from(P.marker);
  try{
    await fs.mkdir(path.join(root,relative),{recursive:true});await writeReconciliationFile81(root,R.exclusive,marker);
    const identity=lockIdentity81(await fs.lstat(path.join(root,R.exclusive),{bigint:true}));
    if(fail==='identity')identity.ino='0';
    if(fail==='archiveExists')await writeReconciliationFile81(root,relative+'/original-reconciliation.lock',marker);
    if(fail==='destinationExists')await writeReconciliationFile81(root,relative+'/retired-reconciliation.lock',marker);
    await assert.rejects(archiveOwnedExclusive81(root,relative,fail==='bytes'?Buffer.from('wrong'):marker,identity));
    assert.equal(hash(await fs.readFile(path.join(root,R.exclusive))),P.sha256);
  }finally{await fs.rm(root,{recursive:true,force:true});}
});
