import {readFile, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {extractSqlFacts} from '../../../scripts/lib/single-database-source-catalog.mjs';
// One bounded increment for PC-CON-HUMAN-RELEASE-METADATA-01. No SQL or full profile regeneration.
const profilePath='deployment/single-database/installation-profile.json';
const reviewPath='deployment/single-database/installation-sources.json';
const profile=JSON.parse(await readFile(profilePath,'utf8'));
const review=JSON.parse(await readFile(reviewPath,'utf8'));
const original=structuredClone(profile), oldReview=structuredClone(review);
const additions=[
 ['CONPROD','V109','db/dm8/V109__product_human_request.sql','CONPROD:V106'],
 ['CONPROD','V110','db/dm8/V110__product_human_cleanup_scope.sql','CONPROD:V109'],
 ['CONPROD','V111','db/dm8/V111__product_human_body_access_deadline.sql','CONPROD:V110'],
 ['CONAUTH','V006','db/identity-authority/V006__consultation_human_cleanup_scope.sql','CONAUTH:V004']
];
const unique=xs=>[...new Map(xs.map(x=>[JSON.stringify(x),x])).values()];
// Historical generate-installation-profile.py items() algorithm, bounded to these four SQL files.
function items(body){let out=[],b='',level=0,quote=false;for(const c of body){if(c==="'")quote=!quote;if(!quote){if(c==='(')level++;if(c===')')level--;if(c===','&&level===0){out.push(b.trim());b='';continue;}}b+=c;}if(b.trim())out.push(b.trim());return out;}
for(const [schema,id,sqlPath,dependency] of additions){
 if(profile.migrations.some(m=>m.schema===schema&&m.id===id)||review.entries.some(e=>e.path===sqlPath))throw Error('INCREMENT_ALREADY_REGISTERED');
 if(!profile.migrations.some(m=>m.schema+':'+m.id===dependency))throw Error('DEPENDENCY_MISSING');
 const bytes=await readFile(sqlPath),sha256=createHash('sha256').update(bytes).digest('hex');
 const text=bytes.toString('utf8'),sql=text.replace(/--[^\n]*/g,''),facts=extractSqlFacts(text),pre=[],post=[];
 const tables=new Set(facts.objects.filter(o=>o.kind==='TABLE').map(o=>o.name));
 for(const o of facts.objects){
  if(o.kind==='TABLE'){
   const check={kind:'object',name:o.name,type:'TABLE',expected:'present'};post.push(check);pre.push({...check,expected:'absent'});
   profile.knownObjects.push({schema,name:o.name,type:'TABLE'});
   profile.installedTables.push({schema,object:o.name,objectType:'TABLE',sourcePath:sqlPath,sourceSha256:sha256});
  }else if(o.kind==='CONSTRAINT'&&o.name){
   const kind=new RegExp('CONSTRAINT\\s+'+o.name+'\\s+(PRIMARY|UNIQUE|FOREIGN|CHECK)\\b','i').exec(sql);
   if(!kind||!o.table)throw Error('UNREVIEWED_NAMED_CONSTRAINT');
   const check={kind:'constraint',table:o.table,name:o.name,expected:'present',type:{PRIMARY:'P',UNIQUE:'U',FOREIGN:'R',CHECK:'C'}[kind[1].toUpperCase()]};post.push(check);
   if(!tables.has(o.table))pre.push({...check,expected:'absent'});
  }
 }
 for(const match of sql.matchAll(/CREATE\s+TABLE\s+(\w+)\s*\(/gi)){
  let n=match.index+match[0].length,start=n,depth=1;while(depth&&n<sql.length){if(sql[n]==='(')depth++;if(sql[n]===')')depth--;n++;}if(depth)throw Error('UNBALANCED_TABLE');
  for(const col of items(sql.slice(start,n-1))){
   if(/^(CONSTRAINT|PRIMARY|UNIQUE|CHECK|FOREIGN)\b/i.test(col))continue;
   const m=/^(\w+)\s+(VARCHAR|CHAR|BIGINT|INTEGER|INT|SMALLINT|TIMESTAMP|CLOB|BLOB|BOOLEAN|DOUBLE|DECIMAL|NUMERIC|DATE|BINARY|VARBINARY)\b/i.exec(col);
   if(!m)throw Error('UNREVIEWED_COLUMN');
   const size=/^\w+\s+(?:VARCHAR|CHAR|BINARY|VARBINARY)\s*\(\s*(\d+)\s*\)/i.exec(col);
   post.push({kind:'column',table:match[1].toUpperCase(),name:m[1].toUpperCase(),expected:'present',...(size?{size:Number(size[1])}:{})});
  }
 }
 for(const a of sql.matchAll(/ALTER\s+TABLE\s+(\w+)\s+ADD\s+([^;]+);/gi)){
  const table=a[1].toUpperCase(),tail=a[2].trim();pre.push({kind:'object',name:table,type:'TABLE',expected:'present'});
  if(!/^CONSTRAINT\b/i.test(tail)){const m=/^(\w+)\s+(\w+)/.exec(tail);if(!m)throw Error('UNREVIEWED_ADD');const check={kind:'column',table,name:m[1].toUpperCase(),expected:'present'};post.push({...check,dataType:'TIMESTAMP',nullable:true});pre.push({...check,expected:'absent'});}
 }
 for(const r of facts.references)if(r.context==='REFERENCES'&&!tables.has(r.name))pre.push({kind:'object',name:r.name,type:'TABLE',expected:'present'});
 const migration={id,schema,domain:'consultation',sqlPath,sha256,dependsOn:[dependency],prechecks:unique(pre),postchecks:unique(post),postcheckSupersededBy:[]};
 profile.migrations.push(migration);profile.finalChecks[schema].push(...migration.postchecks);
 review.entries.push({path:sqlPath,sha256,version:id,decision:'selected',reason:'普通人工请求及新Task专属清理scope的已准入产品结构；仅咨询绑定，不授权物理执行或默认启用。',designReferences:['docs/adr/consultation-product-human-handoff-20261009.md#1014-普通人工请求发行元数据2026-10-13'],bindings:[{schema,domain:'consultation'}]});
}
for(const key of ['migrations','knownObjects','installedTables'])if(JSON.stringify(profile[key].slice(0,original[key].length))!==JSON.stringify(original[key]))throw Error('OLD_PROFILE_CHANGED');
for(const schema of Object.keys(original.finalChecks))if(JSON.stringify(profile.finalChecks[schema].slice(0,original.finalChecks[schema].length))!==JSON.stringify(original.finalChecks[schema]))throw Error('OLD_CHECKS_CHANGED');
if(JSON.stringify(review.entries.slice(0,oldReview.entries.length))!==JSON.stringify(oldReview.entries))throw Error('OLD_REVIEW_CHANGED');
if(profile.installedTables.length-original.installedTables.length!==7)throw Error('EXACT_SEVEN_TABLES_REQUIRED');
// Aggregate metadata SHA fields are intentionally left for the single execution owner after parallel edits.
await writeFile(reviewPath,JSON.stringify(review,null,2)+'\n');
await writeFile(profilePath,JSON.stringify(profile,null,2)+'\n');
console.log(JSON.stringify({migrationsAdded:4,tablesAdded:7,checksAdded:Object.fromEntries(Object.keys(original.finalChecks).map(s=>[s,profile.finalChecks[s].length-original.finalChecks[s].length])),aggregateShaUpdatePending:true}));
