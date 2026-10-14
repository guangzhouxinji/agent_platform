import {hasNativePrivileges,assertNativeDefinition,assertNativeAccountRoles} from './single-database-native-privileges.mjs';
import {open,readFile,mkdir,unlink} from 'node:fs/promises';
import path from 'node:path';
import {canonical,hash,admission} from './single-database-lifecycle-runtime.mjs';
const fail=code=>{throw new Error(code);};
const ID=/^[A-Z][A-Z0-9_]{0,63}$/,SHA=/^[a-f0-9]{64}$/;
const actions=new Set(['SELECT','INSERT','UPDATE','DELETE','REFERENCES']);
const key=s=>`${s.schema}.${s.object}:${s.grantee}:${s.privilege}`;
const exact=(o,keys)=>{if(!o||Array.isArray(o)||typeof o!=='object'||Object.keys(o).some(k=>!keys.includes(k))||keys.some(k=>!Object.hasOwn(o,k)))fail('OBJECT_GRANT_FIELDS_INVALID');};
/** Deliberately table-only typed DCL. No SQL text, ANY, roles, DDL or GRANT OPTION. */
export function validateObjectGrantManifest(m) {
  exact(m,['format','targetId','deployment','initialInventorySha256','installedTables','steps']);
  if(m.format!=='SINGLE_DATABASE_OBJECT_GRANTS/v1'||!SHA.test(m.targetId)||!SHA.test(m.initialInventorySha256)||m.deployment?.database?.containerId!==m.targetId||!['SINGLE_DM8_DEPLOYMENT/v1','SINGLE_DM8_DEPLOYMENT/v2'].includes(m.deployment.schema))fail('OBJECT_GRANT_MANIFEST_INVALID');
  const ds=m.deployment.domains;
  if(!Array.isArray(ds)||ds.length!==3||new Set(ds.map(d=>d.domain)).size!==3||ds.some(d=>!['consultation','enforcement','adjudication'].includes(d.domain)))fail('THREE_DOMAIN_BINDINGS_REQUIRED');
  const bindings=new Map(),accounts=new Set(),issuers=new Set();
  for(const d of ds){let u;try{u=new URL(d.issuer);}catch{fail('OBJECT_GRANT_ISSUER_INVALID');}if(u.protocol!=='https:'||u.username||u.password||issuers.has(u.href))fail('OBJECT_GRANT_ISSUER_INVALID');issuers.add(u.href);
    for(const kind of ['business','authority']){const b=d[kind];if(!b||!ID.test(b.schema)||b.ownerUser!==b.schema||b.jdbcUrl!==m.deployment.database.jdbcUrl||bindings.has(b.schema))fail('OBJECT_GRANT_BINDINGS_INVALID');
      for(const a of [b.ownerUser,b.runtimeUser,...(b.backupUser?[b.backupUser]:[])]){if(!ID.test(a)||['SYSDBA','SYSAUDITOR','SYSSSO','PUBLIC'].includes(a)||accounts.has(a))fail('DISTINCT_OBJECT_GRANT_ACCOUNTS_REQUIRED');accounts.add(a);}
      if(m.deployment.schema.endsWith('/v2')&&kind==='authority'&&!b.backupUser)fail('THREE_AUTHORITY_BACKUP_ACCOUNTS_REQUIRED');
      if(kind==='business'&&b.backupUser)fail('AUTHORITY_BACKUP_BINDING_REQUIRED');bindings.set(b.schema,{...b,kind,domain:d.domain});
    }
  }
  if(!Array.isArray(m.installedTables)||!m.installedTables.length||!Array.isArray(m.steps)||!m.steps.length)fail('COMPLETE_INSTALLED_TABLE_MAP_REQUIRED');
  const tables=new Set(),seen=new Set();
  for(const t of m.installedTables){exact(t,['schema','object','objectType']);if(!bindings.has(t.schema)||!ID.test(t.object)||t.objectType!=='TABLE'||tables.has(`${t.schema}.${t.object}`))fail('INSTALLED_TABLE_MAP_INVALID');tables.add(`${t.schema}.${t.object}`);}
  if([...bindings.keys()].some(s=>!m.installedTables.some(t=>t.schema===s)))fail('SIX_INSTALLED_SCHEMA_MAP_REQUIRED');
  for(const s of m.steps){exact(s,['kind','schema','object','objectType','grantee','privilege','grantOption','evidence']);const b=bindings.get(s.schema);
    if(s.kind!=='grant-object'||!b||!tables.has(`${s.schema}.${s.object}`)||s.objectType!=='TABLE'||!ID.test(s.grantee)||!actions.has(s.privilege)||s.grantOption!==false||seen.has(key(s)))fail('TYPED_MINIMUM_OBJECT_GRANT_REQUIRED');seen.add(key(s));
    const referencedOwner=[...bindings.values()].find(x=>x.ownerUser===s.grantee);
    if(s.privilege==='REFERENCES'){if(s.grantee!==b.runtimeUser&&(!referencedOwner||referencedOwner.schema===s.schema||referencedOwner.domain!==b.domain))fail('SAME_DOMAIN_FK_RUNTIME_OR_OWNER_REQUIRED');}
    else if(s.grantee!==b.runtimeUser && !(b.kind==='authority'&&s.grantee===b.backupUser&&s.privilege==='SELECT'))fail('EXACT_SCHEMA_RUNTIME_OR_READONLY_BACKUP_REQUIRED');
    if(!Array.isArray(s.evidence)||!s.evidence.length||s.evidence.some(e=>!e.path||path.isAbsolute(e.path)||e.path.split(/[\\/]/).includes('..')||!SHA.test(e.sha256)||!Number.isSafeInteger(e.line)||e.line<1))fail('PINNED_OBJECT_GRANT_SOURCE_REQUIRED');
  }
  // Backup reads every installed authority table, including immutable events.
  for(const b of bindings.values())if(b.backupUser)for(const t of m.installedTables.filter(t=>t.schema===b.schema))if(!seen.has(key({...t,grantee:b.backupUser,privilege:'SELECT'})))fail('COMPLETE_AUTHORITY_BACKUP_SELECT_REQUIRED');
  return {bindings,accounts:[...accounts],schemas:[...bindings.keys()]};
}
export const objectGrantRequest=s=>({action:'grant-object',schema:s.schema,object:s.object,objectType:s.objectType,grantee:s.grantee,privilege:s.privilege,grantOption:false});
function grants(i){return i.accounts.flatMap(a=>a.tableGrants.map(g=>({schema:g.OWNER,object:g.TABLE_NAME,grantee:a.name,privilege:g.PRIVILEGE,grantable:g.GRANTABLE})));}
export function checkObjectGrantInventory(i,m) {
  const {bindings,accounts,schemas}=validateObjectGrantManifest(m);
  if(i?.format!=='SINGLE_DATABASE_LIVE_INVENTORY/v1'||i.actualJdbc!==true||i.targetId!==m.targetId||i.complete!==true||!Array.isArray(i.errors)||i.errors.length||!Array.isArray(i.schemas)||i.schemas.length!==6||!Array.isArray(i.accounts)||i.accounts.length!==accounts.length||new Set(i.accounts.map(a=>a.name)).size!==accounts.length||new Set(i.schemas.map(s=>s.schema)).size!==6)fail('EXACT_ACTUAL_GRANT_INVENTORY_REQUIRED');
  for(const s of schemas){const live=i.schemas.find(x=>x.schema===s);if(!live||!Array.isArray(live.objects)||!Array.isArray(live.foreignKeys))fail('EXACT_ACTUAL_GRANT_INVENTORY_REQUIRED');
    const names=live.objects.filter(o=>['TABLE','BASE TABLE'].includes(o.type)).map(o=>o.name).sort();if(canonical(names)!==canonical(m.installedTables.filter(t=>t.schema===s).map(t=>t.object).sort()))fail('INSTALLED_TABLE_INVENTORY_MISMATCH');}
  const native=hasNativePrivileges(i);
  const expected=new Set(m.steps.map(key));
  for(const name of accounts){const a=i.accounts.find(x=>x.name===name);if(!a?.exists||['systemPrivileges','roleGrants','tableGrants','columnGrants','effectiveGrantSubjects','effectiveGrants'].some(k=>!Array.isArray(a[k])))fail('ACCOUNT_EFFECTIVE_PRIVILEGE_INVENTORY_REQUIRED');
    const b=[...bindings.values()].find(b=>[b.ownerUser,b.runtimeUser,b.backupUser].includes(name)),owner=name===b.ownerUser;
    const allowed=owner?['CREATE SESSION','CREATE TABLE','CREATE VIEW','CREATE SEQUENCE','CREATE INDEX']:['CREATE SESSION'];
    if(native)assertNativeAccountRoles(a.roleGrants,['PUBLIC']);
    if((!native&&a.roleGrants.length)||a.columnGrants.length||a.systemPrivileges.some(g=>!allowed.includes(g.PRIVILEGE)||g.ADMIN_OPTION==='YES')||!a.systemPrivileges.some(g=>g.PRIVILEGE==='CREATE SESSION'))fail('EXCESS_ACCOUNT_PRIVILEGES');
    const subjects=native?[name,'PUBLIC','SVI']:[name,'PUBLIC'];
    if(canonical([...a.effectiveGrantSubjects].sort())!==canonical(subjects.sort())||a.effectiveGrants.length!==subjects.length||new Set(a.effectiveGrants.map(g=>g.subject)).size!==subjects.length)fail('EFFECTIVE_PRIVILEGE_INVENTORY_INCOMPLETE');
    for(const e of a.effectiveGrants){if(!subjects.includes(e.subject)||['system','roles','tables','columns'].some(k=>!Array.isArray(e[k])))fail('EXCESS_EFFECTIVE_PRIVILEGES');
      if(native&&e.subject!==name){assertNativeDefinition(e.subject,e);continue;}
      if((!native&&e.roles.length)||e.columns.length)fail('EXCESS_EFFECTIVE_PRIVILEGES');
      if(e.subject==='PUBLIC'&&(e.system.length||e.tables.length))fail('PUBLIC_PRIVILEGE_BYPASS');
      if(e.subject===name&&(canonical(e.system)!==canonical(a.systemPrivileges)||canonical(e.tables)!==canonical(a.tableGrants)||native&&canonical(e.roles)!==canonical(a.roleGrants)))fail('EFFECTIVE_PRIVILEGE_INVENTORY_INCONSISTENT');}
    for(const g of a.tableGrants)if(!expected.has(key({schema:g.OWNER,object:g.TABLE_NAME,grantee:name,privilege:g.PRIVILEGE}))||g.GRANTABLE!=='NO')fail('EXCESS_OBJECT_PRIVILEGES');
  }
  // Cross-schema FK events need REFERENCES granted to the FK schema owner, not its runtime.
  for(const s of i.schemas)for(const fk of s.foreignKeys)if(fk.PKTABLE_SCHEM!==fk.FKTABLE_SCHEM){if(fk.FKTABLE_SCHEM!==s.schema||!bindings.has(fk.PKTABLE_SCHEM)||!expected.has(key({schema:fk.PKTABLE_SCHEM,object:fk.PKTABLE_NAME,grantee:bindings.get(s.schema).ownerUser,privilege:'REFERENCES'})))fail('UNPLANNED_CROSS_SCHEMA_FK_REFERENCES');}
  return new Set(grants(i).map(key));
}
/** START is durable before DCL. An ambiguous START/FAILURE requires a separately approved
 * reconciliation/new manifest; never retries DCL based on an assumed rollback. */
export async function executeObjectGrants({manifest:m,approval,decisionCheck,assertTarget,acquireFence,inventory,execute,journalDir}) {
  validateObjectGrantManifest(m);const manifestSha256=hash(canonical(m));
  if(approval?.manifestSha256!==manifestSha256||![decisionCheck,assertTarget,acquireFence,inventory,execute].every(f=>typeof f==='function')||!Array.isArray(approval.writers)||!approval.writers.length)fail('OBJECT_GRANT_APPROVED_PORTS_REQUIRED');
  const current=async()=>{admission(approval,m.targetId,'grant-object');await decisionCheck();await assertTarget();};await current();
  const lease=await acquireFence(approval.writers);let lock,lastDigest;
  try{if(!lease?.assertHeld||!lease?.release)fail('MANAGED_OBJECT_GRANT_FENCE_REQUIRED');await lease.assertHeld();await mkdir(journalDir,{recursive:true});lock=await open(path.join(journalDir,'grant.lock'),'wx');
    const journal=path.join(journalDir,'events.jsonl');let events=[];try{events=(await readFile(journal,'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);}catch(e){if(e.code!=='ENOENT')throw e;}
    let previousSha256='',completed=0;
    for(let j=0;j<events.length;j++){const {eventSha256,...body}=events[j];if(body.previousSha256!==previousSha256||hash(canonical(body))!==eventSha256||body.manifestSha256!==manifestSha256||body.targetId!==m.targetId)fail('OBJECT_GRANT_JOURNAL_INVALID');previousSha256=eventSha256;
      if(body.kind==='START'){if(body.step!==completed||events[j+1]?.kind!=='SUCCESS'||events[j+1]?.step!==completed)fail('PARTIAL_DCL_RECONCILIATION_REQUIRED');}
      else if(body.kind==='SUCCESS'){if(events[j-1]?.kind!=='START'||body.step!==completed)fail('OBJECT_GRANT_JOURNAL_INVALID');completed++;}else fail('PARTIAL_DCL_RECONCILIATION_REQUIRED');}
    if(completed>m.steps.length)fail('OBJECT_GRANT_JOURNAL_INVALID');
    const append=async body=>{body={...body,targetId:m.targetId,manifestSha256,at:new Date().toISOString(),previousSha256};const eventSha256=hash(canonical(body));const f=await open(journal,'a');try{await f.writeFile(JSON.stringify({...body,eventSha256})+'\n');await f.sync();}finally{await f.close();}previousSha256=eventSha256;};
    await current();await lease.assertHeld();let live=await inventory(),present=checkObjectGrantInventory(live,m);
    const digest=()=>hash(canonical(live));if(digest()!==(events.length?events.at(-1).inventorySha256:m.initialInventorySha256))fail('OBJECT_GRANT_INVENTORY_DRIFT');
    for(let n=0;n<completed;n++)if(!present.has(key(m.steps[n])))fail('COMPLETED_OBJECT_GRANT_MISSING');
    const resumed=completed;
    for(;completed<m.steps.length;completed++){const step=m.steps[completed];await current();await lease.assertHeld();live=await inventory();const before=checkObjectGrantInventory(live,m);
      if(digest()!==(completed===resumed?(events.length?events.at(-1).inventorySha256:m.initialInventorySha256):lastDigest))fail('OBJECT_GRANT_INVENTORY_DRIFT');
      await append({kind:'START',step:completed,grant:key(step),inventorySha256:digest()});
      try{if(!before.has(key(step))){const r=await execute(step,objectGrantRequest(step));if(r?.actualJdbc!==true||r.executedSteps!==1||r.grantOption!==false)fail('ACTUAL_TYPED_GRANT_RECEIPT_REQUIRED');}
        await current();await lease.assertHeld();live=await inventory();present=checkObjectGrantInventory(live,m);
        const expected=new Set([...before,key(step)]);if(canonical([...present].sort())!==canonical([...expected].sort()))fail('OBJECT_GRANT_POSTCONDITION_FAILED');
        const beforeState=structuredClone(live);for(const a of beforeState.accounts){a.tableGrants=a.tableGrants.filter(g=>!(a.name===step.grantee&&key({schema:g.OWNER,object:g.TABLE_NAME,grantee:a.name,privilege:g.PRIVILEGE})===key(step)&&!before.has(key(step))));for(const e of a.effectiveGrants)e.tables=e.tables.filter(g=>!(e.subject===step.grantee&&key({schema:g.OWNER,object:g.TABLE_NAME,grantee:e.subject,privilege:g.PRIVILEGE})===key(step)&&!before.has(key(step))));}
        // All non-grant inventory (rows, objects, accounts and privileges) must remain unchanged.
        if(hash(canonical(beforeState))!== (completed===resumed?(events.length?events.at(-1).inventorySha256:m.initialInventorySha256):lastDigest))fail('DCL_UNRELATED_INVENTORY_DRIFT');
        lastDigest=digest();await append({kind:'SUCCESS',step:completed,grant:key(step),inventorySha256:lastDigest,alreadyPresent:before.has(key(step))});
      }catch{let scene;try{scene=await inventory();}catch{scene={unavailable:true};}const f=await open(path.join(journalDir,`failure-${completed}.json`),'wx');try{await f.writeFile(JSON.stringify(scene,null,2)+'\n');await f.sync();}finally{await f.close();}await append({kind:'FAILURE',step:completed,sceneSha256:hash(canonical(scene)),rollbackClaimed:false});fail('OBJECT_GRANT_FAILED_PARTIAL_PRESERVED');}
    }
    await current();await lease.assertHeld();const final=await inventory();checkObjectGrantInventory(final,m);if(hash(canonical(final))!==(lastDigest|| (events.length?events.at(-1).inventorySha256:m.initialInventorySha256)))fail('OBJECT_GRANT_INVENTORY_DRIFT');
    return {format:'SINGLE_DATABASE_OBJECT_GRANT_RECEIPT/v1',targetId:m.targetId,manifestSha256,applied:completed-resumed,skipped:resumed,inventorySha256:hash(canonical(final)),journalHead:previousSha256,actualExecution:true,dclAtomicityClaimed:false,productInitialized:false};
  }finally{if(lock){await lock.close();await unlink(path.join(journalDir,'grant.lock'));}if(lease?.release)await lease.release();}

}



/** Grant-only compiler consumes main-session installation objects, never chooses migrations,
 * creates tables, rewrites SQL, or derives privileges for unknown runtime objects. */
export function compileObjectGrantManifest({deployment,capabilities,installedTables,initialInventorySha256,installationEvidence}) {
  if(capabilities?.format!=='SINGLE_DATABASE_APPLICATION_CAPABILITIES/v1'||!Array.isArray(capabilities.applications)||capabilities.applications.length!==3||!Array.isArray(installedTables))fail('FORMAL_APPLICATION_CAPABILITIES_REQUIRED');
  const steps=[],seen=new Set();
  const add=(schema,object,grantee,privilege,evidence)=>{const s={kind:'grant-object',schema,object,objectType:'TABLE',grantee,privilege,grantOption:false,evidence};if(!seen.has(key(s))){steps.push(s);seen.add(key(s));}};
  for(const d of deployment.domains){const app=capabilities.applications.find(a=>a.domain===d.domain);if(!app||!Array.isArray(app.objects))fail('EXACT_PRODUCT_APPLICATION_REQUIRED');
    for(const o of app.objects){if(!['business','authority'].includes(o.schemaKind)||o.schema!==d[o.schemaKind].schema||o.schema!==app.schemaBindings[o.schemaKind]||o.objectType!=='TABLE'||!ID.test(o.name)||!o.runtimePrivileges||Array.isArray(o.runtimePrivileges))fail('EXACT_CAPABILITY_SCHEMA_KIND_REQUIRED');
      if(o.installRequired!==true||!installedTables.some(t=>t.schema===o.schema&&t.object===o.name&&t.objectType==='TABLE'))fail('DELIVERED_CAPABILITY_TABLE_NOT_INSTALLED');
      for(const [privilege,evidence] of Object.entries(o.runtimePrivileges))add(o.schema,o.name,d[o.schemaKind].runtimeUser,privilege,evidence);
    }
    if(d.authority.backupUser)for(const t of installedTables.filter(t=>t.schema===d.authority.schema))add(t.schema,t.object,d.authority.backupUser,'SELECT',installationEvidence?.[`${t.schema}.${t.object}`]||(t.sourcePath&&t.sourceSha256?[{path:t.sourcePath,sha256:t.sourceSha256,line:1}]:undefined));
  }
  const m={format:'SINGLE_DATABASE_OBJECT_GRANTS/v1',targetId:deployment.database.containerId,deployment,initialInventorySha256,installedTables:installedTables.map(t=>({schema:t.schema,object:t.object,objectType:t.objectType})),steps};
  validateObjectGrantManifest(m);return m;
}


