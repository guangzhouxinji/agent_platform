import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {randomBytes} from 'node:crypto';
import {hash,canonical,assertInventory,inventoryDigest,foreignKeyDependencies,splitForwardSql,admission,executeLifecycle} from './single-database-lifecycle-runtime.mjs';
import {sealSnapshot,openSnapshot} from './single-database-lifecycle-snapshot.mjs';
// Unit-only port doubles. Real JDBC proof is in the dedicated engineering receipts, not these fixtures.
const schemas=['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'],accounts=schemas.flatMap(s=>[s,s+'_SVC']),targetId='a'.repeat(64);
const fixture=()=>({format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId,actualJdbc:true,complete:true,errors:[],schemas:schemas.map(schema=>({schema,objects:[],columns:[],constraints:[],foreignKeys:[],indexes:[],sequences:[],views:[],triggers:[],dependencies:[],rows:[]})),accounts:accounts.map(name=>({name,exists:true,systemPrivileges:[],roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name],effectiveGrants:[]}))});
const approval=action=>({format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId,action,writers:[{id:'unit-owned-writer',owner:'UNIT',responsibilitySource:'UNIT_DOUBLE_ONLY'}],owner:'UNIT',source:'explicit unit-only approval fixture',approvedAt:'2026-10-07T00:00:00Z',expiresAt:'2099-01-01T00:00:00Z',currentMigration:false,connectionSwitch:false,deleteExisting:false});
const make=async()=>{
 const dir=await mkdtemp(resolve(tmpdir(),'single-lifecycle-unit-'));let live=fixture(),writes=0;
 const bytes=await readFile('db/identity-authority/V001__grant_watermark.sql');
 const m={schema:'CONAUTH',id:'V001',sqlPath:'db/identity-authority/V001__grant_watermark.sql',sha256:hash(bytes),dependsOn:[],prechecks:[{kind:'object',name:'ID2_AUTHORITY_WATERMARK',expected:'absent'}],postchecks:[{kind:'object',name:'ID2_AUTHORITY_WATERMARK',expected:'present'}]};
 const manifest={targetId,schemas,accounts,mode:'empty',expectedInventorySha256:inventoryDigest(live),knownObjects:[],migrations:[m]};
 const a={...approval('execute'),manifestSha256:hash(canonical(manifest))};
 return {dir,manifest,a,m,args:{acquireExecutionFence:async()=>({assertHeld:async()=>{},release:async()=>{}}),manifest,approval:a,workspaceRoot:resolve('.'),journalDir:dir,inventory:async()=>structuredClone(live),execute:async()=>{writes++;live.schemas[1].objects.push({name:'ID2_AUTHORITY_WATERMARK',type:'TABLE'});}},get live(){return live;},set live(x){live=x;},get writes(){return writes;},sign(){a.manifestSha256=hash(canonical(manifest));}};
};
test('inventory has six complete schemas and twelve full account categories',()=>assert.equal(assertInventory(fixture(),targetId,schemas,accounts).complete,true));
for(const [name,mutate,code] of [
 ['no JDBC proof',i=>i.actualJdbc=false,'INCOMPLETE'],['unknown target',i=>i.targetId='b'.repeat(64),'INCOMPLETE'],['partial catalogs',i=>i.errors.push('unsupported'),'INCOMPLETE'],['missing trigger category',i=>delete i.schemas[0].triggers,'CATEGORY'],['duplicate schema',i=>i.schemas[1].schema=i.schemas[0].schema,'SIX_EXACT'],['missing account',i=>i.accounts[1].exists=false,'TWELVE'],['missing effective privileges',i=>delete i.accounts[0].effectiveGrants,'PRIVILEGE_CATEGORY']])test(`unit inventory rejects ${name}`,()=>{const i=fixture();mutate(i);assert.throws(()=>assertInventory(i,targetId,schemas,accounts),new RegExp(code));});
test('exact quoting and comments, semicolon in literal preserved',()=>assert.equal(splitForwardSql("-- hi\nCREATE TABLE T(A VARCHAR(30) DEFAULT 'x;''y'); /* end */").length,1));
for(const sql of ['DROP TABLE T;','DELETE FROM T;','CREATE TABLE T(A INT); DROP TABLE T;','ALTER TABLE T DROP COLUMN A;','CREATE TABLE T(A VARCHAR(2) DEFAULT \'x);','/* open'])test(`unit SQL rejects ${sql.slice(0,25)}`,()=>assert.throws(()=>splitForwardSql(sql)));
test('approval is action- and expiry-bound',()=>{assert.throws(()=>admission({...approval('execute'),expiresAt:'2026-01-01'},targetId,'execute'));assert.throws(()=>admission(approval('execute'),targetId,'snapshot'));assert.throws(()=>admission({...approval('execute'),deleteExisting:true},targetId,'execute'));});
test('FK graph groups composite key, orders parent before child and exposes missing references',()=>{const i=fixture();i.schemas[0].objects=[{name:'P',type:'TABLE'},{name:'C',type:'TABLE'}];i.schemas[0].foreignKeys=[1,2].map(n=>({FKTABLE_SCHEM:'CONPROD',FKTABLE_NAME:'C',PKTABLE_SCHEM:'CONPROD',PKTABLE_NAME:'P',FK_NAME:'FK',KEY_SEQ:n,FKCOLUMN_NAME:'C'+n,PKCOLUMN_NAME:'P'+n}));const g=foreignKeyDependencies(i);assert.equal(g.edges.length,1);assert.equal(g.edges[0].columns.length,2);assert.deepEqual(g.tableDependencyOrder,['CONPROD.P','CONPROD.C']);i.schemas[0].foreignKeys[0].PKTABLE_SCHEM='OTHER';assert.equal(foreignKeyDependencies(i).unresolved.length,1);});
test('unit successful record reconciles originals and never blindly replays',async()=>{const f=await make();assert.equal((await executeLifecycle(f.args)).applied,1);assert.equal((await executeLifecycle(f.args)).skipped,1);assert.equal(f.writes,1);});
for(const [name,mutate,code] of [
 ['changed SQL original',f=>f.m.sha256='b'.repeat(64),'SQL_SHA'],['manifest approval mismatch',f=>{f.manifest.mode='existing';},'APPROVAL_MANIFEST'],['empty path with existing objects',f=>{f.live.schemas[0].objects.push({name:'T',type:'TABLE'});f.manifest.knownObjects=[{schema:'CONPROD',name:'T',type:'TABLE'}];f.manifest.expectedInventorySha256=inventoryDigest(f.live);f.sign();},'EMPTY_MODE'],['unknown object',f=>{f.live.schemas[0].objects.push({name:'T',type:'TABLE'});f.manifest.expectedInventorySha256=inventoryDigest(f.live);f.sign();},'UNKNOWN_OBJECT'],['precondition failure',f=>{f.m.prechecks[0].expected='present';f.sign();},'PRECONDITION'],['unbound dependency',f=>{f.m.dependsOn=['ENFPROD:V999'];f.sign();},'DEPENDENCY'],['wrong original ID',f=>{f.m.id='V999';f.sign();},'EXACT_ORIGINAL'],['inventory drift',f=>{f.live.schemas[0].rows.push({table:'T',count:1});},'LIVE_INVENTORY_DRIFT'],['existing path without authenticated physical protection',f=>{f.manifest.mode='existing';f.sign();},'ACTUAL_AUTHENTICATED']])test(`unit zero-write guard: ${name}`,async()=>{const f=await make();mutate(f);if(name==='changed SQL original')f.sign();await assert.rejects(()=>executeLifecycle(f.args),new RegExp(code));assert.equal(f.writes,0);});
test('all dependencies are checked before first migration, not after partial earlier DDL',async()=>{const f=await make();f.manifest.migrations.push({...f.m,schema:'ENFAUTH',dependsOn:['NOT:V999']});f.sign();await assert.rejects(()=>executeLifecycle(f.args),/DEPENDENCY/);assert.equal(f.writes,0);});
test('DDL failure preserves hash-chained failure scene, blocks next attempt',async()=>{const f=await make();f.args.execute=async()=>{throw new Error('driver fault');};await assert.rejects(()=>executeLifecycle(f.args),/DDL_FAILED_PARTIAL/);const events=(await readFile(resolve(f.dir,'events.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);assert.equal(events.at(-1).rollbackClaimed,false);assert.equal(hash(await readFile(resolve(f.dir,events.at(-1).failureScene))),events.at(-1).failureSceneSha256);await assert.rejects(()=>executeLifecycle(f.args),/PARTIAL_DDL/);});
test('journal mutation and stale execution lock refuse',async()=>{const f=await make();await writeFile(resolve(f.dir,'execute.lock'),'crash');await assert.rejects(()=>executeLifecycle(f.args),/EEXIST/);const g=await make();await executeLifecycle(g.args);const file=resolve(g.dir,'events.jsonl');await writeFile(file,(await readFile(file,'utf8')).replace('SUCCESS','FORGED'));await assert.rejects(()=>executeLifecycle(g.args),/JOURNAL_CHAIN/);});
const snap=async(extra={})=>{const dir=await mkdtemp(resolve(tmpdir(),'single-snapshot-unit-')),file=resolve(dir,'snapshot'),key=randomBytes(32);let released=0,exports=0;const a={...approval('snapshot'),writers:[{id:'owned-writer',owner:'UNIT',responsibilitySource:'unit-only double'}],files:[],databaseRights:{owner:'UNIT',licenseReference:'UNIT_DOUBLE_ONLY',purpose:'unit fixture only',schemas,useUntil:'2099-01-01'},exportMethod:'UNIT_DOUBLE_NOT_DATABASE',scope:'UNIT_ONLY'};const args={approval:a,inventory:async()=>fixture(),exportDatabase:async()=>{exports++;return Buffer.from('unit native bytes, NOT DB proof');},files:[],key,output:file,acquireFence:async()=>({assertHeld:async()=>{},release:async()=>{released++;}}),schemas,accounts,...extra};return {file,key,args,get released(){return released;},get exports(){return exports;}};};
test('unit authenticated bounded package and expired-header/body-tamper guards',async()=>{const f=await snap();const r=await sealSnapshot(f.args);const o=await openSnapshot({file:f.file,key:f.key,expectedTargetId:targetId,expectedSha256:r.sealedSha256});assert.equal(hash(o.native),r.metadata.nativeSha256);o.native.fill(0);await assert.rejects(()=>openSnapshot({file:f.file,key:f.key,expectedTargetId:targetId,now:Date.parse('2100-01-01')}),/EXPIRED/);const b=await readFile(f.file);b[b.length-1]^=1;await writeFile(f.file,b);await assert.rejects(()=>openSnapshot({file:f.file,key:f.key,expectedTargetId:targetId}),/AUTHENTICATION/);assert.equal(f.released,1);});
test('unit absent writer responsibility never exports, changed inventory releases fence',async()=>{const f=await snap();f.args.approval.writers=[];await assert.rejects(()=>sealSnapshot(f.args),/WRITER_RESPONSIBILITY/);assert.equal(f.exports,0);const g=await snap();let reads=0;g.args.inventory=async()=>{const i=fixture();if(reads++)i.schemas[0].rows.push({table:'T',count:1});return i;};await assert.rejects(()=>sealSnapshot(g.args),/DATABASE_CHANGED/);assert.equal(g.released,1);});
test('unit approved original license/digest verified and file content authenticated',async()=>{const f=await snap();const source=resolve(f.file+'-original');await writeFile(source,'self-owned unit bytes',{flag:'wx'});const sha256=hash(await readFile(source));f.args.files=[{path:source,sha256}];f.args.approval.files=[{path:source,sha256,licenseReference:'unit-owned-fixture-only',useUntil:'2090-01-01'}];const r=await sealSnapshot(f.args);const o=await openSnapshot({file:f.file,key:f.key,expectedTargetId:targetId});assert.equal(o.metadata.expiresAt,'2090-01-01');assert.equal(o.content.files.length,1);o.native.fill(0);assert.equal(r.fullThreeDomainProductRecoveryProven,false);});
test('unit expired original refuses package without overriding audit retention',async()=>{const f=await snap();f.args.files=[{path:'unused',sha256:'a'.repeat(64)}];f.args.approval.files=[{path:'unused',sha256:'a'.repeat(64),licenseReference:'unit-only',useUntil:'2000-01-01'}];await assert.rejects(()=>sealSnapshot(f.args),/CURRENT_ORIGINAL_RIGHTS/);assert.equal(f.released,1);});

test('unit execution refuses missing writer fence before inventory or DDL',async()=>{const f=await make();delete f.args.acquireExecutionFence;await assert.rejects(()=>executeLifecycle(f.args),/EXECUTION_WRITER_FENCE/);assert.equal(f.writes,0);});

test('unit absent database rights refuses before fencing/export',async()=>{const f=await snap();delete f.args.approval.databaseRights;await assert.rejects(()=>sealSnapshot(f.args),/DATABASE_SOURCE_RIGHTS/);assert.equal(f.exports,0);});


// Actual elapsed wall time, unit-owned buffers/ports only; not a DM8 dump or business source permit.
for(const boundary of ['approval','database','original']) test('unit expiry during export closes '+boundary+' rights and zeroes native buffer',async()=>{
 const f=await snap();const expires=new Date(Date.now()+1000).toISOString();
 if(boundary==='approval')f.args.approval.expiresAt=expires;
 if(boundary==='database')f.args.approval.databaseRights.useUntil=expires;
 if(boundary==='original'){
  const source=resolve(f.file+'-current-owned-original');await writeFile(source,'self-owned deadline test',{flag:'wx'});
  const sha256=hash(await readFile(source));f.args.files=[{path:source,sha256}];
  f.args.approval.files=[{path:source,sha256,licenseReference:'OWNED_UNIT_ONLY',useUntil:expires}];
 }
 const native=Buffer.from('own-unit-native-port-bytes');let invoked=false;
 f.args.exportDatabase=async()=>{invoked=true;await new Promise(r=>setTimeout(r,1150));return native;};
 const expected=boundary==='approval'?/EXACT_CURRENT_ACTION_APPROVAL/:boundary==='database'?/CURRENT_DATABASE_SOURCE_RIGHTS/:/CURRENT_ORIGINAL_RIGHTS/;
 await assert.rejects(()=>sealSnapshot(f.args),expected);assert.equal(invoked,true);assert.equal(f.released,1);
 assert.ok(native.every(b=>b===0));await assert.rejects(()=>readFile(f.file),{code:'ENOENT'});
});
test('unit stale entry timestamp cannot reopen now-expired owned package',async()=>{
 const f=await snap();const entered=Date.now();f.args.approval.expiresAt=new Date(entered+1000).toISOString();
 await sealSnapshot(f.args);await new Promise(r=>setTimeout(r,1150));
 await assert.rejects(()=>openSnapshot({file:f.file,key:f.key,expectedTargetId:targetId,now:entered}),/EXPIRED_OR_WRONG_TARGET_NO_BODY_OPEN/);
});


test('unit execute expiry during inventory refuses DDL rather than freezing entry time',async()=>{
 const f=await make();f.a.expiresAt=new Date(Date.now()+1000).toISOString();let released=0;
 f.args.acquireExecutionFence=async()=>({assertHeld:async()=>{},release:async()=>{released++;}});
 f.args.inventory=async()=>{await new Promise(r=>setTimeout(r,1150));return structuredClone(f.live);};
 await assert.rejects(()=>executeLifecycle(f.args),/EXACT_CURRENT_ACTION_APPROVAL/);assert.equal(f.writes,0);assert.equal(released,1);
});
test('unit execute expiry during DDL preserves real partial scene and never records success',async()=>{
 const f=await make();f.a.expiresAt=new Date(Date.now()+1000).toISOString();const actual=f.args.execute;let released=0;
 f.args.acquireExecutionFence=async()=>({assertHeld:async()=>{},release:async()=>{released++;}});
 f.args.execute=async(...args)=>{await actual(...args);await new Promise(r=>setTimeout(r,1150));};
 await assert.rejects(()=>executeLifecycle(f.args),/DDL_FAILED_PARTIAL_PRESERVED/);assert.equal(f.writes,1);assert.equal(released,1);
 const events=(await readFile(resolve(f.dir,'events.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);
 assert.equal(events.some(e=>e.kind==='SUCCESS'),false);assert.equal(events.at(-1).kind,'FAILURE');assert.equal(events.at(-1).rollbackClaimed,false);
});

test('native recovery key reference is authenticated metadata, never key material',async()=>{
 const f=await snap();f.args.approval.nativeExportKeyReference='managed-owned-native-key-v1';
 const report=await sealSnapshot(f.args);
 assert.equal(report.metadata.nativeExportKeyReference,'managed-owned-native-key-v1');
 const opened=await openSnapshot({file:f.args.output,key:f.args.key,expectedTargetId:targetId});
 assert.equal(opened.metadata.nativeExportKeyReference,'managed-owned-native-key-v1');
 opened.native.fill(0);
});
