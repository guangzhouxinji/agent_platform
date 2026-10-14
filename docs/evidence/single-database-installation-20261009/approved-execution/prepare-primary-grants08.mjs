import {readFile,writeFile,access} from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {canonical,hash,jdbcRequest,assertInventory,inventoryDigest} from '../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
import {assertLiveOwnedTarget} from '../../../../scripts/lib/single-database-lifecycle-target.mjs';
import {compileInstallation} from '../../../../scripts/lib/single-database-installation.mjs';

// This validates completion, never converts an observed START into a recovery cursor.
export function assertCompletedMigration(c,receipt,events) {
 if(receipt?.format!=='SINGLE_DATABASE_EXECUTION_RECEIPT/v1'||receipt.targetId!==c.targetId||receipt.mode!=='empty'||receipt.actualExecution!==true||receipt.applied+receipt.skipped!==c.manifest.migrations.length)throw Error('ACTUAL_COMPLETE_MIGRATION_RECEIPT_REQUIRED');
 let previous='';const done=new Set(),pending=new Set();
 const expected=new Map(c.manifest.migrations.map(m=>[`${m.schema}:${m.id}`,m.sha256]));
 for(const event of events){
  const {eventSha256,...body}=event;
  if(body.previousSha256!==previous||hash(canonical(body))!==eventSha256)throw Error('JOURNAL_CHAIN_INVALID');
  if(event.targetId!==c.targetId||expected.get(event.key)!==event.scriptSha256)throw Error('JOURNAL_MANIFEST_BINDING_REQUIRED');
  if(event.kind==='START'){
   if(pending.has(event.key)||done.has(event.key))throw Error('DUPLICATE_OR_REPLAYED_MIGRATION_EVENT');
   pending.add(event.key);
  }else if(event.kind==='SUCCESS'){
   if(!pending.delete(event.key)||done.has(event.key))throw Error('UNMATCHED_MIGRATION_SUCCESS');
   done.add(event.key);
  }else throw Error('FAILED_OR_UNKNOWN_MIGRATION_EVENT_REQUIRES_RECONCILIATION');
  previous=eventSha256;
 }
 if(pending.size||done.size!==expected.size||[...expected.keys()].some(k=>!done.has(k))||receipt.journalHead!==previous)throw Error('MIGRATION_INCOMPLETE_OR_AMBIGUOUS');
}
const exists=async p=>{try{await access(p);return true;}catch(e){if(e.code==='ENOENT')return false;throw e;}};
export async function prepare() {
 const root=process.cwd(),out=path.resolve('docs/evidence/single-database-installation-20261009/approved-execution');
 const read=async n=>JSON.parse(await readFile(path.join(out,n),'utf8'));
 const save=async(n,x)=>writeFile(path.join(out,n),JSON.stringify(x,null,2)+'\n',{flag:'wx'});
 const c=await read('primary-migration-config07.json');
 const protectedMigration={...c};delete protectedMigration.approval;
 if(c.targetId!=='f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f'||c.approval?.action!=='execute'||c.approval.targetId!==c.targetId||c.approval.configurationSha256!==hash(canonical(protectedMigration))||c.approval.manifestSha256!==hash(canonical(c.manifest)))throw Error('PINNED_ORIGINAL_MIGRATION_CONFIGURATION_REQUIRED');
 const decisionBytes=await readFile(c.currentDecisionPath);const decision={...c.approval};delete decision.currentDecisionEvidenceSha256;
 if(hash(decisionBytes)!==c.approval.currentDecisionEvidenceSha256||canonical(JSON.parse(decisionBytes))!==canonical(decision))throw Error('CURRENT_ORIGINAL_MIGRATION_DECISION_REQUIRED');
 // Reject before credentials, JDBC, new outputs or DCL while the old process owns either lock.
 if(await exists(c.maintenanceLock)||await exists(path.join(c.journalDir,'execute.lock')))throw Error('ACTIVE_OR_UNRECONCILED_MIGRATION_LOCK');
 const original=await readFile(path.join(out,'resource-approval01.json'));
 const requestBytes=await readFile(path.join(out,'resource-request-original01.json'));
 if(hash(original)!=='649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7'||hash(requestBytes)!=='8c6c5b46f977732b4dcb1825b54620ae4edac1b700004a3c2f967a70738d3a17')throw Error('ORIGINAL_APPROVAL_BINDING_REQUIRED');
 const approval=JSON.parse(original),request=JSON.parse(requestBytes);
 if(!approval.approvalGranted||Date.now()<Date.parse(request.operationWindowBeijing.from)||Date.now()>=Date.parse(request.operationWindowBeijing.until))throw Error('APPROVAL_WINDOW_REQUIRED');
 const receipt=await read('primary-migration-receipt07.json');
 const events=(await readFile(path.join(c.journalDir,'events.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);
 assertCompletedMigration(c,receipt,events);
 await assertLiveOwnedTarget(c);
 const inventory=await jdbcRequest(c.inventoryJdbc,{action:'inventory',targetId:c.targetId,schemas:c.schemas,accounts:c.accounts});
 assertInventory(inventory,c.targetId,c.schemas,c.accounts,c.deployment);
 if(inventoryDigest(inventory)!==receipt.inventorySha256)throw Error('POST_MIGRATION_INVENTORY_DRIFT');
 const compiled=await compileInstallation({workspaceRoot:root,deployment:c.deployment,stage:'grants',mode:'empty',inventory});
 const fence=path.resolve('scripts/lib/single-database-object-grants-fence.mjs');
 const coordinator=path.resolve('scripts/lib/single-database-object-grants.mjs');
 const writerEvidence=path.join(out,'primary-grant-writer-responsibility08.json');
 const decisionPath=path.join(out,'primary-grant-current-decision08.json');
 await save('primary-post-migration-inventory08.json',inventory);
 await save('primary-grant-installation08.json',compiled);
 await save(path.basename(writerEvidence),{format:'SINGLE_DATABASE_EXACT_WRITER_RESPONSIBILITY/v1',owner:'current-main-session',sourceApproval:path.join(out,'resource-approval01.json'),targetId:c.targetId,applicationsNeverStarted:true,externalOrWorkerWritersStarted:false,completeRegisteredMaintenanceEntrypoint:'grant-single-database-objects.mjs',maintenanceLock:c.maintenanceLock,oldResourcesExcluded:true,unregisteredAdministrativeWritesForbidden:true,migrationReceipt:path.join(out,'primary-migration-receipt07.json')});
 const config={...c,manifest:compiled.manifest,executionFenceAdapter:fence,journalDir:path.join(out,'primary-grant-journal08'),currentDecisionPath:decisionPath};delete config.approval;
 const action={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',action:'grant-object',targetId:c.targetId,owner:'current-main-session',source:path.join(out,'resource-approval01.json'),approvedAt:new Date().toISOString(),expiresAt:request.operationWindowBeijing.until,currentMigration:false,connectionSwitch:false,deleteExisting:false,newEnvironmentOnly:true,applicationsNeverStarted:true,writerRegistryComplete:true,writerRegistryEvidence:writerEvidence,maintenanceExclusivityEvidence:writerEvidence,writers:[{id:c.maintenanceLock,kind:'MAINTENANCE',owner:'current-main-session',responsibilitySource:writerEvidence}],manifestSha256:compiled.manifestSha256,configurationSha256:hash(canonical(config)),executionFenceAdapterSha256:hash(await readFile(fence)),coordinatorSha256:hash(await readFile(coordinator)),jdbcSourceSha256:hash(await readFile(c.jdbcSourcePath)),jdbcClassSha256:hash(await readFile(c.jdbcClassPath))};
 await save(path.basename(decisionPath),action);
 config.approval={...action,currentDecisionEvidenceSha256:hash(await readFile(decisionPath))};
 await save('primary-grant-config08.json',config);
 console.log(JSON.stringify({prepared:true,targetId:c.targetId,steps:compiled.manifest.steps.length,actualDclExecuted:false}));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href)await prepare();
