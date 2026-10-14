import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {open,unlink} from 'node:fs/promises';
import {resolve,basename} from 'node:path';
import {canonical,admission} from './single-database-lifecycle-runtime.mjs';
import {assertLiveOwnedTarget} from './single-database-lifecycle-target.mjs';
const exec=promisify(execFile),fail=c=>{throw new Error(c);};
export function validateBootstrapFence(c,writers) {
  const a=c.approval,lock=c.maintenanceLock;
  if(a.writerRegistryComplete!==true||!a.writerRegistryEvidence||!a.maintenanceExclusivityEvidence||a.newEnvironmentOnly!==true||!lock||resolve(lock)!==lock||basename(lock)!==`single-database-maintenance-${c.targetId}.lock`)fail('EXPLICIT_BOOTSTRAP_WRITER_EXCLUSIVITY_REQUIRED');
  if(!Array.isArray(writers)||canonical(writers)!==canonical(a.writers)||writers.some(w=>!w.owner||!w.responsibilitySource||!['CONTAINER','MAINTENANCE'].includes(w.kind)))fail('BOOTSTRAP_REGISTERED_WRITERS_REQUIRED');
  const expected=c.deployment.domains.flatMap(d=>d.writerContainerIds);
  if(canonical(writers.filter(w=>w.kind==='CONTAINER').map(w=>w.id).sort())!==canonical([...expected].sort()))fail('BOOTSTRAP_EXACT_WRITER_SET_REQUIRED');
  if(!expected.length&&a.applicationsNeverStarted!==true)fail('NEVER_STARTED_APPS_OR_STOPPED_WRITERS_REQUIRED');
  if(writers.filter(w=>w.kind==='MAINTENANCE').length!==1||!writers.some(w=>w.kind==='MAINTENANCE'&&w.id===lock&&w.responsibilitySource===a.maintenanceExclusivityEvidence))fail('BOOTSTRAP_EXCLUSIVE_MAINTENANCE_OWNER_REQUIRED');
}
/** Empty-instance fence deliberately does not take locks on not-yet-created business tables.
 * Actual stopped writers + a named complete registry/maintenance exclusion remain required.
 * No claim that this cooperative lock blocks an unregistered external administrator. */
export async function acquireBootstrapExecutionFence(c,writers) {
  validateBootstrapFence(c,writers);
  const held=await open(c.maintenanceLock,'wx');let closed=false;
  const assertHeld=async()=>{
    if(closed)fail('BOOTSTRAP_FENCE_RELEASED');
    admission(c.approval,c.targetId,'bootstrap');await assertLiveOwnedTarget(c);
    await held.stat();
    const ids=c.deployment.domains.flatMap(d=>d.writerContainerIds);
    if(ids.length) {
      const {stdout}=await exec('docker',['inspect',...ids],{shell:false,maxBuffer:4*1024*1024});let result;try{result=JSON.parse(stdout);}catch{fail('BOOTSTRAP_WRITER_INSPECT_FAILED');}
      if(result.length!==ids.length)fail('BOOTSTRAP_WRITER_INSPECT_SET_MISMATCH');
      for(const d of c.deployment.domains)for(const id of d.writerContainerIds){const w=result.find(w=>w.Id===id);if(!w||w.State?.Running!==false||w.State?.Restarting||w.Config?.Labels?.['agent-platform.domain']!==d.domain)fail('BOOTSTRAP_WRITER_NOT_STOPPED');}
    }
  };
  try{await assertHeld();}catch(e){await held.close();await unlink(c.maintenanceLock);throw e;}
  return {assertHeld,release:async()=>{if(!closed){closed=true;await held.close();await unlink(c.maintenanceLock);}}};
}
