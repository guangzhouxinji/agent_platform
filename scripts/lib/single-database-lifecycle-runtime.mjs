import {createHash} from 'node:crypto';
import {readFile, writeFile, open, mkdir, realpath, lstat} from 'node:fs/promises';
import path from 'node:path';
import {jdbcContainerCommand} from './single-database-jdbc-container.mjs';
import {deploymentAccounts} from './single-database-lifecycle.mjs';
export const hash = x => createHash('sha256').update(x).digest('hex');
export const canonical = x => JSON.stringify(x && typeof x === 'object' ? Array.isArray(x) ? x.map(v=>JSON.parse(canonical(v))) : Object.fromEntries(Object.keys(x).sort().map(k=>[k,JSON.parse(canonical(x[k]))])) : x);
const fail = code => {throw new Error(code);};
const ident = s => {if(!/^[A-Z][A-Z0-9_]{0,63}$/.test(s)) fail('INVALID_IDENTIFIER');};
export function assertInventory(i, targetId, schemas, accounts, deployment) {
  const expectedAccounts=(accounts.length===15||deployment?.schema==='SINGLE_DM8_DEPLOYMENT/v2')&&deployment?deploymentAccounts(deployment):null;
  if((accounts.length===15&&!expectedAccounts)||(expectedAccounts&&canonical([...accounts].sort())!==canonical([...expectedAccounts].sort())))fail('FIFTEEN_ACCOUNT_DEPLOYMENT_REQUIRED');
  if(expectedAccounts&&canonical(deployment.domains.flatMap(d=>[d.business.schema,d.authority.schema]).sort())!==canonical([...schemas].sort()))fail('EXACT_DEPLOYMENT_SCHEMA_BINDING_REQUIRED');
  if(!/^[a-f0-9]{64}$/.test(targetId)||i.actualJdbc!==true||i.format !== 'SINGLE_DATABASE_LIVE_INVENTORY/v1' || i.targetId !== targetId || !i.complete || i.errors?.length) fail('INCOMPLETE_OR_WRONG_LIVE_INVENTORY');
  if(new Set(schemas).size!==6 || i.schemas.length!==6 || schemas.some(s=>!i.schemas.some(v=>v.schema===s))) fail('SIX_EXACT_SCHEMAS_REQUIRED');
  if(![12,15].includes(accounts.length) || new Set(accounts).size!==accounts.length || i.accounts.length!==accounts.length || accounts.some(s=>!i.accounts.some(v=>v.name===s && v.exists))) fail('TWELVE_EXACT_ACCOUNTS_REQUIRED');
  for(const s of i.schemas) {
    ident(s.schema);
    if(/dm/i.test(i.databaseProduct||'')){const rows=s.nativeSchemaObjects;if(!Array.isArray(rows)||rows.length!==1||rows[0].OBJECT_NAME!==s.schema||rows[0].OBJECT_TYPE!=='SCH')fail('EXACT_NATIVE_SCHEMA_OBJECT_REQUIRED');}

    for(const k of ['objects','columns','constraints','foreignKeys','indexes','sequences','views','triggers','dependencies','rows']) if(!Array.isArray(s[k])) fail('INVENTORY_CATEGORY_MISSING');
  }
  for(const a of i.accounts)for(const k of ['systemPrivileges','roleGrants','tableGrants','columnGrants','effectiveGrantSubjects','effectiveGrants'])if(!Array.isArray(a[k]))fail('ACCOUNT_PRIVILEGE_CATEGORY_MISSING');
  return i;
}
export function foreignKeyDependencies(i) {
  const nodes=new Set(i.schemas.flatMap(s=>s.objects.filter(o=>['TABLE','BASE TABLE'].includes(o.type)).map(o=>`${s.schema}.${o.name}`)));
  const edges=new Map();
  for(const s of i.schemas)for(const fk of s.foreignKeys) {
    const from=`${fk.FKTABLE_SCHEM}.${fk.FKTABLE_NAME}`,to=`${fk.PKTABLE_SCHEM}.${fk.PKTABLE_NAME}`,key=`${from}:${fk.FK_NAME}:${to}`;
    if(!edges.has(key))edges.set(key,{from,to,constraint:fk.FK_NAME,columns:[]});
    edges.get(key).columns.push({ordinal:Number(fk.KEY_SEQ),from:fk.FKCOLUMN_NAME,to:fk.PKCOLUMN_NAME});
  }
  const result=[...edges.values()].sort((a,b)=>canonical(a).localeCompare(canonical(b)));
  result.forEach(e=>e.columns.sort((a,b)=>a.ordinal-b.ordinal));
  const unresolved=result.filter(e=>!nodes.has(e.from)||!nodes.has(e.to));
  const remaining=new Set(nodes),order=[];
  while(remaining.size){const ready=[...remaining].filter(n=>!result.some(e=>e.from===n&&e.to!==n&&remaining.has(e.to))).sort();if(!ready.length)break;order.push(...ready);ready.forEach(n=>remaining.delete(n));}
  return {edges:result,unresolved,tableDependencyOrder:order,cyclicTables:[...remaining].sort(),migrationOrderInferred:false};
}
export const inventoryDigest = i => hash(canonical({targetId:i.targetId,schemas:i.schemas,accounts:i.accounts}));
export async function jdbcRequest(config, request, {captureRaw} = {}) {
  // Config is a trusted deployment input, never shell text. Secrets exist in environment only.
  if(!config.java || !config.classpath || !config.mainClass || !config.url || !config.user || !config.passwordEnv || !process.env[config.passwordEnv]) fail('JDBC_CONFIGURATION_REQUIRED');
  const command=config.dockerJdbc?await jdbcContainerCommand(config,request):{command:config.java,args:['-Xmx256m','-cp',config.classpath,config.mainClass]};
  // Lazy import avoids a new initialization cycle; reuse the already verified bounded manager.
  const {runNativeCommand}=await import('./single-database-lifecycle-native.mjs');
  let out;
  try {
    out=await runNativeCommand(command.command,command.args,{
      input:JSON.stringify(request),timeoutMs:120000,maxBytes:32*1024*1024,
      env:{...process.env,LIFECYCLE_JDBC_URL:config.url,LIFECYCLE_JDBC_USER:config.user,LIFECYCLE_JDBC_PASSWORD:process.env[config.passwordEnv]},
      unavailableCode:'JDBC_PROCESS_UNAVAILABLE',failureCode:'JDBC_REQUEST_FAILED'
    });
    if(captureRaw!==undefined){if(typeof captureRaw!=='function')fail('JDBC_RAW_EVIDENCE_PORT_REQUIRED');await captureRaw(Buffer.from(out));}
    try {
      const v=JSON.parse(out.toString('utf8'));
      if(v.error)throw new Error(v.error+':'+(v.sqlState||'')+':'+(v.vendorCode||0));
      return v;
    }catch(e){throw new Error(/^JDBC_OPERATION_REFUSED_OR_FAILED:/.test(e.message)?e.message:'JDBC_REQUEST_FAILED');}
  }finally{out?.fill(0);}
}
export function splitForwardSql(sql, {adjudicationAuthorityInitialization=false,controlledStatementSha256=[]}={}) {
  const out=[];let b='',quote=null,line=false,block=false;
  for(let n=0;n<sql.length;n++) {const c=sql[n],next=sql[n+1];
    if(line){if(c==='\n'){line=false;b+=' ';}continue;}
    if(block){if(c==='*'&&next==='/'){block=false;n++;b+=' ';}continue;}
    if(quote){b+=c;if(c===quote){if(next===quote){b+=next;n++;}else quote=null;}continue;}
    if(c==='-'&&next==='-'){line=true;n++;continue;}if(c==='/'&&next==='*'){block=true;n++;continue;}
    if(c==="'"||c==='"'){quote=c;b+=c;continue;}
    if(c===';'){if(b.trim())out.push(b.trim());b='';}else b+=c;
  }
  if(quote||block)fail('UNTERMINATED_SQL');if(b.trim())out.push(b.trim());
  if(!Array.isArray(controlledStatementSha256)||controlledStatementSha256.some(s=>! /^[a-f0-9]{64}$/.test(s)))fail('CONTROLLED_STATEMENT_PINS_REQUIRED');
  if(!out.length || out.some(s=>{if(controlledStatementSha256.includes(hash(s)))return false;if(adjudicationAuthorityInitialization && /^INSERT\s+INTO\s+adj2_source_generation\s+VALUES\s*\(\s*1\s*,\s*0\s*,\s*'READY'\s*\)$/i.test(s))return false;return ! /^(CREATE\s+(TABLE|INDEX|UNIQUE\s+INDEX|SEQUENCE|VIEW)\b|ALTER\s+TABLE\b[\s\S]*\bADD\b)/i.test(s) || /\b(DROP|TRUNCATE|DELETE|UPDATE|MERGE|GRANT|REVOKE|EXECUTE|CALL)\b/i.test(s.replace(/'(?:''|[^'])*'/g,"''"));})) fail('FORWARD_ONLY_SQL_REQUIRED');
  return out;
}
export async function original(root, m) {
  const auxiliary=m.id==='A001'&&m.sqlPath==='db/dm8/adjudication_source_authority.sql';
  if(auxiliary){
    const registry=JSON.parse(await readFile(path.resolve(root,'deployment/single-database/auxiliary-migrations.json'),'utf8'));
    const entry=registry.migrations?.find(e=>e.id==='A001');
    if(registry.format!=='SINGLE_DATABASE_AUXILIARY_MIGRATIONS/v1'||entry?.sqlPath!==m.sqlPath||entry?.sha256!==m.sha256||entry?.domain!=='adjudication'||entry?.kind!=='authority'||entry?.initializationOnly!==true)fail('CONTROLLED_AUXILIARY_ORIGINAL_REQUIRED');
  }else if(!/^db\/(dm8|identity-authority)\/V\d{3,}__[A-Za-z0-9_]+\.sql$/.test(m.sqlPath)||!path.basename(m.sqlPath).startsWith(m.id+'__'))fail('EXACT_ORIGINAL_SQL_REQUIRED');
  root=await realpath(root);const file=path.resolve(root,m.sqlPath);
  for(const segment of [path.dirname(path.dirname(file)),path.dirname(file),file])if((await lstat(segment)).isSymbolicLink())fail('SQL_SYMLINK');
  if(await realpath(file)!==file)fail('SQL_REDIRECT');const bytes=await readFile(file);
  if(hash(bytes)!==m.sha256)fail('SQL_SHA_MISMATCH');
  let controlledStatementSha256=[];
  if(m.forwardOriginalsSha256){
    const registryFile=path.resolve(root,'deployment/single-database/forward-originals.json');
    if((await lstat(registryFile)).isSymbolicLink()||await realpath(registryFile)!==registryFile)fail('FORWARD_REGISTRY_REDIRECT');
    const registryBytes=await readFile(registryFile);if(hash(registryBytes)!==m.forwardOriginalsSha256)fail('FORWARD_REGISTRY_SHA_MISMATCH');
    const registry=JSON.parse(registryBytes),matches=registry.originals?.filter(e=>e.sqlPath===m.sqlPath&&e.sha256===m.sha256&&e.id===m.id);
    if(registry.format!=='SINGLE_DATABASE_FORWARD_ORIGINALS/v1'||matches?.length!==1)fail('CONTROLLED_FORWARD_ORIGINAL_REQUIRED');
    controlledStatementSha256=matches[0].statements.map(v=>v.sha256);
  }
  return splitForwardSql(bytes.toString('utf8'),{adjudicationAuthorityInitialization:auxiliary,controlledStatementSha256});
}
export function admission(a,targetId,action,now=Date.now()) {
  if(a?.format!=='SINGLE_DATABASE_ACTION_APPROVAL/v1' || a.targetId!==targetId || a.action!==action || !a.owner || !a.source || a.currentMigration!==false || a.connectionSwitch!==false || a.deleteExisting!==false || !(Date.parse(a.expiresAt)>now) || !(Date.parse(a.approvedAt)<=now))fail('EXACT_CURRENT_ACTION_APPROVAL_REQUIRED');
}
/** Exact catalog checks; object count or tool exit zero is not a structural postcondition. */
export function evaluateInventoryChecks(schema, checks, {post=false}={}) {
  if(!schema||!Array.isArray(checks))fail('EXPLICIT_CHECKS_REQUIRED');
  return checks.map(c=>{
    if(!c||typeof c!=='object'||!['present','absent'].includes(c.expected)||(post&&c.expected!=='present'))fail('INVALID_STRUCTURAL_CHECK');
    const allowed={object:['kind','name','expected','type'],column:['kind','table','name','expected','dataType','nullable','size'],constraint:['kind','table','name','expected','type'],'table-rows':['kind','table','expected','min','max']}[c.kind];
    if(!allowed||Object.keys(c).some(k=>!allowed.includes(k)))fail('INVALID_STRUCTURAL_CHECK');
    if(c.kind==='table-rows') {
      ident(c.table);if(post||c.expected!=='present'||!Number.isSafeInteger(c.min)||!Number.isSafeInteger(c.max)||c.min<0||c.max<c.min)fail('INVALID_ROW_PRECONDITION');
      const rows=(schema.rows||[]).filter(r=>r.table===c.table);
      return rows.length===1&&Number.isSafeInteger(rows[0].count)&&rows[0].count>=c.min&&rows[0].count<=c.max;
    }
    ident(c.name);if(c.kind!=='object')ident(c.table);
    if(c.kind==='object') {
      if(c.type!==undefined&&(typeof c.type!=='string'||!['TABLE','BASE TABLE','INDEX','SEQUENCE','VIEW','TRIGGER'].includes(c.type)))fail('INVALID_OBJECT_TYPE_CHECK');
      const rows=schema.objects.filter(r=>r.name===c.name);
      if(c.expected==='absent')return rows.length===0;
      return rows.length===1&&(c.type===undefined||rows[0].type===c.type);
    }
    const rows=(schema[c.kind==='column'?'columns':'constraints']||[]).filter(r=>r.TABLE_NAME===c.table&&(c.kind==='column'?r.COLUMN_NAME:r.CONSTRAINT_NAME)===c.name);
    if(c.kind==='constraint'&&c.type!==undefined&&(typeof c.type!=='string'||!['P','U','R','C','PRIMARY KEY','UNIQUE','FOREIGN KEY','CHECK'].includes(c.type)))fail('INVALID_CONSTRAINT_TYPE_CHECK');
    if(c.kind==='column'&&c.dataType!==undefined&&(typeof c.dataType!=='string'||!/^[A-Z][A-Z0-9_ ]{0,63}$/.test(c.dataType)))fail('INVALID_COLUMN_TYPE_CHECK');
    if(c.kind==='column'&&c.nullable!==undefined&&typeof c.nullable!=='boolean')fail('INVALID_NULLABILITY_CHECK');
    if(c.kind==='column'&&c.size!==undefined&&(!Number.isSafeInteger(c.size)||c.size<1||c.size>1048576))fail('INVALID_COLUMN_SIZE_CHECK');
    if(c.expected==='absent')return rows.length===0;
    if(rows.length!==1)return false;
    const r=rows[0];
    if(c.kind==='constraint') {
      if(c.type!==undefined&&(typeof c.type!=='string'||!['P','U','R','C','PRIMARY KEY','UNIQUE','FOREIGN KEY','CHECK'].includes(c.type)))fail('INVALID_CONSTRAINT_TYPE_CHECK');
      return c.type===undefined||r.CONSTRAINT_TYPE===c.type;
    }
    if(c.dataType!==undefined&&(typeof c.dataType!=='string'||!/^[A-Z][A-Z0-9_ ]{0,63}$/.test(c.dataType)))fail('INVALID_COLUMN_TYPE_CHECK');
    if(c.nullable!==undefined&&typeof c.nullable!=='boolean')fail('INVALID_NULLABILITY_CHECK');
    const nullable=r.NULLABLE==='Y'||r.IS_NULLABLE==='YES'?true:r.NULLABLE==='N'||r.IS_NULLABLE==='NO'?false:undefined;
    const sizes=(schema.jdbcColumns||[]).filter(v=>v.TABLE_NAME===c.table&&v.COLUMN_NAME===c.name);
    return (c.size===undefined||(sizes.length===1&&Number(sizes[0].COLUMN_SIZE)===c.size))&&(c.dataType===undefined||r.DATA_TYPE===c.dataType)&&(c.nullable===undefined||nullable===c.nullable);
  }).every(Boolean);
}

export async function executeLifecycle({manifest, approval, workspaceRoot, journalDir, inventory, execute, validateProtection, acquireExecutionFence, now}) {
  const {targetId, schemas, accounts, migrations, expectedInventorySha256, mode}=manifest;
  const enteredAt=now??Date.now();
  const currentTime=()=>Math.max(enteredAt,Date.now());
  const assertCurrent=()=>{admission(approval,targetId,'execute',currentTime());if(mode==='existing')admission(approval.protection,targetId,'existing-protection',currentTime());};
  admission(approval,targetId,'execute',currentTime());if(approval.manifestSha256!==hash(canonical(manifest)))fail('APPROVAL_MANIFEST_MISMATCH');if(!['empty','existing'].includes(mode))fail('SEPARATE_EMPTY_OR_EXISTING_MODE_REQUIRED');
  if(!Array.isArray(migrations)||!migrations.length)fail('MIGRATIONS_REQUIRED');
  if(!acquireExecutionFence||!Array.isArray(approval.writers)||!approval.writers.length||approval.writers.some(w=>!w.id||!w.owner||!w.responsibilitySource))fail('EXECUTION_WRITER_FENCE_REQUIRED');
  const lease=await acquireExecutionFence(approval.writers);
  if(!lease?.assertHeld||!lease.release)fail('EXECUTION_WRITER_FENCE_REQUIRED');
  let lock;
  try {
    await lease.assertHeld();admission(approval,targetId,'execute',currentTime());await mkdir(journalDir,{recursive:true});
    lock=await open(path.join(journalDir,'execute.lock'),'wx');
    let live=assertInventory(await inventory(),targetId,schemas,accounts,manifest.deployment);
    if(foreignKeyDependencies(live).unresolved.length)fail('UNKNOWN_FOREIGN_KEY_TARGET');
    const journalFile=path.join(journalDir,'events.jsonl');let events=[];
    try {events=(await readFile(journalFile,'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);}catch(e){if(e.code!=='ENOENT')throw e;}
    let prev='';for(const e of events){const {eventSha256,...body}=e;if(body.previousSha256!==prev||hash(canonical(body))!==eventSha256)fail('JOURNAL_CHAIN_INVALID');prev=eventSha256;}
    const append=async body=> {body={...body,at:new Date().toISOString(),previousSha256:prev};prev=hash(canonical(body));const event={...body,eventSha256:prev};const fd=await open(journalFile,'a');try {await fd.writeFile(JSON.stringify(event)+'\n');await fd.sync();}finally{await fd.close();}events.push(event);return event;};
    if(!Array.isArray(manifest.knownObjects))fail('REVIEWED_OBJECT_MAP_REQUIRED');
    const keys=new Set(),done=new Set();let applied=0,skipped=0;
    for(const m of migrations){ident(m.schema);if(!schemas.includes(m.schema)||! /^(?:V[0-9]{3,}|A001)$/.test(m.id)||!/^[a-f0-9]{64}$/.test(m.sha256)||keys.has(`${m.schema}:${m.id}`))fail('MIGRATION_KEY_INVALID');keys.add(`${m.schema}:${m.id}`);}
    for(const m of migrations){evaluateInventoryChecks(live.schemas.find(s=>s.schema===m.schema),m.prechecks);evaluateInventoryChecks(live.schemas.find(s=>s.schema===m.schema),m.postchecks,{post:true});}
    for(const m of migrations)if(m.id==='A001'){
      if(mode!=='empty'&&!events.some(e=>e.kind==='SUCCESS'&&e.key===m.schema+':A001'))fail('AUXILIARY_INITIALIZATION_REJECTS_EXISTING');
      for(const name of ['ADJ2_SOURCE_GENERATION','ADJ2_SOURCE_USE','ADJ2_SOURCE_EVENT']){
        if(!m.prechecks.some(c=>c.kind==='object'&&c.name===name&&c.expected==='absent')||!m.postchecks.some(c=>c.kind==='object'&&c.name===name&&c.type==='TABLE'&&c.expected==='present'))fail('AUXILIARY_EXACT_TABLE_CHECKS_REQUIRED');
      }
    }
    const dependencyDone=new Set();
    for(const m of migrations) {
      if(!Array.isArray(m.dependsOn)||new Set(m.dependsOn).size!==m.dependsOn.length||m.dependsOn.some(k=>!keys.has(k)||!dependencyDone.has(k)))fail('DEPENDENCY_NOT_APPLIED_IN_ORDER');
      if(!Array.isArray(m.prechecks)||!Array.isArray(m.postchecks)||!m.postchecks.length)fail('EXPLICIT_CHECKS_REQUIRED');
      dependencyDone.add(`${m.schema}:${m.id}`);
    }
    // Only explicit later forward originals may supersede an earlier structural postcondition.
    for(let n=0;n<migrations.length;n++)for(const rule of migrations[n].postcheckSupersededBy||[]) {
      const later=migrations.findIndex(m=>`${m.schema}:${m.id}`===rule.by);
      const final=manifest.finalChecks?.[migrations[n].schema];
      if(later<=n||migrations[later].schema!==migrations[n].schema||!migrations[n].postchecks.some(c=>canonical(c)===canonical(rule.check))
          ||!Array.isArray(final)||!final.some(c=>c.kind===rule.check.kind&&c.name===rule.check.name&&c.table===rule.check.table&&c.expected==='absent'))fail('EXPLICIT_FORWARD_SUPERSESSION_REQUIRED');
    }
    // All scripts are authenticated before first DDL, including previously applied originals.
    const sqls=await Promise.all(migrations.map(m=>original(workspaceRoot,m)));
    if(events.some(e=>e.targetId!==targetId))fail('JOURNAL_TARGET_MISMATCH');
    const unresolved=new Map();for(const e of events){if(e.kind==='START')unresolved.set(e.key,e);if(e.kind==='SUCCESS')unresolved.delete(e.key);}
    if(unresolved.size)fail('PARTIAL_DDL_OR_FAILURE_REQUIRES_RECONCILIATION');
    if(inventoryDigest(live)!==expectedInventorySha256 && !events.some(e=>e.targetId===targetId&&e.kind==='SUCCESS'&&e.after===inventoryDigest(live))) fail('LIVE_INVENTORY_DRIFT');
    if(live.schemas.some(s=>s.objects.some(o=>!manifest.knownObjects.some(k=>k.schema===s.schema&&k.name===o.name&&k.type===o.type))) && !events.some(e=>e.kind==='SUCCESS'&&e.after===inventoryDigest(live)))fail('UNKNOWN_OBJECT_REQUIRES_REVIEW');
    if(!events.length && mode==='empty' && live.schemas.some(s=>s.objects.length))fail('EMPTY_MODE_REJECTS_EXISTING_OBJECTS');
    if(mode==='existing'){if(!validateProtection || !await validateProtection(approval.protection,live))fail('ACTUAL_AUTHENTICATED_PROTECTION_REQUIRED');admission(approval.protection,targetId,'existing-protection',currentTime());if(approval.protection.inventorySha256!==inventoryDigest(live)||!approval.protection.receiptSha256||approval.protection.authorityRestoreForbidden!==true)fail('EXISTING_PROTECTION_REQUIRED');}
    for(let n=0;n<migrations.length;n++) {
      assertCurrent();const m=migrations[n],key=`${m.schema}:${m.id}`;
      if(!Array.isArray(m.dependsOn) || m.dependsOn.some(k=>!done.has(k)))fail('DEPENDENCY_NOT_APPLIED_IN_ORDER');
      const old=events.filter(e=>e.key===key);if(old.some(e=>e.scriptSha256!==m.sha256))fail('RECORDED_SCRIPT_SHA_CONFLICT');
      const schema=live.schemas.find(s=>s.schema===m.schema),names=new Set(schema.objects.map(o=>o.name));
      if(!Array.isArray(m.postchecks)||!m.postchecks.length||!Array.isArray(m.prechecks))fail('EXPLICIT_CHECKS_REQUIRED');
      const check=checks=>evaluateInventoryChecks(schema,checks);
      if(old.some(e=>e.kind==='SUCCESS')){const checks=m.postchecks.filter(c=>!(m.postcheckSupersededBy||[]).some(r=>canonical(r.check)===canonical(c)&&events.some(e=>e.kind==='SUCCESS'&&e.key===r.by)));if(!check(checks))fail('APPLIED_OBJECT_DRIFT');done.add(key);skipped++;continue;}
      if(!check(m.prechecks))fail('PRECONDITION_FAILED');
      if(mode==='existing'&&!/^[a-f0-9]{64}$/.test(m.nonDestructiveReviewSha256||''))fail('NONEMPTY_REVIEW_REQUIRED');
      await append({kind:'START',targetId,key,scriptSha256:m.sha256,before:inventoryDigest(live),prechecks:m.prechecks,original:m.sqlPath});
      try {
        await lease.assertHeld();assertCurrent();await execute(m.schema,sqls[n],m);assertCurrent();await lease.assertHeld();live=assertInventory(await inventory(),targetId,schemas,accounts,manifest.deployment);assertCurrent();
        const afterSchema=live.schemas.find(s=>s.schema===m.schema);
        if(!evaluateInventoryChecks(afterSchema,m.postchecks,{post:true}))fail('POSTCONDITION_FAILED');
        if(m.id==='A001'){
          const rowSha=hash(canonical({GENERATION:'0',SINGLETON_KEY:'1',STATE:'READY'})),expectedSeedSha=hash(canonical([rowSha]));
          for(const table of ['ADJ2_SOURCE_GENERATION','ADJ2_SOURCE_USE','ADJ2_SOURCE_EVENT']){
            const rows=afterSchema.rows.filter(r=>r.table===table);
            if(rows.length!==1||rows[0].count!==(table==='ADJ2_SOURCE_GENERATION'?1:0)||(table==='ADJ2_SOURCE_GENERATION'&&rows[0].contentSha256!==expectedSeedSha))fail('AUXILIARY_INITIAL_STATE_MISMATCH');
          }
        }
        await append({kind:'SUCCESS',targetId,key,scriptSha256:m.sha256,after:inventoryDigest(live),postchecks:m.postchecks});done.add(key);applied++;
      }catch(e){let scene;try{scene=await inventory();}catch{scene={unavailable:true};}const scenePath=path.join(journalDir,`failure-${events.length}.json`);await writeFile(scenePath,JSON.stringify(scene,null,2),{flag:'wx'});await append({kind:'FAILURE',targetId,key,scriptSha256:m.sha256,failureScene:path.basename(scenePath),failureSceneSha256:hash(await readFile(scenePath)),rollbackClaimed:false});fail('DDL_FAILED_PARTIAL_PRESERVED');}
    }
    if(manifest.finalChecks)for(const schema of schemas)if(!evaluateInventoryChecks(live.schemas.find(s=>s.schema===schema),manifest.finalChecks[schema]))fail('FINAL_INSTALLATION_STRUCTURE_MISMATCH');
    assertCurrent();return {format:'SINGLE_DATABASE_EXECUTION_RECEIPT/v1',targetId,mode,applied,skipped,actualExecution:true,inventorySha256:inventoryDigest(live),journalHead:prev,ddlAtomicityClaimed:false};
  } finally {if(lock){await lock.close(); const {unlink}=await import('node:fs/promises');await unlink(path.join(journalDir,'execute.lock'));}await lease.release();}
}

/** Persistent JSON-line engineering adapter; a held file-H2 connection prevents reopen artifacts.
 * The evidence harness owns account selection; no product API or remote DB fallback is introduced. */
export async function jdbcSession(config) {
  if(!config.java||!config.classpath||!config.passwordEnv||!process.env[config.passwordEnv])fail('JDBC_CONFIGURATION_REQUIRED');
  const child=spawn(config.java,['-Xmx256m','-cp',config.classpath,config.mainClass,'--session'],{shell:false,env:{...process.env,LIFECYCLE_JDBC_URL:config.url,LIFECYCLE_JDBC_USER:config.user,LIFECYCLE_JDBC_PASSWORD:process.env[config.passwordEnv]}});
  let buffer='',waiting=[],dead=false;child.stderr.resume();
  const die=()=>{dead=true;for(const w of waiting){clearTimeout(w.timer);w.reject(new Error('JDBC_SESSION_CLOSED'));}waiting=[];};
  child.on('error',die);child.on('close',die);
  child.stdout.on('data',b=>{buffer+=b;if(buffer.length>256*1024*1024){child.kill();return;}let pos;while((pos=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,pos);buffer=buffer.slice(pos+1);const w=waiting.shift();if(!w){child.kill();return;}clearTimeout(w.timer);try{const response=JSON.parse(line);if(response.error)throw new Error(`${response.error}:${response.sqlState}:${response.vendorCode}`);w.resolve(response);}catch(e){w.reject(e);}}});
  return {request:input=>new Promise((resolve,reject)=>{if(dead){reject(new Error('JDBC_SESSION_CLOSED'));return;}const timer=setTimeout(()=>child.kill(),120000);waiting.push({resolve,reject,timer});child.stdin.write(JSON.stringify(input)+'\n');}),close:async()=>{child.stdin.end();await new Promise(resolve=>{if(dead)resolve();else child.once('close',resolve);});}};
}
