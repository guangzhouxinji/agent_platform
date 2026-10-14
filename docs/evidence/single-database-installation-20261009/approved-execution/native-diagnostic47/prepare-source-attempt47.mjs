import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';import {fileURLToPath} from 'node:url';
import {hash,canonical,inventoryDigest} from '../../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
import {validateNativeConfiguration,validateCurrentNativeAdmission} from '../../../../../scripts/lib/single-database-lifecycle-native.mjs';
const here=fileURLToPath(new URL('./',import.meta.url)),prior=resolve(here,'../mainline-execution46');
const read=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const save=async(name,obj)=>writeFile(resolve(here,name),JSON.stringify(obj,null,2)+'\n',{flag:'wx'});
const c=await read(resolve(prior,'source-snapshot-config46-02.json')),r=await read(resolve(here,'current-resource-writer-discovery47.json'));
const before=await read(resolve(prior,'post-source02-full-inventory46.json')),after=await read(resolve(here,'current-full-inventory47.json'));
if(inventoryDigest(before)!==inventoryDigest(after)||after.accounts.length!==15||after.schemas.length!==6||r.actualDomainWriters.length!==3)throw Error('CURRENT_SOURCE_CLOSURE_DRIFT');
const old=await read(resolve(prior,'source-protection-execution46-02-result.json'));
if(old.snapshotExitCode!==1||!old.fenceReleaseConfirmed||old.returnFailure||old.restoreStarted)throw Error('PRIOR_ATTEMPT_RETURN_UNCONFIRMED');
const observed=await read(resolve(here,'readonly-fence-diagnostic47.json'));if(!observed.passed||observed.nativeExportAttempted)throw Error('READONLY_DIAGNOSTIC_REQUIRED');
const retained=r.records.filter(v=>v.state.Running&&!r.actualDomainWriters.some(w=>w.id===v.id)&&v.id!==c.targetId);
if(retained.length!==3||retained.some(v=>!v.readonlyRootfs||v.mounts.some(m=>m.RW)||v.environmentNames.some(k=>/PASSWORD|TOKEN|SECRET|JDBC|SYSDBA|DB_/.test(k))))throw Error('COMPLETE_NONWRITER_SCOPE_REQUIRED');
for(const d of ['consultation','enforcement','adjudication']){const lines=(await readFile(resolve(here,d+'-current-files47.log'),'utf8')).trim().split(/\r?\n/);if(lines.length!==1||!lines[0].startsWith('/product/files/'+d+' '))throw Error('CURRENT_FILE_CLOSURE_CHANGED');}
const statement=await read(resolve(prior,'source-maintenance-responsibility46.json'));
statement.capturedAt=new Date().toISOString();statement.owner='Codex replacement main executor, 2026-10-10, original delegated technical scope';statement.currentFullInventoryEvidence='current-full-inventory47.json';statement.currentProcessEvidence=['takeover47.json','current-maintenance-processes47.log'];statement.filesEvidence=['consultation','enforcement','adjudication'].map(d=>d+'-current-files47.log');statement.priorFailureStage='UNKNOWN';statement.reconciledBeforeNewAttempt='Original host invocation terminated; exact source only dmserver, tool only sleep, no prior lock/package/native file; this does not prove prior export never began. No retry of any prior invocation; one separately recorded attempt with pinned diagnostics only.';
// Reuse the same exact per-source maintenance lock, never make a parallel lock namespace.
const maintenance=resolve(here,'source-maintenance-responsibility47.json'),registry=resolve(here,'source-writer-registry47.json');
await save('source-maintenance-responsibility47.json',statement);
const writers=c.approval.writers.map(w=>({...w,owner:statement.owner,responsibilitySource:maintenance}));
await save('source-writer-registry47.json',{format:'EXACT_SOURCE_WRITER_RESPONSIBILITY_REGISTRY/v1',targetId:c.targetId,writers,statementEvidence:maintenance,actualResourceDiscovery:resolve(here,'current-resource-writer-discovery47.json'),completeRegisteredAccessSurface:true,allWritersStopped:false});
c.packageOutput=resolve(here,'single-final-source-protection47.xjsnap');c.approval.writers=writers;c.approval.writerRegistryEvidence=registry;c.approval.maintenanceExclusivityEvidence=maintenance;c.approval.adapterSha256=hash(await readFile(c.adapterPath));c.approval.nativeConfigurationSha256=hash(canonical(c.native));
validateNativeConfiguration(c);validateCurrentNativeAdmission(c);
await save('source-snapshot-config47.json',c);
await save('source-attempt-readiness47.json',{format:'SOURCE_ATTEMPT_RECONCILIATION/v1',capturedAt:new Date().toISOString(),priorFailureStage:'UNKNOWN',wholeInventoryEqual:true,inventorySha256:inventoryDigest(after),accounts:after.accounts.length,schemas:after.schemas.length,adapterSha256:c.approval.adapterSha256,maintenanceLockReused:true,originalFailurePreserved:true,newAttemptPermittedByExistingScope:true,allWritersStopped:false,nativeAttempted:false,restoreStarted:false});
console.log(JSON.stringify({passed:true,pureAssembly:true,nativeAttempted:false}));
