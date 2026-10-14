import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';
import {compareRestoreInventories} from './single-database-restore-comparison.mjs';import {rewriteRestoreSql} from './single-database-restore-semantic-map.mjs';import {loadRecoveryOrigin} from './single-database-recovery-origin.mjs';import {canonical,hash,inventoryDigest,foreignKeyDependencies} from './single-database-lifecycle-runtime.mjs';import {deriveCompleteNativeForeignKeys} from './single-database-restore-catalog.mjs';
// Historical authenticated-origin projection, disconnected components; no Docker/DM8/native restore.
const root=path.resolve('docs/evidence/single-database-installation-20261009/approved-execution/restore-profile51');
const original=JSON.parse(fs.readFileSync(path.join(root,'historical-lossless-unit-input51.json'))),config=JSON.parse(fs.readFileSync(path.join(root,'readonly-profile-config51.json'))),origin=await loadRecoveryOrigin(config.recoveryCatalog);
delete original.unitEvidenceScope; // Test annotation is not a production inventory field; raw historical file stays intact.
const fresh=()=>structuredClone(original),target=hash('OWNED UNIT INDEPENDENT RESTORE TARGET 52');
export function refresh(i){if(Object.hasOwn(i,'inventorySha256'))i.inventorySha256=inventoryDigest(i);if(Object.hasOwn(i,'restoreCatalogSha256'))i.restoreCatalogSha256=hash(canonical(i.restoreCatalog));if(Object.hasOwn(i,'foreignKeyDependencies'))i.foreignKeyDependencies=foreignKeyDependencies(i);if(Object.hasOwn(i,'fullNativeForeignKeys'))i.fullNativeForeignKeys=deriveCompleteNativeForeignKeys(i);return i;}
const compare=(destination,source=original,o=origin)=>compareRestoreInventories({source,destination,origin:o,schemas:config.schemas,accounts:config.accounts,deployment:config.deployment});
function rebuild(input,{ids=true,names=true,times=true}={}){
 const i=structuredClone(input),cats=i.restoreCatalog.categories,oldIds=new Set(cats.nativeObjects.rows.flatMap(o=>[o.ID,o.SCHID])),idMap=new Map([...oldIds].map(id=>[id,(BigInt(id)+400000000n).toString()])),nameMap=new Map();
 if(names){for(const c of cats.nativeConstraints.rows)if(c.CONSTRAINT_NAME==='CONS'+c.ID)nameMap.set(c.CONSTRAINT_NAME,'CONS'+(ids?idMap.get(c.ID):c.ID));for(const x of cats.nativeIndexes.rows)if(x.FLAG!=='0')nameMap.set(x.INDEX_NAME,'REBUILT_INDEX_'+(ids?idMap.get(x.INDEX_ID):x.INDEX_ID));for(const o of cats.nativeObjects.rows){const m=o.NAME.match(/^SREF_CON_(TAB|TRIG)([0-9]+)_(LEVEL|REFING|REFED|BS|AR|AS)$/);if(m)nameMap.set(o.NAME,'SREF_CON_'+m[1]+(ids?idMap.get(m[2]):m[2])+'_'+m[3]);}}
 const idFields=new Set(['ID','SCHID','PID','TABLEID','TABLE_ID','INDEXID','FINDEXID','TRIGID','INDEX_ID']);
 const transform=v=>{if(Array.isArray(v))return v.map(transform);if(v&&typeof v==='object')return Object.fromEntries(Object.entries(v).map(([k,x])=>[k,k==='TXT'&&typeof x==='string'?x.replace(/\b[A-Za-z_][A-Za-z_0-9$]*\b/g,t=>nameMap.get(t)??t):ids&&idFields.has(k)&&idMap.has(x)?idMap.get(x):typeof x==='string'&&nameMap.has(x)?nameMap.get(x):transform(x)]));return v;};
 const result=transform(i);result.targetId=target;
 if(times){for(const o of result.restoreCatalog.categories.nativeObjects.rows)if(o['SUBTYPE$']==='CONS')o.CRTDATE='2026-10-10 14:40:00.123456';for(const s of result.schemas)for(const c of s.constraints)c.LAST_CHANGE='2026-10-10 14:40:00.123456';}
 return refresh(result);
}
test('full origin self-comparison preserves every scoped field and 2288 bijective identities',()=>{const r=compare(original);assert.equal(r.passed,true);assert.equal(r.mappingEvidence.length,2288);assert.equal(r.transforms.selfReferenceGraphs,4);assert.equal(r.actualNativeRestoreProven,false);});
test('order changes are bags with multiplicity, not sets',()=>{const i=fresh();for(const s of i.schemas)for(const v of Object.values(s))if(Array.isArray(v))v.reverse();i.schemas.reverse();i.accounts.reverse();for(const c of Object.values(i.restoreCatalog.categories)){c.columns.reverse();c.rows.reverse();}assert.equal(compare(refresh(i)).passed,true);});
test('only approved independent container identity changes',()=>{const i=fresh();i.targetId=target;assert.equal(compare(refresh(i)).passed,true);});
test('constraint creation times change only with proved native/view correspondence',()=>assert.equal(compare(rebuild(original,{ids:false,names:false})).passed,true));
test('system index names change only through native parent/zero-key roles',()=>assert.equal(compare(rebuild(original,{ids:false,times:false})).passed,true));
test('full physical ID/schema ID/constraint/index/selfgraph bijection and every reference can change together',()=>{const r=compare(rebuild(original));assert.equal(r.passed,true);assert.ok(r.mappingEvidence.every(m=>m.sourceId!==m.targetId));});
for(const [name,mutate] of [
 ['unknown INFO bitmap',i=>i.restoreCatalog.categories.nativeObjects.rows[0].INFO3=(BigInt(i.restoreCatalog.categories.nativeObjects.rows[0].INFO3)+1n).toString()],
 ['same row count different content SHA',i=>i.schemas[0].rows[0].contentSha256='0'.repeat(64)],
 ['direct SELECT privilege',i=>i.accounts[0].tableGrants.push({GRANTEE:i.accounts[0].name,OWNER:'CONPROD',TABLE_NAME:'ID2_SUBJECT',PRIVILEGE:'SELECT',GRANTABLE:'NO'})],
 ['PUBLIC privilege',i=>i.nativePrivileges.definitions.PUBLIC.system.push({PRIVILEGE:'UNKNOWN',ADMIN_OPTION:'NO'})],
 ['effective recursive role grant',i=>i.accounts[0].effectiveGrants[0].system.push({PRIVILEGE:'CREATE SESSION',ADMIN_OPTION:'YES'})],
 ['ADMIN_OPTION',i=>i.accounts[0].systemPrivileges[0].ADMIN_OPTION='YES'],
 ['account status',i=>i.accounts[0].account[0].ACCOUNT_STATUS='LOCKED'],
 ['manual constraint renamed',i=>{const c=i.schemas[0].constraints.find(c=>c.CONSTRAINT_NAME.startsWith('CK_'));c.CONSTRAINT_NAME='CK_UNAPPROVED';}],
 ['manual index renamed',i=>{const x=i.restoreCatalog.categories.nativeIndexes.rows.find(x=>x.FLAG==='0');x.INDEX_NAME='UNAPPROVED_MANUAL';}],
 ['ordered index direction',i=>i.schemas[0].indexColumns[0].DESCEND='DESC'],
 ['CHECK expression',i=>i.schemas[0].constraints.find(c=>c.CONSTRAINT_TYPE==='C').SEARCH_CONDITION='1=1'],
 ['unknown physical/statistics field',i=>i.restoreCatalog.categories.tables.rows[0].NUM_ROWS='999'],
 ['ordinary table creation date must not normalize',i=>i.restoreCatalog.categories.nativeObjects.rows.find(o=>o['SUBTYPE$']==='UTAB').CRTDATE='2026-10-10 00:00:00'],
 ['trigger full body',i=>i.restoreCatalog.categories.hiddenTexts.rows[0].TXT+='\n-- altered comment\n'],
 ['binary INFO flags',i=>i.restoreCatalog.categories.nativeObjects.rows[0].INFO5.binaryBase64='AAAA'],
 ['constraint status',i=>i.schemas[0].constraints[0].STATUS='DISABLED'],
 ['GRANTABLE',i=>i.nativePrivileges.definitions.PUBLIC.tables[0].GRANTABLE=i.nativePrivileges.definitions.PUBLIC.tables[0].GRANTABLE==='YES'?'NO':'YES'],
 ['duplicate same metadata row must not collapse',i=>i.schemas[0].rows.push({...i.schemas[0].rows[0]})]
])test('negative semantic difference: '+name,()=>{const i=fresh();mutate(i);let rejected=false;try{rejected=!compare(refresh(i)).passed;}catch{rejected=true;}assert.equal(rejected,true);});
for(const [name,mutate,code] of [
 ['unknown root',i=>i.extra=true,/UNKNOWN_ROOT_FIELD/],
 ['supplement missing category',i=>delete i.restoreCatalog.categories.hiddenTexts,/CATEGORY_SET/],
 ['unsafe numeric scalar',i=>i.restoreCatalog.categories.nativeObjects.rows[0].INFO3=1157425108529184800,/LOSSY_INTEGER/],
 ['view body drift',i=>i.restoreCatalog.categories.viewDefinitions.rows[0].TEXT+=' ',/VIEW_DEFINITION_DRIFT/],
 ['native FK missing',i=>{const a=i.restoreCatalog.categories.nativeConstraints.rows; a.splice(a.findIndex(c=>c['TYPE$']==='F'),1);},/CONSTRAINT_COVERAGE|EXTRA_JDBC_FK/],
 ['foreign key rule',i=>i.restoreCatalog.categories.nativeConstraints.rows.find(c=>c['TYPE$']==='F').FACTION=' C',/FK_RELATION_MISMATCH/],
 ['hidden table nonempty',i=>i.restoreCatalog.categories.hiddenTableCounts.rows[0].ROW_COUNT='1',/UNSUPPORTED_HIDDEN_ROWS/],
 ['source graph trigger reference missing',i=>{const t=i.restoreCatalog.categories.hiddenTexts.rows[0];t.TXT=t.TXT.replace(/SREF_CON_TAB[0-9]+_REFED/g,'MISSING_GRAPH_TABLE');},/TEXT_REFERENCES/],
 ['single numeric reference not remapped',i=>i.restoreCatalog.categories.nativeObjects.rows[0].PID='999999999999',/UNRESOLVED_OBJECT_REFERENCE/],
 ['same-semantic duplicate generated constraint',i=>{const c=i.restoreCatalog.categories.nativeConstraints.rows.find(c=>c.CONSTRAINT_NAME==='CONS'+c.ID);i.restoreCatalog.categories.nativeConstraints.rows.push({...c});},/DUPLICATE_IDENTITY/],
 ['native/compat creation timestamp mismatch',i=>i.schemas[0].constraints[0].LAST_CHANGE='2026-10-10 00:00:00',/CONSTRAINT_CREATION_LINK/],
 ['extra schema field',i=>i.schemas[0].notApproved=[],/SCHEMA_FIELD_SET/]
])test('negative guard: '+name,()=>{const i=fresh();mutate(i);assert.throws(()=>compare(refresh(i)),code);});
test('SQL tokens preserve literals, comments, substrings, quoted escapes and all whitespace',()=>{const sql=`A A_SUFFIX 'A' -- A\n/* A */ "A" 'don''t A' "A""suffix"`;assert.equal(rewriteRestoreSql(sql,new Map([['A','@mapped']])).text,`@mapped A_SUFFIX 'A' -- A\n/* A */ "@mapped" 'don''t A' "A""suffix"`);});
test('unterminated SQL cannot be normalized',()=>assert.throws(()=>rewriteRestoreSql("SELECT 'bad",new Map()),/UNTERMINATED_SQL/));
test('authenticated origin parsed tampering cannot approve generated identity',()=>{const o=structuredClone(origin);o.files[0].value.generatedCandidates[0].name='UNKNOWN';assert.throws(()=>compare(original,original,o),/AUTHENTICATED_CONTENT_MISMATCH/);});

test('recursive native role cycle rejects even when identically copied into both inventories',()=>{const i=fresh();i.nativePrivileges.definitions.PUBLIC.roles.push({GRANTED_ROLE:'SVI',ADMIN_OPTION:'NO'});i.nativePrivileges.definitions.SVI.roles.push({GRANTED_ROLE:'PUBLIC',ADMIN_OPTION:'NO'});assert.throws(()=>compare(i,i),/ROLE_CYCLE/);});
test('incomplete native role closure rejects rather than treating it as empty permission',()=>{const i=fresh();i.nativePrivileges.definitions.PUBLIC.roles.push({GRANTED_ROLE:'MISSING_ROLE',ADMIN_OPTION:'NO'});assert.throws(()=>compare(i,i),/INCOMPLETE_ROLE_DEFINITION/);});

test('coordinated same-version source and destination view change is not accepted as a new interpretation',()=>{const i=fresh();i.restoreCatalog.categories.viewDefinitions.rows[0].TEXT+=' ';assert.throws(()=>compare(i,i),/VIEW_DEFINITION_DRIFT/);});
