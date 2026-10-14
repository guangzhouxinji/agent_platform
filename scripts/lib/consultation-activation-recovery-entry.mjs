import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import https from 'node:https';
import tls from 'node:tls';
import {lookup as dnsLookup} from 'node:dns/promises';
import {X509Certificate,createHash,randomUUID} from 'node:crypto';
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {parseTaskRows} from './plan-dependencies.mjs';
import {validateOwnedJdbcToolResource} from './single-database-jdbc-container.mjs';
import {readContinuation55,CONTINUATION55} from './single-database-continuous-window-admission.mjs';
import {assertRestoreCatalog,deriveCompleteNativeForeignKeys} from './single-database-restore-catalog.mjs';
import {assertInventory,jdbcRequest,inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {openSnapshot} from './single-database-lifecycle-snapshot.mjs';
const exec=promisify(execFile),ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
export const ENTRY68=Object.freeze({target:CONTINUATION55.targetId,source:CONTINUATION55.sourceId,tool:'e6e40303d097075b4030d43c73158535e11b065a0f35a5dd16d6c8be9540105e',sourceTool:'ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096',network:'047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65',owner:'IMP-SINGLE-DB-FINAL-BASELINE-01',main:'ConsultationActivationRecoveryJdbcEntry',java:'/opt/java/openjdk/bin/java',root:'/jdbc/activation-validation68',fixtureSha256:'3c97aa455f8a212ddc445b6d8dd896db5d3303b23aec4cf03027474b4ca6ee34',artifactRoot:'docs/evidence/activation-recovery-design-20261010/',importJournal:'docs/evidence/single-database-installation-20261009/approved-execution/independent-restore59/single-database-restore-78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481.json',importSha256:'97bc275b613ce8a801d5ea64715c194a3e0244ea940f1f2a9fb65e0a9b3324e4'});
const WRITERS=Object.freeze(['9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f','6796847921a4c9542d8e2e2e7b203a72faae2a89d508ed6dd452ccb009baab2f','4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff','1bf91da6e10a789033932c7cd20aa806e7c6a81e3142509692247daa89af0df1','e0e5ddf6b3caac946c333ce8b581ebde6bbd8a6918b25a053e55b7b6c1a07449']);
const SCHEMAS=Object.freeze(['ACTR66_EMPTY','ACTR66_RECEIPT','ACTR66_EVENT']);
const REVIEWED_SCOPE_ORIGIN70=Object.freeze({thread:'01a1240e-be83-7cc2-86fe-23863cb72550',message:'01a127ff-5b30-7320-aeb3-9b782a7ed48d'});
const OPS=Object.freeze(['V_DROP_OLD','V_ADD_NEW','V_CREATE_RECEIPT','U_REVERSE_COMPLETE']);
const FIXTURE_FORMAT='ACTR66_TRUSTED_FIXTURE_POLICY/v1',FIXTURE_MAIN='ConsultationActivationRecoveryFixtureEntry';
const FIXTURE73=Object.freeze({root:'/jdbc/activation-validation97',plan:'docs/evidence/activation-recovery-design-20261010/fixture-preparation73/fixture-plan73.json',planSha256:'d1faa9f7d55d3a7cd75cb730475c82e5cbda7f7d0f650cb41ca666bc0957754e'});
const FIXTURE_CLASSES=Object.freeze(['ConsultationActivationRecoveryFixtureEntry.class','ConsultationActivationRecoveryFixtureEntry$Live.class','ConsultationActivationRecoveryFixturePrefix.class','ConsultationActivationRecoveryFixtureSeed.class','ConsultationActivationRecoveryFixtureSeed$Kind.class']);
const SHA=/^[a-f0-9]{64}$/,UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const hash=b=>createHash('sha256').update(b).digest('hex');
const canonical=v=>JSON.stringify(v&&typeof v==='object'?Array.isArray(v)?v.map(x=>JSON.parse(canonical(x))):Object.fromEntries(Object.keys(v).sort().map(k=>[k,JSON.parse(canonical(v[k]))])):v);
const fail=code=>{throw Error(code);};
const require=(yes,code)=>{if(!yes)fail(code);};
function keys(o,names){require(o&&typeof o==='object'&&!Array.isArray(o)&&Object.keys(o).length===names.length&&names.every(k=>Object.hasOwn(o,k)),'EXACT_ENTRY_FIELDS_REQUIRED');}
// Flat protocol only: decode key escapes before duplicate detection; no nested payloads.
export function parseEntryRpc(raw){
 require(typeof raw==='string'&&Buffer.byteLength(raw)<=4096,'RPC_LINE_LIMIT');
 const token='"(?:[^"\\\\\\x00-\\x1f]|\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4}))*"';
 const re=new RegExp('('+token+')\\s*:', 'g');
 const names=[...raw.matchAll(re)].map(m=>JSON.parse(m[1]));
 require(new Set(names).size===names.length,'RPC_DUPLICATE_FIELDS');
 const m=JSON.parse(raw);require(m&&typeof m==='object'&&!Array.isArray(m)&&Object.values(m).every(v=>v===null||['string','number','boolean'].includes(typeof v)),'RPC_FLAT_OBJECT_REQUIRED');
 return m;
}
async function checkedPath(root,relative,{newFile=false}={}){
 require(typeof relative==='string'&&!relative.includes('\\')&&!relative.split('/').some(p=>p==='..'||p==='.'||!p)&&!path.isAbsolute(relative),'EXACT_WORKSPACE_PATH_REQUIRED');
 const base=path.resolve(root);require(await fs.realpath(base)===base,'WORKSPACE_REDIRECT');let p=base;const parts=relative.split('/');
 for(let i=0;i<parts.length;i++){p=path.join(p,parts[i]);if(newFile&&i===parts.length-1)break;const s=await fs.lstat(p);require(!s.isSymbolicLink()&&await fs.realpath(p)===p,'EVIDENCE_REDIRECT');}
 return p;
}
async function bounded(root,relative,max=65536){const p=await checkedPath(root,relative);const f=await fs.open(p,'r');try{const s=await f.stat();require(s.isFile()&&s.size<=max,'BOUNDED_REGULAR_EVIDENCE_REQUIRED');const b=Buffer.alloc(max+1);let used=0;while(used<b.length){const r=await f.read(b,used,b.length-used,null);if(!r.bytesRead)break;used+=r.bytesRead;}require(used<=max,'EVIDENCE_CAPACITY');return b.subarray(0,used);}finally{await f.close();}}
async function artifact(root,ref,max=65536){keys(ref,['path','sha256']);require(ref.path.startsWith(ENTRY68.artifactRoot)&&SHA.test(ref.sha256),'PINNED_ACTR_EVIDENCE_REQUIRED');const b=await bounded(root,ref.path,max);require(hash(b)===ref.sha256,'ACTR_EVIDENCE_SHA_CHANGED');return {bytes:b,value:JSON.parse(b),path:ref.path};}
function taskHasExactReference(row,ref){
 const text=Object.values(row??{}).join(' '),links=[...text.matchAll(/\]\(([^()\s]+)\)/g)].map(match=>match[1]),digests=text.match(/\b[a-f0-9]{64}\b/g)??[];
 const planRelative=ref.path.replace(/^docs\//,'');return links.includes(planRelative)&&digests.includes(ref.sha256);
}
export async function assertCurrentScope(policy,{workspaceRoot=ROOT,now=new Date()}={}){
 keys(policy,['format','mode','runId','scopeApproval','hostResponsibility','sourceProtection','sourceReturnPlan','bundle']);require(['ACTR66_TRUSTED_ENTRY_POLICY/v1',FIXTURE_FORMAT].includes(policy.format)&&policy.mode==='EXISTING_IMPORTED_TARGET'&&UUID.test(policy.runId),'EXACT_IMPORTED_ENTRY_POLICY_REQUIRED');
 const plan=(await bounded(workspaceRoot,'docs/08-总开发实现计划.md',4*1024*1024)).toString('utf8');const gate=parseTaskRows(plan).find(t=>t.taskId==='ARC-CON-U107-DM8-VALIDATION-D-01');
 require(gate?.['状态']==='已完成'&&taskHasExactReference(gate,policy.scopeApproval),'CURRENT_PHYSICAL_D_AND_SCOPE_REFERENCE_REQUIRED');
 if(policy.format===FIXTURE_FORMAT)require(taskHasExactReference(gate,policy.bundle),'CURRENT_FIXTURE_BUNDLE_D_REFERENCE_REQUIRED');
 // Existing window is verified as a window only. Its empty-target action is never used for this target.
 readContinuation55(workspaceRoot,'fresh-fenced-source-protection',now);require(now.getTime()<Date.parse(CONTINUATION55.until)-20*60*1000,'SAFE_SOURCE_RETURN_RESERVE_REQUIRED');
 const a=(await artifact(workspaceRoot,policy.scopeApproval)).value;
 keys(a,['format','scopeId','targetId','schemas','until','approvedBy','sourceThreadId','humanMessageId','conclusion','humanEvidence','proposalEvidence']);
 const reviewedOrigin=a.sourceThreadId==='01a12159-ccfb-7402-b371-57986aeb1e88'||(a.sourceThreadId===REVIEWED_SCOPE_ORIGIN70.thread&&a.humanMessageId===REVIEWED_SCOPE_ORIGIN70.message);
 require(a.format==='ACTR66_SCOPE_APPROVAL/v1'&&a.scopeId==='ACTR66_V107_U107_THREE_CASES_ONLY'&&a.targetId===ENTRY68.target&&canonical(a.schemas)===canonical(SCHEMAS)&&a.until===CONTINUATION55.until&&a.approvedBy==='HUMAN_USER'&&reviewedOrigin&&UUID.test(a.humanMessageId)&&a.conclusion==='APPROVE_EXACT_ACTR66_PROPOSAL','EXACT_REVIEWED_HUMAN_SCOPE_REQUIRED');
 const human=(await artifact(workspaceRoot,a.humanEvidence)).value,proposal=await artifact(workspaceRoot,a.proposalEvidence);
 keys(human,['sourceThreadId','messageId','role','content','replyToMessageId']);
 require(human.sourceThreadId===a.sourceThreadId&&human.messageId===a.humanMessageId&&human.role==='user'&&typeof human.content==='string'&&human.content.length>0&&human.replyToMessageId===proposal.value.messageId,'HUMAN_PROPOSAL_ORIGIN_BINDING_REQUIRED');
 require(proposal.value.sourceThreadId===a.sourceThreadId&&proposal.value.role==='assistant'&&proposal.value.scopeId===a.scopeId,'EXACT_PROPOSAL_ORIGIN_REQUIRED');
 // Semantics are reviewed in the sole master-plan D; integrity checking does NOT infer legal approval.
 const imported=await bounded(workspaceRoot,ENTRY68.importJournal);require(hash(imported)===ENTRY68.importSha256,'ORIGINAL_IMPORTED_TARGET_PROOF_REQUIRED');
 const lines=imported.toString('utf8').trim().split(/\r?\n/).map(x=>JSON.parse(x));require(lines[0].targetId===ENTRY68.target&&lines.some(x=>x.kind==='NATIVE_IMPORT_STARTED')&&lines.some(x=>x.kind==='NATIVE_TOOL_RETURNED'),'ALREADY_IMPORTED_TARGET_REQUIRED');
 return {scopeSha256:policy.scopeApproval.sha256,sourceThreadId:a.sourceThreadId,until:a.until};
}
const CIM_SCRIPT="$ErrorActionPreference='Stop';$sha=[Security.Cryptography.SHA256]::Create();@(Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -ne $PID -and $_.Name -match '^(node|java|python|pwsh|powershell|cmd|wsl|bash|sh|disql|dbeaver|navicat|docker)(\\.exe)?$'}|ForEach-Object {@{pid=$_.ProcessId;parentId=$_.ParentProcessId;createdAt=$_.CreationDate.ToUniversalTime().ToString('o');name=$_.Name;commandSha256=if($_.CommandLine){([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($_.CommandLine)))).Replace('-','').ToLowerInvariant()}else{$null}}})|ConvertTo-Json -Compress -AsArray";
export async function readEntryResourceFacts({childPid}={}){
 const ids=[ENTRY68.target,ENTRY68.tool,ENTRY68.source,ENTRY68.sourceTool,...WRITERS];
 const [inspect,owned,network]=await Promise.all([
  exec('docker',['inspect',...ids],{shell:false,maxBuffer:4*1024*1024}),
  exec('docker',['ps','-q','--no-trunc'],{shell:false,maxBuffer:65536}),
  exec('docker',['network','inspect',ENTRY68.network],{shell:false,maxBuffer:65536})
 ]);
 return {containers:JSON.parse(inspect.stdout),ownedRunning:owned.stdout.trim().split(/\s+/).filter(Boolean),network:JSON.parse(network.stdout)[0],selfPid:process.pid,childPid:childPid??null};
}
export async function readEntryFacts(options={}){
 const facts=await readEntryResourceFacts(options);
 const host=await exec('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-Command',CIM_SCRIPT],{shell:false,maxBuffer:1024*1024});
 return {...facts,hostProcesses:JSON.parse(host.stdout)};
}
export function assertEntryFacts(facts,responsibility,{now=new Date(),childPid}={}){
 require(Array.isArray(facts.containers)&&facts.containers.length===9&&new Set(facts.containers.map(c=>c.Id)).size===9,'EXACT_ENTRY_RESOURCE_SET_REQUIRED');
 const byId=new Map(facts.containers.map(c=>[c.Id,c]));const target=byId.get(ENTRY68.target),tool=byId.get(ENTRY68.tool);
 validateOwnedJdbcToolResource({containerId:ENTRY68.tool,targetId:ENTRY68.target,networkId:ENTRY68.network,ownerTask:ENTRY68.owner},tool,target);
 require(target.Name==='/agent-platform-single-final-restore-20261009'&&target.State.Pid>0&&tool.State.Pid>0&&target.HostConfig.Memory===4026531840&&target.HostConfig.NanoCpus===2000000000&&target.HostConfig.ReadonlyRootfs===true&&target.HostConfig.RestartPolicy?.Name==='no'&&tool.HostConfig.RestartPolicy?.Name==='no','EXACT_TARGET_RUNTIME_REQUIRED');
 require(target.Image==='sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760'&&tool.Image==='sha256:14731f0ec76c841877c8858e1a29413d7acacda5c5ce567eaf6e5f19913b7cb1'&&target.Config.Labels.role==='restore-final-database'&&tool.Config.Labels['agent-platform.role']==='restore-jdbc-maintenance','EXACT_RESOURCE_IMAGE_ROLE_REQUIRED');
 const volumes=(target.Mounts||[]).filter(m=>m.Type==='volume');require(volumes.length===2&&volumes.every(m=>m.Name==='agent-platform-single-final-restore-data-20261009'&&m.RW===true&&['/opt/dmdbms/data','/opt/dmdbms/log'].includes(m.Destination))&&new Set(volumes.map(m=>m.Destination)).size===2,'EXACT_TARGET_VOLUME_BINDING_REQUIRED');
 const alias=Object.values(target.NetworkSettings.Networks||{}).find(n=>n.NetworkID===ENTRY68.network);const names=Object.hasOwn(alias||{},'DNSNames')?alias.DNSNames:alias?.Aliases;require(Array.isArray(names)&&names.includes('agent-platform-single-final-restore-20261009'),'EXACT_CURRENT_TARGET_ALIAS_REQUIRED');
 require(Object.keys(target.NetworkSettings.Networks||{}).length===1&&Object.keys(tool.NetworkSettings.Networks||{}).length===1,'SINGLE_ISOLATED_TARGET_NETWORK_REQUIRED');
 require(!(tool.Config.Env||[]).some(s=>/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)=.+/.test(s)),'JVM_INJECTION_FORBIDDEN');
 const binds=tool.Mounts||[];require(binds.length===2&&binds.every(m=>m.Type==='bind'&&m.RW===false&&['/jdbc','/prep'].includes(m.Destination))&&new Set(binds.map(m=>m.Destination)).size===2,'ORIGINAL_RO_TOOL_BINDS_REQUIRED');
 for(const m of binds){const expected=path.join(ROOT,'docs/evidence/single-database-installation-20261009/approved-execution',m.Destination==='/jdbc'?'jdbc-native05':'jdbc-runtime02');require(path.resolve(m.Source)===expected,'EXACT_ORIGINAL_RO_BIND_SOURCE_REQUIRED');}
 for(const id of [ENTRY68.source,ENTRY68.sourceTool,...WRITERS]){const c=byId.get(id);require(c&&c.Config?.Labels?.task===ENTRY68.owner&&c.State.Running===false&&c.State.Restarting===false&&c.State.Pid===0,'ALL_REGISTERED_WRITERS_MUST_BE_STOPPED');}
 require(canonical([...facts.ownedRunning].sort())===canonical([ENTRY68.target,ENTRY68.tool].sort()),'UNKNOWN_OWNED_RUNNING_RESOURCE');
 require(facts.network?.Id===ENTRY68.network&&canonical(Object.keys(facts.network.Containers||{}).sort())===canonical([ENTRY68.target,ENTRY68.tool].sort()),'UNKNOWN_CURRENT_NETWORK_WRITER');
 const running=facts.containers.filter(c=>c.State.Running),memory=running.reduce((n,c)=>n+c.HostConfig.Memory,0),cpu=running.reduce((n,c)=>n+c.HostConfig.NanoCpus,0);require(Number.isSafeInteger(memory)&&memory<=7516192768&&Number.isSafeInteger(cpu)&&cpu<=4000000000,'CURRENT_RESOURCE_LIMIT_EXCEEDED');
 assertHostWorkers(facts,responsibility,now,childPid);
 return {memoryBytes:memory,nanoCpus:cpu,sourceStoppedAt:byId.get(ENTRY68.source).State.FinishedAt};
}
const REQUIRED_CLASSES=Object.freeze(["ConsultationActivationRecoveryFixtureGuard.class", "ConsultationActivationRecoveryJdbcEntry$CurrentGuard.class", "ConsultationActivationRecoveryJdbcEntry$Rpc.class", "ConsultationActivationRecoveryJdbcEntry.class", "ConsultationActivationRecoveryJdbcStep$ExclusiveFileJournal.class", "ConsultationActivationRecoveryJdbcStep$Failure.class", "ConsultationActivationRecoveryJdbcStep$FrozenSql.class", "ConsultationActivationRecoveryJdbcStep$Journal.class", "ConsultationActivationRecoveryJdbcStep$LiveGuard.class", "ConsultationActivationRecoveryJdbcStep$Namespace.class", "ConsultationActivationRecoveryJdbcStep$Operation.class", "ConsultationActivationRecoveryJdbcStep$PreviousOutcome.class", "ConsultationActivationRecoveryJdbcStep$Result.class", "ConsultationActivationRecoveryJdbcStep$Scope.class", "ConsultationActivationRecoveryJdbcStep$Sql.class", "ConsultationActivationRecoveryJdbcStep$State.class", "ConsultationActivationRecoveryJdbcStep.class"]);
function assertHostWorkers(facts,responsibility,now,childPid){
 require(responsibility?.format==='ACTR66_HOST_WRITER_RESPONSIBILITY/v1'&&Array.isArray(responsibility.records)&&responsibility.owner==='Codex'&&Number.isFinite(Date.parse(responsibility.capturedAt))&&Date.parse(responsibility.capturedAt)<=now.getTime(),'CURRENT_HOST_RESPONSIBILITY_REQUIRED');
 const allowed=new Map(responsibility.records.map(r=>[r.pid,r]));require(allowed.size===responsibility.records.length,'DUPLICATE_HOST_RESPONSIBILITY');
 for(const p of facts.hostProcesses){
  require(Number.isSafeInteger(p.pid)&&p.pid>0&&SHA.test(p.commandSha256||'')&&Number.isFinite(Date.parse(p.createdAt)),'UNKNOWN_OR_UNREADABLE_HOST_WORKER');
  if(p.pid===facts.selfPid)continue;
  if(childPid&&p.pid===childPid){require(p.parentId===facts.selfPid&&p.name.toLowerCase()==='docker.exe','OWN_TRACKED_DOCKER_PROCESS_REQUIRED');continue;}
  const r=allowed.get(p.pid);require(r&&r.parentId===p.parentId&&r.createdAt===p.createdAt&&r.commandSha256===p.commandSha256&&['NON_DB_PROCESS','CONTROLLER_LAUNCHER'].includes(r.role),'UNREGISTERED_CURRENT_HOST_WORKER');
 }
}
async function classProof(bundle,readCommand,fixture=false){
 const root=fixture?FIXTURE73.root:ENTRY68.root,main=fixture?FIXTURE_MAIN:ENTRY68.main,classes=fixture?[...REQUIRED_CLASSES,...FIXTURE_CLASSES].sort():REQUIRED_CLASSES;
 keys(bundle,['format','classes','dependencies','fixtureSha256','sourceHashes',...(fixture?['planSha256']:[])]);require(bundle.format===(fixture?'ACTR66_FIXTURE_ENTRY_BUNDLE/v1':'ACTR66_ENTRY_BUNDLE/v1')&&(!fixture||bundle.planSha256===FIXTURE73.planSha256)&&bundle.fixtureSha256===ENTRY68.fixtureSha256&&Array.isArray(bundle.classes)&&Array.isArray(bundle.dependencies)&&bundle.dependencies.length===4,'EXACT_ENTRY_BUNDLE_REQUIRED');
 require(canonical(bundle.classes.map(f=>f.name).sort())===canonical(classes),'COMPLETE_TRANSITIVE_CLASS_SET_REQUIRED');
 const sources=['scripts/lib/consultation-activation-recovery-entry.mjs','scripts/java/ConsultationActivationRecoveryJdbcStep.java','scripts/java/ConsultationActivationRecoveryJdbcEntry.java','scripts/java/ConsultationActivationRecoveryFixtureGuard.java','db/dm8/V107__product_activation_recovery.sql','db/dm8/U107__product_activation_recovery.sql',...(fixture?['scripts/lib/consultation-activation-recovery-fixture-plan.mjs','scripts/java/ConsultationActivationRecoveryFixturePrefix.java','scripts/java/ConsultationActivationRecoveryFixtureEntry.java','scripts/java/ConsultationActivationRecoveryFixtureSeed.java','db/dm8/V032__product_identity.sql','db/dm8/V046__product_workload_subject.sql',FIXTURE73.plan]:[])];
 require(Array.isArray(bundle.sourceHashes)&&canonical(bundle.sourceHashes.map(f=>f.path).sort())===canonical(sources.sort()),'COMPLETE_SOURCE_PIN_SET_REQUIRED');
 const files=[...bundle.classes.map(f=>{require(/^(ConsultationActivationRecovery(?:JdbcStep|JdbcEntry|FixtureGuard|FixturePrefix|FixtureEntry|FixtureSeed))(\$[A-Za-z0-9_]+)?\.class$/.test(f.name)&&SHA.test(f.sha256),'CLOSED_CLASS_BUNDLE_REQUIRED');return {file:`${root}/classes/${f.name}`,sha:f.sha256};}),...bundle.dependencies.map(f=>{require(['/jdbc/jackson-annotations-2.17.2.jar','/jdbc/jackson-core-2.17.2.jar','/jdbc/jackson-databind-2.17.2.jar','/jdbc/DmJdbcDriver18-8.1.3.140.jar'].includes(f.path)&&SHA.test(f.sha256),'PINNED_EXISTING_DEPENDENCIES_REQUIRED');return {file:f.path,sha:f.sha256};}),{file:`${root}/fixture-contract68.json`,sha:ENTRY68.fixtureSha256},...(fixture?[{file:`${root}/fixture-plan73.json`,sha:FIXTURE73.planSha256},...bundle.sourceHashes.filter(f=>/^db\/dm8\/[VU]107_/.test(f.path)).map(f=>({file:`${root}/${f.path}`,sha:f.sha256}))]:[])];
 require(new Set(files.map(f=>f.file)).size===files.length&&bundle.classes.some(f=>f.name===main+'.class'),'EXACT_CLASS_SET_REQUIRED');
 const listing=await readCommand('docker',['exec',ENTRY68.tool,'find',root+'/classes','-mindepth','1','-printf','%P %y\n']);const entries=String(listing.stdout??listing).trim().split(/\r?\n/).sort();require(canonical(entries)===canonical(classes.map(n=>n+' f').sort()),'EXACT_INSTALLED_CLASS_DIRECTORY_REQUIRED');
 const out=await readCommand('docker',['exec',ENTRY68.tool,'sha256sum',...files.map(f=>f.file)]);const rows=String(out.stdout??out).trim().split(/\r?\n/);require(rows.length===files.length,'CLASS_HASH_RESPONSE_INVALID');
 rows.forEach((line,i)=>{const m=/^([a-f0-9]{64})\s+(.+)$/.exec(line);require(m&&m[1]===files[i].sha&&m[2]===files[i].file,'INSTALLED_CLASS_OR_DEPENDENCY_CHANGED');});
}
const SOURCE_CONFIG='docs/evidence/single-database-installation-20261009/approved-execution/source-protection56b/source-snapshot-config56.json';
const SOURCE_CONFIG_SHA='6170eab0136c1d9d3f6196e6684937a2d8ff6040a56fc2e9c5e1127fa4230c15';
const PUBLIC_CLIENT='docs/evidence/single-database-installation-20261009/approved-execution/final-entry-client23/public-client-and-binding-inputs.json';
const PUBLIC_CLIENT_SHA='34a7879b8f896f24e4fb0c34e719d2068f024243e09eb400e159bfb083b44f48';
const SERVER_SHA='de4f652f8f99db36e7b7049fab104de0c0918a0e68dcc2dbd26fad6451374f06';
const SOURCE_TLS_PATHS=Object.freeze(['/app/consultation','/.well-known/openid-configuration','/login']);
async function pinnedJson(root,file,pin){const bytes=await bounded(root,file);require(hash(bytes)===pin,'SOURCE_RETURN_ORIGINAL_CHANGED');return JSON.parse(bytes);}
function immutableResource(c){return JSON.parse(JSON.stringify({Id:c.Id,Name:c.Name,Image:c.Image,labels:c.Config.Labels,user:c.Config.User,mounts:c.Mounts,host:{Memory:c.HostConfig.Memory,NanoCpus:c.HostConfig.NanoCpus,Privileged:c.HostConfig.Privileged,ReadonlyRootfs:c.HostConfig.ReadonlyRootfs,RestartPolicy:c.HostConfig.RestartPolicy,PortBindings:c.HostConfig.PortBindings},networks:Object.values(c.NetworkSettings?.Networks||{}).map(n=>n.NetworkID).sort()}));}
function sourceReturnFacts(facts,initial,host,now){
 require(facts.containers?.length===9&&new Set(facts.containers.map(c=>c.Id)).size===9,'EXACT_RETURN_RESOURCE_SET_REQUIRED');
 const byId=new Map(facts.containers.map(c=>[c.Id,c])),liveIds=[ENTRY68.source,ENTRY68.sourceTool,...WRITERS];
 for(const id of [ENTRY68.target,ENTRY68.tool]){const c=byId.get(id);require(c&&c.State.Running===false&&c.State.Restarting===false&&c.State.Pid===0,'CURRENT_TARGET_TOOL_MUST_BE_STOPPED');}
 for(const id of liveIds){const c=byId.get(id),old=initial.containers.find(x=>x.Id===id);require(c&&old&&c.State.Running===true&&c.State.Restarting===false&&Number.isSafeInteger(c.State.Pid)&&c.State.Pid>0&&Number.isFinite(Date.parse(c.State.StartedAt))&&Date.parse(c.State.StartedAt)<=now.getTime()&&c.Config.Labels.task===ENTRY68.owner&&canonical(immutableResource(c))===canonical(immutableResource(old)),'EXACT_CURRENT_RETURNED_WRITER_REQUIRED');}
 require(canonical([...facts.ownedRunning].sort())===canonical(liveIds.sort())&&facts.network?.Id===ENTRY68.network&&canonical(Object.keys(facts.network.Containers||{}).sort())===canonical([...liveIds].sort()),'EXACT_ONE_DATABASE_RETURNED_RESOURCE_SET');
 const source=byId.get(ENTRY68.source),tool=byId.get(ENTRY68.sourceTool);require(source.Image==='sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760'&&source.Name==='/agent-platform-single-final-primary-20261009'&&source.Config.Labels.role==='primary-final-database','EXACT_CURRENT_PRIMARY_REQUIRED');
 const volumes=(source.Mounts||[]).filter(m=>m.Type==='volume');require(volumes.length===2&&volumes.every(m=>m.Name==='agent-platform-single-final-primary-data-20261009'&&m.RW===true&&['/opt/dmdbms/data','/opt/dmdbms/log'].includes(m.Destination))&&new Set(volumes.map(m=>m.Destination)).size===2,'EXACT_PRIMARY_VOLUME_REQUIRED');
 validateOwnedJdbcToolResource({containerId:ENTRY68.sourceTool,targetId:ENTRY68.source,networkId:ENTRY68.network,ownerTask:ENTRY68.owner},tool,source);
 require(tool.Image==='sha256:14731f0ec76c841877c8858e1a29413d7acacda5c5ce567eaf6e5f19913b7cb1'&&tool.Mounts?.length===1&&tool.Mounts[0].Destination==='/jdbc'&&tool.Mounts[0].RW===false&&tool.Mounts[0].Type==='bind'&&path.resolve(tool.Mounts[0].Source)===path.join(ROOT,'docs/evidence/single-database-installation-20261009/approved-execution/jdbc-native05')&&!(tool.Config.Env||[]).some(e=>/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|LD_PRELOAD|LD_LIBRARY_PATH)=.+/.test(e)),'EXACT_PRIMARY_TOOL_REQUIRED');
 for(const [id,port]of [[WRITERS[1],'5446'],[WRITERS[3],'5448']])require(Object.values(byId.get(id).HostConfig.PortBindings||{}).flat().some(b=>b.HostIp==='127.0.0.1'&&b.HostPort===port),'EXACT_RETURNED_LOOPBACK_PUBLISHER_REQUIRED');
 const memory=liveIds.reduce((n,id)=>n+byId.get(id).HostConfig.Memory,0),cpu=liveIds.reduce((n,id)=>n+byId.get(id).HostConfig.NanoCpus,0);require(liveIds.every(id=>Number.isSafeInteger(byId.get(id).HostConfig.Memory)&&byId.get(id).HostConfig.Memory>0&&Number.isSafeInteger(byId.get(id).HostConfig.NanoCpus)&&byId.get(id).HostConfig.NanoCpus>0)&&memory<=7516192768&&cpu<=4000000000,'CURRENT_SOURCE_RETURN_BUDGET_REQUIRED');
 assertHostWorkers(facts,host,now,null);
 return {memoryBytes:memory,nanoCpus:cpu,resources:liveIds.map(id=>({resource:immutableResource(byId.get(id)),pid:byId.get(id).State.Pid,startedAt:byId.get(id).State.StartedAt})).sort((a,b)=>a.resource.Id.localeCompare(b.resource.Id))};
}
export async function probeSourceReturnTls({caBytes,request=https.request,lookup=dnsLookup}){
 const name='consultation.agent-platform.internal',addresses=await lookup(name,{all:true});require(addresses.length===1&&addresses[0].address==='127.0.0.1','EXACT_CURRENT_LOCAL_DNS_REQUIRED');
 const ca=new X509Certificate(caBytes).toString(),results=[];
 for(const urlPath of SOURCE_TLS_PATHS){
  results.push(await new Promise((resolve,reject)=>{let finished=false,req;const deadline=setTimeout(()=>stop(),20000);const stop=()=>{if(finished)return;finished=true;clearTimeout(deadline);req?.destroy();reject(Error('CURRENT_STRICT_TLS_GET_REFUSED'));};
   req=request({host:'127.0.0.1',port:5446,path:urlPath,method:'GET',headers:{Host:name+':5446'},servername:name,ca,rejectUnauthorized:true,minVersion:'TLSv1.2',maxVersion:'TLSv1.2',agent:false,checkServerIdentity:(_host,cert)=>tls.checkServerIdentity(name,cert)||(hash(cert.raw)===SERVER_SHA?undefined:Error('EXACT_SERVER_PIN_REQUIRED'))},response=>{
    try{require(response.socket?.authorized===true&&hash(response.socket.getPeerCertificate().raw)===SERVER_SHA&&response.statusCode===200,'CURRENT_CA_SAN_PIN_AND_200_REQUIRED');}catch{stop();return;}
    const chunks=[];let size=0;response.on('data',b=>{size+=b.length;if(size>1048576){stop();return;}chunks.push(b);});response.on('error',stop);response.on('aborted',stop);response.on('end',()=>{if(finished)return;try{const body=Buffer.concat(chunks),text=body.toString('utf8');require(urlPath!=='/.well-known/openid-configuration'||JSON.parse(text).issuer==='https://consultation.agent-platform.internal:5446','CURRENT_OIDC_ISSUER_REQUIRED');require(urlPath!=='/login'||/name="username"/.test(text)&&/name="password"/.test(text),'CURRENT_NORMAL_LOGIN_FIELDS_REQUIRED');finished=true;clearTimeout(deadline);resolve({path:urlPath,status:200,serverSha256:SERVER_SHA,bodySha256:hash(body),bytes:body.length,clientCertificateCount:0});body.fill(0);}catch{stop();}});
   });req.setTimeout(20000,stop);req.on('error',stop);req.end();
  }));
 }
 return {format:'ACTR66_CURRENT_PUBLIC_TLS_GET/v1',requests:results};
}
const defaultReadCommand=(c,args)=>exec(c,args,{shell:false,maxBuffer:65536,timeout:15000});
export async function readActivationRecoveryPublicCa97(ref,{io=fs}={}){
 const logical='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer';
 const physical='C:/Users/60329/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer';
 require(ref?.path===logical&&ref.sha256==='ab658c0492f34775181a5a97cc46fd2ecf04bcf1ecbe08f073124fadd0eeb9f0','EXACT_PUBLIC_CA_REFERENCE_REQUIRED');
 const requested=path.resolve(logical),first=await io.lstat(requested),actual=await io.realpath(requested);
 require(!first.isSymbolicLink()&&[path.resolve(logical),path.resolve(physical)].includes(actual),'EXACT_PUBLIC_CA_PHYSICAL_BINDING_REQUIRED');
 const stat=await io.lstat(actual);
 require(!stat.isSymbolicLink()&&stat.isFile()&&stat.size<=65536&&await io.realpath(actual)===actual,'BOUNDED_PUBLIC_CA_REQUIRED');
 const b=await io.readFile(actual);require(b.length<=65536&&hash(b)===ref.sha256,'CURRENT_PUBLIC_CA_BYTES_CHANGED');return b;
}
export async function createActivationRecoveryEntry(policy,{workspaceRoot=ROOT,now=()=>new Date(),observe=readEntryFacts,readCommand=defaultReadCommand,openProtection=openSnapshot,env=process.env,inventoryProducer=jdbcRequest,tlsProbe=probeSourceReturnTls,readPublicCa=readActivationRecoveryPublicCa97}={}){
 const p=JSON.parse(JSON.stringify(policy)),decision=hash(canonical(p)),fixture=p.format===FIXTURE_FORMAT;const original=()=>require(hash(canonical(policy))===decision,'HELD_ENTRY_POLICY_CHANGED');
 await assertCurrentScope(p,{workspaceRoot,now:now()});original(); // No current scope => no processes, locks or secrets touched.
 const load=async()=>{original();await assertCurrentScope(p,{workspaceRoot,now:now()});await artifact(workspaceRoot,p.bundle);await artifact(workspaceRoot,p.sourceProtection);await artifact(workspaceRoot,p.sourceReturnPlan);const host=(await artifact(workspaceRoot,p.hostResponsibility)).value;require(host.runId===p.runId,'HOST_RESPONSIBILITY_RUN_MISMATCH');const facts=await observe({childPid:child?.pid});return {facts,limits:assertEntryFacts(facts,host,{now:now(),childPid:child?.pid})};};
 const ownerPid=process.pid;let releasing=false,released=false,used=false,runPending=false,resumedSchema=false,resumedPartial=false;let child=null,childClosed=false,javaPid=null,unknown=false,lock=null,journal=null,leaseRoot=null,marker=null,journalSequence=0,lastPrepared=null,stepIndex=0;const ctx=await load();
 const protection=(await artifact(workspaceRoot,p.sourceProtection)).value;keys(protection,['format','runId','sourceId','capturedAt','package','inventorySha256','catalogSha256','keyEnv','sourceApproval']);
 require(protection.format==='ACTR66_CURRENT_SOURCE_PROTECTION/v1'&&protection.runId===p.runId&&protection.sourceId===ENTRY68.source&&SHA.test(protection.inventorySha256)&&SHA.test(protection.catalogSha256)&&protection.keyEnv==='SINGLE_DB_FINAL_SNAPSHOT45_KEY'&&Date.parse(protection.capturedAt)<=Date.parse(ctx.limits.sourceStoppedAt),'CURRENT_SAME_RUN_SOURCE_PROTECTION_REQUIRED');
 const nativeApproval=(await artifact(workspaceRoot,protection.sourceApproval)).value;require(nativeApproval.runId===p.runId&&nativeApproval.targetId===ENTRY68.source&&nativeApproval.action==='snapshot','SAME_RUN_SEALED_SOURCE_APPROVAL_REQUIRED');
 const returnPlan=(await artifact(workspaceRoot,p.sourceReturnPlan)).value;require(returnPlan.format==='ACTR66_SOURCE_RETURN_PLAN/v1'&&returnPlan.runId===p.runId&&returnPlan.sourceId===ENTRY68.source&&returnPlan.targetId===ENTRY68.target&&canonical(returnPlan.writerIds)===canonical(WRITERS)&&returnPlan.fullInventoryAndStrictTlsRequired===true,'EXACT_SOURCE_RETURN_PLAN_REQUIRED');
 const bundle=(await artifact(workspaceRoot,p.bundle)).value;await classProof(bundle,readCommand,fixture);
 const planBytes=fixture?await bounded(workspaceRoot,FIXTURE73.plan):null;require(!fixture||hash(planBytes)===FIXTURE73.planSha256,'CURRENT_FIXTURE_PLAN_REQUIRED');const fixturePlan=fixture?JSON.parse(planBytes):null;
 for(const f of bundle.sourceHashes){const b=await bounded(workspaceRoot,f.path,4*1024*1024);require(SHA.test(f.sha256)&&hash(b)===f.sha256,'ENTRY_ORIGINAL_SOURCE_CHANGED');}
 let fixtureCase73,createFixtureJournal73,assertSchemaRecovery96,SCHEMA_RECOVERY96,assertPartialRecovery97,PARTIAL_RECOVERY97;
 if(fixture)({fixtureCase73,createFixtureJournal73,assertSchemaRecovery96,SCHEMA_RECOVERY96,assertPartialRecovery97,PARTIAL_RECOVERY97}=await import('./consultation-activation-recovery-fixture-plan.mjs'));
 keys(protection.package,['path','sha256']);require(SHA.test(protection.package.sha256),'AUTHENTICATED_PACKAGE_PIN_REQUIRED');const packageFile=await checkedPath(workspaceRoot,protection.package.path);
 require(/^[a-f0-9]{64}$/.test(env[protection.keyEnv]||''),'MANAGED_SNAPSHOT_KEY_REQUIRED');const key=Buffer.from(env[protection.keyEnv],'hex');let opened;
 try{opened=await openProtection({file:packageFile,key,expectedTargetId:ENTRY68.source,expectedSha256:protection.package.sha256,now:now().getTime()});}finally{key.fill(0);}
 try{require(opened.metadata.targetId===ENTRY68.source&&opened.metadata.approvalSha256===hash(canonical(nativeApproval))&&opened.metadata.createdAt===protection.capturedAt&&opened.metadata.inventorySha256===protection.inventorySha256&&opened.metadata.recoveryCatalog?.catalogSha256===protection.catalogSha256,'AUTHENTICATED_CURRENT_PACKAGE_BINDING_REQUIRED');}finally{opened.native?.fill(0);}
 const sealedInventory=opened.content?.inventory;require(sealedInventory?.actualJdbc===true&&sealedInventory.complete===true&&sealedInventory.targetId===ENTRY68.source&&sealedInventory.schemas?.length===6&&sealedInventory.accounts?.length===15&&inventoryDigest(sealedInventory)===protection.inventorySha256&&sealedInventory.restoreCatalog&&hash(canonical(sealedInventory.restoreCatalog))===protection.catalogSha256,'AUTHENTICATED_COMPLETE_SOURCE_INVENTORY_REQUIRED');
 const expiresAt=opened.metadata.expiresAt;opened=null;
 const sqlBytes={};for(const [name,pin]of [['V107__product_activation_recovery.sql','1b36de0c01ad6b5fd1bba76a0a4e43f3050974d1f66101f41d2cb90cbf042cde'],['U107__product_activation_recovery.sql','858ce5557dfbe449485abc85f91a1e47b89aff2d3083aec691e9118cb0e72fb6']]){const b=await bounded(workspaceRoot,'db/dm8/'+name);require(hash(b)===pin,'CONTROLLED_V_U_SOURCE_CHANGED');sqlBytes[name[0]]=b.toString('utf8');}
 const v=sqlBytes.V.split(';').filter(s=>s.trim()).map(s=>s.trim()+';'),u=sqlBytes.U.split(/\r?\n\/\r?\n/);require(v.length===3&&u.length===2,'ORIGINAL_SQL_LAYOUT_REQUIRED');const guardSql=u[0].slice(u[0].indexOf('DECLARE')).trim(),tail=u[1].split(';').filter(s=>s.trim()).map(s=>s.trim()+';');
 const sqlHashes={V_DROP_OLD:hash(v[0]),V_ADD_NEW:hash(v[1]),V_CREATE_RECEIPT:hash(v[2]),U_GUARD:hash(guardSql),U_DROP_RECEIPT:hash(tail[0]),U_DROP_OPERATION:hash(tail[1]),U_ADD_OLD_OPERATION:hash(tail[2])};
 if(fixture){for(const m of fixturePlan.migration)require(sqlHashes[m.step]===m.sqlSha256,'FIXTURE_ORIGINAL_SQL_BINDING');for(const m of [...fixturePlan.prepare,...fixturePlan.cases.map(c=>c.create),...fixturePlan.seeds])sqlHashes[m.step]=m.sqlSha256;for(const m of fixturePlan.seeds)sqlHashes[m.commitStep]=m.commitSha256;}
 const parent=ENTRY68.artifactRoot+'entry-admission68';const parentPath=await checkedPath(workspaceRoot,parent);
 const sourceLock='docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46/single-database-maintenance-'+ENTRY68.source+'.lock';
 async function noSourceLock(){try{await fs.lstat(path.resolve(workspaceRoot,sourceLock));fail('SOURCE_MAINTENANCE_LOCK_PRESENT');}catch(e){if(e.code!=='ENOENT')throw e;}}
 await noSourceLock();marker=randomUUID();const lockRelative=parent+'/single-database-maintenance-'+ENTRY68.target+'.lock';const lockPath=await checkedPath(workspaceRoot,lockRelative,{newFile:true});
 lock=await fs.open(lockPath,'wx',0o600);try{await lock.writeFile(p.runId+' '+marker+'\n');await lock.sync();leaseRoot=path.join(parentPath,'run-'+p.runId);await fs.mkdir(leaseRoot);journal=await fs.open(path.join(leaseRoot,'attempt.jsonl'),'wx',0o600);}catch(error){await lock.close();throw Error('ENTRY_JOURNAL_CREATION_FAILED_LOCK_PRESERVED');}
 const packageDigest=async()=>{const f=await fs.open(packageFile,'r');try{const h=createHash('sha256'),b=Buffer.alloc(1024*1024);let n;while((n=(await f.read(b,0,b.length,null)).bytesRead)>0)h.update(b.subarray(0,n));return h.digest('hex');}finally{await f.close();}};
 const live=async()=>{if(resumedSchema)await assertSchemaRecovery96(workspaceRoot);if(resumedPartial)await assertPartialRecovery97(workspaceRoot);require(!unknown,'UNKNOWN_REQUIRES_SOURCE_RETURN_NO_SUCCESSOR');await noSourceLock();const captured=await load();require((await bounded(workspaceRoot,lockRelative)).toString('utf8')===p.runId+' '+marker+'\n','CURRENT_OWNED_LOCK_LOST');require(now().getTime()<Date.parse(expiresAt)&&await packageDigest()===protection.package.sha256,'CURRENT_PROTECTION_EXPIRED_OR_CHANGED');await classProof(bundle,readCommand,fixture);for(const f of bundle.sourceHashes){require(hash(await bounded(workspaceRoot,f.path,4*1024*1024))===f.sha256,'HELD_ENTRY_SOURCE_CHANGED');}return hash(canonical({scope:p.scopeApproval.sha256,lockMarker:marker,limits:captured.limits,time:now().toISOString()}));};
 const failClosed=async()=>{unknown=true;if(child&&!child.killed)child.kill();await journal?.sync().catch(()=>{});await journal?.close().catch(()=>{});await lock?.close().catch(()=>{});};
 async function runInternal(request,{spawnIO=spawn}={}){
  keys(request,['schema','operation','previousOutcome']);const selected=fixture?fixtureCase73(fixturePlan,request):null;
  resumedSchema=fixture&&request.previousOutcome===SCHEMA_RECOVERY96.outcome;resumedPartial=fixture&&request.previousOutcome===PARTIAL_RECOVERY97.outcome;
  if(resumedPartial)await assertPartialRecovery97(workspaceRoot);
  if(resumedSchema)await assertSchemaRecovery96(workspaceRoot);
  require(fixture||SCHEMAS.includes(request.schema)&&OPS.includes(request.operation)&&request.previousOutcome==='CLEAR','CLOSED_ENTRY_REQUEST_REQUIRED');require(!used,'ENTRY_ALREADY_USED');used=true;
  if(fixture)require(now().getTime()<Date.parse(CONTINUATION55.until)-45*60*1000,'FIXTURE_CASE_START_RETURN_RESERVE_REQUIRED');await live();
  require(!unknown&&!releasing&&!released,'ENTRY_NO_LONGER_ADMITTED');
  const password=env.SINGLE_DB_ADMIN_PASSWORD;require(typeof password==='string'&&password.length>0&&!/[\r\n\0]/.test(password),'MANAGED_ADMIN_SECRET_REQUIRED');
  const classpath=(fixture?FIXTURE73.root:ENTRY68.root)+'/classes:/jdbc/jackson-annotations-2.17.2.jar:/jdbc/jackson-core-2.17.2.jar:/jdbc/jackson-databind-2.17.2.jar:/jdbc/DmJdbcDriver18-8.1.3.140.jar';
  const args=['exec','-i',...['LIFECYCLE_JDBC_URL','LIFECYCLE_JDBC_USER','LIFECYCLE_JDBC_PASSWORD'].flatMap(n=>['-e',n]),ENTRY68.tool,'/usr/bin/timeout','--signal=TERM','--kill-after=5s',fixture?'900s':'120s',ENTRY68.java,'-Xmx256m','-cp',classpath,fixture?FIXTURE_MAIN:ENTRY68.main];
  const caseJournal=fixture?createFixtureJournal73(fixturePlan,request):null;
  const steps=fixture?selected.journal.map(s=>s.step):request.operation==='U_REVERSE_COMPLETE'?['U_GUARD','U_DROP_RECEIPT','U_DROP_OPERATION','U_ADD_OLD_OPERATION']:[request.operation];let seq=0,buffer='',seenResult=null,total=0,queue=Promise.resolve();const nonces=new Set(),completions=[];let connectedGuard=false,finalGuard=false;
  return await new Promise((resolve,reject)=>{
   let ended=false;const stop=async code=>{if(ended)return;ended=true;clearTimeout(timer);await failClosed();reject(Error(code));};
   const timer=setTimeout(()=>void stop('ENTRY_TIMEOUT_UNKNOWN_LOCK_PRESERVED'),fixture?910000:125000);
   try{child=spawnIO('docker',args,{shell:false,env:{...env,LIFECYCLE_JDBC_URL:'jdbc:dm://agent-platform-single-final-restore-20261009:5236',LIFECYCLE_JDBC_USER:'SYSDBA',LIFECYCLE_JDBC_PASSWORD:password}});}catch{void stop('OWN_ENTRY_PROCESS_UNAVAILABLE');return;}
   child.stdout.on('data',bytes=>{total+=bytes.length;buffer+=bytes.toString('utf8');if(total>262144||buffer.length>4096)return void stop('RPC_OUTPUT_CAPACITY');let end;while((end=buffer.indexOf('\n'))>=0){const raw=buffer.slice(0,end).replace(/\r$/,'');buffer=buffer.slice(end+1);queue=queue.then(async()=>{
    require(!ended,'ENTRY_ALREADY_REFUSED_LOCK_PRESERVED');const m=parseEntryRpc(raw);
    if(m.type==='ERROR') {
      keys(m,['type','code','outcome','sqlState','vendorCode','automaticRetryAllowed']);require(typeof m.code==='string'&&typeof m.outcome==='string'&&typeof m.sqlState==='string'&&/^[A-Z][A-Z0-9_]{0,100}$/.test(m.code)&&/^[A-Z][A-Z0-9_]{0,100}$/.test(m.outcome)&&/^(?:[A-Z0-9]{5}|UNKNOWN)$/.test(m.sqlState)&&Number.isSafeInteger(m.vendorCode)&&m.vendorCode>=-2147483648&&m.vendorCode<=2147483647&&m.automaticRetryAllowed===false,'RPC_SANITIZED_ERROR_REQUIRED');
      await journal.writeFile(JSON.stringify({...m,runId:p.runId,schema:request.schema,javaPid,hostSequence:++journalSequence,capturedAt:now().toISOString()})+'\n');await journal.sync();fail('ENTRY_CHILD_REFUSED_OR_UNKNOWN_LOCK_PRESERVED');
    }
    require(!unknown,'ENTRY_ALREADY_REFUSED_LOCK_PRESERVED');
    if(m.type==='RESULT'&&fixture){keys(m,['type','runId','schema','outcome','completedDdlStatements','committedSeedTransactions','vendorCode','sqlState','guardSqlSha256']);require(!seenResult&&!lastPrepared&&finalGuard&&m.runId===p.runId&&m.schema===request.schema,'RPC_RESULT_WITHOUT_DURABLE_COMPLETION');caseJournal.assertResult(m);seenResult=m;return;}
    if(m.type==='RESULT'){keys(m,['type','runId','schema','outcome','completedDdlStatements']);require(!seenResult&&!lastPrepared&&!unknown&&m.runId===p.runId&&m.schema===request.schema&&['FORWARD_STEP_COMPLETED_REOBSERVE_REQUIRED','REVERSE_COMPLETED_REOBSERVE_REQUIRED','GUARD_REFUSED_HISTORY'].includes(m.outcome)&&m.completedDdlStatements===(m.outcome==='REVERSE_COMPLETED_REOBSERVE_REQUIRED'?3:m.outcome==='GUARD_REFUSED_HISTORY'?0:1),'RPC_RESULT_WITHOUT_DURABLE_COMPLETION');require((request.operation==='U_REVERSE_COMPLETE'?m.outcome==='REVERSE_COMPLETED_REOBSERVE_REQUIRED'||m.outcome==='GUARD_REFUSED_HISTORY':m.outcome==='FORWARD_STEP_COMPLETED_REOBSERVE_REQUIRED')&&((m.outcome==='GUARD_REFUSED_HISTORY'&&canonical(completions)===canonical(['REFUSED']))||(m.outcome!=='GUARD_REFUSED_HISTORY'&&completions.length===steps.length&&completions.every(p=>p==='COMPLETED'))),'RPC_RESULT_OPERATION_OR_JOURNAL_MISMATCH');seenResult=m;return;}
    require(!seenResult&&['GUARD','JOURNAL'].includes(m.type),'RPC_UNSOLICITED_DATA');keys(m,m.type==='GUARD'?['type','runId','schema','sequence','nonce','javaPid','phase']:['type','runId','schema','sequence','nonce','javaPid','step','phase','sqlSha256']);
    require(m.runId===p.runId&&m.schema===request.schema&&Number.isSafeInteger(m.sequence)&&m.sequence===++seq&&seq<=256&&UUID.test(m.nonce)&&Number.isSafeInteger(m.javaPid)&&m.javaPid>0&&(!javaPid||javaPid===m.javaPid),'RPC_CURRENT_BINDING_REQUIRED');javaPid=m.javaPid;require(!nonces.has(m.nonce),'RPC_NONCE_REUSED');nonces.add(m.nonce);require(seq!==1||(m.type==='GUARD'&&m.phase==='BEFORE_CONNECTION'),'RPC_FIRST_CURRENT_GUARD_REQUIRED');
    let proof;if(m.type==='GUARD'){require(['BEFORE_CONNECTION','CURRENT_STEP'].includes(m.phase),'RPC_GUARD_PHASE');proof=await live();connectedGuard=true;if(fixture&&stepIndex===steps.length&&m.phase==='CURRENT_STEP')finalGuard=true;}
    else{
     require(connectedGuard,'RPC_CURRENT_GUARD_MISSING');
     finalGuard=false;
     require(steps[stepIndex]===m.step&&(fixture?selected.journal[stepIndex].sqlSha256:sqlHashes[m.step])===m.sqlSha256,'RPC_CONTROLLED_STEP_SHA_REQUIRED');const accepted=caseJournal?.prepareRecord(m);
     if(m.phase==='PREPARED'){require(!lastPrepared&&!unknown,'RPC_PREPARED_ALREADY_PENDING');lastPrepared=m.step;await live();}
     else{require(lastPrepared===m.step&&['COMPLETED','REFUSED','UNKNOWN'].includes(m.phase),'RPC_COMPLETION_WITHOUT_ATTEMPT');lastPrepared=null;if(m.phase==='UNKNOWN'||fixture&&m.phase==='REFUSED'&&!(m.step==='U_GUARD'&&selected.vendorCode===-20001))unknown=true;completions.push(m.phase);stepIndex++;}
     const record={...m,hostSequence:++journalSequence,capturedAt:now().toISOString()};await journal.writeFile(JSON.stringify(record)+'\n');await journal.sync();accepted?.acceptAfterSync();proof=hash(canonical(record));
    }
    child.stdin.write(JSON.stringify({type:m.type+'_ACK',runId:p.runId,schema:request.schema,sequence:m.sequence,nonce:m.nonce,proofSha256:proof})+'\n');
   }).catch(()=>void stop('RPC_OR_CURRENT_GUARD_REFUSED_LOCK_PRESERVED'));}});
   child.stderr.on('data',()=>{});child.on('error',()=>void stop('OWN_ENTRY_PROCESS_UNAVAILABLE'));child.stdin.on('error',()=>void stop('RPC_STDIN_LOST_UNKNOWN'));child.on('close',(code,signal)=>{childClosed=true;queue.then(async()=>{const output=await fs.open(path.join(leaseRoot,'child-exit.json'),'wx',0o600);try{await output.writeFile(JSON.stringify({exitCode:code,signal,completedSteps:stepIndex,pendingStep:lastPrepared,resultReceived:!!seenResult,bufferedBytes:Buffer.byteLength(buffer),sqlAcceptance:false})+'\n');await output.sync();}finally{await output.close();}if(ended)return;if(code===124)fail('ENTRY_CHILD_TIMEOUT_LOCK_PRESERVED');require(code===0&&!signal&&seenResult&&!buffer&&stepIndex===(!fixture&&seenResult.outcome==='GUARD_REFUSED_HISTORY'?1:steps.length),'ENTRY_CLOSE_UNPROVEN');await journal.sync();await journal.close();await lock.close();ended=true;clearTimeout(timer);resolve({...seenResult,requiresCompleteSourceReturn:true,lockReleased:false});}).catch(error=>void stop(error?.message==='ENTRY_CHILD_TIMEOUT_LOCK_PRESERVED'?error.message:'ENTRY_EXIT_UNKNOWN_LOCK_PRESERVED'));});
   child.stdin.write(JSON.stringify({type:'START',runId:p.runId,...request})+'\n');
  });
 }
 async function run(request,options){require(!runPending,'ENTRY_ALREADY_USED');runPending=true;try{return await runInternal(request,options);}catch(error){await failClosed();throw error;}finally{runPending=false;}}
 // Releasing a maintenance lease is separate from a SQL result. Never silently deletes a failed lock.
 async function releaseAfterSourceReturn(reference){
  require(!releasing&&!released,'OWN_RELEASE_ALREADY_REQUESTED');releasing=true;
  try{
   require(process.pid===ownerPid&&!runPending&&(!child||childClosed),'ENTRY_PROCESS_OR_OWNER_NOT_RETURNED');original();
   const requested=(await artifact(workspaceRoot,reference)).value;keys(requested,['format','runId','sourceId','ownerPid']);require(requested.format==='ACTR66_SOURCE_RETURN_REQUEST/v1'&&requested.runId===p.runId&&requested.sourceId===ENTRY68.source&&requested.ownerPid===ownerPid,'EXACT_CURRENT_OWNER_RETURN_REQUEST_REQUIRED');
   const check=async()=>{original();readContinuation55(workspaceRoot,'fresh-fenced-source-protection',now());await artifact(workspaceRoot,p.sourceReturnPlan);await artifact(workspaceRoot,p.sourceProtection);await artifact(workspaceRoot,p.bundle);await artifact(workspaceRoot,p.scopeApproval);require(await packageDigest()===protection.package.sha256,'SOURCE_PROTECTION_PACKAGE_CHANGED');for(const f of bundle.sourceHashes)require(hash(await bounded(workspaceRoot,f.path,4*1024*1024))===f.sha256,'CURRENT_RETURN_SOURCE_PIN_CHANGED');await noSourceLock();require((await bounded(workspaceRoot,lockRelative)).toString('utf8')===p.runId+' '+marker+'\n','OWN_CURRENT_RELEASE_MARKER_REQUIRED');const host=(await artifact(workspaceRoot,p.hostResponsibility)).value;require(host.runId===p.runId,'CURRENT_RETURN_HOST_RUN_REQUIRED');return sourceReturnFacts(await observe({childPid:null}),ctx.facts,host,now());};
   const before=await check(),fingerprint=hash(canonical(before)),same=async()=>{const current=await check();require(hash(canonical(current))===fingerprint,'SOURCE_RESTART_OR_CURRENT_RESOURCES_CHANGED');};
   const config=await pinnedJson(workspaceRoot,SOURCE_CONFIG,SOURCE_CONFIG_SHA);require(config.targetId===ENTRY68.source&&config.schemas.length===6&&config.accounts.length===15&&config.inventoryJdbc.mainClass==='SingleDatabaseLifecycleJdbc'&&config.inventoryJdbc.dockerJdbc.containerId===ENTRY68.sourceTool&&config.inventoryJdbc.url==='jdbc:dm://agent-platform-single-final-primary-20261009:5236','EXACT_SOURCE_INVENTORY_PRODUCER_REQUIRED');
   const save=async(name,bytes)=>{require(Buffer.isBuffer(bytes)&&bytes.length<=32*1024*1024,'BOUNDED_RETURN_RAW_REQUIRED');const file=path.join(leaseRoot,name),handle=await fs.open(file,'wx',0o600);try{await handle.writeFile(bytes);await handle.sync();}finally{await handle.close();}return {path:path.relative(workspaceRoot,file).replaceAll('\\','/'),sha256:hash(bytes)};};
   const capture=async index=>{await same();await pinnedJson(workspaceRoot,SOURCE_CONFIG,SOURCE_CONFIG_SHA);let rawRef=null,rawValue;
    const inventory=await inventoryProducer(config.inventoryJdbc,{action:'inventory',targetId:ENTRY68.source,schemas:config.schemas,accounts:config.accounts,restoreCatalogProfile:config.recoveryCatalog.profileId},{captureRaw:async bytes=>{require(rawRef===null,'DUPLICATE_RAW_RETURN_OUTPUT');rawRef=await save('source-inventory-'+index+'.json',bytes);rawValue=JSON.parse(bytes.toString('utf8'));}});
    require(rawRef&&canonical(inventory)===canonical(rawValue),'CURRENT_RAW_PRODUCER_OUTPUT_REQUIRED');assertInventory(inventory,ENTRY68.source,config.schemas,config.accounts,config.deployment);assertRestoreCatalog(inventory.restoreCatalog);deriveCompleteNativeForeignKeys(inventory);
    require(inventoryDigest(inventory)===protection.inventorySha256&&hash(canonical(inventory.restoreCatalog))===protection.catalogSha256&&hash(canonical(inventory.nativePrivileges??null))===hash(canonical(sealedInventory.nativePrivileges??null)),'ACTUAL_FULL_SOURCE_RETURN_DIGEST_CHANGED');await same();return rawRef;
   };
   const inventoryBefore=await capture(0),publicClients=await pinnedJson(workspaceRoot,PUBLIC_CLIENT,PUBLIC_CLIENT_SHA),caRef=publicClients.clients.find(c=>c.domain==='consultation').maintenance.trustCertificate,caBytes=await readPublicCa(caRef);
   const tlsEvidence=await tlsProbe({caBytes});require(tlsEvidence?.format==='ACTR66_CURRENT_PUBLIC_TLS_GET/v1'&&tlsEvidence.requests?.length===3&&canonical(tlsEvidence.requests.map(r=>r.path))===canonical(SOURCE_TLS_PATHS)&&tlsEvidence.requests.every(r=>r.status===200&&r.serverSha256===SERVER_SHA&&SHA.test(r.bodySha256)&&Number.isSafeInteger(r.bytes)&&r.bytes>=0&&r.bytes<=1048576&&r.clientCertificateCount===0),'CURRENT_REAL_TLS_PROJECTION_REQUIRED');await same();
   const inventoryAfter=await capture(1);await same();await pinnedJson(workspaceRoot,PUBLIC_CLIENT,PUBLIC_CLIENT_SHA);await readPublicCa(caRef);
   const proof={format:'ACTR66_CURRENT_SOURCE_RETURN_CHECKED/v1',runId:p.runId,ownerPid,sourceId:ENTRY68.source,lockMarkerSha256:hash(marker),capturedAt:now().toISOString(),currentResources:before,resourceSha256:fingerprint,inventoryBefore,inventoryAfter,actualInventorySha256:protection.inventorySha256,actualCatalogSha256:protection.catalogSha256,tls:tlsEvidence,sqlAcceptance:false,automaticRetryAllowed:false};
   await save('source-return-proof.json',Buffer.from(JSON.stringify(proof)+'\n'));await same();await journal.close();await lock.close();await fs.unlink(lockPath);released=true;return {lockReleased:true,sourceReturned:true,sqlAcceptance:false,automaticRetryAllowed:false};
  }finally{releasing=false;}
 }
 return {run,assertCurrent:live,releaseAfterSourceReturn,leaseRoot,automaticRetryAllowed:false};
}
