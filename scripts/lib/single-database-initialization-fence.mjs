import {execFile} from 'node:child_process';import {promisify} from 'node:util';import {open,unlink} from 'node:fs/promises';
import {validateBootstrapFence} from './single-database-bootstrap-fence.mjs';
import {assertLiveOwnedTarget} from './single-database-lifecycle-target.mjs';
import {admission} from './single-database-lifecycle-runtime.mjs';
const exec=promisify(execFile);
/** New-instance owner DDL only; no application has started and data-table fences do not exist yet. */
export async function acquireExecutionFence(c,writers){
 if(c.manifest?.mode!=='empty'||c.approval?.applicationsNeverStarted!==true||c.deployment.domains.some(d=>d.writerContainerIds.length))throw Error('EXACT_NEVER_STARTED_INITIALIZATION_REQUIRED');
 validateBootstrapFence(c,writers);const file=await open(c.maintenanceLock,'wx');let closed=false;
 const assertHeld=async()=>{if(closed)throw Error('INITIALIZATION_FENCE_RELEASED');admission(c.approval,c.targetId,'execute');await assertLiveOwnedTarget(c);await file.stat();
  for(const d of c.deployment.domains){const name=`agent-platform-single-final-${d.domain}-20261009`;const names=(await exec('docker',['ps','-a','--filter',`name=^/${name}$`,'--format','{{.Names}}'],{shell:false,maxBuffer:4096})).stdout.trim();if(names)throw Error('APPLICATION_RESOURCE_EXISTS_DURING_INITIALIZATION');}
 };
 try{await assertHeld();}catch(e){await file.close();await unlink(c.maintenanceLock);throw e;}
 return {assertHeld,release:async()=>{if(!closed){closed=true;await file.close();await unlink(c.maintenanceLock);}}};
}
