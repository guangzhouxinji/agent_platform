#!/usr/bin/env node
import {assertLiveOwnedTarget} from './lib/single-database-lifecycle-target.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {foreignKeyDependencies,jdbcRequest,assertInventory,inventoryDigest,executeLifecycle,hash,canonical} from './lib/single-database-lifecycle-runtime.mjs';
import {deriveCompleteNativeForeignKeys} from './lib/single-database-restore-catalog.mjs';
const [configPath,output]=process.argv.slice(2);
if(!configPath||!output||process.argv.length!==4){console.error('Usage: node scripts/inventory-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json');process.exit(2);}
try {
 const c=JSON.parse(await readFile(configPath,'utf8'));
 const inventory=async()=>{await assertLiveOwnedTarget(c);return jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,...(c.recoveryCatalog?{restoreCatalogProfile:c.recoveryCatalog.profileId}:{})});};
 let report;
 report=await inventory();
 report.inventorySha256=inventoryDigest(report);
 report.foreignKeyDependencies=foreignKeyDependencies(report);
 if(report.restoreCatalog){report.restoreCatalogSha256=hash(canonical(report.restoreCatalog));report.fullNativeForeignKeys=deriveCompleteNativeForeignKeys(report);}
 await writeFile(resolve(output),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
 assertInventory(report,c.targetId,c.schemas,c.accounts,c.deployment);
 console.log(JSON.stringify({passed:true,output:resolve(output)}));
}catch(e){console.error(JSON.stringify({passed:false,code:/^[A-Z0-9_]+(?::[A-Za-z0-9]*:-?[0-9]+)?$/.test(e.message)?e.message:'INPUT_OR_ADAPTER_UNAVAILABLE_OR_INVALID'}));process.exitCode=1;}
