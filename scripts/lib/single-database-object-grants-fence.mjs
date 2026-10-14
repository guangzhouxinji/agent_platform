import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {open,unlink} from 'node:fs/promises';
import {validateBootstrapFence} from './single-database-bootstrap-fence.mjs';
import {admission} from './single-database-lifecycle-runtime.mjs';
import {assertLiveOwnedTarget} from './single-database-lifecycle-target.mjs';
const exec=promisify(execFile),fail=c=>{throw new Error(c);};
// Same managed stopped-writer/maintenance registry as bootstrap; exact grant action,
// never converts a grant approval to a bootstrap approval or acquires table locks for DCL.
export async function acquireObjectGrantFence(c,writers) {
  validateBootstrapFence(c,writers);
  const held=await open(c.maintenanceLock,'wx');let closed=false;
  const assertHeld=async()=>{
    if(closed)fail('OBJECT_GRANT_FENCE_RELEASED');admission(c.approval,c.targetId,'grant-object');await assertLiveOwnedTarget(c);await held.stat();
    const ids=c.deployment.domains.flatMap(d=>d.writerContainerIds);
    if(ids.length){const {stdout}=await exec('docker',['inspect',...ids],{shell:false,maxBuffer:4*1024*1024});const rows=JSON.parse(stdout);
      if(rows.length!==ids.length)fail('OBJECT_GRANT_WRITER_SET_MISMATCH');
      for(const d of c.deployment.domains)for(const id of d.writerContainerIds){const w=rows.find(w=>w.Id===id);if(!w||w.State?.Running!==false||w.State?.Restarting||w.Config?.Labels?.['agent-platform.domain']!==d.domain)fail('OBJECT_GRANT_WRITER_NOT_STOPPED');}
    }
  };
  try{await assertHeld();}catch(e){await held.close();await unlink(c.maintenanceLock);throw e;}
  return {assertHeld,release:async()=>{if(!closed){closed=true;await held.close();await unlink(c.maintenanceLock);}}};
}
