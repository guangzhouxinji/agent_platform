#!/usr/bin/env node
import {readFile,open,realpath,lstat} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {jdbcRequest,hash,canonical} from './lib/single-database-lifecycle-runtime.mjs';
import {writeRestoreComparisonEvidence} from './lib/single-database-restore-evidence.mjs';
import {compareRestoreInventories} from './lib/single-database-restore-comparison.mjs';
import {validateRestoreConfiguration,assertRestoreCurrent,restoreSnapshot,restoreOriginalFiles,checkOriginalFiles} from './lib/single-database-lifecycle-restore.mjs';
const [configPath,newReceipt,newJournal]=process.argv.slice(2);
if(!configPath||!newReceipt||!newJournal||process.argv.length!==5){console.error('Usage: node scripts/restore-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json NEW_JOURNAL.jsonl');process.exit(2);}
let journalFd,receiptFd,key;
try {
 const c=JSON.parse(await readFile(configPath,'utf8'));validateRestoreConfiguration(c);
 if(c.recoveryCatalog){if(await realpath(resolve(c.recoveryComparisonDirectory,'..'))!==resolve(c.recoveryComparisonDirectory,'..'))throw new Error('RESTORE_COMPARISON_PARENT_REDIRECT');try{await lstat(c.recoveryComparisonDirectory);throw new Error('NEW_COMPARISON_EVIDENCE_DIRECTORY_REQUIRED');}catch(e){if(e.code!=='ENOENT')throw e;}}
 const outputs=[resolve(newReceipt),resolve(newJournal),resolve(c.packagePath),resolve(c.nativeRestore.attemptMarker),resolve(c.nativeRestore.maintenanceLock),...(c.recoveryCatalog?[c.recoveryComparisonDirectory]:[])];
 if(new Set(outputs).size!==outputs.length)throw new Error('DISTINCT_NEW_RESTORE_OUTPUTS_REQUIRED');
 for(const f of outputs.slice(0,2)){if(await realpath(resolve(f,'..'))!==resolve(f,'..'))throw new Error('OUTPUT_PARENT_REDIRECT');try{await lstat(f);throw new Error('NEW_OUTPUT_REQUIRED');}catch(e){if(e.code!=='ENOENT')throw e;}}
 const adapterPath=await realpath(c.adapterPath);if(adapterPath!==resolve(c.adapterPath)||hash(await readFile(adapterPath))!==c.approval.adapterSha256)throw new Error('APPROVED_RESTORE_ADAPTER_REQUIRED');
 const adapter=await import(pathToFileURL(adapterPath).href);if(typeof adapter.acquireFence!=='function'||typeof adapter.importDatabase!=='function'||typeof adapter.requireCurrentRestoreDecision!=='function')throw new Error('ACTUAL_NATIVE_RESTORE_PORTS_REQUIRED');
 key=Buffer.from(process.env[c.keyEnv]||'','base64');if(key.length!==32)throw new Error('MANAGED_PACKAGE_KEY_REQUIRED');
 receiptFd=await open(resolve(newReceipt),'wx',0o600);journalFd=await open(resolve(newJournal),'wx',0o600);
 const journal=async event=>{await journalFd.writeFile(JSON.stringify({...event,at:new Date().toISOString()})+'\n');await journalFd.sync();};
 const rawInventories=new WeakMap();let lastInventoryRaw=Buffer.alloc(0);
 const inventory=async()=>{lastInventoryRaw=Buffer.alloc(0);assertRestoreCurrent(c);await assertLiveOwnedTarget(c);const result=await jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,...(c.recoveryCatalog?{restoreCatalogProfile:c.recoveryCatalog.profileId}:{})},c.recoveryCatalog?{captureRaw:bytes=>{lastInventoryRaw=Buffer.from(bytes);}}:{});if(c.recoveryCatalog)rawInventories.set(result,lastInventoryRaw);assertRestoreCurrent(c);return result;};
 const writeComparisonEvidence=c.recoveryCatalog?({sourceBytes,destinationBytes,origin,forceFailureCode})=>writeRestoreComparisonEvidence({directory:c.recoveryComparisonDirectory,sourceBytes,destinationBytes,compare:({source,destination})=>forceFailureCode?{passed:false,code:forceFailureCode,differences:[],actualNativeRestoreProven:false}:compareRestoreInventories({source,destination,origin,sourceTargetId:c.sourceTargetId,targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,deployment:c.deployment})}):undefined;
 const readInventoryBytes=c.recoveryCatalog?i=>i?rawInventories.get(i):lastInventoryRaw:undefined;
 const report=await restoreSnapshot({configuration:c,key,inventory,acquireFence:()=>adapter.acquireFence(c),importDatabase:adapter.importDatabase,restoreFiles:restoreOriginalFiles,checkFiles:checkOriginalFiles,journal,writeComparisonEvidence,readInventoryBytes});
 await adapter.requireCurrentRestoreDecision(c);assertRestoreCurrent(c);await receiptFd.writeFile(JSON.stringify(report,null,2)+'\n');await receiptFd.sync();assertRestoreCurrent(c);await adapter.requireCurrentRestoreDecision(c);
 console.log(JSON.stringify({passed:true,receipt:resolve(newReceipt),businessActivation:false,fullThreeDomainProductRecoveryProven:false}));
}catch(e){if(receiptFd){try{await receiptFd.truncate(0);await receiptFd.write(Buffer.from(JSON.stringify({passed:false,partialScenePreserved:true,rollbackClaimed:false})+'\n'),0,undefined,0);await receiptFd.sync();}catch{ /* new receipt remains incomplete; never a valid success */ }}console.error(JSON.stringify({passed:false,partialScenePreserved:true,rollbackClaimed:false,code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'RESTORE_INPUT_OR_OPERATION_FAILED'}));process.exitCode=1;
}finally{key?.fill(0);await journalFd?.close();await receiptFd?.close();}
