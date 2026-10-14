import {canonical,hash} from './single-database-lifecycle-runtime.mjs';import {RESTORE_CATALOG_PROFILE,RESTORE_CATALOG_PROFILE_SHA256,assertRestoreCatalog} from './single-database-restore-catalog.mjs';import {loadRecoveryOrigin} from './single-database-recovery-origin.mjs';import {compareRestoreInventories} from './single-database-restore-comparison.mjs';import {isAbsolute,resolve} from 'node:path';
const fail=code=>{throw new Error(code);};
export function validateRestoreRecoveryProfile(c){
 const p=c.recoveryCatalog;
 if(!p||p.profileId!==RESTORE_CATALOG_PROFILE.profileId||p.profileSha256!==RESTORE_CATALOG_PROFILE_SHA256||!Array.isArray(p.originFiles)||p.originFiles.length!==2||new Set(p.originFiles.map(f=>f.kind)).size!==2||p.originFiles.some(f=>!['CONTROLLED_SOURCE_ORIGIN_PROOF','CONTROLLED_MIGRATION_HISTORY'].includes(f.kind)||!/^[a-f0-9]{64}$/.test(f.sha256||'')))fail('EXACT_RECOVERY_CATALOG_CONFIGURATION_REQUIRED');
 if(c.approval.recoveryCatalogConfigurationSha256!==hash(canonical(p))||!isAbsolute(c.recoveryComparisonDirectory||'')||resolve(c.recoveryComparisonDirectory)!==c.recoveryComparisonDirectory||c.approval.recoveryComparisonDirectory!==c.recoveryComparisonDirectory)fail('APPROVED_RECOVERY_COMPARISON_CONFIGURATION_REQUIRED');
}
export async function assertRestoreProfileSource(opened,c){
 validateRestoreRecoveryProfile(c);const m=opened.metadata.recoveryCatalog;
 if(!m||!opened.content.recoveryOrigin||!opened.content.inventory.restoreCatalog||m.profileId!==c.recoveryCatalog.profileId||m.profileSha256!==c.recoveryCatalog.profileSha256)fail('EXACT_AUTHENTICATED_RESTORE_PROFILE_REQUIRED');
 const current=await loadRecoveryOrigin(c.recoveryCatalog);
 if(canonical(current)!==canonical(opened.content.recoveryOrigin))fail('RESTORE_AUTHENTICATED_ORIGIN_CONFIGURATION_MISMATCH');
 const s=opened.content.inventory;compareRestoreInventories({source:s,destination:s,origin:opened.content.recoveryOrigin,sourceTargetId:c.sourceTargetId,targetId:c.sourceTargetId,schemas:c.schemas,accounts:c.accounts,deployment:c.deployment});
}
export function assertRestoreProfileTarget(source,target){
 assertRestoreCatalog(target.restoreCatalog);assertRestoreCatalog(source.restoreCatalog);
 const view=v=>canonical([...v.categories.viewDefinitions.columns].sort((a,b)=>a.label.localeCompare(b.label)))+canonical([...v.categories.viewDefinitions.rows].sort((a,b)=>canonical(a).localeCompare(canonical(b))));
 if(view(source.restoreCatalog)!==view(target.restoreCatalog))fail('RESTORE_PREIMPORT_VIEW_OR_VERSION_DRIFT');
}
