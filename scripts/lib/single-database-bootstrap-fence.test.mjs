import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import os from 'node:os';
import {validateBootstrapFence} from './single-database-bootstrap-fence.mjs';
const target='a'.repeat(64);
function config(){const lock=path.join(os.tmpdir(),`single-database-maintenance-${target}.lock`);return {targetId:target,maintenanceLock:lock,deployment:{domains:[{writerContainerIds:[]},{writerContainerIds:[]},{writerContainerIds:[]}]},approval:{newEnvironmentOnly:true,writerRegistryComplete:true,writerRegistryEvidence:'original-named-complete-registry',maintenanceExclusivityEvidence:'original-owner-exclusion',applicationsNeverStarted:true,writers:[{kind:'MAINTENANCE',id:lock,owner:'responsible-party',responsibilitySource:'original-owner-exclusion'}]}};}
test('new target before apps created still requires complete maintenance responsibility',()=>{const c=config();validateBootstrapFence(c,c.approval.writers);});
for(const [title,mutate,code] of [
 ['missing complete registry',c=>c.approval.writerRegistryComplete=false,'EXPLICIT_BOOTSTRAP_WRITER_EXCLUSIVITY_REQUIRED'],
 ['unknown whether apps existed',c=>delete c.approval.applicationsNeverStarted,'NEVER_STARTED_APPS_OR_STOPPED_WRITERS_REQUIRED'],
 ['empty writers',c=>c.approval.writers=[],'BOOTSTRAP_EXCLUSIVE_MAINTENANCE_OWNER_REQUIRED'],
 ['wrong target lock',c=>c.maintenanceLock+='other','EXPLICIT_BOOTSTRAP_WRITER_EXCLUSIVITY_REQUIRED'],
 ['unnamed worker',c=>c.approval.writers.push({kind:'WORKER',id:'unknown'}),'BOOTSTRAP_REGISTERED_WRITERS_REQUIRED'],
 ['unlisted actual app',c=>c.deployment.domains[0].writerContainerIds=['b'.repeat(64)],'BOOTSTRAP_EXACT_WRITER_SET_REQUIRED'],
 ['wrong responsibility',c=>c.approval.writers[0].responsibilitySource='different','BOOTSTRAP_EXCLUSIVE_MAINTENANCE_OWNER_REQUIRED']])test(title,()=>{const c=config();mutate(c);assert.throws(()=>validateBootstrapFence(c,c.approval.writers),new RegExp(code));});
