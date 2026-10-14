import test from 'node:test';
import assert from 'node:assert/strict';
import cp from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {jdbcRequest} from './single-database-lifecycle-runtime.mjs';
import './single-database-lifecycle-native.mjs'; // Preload existing manager, not an external operation.
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function fixture(t,{closeOnKill=false}={}){
 const p=new EventEmitter(),signals=[],calls=[];p.stdout=new PassThrough();p.stderr=new PassThrough();p.stdin=new PassThrough();p.stdin.resume();
 p.kill=signal=>{signals.push(signal);if(closeOnKill)queueMicrotask(()=>p.emit('close',0));return true;};
 const original=process.env.UNIT_JDBC_PASSWORD;process.env.UNIT_JDBC_PASSWORD='self-authored-unit-only';
 t.mock.method(cp,'spawn',(...args)=>{calls.push(args);return p;});syncBuiltinESMExports();t.mock.timers.enable({apis:['setTimeout']});
 t.after(()=>{t.mock.restoreAll();syncBuiltinESMExports();if(original===undefined)delete process.env.UNIT_JDBC_PASSWORD;else process.env.UNIT_JDBC_PASSWORD=original;p.stdout.destroy();p.stderr.destroy();p.stdin.destroy();});
 const config={java:'owned-fake-java',classpath:'owned-fake-classpath',mainClass:'UnitOnly',url:'unit:no-network',user:'UNIT',passwordEnv:'UNIT_JDBC_PASSWORD'};
 return {p,signals,calls,config,run:()=>jdbcRequest(config,{operation:'unit-owned-no-db'})};
}
test('JDBC ignored TERM and absent close still reject within fixed grace',async t=>{
 const f=fixture(t);let outcome='pending';const promise=f.run();promise.then(()=>outcome='success',e=>outcome=e.message);await flush();
 t.mock.timers.tick(120000);t.mock.timers.tick(5000);await flush();
 assert.equal(outcome,'JDBC_REQUEST_FAILED');assert.deepEqual(f.signals,['SIGTERM','SIGKILL']);
});
test('JDBC late zero after timeout cannot become successful inventory',async t=>{
 const f=fixture(t,{closeOnKill:true}),promise=f.run();await flush();f.p.stdout.write(Buffer.from('{"ok":true}'));
 t.mock.timers.tick(120000);await assert.rejects(promise,/JDBC_REQUEST_FAILED/);
});
test('JDBC output overflow cannot accept previously valid JSON prefix',async t=>{
 const f=fixture(t,{closeOnKill:true}),promise=f.run();await flush();f.p.stdout.write(Buffer.from('{"ok":true}'));f.p.stdout.write(Buffer.alloc(32*1024*1024+1));
 await assert.rejects(promise,/JDBC_REQUEST_FAILED/);
});
test('normal JDBC JSON and fixed shell/env contract preserved',async t=>{
 const f=fixture(t),promise=f.run();await flush();const bytes=Buffer.from('{"ok":true}');f.p.stdout.write(bytes);f.p.emit('close',0);
 assert.deepEqual(await promise,{ok:true});assert.equal(f.calls[0][0],'owned-fake-java');assert.equal(f.calls[0][2].shell,false);assert.equal(f.calls[0][2].env.LIFECYCLE_JDBC_PASSWORD,'self-authored-unit-only');
});
for(const payload of ['not-json','{"error":"unexpected private"}','{"error":"JDBC_OPERATION_REFUSED_OR_FAILED","sqlState":"42000","vendorCode":99}'])test('JDBC fixed JSON error mapping: '+payload,async t=>{
 const f=fixture(t),promise=f.run();await flush();f.p.stdout.write(Buffer.from(payload));f.p.emit('close',0);
 const expected=payload.includes('JDBC_OPERATION_REFUSED_OR_FAILED')?'JDBC_OPERATION_REFUSED_OR_FAILED:42000:99':'JDBC_REQUEST_FAILED';
 await assert.rejects(promise,e=>e.message===expected);
});
test('JDBC unavailable event never leaks underlying credential diagnostics',async t=>{
 const f=fixture(t),promise=f.run();await flush();f.p.emit('error',Error('unit-private-password'));
 await assert.rejects(promise,e=>e.message==='JDBC_PROCESS_UNAVAILABLE');
});
for(const stream of ['stdin','stdout','stderr'])test('JDBC '+stream+' failure rejects without close',async t=>{
 const f=fixture(t),promise=f.run();let outcome='pending';promise.then(()=>outcome='success',e=>outcome=e.message);await flush();f.p[stream].emit('error',Error('unit-private'));t.mock.timers.tick(5000);await flush();
 assert.equal(outcome,'JDBC_REQUEST_FAILED');
});
