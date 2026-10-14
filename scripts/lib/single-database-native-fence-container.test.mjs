import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {PassThrough,Writable} from 'node:stream';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {FENCE_SOURCE_SHA256,DOCKER_FENCE_TRANSPORT,validateContainerFenceConfiguration,containerFenceInvocation,prepareContainerFence,persistentFenceProtocol,openContainerFence,assertNativeFenceWorkerFacts} from './single-database-native-fence-container.mjs';
import {CONTINUOUS75,assertHostResponsibility75} from './consultation-activation-recovery-continuous-fence.mjs';

const target='a'.repeat(64),container='b'.repeat(64),network='c'.repeat(64),digest='d'.repeat(64),proof='e'.repeat(64);
function fixture(){
 const port={containerId:container,targetId:target,networkId:network,ownerTask:'IMP-SINGLE-DB-NATIVE-FENCE-01',java:'/opt/jdk/bin/java',classpath:'/app/classes:/app/dm.jar',classFile:'/app/classes/SingleDatabaseFence.class',classSha256:digest};
 const url='jdbc:dm://owned-dm:5236';
 const c={targetId:target,schemas:['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'],inventoryJdbc:{url},deployment:{database:{containerId:target,ownerTask:port.ownerTask,jdbcUrl:url}},approval:{javaFenceSha256:FENCE_SOURCE_SHA256,javaFenceClassSha256:digest},native:{fence:{transport:DOCKER_FENCE_TRANSPORT,sessionTimeoutSeconds:600,dockerFence:port,java:port.java,classpath:port.classpath,jdbcUrl:url,user:'ENGINEERING_FENCE',passwordEnv:'FENCE_PASSWORD'}}};
 const nets={owned:{NetworkID:network,Aliases:['owned-dm']}};
 const tool={Id:container,State:{Running:true},Config:{User:'10001:10001',Labels:{task:port.ownerTask,'agent-platform.target-id':target}},HostConfig:{Privileged:false,ReadonlyRootfs:true,Memory:536870912,NanoCpus:250000000},NetworkSettings:{Networks:structuredClone(nets)}};
 const db={Id:target,State:{Running:true},Config:{Labels:{task:port.ownerTask}},NetworkSettings:{Networks:structuredClone(nets)}};
 return {c,tool,db,classDigest:digest};
}
function fakeChild(reply){
 const child=new EventEmitter();child.stdout=new PassThrough();child.stderr=new PassThrough();child.kills=0;child.requests=[];
 child.kill=()=>{child.kills++;queueMicrotask(()=>{if(!child.closed){child.closed=true;child.emit('close',0,'SIGTERM');}});};
 child.stdin=new Writable({write(b,_encoding,done){const text=b.toString().trim();child.requests.push(text);done();queueMicrotask(()=>reply?.(text,child));}});
 return child;
}
function reader(f,calls=[]){return async(cmd,args)=>{calls.push([cmd,args]);return Buffer.from(args[0]==='inspect'?JSON.stringify([f.tool,f.db]):`${f.classDigest}  ${f.c.native.fence.dockerFence.classFile}\n`);};}
const invocation={command:'unit-protocol',args:[]},schemas=fixture().c.schemas;
const send=(p,state='HELD',sha=proof,count=36)=>p.stdout.write(`${state} ${sha} ${count}\n`);

test('original unmodified Java source SHA is fixed; no lock probe is run',async()=>{
 const source=await readFile(new URL('../java/SingleDatabaseFence.java',import.meta.url));
 assert.equal(createHash('sha256').update(source).digest('hex'),FENCE_SOURCE_SHA256);
 assert.match(source.toString(),/LOCK TABLE .* IN SHARE MODE/);assert.match(source.toString(),/c\.rollback\(\); send\("RELEASED"/);
});
test('explicit owned transport executes pinned class in exact container; environment passes names only',()=>{
 const f=fixture(),v=containerFenceInvocation(f.c,f);
 assert.equal(v.command,'docker');assert.ok(v.args.includes(container));assert.ok(v.args.includes('SingleDatabaseFence'));assert.ok(v.args.includes('600s'));
 assert.deepEqual(v.args.slice(2,8),['-e','LIFECYCLE_FENCE_URL','-e','LIFECYCLE_FENCE_USER','-e','LIFECYCLE_FENCE_PASSWORD']);
 assert.doesNotMatch(v.args.join(' '),/jdbc:|ENGINEERING_FENCE|unit-only-secret|\.java|127\.0\.0\.1/);
});
for(const [name,change,code] of [
 ['different transport',f=>f.c.native.fence.transport='AUTO','EXACT_OWNED_CONTAINER_FENCE'],
 ['wrong target',f=>f.c.native.fence.dockerFence.targetId='f'.repeat(64),'EXACT_OWNED_CONTAINER_FENCE'],
 ['wrong network',f=>f.tool.NetworkSettings.Networks.owned.NetworkID='f'.repeat(64),'EXACT_SHARED_JDBC_NETWORK'],
 ['wrong tool owner',f=>f.tool.Config.Labels.task='OTHER','EXACT_OWNED_JDBC_CONTAINER'],
 ['wrong target label',f=>f.tool.Config.Labels['agent-platform.target-id']='f'.repeat(64),'EXACT_OWNED_JDBC_CONTAINER'],
 ['wrong DB owner',f=>f.db.Config.Labels.task='OTHER','EXACT_OWNED_JDBC_CONTAINER'],
 ['tool stopped',f=>f.tool.State.Running=false,'EXACT_OWNED_JDBC_CONTAINER'],
 ['DB stopped',f=>f.db.State.Running=false,'EXACT_OWNED_JDBC_CONTAINER'],
 ['root',f=>f.tool.Config.User='0:0','BOUNDED_NONROOT'],
 ['writable rootfs',f=>f.tool.HostConfig.ReadonlyRootfs=false,'BOUNDED_NONROOT'],
 ['privileged',f=>f.tool.HostConfig.Privileged=true,'BOUNDED_NONROOT'],
 ['memory overflow',f=>f.tool.HostConfig.Memory++,'BOUNDED_NONROOT'],
 ['CPU overflow',f=>f.tool.HostConfig.NanoCpus++,'BOUNDED_NONROOT'],
 ['source changed',f=>f.c.approval.javaFenceSha256='f'.repeat(64),'PINNED_CONTAINER_FENCE_CODE'],
 ['class not approved',f=>f.c.approval.javaFenceClassSha256='f'.repeat(64),'PINNED_CONTAINER_FENCE_CODE'],
 ['executed class changed',f=>f.classDigest='f'.repeat(64),'CONTAINER_FENCE_CLASS_PIN'],
 ['wrong main class',f=>f.c.native.fence.dockerFence.classFile='/app/classes/Other.class','PINNED_CONTAINER_FENCE_CLASSPATH'],
 ['classpath shadow',f=>f.c.native.fence.dockerFence.classpath='/other:/app/classes:/app/dm.jar','PINNED_CONTAINER_FENCE_CLASSPATH'],
 ['classpath traversal',f=>f.c.native.fence.dockerFence.classpath='/app/classes:/app/../other.jar','PINNED_CONTAINER_FENCE_CLASSPATH'],
 ['host fallback URL',f=>{f.c.native.fence.jdbcUrl='jdbc:dm://127.0.0.1:5492';},'EXACT_CONTAINER_FENCE_JDBC'],
 ['unowned alias',f=>f.db.NetworkSettings.Networks.owned.Aliases=['other'],'EXACT_CONTAINER_FENCE_ALIAS'],
 ['alias only on other network',f=>{f.db.NetworkSettings.Networks.owned.Aliases=[];f.db.NetworkSettings.Networks.other={NetworkID:'f'.repeat(64),Aliases:['owned-dm']};},'EXACT_CONTAINER_FENCE_ALIAS']
])test(`metadata refuses ${name}`,()=>{const f=fixture();change(f);assert.throws(()=>containerFenceInvocation(f.c,f),new RegExp(code));});

test('invalid ownership stops before any digest exec',async()=>{const f=fixture(),calls=[];f.tool.Config.Labels.task='OTHER';await assert.rejects(prepareContainerFence(f.c,reader(f,calls)),/EXACT_OWNED/);assert.equal(calls.length,1);});
test('malformed inspect, digest and unavailable command fail closed without alternative URL',async()=>{
 for(const io of [async()=>Buffer.from('{}'),async(_cmd,args)=>Buffer.from(args[0]==='inspect'?JSON.stringify([fixture().tool,fixture().db]):'garbage'),async()=>{throw new Error('DNS failure secret');}])await assert.rejects(prepareContainerFence(fixture().c,io),/CONTAINER_FENCE/);
});
test('one process handles six-schema acquire, repeated CHECK and RELEASE with invariant table proof',async()=>{
 let spawns=0;const p=fakeChild((text,p)=>send(p,text==='RELEASE'?'RELEASED':'HELD'));
 const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>{spawns++;return p;}});
 assert.deepEqual(await lease.acquire(),{tableSetSha256:proof,tableCount:36});await lease.assertHeld();await lease.assertHeld();await lease.release();
 assert.equal(spawns,1);assert.deepEqual(p.requests,[schemas.join(','),'CHECK','CHECK','RELEASE']);await assert.rejects(lease.assertHeld(),/FENCE_LOST/);
});
test('fragmented stdout is supported',async()=>{const p=fakeChild((_text,p)=>{p.stdout.write('HE');queueMicrotask(()=>p.stdout.write(`LD ${proof} 36\n`));});const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await lease.acquire();lease.abort();});
for(const [label,line] of [['refusal','FENCE_REFUSED\n'],['wrong response',`RELEASED ${proof} 36\n`],['zero count',`HELD ${proof} 0\n`],['overflow count',`HELD ${proof} 4097\n`],['duplicate',`HELD ${proof} 36\nHELD ${proof} 36\n`],['partial unsolicited',`HELD ${proof} 36\nx`],['unbounded','x'.repeat(4097)]])test(`protocol closes on ${label}`,async()=>{
 const p=fakeChild((_text,p)=>p.stdout.write(line)),lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await assert.rejects(lease.acquire(),/FENCE_REFUSED/);assert.ok(p.kills);await assert.rejects(lease.assertHeld(),/FENCE_LOST/);
});
for(const changed of ['sha','count'])test(`CHECK refuses changed original ${changed}`,async()=>{
 const p=fakeChild((text,p)=>send(p,'HELD',text==='CHECK'&&changed==='sha'?'f'.repeat(64):proof,text==='CHECK'&&changed==='count'?37:36)),lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await lease.acquire();await assert.rejects(lease.assertHeld(),/FENCE_REFUSED/);assert.ok(p.kills);
});
for(const event of ['close','error','stdin-error'])test(`protocol fails closed on ${event}`,async()=>{
 const p=fakeChild((_text,p)=>{if(event==='stdin-error')p.stdin.emit('error',new Error('secret'));else p.emit(event,event==='error'?new Error('secret'):0);});
 const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await assert.rejects(lease.acquire(),/FENCE_LOST/);assert.ok(p.kills);
});
test('timeout kills pending connection and does not permit release success',async()=>{const p=fakeChild(),lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p,timeoutMs:10});await assert.rejects(lease.acquire(),/FENCE_LOST/);await assert.rejects(lease.release(),/FENCE_LOST/);assert.ok(p.kills);});
test('overlapping requests abort rather than replacing original pending decision',async()=>{const p=fakeChild(),lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});const first=lease.acquire();const second=lease.assertHeld();await Promise.all([assert.rejects(first,/FENCE_LOST/),assert.rejects(second,/FENCE_LOST/)]);});
test('aborted or closed held process cannot claim release',async()=>{const p=fakeChild((_text,p)=>send(p)),lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await lease.acquire();p.emit('close',1);await assert.rejects(lease.release(),/FENCE_LOST/);});
test('failed RELEASE acknowledgement is refused',async()=>{const p=fakeChild((text,p)=>send(p,text==='RELEASE'?'HELD':'HELD')),lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await lease.acquire();await assert.rejects(lease.release(),/FENCE_REFUSED/);});
test('container lease revalidates exact resources and executed class before CHECK, without new process',async()=>{
 const f=fixture(),calls=[];let spawns=0,options;
 const p=fakeChild((text,p)=>send(p,text==='RELEASE'?'RELEASED':'HELD'));
 const lease=await openContainerFence(f.c,reader(f,calls),{env:{FENCE_PASSWORD:'unit-only-secret'},spawnIO:(cmd,args,o)=>{spawns++;options=o;assert.equal(cmd,'docker');assert.doesNotMatch(args.join(' '),/unit-only-secret/);return p;}});
 await lease.acquire();await lease.assertHeld();assert.equal(spawns,1);assert.equal(calls.length,4);assert.equal(options.env.LIFECYCLE_FENCE_PASSWORD,'unit-only-secret');
 f.classDigest='f'.repeat(64);await assert.rejects(lease.assertHeld(),/CLASS_PIN_MISMATCH/);assert.deepEqual(p.requests,[schemas.join(','),'CHECK']);assert.ok(p.kills);await assert.rejects(lease.release(),/FENCE_LOST/);
});
test('revoked tool ownership aborts held protocol before CHECK',async()=>{
 const f=fixture(),p=fakeChild((_text,p)=>send(p));const lease=await openContainerFence(f.c,reader(f),{env:{FENCE_PASSWORD:'unit'},spawnIO:()=>p});await lease.acquire();f.tool.Config.Labels.task='OTHER';await assert.rejects(lease.assertHeld(),/EXACT_OWNED/);assert.ok(p.kills);assert.equal(p.requests.length,1);
});
test('missing secret blocks before metadata and process access',async()=>{let called=false;await assert.rejects(openContainerFence(fixture().c,async()=>{called=true;},{env:{},spawnIO:()=>{called=true;}}),/CURRENT_FENCE_SECRET_REQUIRED/);assert.equal(called,false);});

test('local owned protocol process uses a single persistent pipe (no Java, Docker or database)',async()=>{
 const code=`const r=require('node:readline').createInterface({input:process.stdin});r.on('line',s=>{console.log((s==='RELEASE'?'RELEASED':'HELD')+' ${proof} 36');if(s==='RELEASE')r.close();});`;
 const lease=persistentFenceProtocol({command:process.execPath,args:['-e',code]},schemas,{env:{}});
 try{await lease.acquire();await lease.assertHeld();await lease.assertHeld();await lease.release();}finally{lease.abort();}
});

for(const field of ['native','approval','deployment','schemas','accounts','inventoryJdbc','targetId'])test(`original decision pin rejects replacement of ${field}`,async()=>{
 const {pinNativeFenceDecision}=await import('./single-database-native-fence-container.mjs');const f=fixture(),check=pinNativeFenceDecision(f.c);check();f.c[field]=field==='targetId'?'f'.repeat(64):{replacement:true};assert.throws(check,/NATIVE_FENCE_ORIGINAL_DECISION_CHANGED/);
});
test('matching new class+approval cannot replace originally held decision',async()=>{
 const f=fixture(),p=fakeChild((_text,p)=>send(p));const lease=await openContainerFence(f.c,reader(f),{env:{FENCE_PASSWORD:'unit'},spawnIO:()=>p});await lease.acquire();f.c.native.fence.dockerFence.classSha256=f.c.approval.javaFenceClassSha256=f.classDigest='f'.repeat(64);await assert.rejects(lease.assertHeld(),/ORIGINAL_DECISION_CHANGED/);assert.equal(p.requests.length,1);assert.ok(p.kills);
});

for(const option of ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'])test(`container JVM injection ${option} is refused around pinned class`,()=>{
 const f=fixture();f.tool.Config.Env=[`${option}=-javaagent:/unapproved.jar`];assert.throws(()=>containerFenceInvocation(f.c,f),/JVM_OPTIONS_FORBIDDEN/);
});
for(const text of ['CHECK','RELEASE'])test(`${text} timeout closes an already held connection`,async()=>{
 const p=fakeChild((command,p)=>{if(command===schemas.join(','))send(p);});const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p,timeoutMs:10});await lease.acquire();await assert.rejects(text==='CHECK'?lease.assertHeld():lease.release(),/FENCE_LOST/);assert.ok(p.kills);
});
test('idle unsolicited reply loses held state',async()=>{const p=fakeChild((_text,p)=>send(p));const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await lease.acquire();send(p);await assert.rejects(lease.assertHeld(),/FENCE_LOST/);assert.ok(p.kills);});
test('spawn failure is sanitized and never retried with host Java or old URL',()=>{let calls=0;assert.throws(()=>persistentFenceProtocol(invocation,schemas,{spawnIO:()=>{calls++;throw new Error('unit secret DNS');}}),/ACTUAL_DATABASE_FENCE_LOST/);assert.equal(calls,1);});
test('container metadata failure never starts host or container Java',async()=>{let spawns=0;await assert.rejects(openContainerFence(fixture().c,async()=>{throw new Error('alias failed');},{env:{FENCE_PASSWORD:'unit'},spawnIO:()=>{spawns++;}}),/METADATA_UNAVAILABLE/);assert.equal(spawns,0);});

for(const stream of ['stdout','stderr'])test(`${stream} stream failure closes protocol without raw diagnostics`,async()=>{
 const p=fakeChild((_text,p)=>p[stream].emit('error',new Error('unit-secret')));const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await assert.rejects(lease.acquire(),/ACTUAL_DATABASE_FENCE_LOST/);assert.ok(p.kills);
});
test('stdout EOF while held refuses further CHECK even before child close',async()=>{
 const p=fakeChild((_text,p)=>send(p));const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p});await lease.acquire();p.stdout.emit('end');await assert.rejects(lease.assertHeld(),/FENCE_LOST/);assert.ok(p.kills);
});

for(const seconds of [undefined,0,29,3601,Infinity,'600'])test(`session timeout must be explicitly finite (${seconds})`,()=>{
 const f=fixture();f.c.native.fence.sessionTimeoutSeconds=seconds;assert.throws(()=>validateContainerFenceConfiguration(f.c),/FINITE_CONTAINER_FENCE_SESSION/);
});
test('explicit approved container lifetime is passed as bounded argv, not a hidden fixed snapshot cutoff',()=>{
 const f=fixture();f.c.native.fence.sessionTimeoutSeconds=900;assert.ok(containerFenceInvocation(f.c,f).args.includes('900s'));
});


test('actual Docker DNSNames with null Aliases binds only the exact approved network and JDBC alias',()=>{
 const f=fixture();f.db.NetworkSettings.Networks.owned.Aliases=null;
 f.db.NetworkSettings.Networks.owned.DNSNames=['owned-dm','aaaaaaaaaaaa'];
 assert.equal(containerFenceInvocation(f.c,f).command,'docker');
});
for(const [name,names] of [['null',null],['empty',[]],['string','owned-dm'],['wrong',['other-dm']],['substring',['owned-dm-extra']],['mixed',['owned-dm',123]],['blank',['owned-dm','']]])
 test(`authoritative DNSNames ${name} refuses despite matching legacy Aliases`,()=>{
  const f=fixture();f.db.NetworkSettings.Networks.owned.DNSNames=names;
  assert.throws(()=>containerFenceInvocation(f.c,f),/EXACT_CONTAINER_FENCE_ALIAS_REQUIRED/);
 });
test('matching DNSNames on a different network and global container Name do not authorize fence alias',()=>{
 const f=fixture();f.db.Name='/owned-dm';f.db.NetworkSettings.Networks.owned={NetworkID:network,Aliases:null,DNSNames:['other-dm']};
 f.db.NetworkSettings.Networks.foreign={NetworkID:'f'.repeat(64),DNSNames:['owned-dm']};
 assert.throws(()=>containerFenceInvocation(f.c,f),/EXACT_CONTAINER_FENCE_ALIAS_REQUIRED/);
});
test('old inspect without DNSNames retains exact Aliases binding',()=>{
 const f=fixture();assert.equal(Object.hasOwn(f.db.NetworkSettings.Networks.owned,'DNSNames'),false);
 assert.equal(containerFenceInvocation(f.c,f).command,'docker');
});

function ownedWorkerFixture(change=()=>{},toolId=container){
 const f=fixture();f.c.native.fence.dockerFence.containerId=f.tool.Id=toolId;
 const v=containerFenceInvocation(f.c,f),p=fakeChild((text,p)=>send(p,text==='RELEASE'?'RELEASED':'HELD'));
 p.pid=54321;p.exitCode=null;p.signalCode=null;
 let observation;
 const lease=persistentFenceProtocol(v,schemas,{spawnIO:()=>{observation={pid:p.pid,parentId:process.pid,createdAt:new Date().toISOString(),name:'docker.exe',executablePath:'D:\\tools\\docker.exe',commandSha256:createHash('sha256').update([v.command,...v.args].join(' ')).digest('hex')};return p;},observeWorker:async()=>{change(observation);return observation;}});
 return {lease,p,f,observation:()=>observation,facts:()=>({selfPid:process.pid,hostProcesses:[{...observation}]})};
}
test('native worker capability binds once to the actual spawned invocation and exact live tuple',async()=>{
 const f=ownedWorkerFixture();await f.lease.acquire();const proof=await f.lease.worker.bind();assert.equal(proof.pid,f.p.pid);assert.ok(Object.isFrozen(proof));
 assert.deepEqual(assertNativeFenceWorkerFacts(f.lease.worker,f.facts(),container),proof);
 await assert.rejects(f.lease.worker.bind(),/ALREADY_BOUND/);assert.throws(()=>assertNativeFenceWorkerFacts({...f.lease.worker},f.facts(),container),/TRUSTED_NATIVE/);
 await f.lease.release();assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,f.facts(),container),/NOT_HELD/);
});
for(const [name,change]of [
 ['pid',o=>o.pid++],['parent',o=>o.parentId++],['old birth',o=>o.createdAt='2000-01-01T00:00:00Z'],['future birth',o=>o.createdAt='2099-01-01T00:00:00Z'],['unreadable command',o=>o.commandSha256=null],['different command',o=>o.commandSha256='f'.repeat(64)],['wrong executable',o=>o.executablePath='D:\\tools\\other.exe'],['wrong name',o=>o.name='node.exe']
])test('native worker initial binding refuses '+name,async()=>{
 const f=ownedWorkerFixture(change);await f.lease.acquire();await assert.rejects(f.lease.worker.bind(),/BINDING_REFUSED/);await assert.rejects(f.lease.worker.bind(),/ALREADY_BOUND/);f.lease.abort();await f.lease.waitClosed();
});
for(const field of ['pid','parentId','createdAt','commandSha256'])test('native worker refuses live identity drift '+field,async()=>{
 const f=ownedWorkerFixture();await f.lease.acquire();await f.lease.worker.bind();const facts=f.facts();facts.hostProcesses[0][field]=null;
 assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,facts,container),/IDENTITY_CHANGED/);await f.lease.release();
});
test('native worker cannot bind NEW, use a different tool, omit the PID or accept duplicate observations',async()=>{
 const f=ownedWorkerFixture();await assert.rejects(f.lease.worker.bind(),/NOT_HELD/);await f.lease.acquire();await f.lease.worker.bind();
 assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,f.facts(),'f'.repeat(64)),/SCOPE_REQUIRED/);
 const facts=f.facts();facts.hostProcesses=[];assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,facts,container),/IDENTITY_CHANGED/);
 facts.hostProcesses=[f.observation(),f.observation()];assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,facts,container),/IDENTITY_CHANGED/);await f.lease.release();
});
test('native worker PID reuse, exit without close and controller changes never acquire responsibility',async()=>{
 const f=ownedWorkerFixture();await f.lease.acquire();await f.lease.worker.bind();f.p.pid++;
 assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,f.facts(),container),/NOT_HELD/);f.p.pid--;f.p.exitCode=0;
 assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,f.facts(),container),/NOT_HELD/);f.p.exitCode=null;
 assert.throws(()=>assertNativeFenceWorkerFacts(f.lease.worker,{...f.facts(),selfPid:1},container),/SCOPE_REQUIRED/);await f.lease.release();
});
test('continuous guard recognizes only its held source worker and still rejects unrelated children',async()=>{
 const f=ownedWorkerFixture(()=>{},CONTINUOUS75.sourceTool),host={format:'ACTR66_HOST_WRITER_RESPONSIBILITY/v1',owner:'Codex',records:[]};await f.lease.acquire();await f.lease.worker.bind();
 assertHostResponsibility75(f.facts(),host,f.lease.worker);
 const facts=f.facts();facts.hostProcesses.push({...f.observation(),pid:54322});assert.throws(()=>assertHostResponsibility75(facts,host,f.lease.worker),/UNREGISTERED_HOST_54322/);
 await f.lease.release();assert.throws(()=>assertHostResponsibility75(f.facts(),host,f.lease.worker),/NOT_HELD/);
});
test('release acknowledgement and kill request do not substitute for an actual close event',async()=>{
 const p=fakeChild((text,p)=>send(p,text==='RELEASE'?'RELEASED':'HELD'));p.kill=()=>{p.kills++;};
 const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p,timeoutMs:50});await lease.acquire();let returned=false;
 const release=lease.release().then(()=>{returned=true;});await new Promise(r=>setTimeout(r,5));assert.equal(returned,false);assert.ok(p.kills);
 p.emit('close',0,null);await release;assert.equal(returned,true);assert.equal((await lease.waitClosed()).code,0);
});
test('unclosed release, failed kill and repeated wait remain bounded and never claim exit',async()=>{
 const p=fakeChild((text,p)=>send(p,text==='RELEASE'?'RELEASED':'HELD'));p.kill=()=>{throw Error('unit only');};
 const lease=persistentFenceProtocol(invocation,schemas,{spawnIO:()=>p,timeoutMs:10});await lease.acquire();await assert.rejects(lease.release(),/EXIT_UNCONFIRMED/);
 await assert.rejects(lease.waitClosed(),/EXIT_UNCONFIRMED/);p.emit('close',1,'SIGTERM');assert.equal((await lease.waitClosed()).code,1);
});
test('real owned Node protocol worker has a verifiable Windows command tuple without Docker or DB', {skip:process.platform!=='win32'},async()=>{
 const code=`const r=require('node:readline').createInterface({input:process.stdin});r.on('line',s=>console.log((s==='RELEASE'?'RELEASED':'HELD')+' ${proof} 36'));`;
 const lease=persistentFenceProtocol({command:process.execPath,args:['-e',code]},schemas);
 try{await lease.acquire();const binding=await lease.worker.bind();assert.equal(binding.parentId,process.pid);assert.match(binding.commandSha256,/^[a-f0-9]{64}$/);await lease.release();assert.equal((await lease.waitClosed()).pid,binding.pid);}finally{lease.abort();await lease.waitClosed();}
});


import {nativeDiagnostic,withNativeStage} from './single-database-native-diagnostic.mjs';
for(const [name,patch,stage] of [
 ['metadata shape',f=>{f.tool.Id='wrong';},'FENCE_METADATA_INSPECT'],
 ['digest mismatch',f=>{f.classDigest='f'.repeat(64);},'FENCE_CLASS_DIGEST'],
 ['digest malformed',f=>{f.classDigest='private';},'FENCE_CLASS_DIGEST']
])test('real prepare wiring keeps validation stage: '+name,async()=>{
 const f=fixture();patch(f);await assert.rejects(withNativeStage('FENCE_OPEN',()=>prepareContainerFence(f.c,reader(f))),e=>{assert.equal(nativeDiagnostic(e).stage,stage);assert.doesNotMatch(JSON.stringify(nativeDiagnostic(e)),/private/);return true;});
});
test('malformed inspect JSON is sanitized inside its exact stage',async()=>{
 await assert.rejects(withNativeStage('FENCE_OPEN',()=>prepareContainerFence(fixture().c,async()=>Buffer.from('private-not-json'))),e=>{assert.equal(e.message,'CONTAINER_FENCE_METADATA_UNAVAILABLE');assert.equal(nativeDiagnostic(e).stage,'FENCE_METADATA_INSPECT');return true;});
});
