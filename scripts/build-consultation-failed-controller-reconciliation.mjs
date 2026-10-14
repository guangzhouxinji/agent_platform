#!/usr/bin/env node
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {hash} from './lib/single-database-lifecycle-runtime.mjs';
import {CONTINUOUS75 as C} from './lib/consultation-activation-recovery-continuous-fence.mjs';
import {RECONCILIATION81 as R,PREVIOUS_RECONCILIATION81 as P,writeReconciliationFile81} from './lib/consultation-failed-controller-reconciliation.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const testFile='scripts/lib/consultation-failed-controller-reconciliation.test.mjs';
if(process.argv.length!==2)throw Error('RECONCILIATION_BUILD_NO_ARGUMENTS');
const oldBytes=await fs.readFile(path.join(root,C.root,C.manifest));
if(hash(oldBytes)!=='735b4f2a7826a43564a6affa8c05dbd72643a373ebb067087179ae2bbbe3d75e')throw Error('RECONCILIATION_ORIGINAL_CONTINUOUS_BUNDLE_CHANGED');
const old=JSON.parse(oldBytes);
for(const f of [...old.sources,...old.staged])if(hash(await fs.readFile(path.join(root,f.path)))!==f.sha256)throw Error('RECONCILIATION_EXISTING_SOURCE_DRIFT');
const result=spawnSync(process.execPath,['--test',testFile],{cwd:root,encoding:'utf8',windowsHide:true,maxBuffer:2*1024*1024});
if(result.status!==0||result.signal)throw Error('RECONCILIATION_COMPONENTS_FAILED');
const count=name=>{const matches=[...result.stdout.matchAll(new RegExp('\\b'+name+' (\\d+)\\s*$','gm'))];if(matches.length!==1)throw Error('RECONCILIATION_TEST_SUMMARY_REQUIRED');return Number(matches[0][1]);};
const tests={tests:count('tests'),passed:count('pass'),failed:count('fail'),skipped:count('skipped'),cancelled:count('cancelled')};
if(tests.tests<72||tests.passed!==tests.tests||tests.failed||tests.skipped||tests.cancelled)throw Error('RECONCILIATION_ALL_COMPONENTS_REQUIRED');
const files=[...new Set([
  ...old.sources.map(f=>f.path),...old.staged.map(f=>f.path),C.root+'/'+C.manifest,
  old.resourceBaseline.path,old.bridge.path,
  'scripts/build-consultation-failed-controller-reconciliation.mjs',
  'scripts/execute-consultation-failed-controller-reconciliation.mjs',
  'scripts/lib/consultation-failed-controller-reconciliation.mjs',
  'scripts/lib/consultation-failed-controller-reconciliation-runtime.mjs',testFile,
  'scripts/lib/consultation-failed-controller-secret.ps1',
  'scripts/lib/consultation-failed-controller-public-ca.ps1',
  C.root+'/reconcile-'+P.runId+'/failure.json',
  C.root+'/reconcile-'+P.runId+'/source-before.json',
  'scripts/lib/single-database-final-deployment.ps1','scripts/lib/single-database-deployment.ps1',
  'scripts/lib/single-database-owned-image-admission.ps1','scripts/lib/single-database-windows-reference.ps1',
  'scripts/lib/single-database-lifecycle-runtime.mjs','scripts/lib/single-database-lifecycle.mjs',
  'scripts/lib/single-database-jdbc-container.mjs','scripts/lib/single-database-lifecycle-target.mjs',
  'scripts/lib/single-database-lifecycle-snapshot.mjs','scripts/lib/single-database-recovery-origin.mjs',
  'scripts/lib/single-database-restore-catalog.mjs','scripts/lib/single-database-restore-catalog-profile.json',
  'scripts/lib/single-database-restore-view-contract.mjs','scripts/lib/single-database-continuous-window-admission.mjs',
  'scripts/lib/single-database-dm8-startup-observation.mjs','scripts/lib/plan-dependencies.mjs',
  'docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/source-snapshot-config56.json',
])];
const manifest={format:'ACTR81_FIXED_RECONCILIATION_BUNDLE/v1',taskId:'REC-CON-ACTR78-FAILED-CONTROLLER-01',files:await Promise.all(files.map(async relative=>({path:relative,sha256:hash(await fs.readFile(path.join(root,relative)))}))),tests,boundaries:{sqlWrites:0,resourceMutations:0,lockRetired:false,businessAccepted:false}};
const ref=await writeReconciliationFile81(root,C.root+'/'+R.bundle,manifest);
await writeReconciliationFile81(root,C.root+'/reconciliation82-build-receipt.json',{format:'ACTR81_SOURCE_BUILD_RECEIPT/v1',manifest:ref,components:result,tests,boundaries:manifest.boundaries});
console.log(JSON.stringify({passed:true,manifest:ref,tests,physicalExecution:false}));
