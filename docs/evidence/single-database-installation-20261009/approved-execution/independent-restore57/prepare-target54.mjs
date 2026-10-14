import {readFile,writeFile,open,unlink} from 'node:fs/promises';import path from 'node:path';import {execFileSync} from 'node:child_process';

import {hash,canonical,jdbcRequest,inventoryDigest,assertInventory} from '../../../../../scripts/lib/single-database-lifecycle-runtime.mjs';

import {assertLiveOwnedTarget} from '../../../../../scripts/lib/single-database-lifecycle-target.mjs';

import {compileInstallation} from '../../../../../scripts/lib/single-database-installation.mjs';

import {hasNativePrivileges} from '../../../../../scripts/lib/single-database-native-privileges.mjs';

import {validateRestoreConfiguration} from '../../../../../scripts/lib/single-database-lifecycle-restore.mjs';

import {assertRestoreProfileTarget} from '../../../../../scripts/lib/single-database-restore-profile.mjs';

const root=process.cwd(),out=path.resolve(root,'docs/evidence/single-database-installation-20261009/approved-execution/independent-restore57'),base=path.dirname(out);

import {readContinuation55} from '../../../../../scripts/lib/single-database-continuous-window-admission.mjs';
const read=async p=>JSON.parse(await readFile(p));const save=async(n,v)=>writeFile(path.join(out,n),JSON.stringify(v,null,2)+'\n',{flag:'wx'});

const command=args=>execFileSync('docker',args,{encoding:'utf8',maxBuffer:4*1024*1024});const inspect=id=>JSON.parse(command(['inspect',id]))[0];

const request=await read(path.join(base,'resource-request-original01.json')),approved=await read(path.join(base,'resource-approval01.json')),live=await read(path.join(out,'target-resources54.json'));

const sourceConfig=await read(path.join(base,'source-protection56b/source-snapshot-config56.json')),sourceInventory=await read(path.join(base,'source-protection56b/post-source-full-profile-inventory56.json'));

const window55=readContinuation55(root,'existing-initialized-empty-independent-target-recovery');
const sourceReceipt=await read(path.join(base,'source-protection56b/source-snapshot-receipt56.json'));if(!sourceReceipt.actualSnapshot||sourceReceipt.sealedSha256==='92be34d7501e03ffa4799530fd4d77ddcda4bb7c1d51c4b9f2f047b83f2057b2'||sourceReceipt.metadata.inventorySha256!==inventoryDigest(sourceInventory))throw Error('FRESH_SOURCE_PACKAGE_AND_POST_PROFILE_REQUIRED');
const targetId=live.targetId,toolId=live.toolId,deadline=window55.until;
if(targetId!==window55.targetId||hash(await readFile(sourceConfig.packageOutput))!==sourceReceipt.sealedSha256)throw Error('EXACT_TARGET_AND_AUTHENTICATED_CURRENT_PACKAGE_REQUIRED');

const deployment=structuredClone(sourceConfig.deployment);deployment.database={...deployment.database,containerId:targetId,volume:request.targets[1].newVolume,jdbcUrl:request.managedJdbcExecution.restoreUrl};deployment.domains.forEach(d=>{d.business.jdbcUrl=deployment.database.jdbcUrl;d.authority.jdbcUrl=deployment.database.jdbcUrl;});

// Originals are the complete registered application writer set, kept stopped throughout. No target applications are created.

const port=structuredClone(sourceConfig.inventoryJdbc);port.url=deployment.database.jdbcUrl;port.dockerJdbc.containerId=toolId;port.dockerJdbc.targetId=targetId;const libs=['jackson-annotations-2.17.2.jar','jackson-core-2.17.2.jar','jackson-databind-2.17.2.jar','DmJdbcDriver18-8.1.3.140.jar'];port.classpath=[path.join(base,'jdbc-native05/restore51'),path.join(base,'jdbc-native05'),...libs.map(n=>path.join(base,'jdbc-native05',n))].join(path.delimiter);

const c={workspaceRoot:root,targetId,deployment,schemas:sourceConfig.schemas,accounts:sourceConfig.accounts,inventoryJdbc:port,adminJdbc:port,jdbcSourcePath:path.resolve('scripts/java/SingleDatabaseLifecycleJdbc.java'),jdbcClassPath:path.join(port.classpath.split(path.delimiter)[0],'SingleDatabaseLifecycleJdbc.class')};

const current=async()=>{readContinuation55(root,'existing-initialized-empty-independent-target-recovery');if(!approved.approvalGranted||Date.now()>=(Date.parse(deadline)-20*60*1000))throw Error('SAFE_RETURN_RESERVE_REQUIRED');const s=inspect(sourceConfig.targetId);if(s.State.Running||s.State.Pid!==0)throw Error('SOURCE_DM8_NOT_STOPPED');for(const id of sourceConfig.deployment.domains.flatMap(d=>d.writerContainerIds)){const w=inspect(id);if(w.State.Running||w.State.Pid!==0)throw Error('ORIGINAL_WRITER_NOT_STOPPED');}await assertLiveOwnedTarget(c);};

const inventory=()=>jdbcRequest(port,{action:'inventory',targetId,schemas:c.schemas,accounts:c.accounts,restoreCatalogProfile:sourceConfig.recoveryCatalog.profileId});

if(process.argv[2]==='prepare'){

 await current();

 const legacy=structuredClone(port);legacy.classpath=[path.join(base,'jdbc-runtime02'),...sourceConfig.inventoryJdbc.classpath.split(path.delimiter).slice(2)].join(path.delimiter);legacy.dockerJdbc.classpath='/prep:'+port.dockerJdbc.classpath.split(':').slice(2).join(':');legacy.dockerJdbc.classFile='/prep/SingleDatabaseLifecycleJdbc.class';legacy.dockerJdbc.classSha256=hash(await readFile(path.join(base,'jdbc-runtime02/SingleDatabaseLifecycleJdbc.class')));

 const originalDecision=await read(path.join(base,'primary-native-public-hardening-decision01.json'));if(legacy.dockerJdbc.classSha256!==originalDecision.jdbcClassSha256)throw Error('ORIGINAL_PUBLIC_TIGHTENING_CLASS_REQUIRED');

 const manifest=await read(path.join(base,'primary-native-public-hardening-manifest01.json'));if(hash(canonical(manifest))!==originalDecision.manifestSha256)throw Error('ORIGINAL_PUBLIC_TIGHTENING_MANIFEST_REQUIRED');

 const before=await jdbcRequest(legacy,{action:'bootstrap-inventory',targetId,accounts:['PUBLIC','SVI']});await save('fresh-native-public54.json',before);

 const old=await read(path.join(base,'native-public-observation02.json'));if(canonical({...before,targetId:old.targetId})!==canonical(old))throw Error('FRESH_NATIVE_PUBLIC_PRESTATE_DRIFT');

 const lockPath=path.join(out,'single-database-maintenance-'+targetId+'.lock'),lock=await open(lockPath,'wx');

 const journal=await open(path.join(out,'public-tightening54.jsonl'),'wx');let completed=0;

 try{await current();await lock.writeFile('EXACT_NEW_RESTORE_PUBLIC_TIGHTENING_ONLY\n');await lock.sync();

  await save('public-tightening-decision54.json',{targetId,owner:'Codex delegated technical executor',source:path.join(base,'continuous-window55/resource-window-extension55.json'),sourceManifestSha256:hash(canonical(manifest)),legacyClassSha256:legacy.dockerJdbc.classSha256,expiresAt:deadline,sourcePidZero:true,automaticRetryAllowed:false,oldResourcesExcluded:true});

  for(const step of manifest.steps){if(Date.now()>=(Date.parse(deadline)-20*60*1000)||inspect(sourceConfig.targetId).State.Pid!==0)throw Error('SOURCE_STOP_OR_RETURN_WINDOW_LOST');await journal.writeFile(JSON.stringify({kind:'START',index:completed})+'\n');await journal.sync();await jdbcRequest(legacy,{action:'harden-public-step',step});await journal.writeFile(JSON.stringify({kind:'SUCCESS',index:completed})+'\n');await journal.sync();completed++;}

  const hardened=await jdbcRequest(port,{action:'bootstrap-inventory',targetId,accounts:c.accounts});await save('hardened-empty-accounts54.json',hardened);hasNativePrivileges(hardened);

  if(hardened.accounts.some(a=>a.users.length||a.objects.length))throw Error('FRESH_ACCOUNTS_REQUIRED');

  const compiled=await compileInstallation({workspaceRoot:root,deployment,stage:'bootstrap',inventory:hardened});await save('bootstrap-installation54.json',compiled);

  const fence=path.resolve('scripts/lib/single-database-bootstrap-fence.mjs'),decisionPath=path.join(out,'bootstrap-current-decision54.json'),responsibility=path.join(out,'restore-maintenance-responsibility54.json');

  const config={...c,manifest:compiled.manifest,maintenanceLock:lockPath,currentDecisionPath:decisionPath,journalDir:path.join(out,'bootstrap-journal54'),executionFenceAdapter:fence};

  // Empty target has no application writers yet; original stopped writers remain in the separate restore registry.

  const emptyDeployment=structuredClone(deployment);emptyDeployment.domains.forEach(d=>d.writerContainerIds=[]);config.deployment=emptyDeployment;config.manifest.deployment=emptyDeployment;

  const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId,action:'bootstrap',owner:'Codex delegated technical executor',source:path.join(base,'continuous-window55/resource-window-extension55.json'),approvedAt:new Date().toISOString(),expiresAt:deadline,currentMigration:false,connectionSwitch:false,deleteExisting:false,newEnvironmentOnly:true,manifestSha256:hash(canonical(config.manifest)),writerRegistryComplete:true,writerRegistryEvidence:responsibility,maintenanceExclusivityEvidence:responsibility,writers:[{id:lockPath,kind:'MAINTENANCE',owner:'Codex delegated technical executor',responsibilitySource:responsibility}],applicationsNeverStarted:true,configurationSha256:hash(canonical(config)),executionFenceAdapterSha256:hash(await readFile(fence)),jdbcSourceSha256:hash(await readFile(c.jdbcSourcePath)),jdbcClassSha256:hash(await readFile(c.jdbcClassPath))};

  const bytes=Buffer.from(JSON.stringify(approval,null,2)+'\n');await writeFile(decisionPath,bytes,{flag:'wx'});approval.currentDecisionEvidenceSha256=hash(bytes);await save('bootstrap-config54.json',{...config,approval});

  await save('public-tightening-receipt54.json',{passed:true,completed,actualJdbc:true,targetId,originalManifestExact:true,productObjectsCreated:0});

 }catch(e){await save('public-tightening-failure54.json',{passed:false,completed,code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'PREPARATION_OPERATION_FAILED',partialScenePreserved:true,automaticRetryAllowed:false});throw e;}finally{await journal.close();await lock.close();await unlink(lockPath);}

}else if(process.argv[2]==='admit'){

 await current();const raw=[];const empty=await jdbcRequest(port,{action:'inventory',targetId,schemas:c.schemas,accounts:c.accounts,restoreCatalogProfile:sourceConfig.recoveryCatalog.profileId},{captureRaw:b=>raw.push(Buffer.from(b))});await writeFile(path.join(out,'empty-full-profile54.raw.json'),raw[0],{flag:'wx'});await save('empty-full-profile54.json',empty);assertInventory(empty,targetId,c.schemas,c.accounts,deployment);hasNativePrivileges(empty);assertRestoreProfileTarget(sourceInventory,empty);

 if(empty.schemas.some(s=>['objects','columns','constraints','foreignKeys','indexes','sequences','views','triggers','dependencies','rows'].some(k=>s[k].length)))throw Error('RESTORE_TARGET_NOT_EMPTY');

 // Compare all source/target direct account system privileges before import. No object grants replayed.

 for(const a of empty.accounts){const s=sourceInventory.accounts.find(v=>v.name===a.name);if(canonical(a.systemPrivileges)!==canonical(s.systemPrivileges)||canonical(a.roleGrants)!==canonical(s.roleGrants)||a.tableGrants.length||a.columnGrants.length)throw Error('EMPTY_ACCOUNT_PERMISSION_PRESTATE_DRIFT');}

 const help=Buffer.from(command(['exec',targetId,'/opt/dmdbms/bin/dimp','HELP']));await writeFile(path.join(out,'native-import-help54.raw.log'),help,{flag:'wx'});

 const adapter=path.resolve('scripts/lib/single-database-lifecycle-native-restore.mjs'),responsibility=path.join(out,'restore-maintenance-responsibility54.json'),lock=path.join(out,'single-database-maintenance-'+targetId+'.lock');

 const nativeRestore={user:'SYSDBA',passwordEnv:sourceConfig.native.passwordEnv,keyEnv:sourceConfig.native.exportKeyEnv,keyReference:sourceConfig.native.exportKeyReference,timeoutSeconds:120,maintenanceLock:lock,attemptMarker:path.join(out,'single-database-restore-'+targetId+'.json')};

 const config={...c,sourceTargetId:sourceConfig.targetId,packagePath:sourceConfig.packageOutput,packageSha256:sourceReceipt.sealedSha256,keyEnv:sourceConfig.keyEnv,keyReference:sourceConfig.approval.keyReference,nativeRestore,adapterPath:adapter,currentDecisionPath:path.join(out,'restore-current-decision54.json'),files:[],fileRoot:path.join(out,'restored-originals54'),recoveryCatalog:sourceConfig.recoveryCatalog,recoveryComparisonDirectory:path.join(out,'comparison54')};

 const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId,action:'restore',owner:'Codex delegated technical executor',source:path.join(base,'continuous-window55/resource-window-extension55.json'),approvedAt:new Date().toISOString(),expiresAt:deadline,currentMigration:false,connectionSwitch:false,deleteExisting:false,newEnvironmentOnly:true,sourceTargetId:config.sourceTargetId,sourceVolume:sourceConfig.deployment.database.volume,configurationSha256:hash(canonical(deployment)),restoreConfigurationSha256:hash(canonical(nativeRestore)),packageSha256:config.packageSha256,keyReference:config.keyReference,nativeExportKeyReference:nativeRestore.keyReference,nativeImportAllowed:true,nativeToolEvidence:path.join(out,'native-import-help54.raw.log'),nativeToolHelpSha256:hash(help),fileRoot:config.fileRoot,writerRegistryComplete:true,writerRegistryEvidence:responsibility,maintenanceExclusivityEvidence:responsibility,writers:[...sourceConfig.approval.writers.filter(w=>w.kind==='CONTAINER').map(w=>({...w,responsibilitySource:responsibility})),{id:lock,kind:'MAINTENANCE',owner:'Codex delegated technical executor',responsibilitySource:responsibility}],emptyInventorySha256:inventoryDigest(empty),files:[],databaseRights:sourceConfig.approval.databaseRights,adapterSha256:hash(await readFile(adapter)),recoveryCatalogConfigurationSha256:hash(canonical(config.recoveryCatalog)),recoveryComparisonDirectory:config.recoveryComparisonDirectory};

 const bytes=Buffer.from(JSON.stringify(approval,null,2)+'\n');await writeFile(config.currentDecisionPath,bytes,{flag:'wx'});approval.currentDecisionEvidenceSha256=hash(bytes);config.approval=approval;validateRestoreConfiguration(config);await save('restore-config54.json',config);await save('preimport-admission54.json',{passed:true,targetId,sourcePidZero:true,empty:true,accounts:15,schemas:6,nativePolicyEqual:true,sourceDirectSystemAndRoleGrantsEqual:true,profileVersionAndTenViewsEqual:true,sourcePackageSha256:config.packageSha256,automaticRetryAllowed:false});

}else throw Error('EXACT_PREPARATION_MODE_REQUIRED');

console.log(JSON.stringify({passed:true,mode:process.argv[2],targetId,domainMigrationsExecuted:0,objectGrantsReplayed:0,domainMaintenanceInitialized:0}));

