import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,unlink,rmdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {createCipheriv,createDecipheriv,randomBytes} from 'node:crypto';
import {hash,canonical,inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {sealSnapshot,openSnapshot} from './single-database-lifecycle-snapshot.mjs';

// External Java/native/fence ports are controlled doubles. Files and AES-GCM are real;
// this does not prove DM8, actual source permission, writer admission or activation.
const targetId='a'.repeat(64),schemas=['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'];
const accounts=schemas.flatMap(s=>[s,s+'_SVC']);
const epoch=iso=>{const ms=BigInt(Date.parse(iso));return `${ms/1000n}.${String(ms%1000n).padStart(3,'0')}`;};
const stdoutFor=(summary,reverse=false)=>{
 const value=reverse?{summary,sourceAndFenceBindingProven:false,businessActivation:false,mode:'SOURCE_ONLY_WAITING'}
  :{mode:'SOURCE_ONLY_WAITING',businessActivation:false,sourceAndFenceBindingProven:false,summary};
 return JSON.stringify(value).replace(`"validUntil":${JSON.stringify(summary.validUntil)}`,`"validUntil":${epoch(summary.validUntil)}`)
  .replace(`"executionUntil":${JSON.stringify(summary.executionUntil)}`,`"executionUntil":${epoch(summary.executionUntil)}`);
};
const humanTables={CONPROD:['CON6_HUMAN_PACKAGE','CON6_HUMAN_HANDLING','CON6_HUMAN_EVENT','CON6_HUMAN_RECEIPT','CON6_HUMAN_CLEANUP_SCOPE','CON6_HUMAN_CLEANUP_EVENT'],CONAUTH:['ID6_HUMAN_CLEANUP_SCOPE']};
const typedRow=table=>({table,count:1,contentSha256:hash(`controlled typed rows for ${table}`),digestMethod:'SORTED_TYPED_COLUMN_ROW_SHA256/v1'});
const inventory=()=>({format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId,actualJdbc:true,complete:true,errors:[],
 schemas:schemas.map(schema=>({schema,objects:(humanTables[schema]??[]).map(name=>({name,type:'TABLE'})),columns:[],constraints:[],foreignKeys:[],indexes:[],sequences:[],views:[],triggers:[],dependencies:[],rows:(humanTables[schema]??[]).map(typedRow)})),
 accounts:accounts.map(name=>({name,exists:true,systemPrivileges:[],roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name],effectiveGrants:[]}))});

async function fixture(t) {
 const dir=await mkdtemp(resolve(tmpdir(),'human-waiting-snapshot-')),output=resolve(dir,'snapshot'),original=resolve(dir,'policy.json');
 await writeFile(original,'controlled approved policy bytes');
 const now=Date.now(),iso=n=>new Date(n).toISOString(),backupUntil=iso(now+60_000),executionUntil=iso(now+30_000),long=iso(now+300_000);
 const files=[{ref:'policy.json',path:original,sha256:hash(await readFile(original))}],configurationSha256='b'.repeat(64),events=[];
 let released=0,exports=0;
 const lease={assertHeld:async()=>events.push('held'),release:async()=>{events.push('release');released++;}};
 const summary={packages:1,scopes:1,closureSha:'c'.repeat(64),businessActivation:false,validUntil:backupUntil,executionUntil};
 const stdout=stdoutFor(summary);
 const binding={format:'HUMAN_WAITING_SOURCE_BINDING/v1',sourceTargetId:targetId,
  source:{jdbcUrl:'jdbc:dm://127.0.0.1:5493',deploymentJdbcUrl:'jdbc:dm://dm-source:5236',endpointProof:{containerId:targetId,containerPort:5236,hostIp:'127.0.0.1',hostPort:5493,networkId:'1'.repeat(64),alias:'dm-source'},schema:'CONPROD',account:'CONPROD_SVC',authoritySchema:'CONAUTH',authorityAccount:'CONAUTH_SVC',issuer:'https://consultation.example.test',evidenceRoot:dir},
  configurationSha256,summary,backupUntil,executionUntil,originalStdout:stdout,stdoutSha256:hash(stdout),files,
  fileSetSha256:hash(canonical(files)),javaSha256:'d'.repeat(64),jarSha256:'e'.repeat(64),policiesSha256:'f'.repeat(64),
  workerProof:{worker:{pid:123,parentId:100,createdAt:iso(now-1000),commandSha256:'1'.repeat(64),invocationSha256:'2'.repeat(64)},
   leaseProof:{tableSetSha256:'3'.repeat(64),tableCount:7}}};
 const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId,action:'snapshot',
  owner:'UNIT',source:'controlled component approval',approvedAt:iso(now-1000),expiresAt:long,
  writers:[{id:'unit-database-and-file-writer',owner:'UNIT',responsibilitySource:'controlled fixture'}],
  files:files.map(({path,sha256})=>({path,sha256,licenseReference:'OWNED_COMPONENT',useUntil:long})),
  databaseRights:{owner:'UNIT',licenseReference:'OWNED_COMPONENT',purpose:'component snapshot',schemas,useUntil:long},
  currentMigration:false,connectionSwitch:false,deleteExisting:false,currentAuthorityOutsideBackup:true,
  exportMethod:'CONTROLLED_NATIVE_PORT_NOT_DM8',scope:'UNIT_ONLY',humanWaitingConfigurationSha256:configurationSha256};
 const key=randomBytes(32),args={approval,key,output,files:files.map(({path,sha256})=>({path,sha256})),schemas,accounts,now,
  acquireFence:async()=>{events.push('acquire');return lease;},
  inventory:async()=>{events.push('inventory');return inventory();},
  exportDatabase:async()=>{events.push('export');exports++;return Buffer.from('controlled native bytes');},
  humanWaiting:async({lease:held,phase})=>{assert.equal(held,lease);events.push(phase);return structuredClone(binding);}};
 t.after(async()=>{for(const file of [output,original])await unlink(file).catch(e=>{if(e.code!=='ENOENT')throw e;});await rmdir(dir);key.fill(0);});
 return {args,key,output,original,binding,events,now,get released(){return released;},get exports(){return exports;},
  open:()=>openSnapshot({file:output,key,expectedTargetId:targetId})};
}

test('WAITING source checks surround inventory/export under one lease and authenticate both observations',async t=>{
 const f=await fixture(t),receipt=await sealSnapshot(f.args),opened=await f.open();
 try {
  const actions=f.events.filter(e=>e!=='held');
  assert.equal(actions[0],'acquire'); assert.equal(actions[1],'before');
  assert.ok(actions.indexOf('before')<actions.indexOf('inventory'));
  assert.ok(actions.indexOf('export')<actions.indexOf('after'));assert.equal(actions.at(-1),'release');
  assert.equal(f.released,1);assert.equal(f.exports,1);
  assert.equal(receipt.metadata.expiresAt,f.binding.backupUntil);
  assert.equal(receipt.metadata.humanWaiting.format,'HUMAN_WAITING_SNAPSHOT/v1');
  assert.equal(receipt.metadata.humanWaiting.inventorySha256,inventoryDigest(inventory()));
  assert.equal(opened.content.humanWaiting.before.originalStdout,f.binding.originalStdout);
  assert.equal(opened.content.humanWaiting.after.stdoutSha256,hash(f.binding.originalStdout));
  assert.equal(opened.content.humanWaiting.inventorySha256,receipt.metadata.inventorySha256);
  assert.equal(receipt.fullThreeDomainProductRecoveryProven,false);
 } finally {opened.native.fill(0);}
});

test('different stdout key ordering preserves semantic equivalence and both original hashes',async t=>{
 const f=await fixture(t);f.args.humanWaiting=async({phase})=>{const b=structuredClone(f.binding);
  if(phase==='after'){b.originalStdout=stdoutFor(b.summary,true);b.stdoutSha256=hash(b.originalStdout);}return b;};
 await sealSnapshot(f.args);const opened=await f.open();
 try {assert.notEqual(opened.content.humanWaiting.before.stdoutSha256,opened.content.humanWaiting.after.stdoutSha256);}
 finally {opened.native.fill(0);}
});

test('decimal epoch nanoseconds never round the authenticated save deadline upward',async t=>{
 const f=await fixture(t);
 f.binding.backupUntil=f.binding.summary.validUntil=new Date(Math.floor(f.now/1000)*1000+60_999).toISOString();
 f.binding.originalStdout=stdoutFor(f.binding.summary).replace(`"validUntil":${epoch(f.binding.backupUntil)}`,
  `"validUntil":${epoch(f.binding.backupUntil)}999999`);
 f.binding.stdoutSha256=hash(f.binding.originalStdout);
 const receipt=await sealSnapshot(f.args);
 assert.equal(receipt.metadata.expiresAt,f.binding.backupUntil);
 assert.equal(receipt.metadata.humanWaiting.backupUntil,f.binding.backupUntil);
 const opened=await f.open();opened.native.fill(0);
});

test('historical inspection execution expiry does not expire a still-retained authenticated package',async t=>{
 const f=await fixture(t);await sealSnapshot(f.args);
 const opened=await openSnapshot({file:f.output,key:f.key,expectedTargetId:targetId,now:Date.parse(f.binding.executionUntil)});
 try {assert.equal(opened.metadata.expiresAt,f.binding.backupUntil);assert.equal(opened.content.humanWaiting.before.summary.businessActivation,false);}
 finally {opened.native.fill(0);}
});

for(const [name,mutate] of [
 ['missing typed content hash',i=>delete i.schemas[0].rows[0].contentSha256],
 ['wrong typed digest method',i=>i.schemas[0].rows[0].digestMethod='COUNT_ONLY'],
 ['negative row count',i=>i.schemas[0].rows[0].count=-1],
 ['fractional row count',i=>i.schemas[0].rows[0].count=1.5],
 ['missing human table',i=>{i.schemas[0].objects.shift();i.schemas[0].rows.shift();}],
 ['missing independent human scope',i=>{i.schemas[1].objects=[];i.schemas[1].rows=[];}],
 ['package count differs from Java summary',i=>i.schemas[0].rows[0].count=2],
 ['primary scope count differs from Java summary',i=>i.schemas[0].rows.find(r=>r.table==='CON6_HUMAN_CLEANUP_SCOPE').count=2],
 ['independent scope count differs from Java summary',i=>i.schemas[1].rows[0].count=2],
 ['other domain table without digest',i=>{i.schemas[2].objects.push({name:'OTHER_DOMAIN_TABLE',type:'BASE TABLE'});i.schemas[2].rows.push({table:'OTHER_DOMAIN_TABLE',count:0});}],
 ['catalog table without matching row',i=>i.schemas[2].objects.push({name:'OTHER_DOMAIN_TABLE',type:'TABLE'})],
 ['row without matching catalog table',i=>i.schemas[2].rows.push(typedRow('UNCATALOGUED_TABLE'))],
 ['duplicate table row',i=>i.schemas[0].rows.push(structuredClone(i.schemas[0].rows[0]))],
])test(`human seal rejects ${name} before export and releases fence`,async t=>{
 const f=await fixture(t);f.args.inventory=async()=>{const value=inventory();mutate(value);return value;};
 await assert.rejects(sealSnapshot(f.args));assert.equal(f.exports,0);assert.equal(f.released,1);
 await assert.rejects(readFile(f.output),{code:'ENOENT'});
});

test('typed BASE TABLE in another domain participates without requiring human data there',async t=>{
 const f=await fixture(t);f.args.inventory=async()=>{const value=inventory();
  value.schemas[2].objects.push({name:'OTHER_DOMAIN_TABLE',type:'BASE TABLE'});
  value.schemas[2].rows.push({...typedRow('OTHER_DOMAIN_TABLE'),count:0});return value;};
 await sealSnapshot(f.args);const opened=await f.open();
 try {assert.equal(opened.content.inventory.schemas[2].rows[0].count,0);}
 finally {opened.native.fill(0);}
});

for(const [name,mutate] of [
 ['closure',b=>b.summary.closureSha='9'.repeat(64)],['target',b=>b.sourceTargetId='9'.repeat(64)],
 ['configuration',b=>b.configurationSha256='9'.repeat(64)],['jar',b=>b.jarSha256='9'.repeat(64)],
 ['java',b=>b.javaSha256='9'.repeat(64)],['policy',b=>b.policiesSha256='9'.repeat(64)],
 ['worker',b=>b.workerProof.worker.pid=456],['lease',b=>b.workerProof.leaseProof.tableSetSha256='9'.repeat(64)],
 ['source schema',b=>b.source.schema='OTHER'],['files',b=>b.files=[]],
])test(`changed ${name} after export refuses seal and releases lease`,async t=>{
 const f=await fixture(t);f.args.humanWaiting=async({phase})=>{const b=structuredClone(f.binding);if(phase==='after')mutate(b);return b;};
 await assert.rejects(sealSnapshot(f.args));assert.equal(f.released,1);assert.equal(f.exports,1);
 await assert.rejects(readFile(f.output),{code:'ENOENT'});
});

for(const [name,mutate] of [
 ['zero packages',b=>b.summary.packages=0],['negative packages',b=>b.summary.packages=-1],
 ['fractional packages',b=>b.summary.packages=1.5],['business activation',b=>b.summary.businessActivation=true],
 ['expired execution',b=>b.executionUntil=b.summary.executionUntil='2000-01-01T00:00:00.000Z'],
 ['expired backup',b=>b.backupUntil=b.summary.validUntil='2000-01-01T00:00:00.000Z'],
 ['submillisecond binding',b=>b.backupUntil=b.summary.validUntil='2090-01-01T00:00:00.000000001Z'],
 ['stdout digest',b=>b.stdoutSha256='0'.repeat(64)],['file set digest',b=>b.fileSetSha256='0'.repeat(64)],
])test(`invalid initial ${name} cannot export`,async t=>{
 const f=await fixture(t);mutate(f.binding);await assert.rejects(sealSnapshot(f.args));
 assert.equal(f.exports,0);assert.equal(f.released,1);await assert.rejects(readFile(f.output),{code:'ENOENT'});
});

for(const phase of ['before','after'])test(`inspector ${phase} failure releases exact lease without publishing`,async t=>{
 const f=await fixture(t);f.args.humanWaiting=async input=>{if(input.phase===phase)throw Error('CONTROLLED_INSPECTOR_FAILURE');return structuredClone(f.binding);};
 await assert.rejects(sealSnapshot(f.args),/CONTROLLED_INSPECTOR_FAILURE/);assert.equal(f.released,1);
 assert.equal(f.exports,phase==='before'?0:1);await assert.rejects(readFile(f.output),{code:'ENOENT'});
});

test('approval must pin the inspector configuration before native export',async t=>{
 const f=await fixture(t);delete f.args.approval.humanWaitingConfigurationSha256;
 await assert.rejects(sealSnapshot(f.args));assert.equal(f.exports,0);
});

test('deadline passing at final held check cannot publish human protection',async t=>{
 const f=await fixture(t);let expired=false;const actual=f.args.humanWaiting;
 f.args.humanWaiting=async input=>{const b=await actual(input);if(input.phase==='after')expired=true;return b;};
 const originalNow=Date.now;
 t.mock.method(Date,'now',()=>expired?Date.parse(f.binding.executionUntil):originalNow());
 await assert.rejects(sealSnapshot(f.args));assert.equal(f.released,1);await assert.rejects(readFile(f.output),{code:'ENOENT'});
});

// Reauthenticate controlled component content with its own key to exercise semantic
// binding checks independently of the cipher's generic corrupt-byte rejection.
async function rewriteAuthenticated(f,mutate) {
 const bytes=await readFile(f.output),size=bytes.readUInt32BE(8),header=bytes.subarray(12,12+size),start=12+size;
 const d=createDecipheriv('aes-256-gcm',f.key,bytes.subarray(start,start+12));d.setAAD(header);d.setAuthTag(bytes.subarray(start+12,start+28));
 const plain=Buffer.concat([d.update(bytes.subarray(start+28)),d.final()]);
 const metadata=JSON.parse(header),content=JSON.parse(plain);mutate(metadata,content);plain.fill(0);
 const nextHeader=Buffer.from(canonical(metadata)),nonce=randomBytes(12),cipher=createCipheriv('aes-256-gcm',f.key,nonce);cipher.setAAD(nextHeader);
 const encrypted=Buffer.concat([cipher.update(Buffer.from(JSON.stringify(content))),cipher.final()]),prefix=Buffer.alloc(12);
 prefix.write('XJSNAP02');prefix.writeUInt32BE(nextHeader.length,8);
 await writeFile(f.output,Buffer.concat([prefix,nextHeader,nonce,cipher.getAuthTag(),encrypted]));
}

for(const [name,mutate] of [
 ['missing authenticated body',(m,c)=>delete c.humanWaiting],
 ['missing authenticated header',(m)=>delete m.humanWaiting],
 ['inventory link',(m,c)=>c.humanWaiting.inventorySha256='0'.repeat(64)],
 ['binding digest',(m)=>m.humanWaiting.bindingSha256='0'.repeat(64)],
 ['original stdout',(m,c)=>c.humanWaiting.before.originalStdout+=' '],
 ['package expiry beyond human window',(m)=>m.expiresAt=new Date(Date.parse(m.expiresAt)+1000).toISOString()],
])test(`authenticated human ${name} mismatch cannot open`,async t=>{
 const f=await fixture(t);await sealSnapshot(f.args);await rewriteAuthenticated(f,mutate);await assert.rejects(f.open());
});

test('ordinary legacy snapshot without human inspector remains readable without WAITING claim',async t=>{
 const f=await fixture(t);delete f.args.humanWaiting;delete f.args.approval.humanWaitingConfigurationSha256;
 const receipt=await sealSnapshot(f.args),opened=await f.open();
 try {assert.equal(receipt.metadata.humanWaiting,undefined);assert.equal(opened.content.humanWaiting,undefined);assert.equal(f.exports,1);}
 finally {opened.native.fill(0);}
});
