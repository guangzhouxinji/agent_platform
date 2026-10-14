#!/usr/bin/env node
import fs from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readProjectFacts76} from './lib/consultation-project-resource-isolation.mjs';

const root='docs/evidence/activation-recovery-design-20261010/continuous-fence75';
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const ref=async path=>({path,sha256:sha(await fs.readFile(path))});
const facts=await readProjectFacts76();
const old=JSON.parse(execFileSync('docker',['inspect','agent-platform-dm8','--format','{"Id":{{json .Id}},"Name":{{json .Name}},"State":{{json .State}},"Mounts":{{json .Mounts}}}'],{encoding:'utf8',windowsHide:true}));
if(old.Id!=='3fd7f963aa8296a25692e06fb56bca64f904b3677b86535d9ff724a84407a136'||old.State.Running||old.State.Pid!==0)throw Error('PROJECT76_OLD_DATABASE_NOT_STOPPED');
const value={
  format:'ACTR76_PROJECT_RESOURCE_ISOLATION_RECEIPT/v1',
  taskId:'FIX-CON-ACTR76-PROJECT-RESOURCE-ISOLATION-01',
  capturedAt:new Date().toISOString(),
  humanInstruction:{source:'Current main execution conversation; directly received user message',text:'`entservice-dm8`是其他项目的dm,不要动，`agent-platform-dm8`检查一下是不是目前的单库，如果不是，那应该就可以停掉',scope:'Do not mutate entservice; conditionally stop only the identified old project database, never delete it.'},
  manifest:await ref(root+'/manifest75-05.json'),
  buildReceipt:await ref(root+'/source-build-receipt75-05.json'),
  preflight:await ref(root+'/verify-before-project-isolation76.log'),
  oldDatabase:old,
  oldShutdownWasGraceful:false,
  source:facts.containers.find(c=>c.Config.Labels?.role==='primary-final-database')?.State,
  runningProjectIds:facts.ownedRunning,
  allRunningIds:facts.allRunning,
  externalResources:facts.externalResources,
  projectNetworkId:facts.network.Id,
  projectNetworkMembers:Object.keys(facts.network.Containers??{}),
  hostProcesses:facts.hostProcesses,
  boundaries:{secretRead:false,sql:0,leaseCreated:false,physicalCaseExecuted:false,externalContainerMutated:false,currentSourceStartedOrStopped:false},
};
const target=root+'/project-isolation-receipt76.json',bytes=Buffer.from(JSON.stringify(value,null,2)+'\n');
await fs.writeFile(target,bytes,{flag:'wx',mode:0o600});
console.log(JSON.stringify({receipt:{path:target,sha256:sha(bytes)},projectRunning:value.runningProjectIds.length,externalObserved:value.externalResources.length,oldDatabaseStopped:true,boundaries:value.boundaries}));
