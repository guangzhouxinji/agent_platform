import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,writeFile,realpath,rm} from 'node:fs/promises';
import {resolve,dirname,basename} from 'node:path';
import {tmpdir} from 'node:os';
import {randomBytes} from 'node:crypto';
import {hash,canonical,inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {sealSnapshot} from './single-database-lifecycle-snapshot.mjs';
import {restoreSnapshot,restoreOriginalFiles,checkOriginalFiles} from './single-database-lifecycle-restore.mjs';

// Real self-owned AES package and filesystem; JDBC/native/fence ports are doubles.
// No DM8, current personnel permission, source return or business activation is claimed.
const schemas=['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'];
const accounts=schemas.flatMap(s=>[s,s+'_SVC']);
const sourceTargetId=hash('human-restore-owned-source'),targetId=hash('human-restore-owned-target');
const categories=['objects','columns','constraints','foreignKeys','indexes','sequences','views','triggers','dependencies','rows'];
const tables={CONPROD:['CON6_HUMAN_PACKAGE','CON6_HUMAN_HANDLING','CON6_HUMAN_EVENT','CON6_HUMAN_RECEIPT','CON6_HUMAN_CLEANUP_SCOPE','CON6_HUMAN_CLEANUP_EVENT'],CONAUTH:['ID6_HUMAN_CLEANUP_SCOPE']};
const clone=x=>structuredClone(x),scope='SOURCE_PRESERVED_ORDINARY_WAITING';
function inventory(id,populated=false){return {format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId:id,actualJdbc:true,complete:true,errors:[],
 schemas:schemas.map(schema=>({schema,...Object.fromEntries(categories.map(k=>[k,[]])),
  objects:(populated?tables[schema]??[]:[]).map(name=>({name,type:'TABLE'})),
  rows:(populated?tables[schema]??[]:[]).map(table=>({table,count:1,contentSha256:hash('owned typed row '+table),digestMethod:'SORTED_TYPED_COLUMN_ROW_SHA256/v1'}))})),
 accounts:accounts.map(name=>({name,exists:true,systemPrivileges:[],roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name],effectiveGrants:[]}))};}
const epoch=value=>{const ms=BigInt(Date.parse(value));return `${ms/1000n}.${String(ms%1000n).padStart(3,'0')}`;};
const approval=(id,action,until)=>({format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId:id,action,owner:'UNIT',source:'owned test input',
 approvedAt:new Date(Date.now()-1000).toISOString(),expiresAt:until,currentMigration:false,connectionSwitch:false,deleteExisting:false});

async function fixture(t,{legacy=false}={}){
 const root=await realpath(await mkdtemp(resolve(tmpdir(),'human-waiting-restore-')));
 t.after(async()=>{assert.equal(dirname(root).toLowerCase(),(await realpath(tmpdir())).toLowerCase());assert.ok(basename(root).startsWith('human-waiting-restore-'));await rm(root,{recursive:true,force:true});});
 const now=Date.now(),until=new Date(now+600_000).toISOString(),backupUntil=new Date(now+300_000).toISOString(),executionUntil=new Date(now+60_000).toISOString();
 const evidenceRoot=resolve(root,'source-evidence');await mkdir(resolve(evidenceRoot,'policies'),{recursive:true});
 const original=resolve(evidenceRoot,'policies','human.json');await writeFile(original,'self-owned finite policy source');
 const files=[{ref:'policies/human.json',path:original,sha256:hash(await readFile(original))}];
 const summary={packages:1,scopes:1,closureSha:hash('owned closure'),businessActivation:false,validUntil:backupUntil,executionUntil};
 const originalStdout=JSON.stringify({mode:'SOURCE_ONLY_WAITING',businessActivation:false,sourceAndFenceBindingProven:false,summary})
  .replace(`"validUntil":"${backupUntil}"`,`"validUntil":${epoch(backupUntil)}`).replace(`"executionUntil":"${executionUntil}"`,`"executionUntil":${epoch(executionUntil)}`);
 const binding={format:'HUMAN_WAITING_SOURCE_BINDING/v1',sourceTargetId,source:{jdbcUrl:'jdbc:dm://127.0.0.1:5493',deploymentJdbcUrl:'jdbc:dm://source:5236',
  endpointProof:{containerId:sourceTargetId,containerPort:5236,hostIp:'127.0.0.1',hostPort:5493,networkId:hash('owned network'),alias:'source'},
  schema:'CONPROD',account:'CONPROD_SVC',authoritySchema:'CONAUTH',authorityAccount:'CONAUTH_SVC',issuer:'https://consultation.example.test',evidenceRoot},
  configurationSha256:hash('source config'),summary,backupUntil,executionUntil,originalStdout,stdoutSha256:hash(originalStdout),files,fileSetSha256:hash(canonical(files)),
  javaSha256:hash('java'),jarSha256:hash('jar'),policiesSha256:hash('policies'),
  workerProof:{worker:{pid:12,parentId:10,createdAt:new Date(now-1000).toISOString(),commandSha256:hash('command'),invocationSha256:hash('invocation')},leaseProof:{tableSetSha256:hash('tables'),tableCount:7}}};
 const source=inventory(sourceTargetId,true),empty=inventory(targetId),expected={...clone(source),targetId};
 const originals=files.map(({path,sha256})=>({path,sha256,licenseReference:'SELF_OWNED_UNIT',useUntil:until}));
 const rights={owner:'UNIT',licenseReference:'SELF_OWNED_UNIT',purpose:'controlled restore component',useUntil:until,schemas};
 const sourceApproval={...approval(sourceTargetId,'snapshot',until),writers:[{id:'owned-source-writer',owner:'UNIT',responsibilitySource:'unit source and file writer'}],
  files:originals,databaseRights:rights,currentAuthorityOutsideBackup:true,exportMethod:'DM8_NATIVE_EXPORT',nativeExportKeyReference:'owned-native-key',scope:'UNIT_ONLY',
  ...(!legacy?{humanWaitingConfigurationSha256:binding.configurationSha256}:{})};
 const secret=randomBytes(32),packagePath=resolve(root,'source.snapshot');t.after(()=>secret.fill(0));
 const sealed=await sealSnapshot({approval:sourceApproval,inventory:async()=>clone(source),exportDatabase:async()=>Buffer.from('owned native port bytes'),files:originals,key:secret,
  output:packagePath,schemas,accounts,acquireFence:async()=>({assertHeld:async()=>{},release:async()=>{}}),...(!legacy?{humanWaiting:async()=>clone(binding)}:{})});
 const deployment={domains:[{writerContainerIds:[hash('owned destination writer')]}]};
 const nativeRestore={user:'RESTORE_OWNER',passwordEnv:'UNIT_RESTORE_PASSWORD',keyEnv:'UNIT_NATIVE_KEY',keyReference:'owned-native-key',timeoutSeconds:5,
  maintenanceLock:resolve(root,`single-database-maintenance-${targetId}.lock`),attemptMarker:resolve(root,`single-database-restore-${targetId}.json`)};
 const mapped=originals.map(f=>({originalKey:f.path,relativePath:'policies/human.json',sha256:f.sha256,licenseReference:f.licenseReference,useUntil:until}));
 const h={format:'HUMAN_WAITING_RESTORE/v1',bindingSha256:sealed.metadata.humanWaiting?.bindingSha256??hash('legacy has no human binding'),
  sourceConfigurationSha256:binding.configurationSha256,scope};
 const c={targetId,sourceTargetId,deployment,schemas,accounts,packagePath,packageSha256:sealed.sealedSha256,keyEnv:'UNIT_PACKAGE_KEY',keyReference:'owned-package-key',
  nativeRestore,files:mapped,fileRoot:resolve(root,'restored-files'),...(!legacy?{humanWaiting:h}:{}),
  approval:{...approval(targetId,'restore',until),sourceTargetId,configurationSha256:hash(canonical(deployment)),restoreConfigurationSha256:hash(canonical(nativeRestore)),
   packageSha256:sealed.sealedSha256,keyReference:'owned-package-key',nativeExportKeyReference:nativeRestore.keyReference,databaseRights:clone(rights),files:clone(mapped),
   writerRegistryComplete:true,writerRegistryEvidence:'unit writer registry',maintenanceExclusivityEvidence:'unit maintenance',
   writers:[{id:deployment.domains[0].writerContainerIds[0],kind:'CONTAINER',owner:'UNIT',responsibilitySource:'unit writer registry'},
    {id:nativeRestore.maintenanceLock,kind:'MAINTENANCE',owner:'UNIT',responsibilitySource:'unit maintenance'}],emptyInventorySha256:inventoryDigest(empty),
   ...(!legacy?{humanWaitingRestoreConfigurationSha256:hash(canonical(h))}:{})}};
 const f={root,evidenceRoot,original,c,h,sealed,source,empty,expected,binding,events:[],live:clone(empty),imports:0,releases:0,acquires:0};
 f.args={configuration:c,key:Buffer.from(secret),inventory:async()=>clone(f.live),
  acquireFence:async()=>{f.acquires++;return {assertHeld:async()=>{},release:async()=>{f.releases++;}};},
  importDatabase:async(config,bytes)=>{assert.equal(bytes.toString(),'owned native port bytes');f.imports++;f.live=clone(expected);},
  restoreFiles:restoreOriginalFiles,checkFiles:checkOriginalFiles,journal:async e=>f.events.push(clone(e))};
 t.after(()=>f.args.key.fill(0));return f;
}
function resign(f){f.c.approval.humanWaitingRestoreConfigurationSha256=hash(canonical(f.c.humanWaiting));}
async function refused(f,{imports=0,partial=false}={}){
 await assert.rejects(restoreSnapshot(f.args));assert.equal(f.imports,imports);assert.equal(f.releases,f.acquires);
 assert.ok(f.args.key.every(b=>b===0));assert.equal(f.events.some(e=>e.kind==='RESTORE_CHECKS_PASSED'),false);
 if(f.acquires){const last=f.events.at(-1);assert.equal(last.kind,'FAILURE');assert.equal(last.importStarted,partial);assert.equal(last.partialScenePreserved,true);assert.equal(last.rollbackClaimed,false);}
}

test('exact authenticated WAITING restores typed inventory and original relative references with restricted receipt',async t=>{
 const f=await fixture(t),receipt=await restoreSnapshot(f.args);
 assert.equal(f.imports,1);assert.equal(f.releases,1);assert.equal(receipt.businessActivation,false);
 assert.deepEqual(receipt.humanWaiting,{format:'HUMAN_WAITING_RESTORE_RECEIPT/v1',bindingSha256:f.h.bindingSha256,
  sourceConfigurationSha256:f.h.sourceConfigurationSha256,sourceInventorySha256:inventoryDigest(f.source),targetInventorySha256:inventoryDigest(f.expected),scope,businessActivation:false});
 assert.deepEqual(f.events.find(e=>e.kind==='RESTORE_CHECKS_PASSED').humanWaiting,receipt.humanWaiting);
 assert.equal(hash(await readFile(resolve(f.c.fileRoot,'policies/human.json'))),f.c.files[0].sha256);
 assert.equal(hash(await readFile(f.original)),f.c.files[0].sha256);
});

for(const [name,mutate] of [
 ['missing human configuration',f=>{delete f.c.humanWaiting;delete f.c.approval.humanWaitingRestoreConfigurationSha256;}],
 ['missing approval pin',f=>delete f.c.approval.humanWaitingRestoreConfigurationSha256],
 ['wrong binding',f=>{f.c.humanWaiting.bindingSha256=hash('other binding');resign(f);}],
 ['wrong source configuration',f=>{f.c.humanWaiting.sourceConfigurationSha256=hash('other source');resign(f);}],
 ['wrong human scope',f=>{f.c.humanWaiting.scope='BUSINESS_ACTIVATION';resign(f);}],
 ['wrong relative original reference',f=>{f.c.files[0].relativePath='renamed.json';f.c.approval.files=clone(f.c.files);}],
 ['target equals source root',f=>f.c.fileRoot=f.evidenceRoot],
 ['target inside source root',f=>f.c.fileRoot=resolve(f.evidenceRoot,'new-destination')],
 ['target contains source root',f=>f.c.fileRoot=f.root],
])test(`WAITING restore rejects ${name} without importing`,async t=>{const f=await fixture(t);mutate(f);await refused(f);});

test('a legacy authenticated package cannot acquire a human claim from restore configuration',async t=>{
 const f=await fixture(t,{legacy:true});f.c.humanWaiting=f.h;resign(f);await refused(f);
});
test('legacy restore without a human claim remains compatible',async t=>{
 const f=await fixture(t,{legacy:true}),receipt=await restoreSnapshot(f.args);
 assert.equal(receipt.humanWaiting,undefined);assert.equal(f.imports,1);assert.equal(f.releases,1);
});

for(const [name,mutate] of [
 ['missing table',i=>{i.schemas[0].objects.shift();i.schemas[0].rows.shift();}],
 ['missing typed digest',i=>delete i.schemas[0].rows[0].contentSha256],
 ['wrong package count',i=>i.schemas[0].rows[0].count=2],
 ['wrong independent scope count',i=>i.schemas[1].rows[0].count=2],
 ['changed typed row bytes',i=>i.schemas[0].rows[0].contentSha256=hash('changed restored bytes')],
])test(`post-import ${name} retains partial scene and cannot issue success`,async t=>{
 const f=await fixture(t),original=f.args.importDatabase;
 f.args.importDatabase=async(...args)=>{await original(...args);mutate(f.live);};await refused(f,{imports:1,partial:true});
});

for(const boundary of ['configuration','approval','both'])test(`post-import ${boundary} drift cannot change admitted human decision`,async t=>{
 const f=await fixture(t),original=f.args.importDatabase;
 f.args.importDatabase=async(...args)=>{await original(...args);
  if(boundary!=='approval')f.c.humanWaiting.bindingSha256=hash('substituted binding');
  if(boundary==='both')resign(f);else if(boundary==='approval')f.c.approval.humanWaitingRestoreConfigurationSha256=hash('other approval');};
 await refused(f,{imports:1,partial:true});
});

test('old source inspection expiry does not replace currently valid restore and saving authority',async t=>{
 const f=await fixture(t);t.mock.method(Date,'now',()=>Date.parse(f.binding.executionUntil));
 const receipt=await restoreSnapshot(f.args);assert.equal(receipt.humanWaiting.businessActivation,false);assert.equal(f.imports,1);
});
test('expired saved package refuses before import and releases fence',async t=>{
 const f=await fixture(t);t.mock.method(Date,'now',()=>Date.parse(f.sealed.metadata.expiresAt));await refused(f);
});
test('native import failure is not retried and retains partial restore evidence',async t=>{
 const f=await fixture(t);f.args.importDatabase=async()=>{f.imports++;throw Error('OWNED_NATIVE_IMPORT_FAILED');};
 await refused(f,{imports:1,partial:true});
});
test('file restoration failure preserves completed native import without success',async t=>{
 const f=await fixture(t);f.args.restoreFiles=async()=>{throw Error('OWNED_FILE_FAILURE');};await refused(f,{imports:1,partial:true});
});

test('self-consistent binding replacement in final held callback cannot return a success receipt',async t=>{
 const f=await fixture(t);let changed=false;
 f.args.acquireFence=async()=>{f.acquires++;return {
  assertHeld:async()=>{
   if(!changed&&f.events.at(-1)?.kind==='RESTORE_CHECKS_PASSED'){
    changed=true;f.c.humanWaiting.bindingSha256=hash('late self-consistent replacement');resign(f);
   }
  },release:async()=>{f.releases++;},
 };};
 await assert.rejects(restoreSnapshot(f.args));assert.equal(changed,true);assert.equal(f.imports,1);assert.equal(f.releases,1);
 assert.ok(f.args.key.every(b=>b===0));
 // The completed check observation remains in history; the later failure prevents
 // a final receipt and records that no rollback of the imported target occurred.
 assert.equal(f.events.some(e=>e.kind==='RESTORE_CHECKS_PASSED'),true);
 assert.equal(f.events.at(-1).kind,'FAILURE');assert.equal(f.events.at(-1).importStarted,true);
 assert.equal(f.events.at(-1).partialScenePreserved,true);assert.equal(f.events.at(-1).rollbackClaimed,false);
});

test('target root drifting into preserved source after import is refused before any file write',async t=>{
 const f=await fixture(t),original=f.args.importDatabase,destination=resolve(f.evidenceRoot,'unexpected-destination');
 let restores=0;
 f.args.importDatabase=async(...args)=>{await original(...args);f.c.fileRoot=destination;};
 f.args.restoreFiles=async(...args)=>{restores++;await restoreOriginalFiles(...args);};
 await refused(f,{imports:1,partial:true});assert.equal(restores,0);
 await assert.rejects(realpath(destination),{code:'ENOENT'});
 assert.equal(hash(await readFile(f.original)),f.c.files[0].sha256);
});
