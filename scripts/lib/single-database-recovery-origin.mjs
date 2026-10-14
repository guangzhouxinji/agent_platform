import {readFile,lstat,realpath} from 'node:fs/promises';import path from 'node:path';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {assertRestoreCatalog,deriveCompleteNativeForeignKeys,RESTORE_CATALOG_PROFILE,RESTORE_CATALOG_PROFILE_SHA256} from './single-database-restore-catalog.mjs';
const fail=code=>{throw new Error(code);};const SHA=/^[a-f0-9]{64}$/;const MAX=4*1024*1024;
const kinds=['CONTROLLED_SOURCE_ORIGIN_PROOF','CONTROLLED_MIGRATION_HISTORY'];
export async function loadRecoveryOrigin(configuration){
 if(!configuration||!path.isAbsolute(configuration.workspaceRoot||'')||configuration.profileId!==RESTORE_CATALOG_PROFILE.profileId||configuration.profileSha256!==RESTORE_CATALOG_PROFILE_SHA256||!Array.isArray(configuration.originFiles)||configuration.originFiles.length!==2||new Set(configuration.originFiles.map(f=>f.kind)).size!==2)fail('EXACT_RECOVERY_ORIGIN_CONFIGURATION_REQUIRED');
 const files=[];
 for(const file of configuration.originFiles){if(!kinds.includes(file.kind)||!SHA.test(file.sha256||'')||!path.isAbsolute(file.path)||await realpath(file.path)!==path.resolve(file.path)||(await lstat(file.path)).isSymbolicLink()||!(await lstat(file.path)).isFile()||(await lstat(file.path)).size>MAX)fail('RECOVERY_ORIGIN_REFERENCE_REDIRECT_OR_LIMIT');
  const bytes=await readFile(file.path);if(bytes.length>MAX||hash(bytes)!==file.sha256)fail('RECOVERY_ORIGIN_RAW_SHA_MISMATCH');
  const value=JSON.parse(bytes.toString('utf8'));files.push({kind:file.kind,sourcePath:file.path,rawSha256:hash(bytes),rawBase64:bytes.toString('base64'),value,canonicalSha256:hash(canonical(value))});
 }
 const history=files.find(f=>f.kind==='CONTROLLED_MIGRATION_HISTORY').value;
 if(!Array.isArray(history.migrations))fail('RECOVERY_ORIGIN_MIGRATION_HISTORY_REQUIRED');
 const root=await realpath(configuration.workspaceRoot);
 if(root!==path.resolve(configuration.workspaceRoot)||(await lstat(configuration.workspaceRoot)).isSymbolicLink()||!(await lstat(root)).isDirectory())fail('RECOVERY_ORIGIN_WORKSPACE_REDIRECT');
 for(const m of history.migrations){if(!SHA.test(m.sqlSha256||'')||!(/^db\/(dm8|identity-authority)\/V[0-9]{3,}__[A-Za-z0-9_]+\.sql$/.test(m.sqlPath)||(m.id==='A001'&&m.sqlPath==='db/dm8/adjudication_source_authority.sql')))fail('RECOVERY_ORIGIN_EXACT_ORIGINAL_SQL_REQUIRED');
  const file=path.resolve(root,m.sqlPath);if(await realpath(file)!==file||(await lstat(file)).isSymbolicLink()||(await lstat(file)).size>MAX)fail('RECOVERY_ORIGIN_ORIGINAL_REDIRECT_OR_LIMIT');
  if(hash(await readFile(file))!==m.sqlSha256)fail('RECOVERY_ORIGIN_ORIGINAL_SQL_SHA_CHANGED');
 }
 return {format:'DM8_AUTHENTICATED_RECOVERY_ORIGIN/v1',files};
}
export function assertRecoveryOriginEnvelope(origin){
 if(!origin||origin.format!=='DM8_AUTHENTICATED_RECOVERY_ORIGIN/v1'||!Array.isArray(origin.files)||origin.files.length!==2||new Set(origin.files.map(f=>f.kind)).size!==2)fail('RECOVERY_ORIGIN_ENVELOPE_REQUIRED');
 for(const f of origin.files){if(!kinds.includes(f.kind)||typeof f.rawBase64!=='string'||!SHA.test(f.rawSha256)||!SHA.test(f.canonicalSha256))fail('RECOVERY_ORIGIN_ENVELOPE_REQUIRED');const bytes=Buffer.from(f.rawBase64,'base64');if(bytes.length>MAX||bytes.toString('base64')!==f.rawBase64||hash(bytes)!==f.rawSha256||canonical(JSON.parse(bytes.toString('utf8')))!==canonical(f.value)||hash(canonical(f.value))!==f.canonicalSha256)fail('RECOVERY_ORIGIN_AUTHENTICATED_CONTENT_MISMATCH');}
 return origin;
}
/** Attach source provenance to the exact new capture; never backfill an old package. */
export function assertRecoverySource(inventory,origin){
 assertRecoveryOriginEnvelope(origin);const catalog=assertRestoreCatalog(inventory.restoreCatalog);deriveCompleteNativeForeignKeys(inventory);
 const proof=origin.files.find(f=>f.kind===kinds[0]).value,history=origin.files.find(f=>f.kind===kinds[1]).value;
 if(proof.format!=='DM8_CONTROLLED_SOURCE_ORIGIN_PROOF/v1'||history.format!=='ORIGINAL_MIGRATION_HISTORY_READ_ONLY_AUDIT/v1'||proof.targetId!==inventory.targetId||history.targetId!==inventory.targetId||proof.databaseVersion!==catalog.databaseVersion||proof.journalChainValid!==true||history.journalChainValid!==true||proof.lastEventSha256!==history.lastEventSha256||!SHA.test(history.lastEventSha256)||proof.originalMigrationCount!==history.migrationCount||history.migrations.length!==history.migrationCount)fail('RECOVERY_ORIGIN_SOURCE_OR_HISTORY_MISMATCH');
 const native=new Map(catalog.categories.nativeConstraints.rows.map(c=>[`${c.SCHEMA_NAME}:${c.CONSTRAINT_NAME}`,c])),objects=new Map(catalog.categories.nativeObjects.rows.map(o=>[o.ID,o])),seen=new Set();
 const migrations=new Map();for(const m of history.migrations){const key=m.schema+':'+m.id;if(migrations.has(key)||!SHA.test(m.sqlSha256)||!Array.isArray(m.statements))fail('RECOVERY_ORIGIN_AMBIGUOUS_MIGRATION');migrations.set(key,m);}
 const consume=(schema,name,table,type)=>{const key=schema+':'+name,c=native.get(key);if(!c||seen.has(key)||objects.get(c.TABLEID)?.NAME!==table||(c['TYPE$']==='F'?'R':c['TYPE$'])!==type)fail('RECOVERY_ORIGIN_CONSTRAINT_IDENTITY_MISMATCH');seen.add(key);return c;};
 for(const p of proof.generatedCandidates){const c=consume(p.schema,p.name,p.table,p.type),o=objects.get(c.ID),m=migrations.get(p.originMigration);
  if(!Number.isSafeInteger(p.id)||String(p.id)!==c.ID||p.name!=='CONS'+c.ID||!o||o.NAME!==p.name||o['TYPE$']!=='TABOBJ'||o['SUBTYPE$']!=='CONS'||o.CRTDATE!==p.createdAt||p.noLaterExplicitNameMutation!==true||!m||m.sqlPath!==p.originSqlPath||m.sqlSha256!==p.originSqlSha256||m.startedAt!==p.actualStart||m.finishedAt!==p.actualSuccess)fail('RECOVERY_ORIGIN_GENERATED_IDENTITY_MISMATCH');
 }
 for(const p of proof.manualConstraints){consume(p.schema,p.constraint,p.table,p.type);const m=migrations.get(p.schema+':'+p.migrationId),statement=m?.statements[p.statementOrdinal];if(!m||m.sqlPath!==p.sqlPath||m.sqlSha256!==p.sqlSha256||typeof statement!=='string'||!new RegExp('\\bCONSTRAINT\\s+'+p.constraint+'\\s+','i').test(statement))fail('RECOVERY_ORIGIN_MANUAL_CONSTRAINT_MISMATCH');}
 if(seen.size!==native.size)fail('RECOVERY_ORIGIN_CONSTRAINT_COVERAGE');
 // The preserved 50 proof omitted dictionary map keys from manualIndexes. Recover each exact
 // name from its pinned original statement, rather than guessing by table or list position.
 const indexes=new Map();for(const p of proof.manualIndexes){const m=migrations.get(p.schema+':'+p.migrationId),statement=m?.statements[p.statementOrdinal],match=typeof statement==='string'?statement.match(/^CREATE\s+(?:UNIQUE\s+)?INDEX\s+([A-Za-z][A-Za-z0-9_]*)\s+ON\s+([A-Za-z][A-Za-z0-9_]*)/i):null;if(!m||m.sqlPath!==p.sqlPath||m.sqlSha256!==p.sqlSha256||!match||match[2].toUpperCase()!==p.table)fail('RECOVERY_ORIGIN_MANUAL_INDEX_MISMATCH');const key=p.schema+':'+match[1].toUpperCase();if(indexes.has(key))fail('RECOVERY_ORIGIN_AMBIGUOUS_INDEX');indexes.set(key,p);}
 const actual=inventory.schemas.flatMap(s=>s.indexes.filter(i=>i.GENERATED==='N').map(i=>({schema:s.schema,row:i})));
 if(actual.length!==indexes.size||actual.some(({schema,row})=>indexes.get(schema+':'+row.INDEX_NAME)?.table!==row.TABLE_NAME))fail('RECOVERY_ORIGIN_MANUAL_INDEX_COVERAGE');
 return inventory;
}
export function recoveryBinding(inventory,origin){assertRecoverySource(inventory,origin);return {profileId:RESTORE_CATALOG_PROFILE.profileId,profileSha256:RESTORE_CATALOG_PROFILE_SHA256,catalogSha256:hash(canonical(inventory.restoreCatalog)),nativePrivilegesSha256:hash(canonical(inventory.nativePrivileges??null)),originFiles:origin.files.map(({kind,rawSha256,canonicalSha256})=>({kind,rawSha256,canonicalSha256})).sort((a,b)=>a.kind.localeCompare(b.kind))};}
