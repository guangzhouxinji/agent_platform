import {nativeAccountDefaultsSha256} from './single-database-native-privileges.mjs';
import {readFile,lstat,realpath} from 'node:fs/promises';
import path from 'node:path';
import {validateDeploymentConfiguration,deploymentAccounts} from './single-database-lifecycle.mjs';
import {canonical,hash,original,assertInventory,inventoryDigest,evaluateInventoryChecks} from './single-database-lifecycle-runtime.mjs';
import {validateBootstrapManifest,checkBootstrapInventory} from './single-database-bootstrap.mjs';
import {validateObjectGrantManifest,checkObjectGrantInventory} from './single-database-object-grants.mjs';
const fail=c=>{throw new Error(c);};
const PROFILE='deployment/single-database/installation-profile.json';
async function bytes(root,relative){
 if(typeof relative!=='string'||!relative||path.isAbsolute(relative)||relative.includes('\\')||relative.split('/').some(v=>!v||v==='.'||v==='..'))fail('INSTALLATION_RELATIVE_SOURCE_REQUIRED');
 const file=path.resolve(root,relative);let part=root;
 for(const segment of relative.split('/')){part=path.join(part,segment);if((await lstat(part)).isSymbolicLink())fail('INSTALLATION_SOURCE_REDIRECT');}
 if(await realpath(file)!==file)fail('INSTALLATION_SOURCE_REDIRECT');return readFile(file);
}
/** Fixed product installation metadata, not a new SQL catalog, execution engine, or approval source. */
export async function loadInstallationProfile(workspaceRoot){
 const root=await realpath(workspaceRoot),profile=JSON.parse(await bytes(root,PROFILE));
 if(profile.format!=='SINGLE_DATABASE_INSTALLATION_PROFILE/v1'||profile.deploymentVersion!=='SINGLE_DM8_DEPLOYMENT/v2'||profile.executionAuthorization!==false)fail('FIXED_INSTALLATION_PROFILE_REQUIRED');
 for(const [file,digest] of [[profile.sourceReviewPath,profile.sourceReviewSha256],[profile.forwardOriginalsPath,profile.forwardOriginalsSha256],[profile.capabilitiesPath,profile.capabilitiesSha256],[profile.applicationSettingsTemplatePath,profile.applicationSettingsTemplateSha256]])
  if(hash(await bytes(root,file))!==digest)fail('INSTALLATION_METADATA_SHA_MISMATCH');
 const review=JSON.parse(await bytes(root,profile.sourceReviewPath)),capabilities=JSON.parse(await bytes(root,profile.capabilitiesPath));
 const instances=review.entries.filter(e=>e.decision==='selected').flatMap(e=>e.bindings.map(b=>({key:b.schema+':'+e.version,path:e.path,sha:e.sha256})));
 if(review.format!=='SINGLE_DATABASE_SOURCE_REVIEW/v2'||instances.length!==profile.migrations.length||new Set(instances.map(v=>v.key)).size!==instances.length)fail('COMPLETE_INSTALLATION_BINDINGS_REQUIRED');
 const preceding=new Set();
 for(const m of profile.migrations){const instance=instances.find(v=>v.key===m.schema+':'+m.id);if(!instance||instance.path!==m.sqlPath||instance.sha!==m.sha256||m.dependsOn.some(k=>!preceding.has(k)))fail('INSTALLATION_INSTANCE_OR_ORDER_MISMATCH');
  await original(root,m);evaluateInventoryChecks({objects:[],columns:[],constraints:[],rows:[]},m.prechecks);evaluateInventoryChecks({objects:[],columns:[],constraints:[],rows:[]},m.postchecks,{post:true});preceding.add(instance.key);}
 if(capabilities.format!=='SINGLE_DATABASE_APPLICATION_CAPABILITIES/v1'||capabilities.applications.length!==3)fail('THREE_PRODUCT_CAPABILITIES_REQUIRED');
 for(const a of capabilities.applications)for(const o of a.objects)if(!profile.installedTables.some(t=>t.schema===a.schemaBindings[o.schemaKind]&&t.object===o.name))fail('ENABLED_PRODUCT_OBJECT_MISSING');
 return {profile,capabilities,root};
}
function boundDeployment(profile,deployment){
 validateDeploymentConfiguration(deployment);
 if(deployment.schema!==profile.deploymentVersion)fail('FINAL_DEPLOYMENT_VERSION_REQUIRED');
 for(const fixed of profile.bindings){const d=deployment.domains.find(v=>v.domain===fixed.domain);if(!d)fail('FINAL_DOMAIN_BINDING_REQUIRED');
  for(const kind of ['business','authority'])for(const [key,value] of Object.entries(fixed[kind]))if(d[kind][key]!==value)fail('EXACT_FINAL_SCHEMA_ACCOUNT_BINDING_REQUIRED');}
 return {targetId:deployment.database.containerId,schemas:deployment.domains.flatMap(d=>[d.business.schema,d.authority.schema]),accounts:deploymentAccounts(deployment)};
}
/** Resolve conditions before emitting any properties; only same-application fixed booleans are gates. */
export function compileApplicationConfiguration(application,deployment){
 const {fields,dynamicDeploymentBindings}=application;
 if(!Array.isArray(fields))fail('MANAGED_APPLICATION_FIELDS_REQUIRED');
 const properties=new Set(),switches=new Map();
 for(const f of fields){
  if(!f||typeof f.property!=='string'||!f.property||properties.has(f.property))fail('MANAGED_APPLICATION_FIELD_INVALID');
  properties.add(f.property);
  if(typeof f.fixedValue==='boolean')switches.set(f.property,f.fixedValue);
 }
 // Validate every condition, even one whose branch would be omitted. Never infer unknown gates as false.
 for(const f of fields){
  if(!Object.hasOwn(f,'requiredWhen')||f.requiredWhen===null)continue;
  const condition=f.requiredWhen;
  if(!condition||typeof condition!=='object'||Array.isArray(condition)
    ||Reflect.ownKeys(condition).length!==2||!Object.hasOwn(condition,'property')||!Object.hasOwn(condition,'equals')
    ||typeof condition.property!=='string'||!condition.property||typeof condition.equals!=='boolean')
   fail('MANAGED_APPLICATION_REQUIRED_WHEN_INVALID');
  if(!switches.has(condition.property))fail('MANAGED_APPLICATION_REQUIRED_WHEN_FIXED_BOOLEAN_REQUIRED');
 }
 const active=fields.filter(f=>!Object.hasOwn(f,'requiredWhen')||f.requiredWhen===null
  ||switches.get(f.requiredWhen.property)===f.requiredWhen.equals);
 const omitted=new Set(fields.filter(f=>!active.includes(f)).map(f=>f.property)),settings={};
 for(const f of active){
  if(f.fixedValue!==null&&f.fixedValue!==undefined)settings[f.property]=f.fixedValue;
  else {if(!/^[A-Z][A-Z0-9_]{0,200}$/.test(f.referenceEnvironment||''))fail('MANAGED_APPLICATION_INPUT_REFERENCE_REQUIRED');settings[f.property]='${'+f.referenceEnvironment+'}';}
 }
 const domain=deployment.domains.find(d=>d.domain===application.domain);
 for(const [property,value] of [['spring.datasource.url',deployment.database.jdbcUrl],
  ['product.identity.authority.jdbc-url',deployment.database.jdbcUrl],['product.identity.issuer',domain.issuer]])
  if(!omitted.has(property))settings[property]=value;
 return {domain:application.domain,mainClass:application.mainClass,settings,
  propertiesContent:Object.entries(settings).map(([k,v])=>k+'='+v).join('\n')+'\n',
  requiredEnvironment:active.filter(f=>(f.fixedValue===null||f.fixedValue===undefined)&&!Object.hasOwn(dynamicDeploymentBindings,f.property))
   .map(f=>({environment:f.referenceEnvironment,valueSource:f.valueSource})),
  identityBootstrapClass:application.identityBootstrapClass,managedBootstrapManifestReference:application.managedBootstrapManifestReference,
  managedTrustShaReference:application.managedTrustShaReference};
}
/** Compiles directly consumable manifests for the existing admin, owner and typed-DCL CLIs.
 * Captured inventory is re-read by those CLIs; this offline compilation cannot authorize DDL. */
export async function compileInstallation({workspaceRoot,deployment,stage,inventory,mode='empty',nonDestructiveReviews={},bootstrapRecovery=null}){
 const {profile,capabilities,root}=await loadInstallationProfile(workspaceRoot),scope=boundDeployment(profile,deployment);
 let manifest;
 if(stage==='bootstrap'){
  if(mode!=='empty')fail('ADMIN_BOOTSTRAP_NEW_ENVIRONMENT_ONLY');
  const steps=profile.bindings.flatMap(d=>['business','authority'].flatMap(kind=>{const b=d[kind];return ['ownerUser','runtimeUser',...(kind==='authority'?['backupUser']:[])].flatMap(key=>[
   {kind:'create-user',account:b[key],passwordEnv:'SINGLE_DB_'+b[key]+'_PASSWORD',...(inventory.nativePrivileges?{nativeDefaultsSha256:nativeAccountDefaultsSha256}:{})},
   ...[...(inventory.nativePrivileges?[]:['CREATE SESSION']),...(key==='ownerUser'?['CREATE TABLE',...['VIEW','SEQUENCE','INDEX'].filter(type=>profile.knownObjects.some(o=>o.schema===b.schema&&o.type===type)).map(type=>'CREATE '+type)]:[])].map(privilege=>({kind:'grant-system',account:b[key],privilege}))]);}));
  manifest={format:'SINGLE_DATABASE_BOOTSTRAP/v1',targetId:scope.targetId,deployment,initialInventorySha256:hash(canonical(inventory)),steps};validateBootstrapManifest(manifest);if(bootstrapRecovery&&(bootstrapRecovery.format!=='BOOTSTRAP_EXACT_CREATE_PREFIX_RECOVERY/v1'||bootstrapRecovery.prefixSteps!==1))fail('EXACT_BOOTSTRAP_PREFIX_RECOVERY_REQUIRED');checkBootstrapInventory(inventory,manifest,bootstrapRecovery?1:0);
 }else if(stage==='migrate'){
  if(!['empty','existing'].includes(mode))fail('SEPARATE_INSTALLATION_MODE_REQUIRED');assertInventory(inventory,scope.targetId,scope.schemas,scope.accounts,deployment);
  if(mode==='empty'&&inventory.schemas.some(s=>s.objects.length))fail('EMPTY_INITIALIZATION_NOT_AN_UPGRADE');
  const migrations=structuredClone(profile.migrations);
  if(mode==='existing')for(const m of migrations){if(!/^[a-f0-9]{64}$/.test(nonDestructiveReviews[m.schema+':'+m.id]||''))fail('EXPLICIT_NONEMPTY_REVIEW_REQUIRED');m.nonDestructiveReviewSha256=nonDestructiveReviews[m.schema+':'+m.id];}
  manifest={...scope,deployment,mode,expectedInventorySha256:inventoryDigest(inventory),migrations,knownObjects:profile.knownObjects,finalChecks:profile.finalChecks};
 }else if(stage==='grants'||stage==='application-settings'){
  assertInventory(inventory,scope.targetId,scope.schemas,scope.accounts,deployment);
  for(const schema of scope.schemas)if(!evaluateInventoryChecks(inventory.schemas.find(s=>s.schema===schema),profile.finalChecks[schema]))fail('FULL_STRUCTURE_BEFORE_GRANTS_REQUIRED');
  const steps=[];
  for(const a of capabilities.applications){const d=deployment.domains.find(v=>v.domain===a.domain);
   for(const o of a.objects){const b=d[o.schemaKind];for(const [privilege,evidence] of Object.entries(o.runtimePrivileges))steps.push({kind:'grant-object',schema:b.schema,object:o.name,objectType:'TABLE',grantee:b.runtimeUser,privilege,grantOption:false,evidence});}
   for(const t of profile.installedTables.filter(t=>t.schema===d.authority.schema))steps.push({kind:'grant-object',schema:t.schema,object:t.object,objectType:'TABLE',grantee:d.authority.backupUser,privilege:'SELECT',grantOption:false,evidence:[{path:t.sourcePath,sha256:t.sourceSha256,line:1}]});
  }
  for(const e of steps.flatMap(s=>s.evidence))if(hash(await bytes(root,e.path))!==e.sha256)fail('GRANT_SOURCE_CHANGED');
  manifest={format:'SINGLE_DATABASE_OBJECT_GRANTS/v1',targetId:scope.targetId,deployment,initialInventorySha256:hash(canonical(inventory)),installedTables:profile.installedTables.map(({schema,object,objectType})=>({schema,object,objectType})),steps};validateObjectGrantManifest(manifest);
  if(stage==='application-settings'){
   const present=checkObjectGrantInventory(inventory,manifest);
   if(manifest.steps.some(s=>!present.has(`${s.schema}.${s.object}:${s.grantee}:${s.privilege}`)))fail('COMPLETE_RUNTIME_GRANTS_BEFORE_START_REQUIRED');
   const template=JSON.parse(await bytes(root,profile.applicationSettingsTemplatePath));
   if(template.format!=='SINGLE_DATABASE_APPLICATION_SETTINGS_TEMPLATE/v1'||template.containsSecretValues!==false)fail('MANAGED_APPLICATION_TEMPLATE_REQUIRED');
   const configurations=template.applications.map(a=>compileApplicationConfiguration(a,deployment));
   manifest={format:'SINGLE_DATABASE_BOUND_APPLICATION_SETTINGS/v1',targetId:scope.targetId,deployment,configurations,containsSecretValues:false};
  }
 }else fail('INSTALLATION_STAGE_REQUIRED');
 return {format:'SINGLE_DATABASE_COMPILED_INSTALLATION/v1',taskId:profile.taskId,stage,manifest,manifestSha256:hash(canonical(manifest)),executionAuthorized:false,approvalAction:{bootstrap:'bootstrap',migrate:'execute',grants:'grant-object','application-settings':'deploy-applications'}[stage],applicationsStarted:false};
}
