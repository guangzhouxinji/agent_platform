import {canonical,hash,inventoryDigest,assertInventory,foreignKeyDependencies} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/single-database-lifecycle-runtime.mjs';
import {assertRestoreCatalog,deriveCompleteNativeForeignKeys} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/single-database-restore-catalog.mjs';
import {assertRecoverySource} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/single-database-recovery-origin.mjs';
import {buildRestoreSemanticMap,controlledNames,rewriteRestoreSql} from 'file:///D:/daima/xinji/agent_platform/scripts/lib/single-database-restore-semantic-map.mjs';
const fail=code=>{throw new Error(code);};
const bag=value=>Array.isArray(value)?value.map(bag).sort((a,b)=>canonical(a).localeCompare(canonical(b))):value&&typeof value==='object'?Object.fromEntries(Object.entries(value).map(([k,v])=>[k,bag(v)])):value;
const equal=(a,b)=>canonical(bag(a))===canonical(bag(b));
const rootFields=['format','targetId','actualJdbc','complete','errors','databaseProduct','schemas','accounts','nativePrivileges','restoreCatalog','inventorySha256','foreignKeyDependencies','restoreCatalogSha256','fullNativeForeignKeys'];
const schemaFields=['schema','objects','columns','constraints','foreignKeys','indexes','sequences','views','triggers','dependencies','rows','nativeSchemaObjects','jdbcColumns','indexColumns','checkConstraints','constraintColumns'];
function checkCaptured(i,id,scope){
 assertInventory(i,id,scope.schemas,scope.accounts,scope.deployment);assertRestoreCatalog(i.restoreCatalog);
 if(Object.keys(i).some(k=>!rootFields.includes(k)))fail('RESTORE_SEMANTIC_UNKNOWN_ROOT_FIELD');
 if(!i.nativePrivileges||!equal(Object.keys(i.nativePrivileges),['format','definitions'])||i.nativePrivileges.format!=='DM8_NATIVE_PRIVILEGES/v1')fail('RESTORE_SEMANTIC_NATIVE_PRIVILEGES_REQUIRED');
 const definitions=i.nativePrivileges.definitions;if(!definitions||typeof definitions!=='object'||Array.isArray(definitions))fail('RESTORE_SEMANTIC_NATIVE_PRIVILEGES_REQUIRED');
 const visitState=new Map();const visit=role=>{if(visitState.get(role)===1)fail('RESTORE_SEMANTIC_ROLE_CYCLE');if(visitState.get(role)===2)return;const def=definitions[role];if(!def||!['tables','roles','columns','system'].every(k=>Array.isArray(def[k]))||Object.keys(def).some(k=>!['tables','roles','columns','system'].includes(k)))fail('RESTORE_SEMANTIC_INCOMPLETE_ROLE_DEFINITION');visitState.set(role,1);for(const grant of def.roles){if(typeof grant.GRANTED_ROLE!=='string'||!Object.hasOwn(definitions,grant.GRANTED_ROLE))fail('RESTORE_SEMANTIC_INCOMPLETE_ROLE_DEFINITION');visit(grant.GRANTED_ROLE);}visitState.set(role,2);};Object.keys(definitions).forEach(visit);
 for(const s of i.schemas)if(!equal(Object.keys(s),schemaFields)||schemaFields.filter(k=>k!=='schema').some(k=>!Array.isArray(s[k])))fail('RESTORE_SEMANTIC_SCHEMA_FIELD_SET');
 if(Object.hasOwn(i,'inventorySha256')&&i.inventorySha256!==inventoryDigest(i))fail('RESTORE_SEMANTIC_INVENTORY_RECEIPT_DRIFT');
 if(Object.hasOwn(i,'restoreCatalogSha256')&&i.restoreCatalogSha256!==hash(canonical(i.restoreCatalog)))fail('RESTORE_SEMANTIC_CATALOG_RECEIPT_DRIFT');
 if(Object.hasOwn(i,'foreignKeyDependencies')&&!equal(i.foreignKeyDependencies,foreignKeyDependencies(i)))fail('RESTORE_SEMANTIC_DEPENDENCY_RECEIPT_DRIFT');
 const fk=deriveCompleteNativeForeignKeys(i);
 if(Object.hasOwn(i,'fullNativeForeignKeys')&&!equal(i.fullNativeForeignKeys,fk))fail('RESTORE_SEMANTIC_FK_RECEIPT_DRIFT');
 const checkNumbers=v=>{if(typeof v==='number'&&(!Number.isFinite(v)||(Number.isInteger(v)&&!Number.isSafeInteger(v))))fail('RESTORE_SEMANTIC_UNSAFE_NUMBER');if(v&&typeof v==='object')Object.values(v).forEach(checkNumbers);};checkNumbers(i);
 return fk;
}
const idFields=new Set(['ID','SCHID','PID','TABLEID','TABLE_ID','INDEXID','FINDEXID','TRIGID','INDEX_ID']);
function project(inventory,mapping){
 const i=structuredClone(inventory),name=(schema,v)=>v===null?v:mapping.names.get(schema+':'+v)??v;
 const id=v=>{if(v===null||v==='-1')return v;const mapped=mapping.ids.get(v);if(!mapped)fail('RESTORE_SEMANTIC_UNRESOLVED_OBJECT_REFERENCE');return '@ID:'+mapped;};
 for(const [category,capture] of Object.entries(i.restoreCatalog.categories))for(const r of capture.rows){
  const schema=r.SCHEMA_NAME||r.OWNER||r.INDEX_OWNER;
  if(category==='nativeObjects'){r.NAME=name(schema,r.NAME);if(mapping.timeObjects.has(r.ID))r.CRTDATE='@CONSTRAINT_CREATION_TIME';}
  for(const field of Object.keys(r))if(idFields.has(field))r[field]=id(r[field]);
  for(const field of ['CONSTRAINT_NAME','INDEX_NAME','TABLE_NAME','IOT_NAME'])if(Object.hasOwn(r,field))r[field]=name(schema,r[field]);
  if(category==='hiddenTexts'){const originalId=inventory.restoreCatalog.categories.hiddenTexts.rows.find(v=>id(v.ID)===r.ID)?.ID,object=mapping.objects.get(originalId);if(!object)fail('RESTORE_SEMANTIC_TEXT_OBJECT_REFERENCE');const local=new Map([...mapping.names].filter(([k])=>k.startsWith(object.SCHEMA_NAME+':')).map(([k,v])=>[k.slice(object.SCHEMA_NAME.length+1),v]));r.TXT=rewriteRestoreSql(r.TXT,local).text;}
 }
 for(const s of i.schemas)for(const [category,rows] of Object.entries(s))if(Array.isArray(rows))for(const r of rows){
  if(category==='objects')r.name=name(s.schema,r.name);
  if(category==='constraints')r.LAST_CHANGE='@CONSTRAINT_CREATION_TIME';
  for(const field of ['CONSTRAINT_NAME','TABLE_NAME','IOT_NAME'])if(Object.hasOwn(r,field))r[field]=name(r.OWNER||r.TABLE_OWNER||s.schema,r[field]);
  if(Object.hasOwn(r,'INDEX_NAME'))r.INDEX_NAME=name(r.INDEX_OWNER||r.OWNER||s.schema,r.INDEX_NAME);
  if(Object.hasOwn(r,'R_CONSTRAINT_NAME'))r.R_CONSTRAINT_NAME=name(r.R_OWNER||s.schema,r.R_CONSTRAINT_NAME);
  for(const [field,owner] of [['FK_NAME','FKTABLE_SCHEM'],['PK_NAME','PKTABLE_SCHEM'],['FKTABLE_NAME','FKTABLE_SCHEM'],['PKTABLE_NAME','PKTABLE_SCHEM']])if(Object.hasOwn(r,field))r[field]=name(r[owner],r[field]);
 }
 // These four fields are generated receipts, never independent source evidence. checkCaptured
 // recomputes them before projection; source/target raw and original digest remain in the report.
 for(const field of ['inventorySha256','restoreCatalogSha256','foreignKeyDependencies','fullNativeForeignKeys'])delete i[field];
 i.targetId='@APPROVED_INDEPENDENT_TARGET';return bag(i);
}
function differences(a,b){const out=[];const digest=v=>hash(v===undefined?'@MISSING_FIELD':canonical(v));const walk=(x,y,path)=>{if(canonical(x)===canonical(y))return;if(out.length>=10000)fail('RESTORE_SEMANTIC_DIFFERENCE_CAPACITY');if(x&&y&&typeof x==='object'&&typeof y==='object'&&Array.isArray(x)===Array.isArray(y)){for(const key of [...new Set([...Object.keys(x),...Object.keys(y)])].sort())walk(x[key],y[key],path+'/'+key.replaceAll('~','~0').replaceAll('/','~1'));}else out.push({path,sourceSha256:digest(x),targetSha256:digest(y)});};walk(a,b,'');return out;}
/** Exact non-identity fields and multiplicities; no inferred/unknown transform is accepted. */
export function compareRestoreInventories({source,destination,origin,sourceTargetId=source.targetId,targetId=destination.targetId,schemas,accounts,deployment}){
 const scope={schemas,accounts,deployment};checkCaptured(source,sourceTargetId,scope);checkCaptured(destination,targetId,scope);assertRecoverySource(source,origin);
 const sourceViews=source.restoreCatalog.categories.viewDefinitions,destinationViews=destination.restoreCatalog.categories.viewDefinitions;
 if(!equal(sourceViews,destinationViews))fail('RESTORE_SEMANTIC_VIEW_DEFINITION_DRIFT');
 if(!sourceViews.rows.some(v=>v.OWNER==='SYS'&&v.VIEW_NAME==='DBA_CONSTRAINTS'&&/O_CONS\.CRTDATE AS\s+"LAST_CHANGE"/.test(v.TEXT)))fail('RESTORE_SEMANTIC_CREATION_TIME_VIEW_UNPROVEN');
 const controlled=controlledNames(origin),a=buildRestoreSemanticMap(source,controlled,{source:true}),b=buildRestoreSemanticMap(destination,controlled);
 const reverse=new Map([...b.ids].map(([id,semantic])=>[semantic,id]));
 if(a.ids.size!==b.ids.size||[...a.ids.values()].some(v=>!reverse.has(v)))fail('RESTORE_SEMANTIC_OBJECT_MAPPING_MISMATCH');
 const mappingEvidence=[...a.ids].map(([sourceId,identitySha256])=>({sourceId,targetId:reverse.get(identitySha256),identitySha256})).sort((x,y)=>x.identitySha256.localeCompare(y.identitySha256));
 const diff=differences(project(source,a),project(destination,b));
 const createdTimes=[...a.timeObjects].map(sourceId=>({identitySha256:a.ids.get(sourceId),sourceSha256:hash(canonical(a.objects.get(sourceId).CRTDATE)),targetSha256:hash(canonical(b.objects.get(reverse.get(a.ids.get(sourceId))).CRTDATE))}));
 return {format:'DM8_RESTORE_SEMANTIC_COMPARISON/v1',passed:diff.length===0,code:diff.length?'RESTORE_SEMANTIC_DIFFERENCES':'RESTORE_SEMANTIC_EQUIVALENT',sourceInventorySha256:inventoryDigest(source),targetInventorySha256:inventoryDigest(destination),differences:diff,mappingEvidence,transforms:{targetIdentity:true,generatedNames:a.names.size,objectIds:mappingEvidence.length,selfReferenceGraphs:a.graphs.length,constraintCreationTimes:createdTimes},actualNativeRestoreProven:false};
}

export {project,checkCaptured};
