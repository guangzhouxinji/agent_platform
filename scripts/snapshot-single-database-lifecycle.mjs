#!/usr/bin/env node
import {nativeDiagnostic,withNativeStage} from './lib/single-database-native-diagnostic.mjs';
import {readFile,writeFile,realpath,lstat} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {jdbcRequest,hash,canonical,admission} from './lib/single-database-lifecycle-runtime.mjs';
import {sealSnapshot} from './lib/single-database-lifecycle-snapshot.mjs';
import {createHumanWaitingInspector} from './lib/single-database-human-waiting-source.mjs';
const [configPath,newReceipt]=process.argv.slice(2);
if(!configPath||!newReceipt||process.argv.length!==4){console.error('Usage: node scripts/snapshot-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json');process.exit(2);}
try {
 try {await lstat(resolve(newReceipt));throw new Error('NEW_OUTPUT_REQUIRED');}catch(e){if(e.code!=='ENOENT')throw e;}
 const c=JSON.parse(await readFile(configPath,'utf8'));
 try {await lstat(resolve(c.packageOutput));throw new Error('NEW_PACKAGE_OUTPUT_REQUIRED');}catch(e){if(e.code!=='ENOENT')throw e;}
 admission(c.approval,c.targetId,'snapshot');
 if(c.approval.configurationSha256!==hash(canonical(c.deployment))||c.approval.exportMethod!=='DM8_NATIVE_EXPORT')throw new Error('EXACT_NATIVE_EXPORT_APPROVAL_REQUIRED');
 // No built-in fake native dump, Docker stop, or writer-list inference. Deployment supplies the
 // explicitly approved native-export/fencing port implementation; unknown writers stay blocked.
 const adapterPath=await realpath(c.adapterPath),bytes=await readFile(adapterPath);
 if(adapterPath!==resolve(c.adapterPath)||hash(bytes)!==c.approval.adapterSha256)throw new Error('APPROVED_NATIVE_FENCE_ADAPTER_REQUIRED');
 const adapter=await import(pathToFileURL(adapterPath).href);
 if(typeof adapter.acquireFence!=='function'||typeof adapter.exportDatabase!=='function')throw new Error('ACTUAL_NATIVE_FENCE_PORTS_REQUIRED');
 let inventoryCalls=0;
 const humanWaiting=c.humanWaiting?createHumanWaitingInspector(c,c.approval):undefined;
 const inventory=()=>withNativeStage(inventoryCalls++===0?'INVENTORY_BEFORE':'INVENTORY_AFTER',async()=>{await withNativeStage('TARGET_INSPECT',()=>assertLiveOwnedTarget(c));return jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,deployment:c.deployment,...(c.recoveryCatalog?{restoreCatalogProfile:c.recoveryCatalog.profileId}:{})});});
 const report=await withNativeStage('SNAPSHOT_SEAL',()=>sealSnapshot({approval:c.approval,inventory,exportDatabase:()=>adapter.exportDatabase(c),acquireFence:writers=>withNativeStage('FENCE_ACQUIRE',()=>adapter.acquireFence(c,writers)),files:c.files,key:Buffer.from(process.env[c.keyEnv]||'','base64'),output:resolve(c.packageOutput),schemas:c.schemas,accounts:c.accounts,deployment:c.deployment,recoveryCatalog:c.recoveryCatalog,humanWaiting}));
 await writeFile(resolve(newReceipt),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
 console.log(JSON.stringify({passed:true,receipt:resolve(newReceipt),fullThreeDomainProductRecoveryProven:false}));
}catch(e){console.error(JSON.stringify({passed:false,...(nativeDiagnostic(e)?{diagnostic:nativeDiagnostic(e)}:{}),code:/^[A-Z0-9_]+(?::[A-Za-z0-9]*:-?[0-9]+)?$/.test(e.message)?e.message:'INPUT_OR_ADAPTER_UNAVAILABLE_OR_INVALID'}));process.exitCode=1;}
