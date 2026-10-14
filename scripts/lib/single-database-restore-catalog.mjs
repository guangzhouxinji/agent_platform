import {readFileSync} from 'node:fs';
import {assertKnownRestoreViews} from './single-database-restore-view-contract.mjs';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
export const RESTORE_CATALOG_PROFILE=JSON.parse(readFileSync(new URL('./single-database-restore-catalog-profile.json',import.meta.url),'utf8'));
export const RESTORE_CATALOG_PROFILE_SHA256=hash(canonical(RESTORE_CATALOG_PROFILE));
const fail=code=>{throw new Error(code);};
const textTypes=new Set([1,12,-1,2005,-15,-9,-16,2011,91,92,93,2013,2014]);
const integerTypes=new Set([-6,5,4,-5]);
const decimalTypes=new Set([2,3]);
const booleanTypes=new Set([16,-7]);
const binaryTypes=new Set([-2,-3,-4,2004]);
const scalar=(v,column)=>{
 if(v===null)return;
 const type=column.jdbcType;
 if(integerTypes.has(type)){if(typeof v!=='string'||! /^(?:0|-?[1-9][0-9]*)$/.test(v))fail('RESTORE_CATALOG_LOSSY_INTEGER');}
 else if(decimalTypes.has(type)){if(typeof v!=='string'||! /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(v))fail('RESTORE_CATALOG_LOSSY_DECIMAL');}
 else if(textTypes.has(type)){if(typeof v!=='string')fail('RESTORE_CATALOG_TEXT_TYPE');}
 else if(booleanTypes.has(type)){if(typeof v!=='boolean')fail('RESTORE_CATALOG_BOOLEAN_TYPE');}
 else if(binaryTypes.has(type)){
  if(!v||typeof v!=='object'||canonical(Object.keys(v))!==canonical(['binaryBase64'])||typeof v.binaryBase64!=='string'||Buffer.from(v.binaryBase64,'base64').toString('base64')!==v.binaryBase64)fail('RESTORE_CATALOG_BINARY_TYPE');
 }else fail('RESTORE_CATALOG_UNKNOWN_JDBC_TYPE');
 const bytes=binaryTypes.has(type)?Buffer.from(v.binaryBase64,'base64').length:typeof v==='string'?Buffer.byteLength(v,'utf8'):1;
 if(bytes>RESTORE_CATALOG_PROFILE.limits.maxCellBytes)fail('RESTORE_CATALOG_CELL_LIMIT');
};
/** Structural/type guard only; neither this nor the FK guard proves restored equivalence. */
export function assertRestoreCatalog(c){
 const p=RESTORE_CATALOG_PROFILE;
 if(!c||c.format!=='DM8_RESTORE_CATALOG_CAPTURE/v1'||c.profileId!==p.profileId||c.databaseVersion!==p.databaseVersion)fail('RESTORE_CATALOG_PROFILE_OR_VERSION');
 if(canonical(Object.keys(c).sort())!==canonical(['categories','databaseVersion','format','profileId']))fail('RESTORE_CATALOG_ROOT_FIELD_SET');
 if(!c.categories||canonical(Object.keys(c.categories).sort())!==canonical(Object.keys(p.categories).sort()))fail('RESTORE_CATALOG_CATEGORY_SET');
 if(Buffer.byteLength(canonical(c))>p.limits.maxCatalogBytes)fail('RESTORE_CATALOG_CAPACITY');
 for(const [key,spec] of Object.entries(p.categories)){
  const category=c.categories[key];
  if(!category||canonical(Object.keys(category).sort())!==canonical(['columns','rows'])||!Array.isArray(category.columns)||!Array.isArray(category.rows)||category.rows.length>spec.maxRows)fail('RESTORE_CATALOG_CATEGORY_SHAPE');
  if(canonical(category.columns.map(v=>v.label).sort())!==canonical([...spec.fields].sort()))fail('RESTORE_CATALOG_FIELD_SET');
  for(const column of category.columns){if(canonical(Object.keys(column).sort())!==canonical(['jdbcType','label','typeName'])||!Number.isSafeInteger(column.jdbcType)||typeof column.typeName!=='string'||!column.typeName)fail('RESTORE_CATALOG_COLUMN_TYPE');
   // Validate metadata even for a successfully empty category.
   if(![textTypes,integerTypes,decimalTypes,booleanTypes,binaryTypes].some(types=>types.has(column.jdbcType)))fail('RESTORE_CATALOG_UNKNOWN_JDBC_TYPE');
  }
  for(const row of category.rows){if(!row||typeof row!=='object'||canonical(Object.keys(row).sort())!==canonical([...spec.fields].sort()))fail('RESTORE_CATALOG_ROW_FIELD_SET');for(const column of category.columns)scalar(row[column.label],column);}
  if(spec.allCounts!==undefined&&category.rows.some(row=>row.ROW_COUNT!==spec.allCounts))fail('RESTORE_CATALOG_UNSUPPORTED_HIDDEN_ROWS');
  if(spec.requiredCount!==undefined&&(category.rows.length!==1||category.rows[0].ROW_COUNT!==spec.requiredCount))fail('RESTORE_CATALOG_UNSUPPORTED_HIDDEN_DEPENDENCY');
 }
 assertKnownRestoreViews(c.categories.viewDefinitions.rows);
 return c;
}
const unique=(rows,key)=>{const map=new Map();for(const row of rows){const k=key(row);if(map.has(k))fail('RESTORE_CATALOG_DUPLICATE_IDENTITY');map.set(k,row);}return map;};
const smallInteger=v=>{if(!/^(?:0|[1-9][0-9]*)$/.test(String(v))||!Number.isSafeInteger(Number(v)))fail('RESTORE_CATALOG_BAD_ORDINAL');return Number(v);};
const exact=(a,b)=>{if(canonical(a)!==canonical(b))fail('RESTORE_CATALOG_FK_RELATION_MISMATCH');};
/** Read the full native P/U FK set. Keep legacy JDBC raw, including its known U-target gap. */
export function deriveCompleteNativeForeignKeys(inventory){
 const catalog=assertRestoreCatalog(inventory.restoreCatalog),cat=catalog.categories;
 if(!Array.isArray(inventory.schemas)||canonical(inventory.schemas.map(s=>s.schema).sort())!==canonical([...RESTORE_CATALOG_PROFILE.schemas].sort()))fail('RESTORE_CATALOG_SCHEMA_SET');
 const schemas=unique(inventory.schemas,s=>s.schema),objects=unique(cat.nativeObjects.rows,o=>o.ID),indexes=unique(cat.nativeIndexes.rows,i=>i.INDEX_ID),constraints=unique(cat.nativeConstraints.rows,c=>c.ID);
 const compatibility=unique(inventory.schemas.flatMap(s=>s.constraints.map(c=>({...c,schema:s.schema}))),c=>`${c.schema}:${c.CONSTRAINT_NAME}`);
 const hiddenTables=[...objects.values()].filter(o=>o['TYPE$']==='SCHOBJ'&&o['SUBTYPE$']==='UTAB'&&(BigInt(o.INFO3)&0x100000000n)!==0n);
 const counts=unique(cat.hiddenTableCounts.rows,o=>o.SCHEMA_NAME+':'+o.TABLE_NAME);
 if(counts.size!==hiddenTables.length||hiddenTables.some(o=>!counts.has(o.SCHEMA_NAME+':'+o.NAME)))fail('RESTORE_CATALOG_HIDDEN_TABLE_COVERAGE');
 const keys=new Map();
 for(const index of indexes.values()){
  const s=schemas.get(index.SCHEMA_NAME),table=objects.get(index.TABLE_ID),object=objects.get(index.INDEX_ID);
  if(!s||!table||!object||table.NAME!==index.TABLE_NAME||table.SCHEMA_NAME!==index.SCHEMA_NAME||object.NAME!==index.INDEX_NAME||object.PID!==index.TABLE_ID)fail('RESTORE_CATALOG_INDEX_REFERENCE');
  const rows=s.indexColumns.filter(r=>r.INDEX_NAME===index.INDEX_NAME).sort((a,b)=>smallInteger(a.COLUMN_POSITION)-smallInteger(b.COLUMN_POSITION));
  const count=smallInteger(index.KEYNUM);
  if(rows.length!==count||rows.some((r,n)=>smallInteger(r.COLUMN_POSITION)!==n+1||r.TABLE_NAME!==index.TABLE_NAME||r.TABLE_OWNER!==index.SCHEMA_NAME||r.INDEX_OWNER!==index.SCHEMA_NAME||!s.columns.some(c=>c.TABLE_NAME===index.TABLE_NAME&&c.COLUMN_NAME===r.COLUMN_NAME)))fail('RESTORE_CATALOG_INDEX_COLUMNS_INCOMPLETE');
  if(count===0&&(index.XTYPE!=='0'||(BigInt(index.FLAG)&1n)!==1n))fail('RESTORE_CATALOG_UNPROVEN_ZERO_KEY_INDEX');
  if((BigInt(index.XTYPE)&2n)!==0n)fail('RESTORE_CATALOG_UNSUPPORTED_FUNCTION_INDEX');
  keys.set(index.INDEX_ID,rows);
 }
 if(cat.indexExpressions.rows.length)fail('RESTORE_CATALOG_UNSUPPORTED_FUNCTION_INDEX');
 const parents=unique([...constraints.values()].filter(c=>['P','U'].includes(c['TYPE$'])),c=>c.INDEXID);
 const result=[],groups=[];
 for(const c of constraints.values()){
  const compat=compatibility.get(`${c.SCHEMA_NAME}:${c.CONSTRAINT_NAME}`),object=objects.get(c.ID),table=objects.get(c.TABLEID);
  if(!compat||!object||!table||object.NAME!==c.CONSTRAINT_NAME||object.SCHEMA_NAME!==c.SCHEMA_NAME||table.SCHEMA_NAME!==c.SCHEMA_NAME||table.NAME!==compat.TABLE_NAME||compat.CONSTRAINT_TYPE!==(c['TYPE$']==='F'?'R':c['TYPE$']))fail('RESTORE_CATALOG_CONSTRAINT_REFERENCE');
  if(c['TYPE$']!=='F')continue;
  const own=indexes.get(c.INDEXID),ref=indexes.get(c.FINDEXID),parent=parents.get(c.FINDEXID),a=keys.get(c.INDEXID),b=keys.get(c.FINDEXID);
  if(!own||!ref||!parent||!a?.length||!b?.length||a.length!==b.length||own.TABLE_ID!==c.TABLEID||ref.TABLE_ID!==parent.TABLEID||c.FACTION!=='  '||compat.DELETE_RULE!=='NO ACTION'||compat.R_OWNER!==parent.SCHEMA_NAME||compat.R_CONSTRAINT_NAME!==parent.CONSTRAINT_NAME||compat.INDEX_NAME!==ref.INDEX_NAME)fail('RESTORE_CATALOG_FK_RELATION_MISMATCH');
  const ownDeclared=schemas.get(c.SCHEMA_NAME).constraintColumns.filter(v=>v.CONSTRAINT_NAME===c.CONSTRAINT_NAME).sort((x,y)=>smallInteger(x.POSITION)-smallInteger(y.POSITION));
  const parentDeclared=schemas.get(parent.SCHEMA_NAME).constraintColumns.filter(v=>v.CONSTRAINT_NAME===parent.CONSTRAINT_NAME).sort((x,y)=>smallInteger(x.POSITION)-smallInteger(y.POSITION));
  exact(ownDeclared.map(v=>[String(v.POSITION),v.COLUMN_NAME]),a.map((v,n)=>[String(n+1),v.COLUMN_NAME]));
  exact(parentDeclared.map(v=>[String(v.POSITION),v.COLUMN_NAME]),b.map((v,n)=>[String(n+1),v.COLUMN_NAME]));
  const native=a.map((aa,n)=>({FK_NAME:c.CONSTRAINT_NAME,FKTABLE_SCHEM:c.SCHEMA_NAME,FKTABLE_NAME:table.NAME,FKCOLUMN_NAME:aa.COLUMN_NAME,KEY_SEQ:String(n+1),PK_NAME:parent.CONSTRAINT_NAME,PKTABLE_SCHEM:ref.SCHEMA_NAME,PKTABLE_NAME:ref.TABLE_NAME,PKCOLUMN_NAME:b[n].COLUMN_NAME,UPDATE_RULE:'3',DELETE_RULE:'3'}));
  const observed=schemas.get(c.SCHEMA_NAME).foreignKeys.filter(f=>f.FK_NAME===c.CONSTRAINT_NAME);
  if(observed.length){if(observed.length!==native.length)fail('RESTORE_CATALOG_JDBC_FK_CONFLICT');const byOrdinal=unique(observed,f=>f.KEY_SEQ);for(const row of native){const actual=byOrdinal.get(row.KEY_SEQ);if(!actual)fail('RESTORE_CATALOG_JDBC_FK_CONFLICT');exact(Object.fromEntries(Object.keys(row).map(k=>[k,actual[k]])),row);}}
  else if(parent['TYPE$']!=='U')fail('RESTORE_CATALOG_JDBC_FK_UNEXPLAINED_GAP');
  groups.push({schema:c.SCHEMA_NAME,constraint:c.CONSTRAINT_NAME,nativeConstraintId:c.ID,referencedConstraintType:parent['TYPE$'],jdbcPresent:observed.length>0,orderedColumns:native});result.push(...native);
 }
 if(compatibility.size!==constraints.size)fail('RESTORE_CATALOG_CONSTRAINT_COVERAGE');
 for(const s of schemas.values())for(const f of s.foreignKeys)if(!groups.some(g=>g.schema===s.schema&&g.constraint===f.FK_NAME))fail('RESTORE_CATALOG_EXTRA_JDBC_FK');
 return {format:'DM8_COMPLETE_NATIVE_FOREIGN_KEYS/v1',groups,orderedColumns:result,nativeFkCount:groups.length,nativeOrderedColumnPairs:result.length,jdbcMissingFkCount:groups.filter(g=>!g.jdbcPresent).length,legacyJdbcRawPreserved:true,restoredEquivalenceProven:false};
}

