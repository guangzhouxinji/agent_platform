import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {readProjectFacts76,EXTERNAL76 as E,partitionProjectResources76,projectHostScope92,IMPACT_SCRIPT92} from './consultation-project-resource-isolation.mjs';
import {resourceBinding75} from './consultation-activation-recovery-continuous-runtime.mjs';
import {assertHostResponsibility75} from './consultation-activation-recovery-continuous-fence.mjs';

const process92=(pid,parentId,name='node.exe',projectReferenced=false)=>({pid,parentId,name,projectReferenced,createdAt:'2026-10-12T00:00:00Z',commandSha256:'a'.repeat(64)});
const sample92=()=>({format:'ACTR92_HOST_IMPACT_OBSERVATION/v1',capturedAt:'2026-10-12T02:00:00Z',connectionsCapturedAt:'2026-10-12T01:59:59Z',processes:[process92(1,0,'Codex.exe'),process92(2,1,'pwsh.exe'),process92(3,2),process92(4,1,'powershell.exe')],connections:[]});
const scope92=()=>({roots:[2,3],ports:[5237,5446],databaseIps:['172.20.0.2']});
for(const scenario of ['EXIT','READABLE','LIVE'])test('93 actual PowerShell producer rechecks .exe candidate '+scenario,{skip:process.platform!=='win32'},()=>{
  const mock=String.raw`
function Get-NetTCPConnection { @() }
function Get-CimInstance {
  param([string]$ClassName,[string]$Filter)
  if($Filter){
    if($env:PROJECT93_TEST_SCENARIO -eq 'EXIT'){return}
    $line=if($env:PROJECT93_TEST_SCENARIO -eq 'READABLE'){'D:\daima\xinji\agent_platform\scripts\worker.mjs'}else{$null}
    [pscustomobject]@{ProcessId=4;ParentProcessId=1;Name='powershell.exe';CreationDate=[datetime]'2026-10-12T00:00:00Z';CommandLine=$line}
  }else{
    foreach($id in @(2,3,4)){
      [pscustomobject]@{ProcessId=$id;ParentProcessId=1;Name=if($id -eq 4){'powershell.exe'}else{'node.exe'};CreationDate=[datetime]'2026-10-12T00:00:00Z';CommandLine=if($id -eq 4){$null}else{'test-root'}}
    }
  }
}
`;
  const env=Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('SINGLE_DB_')));
  env.PROJECT93_TEST_SCENARIO=scenario;env.ACTR92_SCOPE_JSON=JSON.stringify({...scope92(),references:['d:/daima/xinji/agent_platform']});
  const r=spawnSync('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-Command',mock+IMPACT_SCRIPT92],{env,encoding:'utf8',windowsHide:true,timeout:15000});
  assert.equal(r.status,0,r.stderr);const sample=JSON.parse(r.stdout);assert.equal(sample.commandRechecks.length,1);
  if(scenario==='LIVE'){assert.throws(()=>projectHostScope92(sample,scope92()),/LIVE_HOST_COMMAND_UNREADABLE_4/);return;}
  const result=projectHostScope92(sample,scope92());
  if(scenario==='EXIT'){assert.equal(result.hostScopeObservation.exitedDuringCapture[0].pid,4);assert.ok(!result.hostProcesses.some(p=>p.pid===4));}
  else{assert.equal(sample.commandRechecks[0].process.projectReferenced,true);assert.ok(result.hostProcesses.some(p=>p.pid===4));}
});
function exiting93(){const s=sample92();s.processes[3].commandSha256=null;s.commandRechecks=[{pid:4,recheckedAt:'2026-10-12T01:59:59Z',process:null}];return s;}
test('93 a once-confirmed exited shell is recorded without a role',()=>{
  const r=projectHostScope92(exiting93(),scope92());assert.deepEqual(r.hostProcesses.map(p=>p.pid),[2,3]);assert.equal(r.hostScopeObservation.exitedDuringCapture[0].pid,4);assert.equal(r.hostScopeObservation.unrelatedProcessesGrantedRoles,false);
});
test('93 readable same-identity recheck retains actual project impact',()=>{
  const s=exiting93();s.commandRechecks[0].process=process92(4,1,'powershell.exe',true);
  assert.ok(projectHostScope92(s,scope92()).hostProcesses.some(p=>p.pid===4));
});
for(const [name,mutate,code] of [
  ['live unreadable',s=>s.commandRechecks[0].process={...s.processes[3]},/LIVE_HOST_COMMAND_UNREADABLE_4/],
  ['PID reuse',s=>s.commandRechecks[0].process={...process92(4,1,'powershell.exe'),createdAt:'2026-10-12T01:00:00Z'},/IDENTITY_CHANGED/],
  ['missing timestamp',s=>delete s.commandRechecks[0].recheckedAt,/EXACT_RECHECK/],
  ['future timestamp',s=>s.commandRechecks[0].recheckedAt='2026-10-12T02:00:01Z',/EXACT_RECHECK/],
  ['duplicate query',s=>s.commandRechecks.push({...s.commandRechecks[0]}),/EXACT_RECHECK/],
  ['unobserved PID',s=>s.commandRechecks[0].pid=99,/EXACT_RECHECK/],
  ['exited execution root',s=>{s.processes[1].commandSha256=null;s.commandRechecks=[{pid:2,recheckedAt:'2026-10-12T01:59:59Z',process:null}];},/ROOT_MISSING/],
  ['exited project socket owner',s=>s.connections.push({pid:4,remotePort:5237,remoteAddress:'127.0.0.1',state:'Established'}),/OWNER_UNKNOWN/],
])test('93 uncertain '+name+' refuses before granting responsibility',()=>{const s=exiting93();mutate(s);assert.throws(()=>projectHostScope92(s,scope92()),code);});
function responsibility92(rows){return {format:'ACTR66_HOST_WRITER_RESPONSIBILITY/v1',owner:'Codex',records:rows.map(p=>({...p,role:p.pid===2?'CONTROLLER_LAUNCHER':'NON_DB_PROCESS'}))};}
test('92 unrelated shell and tool PID changes do not require roles',()=>{
  const s=sample92(),r=projectHostScope92(s,scope92());assert.deepEqual(r.hostProcesses.map(p=>p.pid),[2,3]);assert.equal(r.hostScopeObservation.observedUnrelated.length,1);assert.equal(r.hostScopeObservation.unrelatedProcessesGrantedRoles,false);
  const recorded=responsibility92(r.hostProcesses);s.processes[3]=process92(90,1,'cmd.exe');const next=projectHostScope92(s,scope92());
  assertHostResponsibility75({selfPid:3,hostProcesses:next.hostProcesses},recorded);
});
for(const [name,mutate] of [
  ['project command',s=>s.processes[3].projectReferenced=true],['direct DB client',s=>s.processes[3].name='disql.exe'],
  ['execution child',s=>s.processes[3].parentId=3],
  ['published DB socket',s=>s.connections.push({pid:4,remotePort:5237,remoteAddress:'127.0.0.1',state:'Established'})],
  ['container DB socket',s=>s.connections.push({pid:4,remotePort:5236,remoteAddress:'172.20.0.2',state:'SynSent'})],
  ['application socket',s=>{s.processes[3].name='browser.exe';s.connections.push({pid:4,remotePort:5446,remoteAddress:'127.0.0.1',state:'Established'});}],
])test('92 affected unknown '+name+' still refuses strict responsibility',()=>{
  const s=sample92(),initial=projectHostScope92(s,scope92()),recorded=responsibility92(initial.hostProcesses);mutate(s);
  const current=projectHostScope92(s,scope92());assert.ok(current.hostProcesses.some(p=>p.pid===4));
  assert.throws(()=>assertHostResponsibility75({selfPid:3,hostProcesses:current.hostProcesses},recorded),/UNREGISTERED_HOST_4/);
});
test('92 incomplete unrelated ancestry is observed without claiming a role',()=>{
  const s=sample92();s.processes[3].parentId=999;
  const r=projectHostScope92(s,scope92());assert.ok(!r.hostProcesses.some(p=>p.pid===4));assert.equal(r.hostScopeObservation.observedUnrelated[0].impact.ancestryComplete,false);
  s.processes[3].projectReferenced=true;assert.ok(projectHostScope92(s,scope92()).hostProcesses.some(p=>p.pid===4));
});
for(const [name,mutate,code] of [
  ['unreadable',s=>s.processes[3].commandSha256=null,/COMMAND_UNREADABLE/],
  ['missing root',s=>s.processes=s.processes.filter(p=>p.pid!==2),/ROOT_MISSING/],
  ['duplicate PID',s=>s.processes.push({...s.processes[3]}),/IDENTITY_REQUIRED/],
  ['invalid identity',s=>s.processes[3].createdAt='unknown',/IDENTITY_REQUIRED/],
  ['parent cycle',s=>s.processes[3].parentId=4,/PARENT_CYCLE/],
  ['unknown TCP state',s=>s.connections.push({pid:4,remotePort:5237,remoteAddress:'127.0.0.1',state:'UNKNOWN'}),/TCP_OBSERVATION/],
  ['unknown connected owner',s=>s.connections.push({pid:99,remotePort:5237,remoteAddress:'127.0.0.1',state:'Established'}),/OWNER_UNKNOWN/],
  ['reused connection owner',s=>{s.processes[3].createdAt='2026-10-12T02:00:00Z';s.connections.push({pid:4,remotePort:5237,remoteAddress:'127.0.0.1',state:'Established'});},/OWNER_UNKNOWN/],
  ['missing TCP observation',s=>delete s.connections,/COMPLETE_HOST_OBSERVATION/],
])test('92 uncertain '+name+' is never classified unrelated',()=>{const s=sample92();mutate(s);assert.throws(()=>projectHostScope92(s,scope92()),code);});
test('92 unrelated remote DB, listening socket and timewait are observations',()=>{
  const s=sample92();s.connections=[{pid:4,remotePort:5236,remoteAddress:'172.99.0.2',state:'Established'},{pid:4,remotePort:5237,remoteAddress:'127.0.0.1',state:'Listen'},{pid:0,remotePort:5237,remoteAddress:'127.0.0.1',state:'TimeWait'}];
  assert.ok(!projectHostScope92(s,scope92()).hostProcesses.some(p=>p.pid===4));
});
test('92 project actor identity drift remains rejected',()=>{
  const s=sample92(),initial=projectHostScope92(s,scope92()),recorded=responsibility92(initial.hostProcesses);s.processes[1].commandSha256='b'.repeat(64);
  assert.throws(()=>assertHostResponsibility75({selfPid:3,hostProcesses:projectHostScope92(s,scope92()).hostProcesses},recorded),/UNREGISTERED_HOST_2/);
});

const facts=()=>({containers:Array.from({length:9},(_,i)=>({Id:'project-'+i})),ownedRunning:['project-0',E.id]});
const external=()=>({Id:E.id,Name:E.name,Image:E.image,Labels:{},NetworkMode:'bridge',Networks:{bridge:{NetworkID:E.network}},Ports:{'5236/tcp':[{HostIp:'',HostPort:'5236'}]},Mounts:[{Destination:'/opt/dmdbms/data',Mode:'',Propagation:'rprivate',RW:true,Source:'D:\\docker\\dm8\\entservice-data',Type:'bind'}]});
test('project76 exact other-project resource is observed but never owned',()=>{
  const f=facts(),c=external(),r=partitionProjectResources76(f,[c]);assert.deepEqual(r.ownedRunning,['project-0']);assert.deepEqual(r.allRunning,f.ownedRunning);assert.deepEqual(r.externalResources,[c]);assert.equal(f.ownedRunning.length,2);
});
test('project76 no external resource preserves exact project running IDs',()=>{const f=facts();f.ownedRunning.pop();assert.deepEqual(partitionProjectResources76(f,[]).ownedRunning,f.ownedRunning);});
for(const [name,mutate] of [
  ['ID',c=>c.Id='f'.repeat(64)],['name',c=>c.Name='/entservice-dm8-copy'],['image',c=>c.Image='sha256:'+'f'.repeat(64)],
  ['labels',c=>c.Labels={task:'IMP-SINGLE-DB-FINAL-BASELINE-01'}],['network',c=>c.Networks.project={NetworkID:'f'.repeat(64)}],
  ['network ID',c=>c.Networks.bridge.NetworkID='f'.repeat(64)],['mount',c=>c.Mounts[0].Source='D:\\daima\\xinji\\agent_platform'],
  ['port',c=>c.Ports['5236/tcp'][0].HostPort='5496'],
])test('project76 external drift rejects '+name,()=>{const c=external();mutate(c);assert.throws(()=>partitionProjectResources76(facts(),[c]),/PROJECT76_/);});
test('project76 arbitrary unknown resource cannot disappear from the observation',()=>{const f=facts();f.ownedRunning.push('unknown');assert.throws(()=>partitionProjectResources76(f,[external()]),/EXTERNAL_OBSERVATION_MISMATCH/);});
test('project76 duplicate or missing external inspect refuses',()=>{assert.throws(()=>partitionProjectResources76(facts(),[]));assert.throws(()=>partitionProjectResources76(facts(),[external(),external()]));const f=facts();f.ownedRunning.push(E.id);assert.throws(()=>partitionProjectResources76(f,[external()]),/DUPLICATE_RUNNING/);});
test('project76 persistent binding allows only mount ordering differences',()=>{
  const c={Id:'fixed',Config:{},HostConfig:{Mounts:[{Target:'/a'},{Target:'/b'}]},Mounts:[{Destination:'/a',Source:'/one'},{Destination:'/b',Source:'/two'}],NetworkSettings:{Networks:{fixed:{NetworkID:'fixed'}}}};
  const d=structuredClone(c);d.Mounts.reverse();d.HostConfig.Mounts.reverse();assert.deepEqual(resourceBinding75(c),resourceBinding75(d));d.Mounts[0].Source='/changed';assert.notDeepEqual(resourceBinding75(c),resourceBinding75(d));
});

test('97 project scope collects fresh impact every time and never falls back to stale global hosts',async()=>{
 const facts={containers:Array.from({length:9},(_,i)=>({Id:String(i)})),ownedRunning:[]};let raw=0,full=0,impact=0;
 const ports={readResources:async()=>{raw++;return facts;},readFull:async()=>{full++;return {...facts,hostProcesses:[{pid:99}]};},readImpact:async()=>{impact++;if(impact===2)throw Error('CURRENT_HOST_REFUSED');return {hostProcesses:[{pid:7}]};}};
 assert.deepEqual((await readProjectFacts76({projectImpactScope:true},ports)).hostProcesses,[{pid:7}]);
 await assert.rejects(readProjectFacts76({projectImpactScope:true},ports),/CURRENT_HOST_REFUSED/);assert.equal(raw,2);assert.equal(full,0);assert.equal(impact,2);
 assert.deepEqual((await readProjectFacts76({},ports)).hostProcesses,[{pid:99}]);assert.equal(full,1);
});
