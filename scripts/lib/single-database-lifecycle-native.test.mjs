import test from 'node:test';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {validateNativeConfiguration,nativeExportInvocation,verifyNativeExportLog,exportDatabase,settleNativeFenceExit} from './single-database-lifecycle-native.mjs';
import {hash,canonical} from './single-database-lifecycle-runtime.mjs';
const target='1'.repeat(64), ids=['2','3','4'].map(v=>v.repeat(64));
function config(){
 const c={targetId:target,schemas:['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'],inventoryJdbc:{url:'jdbc:dm://127.0.0.1:5492'},deployment:{database:{containerId:target},domains:ids.map(id=>({writerContainerIds:[id]}))},approval:{exportMethod:'DM8_NATIVE_EXPORT',nativeExportKeyReference:'owned-native-key-v1',javaFenceSha256:'5'.repeat(64),writerRegistryComplete:true,writerRegistryEvidence:'owned-writer-registry-v1',maintenanceExclusivityEvidence:'owned-maintenance-v1'},native:{user:'ENGINEERING_BKP',passwordEnv:'NATIVE_PASSWORD',exportKeyEnv:'NATIVE_KEY',exportKeyReference:'owned-native-key-v1',timeoutSeconds:30,maintenanceLock:resolve('var',`single-database-maintenance-${target}.lock`),fence:{java:'java',classpath:'managed-driver',jdbcUrl:'jdbc:dm://127.0.0.1:5492',user:'ENGINEERING_FENCE',passwordEnv:'FENCE_PASSWORD'}}};
 c.approval.writers=[...ids.map(id=>({kind:'CONTAINER',id,owner:'engineering-owner',responsibilitySource:'owned-writer-registry-v1'})),{kind:'MAINTENANCE',id:c.native.maintenanceLock,owner:'engineering-owner',responsibilitySource:'owned-maintenance-v1'}];
 c.approval.nativeConfigurationSha256=hash(canonical(c.native));return c;
}
const repin=c=>c.approval.nativeConfigurationSha256=hash(canonical(c.native));
test('native lock is retained and handle closed when worker exit is not proved',async()=>{
 let unlocked=0,closed=0;await assert.rejects(settleNativeFenceExit({waitClosed:async()=>{throw Error('ACTUAL_DATABASE_FENCE_EXIT_UNCONFIRMED');}},{unlock:async()=>{unlocked++;},closeLock:async()=>{closed++;}}),/EXIT_UNCONFIRMED/);assert.equal(unlocked,0);assert.equal(closed,1);
});
test('native lock cleanup waits for actual worker close before unlocking',async()=>{
 let confirm,unlocked=0;const close=new Promise(r=>{confirm=r;});const work=settleNativeFenceExit({waitClosed:()=>close},{unlock:async()=>{unlocked++;},closeLock:async()=>{throw Error('unexpected');}});
 await new Promise(r=>setImmediate(r));assert.equal(unlocked,0);confirm();await work;assert.equal(unlocked,1);
});
test('native plan uses exact six schemas and managed fenced configuration',()=>assert.equal(validateNativeConfiguration(config()).user,'ENGINEERING_BKP'));
for(const [name,mutate,code] of [
 ['wrong target',c=>c.targetId='6'.repeat(64),'EXACT_NATIVE_TARGET_REQUIRED'],
 ['schema injection',c=>c.schemas[0]='CONPROD; DROP TABLE T','SIX_NATIVE_SCHEMAS_REQUIRED'],
 ['unbounded timeout',c=>{c.native.timeoutSeconds=0;repin(c);},'FINITE_NATIVE_TIMEOUT_REQUIRED'],
 ['wrong fence endpoint',c=>{c.native.fence.jdbcUrl='jdbc:dm://127.0.0.1:5301';repin(c);},'PINNED_FENCE_JDBC_REQUIRED'],
 ['configuration drift',c=>c.native.user='OTHER','APPROVED_NATIVE_CONFIGURATION_REQUIRED'],
 ['missing registry',c=>c.approval.writerRegistryComplete=false,'COMPLETE_WRITER_AND_MAINTENANCE_EVIDENCE_REQUIRED'],
 ['empty stopped writers',c=>c.deployment.domains.forEach(d=>d.writerContainerIds=[]),'NONEMPTY_EXACT_STOPPED_WRITERS_REQUIRED'],
 ['undeclared direct writer',c=>c.approval.writers.push({kind:'UNKNOWN',id:'direct-jdbc',owner:'x',responsibilitySource:'x'}),'COMPLETE_REGISTERED_WRITER_SET_REQUIRED'],
 ['missing maintenance authority',c=>c.approval.maintenanceExclusivityEvidence='different','EXCLUSIVE_MAINTENANCE_LOCK_REQUIRED'],
 ['unrelated maintenance lock',c=>{c.native.maintenanceLock=resolve('var','another.lock');repin(c);},'EXCLUSIVE_MAINTENANCE_LOCK_REQUIRED']
]) test(name,()=>assert.throws(()=>validateNativeConfiguration((()=>{const c=config();mutate(c);return c;})()),new RegExp(code)));
test('native export never accepts arbitrary native flags, full scope or host-side secret',()=>{
 const args=nativeExportInvocation(config(),'single-native-11111111-1111-1111-1111-111111111111.dmp');
 assert.deepEqual(args.slice(0,3),['exec','-i',target]);assert.match(args[5],/SCHEMAS/);assert.match(args[5],/DROP=N/);assert.match(args[5],/GRANTS=Y/);assert.match(args[5],/timeout/);assert.doesNotMatch(args.join(' '),/FULL=Y|native-secret|ENCRYPT_PASSWORD=actual/);
});
test('native filename traversal rejected',()=>assert.throws(()=>nativeExportInvocation(config(),'../../original.dmp'),/NEW_NATIVE_NAME_REQUIRED/));
test('actual warning-free summary required, exit-zero or completed banner alone is insufficient',()=>{
 verifyNativeExportLog(Buffer.from('成功终止导出, 没有出现警告'));
 for(const log of ['completed','成功终止导出, 出现1个警告','成功终止导出, 没有出现警告\n错误: 1'])assert.throws(()=>verifyNativeExportLog(Buffer.from(log)),/NATIVE_/);
});
test('native export without an actual held lease cannot read data',async()=>await assert.rejects(exportDatabase(config()),/ACTUAL_HELD_FENCE_REQUIRED/));

test('expired snapshot action fails before any target or secret access',async()=>{
 const c=config();Object.assign(c.approval,{format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId:c.targetId,action:'snapshot',owner:'UNIT',source:'UNIT_ONLY',approvedAt:'2000-01-01T00:00:00Z',expiresAt:'2001-01-01T00:00:00Z',currentMigration:false,connectionSwitch:false,deleteExisting:false});
 const {acquireFence}=await import('./single-database-lifecycle-native.mjs');
 await assert.rejects(acquireFence(c,c.approval.writers),/EXACT_CURRENT_ACTION_APPROVAL_REQUIRED/);
});
test('action approval does not imply database source-use rights',async()=>{
 const c=config();Object.assign(c.approval,{format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId:c.targetId,action:'snapshot',owner:'UNIT',source:'UNIT_ONLY',approvedAt:'2000-01-01T00:00:00Z',expiresAt:'2099-01-01T00:00:00Z',currentMigration:false,connectionSwitch:false,deleteExisting:false,files:[]});
 const {acquireFence}=await import('./single-database-lifecycle-native.mjs');
 await assert.rejects(acquireFence(c,c.approval.writers),/CURRENT_NATIVE_DATA_RIGHTS_REQUIRED/);
});

// Full v2 metadata scope; this is not a Docker/DM8 execution fixture.
function v2config(){
 const c=config(),url=c.inventoryJdbc.url;
 c.deployment={schema:'SINGLE_DM8_DEPLOYMENT/v2',database:{containerId:target,volume:'agent-platform-native-unit',ownerTask:'IMP-SINGLE-DB-NATIVE-FENCE-01',jdbcUrl:url,memoryLimitBytes:4026531840,nanoCpuLimit:2000000000},domains:['consultation','enforcement','adjudication'].map((domain,i)=>({domain,issuer:`https://identity.example.invalid/${domain}`,writerContainerIds:[ids[i]],business:{schema:c.schemas[i*2],jdbcUrl:url,ownerUser:`DOM${i}_BIZ_OWNER`,runtimeUser:`DOM${i}_BIZ_RUNTIME`},authority:{schema:c.schemas[i*2+1],jdbcUrl:url,ownerUser:`DOM${i}_AUTH_OWNER`,runtimeUser:`DOM${i}_AUTH_RUNTIME`,backupUser:`DOM${i}_AUTH_BACKUP`}}))};
 c.accounts=c.deployment.domains.flatMap(d=>[d.business.ownerUser,d.business.runtimeUser,d.authority.ownerUser,d.authority.runtimeUser,d.authority.backupUser]);
 c.approval.configurationSha256=hash(canonical(c.deployment));return c;
}
const pinDeployment=c=>c.approval.configurationSha256=hash(canonical(c.deployment));
test('v2 native validates full deployment-derived six schemas and fifteen accounts, not count alone',()=>{
 const c=v2config();assert.equal(validateNativeConfiguration(c).user,'ENGINEERING_BKP');c.schemas.reverse();c.accounts.reverse();assert.equal(validateNativeConfiguration(c).fence.user,'ENGINEERING_FENCE');
});
for(const [name,mutate,code] of [
 ['v2 missing accounts',c=>delete c.accounts,'EXACT_V2_NATIVE_DEPLOYMENT_SCOPE'],
 ['v2 count only with wrong account',c=>c.accounts[14]='UNRELATED_BACKUP','EXACT_V2_NATIVE_DEPLOYMENT_SCOPE'],
 ['v2 duplicate account',c=>c.accounts[14]=c.accounts[0],'EXACT_V2_NATIVE_DEPLOYMENT_SCOPE'],
 ['v2 foreign six-schema scope',c=>c.schemas[0]='UNRELATED_SCHEMA','EXACT_V2_NATIVE_DEPLOYMENT_SCOPE'],
 ['v2 missing role binding',c=>{delete c.deployment.domains[0].authority.backupUser;pinDeployment(c);},'ACCOUNT_BINDING'],
 ['v2 duplicate role binding',c=>{c.deployment.domains[0].authority.backupUser=c.deployment.domains[0].authority.runtimeUser;pinDeployment(c);},'DUPLICATE_ACCOUNT'],
 ['v2 issuer malformed',c=>{c.deployment.domains[0].issuer='http://localhost';pinDeployment(c);},'HTTPS_ISSUER'],
 ['v2 split endpoint',c=>{c.deployment.domains[0].business.jdbcUrl='jdbc:dm://other:5236';pinDeployment(c);},'SIX_SCHEMAS_ONE_ENDPOINT'],
 ['v2 changed deployment not approved',c=>c.deployment.database.volume='agent-platform-other','EXACT_V2_NATIVE_DEPLOYMENT_SCOPE'],
 ['v2 different inventory URL',c=>{c.inventoryJdbc.url='jdbc:dm://other:5236';c.native.fence.jdbcUrl=c.inventoryJdbc.url;repin(c);},'EXACT_V2_NATIVE_DEPLOYMENT_SCOPE'],
 ['15 accounts without v2',c=>c.deployment.schema='SINGLE_DM8_DEPLOYMENT/v1','FIFTEEN_ACCOUNT_DEPLOYMENT'],
 ['unsupported automatic transport',c=>{c.native.fence.transport='AUTO';repin(c);},'UNSUPPORTED_NATIVE_FENCE_TRANSPORT'],
 ['implicit container fallback forbidden',c=>{c.native.fence.dockerFence={};repin(c);},'EXPLICIT_CONTAINER_FENCE_TRANSPORT'],
 ['backup does not replace maintenance registry',c=>{c.native.user=c.deployment.domains[0].authority.backupUser;c.approval.writers=c.approval.writers.filter(w=>w.kind!=='MAINTENANCE');repin(c);},'EXCLUSIVE_MAINTENANCE_LOCK'],
 ['backup does not replace stopped application writers',c=>{c.native.user=c.deployment.domains[0].authority.backupUser;c.approval.writers=c.approval.writers.filter(w=>w.id!==ids[0]);repin(c);},'COMPLETE_REGISTERED_WRITER_SET']
])test(name,()=>{const c=v2config();mutate(c);assert.throws(()=>validateNativeConfiguration(c),new RegExp(code));});
for(const domain of [0,1,2])for(const [kind,role] of [['authority','backupUser'],['authority','runtimeUser'],['business','runtimeUser'],['authority','ownerUser'],['business','ownerUser']])test(`v2 rejects domain ${domain} ${kind}.${role} as cross-schema maintenance fence`,()=>{
 const c=v2config();c.native.fence.user=c.deployment.domains[domain][kind][role];repin(c);assert.throws(()=>validateNativeConfiguration(c),/SEPARATE_MAINTENANCE_FENCE_ACCOUNT/);
});
test('v2 rejects generic system maintenance fence without widening privilege',()=>{const c=v2config();c.native.fence.user='SYSDBA';repin(c);assert.throws(()=>validateNativeConfiguration(c),/SEPARATE_MAINTENANCE_FENCE_ACCOUNT/);});
test('v2 owned Docker fence is consumable and separately pins the actual class',async()=>{
 const {FENCE_SOURCE_SHA256,DOCKER_FENCE_TRANSPORT}=await import('./single-database-native-fence-container.mjs');
 const c=v2config(),f=c.native.fence;c.inventoryJdbc.url=c.deployment.database.jdbcUrl='jdbc:dm://owned-dm:5236';for(const d of c.deployment.domains)for(const kind of ['business','authority'])d[kind].jdbcUrl=c.inventoryJdbc.url;
 f.jdbcUrl=c.inventoryJdbc.url;f.transport=DOCKER_FENCE_TRANSPORT;f.sessionTimeoutSeconds=600;
 f.dockerFence={containerId:'b'.repeat(64),targetId:target,networkId:'c'.repeat(64),ownerTask:c.deployment.database.ownerTask,java:'/opt/jdk/bin/java',classpath:'/app/classes:/app/dm.jar',classFile:'/app/classes/SingleDatabaseFence.class',classSha256:'d'.repeat(64)};
 f.java=f.dockerFence.java;f.classpath=f.dockerFence.classpath;c.approval.javaFenceSha256=FENCE_SOURCE_SHA256;c.approval.javaFenceClassSha256=f.dockerFence.classSha256;pinDeployment(c);repin(c);
 assert.equal(validateNativeConfiguration(c).fence.transport,DOCKER_FENCE_TRANSPORT);
 c.approval.javaFenceClassSha256='e'.repeat(64);assert.throws(()=>validateNativeConfiguration(c),/PINNED_CONTAINER_FENCE_CODE/);
});

function admissionConfig(){
 const c=v2config();Object.assign(c.approval,{format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId:c.targetId,action:'snapshot',owner:'UNIT',source:'UNIT_ONLY_METADATA',approvedAt:'2000-01-01T00:00:00Z',expiresAt:'2099-01-01T00:00:00Z',currentMigration:false,connectionSwitch:false,deleteExisting:false,files:[],databaseRights:{owner:'UNIT',licenseReference:'UNIT_ONLY',purpose:'metadata-test',schemas:[...c.schemas],useUntil:'2099-01-01T00:00:00Z'}});return c;
}
test('current admission rechecks database rights, file rights and action expiry on every call',async()=>{
 const {validateCurrentNativeAdmission}=await import('./single-database-lifecycle-native.mjs');
 for(const [change,code] of [
  [c=>c.approval.databaseRights.useUntil='2001-01-01T00:00:00Z','CURRENT_NATIVE_DATA_RIGHTS'],
  [c=>c.approval.databaseRights.schemas[0]='OTHER','CURRENT_NATIVE_DATA_RIGHTS'],
  [c=>c.approval.databaseRights.licenseReference='','CURRENT_NATIVE_DATA_RIGHTS'],
  [c=>c.approval.files.push({licenseReference:'UNIT',useUntil:'2001-01-01T00:00:00Z'}),'CURRENT_NATIVE_DATA_RIGHTS'],
  [c=>c.approval.expiresAt='2001-01-01T00:00:00Z','EXACT_CURRENT_ACTION_APPROVAL']
 ]){const c=admissionConfig();validateCurrentNativeAdmission(c);change(c);assert.throws(()=>validateCurrentNativeAdmission(c),new RegExp(code));}
});

test('v2 explicitly approved administrator is maintenance-only, not a six-schema product account',()=>{const c=v2config();c.native.fence.user='SYSDBA';c.approval.administratorFence={targetId:c.targetId,user:'SYSDBA',passwordEnv:c.native.fence.passwordEnv,purpose:'SINGLE_DATABASE_PROTECTION_FENCE',owner:'UNIT',source:'UNIT_METADATA_ONLY_NOT_OPERATION_APPROVAL'};repin(c);assert.equal(validateNativeConfiguration(c).fence.user,'SYSDBA');c.approval.administratorFence.targetId='e'.repeat(64);assert.throws(()=>validateNativeConfiguration(c),/SEPARATE_MAINTENANCE_FENCE_ACCOUNT/);});

// D9.26 installed-native parameter compatibility; pure tests, no DM8 operations.
import './single-database-native-export-parameters.test.mjs';
