import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rm,symlink,unlink} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {createHumanWaitingInspector,parseHumanWaitingStdout} from './single-database-human-waiting-source.mjs';
const AT=Date.parse('2026-10-13T08:00:00.000Z'),iso=n=>new Date(n).toISOString();
const raw=(changes={})=>JSON.stringify({mode:'SOURCE_ONLY_WAITING',businessActivation:false,sourceAndFenceBindingProven:false,
  summary:{packages:1,scopes:1,closureSha:'a'.repeat(64),businessActivation:false,validUntil:(AT+600000)/1000,executionUntil:(AT+300000)/1000,...changes}});
async function fixture(t){
  const root=await mkdtemp(path.join(os.tmpdir(),'human-waiting-source-'));
  t.after(()=>rm(root,{recursive:true,force:true}));
  const evidenceRoot=path.join(root,'evidence');await mkdir(evidenceRoot);
  const pins={};for(const name of ['java','jar','human','task','facts','original']){
    const file=path.join(['java','jar'].includes(name)?root:evidenceRoot,name);await writeFile(file,name);pins[name]={path:file,sha256:hash(name)};
  }
  const jdbcUrl='jdbc:dm://dm-source:5236',targetId='b'.repeat(64);
  const deployment={schema:'SINGLE_DM8_DEPLOYMENT/v2',database:{containerId:targetId,volume:'agent-platform-main',ownerTask:'WAITING-SEAL',jdbcUrl,memoryLimitBytes:1073741824,nanoCpuLimit:1000000000},
    domains:['consultation','enforcement','adjudication'].map((domain,i)=>({domain,issuer:`https://${domain}.example.test`,
      business:{schema:`BIZ_${i}`,jdbcUrl,ownerUser:`BIZ_OWNER_${i}`,runtimeUser:`BIZ_USER_${i}`},
      authority:{schema:`AUTH_${i}`,jdbcUrl,ownerUser:`AUTH_OWNER_${i}`,runtimeUser:`AUTH_USER_${i}`,backupUser:`AUTH_BACKUP_${i}`},writerContainerIds:[String(i+1).repeat(64)]}))};
  const makePolicy=(name)=>({ref:name,version:1,sha256:pins[name].sha256,...(name==='human'?{}:{maxAge:'PT1H'})});
  const humanWaiting={format:'HUMAN_WAITING_SOURCE/v1',java:pins.java,jar:pins.jar,
    jdbc:{url:'jdbc:dm://127.0.0.1:15236',user:'BIZ_USER_0',passwordEnv:'WAITING_BUSINESS_PASSWORD',schema:'BIZ_0'},
    authority:{url:'jdbc:dm://127.0.0.1:15236',user:'AUTH_USER_0',passwordEnv:'WAITING_AUTHORITY_PASSWORD',schema:'AUTH_0'},
    issuer:deployment.domains[0].issuer,evidenceRoot,evidenceMaxBytes:4096,backupUntil:iso(AT+600000),timeoutMs:5000,
    humanPolicy:makePolicy('human'),taskPolicy:makePolicy('task'),factsPolicy:makePolicy('facts'),
    originalWriters:{root:evidenceRoot,writerIds:['app','maintenance'],maintenanceWriterId:'maintenance',responsibilitySource:'root-scope',stableTreeEvidence:'fenced-original-root'}};
  const files=['human','task','facts','original'].map(n=>pins[n]);
  const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',action:'snapshot',targetId,owner:'owner',source:'decision',approvedAt:iso(AT-60000),expiresAt:iso(AT+1200000),currentMigration:false,connectionSwitch:false,deleteExisting:false,
    configurationSha256:hash(canonical(deployment)),humanWaitingConfigurationSha256:hash(canonical(humanWaiting)),writerRegistryComplete:true,writerRegistryEvidence:'registry',maintenanceExclusivityEvidence:'maintenance-rule',
    writers:[{id:'app',owner:'app-owner',responsibilitySource:'app-rule'},{id:'maintenance',kind:'MAINTENANCE',owner:'maintainer',responsibilitySource:'maintenance-rule'}],
    files:files.map(f=>({...f,licenseReference:'project-owned',useUntil:iso(AT+3600000)}))};
  let binds=0;const lease={proof:{tableSetSha256:'c'.repeat(64),tableCount:7},assertHeld:async()=>{},bindWorker:async()=>{binds++;return {pid:10,parentId:5,createdAt:iso(AT-1000),commandSha256:'d'.repeat(64),invocationSha256:'e'.repeat(64)};}};
  const c={targetId,deployment,inventoryJdbc:{url:jdbcUrl},files,humanWaiting,approval};
  const env={WAITING_BUSINESS_PASSWORD:'business-secret',WAITING_AUTHORITY_PASSWORD:'authority-secret',JAVA_TOOL_OPTIONS:'unapproved',UNRELATED_SECRET:'do-not-forward'};
  const docker=[{Id:targetId,State:{Running:true},Config:{Labels:{task:'WAITING-SEAL'}},NetworkSettings:{Ports:{'5236/tcp':[{HostIp:'127.0.0.1',HostPort:'15236'}]},Networks:{owned:{NetworkID:'f'.repeat(64),Aliases:['dm-source']}}}}];
  return {c,approval,lease,pins,env,root,docker,binds:()=>binds,inspect:(run=async()=>Buffer.from(raw()),now=()=>AT)=>createHumanWaitingInspector(c,approval,{run:async(file,...args)=>file==='docker'?Buffer.from(JSON.stringify(docker)):run(file,...args),now,env})};
}
test('decimal nanoseconds floor without extending deadlines; strict source-only shape',()=>{
  const text=raw().replace(String((AT+600000)/1000),`${(AT+600000)/1000}.000999999`);
  assert.equal(parseHumanWaitingStdout(text).validUntil,iso(AT+600000));
  for(const invalid of [raw({packages:0}),raw({executionUntil:(AT+700000)/1000}),raw().replace('SOURCE_ONLY_WAITING','ACTIVATED'),
    raw().replace('"validUntil":','"validUntil":1,"validUntil":'),raw().replace('"packages":','"pack\\u0061ges":'),
    raw().replace(String((AT+600000)/1000),'1e9'),raw().replace(String((AT+600000)/1000),JSON.stringify(iso(AT+600000)))])assert.throws(()=>parseHumanWaitingStdout(invalid));
});
test('real file pins and exact child environment bound once to the same worker',async t=>{
  const f=await fixture(t);let captured;
  const inspect=f.inspect(async(...args)=>{captured=args;return Buffer.from(raw());});
  const first=await inspect({lease:f.lease,phase:'before'}),second=await inspect({lease:f.lease,phase:'after'});
  assert.equal(f.binds(),1);assert.deepEqual(first,second);assert.equal(first.files.length,4);
  assert.equal(first.stdoutSha256,hash(raw()));assert.equal(first.summary.validUntil,f.c.humanWaiting.backupUntil);
  assert.equal(captured[0],f.pins.java.path);assert.ok(captured[1].includes('--source-waiting'));
  assert.equal(captured[2].env.PRODUCT_HUMAN_SOURCE_AUTHORITY_PASSWORD,'authority-secret');
  assert.equal(captured[2].env.PRODUCT_HUMAN_SOURCE_DB_PASSWORD,'business-secret');
  assert.equal(captured[2].env.JAVA_TOOL_OPTIONS,undefined);assert.equal(captured[2].env.UNRELATED_SECRET,undefined);
  first.workerProof.leaseProof.tableCount=1;
  assert.equal((await inspect({lease:f.lease,phase:'again'})).workerProof.leaseProof.tableCount,7);
});
for(const [name,mutate] of [
  ['original content',async f=>writeFile(f.pins.original.path,'changed')],
  ['unlisted original',async f=>writeFile(path.join(f.c.humanWaiting.evidenceRoot,'unlisted'),'new')],
  ['jar',async f=>writeFile(f.pins.jar.path,'changed')],
  ['source config',async f=>{f.c.inventoryJdbc.url='jdbc:dm://other:5236';}],
  ['deployment config',async f=>{f.c.deployment.domains[0].business.runtimeUser='OTHER_USER';}],
  ['file approvals',async f=>{f.approval.files[0].useUntil=iso(AT+1);}],
  ['lease proof',async f=>{f.lease.proof.tableCount=1;}]
])test(`rejects ${name} mutation during the Java read`,async t=>{
  const f=await fixture(t),inspect=f.inspect(async()=>{await mutate(f);return Buffer.from(raw());});
  await assert.rejects(inspect({lease:f.lease,phase:'before'}));
});
test('live endpoint mapping rejects another container, port, alias and post-read changes',async t=>{
  const f=await fixture(t);const inspect=f.inspect(async()=>{f.docker[0].NetworkSettings.Ports['5236/tcp'][0].HostPort='15237';return Buffer.from(raw());});
  await assert.rejects(inspect({lease:f.lease,phase:'before'}),/ENDPOINT/);
  f.docker[0].NetworkSettings.Ports['5236/tcp'][0].HostPort='15236';f.docker[0].Id='0'.repeat(64);
  await assert.rejects(f.inspect()({lease:f.lease,phase:'before'}),/ENDPOINT/);
  f.docker[0].Id=f.c.targetId;f.docker[0].NetworkSettings.Networks.owned.Aliases=['other'];
  await assert.rejects(f.inspect()({lease:f.lease,phase:'before'}),/ENDPOINT/);
});
test('deadline expiring during actual read closes source binding',async t=>{
  const f=await fixture(t);let at=AT;
  await assert.rejects(f.inspect(async()=>{at=AT+300000;return Buffer.from(raw());},()=>at)({lease:f.lease,phase:'before'}),/EXPIRED/);
});
test('missing original rights and unfenced writers fail before Java',async t=>{
  const f=await fixture(t);f.approval.files.pop();let calls=0;
  await assert.rejects(f.inspect(async()=>{calls++;return Buffer.from(raw());})({lease:f.lease,phase:'before'}),/APPROVED_ORIGINAL/);
  assert.equal(calls,0);
  f.c.humanWaiting.originalWriters.writerIds.push('unregistered');
  f.approval.humanWaitingConfigurationSha256=hash(canonical(f.c.humanWaiting));
  assert.throws(()=>f.inspect(),/ORIGINAL_WRITER_FENCE/);
});
test('directory junction cannot introduce an unfenced tree',async t=>{
  const f=await fixture(t),external=path.join(f.root,'external'),redirect=path.join(f.c.humanWaiting.evidenceRoot,'redirect');
  await mkdir(external);await symlink(external,redirect,process.platform==='win32'?'junction':'dir');
  try{await assert.rejects(f.inspect()({lease:f.lease,phase:'before'}),/PATH_REDIRECT/);}finally{await unlink(redirect);}
});
test('authoritative DNSNames cannot be masked by old aliases',async t=>{
  const f=await fixture(t);f.docker[0].NetworkSettings.Networks.owned.DNSNames=[];
  await assert.rejects(f.inspect()({lease:f.lease,phase:'before'}),/ENDPOINT/);
});
test('unknown file-tree depth refuses without reaching Java',async t=>{
  const f=await fixture(t);let folder=f.c.humanWaiting.evidenceRoot;
  for(let i=0;i<34;i++){folder=path.join(folder,'d');await mkdir(folder);}
  let reads=0;await assert.rejects(f.inspect(async()=>{reads++;return Buffer.from(raw());})({lease:f.lease,phase:'before'}),/CAPACITY/);
  assert.equal(reads,0);
});
