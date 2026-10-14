#!/usr/bin/env node
import {readFile,open,realpath} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateDeploymentConfiguration} from './lib/single-database-lifecycle.mjs';
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {canonical,hash,jdbcRequest} from './lib/single-database-lifecycle-runtime.mjs';
import {validateObjectGrantManifest,executeObjectGrants} from './lib/single-database-object-grants.mjs';
import {acquireObjectGrantFence} from './lib/single-database-object-grants-fence.mjs';
const fail=c=>{throw new Error(c);};
const [configPath,output]=process.argv.slice(2);if(!configPath||!output||process.argv.length!==4){console.error('Usage: node scripts/grant-single-database-objects.mjs CONFIG.json NEW_RECEIPT.json');process.exit(2);}
let receipt;
try{
  receipt=await open(path.resolve(output),'wx');const c=JSON.parse(await readFile(configPath,'utf8'));
  const {bindings,schemas,accounts}=validateObjectGrantManifest(c.manifest);validateDeploymentConfiguration(c.deployment);
  const protectedConfig={...c};delete protectedConfig.approval;
  if(c.approval.configurationSha256!==hash(canonical(protectedConfig))||canonical(c.deployment)!==canonical(c.manifest.deployment)||c.targetId!==c.manifest.targetId||canonical(c.schemas)!==canonical(schemas)||canonical(c.accounts)!==canonical(accounts))fail('PINNED_OBJECT_GRANT_CONFIGURATION_REQUIRED');
  const expected={executionFenceAdapter:fileURLToPath(new URL('./lib/single-database-object-grants-fence.mjs',import.meta.url)),jdbcSourcePath:fileURLToPath(new URL('./java/SingleDatabaseLifecycleJdbc.java',import.meta.url))};
  for(const [field,actual] of Object.entries(expected))if(await realpath(c[field])!==actual||c[field]!==path.resolve(c[field]))fail('MANAGED_OBJECT_GRANT_ADAPTER_REQUIRED');
  if(!/[\\/]SingleDatabaseLifecycleJdbc\.class$/.test(c.jdbcClassPath)||await realpath(c.jdbcClassPath)!==c.jdbcClassPath)fail('PINNED_JDBC_CLASS_REQUIRED');
  const ports=[c.inventoryJdbc,...Object.values(c.ownerJdbc||{})];
  if(Object.keys(c.ownerJdbc||{}).length!==6||[...bindings].some(([schema,b])=>c.ownerJdbc[schema]?.user!==b.ownerUser)||c.inventoryJdbc?.user!=='SYSDBA')fail('EXACT_SCHEMA_OWNER_PORTS_REQUIRED');
  for(const p of ports)if(p.mainClass!=='SingleDatabaseLifecycleJdbc'||p.url!==c.deployment.database.jdbcUrl||path.resolve(p.classpath.split(path.delimiter)[0])!==path.dirname(c.jdbcClassPath)||! /^[A-Z][A-Z0-9_]*$/.test(p.passwordEnv||''))fail('PINNED_MANAGED_JDBC_PORT_REQUIRED');
  const root=await realpath(c.workspaceRoot);
  const decisionCheck=async()=>{
    const a={...c.approval};delete a.currentDecisionEvidenceSha256;const bytes=await readFile(c.currentDecisionPath);
    if(hash(bytes)!==c.approval.currentDecisionEvidenceSha256||canonical(JSON.parse(bytes))!==canonical(a))fail('CURRENT_ORIGINAL_GRANT_DECISION_REQUIRED');
    for(const [field,digest] of [['executionFenceAdapter','executionFenceAdapterSha256'],['jdbcSourcePath','jdbcSourceSha256'],['jdbcClassPath','jdbcClassSha256']])if(hash(await readFile(c[field]))!==c.approval[digest])fail('PINNED_GRANT_ADAPTER_CHANGED');
    if(hash(await readFile(fileURLToPath(new URL('./lib/single-database-object-grants.mjs',import.meta.url))))!==c.approval.coordinatorSha256)fail('PINNED_GRANT_COORDINATOR_CHANGED');
    for(const e of c.manifest.steps.flatMap(s=>s.evidence)){const p=await realpath(path.resolve(root,e.path));const rel=path.relative(root,p);if(rel.startsWith('..')||path.isAbsolute(rel)||hash(await readFile(p))!==e.sha256)fail('OBJECT_GRANT_SOURCE_ORIGINAL_CHANGED');}
  };
  const result=await executeObjectGrants({manifest:c.manifest,approval:c.approval,decisionCheck,assertTarget:()=>assertLiveOwnedTarget(c),acquireFence:w=>acquireObjectGrantFence(c,w),inventory:()=>jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas,accounts}),execute:(_s,r)=>jdbcRequest(c.ownerJdbc[r.schema],r),journalDir:c.journalDir});
  await receipt.writeFile(JSON.stringify(result,null,2)+'\n');await receipt.sync();console.log(JSON.stringify({passed:true,productInitialized:false}));
}catch(e){if(receipt){await receipt.writeFile(JSON.stringify({passed:false,receiptKind:'OBJECT_GRANT_FAILED_NOT_SUCCESS',partialSceneMayBeInJournal:true})+'\n');await receipt.sync();}console.error(JSON.stringify({passed:false,code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'OBJECT_GRANT_INPUT_OR_ADAPTER_UNAVAILABLE'}));process.exitCode=1;}finally{if(receipt)await receipt.close();}

