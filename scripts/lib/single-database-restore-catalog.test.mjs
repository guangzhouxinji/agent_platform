import test from 'node:test';import assert from 'node:assert/strict';import {readFileSync} from 'node:fs';
import {assertRestoreCatalog,deriveCompleteNativeForeignKeys,RESTORE_CATALOG_PROFILE_SHA256} from './single-database-restore-catalog.mjs';
const path=new URL('../../docs/evidence/single-database-installation-20261009/approved-execution/restore-profile51/historical-lossless-unit-input51.json',import.meta.url);
// Historical, lossless projected input. Its JDBC metadata is UNIT_PROJECTED, not a live capture.
// These tests neither run Docker/DM8 nor prove native recovery or authenticated protection.
const original=JSON.parse(readFileSync(path,'utf8')),fresh=()=>structuredClone(original);
const fk=i=>i.restoreCatalog.categories.nativeConstraints.rows.find(c=>c['TYPE$']==='F');
test('historical lossless input preserves exact INFO3 bigint decimal above JS safe range',()=>{
 const rows=assertRestoreCatalog(original.restoreCatalog).categories.nativeObjects.rows;
 const value=rows.find(v=>v.NAME==='SREF_CON_TAB134219310_LEVEL').INFO3;
 assert.equal(value,'1157425108529184832');assert.equal(typeof value,'string');assert.match(RESTORE_CATALOG_PROFILE_SHA256,/^[a-f0-9]{64}$/);
});
test('native set includes every P and U target: 281 FK/447 pairs/62 explained JDBC gaps, no restored claim',()=>{
 const before=JSON.stringify(original.schemas.map(s=>s.foreignKeys));const r=deriveCompleteNativeForeignKeys(original);
 assert.equal(r.nativeFkCount,281);assert.equal(r.nativeOrderedColumnPairs,447);assert.equal(r.jdbcMissingFkCount,62);assert.equal(r.restoredEquivalenceProven,false);assert.equal(JSON.stringify(original.schemas.map(s=>s.foreignKeys)),before);
});
for(const [name,mutate,pattern] of [
 ['version changed',i=>i.restoreCatalog.databaseVersion='8.1.4.171',/PROFILE_OR_VERSION/],
 ['missing category',i=>delete i.restoreCatalog.categories.hiddenTexts,/CATEGORY_SET/],
 ['unknown category',i=>i.restoreCatalog.categories.extra={columns:[],rows:[]},/CATEGORY_SET/],
 ['unsafe integer JSON number',i=>i.restoreCatalog.categories.nativeObjects.rows[0].INFO3=Number.MAX_SAFE_INTEGER+2,/LOSSY_INTEGER/],
 ['decimal exponent',i=>{let c=i.restoreCatalog.categories.nativeObjects.columns.find(c=>c.label==='INFO3');c.jdbcType=3;i.restoreCatalog.categories.nativeObjects.rows[0].INFO3='1e18';},/LOSSY_DECIMAL/],
 ['empty category missing metadata',i=>i.restoreCatalog.categories.indexExpressions.columns=[],/FIELD_SET/],
 ['empty category unknown JDBC type',i=>i.restoreCatalog.categories.indexExpressions.columns[0].jdbcType=9999,/UNKNOWN_JDBC_TYPE/],
 ['duplicate metadata label',i=>i.restoreCatalog.categories.nativeObjects.columns.push(i.restoreCatalog.categories.nativeObjects.columns[0]),/FIELD_SET/],
 ['hidden table nonempty',i=>i.restoreCatalog.categories.hiddenTableCounts.rows[0].ROW_COUNT='1',/UNSUPPORTED_HIDDEN_ROWS/],
 ['hidden table missing',i=>i.restoreCatalog.categories.hiddenTableCounts.rows.pop(),/HIDDEN_TABLE_COVERAGE/],
 ['hidden table extra',i=>i.restoreCatalog.categories.hiddenTableCounts.rows.push({...i.restoreCatalog.categories.hiddenTableCounts.rows[0],TABLE_NAME:'UNKNOWN'}),/HIDDEN_TABLE_COVERAGE/],
 ['hidden nonempty dependencies',i=>i.restoreCatalog.categories.hiddenDependencyCount.rows[0].ROW_COUNT='1',/UNSUPPORTED_HIDDEN_DEPENDENCY/],
 ['unknown extra row field',i=>i.restoreCatalog.categories.nativeObjects.rows[0].NEW_FIELD='1',/ROW_FIELD_SET/],
 ['one native FK missing',i=>{let c=fk(i);i.restoreCatalog.categories.nativeConstraints.rows=i.restoreCatalog.categories.nativeConstraints.rows.filter(v=>v!==c);},/CONSTRAINT_COVERAGE|EXTRA_JDBC_FK/],
 ['duplicate native constraint identity',i=>i.restoreCatalog.categories.nativeConstraints.rows.push(fk(i)),/DUPLICATE_IDENTITY/],
 ['missing native parent',i=>{let c=fk(i);c.FINDEXID='999999999';},/FK_RELATION_MISMATCH/],
 ['wrong native action',i=>fk(i).FACTION=' C',/FK_RELATION_MISMATCH/],
 ['wrong rule compatibility',i=>{let c=fk(i);i.schemas.find(s=>s.schema===c.SCHEMA_NAME).constraints.find(v=>v.CONSTRAINT_NAME===c.CONSTRAINT_NAME).DELETE_RULE='CASCADE';},/FK_RELATION_MISMATCH/],
 ['function bit with successful empty expressions',i=>i.restoreCatalog.categories.nativeIndexes.rows[0].XTYPE='19',/UNSUPPORTED_FUNCTION_INDEX/],
 ['unproven zero-key index',i=>i.restoreCatalog.categories.nativeIndexes.rows.find(v=>v.KEYNUM==='0').FLAG='0',/UNPROVEN_ZERO_KEY_INDEX/],
 ['missing index column',i=>{let c=fk(i),ix=i.restoreCatalog.categories.nativeIndexes.rows.find(v=>v.INDEX_ID===c.INDEXID),s=i.schemas.find(v=>v.schema===c.SCHEMA_NAME);s.indexColumns=s.indexColumns.filter(v=>v.INDEX_NAME!==ix.INDEX_NAME);},/INDEX_COLUMNS_INCOMPLETE/],
 ['unknown index column',i=>{let c=fk(i),ix=i.restoreCatalog.categories.nativeIndexes.rows.find(v=>v.INDEX_ID===c.INDEXID);i.schemas.find(v=>v.schema===c.SCHEMA_NAME).indexColumns.find(v=>v.INDEX_NAME===ix.INDEX_NAME).COLUMN_NAME='UNKNOWN';},/INDEX_COLUMNS_INCOMPLETE/],
 ['JDBC P gap cannot be explained by known U gap',i=>{let s=i.schemas.find(s=>s.foreignKeys.length),name=s.foreignKeys[0].FK_NAME;s.foreignKeys=s.foreignKeys.filter(f=>f.FK_NAME!==name);},/JDBC_FK_UNEXPLAINED_GAP/],
 ['JDBC extra FK',i=>i.schemas[0].foreignKeys.push({...i.schemas[0].foreignKeys[0],FK_NAME:'UNKNOWN'}),/EXTRA_JDBC_FK/],
 ['JDBC repeated ordinal',i=>{let s=i.schemas.find(s=>s.foreignKeys.length);s.foreignKeys.push({...s.foreignKeys[0]});},/JDBC_FK_CONFLICT|DUPLICATE_IDENTITY/],
 ['JDBC wrong ordered column',i=>i.schemas.find(s=>s.foreignKeys.length).foreignKeys[0].FKCOLUMN_NAME='UNKNOWN',/FK_RELATION_MISMATCH/],
 ['inconsistent declared composite order',i=>{let c=fk(i),s=i.schemas.find(s=>s.schema===c.SCHEMA_NAME);s.constraintColumns.find(v=>v.CONSTRAINT_NAME===c.CONSTRAINT_NAME).COLUMN_NAME='UNKNOWN';},/FK_RELATION_MISMATCH/]
])test(name,()=>{const i=fresh();mutate(i);assert.throws(()=>deriveCompleteNativeForeignKeys(i),pattern);});
test('dictionary row reordering does not drop or merge any FK multiplicity',()=>{const i=fresh();for(const c of Object.values(i.restoreCatalog.categories))c.rows.reverse();const r=deriveCompleteNativeForeignKeys(i);assert.equal(r.nativeFkCount,281);assert.equal(r.nativeOrderedColumnPairs,447);});

test('unknown supplementary root field is refused',()=>{const i=fresh();i.restoreCatalog.extra='unapproved';assert.throws(()=>assertRestoreCatalog(i.restoreCatalog),/ROOT_FIELD_SET/);});
for(const [name,type,value,pass] of [
 ['UTF8 exact 2MiB text',12,'é'.repeat(1048576),true],
 ['UTF8 over 2MiB text',12,'é'.repeat(1048576)+'a',false],
 ['quoted text counts content not JSON escapes',12,'"'.repeat(2097152),true],
 ['binary exact 2MiB',-3,{binaryBase64:Buffer.alloc(2097152).toString('base64')},true],
 ['binary over 2MiB',-3,{binaryBase64:Buffer.alloc(2097153).toString('base64')},false]
])test(name,()=>{const c=fresh().restoreCatalog,k='hiddenTexts';c.categories[k].columns.find(v=>v.label==='TXT').jdbcType=type;c.categories[k].rows=[{ID:'1',SEQNO:'0',TXT:value}];if(pass)assert.equal(assertRestoreCatalog(c),c);else assert.throws(()=>assertRestoreCatalog(c),/CELL_LIMIT/);});
