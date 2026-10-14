import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {compileInstallation,loadInstallationProfile,compileApplicationConfiguration} from './single-database-installation.mjs';
import {hash,canonical,original} from './single-database-lifecycle-runtime.mjs';
import {deploymentAccounts} from './single-database-lifecycle.mjs';
const root=process.cwd(),{profile}=await loadInstallationProfile(root);
function deployment(){const jdbcUrl='jdbc:dm://127.0.0.1:5496';return {schema:'SINGLE_DM8_DEPLOYMENT/v2',database:{containerId:'d'.repeat(64),volume:'agent-platform-install-unit',ownerTask:profile.taskId,jdbcUrl,memoryLimitBytes:4026531840,nanoCpuLimit:2000000000},domains:profile.bindings.map(d=>({...d,issuer:'https://identity.example.invalid/'+d.domain,writerContainerIds:[],business:{...d.business,jdbcUrl},authority:{...d.authority,jdbcUrl}}))};}
function inventory(d,installed=false){return {format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId:d.database.containerId,actualJdbc:true,complete:true,errors:[],schemas:d.domains.flatMap(d=>[d.business.schema,d.authority.schema]).map(schema=>{const checks=installed?profile.finalChecks[schema].filter(c=>c.expected==='present'):[];return {schema,objects:checks.filter(c=>c.kind==='object').map(c=>({name:c.name,type:c.type})),columns:checks.filter(c=>c.kind==='column').map(c=>({TABLE_NAME:c.table,COLUMN_NAME:c.name,DATA_TYPE:c.dataType??null,NULLABLE:c.nullable===undefined?null:c.nullable?'Y':'N'})),jdbcColumns:checks.filter(c=>c.kind==='column').map(c=>({TABLE_NAME:c.table,COLUMN_NAME:c.name,COLUMN_SIZE:c.size??null})),constraints:checks.filter(c=>c.kind==='constraint').map(c=>({TABLE_NAME:c.table,CONSTRAINT_NAME:c.name,CONSTRAINT_TYPE:c.type??null})),foreignKeys:[],indexes:[],sequences:[],views:[],triggers:[],dependencies:[],rows:[]};}),accounts:deploymentAccounts(d).map(name=>{const systemPrivileges=[{PRIVILEGE:'CREATE SESSION',ADMIN_OPTION:'NO'}];return {name,exists:true,systemPrivileges,roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name,'PUBLIC'],effectiveGrants:[{subject:name,system:systemPrivileges,roles:[],tables:[],columns:[]},{subject:'PUBLIC',system:[],roles:[],tables:[],columns:[]}]};})};}
const compile=(d,stage,i,extra={})=>compileInstallation({workspaceRoot:root,deployment:d,stage,inventory:i,...extra});
test('fixed final profile authenticates all originals, choices and enabled product tables without DB',()=>{assert.equal(profile.migrations.length,109);assert.equal(profile.installedTables.length,273);assert.equal(profile.bindings.length,3);});
test('admin compile binds exact fifteen accounts and emits directly consumable empty bootstrap only',async()=>{const d=deployment();const i={format:'SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1',targetId:d.database.containerId,actualJdbc:true,publicSystemPrivileges:[],publicRoles:[],accounts:deploymentAccounts(d).map(name=>({name,users:[],systemPrivileges:[],roles:[],tableGrants:[],columnGrants:[],objects:[]}))};const r=await compile(d,'bootstrap',i);assert.equal(r.executionAuthorized,false);assert.equal(r.manifest.steps.filter(s=>s.kind==='create-user').length,15);assert.ok(r.manifest.steps.every(s=>s.privilege!=='CREATE VIEW'&&s.privilege!=='CREATE SEQUENCE'));await assert.rejects(compile(d,'bootstrap',i,{mode:'existing'}),/NEW_ENVIRONMENT/);});
test('owner compile preserves schema:id, all original SHAs and both scoped V074 instances',async()=>{const d=deployment(),r=await compile(d,'migrate',inventory(d));assert.equal(r.manifest.accounts.length,15);const v74=r.manifest.migrations.filter(m=>m.id==='V074');assert.deepEqual(v74.map(m=>m.schema).sort(),['CONPROD','ENFPROD']);assert.equal(r.manifest.migrations.filter(m=>m.id==='A001')[0].schema,'ADJAUTH');assert.equal(r.manifest.mode,'empty');});
test('nonempty input cannot use empty initialization and upgrade has no guessed review defaults',async()=>{const d=deployment(),i=inventory(d,true);await assert.rejects(compile(d,'migrate',i),/NOT_AN_UPGRADE/);await assert.rejects(compile(d,'migrate',i,{mode:'existing'}),/NONEMPTY_REVIEW/);});
test('typed grants require full final structure and preserve raw inventory digest used by DCL journal',async()=>{const d=deployment(),i=inventory(d,true),r=await compile(d,'grants',i);assert.equal(r.manifest.initialInventorySha256,hash(canonical(i)));assert.equal(r.manifest.installedTables.length,273);assert.ok(r.manifest.steps.every(s=>s.grantOption===false));for(const dom of d.domains)assert.equal(r.manifest.steps.filter(s=>s.grantee===dom.authority.backupUser).length,profile.installedTables.filter(t=>t.schema===dom.authority.schema).length);await assert.rejects(compile(d,'grants',inventory(d)),/FULL_STRUCTURE/);});
test('wrong account/schema, old deployment version and local issuer cannot bind final product',async()=>{for(const mutate of [d=>d.domains[0].business.runtimeUser='OLD_SVC',d=>d.schema='SINGLE_DM8_DEPLOYMENT/v1',d=>d.domains[0].issuer='https://localhost:5446']){const d=deployment();mutate(d);await assert.rejects(compile(d,'migrate',{}));}});
test('application properties contain only managed references, exact endpoint and no identity fallback',async()=>{const d=deployment(),i=inventory(d,true),g=await compile(d,'grants',i);await assert.rejects(compile(d,'application-settings',i),/COMPLETE_RUNTIME_GRANTS/);for(const s of g.manifest.steps){const a=i.accounts.find(a=>a.name===s.grantee),grant={OWNER:s.schema,TABLE_NAME:s.object,PRIVILEGE:s.privilege,GRANTABLE:'NO'};a.tableGrants.push(grant);a.effectiveGrants.find(e=>e.subject===a.name).tables=a.tableGrants;}const r=await compile(d,'application-settings',i);assert.equal(r.manifest.containsSecretValues,false);for(const a of r.manifest.configurations){assert.equal(a.settings['spring.datasource.url'],d.database.jdbcUrl);assert.equal(a.settings['product.identity.authority.jdbc-url'],d.database.jdbcUrl);assert.equal(a.settings['product.identity.authority.topology'],'shared-instance');assert.match(a.settings['spring.datasource.password'],/^\$\{SINGLE_DB_/);assert.match(a.propertiesContent,/spring.sql.init.mode=never/);}});

test('same resource request pins exactly four self-authored substitute files; approval is not implied',async()=>{const request=JSON.parse((await readFile('docs/evidence/single-database-installation-20261009/resource-delta-request01.json','utf8')).replace(/^\uFEFF/,'')),ref=request.dataScope.exactInputSet,bytes=await readFile(ref.path);assert.equal(hash(bytes),ref.sha256);const inputs=JSON.parse(bytes);assert.equal(inputs.files.length,4);assert.equal(request.approvalGranted,false);assert.equal(inputs.approvalGranted,false);assert.equal(inputs.historicalInputsRead,false);for(const f of inputs.files){assert.equal(hash(await readFile(f.path)),f.sha256);assert.equal(f.sourceUseApprovalGranted,false);assert.equal(f.normalInputContractRequired,true);}assert.equal(inputs.modelExternalCallsAllowed,0);assert.equal(inputs.matrixSubmissionsAllowed,0);});

// Synthetic inventories/field metadata below are offline compiler fixtures, never runtime admission evidence.
const requiredField=(property,environment,valueSource='deployment-config',requiredWhen=null)=>({property,
 referenceEnvironment:environment,valueSource,fixedValue:null,requiredWhen});
function conditionalApplication(enabled=false){return {domain:'adjudication',mainClass:'configured-main',dynamicDeploymentBindings:{},fields:[
 // Put the gate after its consumers to prove fixed-switch resolution precedes field generation.
 requiredField('DEEPSEEK_API_KEY','SINGLE_DB_ADJUDICATION_DEEPSEEK_API_KEY','managed-secret',{property:'adjudication.v2.materials.enabled',equals:true}),
 requiredField('local.model.path','SINGLE_DB_LOCAL_MODEL_PATH','deployment-config',{property:'adjudication.v2.materials.enabled',equals:true}),
 requiredField('server.ssl.key-store-password','SINGLE_DB_TLS_PASSWORD','managed-secret'),
 requiredField('spring.datasource.password','SINGLE_DB_MASTER_PASSWORD','managed-secret'),
 requiredField('product.identity.authority.password','SINGLE_DB_AUTHORITY_PASSWORD','managed-secret'),
 requiredField('agent-platform.runtime.checkpoint-key','SINGLE_DB_CHECKPOINT_KEY','managed-secret'),
 requiredField('product.consultation.comparison.start.worker-subject','SINGLE_DB_START_WORKER_SUBJECT'),
 {property:'product.consultation.comparison.calculation.enabled',fixedValue:false,requiredWhen:null},
 {property:'adjudication.v2.materials.enabled',fixedValue:enabled,requiredWhen:null}
 ]};}
function assertOmitted(configuration,field){
 assert.equal(Object.hasOwn(configuration.settings,field.property),false);
 assert.ok(!configuration.propertiesContent.split('\n').some(line=>line.startsWith(field.property+'=')));
 assert.ok(!configuration.requiredEnvironment.some(entry=>entry.environment===field.referenceEnvironment));
}
test('requiredWhen false branch emits no key/model references and retains every ordinary required input',()=>{
 const application=conditionalApplication(),before=structuredClone(application),c=compileApplicationConfiguration(application,deployment());
 for(const field of application.fields.slice(0,2))assertOmitted(c,field);
 for(const field of application.fields.slice(2,7)){
  assert.equal(c.settings[field.property],'${'+field.referenceEnvironment+'}');
  assert.ok(c.propertiesContent.includes(field.property+'=${'+field.referenceEnvironment+'}\n'));
  assert.ok(c.requiredEnvironment.some(entry=>entry.environment===field.referenceEnvironment&&entry.valueSource===field.valueSource));
 }
 assert.equal(c.requiredEnvironment.length,5);assert.deepEqual(application,before);
 assert.equal(c.settings['product.consultation.comparison.calculation.enabled'],false);
});
test('requiredWhen enabled branch retains all model inputs as mandatory managed references, not values',()=>{
 const application=conditionalApplication(true),c=compileApplicationConfiguration(application,deployment());
 for(const field of application.fields.slice(0,2)){
  assert.equal(c.settings[field.property],'${'+field.referenceEnvironment+'}');
  assert.ok(c.requiredEnvironment.some(entry=>entry.environment===field.referenceEnvironment&&entry.valueSource===field.valueSource));
 }
 assert.equal(c.requiredEnvironment.length,7);
 const invalid=structuredClone(application);invalid.fields[0].referenceEnvironment='not-an-environment';
 assert.throws(()=>compileApplicationConfiguration(invalid,deployment()),/MANAGED_APPLICATION_INPUT_REFERENCE_REQUIRED/);
});
test('requiredWhen equals false uses exact booleans and supports conditional fixed properties',()=>{
 for(const enabled of [false,true]){
  const a=conditionalApplication(enabled);
  const field={property:'local.branch.limit',fixedValue:64,requiredWhen:{property:'adjudication.v2.materials.enabled',equals:false}};
  a.fields.push(field);const c=compileApplicationConfiguration(a,deployment());
  if(enabled)assertOmitted(c,field);else {assert.equal(c.settings[field.property],64);assert.ok(c.propertiesContent.includes('local.branch.limit=64\n'));}
 }
});
test('missing/null requiredWhen preserves ordinary fields and dynamic deployment bindings',()=>{
 const a=conditionalApplication();delete a.fields[2].requiredWhen;
 for(const property of ['spring.datasource.url','product.identity.authority.jdbc-url','product.identity.issuer']){
  a.fields.push(requiredField(property,'SINGLE_DB_DYNAMIC_UNUSED'));a.dynamicDeploymentBindings[property]='deployment';
 }
 const d=deployment(),c=compileApplicationConfiguration(a,d);
 assert.equal(c.settings['spring.datasource.url'],d.database.jdbcUrl);
 assert.equal(c.settings['product.identity.authority.jdbc-url'],d.database.jdbcUrl);
 assert.equal(c.settings['product.identity.issuer'],d.domains.find(v=>v.domain===a.domain).issuer);
 assert.ok(!c.requiredEnvironment.some(entry=>entry.environment==='SINGLE_DB_DYNAMIC_UNUSED'));
 assert.ok(c.requiredEnvironment.some(entry=>entry.environment==='SINGLE_DB_TLS_PASSWORD'));
});
test('a false condition cannot be reintroduced by dynamic deployment binding overrides',()=>{
 const a=conditionalApplication();const field=requiredField('spring.datasource.url','SINGLE_DB_GATED_URL','deployment-config',
  {property:'adjudication.v2.materials.enabled',equals:true});a.fields.push(field);a.dynamicDeploymentBindings[field.property]='database.jdbcUrl';
 assertOmitted(compileApplicationConfiguration(a,deployment()),field);
});
for(const [label,condition] of [
 ['empty object',{}],['empty string',''],['boolean',false],['array',[]],['undefined',undefined],
 ['missing equals',{property:'adjudication.v2.materials.enabled'}],['missing property',{equals:true}],
 ['unknown key',{property:'adjudication.v2.materials.enabled',equals:true,optional:true}],
 ['empty property',{property:'',equals:true}],['non-string property',{property:1,equals:true}],
 ['string equals',{property:'adjudication.v2.materials.enabled',equals:'false'}],
 ['number equals',{property:'adjudication.v2.materials.enabled',equals:0}],
 ['null equals',{property:'adjudication.v2.materials.enabled',equals:null}]
])test('malformed requiredWhen fails closed before omitting fields: '+label,()=>{
 const a=conditionalApplication(false);a.fields[0].requiredWhen=condition;
 assert.throws(()=>compileApplicationConfiguration(a,deployment()),/MANAGED_APPLICATION_REQUIRED_WHEN_INVALID/);
});
for(const [label,change] of [
 ['unknown switch',a=>a.fields[0].requiredWhen.property='unknown.enabled'],
 ['other application switch',a=>a.fields[0].requiredWhen.property='consultation.only.enabled'],
 ['managed nonfixed switch',a=>a.fields.at(-1).fixedValue=null],
 ['string fixed switch',a=>a.fields.at(-1).fixedValue='false'],
 ['number fixed switch',a=>a.fields.at(-1).fixedValue=0]
])test('requiredWhen requires an existing same-application fixed boolean: '+label,()=>{
 const a=conditionalApplication();change(a);
 assert.throws(()=>compileApplicationConfiguration(a,deployment()),/MANAGED_APPLICATION_REQUIRED_WHEN_FIXED_BOOLEAN_REQUIRED/);
});
test('invalid condition is rejected even when it would be hidden behind an omitted switch field',()=>{
 const a=conditionalApplication();a.fields.at(-1).requiredWhen={property:'missing.enabled',equals:true};
 assert.throws(()=>compileApplicationConfiguration(a,deployment()),/MANAGED_APPLICATION_REQUIRED_WHEN_FIXED_BOOLEAN_REQUIRED/);
});
test('ordinary invalid required input still rejects with model branches disabled',()=>{
 const a=conditionalApplication();a.fields[2].referenceEnvironment='';
 assert.throws(()=>compileApplicationConfiguration(a,deployment()),/MANAGED_APPLICATION_INPUT_REFERENCE_REQUIRED/);
});
test('duplicate property cannot ambiguously redefine the fixed switch',()=>{
 const a=conditionalApplication();a.fields.push({...a.fields.at(-1),fixedValue:true});
 assert.throws(()=>compileApplicationConfiguration(a,deployment()),/MANAGED_APPLICATION_FIELD_INVALID/);
});
test('current real template compiles through application-settings with fixture inventory; flags are not assumed true',async()=>{
 const d=deployment(),i=inventory(d,true),g=await compile(d,'grants',i);
 for(const step of g.manifest.steps){const account=i.accounts.find(a=>a.name===step.grantee);
  account.tableGrants.push({OWNER:step.schema,TABLE_NAME:step.object,PRIVILEGE:step.privilege,GRANTABLE:'NO'});
  account.effectiveGrants.find(e=>e.subject===account.name).tables=account.tableGrants;
 }
 const result=await compile(d,'application-settings',i);
 const template=JSON.parse(await readFile(profile.applicationSettingsTemplatePath,'utf8'));
 assert.equal(result.executionAuthorized,false);assert.equal(result.applicationsStarted,false);
 for(const application of template.applications){
  const c=result.manifest.configurations.find(c=>c.domain===application.domain);
  const active=application.fields.filter(field=>field.requiredWhen==null||
   application.fields.find(gate=>gate.property===field.requiredWhen.property).fixedValue===field.requiredWhen.equals);
  assert.deepEqual(c.requiredEnvironment,active.filter(field=>field.fixedValue==null&&!Object.hasOwn(application.dynamicDeploymentBindings,field.property))
   .map(field=>({environment:field.referenceEnvironment,valueSource:field.valueSource})));
  for(const field of application.fields){if(!active.includes(field))assertOmitted(c,field);else assert.ok(Object.hasOwn(c.settings,field.property));}
  for(const property of ['spring.datasource.password','product.identity.authority.password','agent-platform.runtime.checkpoint-key'])
   assert.match(c.settings[property],/^\$\{SINGLE_DB_/);
  assert.equal(c.settings['server.ssl.enabled'],true);
 }
});

const humanTables=['CON6_HUMAN_PACKAGE','CON6_HUMAN_HANDLING','CON6_HUMAN_EVENT','CON6_HUMAN_RECEIPT',
 'CON6_HUMAN_CLEANUP_SCOPE','CON6_HUMAN_CLEANUP_EVENT'];
test('ordinary human installation binds exactly seven consultation tables and their original migrations',async()=>{
 const d=deployment(),r=await compile(d,'migrate',inventory(d));
 const selected=r.manifest.migrations.filter(m=>m.schema==='CONPROD'&&['V109','V110','V111'].includes(m.id)
  ||m.schema==='CONAUTH'&&m.id==='V006');
 assert.deepEqual(selected.map(m=>m.schema+':'+m.id).sort(),['CONAUTH:V006','CONPROD:V109','CONPROD:V110','CONPROD:V111']);
 for(const m of selected){assert.equal(hash(await readFile(m.sqlPath)),m.sha256);
  await assert.rejects(original(root,{...m,sha256:'0'.repeat(64)}),/SQL_SHA_MISMATCH/);}
 assert.deepEqual(profile.installedTables.filter(t=>/^CON6_HUMAN_|^ID6_HUMAN_/.test(t.object))
  .map(t=>t.schema+'.'+t.object).sort(),[...humanTables.map(t=>'CONPROD.'+t),'CONAUTH.ID6_HUMAN_CLEANUP_SCOPE'].sort());
 assert.ok(profile.finalChecks.CONPROD.some(c=>c.kind==='column'&&c.table==='CON6_HUMAN_PACKAGE'&&c.name==='BODY_ACCESS_UNTIL'));
});

test('ordinary human runtime grants are exact and independent backup is SELECT only',async()=>{
 const d=deployment(),r=await compile(d,'grants',inventory(d,true));
 const relevant=r.manifest.steps.filter(s=>humanTables.includes(s.object)||s.object==='ID6_HUMAN_CLEANUP_SCOPE');
 const expected=[...humanTables.flatMap(object=>['INSERT','SELECT'].map(privilege=>`CONPROD.${object}:CONPROD_SVC:${privilege}`)),
  ...['INSERT','SELECT','UPDATE'].map(privilege=>`CONAUTH.ID6_HUMAN_CLEANUP_SCOPE:CONAUTH_SVC:${privilege}`),
  'CONAUTH.ID6_HUMAN_CLEANUP_SCOPE:CONAUTH_BAK:SELECT'];
 assert.deepEqual(relevant.map(s=>`${s.schema}.${s.object}:${s.grantee}:${s.privilege}`).sort(),expected.sort());
 assert.ok(relevant.every(s=>s.grantOption===false));
 assert.ok(r.manifest.steps.some(s=>s.schema==='CONPROD'&&s.object==='CON3_TASK_CREATE_RECEIPT'
  &&s.grantee==='CONPROD_SVC'&&s.privilege==='SELECT'));
});

async function humanGrantInventory(){
 const d=deployment(),i=inventory(d,true),r=await compile(d,'grants',i);
 for(const s of r.manifest.steps){const a=i.accounts.find(a=>a.name===s.grantee);
  a.tableGrants.push({OWNER:s.schema,TABLE_NAME:s.object,PRIVILEGE:s.privilege,GRANTABLE:'NO'});
  a.effectiveGrants.find(e=>e.subject===a.name).tables=a.tableGrants;
 }
 return {d,i};
}
test('missing human deadline column is rejected before grant compilation',async()=>{
 const d=deployment(),i=inventory(d,true),schema=i.schemas.find(s=>s.schema==='CONPROD');
 schema.columns=schema.columns.filter(c=>!(c.TABLE_NAME==='CON6_HUMAN_PACKAGE'&&c.COLUMN_NAME==='BODY_ACCESS_UNTIL'));
 await assert.rejects(compile(d,'grants',i),/FULL_STRUCTURE_BEFORE_GRANTS_REQUIRED/);
});
for(const [field,value] of [['DATA_TYPE','VARCHAR'],['NULLABLE','N']])
 test(`incorrect human deadline ${field} is rejected before grant compilation`,async()=>{
  const d=deployment(),i=inventory(d,true),schema=i.schemas.find(s=>s.schema==='CONPROD');
  const column=schema.columns.find(c=>c.TABLE_NAME==='CON6_HUMAN_PACKAGE'&&c.COLUMN_NAME==='BODY_ACCESS_UNTIL');
  column[field]=value;
  await assert.rejects(compile(d,'grants',i),/FULL_STRUCTURE_BEFORE_GRANTS_REQUIRED/);
 });
for(const [account,table,privilege] of [['CONPROD_SVC','CON3_TASK_CREATE_RECEIPT','SELECT'],
 ['CONAUTH_SVC','ID6_HUMAN_CLEANUP_SCOPE','UPDATE'],['CONAUTH_BAK','ID6_HUMAN_CLEANUP_SCOPE','SELECT']])
 test(`missing ${account} ${table} ${privilege} cannot compile an application start`,async()=>{
  const {d,i}=await humanGrantInventory(),a=i.accounts.find(a=>a.name===account);
  a.tableGrants=a.tableGrants.filter(g=>!(g.TABLE_NAME===table&&g.PRIVILEGE===privilege));
  a.effectiveGrants.find(e=>e.subject===a.name).tables=a.tableGrants;
  await assert.rejects(compile(d,'application-settings',i),/COMPLETE_RUNTIME_GRANTS_BEFORE_START_REQUIRED/);
 });
for(const [account,privilege] of [['CONPROD_SVC','DELETE'],['CONPROD_SVC','UPDATE'],['ENFPROD_SVC','SELECT']])
 test(`unapproved human package ${privilege} for ${account} is rejected`,async()=>{
  const {d,i}=await humanGrantInventory(),a=i.accounts.find(a=>a.name===account);
  a.tableGrants.push({OWNER:'CONPROD',TABLE_NAME:'CON6_HUMAN_PACKAGE',PRIVILEGE:privilege,GRANTABLE:'NO'});
  await assert.rejects(compile(d,'application-settings',i),/EXCESS_OBJECT_PRIVILEGES/);
 });

test('real human feature defaults off and enabled compilation requires all three policy references',async()=>{
 const template=JSON.parse(await readFile(profile.applicationSettingsTemplatePath,'utf8'));
 const original=template.applications.find(a=>a.domain==='consultation');
 const flag='product.consultation.human-request.enabled',prefix='product.consultation.human-request.retention.';
 const policy=original.fields.filter(f=>f.property.startsWith(prefix));
 assert.deepEqual(policy.map(f=>f.property).sort(),['evidence-sha256','ref','version'].map(s=>prefix+s).sort());
 assert.equal(original.fields.find(f=>f.property===flag).fixedValue,false);
 const off=compileApplicationConfiguration(original,deployment());
 for(const field of policy){assert.deepEqual(field.requiredWhen,{property:flag,equals:true});assertOmitted(off,field);}
 for(const other of template.applications.filter(a=>a.domain!=='consultation'))
  assert.ok(other.fields.every(f=>!f.property.startsWith('product.consultation.human-request.')));
 const enabled=structuredClone(original);enabled.fields.find(f=>f.property===flag).fixedValue=true;
 const on=compileApplicationConfiguration(enabled,deployment());
 for(const field of policy){assert.equal(on.settings[field.property],'${'+field.referenceEnvironment+'}');
  assert.ok(on.requiredEnvironment.some(e=>e.environment===field.referenceEnvironment));
  const invalid=structuredClone(enabled);invalid.fields.find(f=>f.property===field.property).referenceEnvironment='';
  assert.throws(()=>compileApplicationConfiguration(invalid,deployment()),/MANAGED_APPLICATION_INPUT_REFERENCE_REQUIRED/);
 }
 assert.equal(original.fields.find(f=>f.property===flag).fixedValue,false);
});
