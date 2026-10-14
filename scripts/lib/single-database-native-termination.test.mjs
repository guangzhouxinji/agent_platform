import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {spawn} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {runNativeCommand} from './single-database-lifecycle-native.mjs';
import {runNativeRestoreCommand} from './single-database-lifecycle-native-restore.mjs';
function child(t,{killThrows=false,killReturns=true,closeOnTerm=false}={}) {
 const p=new EventEmitter(),signals=[],calls=[];p.stdout=new PassThrough();p.stderr=new PassThrough();p.stdin=new PassThrough();p.stdin.resume();
 p.kill=signal=>{signals.push(signal);if(killThrows)throw Error('private-detail-not-public');if(closeOnTerm&&signal==='SIGTERM')queueMicrotask(()=>p.emit('close',0));return killReturns;};
 t.after(()=>{p.stdout.destroy();p.stderr.destroy();p.stdin.destroy();});
 return {p,signals,calls,spawnIO:(...args)=>{calls.push(args);return p;}};
}
function execute(fake,options={}) {return runNativeCommand('owned-unit-command',['fixed-argument'],{spawnIO:fake.spawnIO,timeoutMs:15,killGraceMs:15,...options});}
const delay=ms=>new Promise(ok=>setTimeout(ok,ms));
test('actual exported native command completes on legitimate zero close and clears timers',async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:100});const bytes=Buffer.from('owned-success');f.p.stdout.write(bytes);f.p.emit('close',0);
 const output=await promise;assert.equal(output.toString(),'owned-success');assert.equal(bytes.every(n=>n===0),true);await delay(40);assert.deepEqual(f.signals,[]);output.fill(0);
 assert.equal(f.calls[0][0],'owned-unit-command');assert.deepEqual(f.calls[0][1],['fixed-argument']);assert.equal(f.calls[0][2].shell,false);
});
test('ignored TERM and absent close receive KILL and settle failure within finite grace',{timeout:2000},async t=>{
 const f=child(t);await assert.rejects(execute(f),/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
test('kill returns false still cannot leave promise pending',{timeout:2000},async t=>{
 const f=child(t,{killReturns:false});await assert.rejects(execute(f),/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
test('kill throws secret-bearing error but public rejection is fixed and bounded',{timeout:2000},async t=>{
 const f=child(t,{killThrows:true});await assert.rejects(execute(f),e=>e.message==='NATIVE_PROCESS_REFUSED_OR_TIMED_OUT');assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
test('late zero close cannot turn timed-out command into success',async t=>{
 const f=child(t,{closeOnTerm:true});await assert.rejects(execute(f),/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);assert.deepEqual(f.signals,['SIGTERM']);await delay(35);assert.deepEqual(f.signals,['SIGTERM']);
});
test('force-settled child ignores late close and wipes late output',async t=>{
 const f=child(t);await assert.rejects(execute(f),/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);const late=Buffer.from('late-private-output');f.p.stdout.write(late);f.p.emit('close',0);assert.equal(late.every(n=>n===0),true);
});
test('buffer overflow clears collected and overflowing bytes, never exposes diagnostics',async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:1000,maxBytes:3});const first=Buffer.from('abc'),overflow=Buffer.from('private-overflow');f.p.stdout.write(first);f.p.stdout.write(overflow);
 await assert.rejects(promise,e=>e.message==='NATIVE_PROCESS_REFUSED_OR_TIMED_OUT');assert.equal(first.every(n=>n===0),true);assert.equal(overflow.every(n=>n===0),true);assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
for(const stream of ['stdout','stderr','stdin'])test(stream+' error fails closed without depending on close',async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:1000});f.p[stream].emit('error',new Error('not-public'));await assert.rejects(promise,/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
test('spawn failure and asynchronous unavailable event use fixed public classification',async t=>{
 await assert.rejects(runNativeCommand('owned',[],{spawnIO:()=>{throw Error('secret')}}),e=>e.message==='NATIVE_PROCESS_UNAVAILABLE');
 const f=child(t),promise=execute(f);f.p.emit('error',new Error('sensitive-path'));await assert.rejects(promise,e=>e.message==='NATIVE_PROCESS_UNAVAILABLE');await delay(40);assert.deepEqual(f.signals,[]);
});
test('error after timeout does not cancel hard termination timer',async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:5,killGraceMs:30});await delay(15);f.p.emit('error',new Error('late'));await assert.rejects(promise,/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
test('nonzero exit, missing status and signal never become success',async t=>{
 for(const args of [[9],[null],[undefined],[0,'SIGTERM']]){const f=child(t),promise=execute(f);const bytes=Buffer.from('private');f.p.stdout.write(bytes);f.p.emit('close',...args);await assert.rejects(promise,/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);assert.equal(bytes.every(n=>n===0),true);}
});
test('finite timeout/grace/output bounds reject before spawning',async()=>{
 for(const options of [{timeoutMs:0},{timeoutMs:Infinity},{timeoutMs:135001},{killGraceMs:0},{killGraceMs:5001},{maxBytes:0},{maxBytes:134217729}]){
  let spawned=false;await assert.rejects(runNativeCommand('owned',[],{...options,spawnIO:()=>{spawned=true}}),/FINITE_NATIVE_COMMAND_LIMITS_REQUIRED/);assert.equal(spawned,false);
 }
});
test('restore calls identical bounded manager and retains partial-preserved classification',async t=>{
 const f=child(t);await assert.rejects(runNativeRestoreCommand(['exec','owned-unit-only'],{spawnIO:f.spawnIO,timeoutMs:10,killGraceMs:10}),/NATIVE_RESTORE_NONZERO_OR_TIMEOUT_PARTIAL_PRESERVED/);
 assert.equal(f.calls[0][0],'docker');assert.deepEqual(f.calls[0][1],['exec','owned-unit-only']);assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
 await assert.rejects(runNativeRestoreCommand([],{spawnIO:()=>{throw Error()}}),/NATIVE_RESTORE_PROCESS_UNAVAILABLE/);
});
test('exporter now uses the same five-second forced native termination fallback as restore',()=>{
 const exporter=readFileSync(new URL('./single-database-lifecycle-native.mjs',import.meta.url),'utf8'),restorer=readFileSync(new URL('./single-database-lifecycle-native-restore.mjs',import.meta.url),'utf8');
 assert.match(exporter,/timeout -k 5 "\$2" \/opt\/dmdbms\/bin\/dexp/);assert.match(restorer,/timeout -k 5 "\$2" \/opt\/dmdbms\/bin\/dimp/);
 assert.doesNotMatch(exporter+restorer,/pkill|taskkill|killall/);
});
test('real owned Node child normal completion returns bytes without DB or shell',{timeout:5000},async()=>{
 const out=await runNativeCommand(process.execPath,['-e','process.stdout.write("owned-native-command-unit");'],{timeoutMs:2000,killGraceMs:30});assert.equal(out.toString(),'owned-native-command-unit');out.fill(0);
});
test('real owned Node child timeout returns non-success without killing other workers',{timeout:5000},async t=>{
 let owned;const spawnIO=(...args)=>{owned=spawn(...args);return owned;};t.after(()=>{if(owned&&owned.exitCode===null&&owned.signalCode===null)owned.kill('SIGKILL');});
 await assert.rejects(runNativeCommand(process.execPath,['-e','process.on("SIGTERM",()=>{});setInterval(()=>{},1000);'],{spawnIO,timeoutMs:200,killGraceMs:30}),/NATIVE_PROCESS_REFUSED_OR_TIMED_OUT/);
});

// D9.25: diagnostics never serialize command, secrets, output or arbitrary Error properties.
import {nativeDiagnostic,withNativeStage} from './single-database-native-diagnostic.mjs';
test('diagnostic natural nonzero close has exact safe process facts but no command or content',async t=>{
 const f=child(t),promise=withNativeStage('INVENTORY_BEFORE',()=>execute(f,{timeoutMs:1000}));
 f.p.stdout.write(Buffer.from('private-stdout'));f.p.stderr.write(Buffer.from('private-stderr'));f.p.emit('close',7);
 await assert.rejects(promise,e=>{const d=nativeDiagnostic(e);assert.equal(d.stage,'INVENTORY_BEFORE');assert.equal(d.reason,'NONZERO_EXIT');assert.equal(d.exitCode,7);assert.equal(d.closeObserved,true);assert.equal(d.termination,'CLOSE_OBSERVED');assert.equal(d.remoteTermination,'NOT_PROVEN');assert.equal(d.stdoutBytes,14);assert.equal(d.stderrBytes,14);assert.doesNotMatch(JSON.stringify(d),/private|owned-unit|fixed-argument/);assert.ok(Object.isFrozen(d));return true;});
});
test('timeout without close remains unconfirmed and never infers remote completion',async t=>{
 const f=child(t);await assert.rejects(execute(f),e=>{const d=nativeDiagnostic(e);assert.equal(d.reason,'TIMEOUT');assert.equal(d.closeObserved,false);assert.equal(d.exitCode,null);assert.equal(d.termination,'UNCONFIRMED');return true;});
});
test('timeout followed by close remains failure with observed close, not natural success',async t=>{
 const f=child(t,{closeOnTerm:true});await assert.rejects(execute(f),e=>{const d=nativeDiagnostic(e);assert.equal(d.reason,'TIMEOUT');assert.equal(d.closeObserved,true);assert.equal(d.exitCode,0);return true;});
});
test('synchronous spawn failure is not-started, asynchronous error is unconfirmed',async t=>{
 await assert.rejects(runNativeCommand('secret-executable',['secret-argument'],{spawnIO:()=>{throw Error('private')}}),e=>{assert.equal(nativeDiagnostic(e).termination,'NOT_STARTED');assert.doesNotMatch(JSON.stringify(nativeDiagnostic(e)),/private|secret/);return true;});
 const f=child(t),promise=execute(f);f.p.emit('error',Error('private'));await assert.rejects(promise,e=>{assert.equal(nativeDiagnostic(e).termination,'UNCONFIRMED');assert.equal(nativeDiagnostic(e).reason,'SPAWN_ERROR');return true;});
});
test('invalid caller-supplied stage is rejected before any child exists',async()=>{
 let spawned=false;await assert.rejects(runNativeCommand('unused',[],{stage:'PASSWORD=private',spawnIO:()=>{spawned=true}}),/FIXED_NATIVE_DIAGNOSTIC_STAGE_REQUIRED/);assert.equal(spawned,false);
});
test('nested and concurrent stage contexts retain the most specific independent failure',async()=>{
 const work=(stage,ms)=>withNativeStage('SNAPSHOT_SEAL',()=>withNativeStage(stage,async()=>{await delay(ms);throw Error('private');}));
 const outcomes=await Promise.allSettled([work('FENCE_CLASS_DIGEST',8),work('INVENTORY_AFTER',1)]);
 assert.deepEqual(outcomes.map(v=>nativeDiagnostic(v.reason).stage),['FENCE_CLASS_DIGEST','INVENTORY_AFTER']);
});
test('external Error diagnostic and cause are never trusted or serialized',async()=>{
 const e=Error('private');e.diagnostic={stage:'secret',password:'private'};e.cause=Error('secret');assert.equal(nativeDiagnostic(e),undefined);
 await assert.rejects(withNativeStage('FENCE_RELEASE',async()=>{throw e;}),error=>{const d=nativeDiagnostic(error);assert.equal(d.stage,'FENCE_RELEASE');assert.equal(d.reason,'STAGE_REFUSED');assert.doesNotMatch(JSON.stringify(d),/secret|private|password|cause/);return true;});
});
for(const [args,reason,signal] of [[[9],'NONZERO_EXIT',null],[[null],'UNKNOWN_EXIT',null],[[0,'SIGTERM'],'SIGNAL_EXIT','SIGTERM'],[[0,'private-signal'],'SIGNAL_EXIT','UNKNOWN']])test('diagnostic safe close classification '+reason+' '+signal,async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:1000});f.p.emit('close',...args);await assert.rejects(promise,e=>{assert.equal(nativeDiagnostic(e).reason,reason);assert.equal(nativeDiagnostic(e).signal,signal);return true;});
});
test('output overflow reports its own fixed reason',async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:1000,maxBytes:1});f.p.stdout.write(Buffer.from('private'));await assert.rejects(promise,e=>nativeDiagnostic(e).reason==='OUTPUT_LIMIT');
});
for(const stream of ['stdout','stderr','stdin'])test('diagnostic '+stream+' error cannot expose exception text',async t=>{
 const f=child(t),promise=execute(f,{timeoutMs:1000});f.p[stream].emit('error',Error('private'));await assert.rejects(promise,e=>nativeDiagnostic(e).reason==='STREAM_ERROR'&&!JSON.stringify(nativeDiagnostic(e)).includes('private'));
});
test('real owned child natural failure gives bounded safe diagnostic without DB or shell',{timeout:5000},async()=>{
 await assert.rejects(withNativeStage('NATIVE_EXPORT',()=>runNativeCommand(process.execPath,['-e','process.stderr.write("private-key-not-public");process.exitCode=23;'],{timeoutMs:2000})),e=>{const d=nativeDiagnostic(e);assert.equal(d.stage,'NATIVE_EXPORT');assert.equal(d.exitCode,23);assert.equal(d.closeObserved,true);assert.ok(d.pid>0);assert.ok(d.stderrBytes>0);assert.doesNotMatch(JSON.stringify(d),/private-key|process.stderr/);return true;});
});

import {verifyNativeExportLog,verifyNativeExportBytes} from './single-database-lifecycle-native.mjs';
test('successful command with refused log retains export stage, never seal stage',async()=>{
 await assert.rejects(withNativeStage('SNAPSHOT_SEAL',async()=>verifyNativeExportLog(Buffer.from('private-invalid-log'))),e=>{assert.equal(nativeDiagnostic(e).stage,'NATIVE_EXPORT');assert.equal(nativeDiagnostic(e).reason,'STAGE_REFUSED');return true;});
});
test('successful command with empty bytes retains read stage, never seal stage',async()=>{
 await assert.rejects(withNativeStage('SNAPSHOT_SEAL',async()=>verifyNativeExportBytes(Buffer.alloc(0))),e=>{assert.equal(nativeDiagnostic(e).stage,'NATIVE_READ');return true;});
 const bytes=Buffer.from('opaque-encrypted-export');assert.equal(verifyNativeExportBytes(bytes),bytes);
 const source=readFileSync(new URL('./single-database-lifecycle-native.mjs',import.meta.url),'utf8');assert.match(source,/verifyNativeExportBytes\(bytes\);if\(bytes.length!==file.bytes\)fail\('NATIVE_EXPORT_FILE_CHANGED'\)/);
});
