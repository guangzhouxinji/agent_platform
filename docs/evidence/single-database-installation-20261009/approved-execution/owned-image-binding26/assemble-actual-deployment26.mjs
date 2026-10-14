import {readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {hash,canonical} from '../../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
import {checkObjectGrantInventory} from '../../../../../scripts/lib/single-database-object-grants.mjs';
const e=path.resolve('docs/evidence/single-database-installation-20261009/approved-execution'),out=path.join(e,'owned-image-binding26');
const json=async f=>JSON.parse((await readFile(f,'utf8')).replace(/^\uFEFF/,''));
const ref=async f=>({path:path.resolve(f),sha256:hash(await readFile(f))});
const save=async(n,v)=>{const f=path.join(out,n);await writeFile(f,JSON.stringify(v,null,2)+'\n',{flag:'wx'});return ref(f)};
const requestPath=path.join(out,'application-installation-request26.json'),request=await json(requestPath);
const compiledPath=path.join(out,'application-compiled26.json'),compiled=await json(compiledPath);
const c=await json(path.join(e,'primary-grant-config08.json'));
const present=checkObjectGrantInventory(request.inventory,c.manifest);
if(present.size!==674)throw Error('COMPLETE_GRANTS_REQUIRED');
const migrated=await json(path.join(e,'primary-migration-receipt07.json'));
if(migrated.applied!==105||!migrated.actualExecution||migrated.targetId!==c.targetId)throw Error('REAL_MIGRATION_REQUIRED');
const grant=await json(path.join(e,'primary-grant-receipt08.json'));
if(grant.applied!==674||!grant.actualExecution||grant.targetId!==c.targetId)throw Error('REAL_GRANT_REQUIRED');
const base={targetId:c.targetId,manifestSha256:compiled.manifestSha256,installationRequestSha256:(await ref(requestPath)).sha256,actualJdbc:true,complete:true,errors:[]};
const run=await json(path.join(out,'fresh-prestart-inventory26-result.json'));
if(run.exitCode!==0)throw Error('READ_ONLY_INVENTORY_FAILED');
const inv=await save('application-inventory-receipt26.json',{
 ...base,format:'SINGLE_DATABASE_APPLICATION_INVENTORY/v1',capturedAt:run.finishedAt,
 inventorySha256:hash(canonical(request.inventory)),rawInventory:await ref(path.join(out,'fresh-prestart-inventory26.json')),requiredObjectGrants:present.size
});
const identityEvidence={
 execution:await ref(path.join(e,'shared-bootstrap25/bootstrap25-attempt02-actual-result.json')),
 reconciliation:await ref(path.join(e,'shared-bootstrap25/actual-bootstrap-reconciliation.json'))
};
const init=await save('application-initialization-receipt26.json',{
 ...base,format:'SINGLE_DATABASE_APPLICATION_INITIALIZATION/v1',capturedAt:new Date().toISOString(),
 completeScope:'Global six-schema/fifteen-account/105-migration/674-grant/storage installation prerequisites only, not all-domain identity or application acceptance',
 storage:await json(path.join(out,'actual-storage-fragment26-02.json')),
 installationEvidence:{
  bootstrap:await ref(path.join(e,'primary-bootstrap-receipt06.json')),
  migration:await ref(path.join(e,'primary-migration-receipt07.json')),
  migrationChain:await ref(path.join(e,'migration-exit-and-grants09.json')),
  grants:await ref(path.join(e,'primary-grant-receipt08.json')),
  grantChain:await ref(path.join(e,'shared-bootstrap25/original674-exit-chain.json'))
 },
 identityInitialization:{initializedDomains:['consultation'],globalIdentityInitialized:false,evidence:{consultation:identityEvidence}}
});
const ready=(await json(path.join(e,'formal-binding17-resume-inputs.json'))).readyApplicationFields;
const artifact=await json(path.join(e,'shared-bootstrap25/artifact-inspection.json'));
const a=structuredClone(ready.applications.find(a=>a.domain==='consultation'));
a.jar={path:artifact.jar.path,sha256:artifact.jar.sha256,containerSource:'/mnt/docker-desktop-disk/agent-platform-single-final/consultation/platform-api-25.jar'};
const config={
 schema:'SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1',approval:ready.approval,request:ready.request,
 installationRequest:await ref(requestPath),compiledInstallation:await ref(compiledPath),initializationReceipt:init,inventoryReceipt:inv,
 manifestSha256:compiled.manifestSha256,releaseKind:'engineering-candidate',image:ready.image,network:ready.network,networkId:ready.networkId,
 secretRoot:ready.secretRoot,filesVolume:ready.filesVolume,applications:[a]
};
await save('consultation-deployment26.json',config);
console.log(JSON.stringify({assembled:true,scope:'REAL_INSTALLATION_PLUS_CONSULTATION_IDENTITY_ONLY',artifactSha256:a.jar.sha256,requiredObjectGrants:present.size}));
