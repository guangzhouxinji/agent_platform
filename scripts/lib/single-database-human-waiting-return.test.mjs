import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,writeFile,realpath,rm,unlink,symlink} from 'node:fs/promises';
import {resolve,dirname,basename} from 'node:path';
import {tmpdir} from 'node:os';
import {randomBytes} from 'node:crypto';
import {hash,canonical,inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {sealSnapshot} from './single-database-lifecycle-snapshot.mjs';
import {verifyHumanWaitingSourceReturn} from './single-database-human-waiting-return.mjs';

// Real self-owned AES package and source files; database/native/identity ports are doubles.
// These checks do not execute DM8, enable writers or establish business acceptance.
const schemas=['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'];
const accounts=schemas.flatMap(s=>[s,s+'_SVC']);
const sourceTargetId=hash('return-owned-source'),targetId=hash('return-owned-target'),toolId=hash('return-owned-tool');
const categories=['objects','columns','constraints','foreignKeys','indexes','sequences','views','triggers','dependencies','rows'];
const tables={CONPROD:['CON6_HUMAN_PACKAGE','CON6_HUMAN_HANDLING','CON6_HUMAN_EVENT','CON6_HUMAN_RECEIPT','CON6_HUMAN_CLEANUP_SCOPE','CON6_HUMAN_CLEANUP_EVENT'],CONAUTH:['ID6_HUMAN_CLEANUP_SCOPE']};
const clone=x=>structuredClone(x),iso=n=>new Date(n).toISOString();
const epoch=value=>{const ms=BigInt(Date.parse(value));return `${ms/1000n}.${String(ms%1000n).padStart(3,'0')}`;};
function inventory(){return {format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId:sourceTargetId,actualJdbc:true,complete:true,errors:[],
 schemas:schemas.map(schema=>({schema,...Object.fromEntries(categories.map(k=>[k,[]])),
  objects:(tables[schema]??[]).map(name=>({name,type:'TABLE'})),
  rows:(tables[schema]??[]).map(table=>({table,count:1,contentSha256:hash('owned typed '+table),digestMethod:'SORTED_TYPED_COLUMN_ROW_SHA256/v1'}))})),
 accounts:accounts.map(name=>({name,exists:true,systemPrivileges:[],roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name],effectiveGrants:[]}))};}
function summaryBytes(b){
 b.originalStdout=JSON.stringify({mode:'SOURCE_ONLY_WAITING',businessActivation:false,sourceAndFenceBindingProven:false,summary:b.summary})
  .replace(`"validUntil":"${b.backupUntil}"`,`"validUntil":${epoch(b.backupUntil)}`)
  .replace(`"executionUntil":"${b.executionUntil}"`,`"executionUntil":${epoch(b.executionUntil)}`);
 b.stdoutSha256=hash(b.originalStdout);return b;
}
async function fixture(t,{legacy=false,operation='FAILURE'}={}){
 const root=await realpath(await mkdtemp(resolve(tmpdir(),'human-waiting-return-')));
 const junctions=[];
 t.after(async()=>{assert.equal(dirname(root).toLowerCase(),(await realpath(tmpdir())).toLowerCase());assert.ok(basename(root).startsWith('human-waiting-return-'));
  for(const link of junctions){assert.equal(dirname(link),root);await unlink(link);}await rm(root,{recursive:true,force:true});});
 const now=Date.now(),until=iso(now+600_000),backupUntil=iso(now+300_000),executionUntil=iso(now+60_000);
 const evidenceRoot=resolve(root,'human-originals');await mkdir(resolve(evidenceRoot,'policies'),{recursive:true});
 const original=resolve(evidenceRoot,'policies','human.json'),outside=resolve(root,'other-domain-original');
 await writeFile(original,'self-owned human policy');await writeFile(outside,'self-owned enforcement original');
 const originals=await Promise.all([original,outside].map(async path=>({path,sha256:hash(await readFile(path)),licenseReference:'SELF_OWNED_UNIT',useUntil:until})));
 const humanFiles=[{ref:'policies/human.json',path:original,sha256:originals[0].sha256}];
 const humanWaiting={format:'HUMAN_WAITING_SOURCE/v1',evidenceRoot,backupUntil};
 const binding=summaryBytes({format:'HUMAN_WAITING_SOURCE_BINDING/v1',sourceTargetId,
  source:{jdbcUrl:'jdbc:dm://127.0.0.1:5493',deploymentJdbcUrl:'jdbc:dm://source:5236',schema:'CONPROD',account:'CONPROD_SVC',authoritySchema:'CONAUTH',authorityAccount:'CONAUTH_SVC',issuer:'https://consultation.example.test',evidenceRoot,
   endpointProof:{containerId:sourceTargetId,containerPort:5236,hostIp:'127.0.0.1',hostPort:5493,networkId:hash('owned network'),alias:'source'}},
  configurationSha256:hash(canonical(humanWaiting)),summary:{packages:1,scopes:1,closureSha:hash('owned closure'),businessActivation:false,validUntil:backupUntil,executionUntil},
  backupUntil,executionUntil,files:humanFiles,fileSetSha256:hash(canonical(humanFiles)),javaSha256:hash('java'),jarSha256:hash('jar'),policiesSha256:hash('policies'),
  workerProof:{worker:{pid:12,parentId:10,createdAt:iso(now-1000),commandSha256:hash('command'),invocationSha256:hash('old invocation')},leaseProof:{tableSetSha256:hash('tables'),tableCount:7}}});
 const source=inventory(),deployment={domains:[{writerContainerIds:[hash('owned writer')]}]},rights={owner:'UNIT',licenseReference:'SELF_OWNED_UNIT',purpose:'controlled source return component',useUntil:until,schemas};
 const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId:sourceTargetId,action:'snapshot',owner:'UNIT',source:'owned unit decision',approvedAt:iso(now-1000),expiresAt:until,
  currentMigration:false,connectionSwitch:false,deleteExisting:false,configurationSha256:hash(canonical(deployment)),writers:[{id:hash('owned writer'),owner:'UNIT',responsibilitySource:'unit source/file writer'}],
  files:clone(originals),databaseRights:rights,currentAuthorityOutsideBackup:true,exportMethod:'DM8_NATIVE_EXPORT',nativeExportKeyReference:'owned-native-key',scope:'UNIT_ONLY',
  ...(!legacy?{humanWaitingConfigurationSha256:binding.configurationSha256}:{})};
 const secret=randomBytes(32),packagePath=resolve(root,'source.snapshot');t.after(()=>secret.fill(0));
 const sealed=await sealSnapshot({approval,inventory:async()=>clone(source),exportDatabase:async()=>Buffer.from('owned native bytes'),files:originals,key:secret,output:packagePath,schemas,accounts,
  acquireFence:async()=>({assertHeld:async()=>{},release:async()=>{}}),...(!legacy?{humanWaiting:async()=>clone(binding)}:{})});
 const operationEvidencePath=resolve(root,'restore-operation.json');await writeFile(operationEvidencePath,JSON.stringify({kind:operation,partialScenePreserved:true,rollbackClaimed:false}));
 const sourceReturn={format:'HUMAN_WAITING_SOURCE_RETURN/v1',packagePath,packageSha256:sealed.sealedSha256,bindingSha256:sealed.metadata.humanWaiting?.bindingSha256??hash('legacy-no-binding'),
  operationEvidencePath,operationEvidenceSha256:hash(await readFile(operationEvidencePath)),restoreTargetId:targetId,restoreToolId:toolId,ownerTask:'UNIT_RETURN'};
 const c={targetId:sourceTargetId,deployment,schemas,accounts,files:clone(originals),humanWaiting,sourceReturn,approval:{...clone(approval),sourceReturnConfigurationSha256:hash(canonical(sourceReturn))}};
 const fresh=clone(binding);fresh.workerProof.worker.pid=13;fresh.workerProof.worker.createdAt=iso(now);
 const f={root,junctions,original,outside,c,binding,fresh,sealed,source,events:[],calls:[],releases:0,closed:0,published:[],invalidated:0,sceneStopped:true};
 f.args={configuration:c,key:Buffer.from(secret),
  inspect:async({phase})=>{f.calls.push('inspect:'+phase);return clone(f.fresh);},
  inventory:async()=>{f.calls.push('inventory');return clone(f.source);},
  acquireFence:async()=>{f.calls.push('acquire');return {assertHeld:async()=>{f.calls.push('held');},release:async()=>{f.releases++;f.calls.push('release');},waitClosed:async()=>{f.closed++;f.calls.push('closed');}};},
  assertScene:async()=>{f.calls.push('scene');return {sourceTargetId,targets:f.sceneStopped?[targetId,toolId].map(id=>({id,ownerTask:'UNIT_RETURN',running:false,restarting:false,pid:0})):[]};},
  journal:async e=>{f.events.push(clone(e));},
  publish:async(receipt,{assertCurrent})=>{await assertCurrent();f.published.push(clone(receipt));return {invalidate:async()=>{f.invalidated++;f.published.length=0;}};}};
 t.after(()=>f.args.key.fill(0));return f;
}
function resign(f){f.c.approval.sourceReturnConfigurationSha256=hash(canonical(f.c.sourceReturn));}
async function refused(f){
 await assert.rejects(verifyHumanWaitingSourceReturn(f.args));
 assert.equal(f.published.length,0);assert.ok(f.args.key.every(x=>x===0));
}

for(const operation of ['FAILURE','UNKNOWN'])test(`fresh source return accepts authenticated ${operation} operation evidence without claiming restore success`,async t=>{
 const f=await fixture(t,{operation}),receipt=await verifyHumanWaitingSourceReturn(f.args);
 assert.equal(receipt.format,'HUMAN_WAITING_SOURCE_RETURN_RECEIPT/v1');assert.equal(receipt.verificationOnly,true);assert.equal(receipt.businessActivation,false);
 assert.equal(f.releases,1);assert.equal(f.closed,1);assert.equal(f.published.length,1);
 assert.equal(receipt.operationEvidenceSha256,f.c.sourceReturn.operationEvidenceSha256);
 assert.equal(receipt.packageSha256,f.sealed.sealedSha256);assert.deepEqual(receipt.before,f.fresh);assert.deepEqual(receipt.after,f.fresh);
 assert.deepEqual(f.calls.filter(x=>x.startsWith('inspect:')),['inspect:before','inspect:after']);
 assert.ok(f.calls.indexOf('release')<f.calls.indexOf('closed'));
 assert.equal(hash(await readFile(f.outside)),f.c.files[1].sha256);assert.ok(f.args.key.every(x=>x===0));
 assert.ok(JSON.stringify(receipt).includes(inventoryDigest(f.source)));
 assert.equal(JSON.stringify(receipt).includes('self-owned enforcement original'),false);
});
test('legacy authenticated snapshot cannot acquire a human return claim from configuration',async t=>{const f=await fixture(t,{legacy:true});await refused(f);});
test('historical execution deadline does not reject a fresh valid inspection',async t=>{
 const f=await fixture(t);t.mock.method(Date,'now',()=>Date.parse(f.binding.executionUntil));
 f.fresh.executionUntil=iso(Date.now()+60_000);f.fresh.summary.executionUntil=f.fresh.executionUntil;summaryBytes(f.fresh);
 assert.equal((await verifyHumanWaitingSourceReturn(f.args)).verificationOnly,true);
});
test('reused PID with a new process creation time and unchanged command is a fresh worker',async t=>{
 const f=await fixture(t);f.fresh.workerProof.worker.pid=f.binding.workerProof.worker.pid;
 assert.equal(f.fresh.workerProof.worker.commandSha256,f.binding.workerProof.worker.commandSha256);
 assert.equal(f.fresh.workerProof.worker.invocationSha256,f.binding.workerProof.worker.invocationSha256);
 assert.notEqual(f.fresh.workerProof.worker.createdAt,f.binding.workerProof.worker.createdAt);
 assert.equal((await verifyHumanWaitingSourceReturn(f.args)).verificationOnly,true);
});
for(const [name,mutate] of [
 ['wrong binding',f=>{f.c.sourceReturn.bindingSha256=hash('wrong');resign(f);}],
 ['same target and tool',f=>{f.c.sourceReturn.restoreToolId=targetId;resign(f);}],
 ['target equals source',f=>{f.c.sourceReturn.restoreTargetId=sourceTargetId;resign(f);}],
 ['target equals writer',f=>{f.c.sourceReturn.restoreTargetId=f.c.approval.writers[0].id;resign(f);}],
 ['missing nonhuman approved original',f=>{f.c.files.pop();f.c.approval.files.pop();}],
 ['mismatched original license',f=>{f.c.files[1].licenseReference='OTHER';f.c.approval.files[1].licenseReference='OTHER';}],
 ['historical worker reused',f=>{f.fresh.workerProof=clone(f.binding.workerProof);}],
 ['historical process with a changed command digest',f=>{f.fresh.workerProof.worker=clone(f.binding.workerProof.worker);f.fresh.workerProof.worker.invocationSha256=hash('different invocation');}],
 ['changed closure',f=>{f.fresh.summary.closureSha=hash('other closure');summaryBytes(f.fresh);}],
 ['changed source configuration',f=>{f.fresh.configurationSha256=hash('other config');}],
 ['changed source identity',f=>{f.fresh.source.issuer='https://other.example.test';}],
 ['changed Java bytes',f=>{f.fresh.javaSha256=hash('other java');}],
 ['changed policy bytes',f=>{f.fresh.policiesSha256=hash('other policy');}],
])test(`source return refuses ${name}`,async t=>{const f=await fixture(t);mutate(f);await refused(f);});
for(const [name,mutate] of [
 ['missing human table',i=>{i.schemas[0].objects.shift();i.schemas[0].rows.shift();}],
 ['changed typed row',i=>{i.schemas[0].rows[0].contentSha256=hash('changed');}],
 ['missing typed method',i=>{delete i.schemas[0].rows[0].digestMethod;}],
 ['scope count drift',i=>{i.schemas[1].rows[0].count=2;}],
 ['unlisted table',i=>{i.schemas[2].objects.push({name:'EXTRA',type:'TABLE'});}],
 ['account privilege drift',i=>{i.accounts[0].systemPrivileges.push('CREATE TABLE');}],
])test(`complete authenticated inventory rejects ${name}`,async t=>{const f=await fixture(t);mutate(f.source);await refused(f);});
for(const [field,value] of [
 ['restoreCatalog',{format:'OWNED_CHANGED_CATALOG/v1',sha256:hash('unapproved catalog')}],
 ['nativePrivileges',{format:'OWNED_CHANGED_NATIVE_PRIVILEGES/v1',grants:['UNAPPROVED_EXPORT']}],
])test(`top-level ${field} drift is refused despite an unchanged schema/account digest`,async t=>{
 const f=await fixture(t),digest=inventoryDigest(f.source);assert.equal(f.source[field],undefined);
 f.source[field]=clone(value);assert.equal(inventoryDigest(f.source),digest);await refused(f);
});
test('nonhuman original outside protected root is reread and rejects changed bytes',async t=>{
 const f=await fixture(t),inspect=f.args.inspect;f.args.inspect=async args=>{const result=await inspect(args);if(args.phase==='before')await writeFile(f.outside,'changed outside original');return result;};await refused(f);
});
test('missing nonhuman original rejects without a successful receipt',async t=>{const f=await fixture(t);await unlink(f.outside);await refused(f);});
test('operation evidence drift after a valid inspection rejects the bound return',async t=>{
 const f=await fixture(t),inspect=f.args.inspect;f.args.inspect=async args=>{const result=await inspect(args);await writeFile(f.c.sourceReturn.operationEvidencePath,'changed operation evidence');return result;};await refused(f);
});
test('original snapshot byte drift rejects authentication',async t=>{const f=await fixture(t);await writeFile(f.c.sourceReturn.packagePath,'changed package');await refused(f);});
for(const field of ['packagePath','operationEvidencePath'])test(`redirected ${field} is refused`,async t=>{
 const f=await fixture(t),original=f.c.sourceReturn[field],link=resolve(f.root,field+'.junction');
 await symlink(dirname(original),link,process.platform==='win32'?'junction':'dir');f.junctions.push(link);
 f.c.sourceReturn[field]=resolve(link,basename(original));resign(f);await refused(f);
});
for(const [name,change] of [
 ['running',x=>{x.running=true;}],['restarting',x=>{x.restarting=true;}],['live pid',x=>{x.pid=1;}],
 ['wrong owner',x=>{x.ownerTask='OTHER';}],['missing tool',(_,s)=>{s.targets.pop();}],
])test(`actual isolated scene refuses ${name}`,async t=>{
 const f=await fixture(t),scene=f.args.assertScene;f.args.assertScene=async()=>{const s=await scene();change(s.targets[0],s);return s;};await refused(f);
});
for(const boundary of ['inspect','inventory','assertScene','release','waitClosed','journal','publish'])test(`configuration drift after asynchronous ${boundary} prevents success`,async t=>{
 const f=await fixture(t),mutate=()=>{f.c.sourceReturn.bindingSha256=hash('late binding');resign(f);};
 if(['release','waitClosed'].includes(boundary)){const acquire=f.args.acquireFence;f.args.acquireFence=async(...args)=>{const lease=await acquire(...args),port=lease[boundary];lease[boundary]=async(...a)=>{await port(...a);mutate();};return lease;};}
 else{const port=f.args[boundary];f.args[boundary]=async(...args)=>{const result=await port(...args);mutate();return result;};}
 await refused(f);if(boundary==='publish')assert.equal(f.invalidated,1);
});
for(const boundary of ['inspect','inventory','assertScene','release','waitClosed','journal','publish'])test(`exact expiry after asynchronous ${boundary} prevents success`,async t=>{
 const f=await fixture(t);let at=Date.now();t.mock.method(Date,'now',()=>at);const expire=()=>{at=Date.parse(f.sealed.metadata.expiresAt);};
 if(['release','waitClosed'].includes(boundary)){const acquire=f.args.acquireFence;f.args.acquireFence=async(...args)=>{const lease=await acquire(...args),port=lease[boundary];lease[boundary]=async(...a)=>{await port(...a);expire();};return lease;};}
 else{const port=f.args[boundary];f.args[boundary]=async(...args)=>{const result=await port(...args);expire();return result;};}
 await refused(f);if(boundary==='publish')assert.equal(f.invalidated,1);
});
for(const boundary of ['release','waitClosed','publish'])test(`${boundary} failure retains failure evidence without a valid success`,async t=>{
 const f=await fixture(t);
 if(boundary==='publish')f.args.publish=async()=>{throw Error('OWNED_PUBLISH_FAILED');};
 else{const acquire=f.args.acquireFence;f.args.acquireFence=async(...args)=>{const lease=await acquire(...args);lease[boundary]=async()=>{if(boundary==='release')f.releases++;else f.closed++;throw Error('OWNED_'+boundary+'_FAILED');};return lease;};}
 await refused(f);assert.ok(f.events.some(e=>e.kind==='RETURN_FAILURE'));assert.equal(f.releases,1);
});
for(const outcome of ['configuration','deadline','original','scene'])test(`final persisted journal ${outcome} drift refuses after successful fence exit`,async t=>{
 const f=await fixture(t),journal=f.args.journal;let at=Date.now();t.mock.method(Date,'now',()=>at);let changed=false;
 f.args.journal=async event=>{await journal(event);if(event.kind!=='RETURN_FENCE_CLOSED')return;changed=true;
  if(outcome==='configuration'){f.c.sourceReturn.bindingSha256=hash('late journal binding');resign(f);}
  else if(outcome==='deadline')at=Date.parse(f.fresh.executionUntil);
  else if(outcome==='original')await writeFile(f.outside,'late journal source change');
  else f.sceneStopped=false;
 };
 await refused(f);assert.equal(changed,true);assert.equal(f.releases,1);assert.equal(f.closed,1);
 assert.ok(f.events.some(e=>e.kind==='RETURN_CHECKS_PASSED'));assert.equal(f.events.at(-1).kind,'RETURN_FAILURE');
});
test('two inspections must bind one new worker and one unchanged summary',async t=>{
 const f=await fixture(t),inspect=f.args.inspect;
 f.args.inspect=async args=>{const b=await inspect(args);if(args.phase==='after')b.workerProof.worker.invocationSha256=hash('different second worker');return b;};
 await refused(f);
});
test('fresh execution deadline itself expires after the second inspection',async t=>{
 const f=await fixture(t),inspect=f.args.inspect;let at=Date.now();t.mock.method(Date,'now',()=>at);
 f.args.inspect=async args=>{const b=await inspect(args);if(args.phase==='after')at=Date.parse(b.executionUntil);return b;};
 await refused(f);
});
