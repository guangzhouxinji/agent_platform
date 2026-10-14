#!/usr/bin/env node
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {readFile,writeFile,realpath} from 'node:fs/promises';
import {openSnapshot} from './lib/single-database-lifecycle-snapshot.mjs';
import {resolve,dirname,delimiter} from 'node:path';
import {jdbcRequest,assertInventory,inventoryDigest,executeLifecycle,hash,canonical} from './lib/single-database-lifecycle-runtime.mjs';
const [configPath,output]=process.argv.slice(2);
if(!configPath||!output||process.argv.length!==4){console.error('Usage: node scripts/execute-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json');process.exit(2);}
try {
 const c=JSON.parse(await readFile(configPath,'utf8'));
 const expectedSource=fileURLToPath(new URL('./java/SingleDatabaseLifecycleJdbc.java',import.meta.url));
 if(await realpath(c.jdbcSourcePath)!==expectedSource||hash(await readFile(expectedSource))!==c.approval.jdbcSourceSha256||!/[\\/]SingleDatabaseLifecycleJdbc\.class$/.test(c.jdbcClassPath)||await realpath(c.jdbcClassPath)!==resolve(c.jdbcClassPath)||hash(await readFile(c.jdbcClassPath))!==c.approval.jdbcClassSha256)throw new Error('PINNED_MIGRATION_JDBC_ADAPTER_REQUIRED');
 for(const p of [c.inventoryJdbc,...Object.values(c.ownerJdbc||{})])if(p.mainClass!=='SingleDatabaseLifecycleJdbc'||resolve(p.classpath.split(delimiter)[0])!==dirname(resolve(c.jdbcClassPath))||p.dockerJdbc&&p.dockerJdbc.classSha256!==c.approval.jdbcClassSha256)throw new Error('EXACT_PINNED_MIGRATION_JDBC_PORT_REQUIRED');
 const inventory=async()=>{await assertLiveOwnedTarget(c);return jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas:c.schemas,accounts:c.accounts});};
 let report;
 for(const m of c.manifest.migrations||[])if(m.id==='A001'&&m.schema!==c.deployment.domains.find(d=>d.domain==='adjudication')?.authority.schema)throw new Error('EXACT_ADJUDICATION_AUTHORITY_SCHEMA_REQUIRED');
 if(c.manifest.accounts?.length===15&&canonical(c.manifest.deployment)!==canonical(c.deployment))throw new Error('EXACT_MANIFEST_DEPLOYMENT_REQUIRED');
 if(c.approval.manifestSha256!==hash(canonical(c.manifest)))throw new Error('APPROVAL_MANIFEST_MISMATCH');
 const fencePath=await realpath(c.executionFenceAdapter);if(fencePath!==resolve(c.executionFenceAdapter)||hash(await readFile(fencePath))!==c.approval.executionFenceAdapterSha256)throw new Error('APPROVED_EXECUTION_FENCE_ADAPTER_REQUIRED');
 const fence=await import(pathToFileURL(fencePath).href);if(typeof fence.acquireExecutionFence!=='function')throw new Error('ACTUAL_EXECUTION_FENCE_PORT_REQUIRED');
 report=await executeLifecycle({manifest:c.manifest,approval:c.approval,acquireExecutionFence:writers=>fence.acquireExecutionFence(c,writers),workspaceRoot:c.workspaceRoot,journalDir:c.journalDir,inventory,validateProtection:async(p,live)=>{if(!p?.file||!p?.keyEnv||!p?.receiptSha256)return false;const opened=await openSnapshot({file:p.file,key:Buffer.from(process.env[p.keyEnv]||'','base64'),expectedTargetId:c.targetId,expectedSha256:p.receiptSha256});opened.native.fill(0);return opened.metadata.inventorySha256===inventoryDigest(live)&&p.authorityRestoreForbidden===true;},execute:async(schema,statements,m)=>{await assertLiveOwnedTarget(c);return jdbcRequest(c.ownerJdbc[schema],{action:'execute',schema,statements,controlledInitializationId:m.id==='A001'?'A001':'',controlledOriginalSha256:m.forwardOriginalsSha256?m.sha256:''});}});
 await writeFile(resolve(output),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
 console.log(JSON.stringify({passed:true,output:resolve(output)}));
}catch(e){console.error(JSON.stringify({passed:false,code:/^[A-Z0-9_]+(?::[A-Za-z0-9]*:-?[0-9]+)?$/.test(e.message)?e.message:'INPUT_OR_ADAPTER_UNAVAILABLE_OR_INVALID'}));process.exitCode=1;}
