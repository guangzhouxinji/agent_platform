import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {deriveCompleteNativeForeignKeys} from './single-database-restore-catalog.mjs';
const fail=code=>{throw new Error(code);};
const same=(a,b)=>canonical(a)===canonical(b);
const identity=value=>hash(canonical(value));
function indexUnique(rows,key){const out=new Map();for(const row of rows){const k=key(row);if(out.has(k))fail('RESTORE_SEMANTIC_AMBIGUOUS_IDENTITY');out.set(k,row);}return out;}
export function controlledNames(origin){
 const proof=origin.files.find(f=>f.kind==='CONTROLLED_SOURCE_ORIGIN_PROOF').value;
 const history=origin.files.find(f=>f.kind==='CONTROLLED_MIGRATION_HISTORY').value;
 const statements=indexUnique(history.migrations,m=>m.schema+':'+m.id);
 const manualConstraints=new Set(proof.manualConstraints.map(p=>p.schema+':'+p.constraint));
 const generatedConstraints=new Set(proof.generatedCandidates.map(p=>p.schema+':'+p.name));
 const manualIndexes=new Set(proof.manualIndexes.map(p=>{const m=statements.get(p.schema+':'+p.migrationId),sql=m?.statements[p.statementOrdinal],match=typeof sql==='string'?sql.match(/^CREATE\s+(?:UNIQUE\s+)?INDEX\s+([A-Za-z][A-Za-z0-9_]*)\s+ON\s+/i):null;if(!match)fail('RESTORE_SEMANTIC_INDEX_ORIGIN');return p.schema+':'+match[1].toUpperCase();}));
 if(manualConstraints.size!==proof.manualConstraints.length||generatedConstraints.size!==proof.generatedCandidates.length||manualIndexes.size!==proof.manualIndexes.length)fail('RESTORE_SEMANTIC_AMBIGUOUS_ORIGIN');
 return {manualConstraints,generatedConstraints,manualIndexes};
}
/** Lossless lexer: only independent SQL identifier tokens may change, never literals/comments/space. */
export function rewriteRestoreSql(sql,names){
 if(typeof sql!=='string')fail('RESTORE_SEMANTIC_SQL_TEXT_REQUIRED');
 let result='',n=0;const references=new Set();
 while(n<sql.length){const tail=sql.slice(n);let token;
  if(tail.startsWith('--')){const end=tail.indexOf('\n');token=end<0?tail:tail.slice(0,end);}
  else if(tail.startsWith('/*')){const end=tail.indexOf('*/',2);if(end<0)fail('RESTORE_SEMANTIC_UNTERMINATED_SQL');token=tail.slice(0,end+2);}
  else if(tail[0]==="'"){const match=tail.match(/^'(?:''|[^'])*'/);if(!match)fail('RESTORE_SEMANTIC_UNTERMINATED_SQL');token=match[0];}
  else if(tail[0]==='"'){const match=tail.match(/^"(?:""|[^"])*"/);if(!match)fail('RESTORE_SEMANTIC_UNTERMINATED_SQL');token=match[0];const name=token.slice(1,-1).replaceAll('""','"');if(names.has(name)){references.add(name);result+='"'+names.get(name).replaceAll('"','""')+'"';n+=token.length;continue;}}
  else{const match=tail.match(/^[A-Za-z_][A-Za-z_0-9$]*/);token=match?match[0]:tail[0];if(names.has(token)){references.add(token);result+=names.get(token);n+=token.length;continue;}}
  result+=token;n+=token.length;
 }
 return {text:result,references};
}
/** All identity mappings are bijections justified by controlled declarations or complete native roles. */
export function buildRestoreSemanticMap(inventory,controlled,{source=false}={}){
 const cat=inventory.restoreCatalog.categories,schemas=indexUnique(inventory.schemas,s=>s.schema),objects=indexUnique(cat.nativeObjects.rows,o=>o.ID),constraints=indexUnique(cat.nativeConstraints.rows,c=>c.ID);
 const compatibility=indexUnique(inventory.schemas.flatMap(s=>s.constraints.map(c=>({...c,schema:s.schema}))),c=>c.schema+':'+c.CONSTRAINT_NAME);
 const fks=deriveCompleteNativeForeignKeys(inventory),fkById=indexUnique(fks.groups,g=>g.nativeConstraintId);
 const ids=new Map(),names=new Map(),roles=new Map(),semanticSeen=new Set(),timeObjects=new Set(),graphs=[];
 const bind=(id,key)=>{const semantic=identity(key);if(ids.has(id)||semanticSeen.has(semantic))fail('RESTORE_SEMANTIC_AMBIGUOUS_IDENTITY');ids.set(id,semantic);semanticSeen.add(semantic);roles.set(id,key);return semantic;};
 const rename=(schema,name,semantic)=>{const key=schema+':'+name;if(names.has(key))fail('RESTORE_SEMANTIC_AMBIGUOUS_NAME');names.set(key,'@'+semantic);};
 // Schema IDs are not included as nativeObjects rows. Prove each one from every scoped object.
 for(const schema of schemas.keys()){const schids=new Set([...objects.values()].filter(o=>o.SCHEMA_NAME===schema).map(o=>o.SCHID));if(schids.size!==1)fail('RESTORE_SEMANTIC_SCHEMA_ID_AMBIGUOUS');bind([...schids][0],['SCHEMA',schema]);}
 const internalTables=[...objects.values()].filter(o=>o['SUBTYPE$']==='UTAB'&&(BigInt(o.INFO3)&0x100000000n)!==0n);
 for(const o of objects.values())if(o['TYPE$']==='SCHOBJ'&&o['SUBTYPE$']==='UTAB'&&!internalTables.includes(o))bind(o.ID,['TABLE',o.SCHEMA_NAME,o.NAME]);
 const manualFound=new Set(),generatedKeys=new Set();
 for(const c of constraints.values()){
  const s=schemas.get(c.SCHEMA_NAME),compat=compatibility.get(c.SCHEMA_NAME+':'+c.CONSTRAINT_NAME),table=objects.get(c.TABLEID);
  if(!s||!compat||!table||!ids.has(table.ID))fail('RESTORE_SEMANTIC_CONSTRAINT_TABLE');
  const columns=s.constraintColumns.filter(v=>v.CONSTRAINT_NAME===c.CONSTRAINT_NAME).sort((a,b)=>c['TYPE$']==='C'?a.COLUMN_NAME.localeCompare(b.COLUMN_NAME):Number(a.POSITION)-Number(b.POSITION));
  if(columns.some((r,n)=>c['TYPE$']==='C'?r.POSITION!==null:!Number.isSafeInteger(Number(r.POSITION))||Number(r.POSITION)!==n+1))fail('RESTORE_SEMANTIC_CONSTRAINT_ORDINAL');
  const fk=fkById.get(c.ID);
  const structure=[c.SCHEMA_NAME,table.NAME,c['TYPE$'],columns.map(v=>[v.POSITION===null?null:String(v.POSITION),v.COLUMN_NAME]),c.CHECKINFO,compat.SEARCH_CONDITION,fk?fk.orderedColumns.map(v=>[v.KEY_SEQ,v.FKCOLUMN_NAME,v.PKTABLE_SCHEM,v.PKTABLE_NAME,v.PKCOLUMN_NAME,v.UPDATE_RULE,v.DELETE_RULE]):null];
  const manual=controlled.manualConstraints.has(c.SCHEMA_NAME+':'+c.CONSTRAINT_NAME);
  const key=manual?['MANUAL_CONSTRAINT',c.SCHEMA_NAME,c.CONSTRAINT_NAME]:['GENERATED_CONSTRAINT',...structure];
  if(manual)manualFound.add(c.SCHEMA_NAME+':'+c.CONSTRAINT_NAME);
  else{if(source&&!controlled.generatedConstraints.has(c.SCHEMA_NAME+':'+c.CONSTRAINT_NAME))fail('RESTORE_SEMANTIC_UNAUTHENTICATED_GENERATED_CONSTRAINT');if(c.CONSTRAINT_NAME!=='CONS'+c.ID)fail('RESTORE_SEMANTIC_GENERATED_CONSTRAINT_IDENTITY');const k=identity(structure);if(generatedKeys.has(k))fail('RESTORE_SEMANTIC_AMBIGUOUS_GENERATED_CONSTRAINT');generatedKeys.add(k);}
  const semantic=bind(c.ID,key);if(!manual)rename(c.SCHEMA_NAME,c.CONSTRAINT_NAME,semantic);
  const o=objects.get(c.ID);
  if(!o||o['SUBTYPE$']!=='CONS'||o.PID!==c.TABLEID||o.SCHID!==table.SCHID||o.CRTDATE!==compat.LAST_CHANGE||typeof o.CRTDATE!=='string')fail('RESTORE_SEMANTIC_CONSTRAINT_CREATION_LINK');
  timeObjects.add(c.ID);
 }
 if(!same([...manualFound].sort(),[...controlled.manualConstraints].sort())||generatedKeys.size!==controlled.generatedConstraints.size)fail('RESTORE_SEMANTIC_CONTROLLED_CONSTRAINT_COVERAGE');
 // The four native self-reference graphs: complete flags, columns, index roles and SQL references.
 const internalTriggers=[...objects.values()].filter(o=>o['SUBTYPE$']==='TRIG'&&(BigInt(o.INFO1)&32n)!==0n),consumed=new Set(),textIds=new Set(),columnIds=new Set();
 const texts=indexUnique(cat.hiddenTexts.rows,t=>t.ID);
 for(const fk of fks.groups){const pairs=fk.orderedColumns;if(!pairs.length||pairs[0].FKTABLE_SCHEM!==pairs[0].PKTABLE_SCHEM||pairs[0].FKTABLE_NAME!==pairs[0].PKTABLE_NAME)continue;
  const c=constraints.get(fk.nativeConstraintId),schema=c.SCHEMA_NAME,table=objects.get(c.TABLEID),graphNames=new Map(),nodeRoles={};
  for(const role of ['LEVEL','REFING','REFED','BS','AR','AS']){
   const isTable=['LEVEL','REFING','REFED'].includes(role),name='SREF_CON_'+(isTable?'TAB':'TRIG')+c.ID+'_'+role;
   const found=[...objects.values()].filter(o=>o.SCHEMA_NAME===schema&&o.NAME===name);if(found.length!==1)fail('RESTORE_SEMANTIC_INCOMPLETE_SELF_GRAPH');const o=found[0];
   if(o['TYPE$']!=='SCHOBJ'||o['SUBTYPE$']!==(isTable?'UTAB':'TRIG')||o.VALID!=='Y'||o.SCHID!==table.SCHID||consumed.has(o.ID))fail('RESTORE_SEMANTIC_SELF_GRAPH_NODE');
   if(isTable){if(o.PID!=='-1'||(BigInt(o.INFO3)&0x100000040n)!==0x100000040n||(BigInt(o.INFO3)&0x10000n)!==0n)fail('RESTORE_SEMANTIC_SELF_GRAPH_FLAGS');
    const cols=cat.hiddenColumns.rows.filter(v=>v.ID===o.ID).sort((a,b)=>Number(a.COLID)-Number(b.COLID));
    if(!cols.length||cols.some((r,n)=>r.COLID!==String(n)||r.NULLABLE$!=='Y'||r.DEFVAL!==null||r.SCHEMA_NAME!==schema||r.TABLE_NAME!==name))fail('RESTORE_SEMANTIC_SELF_GRAPH_COLUMNS');
    if(role==='LEVEL'){if(cols.length!==1||cols[0].NAME!=='N_LEVEL'||cols[0]['TYPE$']!=='INTEGER')fail('RESTORE_SEMANTIC_SELF_GRAPH_COLUMNS');}
    else{if(cols.length!==pairs.length)fail('RESTORE_SEMANTIC_SELF_GRAPH_COLUMNS');for(const [n,col] of cols.entries()){const original=schemas.get(schema).columns.find(v=>v.TABLE_NAME===table.NAME&&v.COLUMN_NAME===pairs[n][role==='REFING'?'FKCOLUMN_NAME':'PKCOLUMN_NAME']);if(!original||col.NAME!=='COL'+(n+1)||col['TYPE$']!==original.DATA_TYPE||col['LENGTH$']!==String(original.DATA_LENGTH)||col.SCALE!==String(original.DATA_SCALE))fail('RESTORE_SEMANTIC_SELF_GRAPH_COLUMN_TYPE');}}
    cols.forEach(v=>columnIds.add(v.ID+':'+v.COLID));const ix=cat.nativeIndexes.rows.filter(v=>v.TABLE_ID===o.ID);if(ix.length!==1||ix[0].XTYPE!=='0'||ix[0].FLAG!=='9'||ix[0].KEYNUM!=='0')fail('RESTORE_SEMANTIC_SELF_GRAPH_INDEX');
   }else if(o.PID!==table.ID||(BigInt(o.INFO1)&32n)!==32n)fail('RESTORE_SEMANTIC_SELF_GRAPH_FLAGS');
   const semantic=bind(o.ID,['SELF_GRAPH',ids.get(c.ID),isTable?'TABLE':'TRIGGER',role]);rename(schema,name,semantic);graphNames.set(name,'@'+semantic);nodeRoles[role]=o;consumed.add(o.ID);
  }
  for(const role of ['BS','AR','AS']){const o=nodeRoles[role],text=texts.get(o.ID);if(!text||text.SEQNO!=='0'||!text.TXT)fail('RESTORE_SEMANTIC_SELF_GRAPH_TEXT');const tokens=rewriteRestoreSql(text.TXT,graphNames).references,expected=['REFING','REFED',...(role==='AR'?[]:['LEVEL'])].map(r=>nodeRoles[r].NAME);if(!tokens.has(o.NAME)||!same([...tokens].filter(v=>expected.includes(v)||['LEVEL','REFING','REFED'].some(r=>nodeRoles[r].NAME===v)).sort(),expected.sort())||!text.TXT.includes('ON "'+schema+'"."'+table.NAME+'"'))fail('RESTORE_SEMANTIC_SELF_GRAPH_TEXT_REFERENCES');textIds.add(o.ID);}
  graphs.push({parentIdentitySha256:ids.get(c.ID),nodes:6});
 }
 if(graphs.length!==4||consumed.size!==24||internalTables.length!==12||internalTriggers.length!==12||[...internalTables,...internalTriggers].some(o=>!consumed.has(o.ID))||texts.size!==textIds.size||cat.hiddenColumns.rows.length!==columnIds.size)fail('RESTORE_SEMANTIC_SELF_GRAPH_COVERAGE');
 const manualIndexFound=new Set();
 for(const ix of cat.nativeIndexes.rows){const schema=ix.SCHEMA_NAME,table=objects.get(ix.TABLE_ID),o=objects.get(ix.INDEX_ID);if(!table||!ids.has(table.ID)||!o||o['SUBTYPE$']!=='INDEX'||o.PID!==table.ID||o.NAME!==ix.INDEX_NAME)fail('RESTORE_SEMANTIC_INDEX_OBJECT');
  const manual=controlled.manualIndexes.has(schema+':'+ix.INDEX_NAME);let key;
  if(manual){if(ix.FLAG!=='0')fail('RESTORE_SEMANTIC_MANUAL_INDEX_FLAG');manualIndexFound.add(schema+':'+ix.INDEX_NAME);key=['MANUAL_INDEX',schema,ix.INDEX_NAME];}
  else{const parents=[...constraints.values()].filter(c=>c.INDEXID===ix.INDEX_ID).map(c=>ids.get(c.ID)).sort();if((BigInt(ix.FLAG)&1n)!==1n||(!parents.length&&ix.KEYNUM!=='0'))fail('RESTORE_SEMANTIC_UNPROVEN_SYSTEM_INDEX');key=['SYSTEM_INDEX',ids.get(table.ID),parents.length?parents:'ZERO_KEY_CLUSTER'];const compat=schemas.get(schema).indexes.filter(v=>v.INDEX_NAME===ix.INDEX_NAME);if(compat.length!==1||compat[0].GENERATED!=='Y')fail('RESTORE_SEMANTIC_UNPROVEN_SYSTEM_INDEX');}
  const semantic=bind(ix.INDEX_ID,key);if(!manual)rename(schema,ix.INDEX_NAME,semantic);
 }
 if(!same([...manualIndexFound].sort(),[...controlled.manualIndexes].sort())||[...objects.keys()].some(id=>!ids.has(id)))fail('RESTORE_SEMANTIC_OBJECT_COVERAGE');
 return {ids,names,roles,objects,timeObjects,graphs,foreignKeys:fks};
}
