import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { captureSnapshot, verifyRelease, FORWARD, REVERSE, REQUIRED_CLASSES, SUCCESSOR_FORWARD, SUCCESSOR_REVERSE, SUCCESSOR_CLASSES, CURRENT_FORWARD, CURRENT_REVERSE, CURRENT_CLASSES, LINEAGE_FORWARD, LINEAGE_REVERSE, LINEAGE_CLASSES, inspectReleaseMigrations } from './consultation-comparison-release.mjs';

const WORKSPACE = fileURLToPath(new URL('../../',import.meta.url));
const CLI = fileURLToPath(new URL('../verify-consultation-comparison-release.mjs',import.meta.url));
const OUTPUT = 'docs/evidence/consultation-comparison-release-20261007/package/manifest.json';
const BEFORE = 'evidence/before.json', AFTER = 'evidence/after.json', JAR = 'artifact/consultation.jar';
const APP = 'BOOT-INF/classes/static/app/';
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const javaHome = process.env.JAVA_HOME;
assert.ok(javaHome, 'Tests require managed JAVA_HOME; never download a JDK or skip checks.');

function write(root,ref,bytes) {
  const file = path.join(root,...ref.split('/'));
  fs.mkdirSync(path.dirname(file),{recursive:true}); fs.writeFileSync(file,bytes);
}
// Generated minimal ZIP/JAR, not a product build or business-result substitute.
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) { crc ^= byte; for(let i=0;i<8;i++) crc=(crc>>>1)^((crc&1)?0xedb88320:0); }
  return (crc ^ 0xffffffff) >>> 0;
}
function zip(files) {
  const locals=[],centrals=[]; let offset=0;
  for (const [ref,data] of files) {
    const name=Buffer.from(ref),body=Buffer.from(data),crc=crc32(body);
    const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50);local.writeUInt16LE(20,4);
    local.writeUInt32LE(crc,14);local.writeUInt32LE(body.length,18);local.writeUInt32LE(body.length,22);local.writeUInt16LE(name.length,26);
    const central=Buffer.alloc(46);central.writeUInt32LE(0x02014b50);central.writeUInt16LE(20,4);central.writeUInt16LE(20,6);
    central.writeUInt32LE(crc,16);central.writeUInt32LE(body.length,20);central.writeUInt32LE(body.length,24);central.writeUInt16LE(name.length,28);central.writeUInt32LE(offset,42);
    locals.push(local,name,body);centrals.push(central,name);offset+=local.length+name.length+body.length;
  }
  const cd=Buffer.concat(centrals),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(files.length,8);end.writeUInt16LE(files.length,10);end.writeUInt32LE(cd.length,12);end.writeUInt32LE(offset,16);
  return Buffer.concat([...locals,cd,end]);
}
function members() {
  const html=Buffer.from('<html><script type="module" src="/app/assets/consultation.js"></script></html>');
  const js=Buffer.from('// Isolated archive inspection fixture; no business behavior.\n');
  const assets={contract:'CONSULTATION_PRODUCT_BUNDLE/v1',entry:'consultation.html',files:[
    {path:'consultation.html',sha256:hash(html),bytes:html.length},
    {path:'assets/consultation.js',sha256:hash(js),bytes:js.length},
  ]};
  return [...REQUIRED_CLASSES.map(ref=>[ref,Buffer.from('cafebabe00000041','hex')]),
    [APP+'consultation.html',html],[APP+'assets/consultation.js',js],
    [APP+'consultation-product-assets.json',Buffer.from(JSON.stringify(assets))]];
}
function fixture(t) {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'comparison-release-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  for (const pin of [...FORWARD,...REVERSE,...CURRENT_REVERSE]) write(root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
  write(root,'frontend/src/main.tsx','// isolated negative input');
  write(root,'frontend/vite.config.ts','// isolated negative build config');
  write(root,'backend/Source.java','isolated source bytes'); write(root,'scripts/source.mjs','// isolated source');
  const f={root,files:members(),options:{workspace:root,jar:JAR,before:BEFORE,after:AFTER,javaHome,output:OUTPUT}};
  f.pack=()=>{const bytes=zip(f.files);write(root,JAR,bytes);f.options.jarSha256=hash(bytes);};
  f.snapshot=()=>{const bytes=JSON.stringify(captureSnapshot(root,f.options.generation));write(root,BEFORE,bytes);write(root,AFTER,bytes);};
  f.pack();f.snapshot();return f;
}
function rejected(f,code) {
  assert.equal(fs.existsSync(path.join(f.root,f.options.output)),false,'Use a fresh output for failure tests');
  assert.throws(()=>verifyRelease(f.options), code ? new RegExp(code) : undefined);
  assert.equal(fs.existsSync(path.join(f.root,f.options.output)),false,'No successful manifest is published by this failed check');
  const dir=path.dirname(path.join(f.root,f.options.output));
  if (fs.existsSync(dir)) assert.equal(fs.readdirSync(dir).some(n=>n.endsWith('.tmp')),false);
}

test('normal engineering package: actual jar tf, real fixed migration bytes, hashes/refs only',t=>{
  const f=fixture(t), manifest=verifyRelease(f.options);
  assert.equal(manifest.scope,'OFFLINE_ENGINEERING_ONLY');
  assert.equal(manifest.artifact.classes.length,7);
  assert.equal(manifest.migrations.forward.length,13);
  assert.deepEqual(manifest.migrations.reverse.map(p=>p.path.slice(7,11)),['U101','U100','U099','U098','U095','U094']);
  assert.equal(manifest.migrations.databaseState,'NOT_INSPECTED_OR_APPLIED');
  assert.equal(manifest.migrations.formalStatus,'NOT_VERIFIED');
  const output=fs.readFileSync(path.join(f.root,OUTPUT),'utf8');
  assert.ok(!output.includes('CREATE TABLE') && !output.includes('isolated source bytes') && !output.includes('<html>'));
  assert.equal(JSON.parse(output).artifact.artifact.sha256,f.options.jarSha256);
});
test('missingclasses: old jar lacks SchemaGuard',t=>{
  const f=fixture(t);f.files=f.files.filter(([n])=>!n.endsWith('ProductComparisonSchemaGuard.class'));f.pack();rejected(f,'MISSING_CLASSES');
});
test('wrongentry: generic index forbidden',t=>{
  const f=fixture(t);f.files.push([APP+'index.html','old general entry']);f.pack();rejected(f,'WRONG_ENTRY');
});
test('wrongentry: adjudication page forbidden even outside app',t=>{
  const f=fixture(t);f.files.push(['BOOT-INF/classes/static/adjudication.html','other domain']);f.pack();rejected(f,'WRONG_ENTRY');
});
test('badpath: traversal in jar reference',t=>{
  const f=fixture(t);f.options.jar='../outside.jar';rejected(f,'BAD_PATH');
});
test('badpath: unsafe member in actual archive',t=>{
  const f=fixture(t);f.files.push(['../outside','unsafe']);f.pack();rejected(f,'BAD_PATH');
});
test('badpath: linked input rejected',t=>{
  const f=fixture(t);const original=path.join(f.root,'artifact');const dest=path.join(f.root,'linked');
  fs.symlinkSync(original,dest,process.platform==='win32'?'junction':'dir');
  f.options.jar='linked/consultation.jar';rejected(f,'LINKED_PATH');
});
test('missingmigration: cannot replace exact V101 file',t=>{
  const f=fixture(t),pin=FORWARD.find(p=>p.path.includes('V101__'));fs.unlinkSync(path.join(f.root,pin.path));f.snapshot();rejected(f,'MISSING_FILE');
});
test('missingmigration: prerequisite V032 required',t=>{
  const f=fixture(t);fs.unlinkSync(path.join(f.root,FORWARD[0].path));f.snapshot();rejected(f,'MISSING_FILE');
});
test('hashdrift: migration pins cannot be supplied by client',t=>{
  const f=fixture(t);write(f.root,FORWARD[0].path,'CREATE TABLE altered (x INT);');f.snapshot();rejected(f,'MIGRATION_HASH_DRIFT');
});
test('hashdrift: reverse first-step changed refused despite fresh source snapshots',t=>{
  const f=fixture(t);write(f.root,REVERSE[0].path,'DROP TABLE con5_preparation;');f.snapshot();rejected(f,'ROLLBACK_HASH_DRIFT');
});
test('hashdrift: actual jar must match caller fixed SHA',t=>{
  const f=fixture(t);f.options.jarSha256='0'.repeat(64);rejected(f,'JAR_HASH_DRIFT');
});
test('hashdrift: before and after unequal',t=>{
  const f=fixture(t);const rows=captureSnapshot(f.root);rows[0].sha256='0'.repeat(64);write(f.root,AFTER,JSON.stringify(rows));rejected(f,'BUILD_INPUT_DRIFT');
});
test('hashdrift: post-build workspace changed',t=>{
  const f=fixture(t);write(f.root,'backend/Source.java','changed current bytes');rejected(f,'WORKSPACE_INPUT_DRIFT');
});
test('hashdrift: omitted/new source not hidden by equal manifests',t=>{
  const f=fixture(t);write(f.root,'scripts/new.mjs','// new source');rejected(f,'WORKSPACE_INPUT_DRIFT');
});
test('cannot declare fake jar entries as artifact',t=>{
  const f=fixture(t);write(f.root,JAR,REQUIRED_CLASSES.join('\n'));f.options.jarSha256=hash(fs.readFileSync(path.join(f.root,JAR)));rejected(f,'BAD_JAR');
});
test('caller entries are only corroboration; fake list cannot conceal missing class',t=>{
  const f=fixture(t);write(f.root,'evidence/entries.txt',f.files.map(([n])=>n).join('\n')+'\n');
  f.options.entries='evidence/entries.txt';f.files.shift();f.pack();rejected(f,'JAR_ENTRIES_DRIFT');
});
test('exact main-session jar tf entries supported',t=>{
  const f=fixture(t);const exe=path.join(javaHome,'bin',process.platform==='win32'?'jar.exe':'jar');
  const result=spawnSync(exe,['tf',path.join(f.root,JAR)],{encoding:'utf8',windowsHide:true});assert.equal(result.status,0);
  write(f.root,'evidence/entries.txt',result.stdout);f.options.entries='evidence/entries.txt';
  assert.equal(verifyRelease(f.options).artifact.suppliedEntries.sha256,hash(Buffer.from(result.stdout)));
});
test('asset byte drift against actual embedded assets manifest',t=>{
  const f=fixture(t);f.files=f.files.map(([n,b])=>[n,n.endsWith('consultation.js')?'changed asset':b]);f.pack();rejected(f,'ASSET_HASH_DRIFT');
});
test('missing jar cannot succeed',t=>{
  const f=fixture(t);fs.unlinkSync(path.join(f.root,JAR));rejected(f,'MISSING_FILE');
});
test('read error: unmanaged/missing JAVA_HOME never falls back to PATH',t=>{
  const f=fixture(t);f.options.javaHome=path.join(f.root,'absent-jdk');rejected(f);
});
test('corrupt unrelated payload CRC rejects jar',t=>{
  const f=fixture(t);f.files.push(['BOOT-INF/lib/dependency.jar','dependency bytes']);f.pack();
  const bytes=fs.readFileSync(path.join(f.root,JAR));const pos=bytes.indexOf(Buffer.from('dependency bytes'));bytes[pos]^=1;
  write(f.root,JAR,bytes);f.options.jarSha256=hash(bytes);rejected(f,'CORRUPT_JAR_ENTRY');
});
test('manifest accepts only path/hash snapshot rows, no client product claims',t=>{
  const f=fixture(t),rows=captureSnapshot(f.root);rows[0].approved=true;
  write(f.root,BEFORE,JSON.stringify(rows));write(f.root,AFTER,JSON.stringify(rows));rejected(f,'BAD_SNAPSHOT');
});
test('sha alias and ordering independent snapshots still prove actual complete byte set',t=>{
  const f=fixture(t);const rows=captureSnapshot(f.root).map(({path,sha256})=>({path,sha:sha256})).reverse();
  write(f.root,AFTER,JSON.stringify({files:rows}));assert.ok(verifyRelease(f.options));
});
test('CLI: existing successful evidence preserved; failed new output leaves no manifest',t=>{
  const f=fixture(t);const args=['--workspace',f.root,'--jar',JAR,'--jar-sha256',f.options.jarSha256,'--before',BEFORE,'--after',AFTER];
  let result=spawnSync(process.execPath,[CLI,...args],{encoding:'utf8',windowsHide:true});assert.equal(result.status,0,result.stderr);
  assert.equal(JSON.parse(result.stdout).scope,'OFFLINE_ENGINEERING_ONLY');
  const original=fs.readFileSync(path.join(f.root,OUTPUT)), originalSha=hash(original);
  fs.unlinkSync(path.join(f.root,JAR));result=spawnSync(process.execPath,[CLI,...args],{encoding:'utf8',windowsHide:true});
  assert.equal(result.status,1);assert.equal(JSON.parse(result.stderr).code,'EVIDENCE_OUTPUT_EXISTS');
  assert.equal(hash(fs.readFileSync(path.join(f.root,OUTPUT))),originalSha);
  const newOutput=OUTPUT.replace('manifest.json','failed-new.json');
  result=spawnSync(process.execPath,[CLI,...args,'--output',newOutput],{encoding:'utf8',windowsHide:true});
  assert.equal(result.status,1);assert.equal(JSON.parse(result.stderr).code,'MISSING_FILE');
  assert.equal(fs.existsSync(path.join(f.root,newOutput)),false);
  assert.deepEqual(fs.readFileSync(path.join(f.root,OUTPUT)),original);
});
test('CLI snapshot only captures byte references, generated target ignored',t=>{
  const f=fixture(t);write(f.root,'backend/target/not-source.txt','generated');
  const result=spawnSync(process.execPath,[CLI,'--workspace',f.root,'--snapshot'],{encoding:'utf8',windowsHide:true});
  assert.equal(result.status,0,result.stderr);assert.deepEqual(JSON.parse(result.stdout),captureSnapshot(f.root));
  assert.ok(!result.stdout.includes('generated'));
});

test('CLI four required file parameters, no caller jar hash needed, captures real artifact hash',t=>{
  const f=fixture(t);
  const result=spawnSync(process.execPath,[CLI,'--before',BEFORE,'--after',AFTER,'--jar',JAR,'--output',OUTPUT],
    {cwd:f.root,encoding:'utf8',windowsHide:true});
  assert.equal(result.status,0,result.stderr);
  const manifest=JSON.parse(fs.readFileSync(path.join(f.root,OUTPUT),'utf8'));
  assert.equal(manifest.artifact.artifact.sha256,f.options.jarSha256);
});
test('only approved source extensions included; required class source omission refused',t=>{
  const f=fixture(t);write(f.root,'backend/ComparisonSchemaGuard.java','// fixture source');f.snapshot();
  write(f.root,'backend/readme.md','not a declared build-input extension');assert.ok(verifyRelease(f.options));
  const rows=captureSnapshot(f.root).filter(p=>!p.path.endsWith('ComparisonSchemaGuard.java'));
  write(f.root,BEFORE,JSON.stringify(rows));write(f.root,AFTER,JSON.stringify(rows));
  f.options.output=OUTPUT.replace('manifest.json','omitted-source.json');rejected(f,'WORKSPACE_INPUT_DRIFT');
});
test('empty snapshots rejected',t=>{
  const f=fixture(t);write(f.root,BEFORE,'[]');write(f.root,AFTER,'[]');rejected(f,'BAD_SNAPSHOT');
});
test('duplicate snapshot paths rejected',t=>{
  const f=fixture(t),rows=captureSnapshot(f.root);rows.push(rows[0]);
  write(f.root,BEFORE,JSON.stringify(rows));write(f.root,AFTER,JSON.stringify(rows));rejected(f,'DUPLICATE_SNAPSHOT_PATH');
});

test('independent counterexample: existing evidence always refused, exact bytes and SHA unchanged',t=>{
  const f=fixture(t),original=Buffer.from('historical engineering evidence\r\n{"keep":"original bytes"}\n');
  write(f.root,OUTPUT,original);const originalSha=hash(original);
  assert.throws(()=>verifyRelease(f.options),/EVIDENCE_OUTPUT_EXISTS/);
  assert.deepEqual(fs.readFileSync(path.join(f.root,OUTPUT)),original);
  assert.equal(hash(fs.readFileSync(path.join(f.root,OUTPUT))),originalSha);
  f.options.jar='artifact/missing.jar';
  assert.throws(()=>verifyRelease(f.options),/EVIDENCE_OUTPUT_EXISTS/);
  assert.equal(hash(fs.readFileSync(path.join(f.root,OUTPUT))),originalSha);
  assert.deepEqual(fs.readdirSync(path.dirname(path.join(f.root,OUTPUT))),['manifest.json']);
});
test('real managed JDK jar creation: DEFLATE/data descriptors and BOOT-INF/lib payload accepted',t=>{
  const f=fixture(t),staging=path.join(f.root,'jdk-staging');fs.mkdirSync(staging);
  const nested=zip([['META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\n\r\n'],['example/resource.txt','nested dependency fixture']]);
  f.files.push(['BOOT-INF/lib/isolated-dependency.jar',nested]);
  for(const [name,bytes] of f.files) write(staging,name,bytes);
  const exe=path.join(javaHome,'bin',process.platform==='win32'?'jar.exe':'jar');
  const result=spawnSync(exe,['--create','--file',path.join(f.root,JAR),'-C',staging,'.'],{encoding:'utf8',windowsHide:true});
  assert.equal(result.status,0,result.stderr);
  const bytes=fs.readFileSync(path.join(f.root,JAR));
  // Inspect the real JDK-created central directory, not an assumed declaration.
  const end=bytes.length-22;assert.equal(bytes.readUInt32LE(end),0x06054b50);
  let offset=bytes.readUInt32LE(end+16),deflated=0,descriptors=0,library=false;
  for(let i=0;i<bytes.readUInt16LE(end+10);i++){
    assert.equal(bytes.readUInt32LE(offset),0x02014b50);
    if(bytes.readUInt16LE(offset+10)===8)deflated++;
    if(bytes.readUInt16LE(offset+8)&8)descriptors++;
    const length=bytes.readUInt16LE(offset+28),name=bytes.subarray(offset+46,offset+46+length).toString();
    if(name==='BOOT-INF/lib/isolated-dependency.jar')library=true;
    offset+=46+length+bytes.readUInt16LE(offset+30)+bytes.readUInt16LE(offset+32);
  }
  assert.ok(deflated>0,'Actual DEFLATE members');assert.ok(descriptors>0,'Actual bit-3 data descriptor members');assert.ok(library);
  f.options.jarSha256=hash(bytes);assert.ok(verifyRelease(f.options));
});
test('stored BOOT-INF/lib nested jar remains an opaque validated payload (Boot layout)',t=>{
  const f=fixture(t);f.files.push(['BOOT-INF/lib/isolated-stored.jar',zip([['resource.txt','nested jar fixture']])]);f.pack();
  assert.ok(verifyRelease(f.options));
});

test('successor rejects magic-only fake classes; fixture is never positive release evidence',t=>{
  const f=fixture(t);f.options.generation='SUCCESSOR_WITH_ISOLATION_V1';
  for(const pin of SUCCESSOR_FORWARD)write(f.root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
  for(const name of SUCCESSOR_CLASSES.filter(n=>!REQUIRED_CLASSES.includes(n)))f.files.push([name,Buffer.from('cafebabe00000041','hex')]);
  f.pack();f.snapshot();rejected(f,'BAD_CLASS_STRUCTURE');
  assert.equal(FORWARD.length,13);assert.equal(SUCCESSOR_FORWARD.length,3);
});
for(const omit of ['ProductHistoricalResultIsolation','ProductTitleBuildProvenance','ProductComparisonStartApplication','ProductComparisonStartHandoffGuard','ProductComparisonStartReceiptChecks'])
  test(`successor refuses missing ${omit} even when all A classes exist`,t=>{
    const f=fixture(t);f.options.generation='SUCCESSOR_WITH_ISOLATION_V1';
    for(const pin of SUCCESSOR_FORWARD)write(f.root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
    for(const name of SUCCESSOR_CLASSES.filter(n=>!REQUIRED_CLASSES.includes(n)&&!n.endsWith('/'+omit+'.class')))f.files.push([name,Buffer.from('cafebabe00000041','hex')]);
    f.pack();f.snapshot();rejected(f,'MISSING_CLASSES');
  });
test('successor refuses drifted V104 and unknown generation without publishing',t=>{
  const f=fixture(t);f.options.generation='SUCCESSOR_WITH_ISOLATION_V1';
  for(const pin of SUCCESSOR_FORWARD)write(f.root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
  write(f.root,SUCCESSOR_FORWARD.at(-1).path,'-- not approved V104');f.snapshot();rejected(f,'MIGRATION_HASH_DRIFT');
  f.options.generation='caller-supplied';rejected(f,'UNKNOWN_RELEASE_GENERATION');
});
test('a newer package cannot choose old A generation to evade successor guards',t=>{
  const f=fixture(t);f.files.push(['BOOT-INF/classes/cn/xinji/agentplatform/security/product/ProductComparisonStartApplication.class',Buffer.from('cafebabe00000041','hex')]);
  f.pack();rejected(f,'SUCCESSOR_GENERATION_REQUIRED');
});
test('successor source coverage includes TSX and config, excludes generated output',t=>{
  const f=fixture(t); const generation='SUCCESSOR_WITH_ISOLATION_V1';
  const before=captureSnapshot(f.root,generation);
  assert.ok(before.some(r=>r.path==='frontend/src/main.tsx'));
  assert.ok(before.some(r=>r.path==='frontend/vite.config.ts'));
  assert.ok(!captureSnapshot(f.root).some(r=>r.path.startsWith('frontend/')));
  write(f.root,'frontend/dist/app.js','generated');write(f.root,'frontend/tsconfig.tsbuildinfo','generated');
  assert.deepEqual(captureSnapshot(f.root,generation),before);
  write(f.root,'frontend/src/main.tsx','changed');assert.notDeepEqual(captureSnapshot(f.root,generation),before);
});
for(const mode of ['omitted','drifted']) test(`successor rejects ${mode} frontend snapshot before JAR inspection`,t=>{
  const f=fixture(t);f.options.generation='SUCCESSOR_WITH_ISOLATION_V1';f.snapshot();
  if(mode==='omitted') {const rows=captureSnapshot(f.root,f.options.generation).filter(r=>!r.path.startsWith('frontend/'));write(f.root,BEFORE,JSON.stringify(rows));write(f.root,AFTER,JSON.stringify(rows));}
  else write(f.root,'frontend/vite.config.ts','changed config');
  rejected(f,'WORKSPACE_INPUT_DRIFT');
});

for(const damage of ['truncated','corrupt-pool','wrong-name']) test(`successor rejects actual javac class ${damage}, no fake success`,t=>{
  const f=fixture(t);f.options.generation='SUCCESSOR_WITH_ISOLATION_V1';
  for(const pin of SUCCESSOR_FORWARD)write(f.root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
  for(const name of SUCCESSOR_CLASSES.filter(n=>!REQUIRED_CLASSES.includes(n)))f.files.push([name,Buffer.from('cafebabe00000041','hex')]);
  const first=REQUIRED_CLASSES[0];const name=path.basename(first,'.class');
  const realName=damage==='wrong-name' ? 'WrongName' : name;
  write(f.root,'compile/'+realName+'.java',`package cn.xinji.agentplatform.security.product; public class ${realName} { public int value(){return 1;} }`);
  const result=spawnSync(path.join(javaHome,'bin',process.platform==='win32'?'javac.exe':'javac'),['-d',path.join(f.root,'compiled'),path.join(f.root,'compile',realName+'.java')],{encoding:'utf8',windowsHide:true,timeout:30000});
  assert.equal(result.status,0,result.stderr);
  let bytes=fs.readFileSync(path.join(f.root,'compiled/cn/xinji/agentplatform/security/product/'+realName+'.class'));
  if(damage==='truncated') bytes=bytes.subarray(0,bytes.length-7);
  if(damage==='corrupt-pool') {bytes=Buffer.from(bytes);bytes[10]=255;}
  f.files=f.files.map(([ref,body])=>[ref,ref===first?bytes:body]);f.pack();f.snapshot();
  rejected(f,damage==='wrong-name'?'CLASS_NAME_MISMATCH':'BAD_CLASS_STRUCTURE');
});
test('successor freezes source resource bytes as well as TSX',t=>{
  const f=fixture(t);write(f.root,'frontend/src/icon.svg','<svg/>');
  assert.ok(captureSnapshot(f.root,'SUCCESSOR_WITH_ISOLATION_V1').some(r=>r.path==='frontend/src/icon.svg'));
});

// Current generation coverage, not a production build or restore acceptance.
function currentFixture(t) {
  const f=fixture(t);f.options.generation='SUCCESSOR_INPUT_HANDOFF_V2';
  for(const pin of CURRENT_FORWARD)write(f.root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
  for(const name of CURRENT_CLASSES.filter(n=>!REQUIRED_CLASSES.includes(n)))f.files.push([name,Buffer.from('cafebabe00000041','hex')]);
  f.pack();f.snapshot();return f;
}
test('current inventory validates exact V102-V106 without SQL or a release manifest',t=>{
  const f=currentFixture(t);const result=inspectReleaseMigrations(f.options);
  assert.equal(result.scope,'MIGRATION_INVENTORY_ONLY_NOT_RELEASE_APPROVAL');
  assert.equal(result.databaseState,'NOT_INSPECTED_OR_APPLIED');assert.equal(result.formalStatus,'NOT_VERIFIED');
  assert.deepEqual(result.forward.slice(-5).map(x=>path.basename(x.path).slice(0,4)),['V102','V103','V104','V105','V106']);
  assert.deepEqual(result.reverse.slice(0,3).map(x=>path.basename(x.path).slice(0,4)),['U105','U103','U102']);
  assert.ok(result.reverse.slice(0,3).every(x=>x.nonemptyRollback==='REFUSED_BY_HISTORY_GUARD_NOT_EXECUTED'));
  assert.doesNotMatch(JSON.stringify(result),/\b(?:DROP|ALTER|DECLARE|SELECT)\b/i);
  assert.equal(result.forward.length,18);assert.equal(FORWARD.length,13);assert.equal(SUCCESSOR_FORWARD.length,3);
  assert.ok(result.forward.at(-1).existingPrerequisites.some(x=>x.includes('CK_CON4_SCOPE_QUERY_GRANT')));
  assert.equal(fs.existsSync(path.join(f.root,OUTPUT)),false);
});
test('successor and current generations bind only their approved rollback sets',t=>{
  const f=currentFixture(t);
  const successor=inspectReleaseMigrations({...f.options,generation:'SUCCESSOR_WITH_ISOLATION_V1'});
  assert.deepEqual(successor.reverse.slice(0,2).map(x=>path.basename(x.path).slice(0,4)),['U103','U102']);
  assert.ok(!successor.reverse.some(x=>x.path.includes('U105')));
  assert.equal(SUCCESSOR_REVERSE.length,2);assert.equal(CURRENT_REVERSE.length,3);
});
for(const number of ['U102','U103','U105']) test(`current generation refuses drifted or missing ${number}`,t=>{
  const f=currentFixture(t),pin=CURRENT_REVERSE.find(x=>path.basename(x.path).startsWith(number));
  write(f.root,pin.path,'-- rollback guard removed');f.snapshot();rejected(f,'ROLLBACK_HASH_DRIFT');
  fs.unlinkSync(path.join(f.root,pin.path));assert.throws(()=>inspectReleaseMigrations(f.options));
});
for(const number of ['V105','V106']) test(`current generation refuses drifted ${number}`,t=>{
  const f=currentFixture(t);const pin=CURRENT_FORWARD.find(x=>path.basename(x.path).startsWith(number));
  write(f.root,pin.path,'-- wrong migration bytes');f.snapshot();rejected(f,'MIGRATION_HASH_DRIFT');
});
for(const number of ['V105','V106']) test(`current inventory refuses missing ${number} without producing a manifest`,t=>{
  const f=currentFixture(t);const pin=CURRENT_FORWARD.find(x=>path.basename(x.path).startsWith(number));
  fs.unlinkSync(path.join(f.root,pin.path));assert.throws(()=>inspectReleaseMigrations(f.options));
  assert.equal(fs.existsSync(path.join(f.root,OUTPUT)),false);
});
for(const omit of ['ProductComparisonHandoffController','ProductComparisonHandoffStore','ProductComparisonDocumentCatalogController','ProductRetrievalWorkspaceConfiguration'])
  test(`current package refuses missing ${omit}`,t=>{
    const f=currentFixture(t);f.files=f.files.filter(([name])=>!name.endsWith('/'+omit+'.class'));
    f.pack();f.snapshot();rejected(f,'MISSING_CLASSES');
  });
for(const generation of ['FIXED_A_V1','SUCCESSOR_WITH_ISOLATION_V1']) test(`current package cannot masquerade as ${generation}`,t=>{
  const f=currentFixture(t);f.options.generation=generation;f.snapshot();rejected(f,'CURRENT_GENERATION_REQUIRED');
});
test('current package still rejects magic-only fake class structures',t=>{
  const f=currentFixture(t);rejected(f,'BAD_CLASS_STRUCTURE');
});
test('current migration inventory CLI uses real scripts and reports no target inspection',()=>{
  const r=spawnSync(process.execPath,[CLI,'--workspace',WORKSPACE,'--migrations','--generation','SUCCESSOR_INPUT_HANDOFF_V2'],{encoding:'utf8',windowsHide:true});
  assert.equal(r.status,0,r.stderr);const value=JSON.parse(r.stdout);
  assert.equal(value.generation,'SUCCESSOR_INPUT_HANDOFF_V2');assert.equal(value.forward.length,18);
  assert.equal(value.databaseState,'NOT_INSPECTED_OR_APPLIED');assert.equal(value.scope,'MIGRATION_INVENTORY_ONLY_NOT_RELEASE_APPROVAL');
});
for(const extra of [['--snapshot'],['--jar','artifact/a.jar'],['--output',OUTPUT]])
 test(`migration-only CLI refuses conflicting ${extra[0]}`,()=>{
  const r=spawnSync(process.execPath,[CLI,'--workspace',WORKSPACE,'--migrations','--generation','SUCCESSOR_INPUT_HANDOFF_V2',...extra],{encoding:'utf8',windowsHide:true});
  assert.equal(r.status,1);assert.equal(JSON.parse(r.stderr).code,'BAD_ARGUMENTS');
 });
test('migration-only CLI refuses unknown caller-controlled generation',()=>{
 const r=spawnSync(process.execPath,[CLI,'--workspace',WORKSPACE,'--migrations','--generation','invented'],{encoding:'utf8',windowsHide:true});
 assert.equal(r.status,1);assert.equal(JSON.parse(r.stderr).code,'UNKNOWN_RELEASE_GENERATION');
});

function lineageFixture(t) {
  const f=currentFixture(t);f.options.generation='SUCCESSOR_MATRIX_LINEAGE_V3';
  for(const pin of [...LINEAGE_FORWARD,...LINEAGE_REVERSE])write(f.root,pin.path,fs.readFileSync(path.join(WORKSPACE,pin.path)));
  for(const name of LINEAGE_CLASSES.filter(n=>!CURRENT_CLASSES.includes(n)))f.files.push([name,Buffer.from('cafebabe00000041','hex')]);
  f.pack();f.snapshot();return f;
}
test('lineage inventory pins V108 and refuses history before both drops, without executing SQL',t=>{
  const f=lineageFixture(t),result=inspectReleaseMigrations(f.options);
  assert.equal(result.forward.length,19);assert.equal(result.reverse[0].path,LINEAGE_REVERSE.at(-1).path);
  assert.equal(result.databaseState,'NOT_INSPECTED_OR_APPLIED');assert.equal(result.formalStatus,'NOT_VERIFIED');
  assert.deepEqual(result.forward.at(-1).dependencies,['db/dm8/V100__product_comparison_matrix.sql']);
  const sql=fs.readFileSync(path.join(f.root,LINEAGE_REVERSE.at(-1).path),'utf8');
  for(const table of ['con5_matrix_dependency_material','con5_matrix_dependency_original'])
    assert.ok(sql.indexOf('SELECT COUNT(*) INTO history_count FROM '+table)<sql.indexOf('DROP TABLE'));
  assert.equal((sql.match(/RAISE_APPLICATION_ERROR/g)||[]).length,2);
  assert.equal(fs.existsSync(path.join(f.root,OUTPUT)),false);
  assert.equal(inspectReleaseMigrations({...f.options,generation:'SUCCESSOR_INPUT_HANDOFF_V2'}).forward.length,18);
});
for(const pin of [LINEAGE_FORWARD.at(-1),LINEAGE_REVERSE.at(-1)])test(`lineage rejects drift and absence of ${path.basename(pin.path)}`,t=>{
  const f=lineageFixture(t);write(f.root,pin.path,'-- altered original');
  assert.throws(()=>inspectReleaseMigrations(f.options),new RegExp(pin.path.includes('/U')?'ROLLBACK_HASH_DRIFT':'MIGRATION_HASH_DRIFT'));
  fs.unlinkSync(path.join(f.root,pin.path));assert.throws(()=>inspectReleaseMigrations(f.options),/MISSING_FILE/);
});
for(const generation of ['FIXED_A_V1','SUCCESSOR_WITH_ISOLATION_V1','SUCCESSOR_INPUT_HANDOFF_V2'])
 test(`lineage helpers cannot masquerade as ${generation}`,t=>{
  const f=lineageFixture(t);f.options.generation=generation;f.snapshot();rejected(f,'LINEAGE_GENERATION_REQUIRED');
 });
for(const name of ['ProductComparisonLineageFacts','ProductComparisonLineageCodec','ProductComparisonLineageStore'])
 test(`lineage refuses missing ${name}`,t=>{
  const f=lineageFixture(t);f.files=f.files.filter(([ref])=>!ref.endsWith('/'+name+'.class'));
  f.pack();f.snapshot();rejected(f,'MISSING_CLASSES');
 });
test('lineage requires real class structure, not just archive presence',t=>{
  const f=lineageFixture(t);rejected(f,'BAD_CLASS_STRUCTURE');
});
