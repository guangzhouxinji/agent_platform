import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {canonical,hash} from '../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
import {assertCompletedMigration} from './prepare-primary-grants08.mjs';
const target='a'.repeat(64),sql='b'.repeat(64);
const c={targetId:target,manifest:{migrations:[{schema:'CONPROD',id:'V001',sha256:sql}]}};
function chain(bodies){let previous='';return bodies.map(body=>{body={...body,previousSha256:previous};previous=hash(canonical(body));return {...body,eventSha256:previous};});}
const start={kind:'START',targetId:target,key:'CONPROD:V001',scriptSha256:sql};
const success={...start,kind:'SUCCESS'};
const receipt=events=>({format:'SINGLE_DATABASE_EXECUTION_RECEIPT/v1',targetId:target,mode:'empty',actualExecution:true,applied:1,skipped:0,journalHead:events.at(-1)?.eventSha256});
test('guard accepts complete authenticated START/SUCCESS pair, not product acceptance',()=>{const e=chain([start,success]);assert.doesNotThrow(()=>assertCompletedMigration(c,receipt(e),e));});
test('pending START is not a resume cursor',()=>{const e=chain([start]);assert.throws(()=>assertCompletedMigration(c,receipt(e),e),/INCOMPLETE_OR_AMBIGUOUS/);});
test('original failure requires reconciliation even if receipt alleges completion',()=>{const e=chain([start,{...success,kind:'FAILURE'}]);assert.throws(()=>assertCompletedMigration(c,receipt(e),e),/REQUIRES_RECONCILIATION/);});
test('wrong target and changed script cannot cross stage',()=>{for(const event of [{...start,targetId:'c'.repeat(64)},{...start,scriptSha256:'c'.repeat(64)}]){const e=chain([event,success]);assert.throws(()=>assertCompletedMigration(c,receipt(e),e),/MANIFEST_BINDING/);}});
test('tampered chain, unmatched success and replay rejected',()=>{const e=chain([start,success]);e[0].key='CONPROD:V002';assert.throws(()=>assertCompletedMigration(c,receipt(e),e),/CHAIN_INVALID/);for(const bodies of [[success],[start,success,start]]){const x=chain(bodies);assert.throws(()=>assertCompletedMigration(c,receipt(x),x),/UNMATCHED|REPLAYED/);}});
test('failed/missing receipt and partial count reject',()=>{const e=chain([start,success]);for(const r of [undefined,{passed:false},{...receipt(e),applied:0},{...receipt(e),journalHead:'c'.repeat(64)}])assert.throws(()=>assertCompletedMigration(c,r,e));});
test('exact live active lock refuses before any new grant files or credentials',async t=>{
 const d='docs/evidence/single-database-installation-20261009/approved-execution';
 const cfg=JSON.parse(await readFile(`${d}/primary-migration-config07.json`));
 try{await readFile(cfg.maintenanceLock);}catch(e){if(e.code==='ENOENT'){t.skip('original migration completed; no manufactured live lock');return;}throw e;}
 const before=(await readdir(d)).sort();
 const r=spawnSync(process.execPath,[`${d}/prepare-primary-grants08.mjs`],{encoding:'utf8'});
 assert.notEqual(r.status,0);assert.match(r.stderr,/ACTIVE_OR_UNRECONCILED_MIGRATION_LOCK/);
 assert.deepEqual((await readdir(d)).sort(),before);
});
