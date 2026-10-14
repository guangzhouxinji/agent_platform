#!/usr/bin/env node
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const output='docs/evidence/activation-recovery-design-20261010/continuous-fence75/current-resource-baseline75.json';
const priorPath='docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/current-resource-writer-discovery56.json';
const targetCreatedPath='docs/evidence/single-database-installation-20261009/approved-execution/independent-restore54/target-created54.json';
const toolCreationPath='docs/evidence/single-database-installation-20261009/approved-execution/independent-restore54/execute-independent-restore54.ps1';
const importedPath='docs/evidence/single-database-installation-20261009/approved-execution/independent-restore59/single-database-restore-78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481.json';
const returnPath='docs/evidence/single-database-installation-20261009/approved-execution/independent-restore59/source-safe-return59.json';
const observationPath='docs/evidence/activation-recovery-design-20261010/fixture-integration74/admission-readonly74.json';
const targetId='78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481';
const toolId='e6e40303d097075b4030d43c73158535e11b065a0f35a5dd16d6c8be9540105e';
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const absolute=relative=>path.join(root,relative);
const read=relative=>fs.readFile(absolute(relative));
const require=(yes,code)=>{if(!yes)throw Error(code);};
const ref=async(relative)=>({path:relative,sha256:sha(await read(relative))});
const comparable=(field,value)=>field==='mounts'?[...value].sort((a,b)=>a.Destination.localeCompare(b.Destination)):value;

function record(c){
  return {
    id:c.Id,name:c.Name,image:c.Image,labels:c.Config.Labels,user:c.Config.User,
    command:c.Config.Cmd,entrypoint:c.Config.Entrypoint,memoryBytes:c.HostConfig.Memory,
    nanoCpus:c.HostConfig.NanoCpus,readonlyRootfs:c.HostConfig.ReadonlyRootfs,
    restartPolicy:c.HostConfig.RestartPolicy,ports:c.HostConfig.PortBindings,
    mounts:c.Mounts,networks:c.NetworkSettings.Networks,
  };
}

const prior=JSON.parse(await read(priorPath));
require(prior.records?.length===7,'BASELINE75_EXACT_PRIOR_SEVEN_REQUIRED');
const inspected=execFileSync('docker',['inspect',targetId,toolId],{cwd:root,encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024});
const containers=JSON.parse(inspected);
require(containers.length===2&&containers[0].Id===targetId&&containers[1].Id===toolId,'BASELINE75_EXACT_TARGET_TOOL_REQUIRED');
const targetCreated=JSON.parse(await read(targetCreatedPath));
const currentTarget=record(containers[0]);
const createdTarget=record(targetCreated);
for(const field of ['id','name','image','labels','user','command','entrypoint','memoryBytes','nanoCpus','readonlyRootfs','restartPolicy','ports','mounts']){
  require(JSON.stringify(comparable(field,currentTarget[field]))===JSON.stringify(comparable(field,createdTarget[field])),'BASELINE75_TARGET_CREATION_DRIFT_'+field.toUpperCase());
}
require(Object.values(currentTarget.networks).every(n=>n.NetworkID==='047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65'),'BASELINE75_TARGET_NETWORK_REQUIRED');
const toolSource=(await read(toolCreationPath)).toString('utf8');
for(const token of ['agent-platform-single-final-restore-tools54-20261010','restore-jdbc-maintenance',"--memory','512m", "--cpus','0.25",'--read-only'])require(toolSource.includes(token),'BASELINE75_TOOL_CREATION_SOURCE_REQUIRED');
const ids=[...prior.records.map(r=>r.id),targetId,toolId];
require(new Set(ids).size===9,'BASELINE75_DUPLICATE_RESOURCE');
const result={
  format:'ACTR75_APPROVED_CURRENT_RESOURCE_BASELINE/v1',
  purpose:'Exact nine-resource admission baseline; approval sources and current read-only reconciliation remain distinct.',
  approvalSources:{priorSeven:await ref(priorPath),targetCreation:await ref(targetCreatedPath),toolCreation:await ref(toolCreationPath),importedTarget:await ref(importedPath)},
  reconciliationSources:{safeReturn:await ref(returnPath),readOnlyObservation:await ref(observationPath)},
  records:[...prior.records,currentTarget,record(containers[1])],
  boundaries:{environmentValuesSaved:false,secretRead:false,startStop:false,sql:false,leaseCreated:false,physicalCaseExecuted:false},
};
const bytes=Buffer.from(JSON.stringify(result,null,2)+'\n');
try{await fs.writeFile(absolute(output),bytes,{flag:'wx',mode:0o600});}
catch(error){if(error.code!=='EEXIST'||sha(await read(output))!==sha(bytes))throw error;}
console.log(JSON.stringify({path:output,sha256:sha(bytes),records:result.records.length,boundaries:result.boundaries}));
