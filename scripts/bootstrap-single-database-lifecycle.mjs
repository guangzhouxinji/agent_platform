#!/usr/bin/env node
import {readFile,open,realpath} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {acquireBootstrapExecutionFence} from './lib/single-database-bootstrap-fence.mjs';
import {executeBootstrap,validateBootstrapManifest} from './lib/single-database-bootstrap.mjs';
import {deploymentAccounts} from './lib/single-database-lifecycle.mjs';
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {jdbcRequest,hash,canonical} from './lib/single-database-lifecycle-runtime.mjs';
const [configPath,output]=process.argv.slice(2);
if(!configPath||!output||process.argv.length!==4){console.error('Usage: node scripts/bootstrap-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json');process.exit(2);}
let outputHandle;
try {
  // Reserve exclusively before any DB action: a stale receipt cannot turn completed DDL into an ambiguous retry.
  outputHandle=await open(resolve(output),'wx');
  const c=JSON.parse(await readFile(configPath,'utf8'));validateBootstrapManifest(c.manifest);
  const protectedConfig={...c};delete protectedConfig.approval;
  if(c.approval.configurationSha256!==hash(canonical(protectedConfig))||c.adminJdbc.mainClass!=='SingleDatabaseLifecycleJdbc'||resolve(c.adminJdbc.classpath.split(process.platform==='win32'?';':':')[0])!==resolve(c.jdbcClassPath,'..')||!/[\\/]SingleDatabaseLifecycleJdbc\.class$/.test(c.jdbcClassPath))throw new Error('PINNED_BOOTSTRAP_CONFIGURATION_REQUIRED');
  if(c.adminJdbc.user!=='SYSDBA'||c.adminJdbc.url!==c.manifest.deployment.database.jdbcUrl||canonical(c.deployment)!==canonical(c.manifest.deployment)||c.targetId!==c.manifest.targetId)throw new Error('EXACT_SEPARATE_ADMIN_PORT_REQUIRED');
  const adapter=await realpath(c.executionFenceAdapter);
  if(adapter!==fileURLToPath(new URL('./lib/single-database-bootstrap-fence.mjs',import.meta.url))||adapter!==resolve(c.executionFenceAdapter)||hash(await readFile(adapter))!==c.approval.executionFenceAdapterSha256)throw new Error('PINNED_FENCE_ADAPTER_REQUIRED');
  const fence={acquireExecutionFence:acquireBootstrapExecutionFence};
  const decisionCheck=async()=>{
    if(hash(await readFile(adapter))!==c.approval.executionFenceAdapterSha256)throw new Error('FENCE_ADAPTER_CHANGED');
    const bytes=await readFile(c.currentDecisionPath),a={...c.approval};delete a.currentDecisionEvidenceSha256;
    if(hash(bytes)!==c.approval.currentDecisionEvidenceSha256||canonical(JSON.parse(bytes))!==canonical(a))throw new Error('CURRENT_ORIGINAL_DECISION_REQUIRED');
    if(hash(await readFile(c.jdbcSourcePath))!==c.approval.jdbcSourceSha256||hash(await readFile(c.jdbcClassPath))!==c.approval.jdbcClassSha256)throw new Error('PINNED_JDBC_ADAPTER_REQUIRED');
  };
  const accounts=deploymentAccounts(c.manifest.deployment);
  const receipt=await executeBootstrap({manifest:c.manifest,approval:c.approval,decisionCheck,assertTarget:()=>assertLiveOwnedTarget(c),acquireFence:w=>fence.acquireExecutionFence(c,w),
    inventory:()=>jdbcRequest(c.adminJdbc,{action:'bootstrap-inventory',targetId:c.targetId,accounts}),
    execute:step=>jdbcRequest(c.adminJdbc,{action:'bootstrap-step',step}),journalDir:c.journalDir});
  await outputHandle.writeFile(JSON.stringify(receipt,null,2)+'\n');await outputHandle.sync();console.log(JSON.stringify({passed:true,productInitialized:false}));
}catch(e){if(outputHandle){await outputHandle.writeFile(JSON.stringify({passed:false,receiptKind:'BOOTSTRAP_FAILED_NOT_SUCCESS',partialSceneInJournal:true})+'\n');await outputHandle.sync();}console.error(JSON.stringify({passed:false,code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'BOOTSTRAP_INPUT_OR_ADAPTER_UNAVAILABLE'}));process.exitCode=1;}finally{if(outputHandle)await outputHandle.close();}
