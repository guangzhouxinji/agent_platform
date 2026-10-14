#!/usr/bin/env node
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const evidence='docs/evidence/activation-recovery-design-20261010/continuous-fence75';
const native='docs/evidence/single-database-installation-20261009/approved-execution/jdbc-native05';
const bridge='docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74-06.json';
const bridgeSha256='4c4736bdc795498b327d031f7650828c1d8a8860377d32cb1e5cf711d232e2f8';
const resourceBaseline=evidence+'/current-resource-baseline75.json';
const javac='D:/software/jdk-21/bin/javac.exe';
const java='D:/software/jdk-21/bin/java.exe';
const probe='scripts/java/ConsultationActivationRecoveryAdmissionProbe75.java';
const probeTest='scripts/java/ConsultationActivationRecoveryAdmissionProbe75Test.java';
const nodeTests=['scripts/lib/consultation-activation-recovery-continuous-fence.test.mjs','scripts/lib/consultation-activation-recovery-entry.test.mjs','scripts/lib/consultation-activation-recovery-fixture-plan.test.mjs','scripts/lib/consultation-project-resource-isolation.test.mjs','scripts/lib/single-database-native-fence-container.test.mjs','scripts/lib/single-database-lifecycle-native.test.mjs','scripts/lib/single-database-owned-restore-entry.test.mjs'];
const sources=[
  'scripts/build-consultation-activation-recovery-continuous.mjs',
  'scripts/build-consultation-resource-baseline75.mjs',
  'scripts/execute-consultation-activation-recovery-continuous.mjs',
  probe,
  probeTest,
  'scripts/lib/single-database-owned-restore-entry.py',
  'scripts/lib/consultation-activation-recovery-continuous-fence.mjs',
  ...nodeTests,
  'scripts/lib/consultation-activation-recovery-continuous-runtime.mjs',
  'scripts/lib/consultation-activation-recovery-entry.mjs',
  'scripts/lib/consultation-project-resource-isolation.mjs',
  'scripts/lib/single-database-native-fence-container.mjs',
  'scripts/lib/single-database-lifecycle-native.mjs',
  'scripts/lib/single-database-native-export-parameters.test.mjs',
  'scripts/lib/single-database-native-diagnostic.mjs',
];
const dependencies=['jackson-annotations-2.17.2.jar','jackson-core-2.17.2.jar','jackson-databind-2.17.2.jar'].map(name=>path.join(root,native,name));
const sha=b=>createHash('sha256').update(b).digest('hex');
const absolute=relative=>path.join(root,relative);
const json=value=>Buffer.from(JSON.stringify(value,null,2)+'\n');
const require=(yes,code)=>{if(!yes)throw Error(code);};

async function digest(relative){return {path:relative.replaceAll('\\','/'),sha256:sha(await fs.readFile(absolute(relative)))};}
function run(command,args){
  const result=spawnSync(command,args,{cwd:root,encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024});
  require(result.status===0&&!result.signal,'CONTINUOUS_BUILD_COMMAND_FAILED');
  return {exitCode:result.status,stdout:result.stdout,stderr:result.stderr};
}
async function writeExact(relative,bytes){
  const target=absolute(relative);
  try{await fs.writeFile(target,bytes,{flag:'wx',mode:0o600});}
  catch(error){if(error.code!=='EEXIST'||sha(await fs.readFile(target))!==sha(bytes))throw error;}
  return {path:relative,sha256:sha(bytes)};
}

await fs.mkdir(absolute(evidence),{recursive:true});
require((await digest(bridge)).sha256===bridgeSha256,'CONTINUOUS_BRIDGE_CHANGED');
const nodeResult=run(process.execPath,['--test',...nodeTests]);
const counter=name=>{const matches=[...nodeResult.stdout.matchAll(new RegExp('\\b'+name+' (\\d+)\\s*$','gm'))];require(matches.length===1,'CONTINUOUS_NODE_TEST_SUMMARY_REFUSED');return Number(matches[0][1]);};
const nodeCount=counter('tests');require(nodeCount>=184&&counter('pass')===nodeCount&&counter('fail')===0&&counter('cancelled')===0&&counter('skipped')===0&&counter('todo')===0,'CONTINUOUS_NODE_TEST_SUMMARY_REFUSED');

// ACTR91 changes only the host binding; reuse the exact unchanged Java build.
const previousBytes=await fs.readFile(absolute(evidence+'/source-build-receipt75-11.json'));
require(sha(previousBytes)==='50989d7b4d783a2278e82e3353dd92853d9c39f1263f2a74cae9f3b86ae37807','CONTINUOUS_PRIOR_JAVA_RECEIPT_CHANGED');
const previous=JSON.parse(previousBytes),priorBytes=await fs.readFile(absolute(previous.manifest.path));
require(sha(priorBytes)===previous.manifest.sha256&&previous.manifest.sha256==='990ffe8abac3c55353c8c3e72751655c798553874fbe9ed662dec5cba32d6a7b','CONTINUOUS_PRIOR_JAVA_MANIFEST_CHANGED');
const priorManifest=JSON.parse(priorBytes);
for(const relative of [probe,probeTest])require((await digest(relative)).sha256===priorManifest.sources.find(f=>f.path===relative)?.sha256,'CONTINUOUS_JAVA_SOURCE_CHANGED');
const previousBridge=JSON.parse(await fs.readFile(absolute('docs/evidence/activation-recovery-design-20261010/fixture-integration74/bundle74.json')));
for(const f of previousBridge.dependencies)require((await digest(native+'/'+path.basename(f.path))).sha256===f.sha256,'CONTINUOUS_JAVA_DEPENDENCY_CHANGED');
const compile=previous.compile,javaResult=previous.java;
require(/ADMISSION75_JAVA_COMPONENTS=12; DM8_CONNECTIONS=0; SQL_WRITES=0/.test(javaResult.stdout),'CONTINUOUS_JAVA_TEST_SUMMARY_REFUSED');

const stagedRelative=native+'/ConsultationActivationRecoveryAdmissionProbe75.class';
require((await digest(stagedRelative)).sha256===previous.staged.sha256,'CONTINUOUS_STAGED_JAVA_CHANGED');

const manifest={
  format:'ACTR75_CONTINUOUS_SOURCE_BUNDLE/v1',
  bridge:{path:bridge,sha256:bridgeSha256},
  resourceBaseline:await digest(resourceBaseline),
  compiler:{path:javac,release:21},
  sources:await Promise.all(sources.map(digest)),
  staged:[await digest(stagedRelative)],
  tests:{node:{tests:nodeCount,passed:nodeCount,failed:0},java:{tests:12,passed:12,failed:0},dm8Connections:0,sqlWrites:0},
  boundaries:{physicalCaseExecuted:false,secretRead:false,databaseStartedOrStopped:false,businessAccepted:false},
};
const manifestRef=await writeExact(evidence+'/manifest75-18.json',json(manifest));
const receipt={format:'ACTR75_CONTINUOUS_SOURCE_BUILD_RECEIPT/v1',manifest:manifestRef,node:nodeResult,compile,java:javaResult,javaEvidenceReusedFrom:{path:evidence+'/source-build-receipt75-11.json',sha256:sha(previousBytes)},javaExecutedThisBuild:false,staged:manifest.staged[0],boundaries:manifest.boundaries};
await writeExact(evidence+'/source-build-receipt75-18.json',json(receipt));
console.log(JSON.stringify({passed:true,manifest:manifestRef,staged:manifest.staged[0],nodeTests:nodeCount,javaTests:12,dm8Connections:0,sqlWrites:0}));
