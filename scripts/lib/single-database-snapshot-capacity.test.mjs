import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, open, writeFile, unlink, rmdir} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {openSnapshot} from './single-database-lifecycle-snapshot.mjs';
const CAP=128*1024*1024*2+1024*1024+40;
// Small self-authored invalid package; only stat results are doubled. No real package or DB is read.
async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'single-snapshot-capacity-')),file=join(dir,'owned.pkg');
 const header=Buffer.from(JSON.stringify({format:'SINGLE_DATABASE_AUTHENTICATED_SNAPSHOT/v2',targetId:'unit',expiresAt:'2099-01-01T00:00:00Z'}));
 const prefix=Buffer.alloc(12);prefix.write('XJSNAP02');prefix.writeUInt32BE(header.length,8);
 await writeFile(file,Buffer.concat([prefix,header,Buffer.alloc(28)]));
 const handle=await open(file,'r'),proto=Object.getPrototypeOf(handle),stat=proto.stat,alloc=Buffer.alloc;
 await handle.close();let statCalls=0;const allocations=[];
 t.after(async()=>{t.mock.restoreAll();await unlink(file);await rmdir(dir);});
 t.mock.method(Buffer,'alloc',function(size,...args){allocations.push(size);if(size>CAP)throw Error('UNBOUNDED_ALLOCATION_ATTEMPT');return alloc(size,...args);});
 return {file,proto,stat,allocations,get calls(){return statCalls},mockSizes(sizes){t.mock.method(proto,'stat',async function(...args){const actual=await stat.apply(this,args),size=sizes[statCalls++];return size===undefined?actual:{...actual,size};});},args:{file,key:Buffer.alloc(32),expectedTargetId:'unit'}};
}
test('growth between capacity check and allocation never allocates above package bound',async t=>{
 const f=await fixture(t);f.mockSizes([undefined,CAP+1]);
 await assert.rejects(openSnapshot(f.args));
 assert.equal(f.allocations.some(n=>n>CAP),false,'a second unchecked stat must not drive allocation');
});
for(const size of [CAP+1,Number.MAX_SAFE_INTEGER+1,Infinity,-1,12.5])test(`reject invalid package size ${size} before body allocation`,async t=>{
 const f=await fixture(t);f.mockSizes([size]);
 await assert.rejects(openSnapshot(f.args),/PACKAGE_CAPACITY/);
 assert.equal(f.allocations.some(n=>n>CAP),false);
});
test('size changes after bounded read fail closed before authentication',async t=>{
 const f=await fixture(t);f.mockSizes([undefined,1]);
 await assert.rejects(openSnapshot(f.args),/PACKAGE_CHANGED_DURING_READ/);
});
test('unchanged malformed self-authored package still fails authentication',async t=>{
 const f=await fixture(t);f.mockSizes([]);
 await assert.rejects(openSnapshot(f.args),/PACKAGE_AUTHENTICATION_FAILED_NO_TARGET_WRITE/);
 assert.equal(f.allocations.some(n=>n>CAP),false);
});
