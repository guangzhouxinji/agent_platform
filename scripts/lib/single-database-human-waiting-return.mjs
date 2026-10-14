import {hash,canonical,assertInventory,inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {openSnapshot} from './single-database-lifecycle-snapshot.mjs';
import {validateHumanWaitingBinding,humanWaitingSemantic,assertHumanWaitingInventory} from './single-database-human-waiting-binding.mjs';
import {fail,returnConfiguration,returnCurrent,returnFile,returnOriginals,returnScene,returnHistoricalSemantic} from './single-database-human-waiting-return-helper.mjs';

/** Source preservation evidence only. The caller retains ownership of subsequent writer admission. */
export async function verifyHumanWaitingSourceReturn({configuration:c,key,inspect,inventory,acquireFence,assertScene,journal,publish}) {
  let lease,opened,before,after,published,failure,releaseAttempted=false;
  const secondary=[];
  let configurationSha256;
  const current=()=>{
    if(configurationSha256!==hash(canonical(c)))fail('RETURN_CONFIGURATION_CHANGED');
    returnCurrent(c,opened?.metadata,after||before);
  };
  const step=async operation=>{current();const result=await operation();current();return result;};
  const inputs=async()=>{
    const r=c.sourceReturn;
    returnScene(c,await step(()=>assertScene(c)));
    await step(()=>returnFile(r.packagePath,r.packageSha256,257*1024*1024,current));
    await step(()=>returnFile(r.operationEvidencePath,r.operationEvidenceSha256,16*1024*1024,current));
    if(opened)await step(()=>returnOriginals(c,opened.metadata,current));
  };
  const held=async()=>{await step(()=>lease.assertHeld());};
  const release=async()=>{
    releaseAttempted=true;let error;
    try{await lease.release();}catch(e){error=e;}
    try{await lease.waitClosed();}catch(e){if(error)secondary.push(e);else error=e;}
    if(error)throw error;
  };
  try {
    if(!Buffer.isBuffer(key)||key.length!==32||![inspect,inventory,acquireFence,assertScene,journal,publish].every(p=>typeof p==='function'))fail('ACTUAL_RETURN_PORTS_AND_KEY_REQUIRED');
    returnConfiguration(c);configurationSha256=hash(canonical(c));current();
    await inputs();
    current();lease=await acquireFence(c,c.approval.writers);current();
    if(!lease||!['assertHeld','release','waitClosed'].every(k=>typeof lease[k]==='function'))fail('RETURN_LIVE_FENCE_REQUIRED');
    await held();await step(()=>journal({kind:'RETURN_ADMITTED',sourceTargetId:c.targetId,configurationSha256,operationEvidenceSha256:c.sourceReturn.operationEvidenceSha256}));
    current();opened=await openSnapshot({file:c.sourceReturn.packagePath,key,expectedTargetId:c.targetId,expectedSha256:c.sourceReturn.packageSha256});current();
    const m=opened.metadata,h=opened.content.humanWaiting;
    if(!h||m.humanWaiting?.bindingSha256!==c.sourceReturn.bindingSha256||m.exportMethod!=='DM8_NATIVE_EXPORT'||m.currentAuthorityOutsideBackup!==true
        ||m.nativeExportKeyReference!==c.approval.nativeExportKeyReference)fail('RETURN_EXACT_HUMAN_PACKAGE_REQUIRED');
    assertInventory(opened.content.inventory,c.targetId,c.schemas,c.accounts,c.deployment);
    await held();before=structuredClone(await step(()=>inspect({lease,phase:'before'})));current();
    validateHumanWaitingBinding(before,c.targetId,c.files);
    if(before.configurationSha256!==c.approval.humanWaitingConfigurationSha256||returnHistoricalSemantic(before)!==returnHistoricalSemantic(h.before))fail('RETURN_SOURCE_BINDING_CHANGED');
    const oldWorker=h.before.workerProof.worker,newWorker=before.workerProof.worker;
    if(oldWorker.pid===newWorker.pid&&oldWorker.createdAt===newWorker.createdAt)fail('RETURN_FRESH_WORKER_REQUIRED');
    const live=assertInventory(await step(()=>inventory()),c.targetId,c.schemas,c.accounts,c.deployment);
    assertHumanWaitingInventory(before,live);
    if(inventoryDigest(live)!==m.inventorySha256)fail('RETURN_SOURCE_INVENTORY_CHANGED');
    // inventoryDigest covers schemas/accounts only; these authenticated native facts remain exact.
    const original=opened.content.inventory;
    if(canonical(live.restoreCatalog??null)!==canonical(original.restoreCatalog??null)
        ||canonical(live.nativePrivileges??null)!==canonical(original.nativePrivileges??null))fail('RETURN_NATIVE_CATALOG_OR_PRIVILEGES_CHANGED');
    await inputs();await held();
    after=structuredClone(await step(()=>inspect({lease,phase:'after'})));current();
    validateHumanWaitingBinding(after,c.targetId,c.files);
    if(humanWaitingSemantic(before)!==humanWaitingSemantic(after))fail('RETURN_SOURCE_CHANGED_INSIDE_FENCE');
    await held();await inputs();
    await step(()=>journal({kind:'RETURN_CHECKS_PASSED',sourceTargetId:c.targetId,inventorySha256:m.inventorySha256,bindingSha256:c.sourceReturn.bindingSha256}));
    await held();await release();current();await inputs();
    const validUntil=[m.expiresAt,m.databaseRights.useUntil,c.approval.expiresAt,c.approval.databaseRights.useUntil,c.humanWaiting.backupUntil,
      before.executionUntil,after.executionUntil,...c.approval.files.map(f=>f.useUntil)].sort((a,b)=>Date.parse(a)-Date.parse(b))[0];
    const receipt={format:'HUMAN_WAITING_SOURCE_RETURN_RECEIPT/v1',verificationOnly:true,businessActivation:false,sourceTargetId:c.targetId,
      packageSha256:c.sourceReturn.packageSha256,bindingSha256:c.sourceReturn.bindingSha256,operationEvidenceSha256:c.sourceReturn.operationEvidenceSha256,
      configurationSha256,sourceConfigurationSha256:before.configurationSha256,inventorySha256:m.inventorySha256,fileSetSha256:hash(canonical(m.fileDigests)),
      restoreCatalogSha256:hash(canonical(original.restoreCatalog??null)),nativePrivilegesSha256:hash(canonical(original.nativePrivileges??null)),
      before,after,verifiedAt:new Date().toISOString(),validUntil};
    await step(()=>journal({kind:'RETURN_FENCE_CLOSED',sourceTargetId:c.targetId,receiptSha256:hash(canonical(receipt))}));
    await inputs();
    // The production publisher retains an open wx-created file and invalidates a failed final check.
    // A throwing publisher must invalidate its own partial write before rejecting.
    published=await publish(receipt,{assertCurrent:async()=>{current();await inputs();current();}});current();
    if(typeof published?.invalidate!=='function')fail('RETURN_INVALIDATABLE_PUBLICATION_REQUIRED');
    await inputs();current();
    return receipt;
  }catch(e){
    failure=e;
    if(published?.invalidate)try{await published.invalidate();}catch(invalidation){secondary.push(invalidation);}
    if(lease&&!releaseAttempted&&typeof lease.release==='function'&&typeof lease.waitClosed==='function')try{await release();}catch(exit){secondary.push(exit);}
    if(typeof journal==='function')try{await journal({kind:'RETURN_FAILURE',code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'RETURN_OPERATION_FAILED',
      sourceTargetId:c?.targetId??null,releaseAttempted,publicationInvalidationRequired:Boolean(published),secondaryCodes:secondary.map(x=>/^[A-Z0-9_]+$/.test(x.message)?x.message:'RETURN_EXIT_OR_INVALIDATION_FAILED')});}catch(log){secondary.push(log);}
    if(secondary.length)throw new AggregateError([failure,...secondary],'RETURN_FAILED_WITH_EXIT_OR_EVIDENCE_ERRORS');
    throw failure;
  }finally{if(Buffer.isBuffer(key))key.fill(0);opened?.native?.fill(0);}
}
