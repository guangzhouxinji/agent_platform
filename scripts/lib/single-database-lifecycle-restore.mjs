import {readFile,open,realpath,lstat,mkdir} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {hash,canonical,assertInventory,inventoryDigest,admission} from './single-database-lifecycle-runtime.mjs';
import {deploymentAccounts} from './single-database-lifecycle.mjs';
import {openSnapshot} from './single-database-lifecycle-snapshot.mjs';
import {validateRestoreRecoveryProfile,assertRestoreProfileSource,assertRestoreProfileTarget} from './single-database-restore-profile.mjs';
import {compareRestoreInventories} from './single-database-restore-comparison.mjs';
import {validateHumanWaitingRestore,assertHumanWaitingRestorePackage,humanWaitingRestoreReceipt} from './single-database-human-waiting-restore.mjs';
const fail=code=>{throw new Error(code);};
const ID=/^[a-f0-9]{64}$/,ENV=/^[A-Z][A-Z0-9_]{1,79}$/,IDENT=/^[A-Z][A-Z0-9_]{0,63}$/;
export function validateRestoreConfiguration(c) {
 validateHumanWaitingRestore(c);
 if(Object.hasOwn(c,'recoveryCatalog'))validateRestoreRecoveryProfile(c);
 const a=c.approval,n=c.nativeRestore;
 admission(a,c.targetId,'restore');
 if(!ID.test(c.targetId||'')||!ID.test(c.sourceTargetId||'')||c.sourceTargetId===c.targetId||a.sourceTargetId!==c.sourceTargetId)fail('INDEPENDENT_EXACT_RESTORE_TARGET_REQUIRED');
 if(a.configurationSha256!==hash(canonical(c.deployment))||a.restoreConfigurationSha256!==hash(canonical(n)))fail('APPROVED_RESTORE_CONFIGURATION_REQUIRED');
 if(!Array.isArray(c.schemas)||c.schemas.length!==6||new Set(c.schemas).size!==6||c.schemas.some(s=>!IDENT.test(s))||!Array.isArray(c.accounts)||![12,15].includes(c.accounts.length)||new Set(c.accounts).size!==c.accounts.length||c.accounts.some(s=>!IDENT.test(s)))fail('EXACT_RESTORE_SCOPE_REQUIRED');
 if(c.accounts.length===15||c.deployment?.schema==='SINGLE_DM8_DEPLOYMENT/v2') {
  if(c.deployment?.database?.containerId!==c.targetId)fail('EXACT_RESTORE_SCOPE_REQUIRED');
  if(canonical([...deploymentAccounts(c.deployment)].sort())!==canonical([...c.accounts].sort())||canonical(c.deployment.domains.flatMap(d=>[d.business.schema,d.authority.schema]).sort())!==canonical([...c.schemas].sort()))fail('EXACT_RESTORE_SCOPE_REQUIRED');
 }
 if(!ID.test(c.packageSha256||'')||a.packageSha256!==c.packageSha256||!c.packagePath||!ENV.test(c.keyEnv||'')||!c.keyReference||c.keyReference!==a.keyReference)fail('EXACT_PACKAGE_AND_MANAGED_KEY_REQUIRED');
 if(!n||!IDENT.test(n.user||'')||!ENV.test(n.passwordEnv||'')||!ENV.test(n.keyEnv||'')||n.passwordEnv===n.keyEnv||!n.keyReference||n.keyReference!==a.nativeExportKeyReference)fail('MANAGED_NATIVE_RESTORE_SECRETS_REQUIRED');
 if(!Number.isSafeInteger(n.timeoutSeconds)||n.timeoutSeconds<5||n.timeoutSeconds>120)fail('FINITE_RESTORE_TIMEOUT_REQUIRED');
 if(!n.maintenanceLock||resolve(n.maintenanceLock)!==n.maintenanceLock||!n.maintenanceLock.endsWith('single-database-maintenance-'+c.targetId+'.lock')||!n.attemptMarker||resolve(n.attemptMarker)!==n.attemptMarker||!n.attemptMarker.endsWith('single-database-restore-'+c.targetId+'.json'))fail('EXACT_RESTORE_LOCK_AND_ATTEMPT_REQUIRED');
 if(!a.writerRegistryComplete||!a.writerRegistryEvidence||!a.maintenanceExclusivityEvidence||!Array.isArray(a.writers)||!a.writers.length||a.writers.some(w=>!w.id||!w.owner||!w.responsibilitySource||!['CONTAINER','MAINTENANCE'].includes(w.kind)))fail('COMPLETE_RESTORE_WRITER_RESPONSIBILITY_REQUIRED');
 const ids=c.deployment?.domains?.flatMap(d=>d.writerContainerIds)||[];
 if(!ids.length||ids.some(id=>!ID.test(id))||new Set(ids).size!==ids.length||canonical([...ids].sort())!==canonical(a.writers.filter(w=>w.kind==='CONTAINER').map(w=>w.id).sort())||!a.writers.some(w=>w.kind==='MAINTENANCE'&&w.id===n.maintenanceLock&&w.responsibilitySource===a.maintenanceExclusivityEvidence))fail('EXACT_REGISTERED_RESTORE_WRITERS_REQUIRED');
 if(!ID.test(a.emptyInventorySha256||''))fail('APPROVED_EMPTY_TARGET_INVENTORY_REQUIRED');
 if(!Array.isArray(c.files)||!Array.isArray(a.files)||canonical(c.files)!==canonical(a.files)||new Set(c.files.map(f=>f.originalKey)).size!==c.files.length||new Set(c.files.map(f=>f.relativePath)).size!==c.files.length)fail('EXACT_RESTORE_FILE_MAPPING_REQUIRED');
 if(!c.fileRoot||resolve(c.fileRoot)!==c.fileRoot||c.files.some(f=>!ID.test(f.sha256||'')||!f.originalKey||!f.licenseReference||!/^([A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+$/.test(f.relativePath||'')||f.relativePath.split('/').some(v=>v==='.'||v==='..')))fail('NEW_INDEPENDENT_FILE_MAPPING_REQUIRED');
 // Resolve deterministic file-tree conflicts before fence, package open or native import.
 // Compare path segments: "archive" is not an ancestor of "archive2".
 const windows=process.platform==='win32';
 if(windows&&c.files.some(f=>f.relativePath.split('/').some(part=>part.endsWith('.')||/^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$/i.test(part))))fail('NEW_INDEPENDENT_FILE_MAPPING_REQUIRED');
 const destinations=c.files.map(f=>windows?f.relativePath.toLowerCase():f.relativePath),planned=new Set(destinations);
 if(planned.size!==destinations.length)fail('EXACT_RESTORE_FILE_MAPPING_REQUIRED');
 for(const destination of destinations){const parts=destination.split('/');
  for(let length=1;length<parts.length;length++)if(planned.has(parts.slice(0,length).join('/')))fail('EXACT_RESTORE_FILE_MAPPING_REQUIRED');
 }
 assertRestoreCurrent(c);return n;
}
export function assertRestoreCurrent(c,metadata) {
 validateHumanWaitingRestore(c);
 admission(c.approval,c.targetId,'restore');const a=c.approval,r=a.databaseRights;
 if(!r?.owner||!r.licenseReference||!r.purpose||!(Date.parse(r.useUntil)>Date.now())||!Array.isArray(r.schemas)||canonical([...r.schemas].sort())!==canonical([...c.schemas].sort()))fail('CURRENT_RESTORE_DATABASE_RIGHTS_REQUIRED');
 if(!Array.isArray(a.files)||a.files.some(f=>!f.licenseReference||!(Date.parse(f.useUntil)>Date.now())))fail('CURRENT_RESTORE_FILE_RIGHTS_REQUIRED');
 if(metadata&&(!(Date.parse(metadata.expiresAt)>Date.now())||!(Date.parse(metadata.databaseRights?.useUntil)>Date.now())||metadata.fileDigests.some(f=>!a.files.some(v=>v.originalKey===f.originalKey&&v.sha256===f.sha256))))fail('CURRENT_SOURCE_PACKAGE_RIGHTS_REQUIRED');
}
export function assertEmptyRestoreInventory(i,c) {
 assertInventory(i,c.targetId,c.schemas,c.accounts,c.deployment);
 if(inventoryDigest(i)!==c.approval.emptyInventorySha256)fail('EMPTY_TARGET_INVENTORY_DRIFT');
 if(i.schemas.some(s=>['objects','columns','constraints','foreignKeys','indexes','sequences','views','triggers','dependencies','rows'].some(k=>s[k].length)))fail('RESTORE_TARGET_NOT_EMPTY');
}
export function verifyRestoredInventory(source,destination,c) {
 assertInventory(source,c.sourceTargetId,c.schemas,c.accounts,c.deployment);assertInventory(destination,c.targetId,c.schemas,c.accounts,c.deployment);
 if(inventoryDigest({...source,targetId:c.targetId})!==inventoryDigest(destination))fail('RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH');
}
// Orchestration ports are production adapters in the CLI; unit ports are not DM8 evidence.
export async function restoreSnapshot({configuration:c,key,inventory,acquireFence,importDatabase,restoreFiles,checkFiles,journal,writeComparisonEvidence,readInventoryBytes}) {
 try{validateRestoreConfiguration(c);
 if(c.recoveryCatalog&&[writeComparisonEvidence,readInventoryBytes].some(p=>typeof p!=='function'))fail('RESTORE_PROFILE_RAW_EVIDENCE_PORTS_REQUIRED');
 if(!Buffer.isBuffer(key)||key.length!==32||![inventory,acquireFence,importDatabase,restoreFiles,checkFiles,journal].every(p=>typeof p==='function'))fail('ACTUAL_RESTORE_PORTS_AND_KEY_REQUIRED');
 }catch(e){if(Buffer.isBuffer(key))key.fill(0);throw e;}
 let lease,opened,failure,started=false,comparisonEvidence,postInventoryAttempted=false;
 const profileDecision=()=>hash(canonical({recoveryCatalog:c.recoveryCatalog??null,recoveryComparisonDirectory:c.recoveryComparisonDirectory??null,humanWaiting:c.humanWaiting??null,humanWaitingApproval:c.approval.humanWaitingRestoreConfigurationSha256??null,humanDestination:c.humanWaiting?{fileRoot:c.fileRoot,files:c.files,approvedFiles:c.approval.files}:null}));const enteredProfileDecision=profileDecision();
 const assertDecision=()=>{if(profileDecision()!==enteredProfileDecision)fail('RESTORE_RECOVERY_PROFILE_DECISION_CHANGED');assertRestoreCurrent(c,opened?.metadata);if(opened)assertHumanWaitingRestorePackage(c,opened);};
 const current=async()=>{assertDecision();await lease.assertHeld();assertDecision();};
 try {
  lease=await acquireFence(c);if(!lease?.assertHeld||!lease.release)fail('RESTORE_FENCE_REQUIRED');await current();
  await journal({kind:'ADMITTED',targetId:c.targetId,sourceTargetId:c.sourceTargetId,packageSha256:c.packageSha256});await current();
  if(await realpath(c.packagePath)!==resolve(c.packagePath)||(await lstat(c.packagePath)).isSymbolicLink()||!(await lstat(c.packagePath)).isFile())fail('RESTORE_PACKAGE_REDIRECT');await current();
  opened=await openSnapshot({file:c.packagePath,key,expectedTargetId:c.sourceTargetId,expectedSha256:c.packageSha256});await current();
  const m=opened.metadata;
  assertHumanWaitingRestorePackage(c,opened);
  const hasProfile=Object.hasOwn(m,'recoveryCatalog')||Object.hasOwn(opened.content,'recoveryOrigin')||Object.hasOwn(opened.content.inventory,'restoreCatalog');
  if(hasProfile&&!c.recoveryCatalog)fail('EXACT_RECOVERY_CATALOG_CONFIGURATION_REQUIRED');
  if(c.recoveryCatalog){await assertRestoreProfileSource(opened,c);await current();}
  if(m.exportMethod!=='DM8_NATIVE_EXPORT'||m.nativeExportKeyReference!==c.nativeRestore.keyReference||m.currentAuthorityOutsideBackup!==true)fail('EXACT_NATIVE_PACKAGE_AND_AUTHORITY_BOUNDARY_REQUIRED');
  assertInventory(opened.content.inventory,c.sourceTargetId,c.schemas,c.accounts,c.deployment);
  if(c.files.length!==opened.content.files.length||c.files.length!==m.fileDigests.length||opened.content.files.some(f=>!c.files.some(v=>v.originalKey===f.originalKey&&v.sha256===f.sha256&&v.licenseReference===f.licenseReference)))fail('EXACT_AUTHENTICATED_FILE_SET_REQUIRED');
  const before=await inventory();await current();assertEmptyRestoreInventory(before,c);
  if(c.recoveryCatalog)assertRestoreProfileTarget(opened.content.inventory,before);
  // A durable START precedes the first external import. Unknown/partial is never retried here.
  await journal({kind:'IMPORT_STARTED',targetId:c.targetId,nativeSha256:m.nativeSha256});await current();started=true;
  const imported=await importDatabase(c,opened.native,opened.metadata);await current();
  await journal({kind:'IMPORT_RETURNED',targetId:c.targetId,...(imported?.logSha256?{logSha256:imported.logSha256}:{}),nativeToolResultVerified:true});await current();
  await restoreFiles(c,opened.content.files,()=>current());await current();
  postInventoryAttempted=true;const after=await inventory();await current();
  if(c.recoveryCatalog){
   const destinationBytes=await readInventoryBytes(after),sourceBytes=Buffer.from(JSON.stringify(opened.content.inventory)+'\n');
   if(!Buffer.isBuffer(destinationBytes)||canonical(JSON.parse(destinationBytes.toString('utf8')))!==canonical(after))fail('RESTORE_TARGET_RAW_EVIDENCE_BINDING_MISMATCH');
   comparisonEvidence=await writeComparisonEvidence({sourceBytes,destinationBytes,origin:opened.content.recoveryOrigin});
   if(!comparisonEvidence?.complete||!comparisonEvidence.passed)fail(comparisonEvidence?.code&&/^[A-Z][A-Z0-9_]{0,79}$/.test(comparisonEvidence.code)?comparisonEvidence.code:'RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH');
   const compared=compareRestoreInventories({source:opened.content.inventory,destination:after,origin:opened.content.recoveryOrigin,sourceTargetId:c.sourceTargetId,targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,deployment:c.deployment});
   if(!compared.passed)fail('RESTORED_DATA_OBJECT_OR_PRIVILEGE_MISMATCH');
  }else verifyRestoredInventory(opened.content.inventory,after,c);
  await checkFiles(c);await current();
  const human=humanWaitingRestoreReceipt(c,opened,after);
  await journal({kind:'RESTORE_CHECKS_PASSED',targetId:c.targetId,inventorySha256:inventoryDigest(after),...(comparisonEvidence?{comparisonReportSha256:comparisonEvidence.reportSha256,comparisonDirectory:comparisonEvidence.directory}:{}),...(human?{humanWaiting:human}:{}),businessActivation:false});await current();
  return {format:'SINGLE_DATABASE_NATIVE_RESTORE_RECEIPT/v1',targetId:c.targetId,sourceTargetId:c.sourceTargetId,packageSha256:c.packageSha256,inventorySha256:inventoryDigest(after),fileDigests:c.files.map(({originalKey,relativePath,sha256})=>({originalKey,relativePath,sha256})),nativeImportCompleted:true,...(comparisonEvidence?{recoveryProfileId:c.recoveryCatalog.profileId,comparisonReportSha256:comparisonEvidence.reportSha256,comparisonDirectory:comparisonEvidence.directory}:{}),...(human?{humanWaiting:human}:{}),businessActivation:false,fullThreeDomainProductRecoveryProven:false,verifiedAt:new Date().toISOString()};
 }catch(e){failure=e;
 if(c.recoveryCatalog&&opened&&!comparisonEvidence){try{comparisonEvidence=await writeComparisonEvidence({sourceBytes:Buffer.from(JSON.stringify(opened.content.inventory)+'\n'),destinationBytes:await readInventoryBytes(null),origin:opened.content.recoveryOrigin,...(!postInventoryAttempted?{forceFailureCode:'RESTORE_OPERATION_FAILED_BEFORE_POST_CAPTURE'}:{})});}catch{e.comparisonEvidenceUnavailable=true;}}
 if(lease){try{await journal({kind:'FAILURE',importStarted:started,partialScenePreserved:true,rollbackClaimed:false,...(comparisonEvidence?{comparisonDirectory:comparisonEvidence.directory,comparisonReportSha256:comparisonEvidence.reportSha256,comparisonPassed:comparisonEvidence.passed}:{}),...(e.comparisonEvidenceUnavailable?{comparisonEvidenceUnavailable:true}:{}),code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'RESTORE_OPERATION_FAILED'});}catch{e.failureJournalUnavailable=true;}}throw e;
 }finally{opened?.native.fill(0);if(opened?.content){opened.content.native='';for(const f of opened.content.files)f.bytes='';}key.fill(0);try{await lease?.release?.();}catch(e){if(failure){failure.fenceReleaseUnavailable=true;failure.fenceReleaseCause=e;}else throw new Error('RESTORE_FENCE_RELEASE_FAILED_SCENE_PRESERVED',{cause:e});}}
}
// Files go only into a brand-new approved directory; no original path is used as output.
export async function restoreOriginalFiles(c,files,current) {
 const root=resolve(c.fileRoot);if(await realpath(dirname(root))!==dirname(root))fail('RESTORE_FILE_PARENT_REDIRECT');
 await current();await mkdir(root,{recursive:false});await current();
 for(const f of files){const map=c.files.find(v=>v.originalKey===f.originalKey&&v.sha256===f.sha256);if(!map)fail('RESTORE_FILE_MAPPING_MISSING');const destination=resolve(root,map.relativePath);const parent=dirname(destination);await current();await mkdir(parent,{recursive:true});if(await realpath(parent)!==parent)fail('RESTORE_FILE_PARENT_REDIRECT');const bytes=Buffer.from(f.bytes,'base64');try{if(hash(bytes)!==map.sha256)fail('RESTORE_FILE_SHA_MISMATCH');const fd=await open(destination,'wx',0o600);try{await fd.writeFile(bytes);await fd.sync();}finally{await fd.close();}}finally{bytes.fill(0);}await current();}
}
export async function checkOriginalFiles(c) {
 for(const f of c.files){assertRestoreCurrent(c);const dest=resolve(c.fileRoot,f.relativePath);if(await realpath(dest)!==dest||!(await lstat(dest)).isFile())fail('RESTORED_FILE_REDIRECT');const bytes=await readFile(dest);try{if(hash(bytes)!==f.sha256)fail('RESTORED_FILE_SHA_MISMATCH');}finally{bytes.fill(0);}assertRestoreCurrent(c);}
}
