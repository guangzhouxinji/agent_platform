import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {validateDeploymentConfiguration,validateOfflineResources,deploymentAccounts} from './single-database-lifecycle.mjs';
const exec=promisify(execFile),fail=s=>{throw new Error(s);};
/** Re-read exact live Docker metadata, not an offline report. No start/stop/update/delete. */
export async function assertLiveOwnedTarget(c) {
  if(!c.deployment)fail('APPROVED_EXACT_DEPLOYMENT_REQUIRED');
  validateDeploymentConfiguration(c.deployment);
  const d=c.deployment.database;
  if(c.targetId!==d.containerId||c.inventoryJdbc.url!==d.jdbcUrl)fail('TARGET_ENDPOINT_MISMATCH');
  const schemas=c.deployment.domains.flatMap(v=>[v.business.schema,v.authority.schema]);
  const accounts=deploymentAccounts(c.deployment);
  if(JSON.stringify([...c.schemas].sort())!==JSON.stringify(schemas.sort())||JSON.stringify([...c.accounts].sort())!==JSON.stringify(accounts.sort()))fail('CONFIGURED_BINDINGS_MISMATCH');
  for(const [schema,jdbc] of Object.entries(c.ownerJdbc||{})) {
    const binding=c.deployment.domains.flatMap(v=>[v.business,v.authority]).find(v=>v.schema===schema);
    if(!binding||jdbc.url!==d.jdbcUrl||jdbc.user!==binding.ownerUser||['SYSDBA','SYSSSO','SYSAUDITOR'].includes(jdbc.user))fail('EXACT_SCHEMA_OWNER_EXECUTION_REQUIRED');
  }
  const read=async(command,args)=>JSON.parse((await exec(command,args,{shell:false,maxBuffer:4*1024*1024})).stdout);
  const container=await read('docker',['inspect',d.containerId]);const volume=await read('docker',['volume','inspect',d.volume]);
  validateOfflineResources(c.deployment,container,volume,[],false);
  if(!container[0].State.Running)fail('ACTUAL_OWNED_TARGET_NOT_RUNNING');
  return {actualRunningResource:true,containerId:d.containerId,volume:d.volume,observedAt:new Date().toISOString(),connectivityProvenByInventory:false};
}
