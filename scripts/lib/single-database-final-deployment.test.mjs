import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync,mkdtempSync,rmSync} from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {compileInstallation,loadInstallationProfile} from './single-database-installation.mjs';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
import {deploymentAccounts} from './single-database-lifecycle.mjs';
const root=process.cwd(),evidence=path.resolve(root,'docs/evidence/single-database-installation-20261009/approved-execution/worker-reference');
mkdirSync(evidence,{recursive:true});const work=mkdtempSync(path.join(evidence,'test-temp-'));
const {profile}=await loadInstallationProfile(root),template=JSON.parse(readFileSync(path.join(root,profile.applicationSettingsTemplatePath),'utf8'));
const approvedRoot=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution');
const ref=file=>({path:path.resolve(file),sha256:hash(readFileSync(file))});let serial=0;
const save=(name,obj)=>{const f=path.join(work,`${serial++}-${name}`);writeFileSync(f,typeof obj==='string'?obj:JSON.stringify(obj));return ref(f);};
const jdbcUrl='jdbc:dm://agent-platform-single-final-primary-20261009:5236';
const deployment={schema:'SINGLE_DM8_DEPLOYMENT/v2',database:{containerId:'d'.repeat(64),volume:'agent-platform-single-final-primary-data-20261009',ownerTask:profile.taskId,jdbcUrl,memoryLimitBytes:4026531840,nanoCpuLimit:2000000000},domains:profile.bindings.map((b,n)=>({...b,issuer:`https://${b.domain}.agent-platform.internal:${5446+n}`,writerContainerIds:[],business:{...b.business,jdbcUrl},authority:{...b.authority,jdbcUrl}}))};
// Synthetic test input to REAL compiler checks, NOT actual deployment evidence.
const inventory={format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId:deployment.database.containerId,actualJdbc:true,complete:true,errors:[],schemas:deployment.domains.flatMap(d=>[d.business.schema,d.authority.schema]).map(schema=>{const checks=profile.finalChecks[schema].filter(c=>c.expected==='present');return {schema,objects:checks.filter(c=>c.kind==='object').map(c=>({name:c.name,type:c.type})),columns:checks.filter(c=>c.kind==='column').map(c=>({TABLE_NAME:c.table,COLUMN_NAME:c.name})),jdbcColumns:checks.filter(c=>c.kind==='column').map(c=>({TABLE_NAME:c.table,COLUMN_NAME:c.name,COLUMN_SIZE:c.size??null})),constraints:checks.filter(c=>c.kind==='constraint').map(c=>({TABLE_NAME:c.table,CONSTRAINT_NAME:c.name,CONSTRAINT_TYPE:c.type??null})),foreignKeys:[],indexes:[],sequences:[],views:[],triggers:[],dependencies:[],rows:[]};}),accounts:deploymentAccounts(deployment).map(name=>{const systemPrivileges=[{PRIVILEGE:'CREATE SESSION',ADMIN_OPTION:'NO'}];return {name,exists:true,systemPrivileges,roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name,'PUBLIC'],effectiveGrants:[{subject:name,system:systemPrivileges,roles:[],tables:[],columns:[]},{subject:'PUBLIC',system:[],roles:[],tables:[],columns:[]}]};})};
const grants=await compileInstallation({workspaceRoot:root,deployment,stage:'grants',inventory});
for(const s of grants.manifest.steps){const a=inventory.accounts.find(a=>a.name===s.grantee);a.tableGrants.push({OWNER:s.schema,TABLE_NAME:s.object,PRIVILEGE:s.privilege,GRANTABLE:'NO'});a.effectiveGrants[0].tables=structuredClone(a.tableGrants);}
const request={stage:'application-settings',deployment,inventory},compiled=await compileInstallation({workspaceRoot:root,...request});
const installationRequest=save('request.json',request),compiledInstallation=save('compiled.json',compiled);
const receipt={targetId:deployment.database.containerId,manifestSha256:compiled.manifestSha256,installationRequestSha256:installationRequest.sha256,actualJdbc:true,complete:true,errors:[],capturedAt:'2026-10-08T19:01:00Z'};
const quota=volume=>({volume,device:volume.includes('primary')?'/dev/agent-platform-single-final-primary-loop':'/dev/agent-platform-single-final-files-loop',proof:save('quota-proof.txt','SYNTHETIC TEST QUOTA ONLY'),hardLimitBytes:3221225472,enospcVerified:true,noExpansionPath:true,filesystemUuid:'12345678-1234-1234-1234-123456789abc',backingFile:volume.includes('primary')?'/mnt/docker-desktop-disk/agent-platform-single-final-storage-20261009/primary.ext4':'/mnt/docker-desktop-disk/agent-platform-single-final-storage-20261009/files.ext4'});
const filesVolume='agent-platform-single-final-files-20261009';
const init={...receipt,format:'SINGLE_DATABASE_APPLICATION_INITIALIZATION/v1',storage:{database:{...quota(deployment.database.volume),subpaths:['db','logs']},files:{...quota(filesVolume),directories:Object.fromEntries(deployment.domains.map((d,n)=>[d.domain,{subpath:`files/${d.domain}`,uid:10001+n,gid:10001+n,mode:'0700'}]))}}};
const inventoryEvidence={...receipt,format:'SINGLE_DATABASE_APPLICATION_INVENTORY/v1',inventorySha256:hash(canonical(inventory))};
const base={schema:'SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1',approval:ref(path.join(approvedRoot,'resource-approval01.json')),request:ref(path.join(approvedRoot,'resource-request-original01.json')),compiledInstallation,installationRequest,initializationReceipt:save('init.json',init),inventoryReceipt:save('inventory.json',inventoryEvidence),manifestSha256:compiled.manifestSha256,releaseKind:'engineering-candidate',image:'sha256:14731f0ec76c841877c8858e1a29413d7acacda5c5ce567eaf6e5f19913b7cb1',network:'agent-platform-single-final-20261009',networkId:'047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65',secretRoot:'C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009',filesVolume,applications:[]};
for(const [n,c] of compiled.manifest.configurations.entries()){
 const domain=c.domain,containerRoot=`/mnt/docker-desktop-disk/agent-platform-single-final/${domain}`;
 const a={domain,name:`agent-platform-single-final-${domain}-20261009`,uid:10001+n,jar:{...save('jar',`synthetic-jar-${domain}`),containerSource:`${containerRoot}/platform-api.jar`},properties:{...save('properties',c.propertiesContent),containerSource:`${containerRoot}/application.properties`},environment:{},secrets:{},files:{volume:filesVolume,subpath:`files/${domain}`,target:`/product/files/${domain}`},mounts:[]};
 const t=template.applications.find(a=>a.domain===domain);
 for(const e of c.requiredEnvironment){const f=t.fields.find(f=>f.referenceEnvironment===e.environment);
  if(e.valueSource==='managed-secret'){a.secrets[e.environment]={path:`${base.secretRoot}/${e.environment}.xml`,sha256:'a'.repeat(64)};continue;}
  let v='bounded-test-reference';
  if(f.property.includes('key-store'))v=f.property.endsWith('type')?'PKCS12':`file:/product/refs/${domain}/key.p12`;
  else if(f.property.includes('trust-store'))v=f.property.endsWith('type')?'PKCS12':`file:/product/refs/${domain}/trust.p12`;
  else if(f.property==='server.ssl.client-auth')v='need';
  else if(f.property.endsWith('enabled'))v='false';
  else if(f.property.includes('endpoint'))v='https://model.invalid/disabled';
  else if(f.property.includes('key-directory'))v=`/product/files/${domain}/keys`;
  else if(f.property.endsWith('policy-file'))v=`/product/refs/${domain}/policy.json`;
  a.environment[e.environment]=v;
 }
 for(const file of ['key.p12','trust.p12',...(domain==='adjudication'?['policy.json']:[])])a.mounts.push({...save(file,'synthetic-public-test-reference'),containerSource:`${containerRoot}/${file}`,target:`/product/refs/${domain}/${file}`});
 base.applications.push(a);
}
const q=s=>`'${s.replaceAll("'","''")}'`,library=path.join(root,'scripts/lib/single-database-final-deployment.ps1');
function run(config=base,{start=false,clock='2026-10-08T19:02:00Z',setup='',failCreate=0,failSecret=false,fixtureSecrets=true}={}){
 const file=save('config.json',config).path;
 const code=`$ErrorActionPreference='Stop';. ${q(library)}
function Get-FinalDeploymentUtc { [DateTimeOffset]::Parse(${q(clock)}) }
$script:calls=[Collections.Generic.List[object]]::new();$script:secretPaths=[Collections.Generic.List[string]]::new();$script:creates=0;$script:created=@{}
# Tripwire masks direct Docker: tests cannot contact Docker even on regression.
function docker { throw 'REAL_DOCKER_FORBIDDEN' }
${fixtureSecrets?`$script:fileCheck=(Get-Item function:Assert-FinalFile).ScriptBlock
function Get-FinalPhysicalWindowsReference($r,$root) { return Get-FinalWindowsReferencePath $r.path }
function Assert-FinalFile($r) { if($r.path.Replace('\\','/').StartsWith('C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/')) {if($r.sha256 -cne ('a'*64)){throw 'MOCK_SECRET_SHA'};return}; & $script:fileCheck $r }`:''}
function Read-FinalManagedSecret($r) { $script:secretPaths.Add($r.path); ${failSecret?"throw 'MOCK_INVALID_DPAPI'":"return 'TEST_ONLY_CREDENTIAL_SENTINEL'"} }
function Invoke-FinalDocker([string[]]$Arguments) {
 $script:calls.Add(@($Arguments));
 switch($Arguments[0]){
 'network' {return (@{Id='047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65';Name='agent-platform-single-final-20261009';Internal=$true;Labels=@{task='IMP-SINGLE-DB-FINAL-BASELINE-01'};Containers=@{('d'*64)=@{}}}|ConvertTo-Json -Depth 10)}
 'volume' {return (@{Name=$Arguments[2];Driver='local';Options=@{type='ext4';o='nodev,nosuid,noexec';device=if($Arguments[2].Contains('primary')){'/dev/agent-platform-single-final-primary-loop'}else{'/dev/agent-platform-single-final-files-loop'}};Labels=@{task='IMP-SINGLE-DB-FINAL-BASELINE-01'}}|ConvertTo-Json -Depth 10)}
 'image' {return (@{Id=$config.image;Config=@{Volumes=$null}}|ConvertTo-Json -Depth 10)}
 'inspect' {if($script:created.Contains($Arguments[1])){return ($script:created[$Arguments[1]]|ConvertTo-Json -Depth 15)};return (@{Id=('d'*64);Name='/agent-platform-single-final-primary-20261009';State=@{Running=$true};Config=@{User='10001:10001';Entrypoint=@('python3');Cmd=@('/entry.py');Labels=@{task='IMP-SINGLE-DB-FINAL-BASELINE-01'}};HostConfig=@{Privileged=$false;ReadonlyRootfs=$true;CapDrop=@('ALL');Memory=4026531840;NanoCpus=2000000000;Mounts=@(@{Type='volume';Target='/opt/dmdbms/data';Source='agent-platform-single-final-primary-data-20261009';VolumeOptions=@{Subpath='db'}},@{Type='volume';Target='/opt/dmdbms/log';Source='agent-platform-single-final-primary-data-20261009';VolumeOptions=@{Subpath='logs'}},@{Type='bind';Target='/entry.py';Source=${q('/run/desktop/mnt/host/'+root[0].toLowerCase()+'/'+root.substring(3).split(path.sep).join('/')+'/scripts/lib/single-database-owned-dm8-entry.py')};ReadOnly=$true})};Mounts=@(@{Type='volume';Name='agent-platform-single-final-primary-data-20261009';Destination='/opt/dmdbms/data';RW=$true},@{Type='volume';Name='agent-platform-single-final-primary-data-20261009';Destination='/opt/dmdbms/log';RW=$true},@{Type='bind';Source=${q('/run/desktop/mnt/host/'+root[0].toLowerCase()+'/'+root.substring(3).split(path.sep).join('/')+'/scripts/lib/single-database-owned-dm8-entry.py')};Destination='/entry.py';RW=$false})}|ConvertTo-Json -Depth 10)}
 'ps' {return @()}
 'create' {$script:creates++;if($script:creates -eq ${failCreate}){throw 'MOCK_FAILURE TEST_ONLY_CREDENTIAL_SENTINEL'};$id=([string]$script:creates)*64
 $get={param($flag)$Arguments[[Array]::IndexOf($Arguments,$flag)+1]}
 $labels=@{};$mounts=@();$envNames=@()
 for($i=0;$i -lt $Arguments.Count;$i++){
  if($Arguments[$i] -ceq '--label'){$kv=$Arguments[$i+1].Split('=',2);$labels[$kv[0]]=$kv[1]}
  if($Arguments[$i] -ceq '--env'){$envNames+=@($Arguments[$i+1])}
  if($Arguments[$i] -ceq '--mount'){$parts=@{};foreach($p in $Arguments[$i+1].Split(',')){$kv=$p.Split('=',2);$parts[$kv[0]]=if($kv.Count -eq 2){$kv[1]}else{$true}};$mounts+=@{Type=$parts.type;Source=$parts.source;Target=$parts.target;ReadOnly=[bool]$parts.readonly;VolumeOptions=@{Subpath=$parts['volume-subpath'];NoCopy=[bool]$parts['volume-nocopy']}}}
 }
  $runtimeMounts=@()
 foreach($m in $mounts){$source=$m.Source;if($source -cmatch '^([A-Za-z]):/(.*)$'){$source='/run/desktop/mnt/host/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2]};$runtimeMounts+=@{Type=$m.Type;Source=$source;Destination=$m.Target;RW=(-not $m.ReadOnly)}}
 $publish=(& $get '--publish').Split(':')
 $script:created[$id]=@{Id=$id;Name=('/'+(& $get '--name'));NetworkSettings=@{Networks=@{($config.network)=@{NetworkID=$config.networkId;Aliases=@((& $get '--network-alias'))}}};Mounts=$runtimeMounts;Config=@{User=(& $get '--user');Labels=$labels;Entrypoint=@('sh');Cmd=@('-c',$Arguments[-1]);Env=$envNames};HostConfig=@{Privileged=$false;ReadonlyRootfs=$true;CapDrop=@('ALL');Memory=805306368;NanoCpus=500000000;NetworkMode=(& $get '--network');RestartPolicy=@{Name='no'};SecurityOpt=@('no-new-privileges');PortBindings=@{'8443/tcp'=@(@{HostIp=$publish[0];HostPort=$publish[1]})};Mounts=$mounts}}
 return $id}
 'start' {return $Arguments[1]}
 default {throw 'UNEXPECTED_DOCKER_COMMAND'}
 }
}
[Environment]::SetEnvironmentVariable('SINGLE_DB_ENFPROD_SVC_PASSWORD',[NullString]::Value,'Process')
$config=Read-FinalDeploymentJson ${q(file)}
${setup}
$status='OK';$message='';$result=$null
try {$result=Invoke-FinalApplications $config ${start?'':'-ValidateOnly'}} catch {$status='REFUSED';$message=$_.Exception.Message}
@{secretPaths=$script:secretPaths.ToArray();unsetEnvironmentRestored=([Environment]::GetEnvironmentVariable('SINGLE_DB_ENFPROD_SVC_PASSWORD','Process') -eq $null);status=$status;message=$message;rawCreatedFailure=$script:rawCreatedFailure;result=$result;calls=$script:calls.ToArray();environmentRestored=([Environment]::GetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','Process') -ceq 'PREEXISTING_VALUE')}|ConvertTo-Json -Depth 25
`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',cwd:root,timeout:30000});assert.ifError(r.error);assert.equal(r.status,0,r.stdout+r.stderr);assert.ok(!r.stdout.includes('TEST_ONLY_CREDENTIAL_SENTINEL'),r.stdout);return JSON.parse(r.stdout.trim());
}
const mutate=f=>{const c=structuredClone(base);f(c);return c;};
test('real compiler v1 and UTC cross-date window pass ValidateOnly without Docker or secrets',()=>{const r=run();assert.equal(r.status,'OK',r.message);assert.equal(r.calls.length,0);assert.equal(r.result.applicationsStarted,false);assert.equal(r.result.secretsDecrypted,false);assert.equal(r.result.releaseKind,'engineering-candidate');assert.equal(r.result.businessVerified,false);});
for(const [name,f] of [
 ['unapproved SHA',c=>c.approval.sha256='b'.repeat(64)],['request SHA',c=>c.request.sha256='b'.repeat(64)],['manifest SHA',c=>c.manifestSha256='b'.repeat(64)],['missing exact network ID',c=>delete c.networkId],['old network',c=>c.network='old'],['mutable image',c=>c.image='jdk:latest'],['empty domain subset',c=>c.applications=[]],['unknown domain',c=>c.applications[0].domain='other'],['case-changed domain',c=>c.applications[0].domain='Consultation'],['non-array applications',c=>c.applications=c.applications[0]],['duplicate domain',c=>c.applications[1].domain='consultation'],['root UID',c=>c.applications[1].uid=0],['whole files volume',c=>c.applications[1].files.subpath=''],['DB volume to app',c=>c.applications[0].files.volume=deployment.database.volume],['cross-domain files',c=>c.applications[0].files.subpath='files/adjudication'],['unknown identity env',c=>c.applications[0].environment.ADMIN_IDENTITY='SYSDBA'],['plaintext secret',c=>c.applications[0].environment.SINGLE_DB_CONPROD_SVC_PASSWORD='FORBIDDEN'],['missing secret',c=>delete c.applications[0].secrets.SINGLE_DB_CONPROD_SVC_PASSWORD],['unregistered field',c=>c.password='FORBIDDEN'],['unregistered app field',c=>c.applications[0].password='FORBIDDEN'],['domain mount escape',c=>c.applications[0].mounts[0].target='/product/refs/enforcement/key.p12'],['source traversal',c=>c.applications[0].mounts[0].containerSource+='/../escape'],['wrong properties SHA',c=>c.applications[0].properties.sha256='b'.repeat(64)],['false final-release claim',c=>c.releaseKind='final-release']]){
 test(`refuses ${name} before Docker`,()=>{const r=run(mutate(f));assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);});
}
for(const clock of ['2026-10-08T18:59:59Z','2026-10-10T10:00:00Z','2026-10-11T01:00:00Z'])test(`absolute window refuses ${clock}`,()=>{const r=run(base,{clock});assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);});
test('all secret refs checked before Docker',()=>{const r=run(base,{start:true,failSecret:true});assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);});
test('three bounded independent Loader commands and finally restoration',()=>{const r=run(base,{start:true,setup:"[Environment]::SetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','PREEXISTING_VALUE','Process')"});assert.equal(r.status,'OK',r.message);assert.equal(r.environmentRestored,true);assert.equal(r.unsetEnvironmentRestored,true);const creates=r.calls.filter(a=>a[0]==='create');assert.equal(creates.length,3);assert.equal(r.calls.filter(a=>a[0]==='start').length,3);for(const [n,a] of creates.entries()){const text=a.join(' ');assert.equal(a[a.indexOf('--network-alias')+1],`${base.applications[n].domain}.agent-platform.internal`);assert.ok(a.includes(`${10001+n}:${10001+n}`));assert.ok(a.includes(`127.0.0.1:${5446+n}:8443`));assert.match(text,/--memory 768m --cpus 0\.5/);assert.match(text,/--read-only --cap-drop ALL/);assert.match(text,/PropertiesLauncher/);assert.match(text,/sha256sum/);assert.match(text,new RegExp(`volume-subpath=files/${base.applications[n].domain}`));assert.ok(!text.includes(deployment.database.volume));assert.match(text,new RegExp(compiled.manifest.configurations[n].mainClass.replaceAll('.','\\.')));for(let i=0;i<a.length;i++)if(a[i]==='--env')assert.match(a[i+1],/^[A-Z][A-Z0-9_]+$/);}});
test('second create failure restores env and preserves partial resources without start/delete',()=>{const r=run(base,{start:true,failCreate:2,setup:"[Environment]::SetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','PREEXISTING_VALUE','Process')"});assert.equal(r.status,'REFUSED');assert.equal(r.environmentRestored,true);assert.equal(r.calls.filter(a=>a[0]==='create').length,2);assert.equal(r.calls.filter(a=>a[0]==='start').length,0);assert.ok(!r.calls.some(a=>['stop','rm','restart','exec'].includes(a[0])));});
function alteredReceipt(config,which,obj){config[which]=save(which+'.json',obj);return config;}
for(const [name,change] of [
 ['incomplete initialization',c=>alteredReceipt(c,'initializationReceipt',{...init,complete:false})],
 ['unproven quota',c=>{const i=structuredClone(init);i.storage.files.enospcVerified=false;return alteredReceipt(c,'initializationReceipt',i);} ],
 ['wrong domain owner',c=>{const i=structuredClone(init);i.storage.files.directories.enforcement.uid=10001;return alteredReceipt(c,'initializationReceipt',i);} ],
 ['inventory target mismatch',c=>alteredReceipt(c,'inventoryReceipt',{...inventoryEvidence,targetId:'f'.repeat(64)})],
 ['stale inventory',c=>alteredReceipt(c,'inventoryReceipt',{...inventoryEvidence,capturedAt:'2026-10-08T19:00:00Z'})],
 ['inventory digest mismatch',c=>alteredReceipt(c,'inventoryReceipt',{...inventoryEvidence,inventorySha256:'f'.repeat(64)})],
 ['TLS client auth disabled',c=>{const a=c.applications[0],k=Object.keys(a.environment).find(k=>k.endsWith('SERVER_SSL_CLIENT_AUTH'));a.environment[k]='none';return c;}],
 ['raw properties substituted',c=>{const a=c.applications[0];a.properties={...save('properties','spring.datasource.username=SYSDBA\n'),containerSource:a.properties.containerSource};return c;}],
 ['missing approved secret on disk',c=>{c.applications[0].secrets.SINGLE_DB_CONPROD_SVC_PASSWORD={path:path.join(work,'missing.xml'),sha256:'a'.repeat(64)};return c;}],
 ['owner runtime and old address in compiler input',c=>{const q=structuredClone(request);q.deployment.domains[0].business.runtimeUser=q.deployment.domains[0].business.ownerUser;q.deployment.database.jdbcUrl='jdbc:dm://127.0.0.1:5491';c.installationRequest=save('wrong-request.json',q);return c;}],
 ['incomplete minimum grants',c=>{const q=structuredClone(request);q.inventory.accounts.find(a=>a.name==='CONPROD_SVC').tableGrants.pop();c.installationRequest=save('ungranted.json',q);c.inventoryReceipt=save('ungranted-receipt.json',{...inventoryEvidence,inventorySha256:hash(canonical(q.inventory))});return c;}]
])test(`prerequisite refuses ${name}`,()=>{const c=change(structuredClone(base));const r=run(c,{clock:name==='stale inventory'?'2026-10-08T19:16:00Z':undefined});assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);});
const wrapDocker=body=>`$script:originalDocker=(Get-Item function:Invoke-FinalDocker).ScriptBlock
function Invoke-FinalDocker([string[]]$Arguments){$value=& $script:originalDocker $Arguments;${body};return $value}`;
for(const [name,body] of [
 ['wrong network owner',"if($Arguments[0] -ceq 'network'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Labels.task='OTHER';$value=$v|ConvertTo-Json -Depth 20}"],
 ['external network',"if($Arguments[0] -ceq 'network'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Internal=$false;$value=$v|ConvertTo-Json -Depth 20}"],
 ['wrong DB identity',"if($Arguments[0] -ceq 'inspect'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Id=('e'*64);$value=$v|ConvertTo-Json -Depth 20}"],
 ['root DB',"if($Arguments[0] -ceq 'inspect'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Config.User='0:0';$value=$v|ConvertTo-Json -Depth 20}"],
 ['whole DB volume',"if($Arguments[0] -ceq 'inspect'){$v=$value|ConvertFrom-Json -AsHashtable;$v.HostConfig.Mounts[0].VolumeOptions.Subpath='';$value=$v|ConvertTo-Json -Depth 20}"],
 ['wrong files volume owner',"if($Arguments[0] -ceq 'volume'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Labels.task='OTHER';$value=$v|ConvertTo-Json -Depth 20}"],
 ['implicit image volume',"if($Arguments[0] -ceq 'image'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Config.Volumes=@{'/unexpected'=@{}};$value=$v|ConvertTo-Json -Depth 20}"],
 ['existing application collision',"if($Arguments[0] -ceq 'ps'){$value=@('agent-platform-single-final-enforcement-20261009')}"],
 ['running restore',"if($Arguments[0] -ceq 'ps'){$value=@('agent-platform-single-final-restore-20261009')};if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -ceq 'agent-platform-single-final-restore-20261009'){$v=$value|ConvertFrom-Json -AsHashtable;$v.Name='/agent-platform-single-final-restore-20261009';$value=$v|ConvertTo-Json -Depth 20}"]
])test(`Docker boundary refuses ${name} without create/start`,()=>{const r=run(base,{start:true,setup:wrapDocker(body)});assert.equal(r.status,'REFUSED');assert.equal(r.calls.filter(a=>['create','start'].includes(a[0])).length,0);});
test('created container drift blocks every start and leaves candidates',()=>{const setup=wrapDocker("if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -cne ('d'*64)){$v=$value|ConvertFrom-Json -AsHashtable;$v.HostConfig.Memory=0;$value=$v|ConvertTo-Json -Depth 20}");const r=run(base,{start:true,setup});assert.equal(r.status,'REFUSED');assert.equal(r.calls.filter(a=>a[0]==='create').length,3);assert.equal(r.calls.filter(a=>a[0]==='start').length,0);});
test('window expires mid-create: bounded partial preservation, env restored',()=>{const r=run(base,{start:true,setup:"function Get-FinalDeploymentUtc {if($script:creates -ge 2){return [DateTimeOffset]::Parse('2026-10-10T10:00:00Z')};return [DateTimeOffset]::Parse('2026-10-08T19:02:00Z')};[Environment]::SetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','PREEXISTING_VALUE','Process')"});assert.equal(r.status,'REFUSED');assert.equal(r.environmentRestored,true);assert.equal(r.calls.filter(a=>a[0]==='create').length,2);assert.equal(r.calls.filter(a=>a[0]==='start').length,0);});
test('valid final release remains separate from deployment/business acceptance',()=>{const c=structuredClone(base);c.releaseKind='final-release';c.releaseEvidence=save('release.json',{format:'SINGLE_DATABASE_FINAL_RELEASE/v1',manifestSha256:c.manifestSha256,ready:true,applications:c.applications.map(a=>({domain:a.domain,jarSha256:a.jar.sha256}))});const r=run(c);assert.equal(r.status,'OK',r.message);assert.equal(r.result.releaseKind,'final-release');assert.equal(r.result.businessVerified,false);assert.equal(r.result.applicationsStarted,false);});
test('managed-secret decoder uses only an owned temp DPAPI sentinel and refuses plaintext',()=>{
 const file=path.join(work,'sentinel.dpapi');const plain=save('plaintext.xml','TEST_ONLY_CREDENTIAL_SENTINEL');
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker {throw 'REAL_DOCKER_FORBIDDEN'}
ConvertTo-SecureString 'TEST_ONLY_CREDENTIAL_SENTINEL' -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath ${q(file)}
$r=@{path=${q(file)};sha256=(Get-FileHash -LiteralPath ${q(file)}).Hash.ToLowerInvariant()}
if((Read-FinalManagedSecret $r) -cne 'TEST_ONLY_CREDENTIAL_SENTINEL'){throw 'DPAPI_ROUNDTRIP_FAILED'}
try {Read-FinalManagedSecret @{path=${q(plain.path)};sha256=${q(plain.sha256)}}|Out-Null;throw 'PLAINTEXT_ACCEPTED'} catch {if($_.Exception.Message -cne 'FINAL_DPAPI_SECRET_UNAVAILABLE'){throw 'EXPECTED_DPAPI_REFUSAL'}}
Write-Output 'OWNED_TEMP_DPAPI_OK'`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',cwd:root,timeout:10000});assert.equal(r.status,0,r.stdout+r.stderr);assert.equal(r.stdout.trim(),'OWNED_TEMP_DPAPI_OK');assert.ok(!r.stderr.includes('TEST_ONLY_CREDENTIAL_SENTINEL'));
});
for(const [name,change] of [
 ['PUBLIC object privilege bypass',q=>{q.inventory.accounts[0].effectiveGrants.find(g=>g.subject==='PUBLIC').tables.push({OWNER:'SYS',TABLE_NAME:'SYSOBJECTS',PRIVILEGE:'SELECT',GRANTABLE:'NO'});} ],
 ['runtime GRANT TABLE capability',q=>{const a=q.inventory.accounts.find(a=>a.name==='CONPROD_SVC');a.systemPrivileges.push({PRIVILEGE:'GRANT TABLE',ADMIN_OPTION:'NO'});a.effectiveGrants[0].system=structuredClone(a.systemPrivileges);} ],
 ['wrong main in compiled manifest',q=>{}]
])test(`real compiler rejects ${name} without weakening shared guard`,()=>{const c=structuredClone(base);if(name==='wrong main in compiled manifest'){const m=structuredClone(compiled);m.manifest.configurations[0].mainClass=m.manifest.configurations[1].mainClass;m.manifestSha256=hash(canonical(m.manifest));c.compiledInstallation=save('wrong-main.json',m);c.manifestSha256=m.manifestSha256;}else{const q=structuredClone(request);change(q);c.installationRequest=save('unsupported-inventory.json',q);c.inventoryReceipt=save('unsupported-inventory-receipt.json',{...inventoryEvidence,inventorySha256:hash(canonical(q.inventory))});}const r=run(c);assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);});
test('existing owned-storage primary volume can serve only the three files/domain subpaths',()=>{const c=structuredClone(base);c.filesVolume=deployment.database.volume;for(const a of c.applications)a.files.volume=c.filesVolume;const i=structuredClone(init);i.storage.files={...i.storage.database,directories:i.storage.files.directories};delete i.storage.files.subpaths;c.initializationReceipt=save('shared-primary-init.json',i);const r=run(c,{start:true});assert.equal(r.status,'OK',r.message);const creates=r.calls.filter(a=>a[0]==='create');assert.equal(creates.length,3);for(const [n,args] of creates.entries()){const mounts=args.filter((v,j)=>j>0&&args[j-1]==='--mount');const vol=mounts.filter(v=>v.startsWith('type=volume,'));assert.equal(vol.length,1);assert.ok(vol[0].includes('source='+deployment.database.volume+','));assert.ok(vol[0].includes('volume-subpath=files/'+c.applications[n].domain+','));assert.ok(!vol[0].includes('volume-subpath=db'));assert.ok(!vol[0].includes('volume-subpath=logs'));}});
// Pure synthetic protected-root references: file SHA check and secret decode are mocked in run().
// No production private-root file is touched, and every Docker command is a mock/tripwire.
function windowsReferences(){const c=structuredClone(base);for(const a of c.applications)for(const [n,m] of a.mounts.entries()){m.path=`${c.secretRoot}/${a.domain}/certs/reference-${n}.p12`;m.containerSource=m.path;m.sha256='a'.repeat(64);}return c;}
test('managed Windows reference files bind directly, readonly, without a copy or widened artifact root',()=>{const c=windowsReferences();const r=run(c,{start:true,setup:"[Environment]::SetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','PREEXISTING_VALUE','Process')"});assert.equal(r.status,'OK',r.message);assert.equal(r.environmentRestored,true);assert.equal(r.unsetEnvironmentRestored,true);assert.equal(r.calls.filter(a=>a[0]==='start').length,3);for(const [n,args] of r.calls.filter(a=>a[0]==='create').entries()){for(const m of c.applications[n].mounts){assert.ok(args.includes(`type=bind,source=${m.path},target=${m.target},readonly`));assert.ok(args.at(-1).includes(`sha256sum '${m.target}'`));}assert.ok(args.includes(`type=bind,source=${c.applications[n].jar.containerSource},target=/app/platform-api.jar,readonly`));assert.ok(!args.some(v=>/(?:^|[;&]\s*)(?:cp|Copy-Item|Move-Item)\s/.test(v)));}});
test('slash normalization admits the same protected Windows file without changing directory spelling',()=>{const c=windowsReferences();for(const a of c.applications)for(const m of a.mounts){m.path=m.path.replaceAll('/','\\');m.containerSource=m.containerSource.replace(/^C:/,'c:');}const r=run(c,{start:true});assert.equal(r.status,'OK',r.message);});
for(const [name,mutate] of [
 ['other domain',c=>{const m=c.applications[0].mounts[0];m.path=m.path.replace('/consultation/','/enforcement/');m.containerSource=m.path;}],
 ['outside approved root',c=>{const m=c.applications[0].mounts[0];m.path='C:/unapproved/key.p12';m.containerSource=m.path;}],
 ['root prefix sibling',c=>{const m=c.applications[0].mounts[0];m.path=m.path.replace('single-final-20261009/','single-final-20261009-other/');m.containerSource=m.path;}],
 ['domain prefix sibling',c=>{const m=c.applications[0].mounts[0];m.path=m.path.replace('/consultation/','/consultation-other/');m.containerSource=m.path;}],
 ['DB directory',c=>{const m=c.applications[0].mounts[0];m.path='C:/db/data/key.p12';m.containerSource=m.path;}],
 ['source and file mismatch',c=>c.applications[0].mounts[0].containerSource+='.other'],
 ['comma source injection',c=>{const m=c.applications[0].mounts[0];m.path+=',target=/db';m.containerSource=m.path;}],
 ['comma target injection',c=>c.applications[0].mounts[0].target+=',readonly=false'],
 ['dot-dot source',c=>{const m=c.applications[0].mounts[0];m.path=m.path.replace('/certs/','/certs/../');m.containerSource=m.path;}],
 ['dot source',c=>{const m=c.applications[0].mounts[0];m.path=m.path.replace('/certs/','/certs/./');m.containerSource=m.path;}],
 ['dot target',c=>c.applications[0].mounts[0].target='/product/refs/consultation/..'],
 ['Windows trailing-dot alias',c=>{const m=c.applications[0].mounts[0];m.path+='.';m.containerSource=m.path;}],
 ['Windows ADS',c=>{const m=c.applications[0].mounts[0];m.path+=':payload';m.containerSource=m.path;}],
 ['UNC source',c=>{const m=c.applications[0].mounts[0];m.path='\\\\host\\share\\key.p12';m.containerSource=m.path;}],
 ['device source',c=>{const m=c.applications[0].mounts[0];m.path='\\\\?\\C:\\key.p12';m.containerSource=m.path;}],
 ['bare drive relative source',c=>{const m=c.applications[0].mounts[0];m.path='C:key.p12';m.containerSource=m.path;}],
 ['arbitrary daemon path',c=>c.applications[0].mounts[0].containerSource='/run/desktop/mnt/host/c/Users/unapproved/key.p12'],
 ['Windows source for JAR',c=>c.applications[0].jar.containerSource=`${c.secretRoot}/consultation/platform-api.jar`],
 ['Windows source for properties',c=>c.applications[0].properties.containerSource=`${c.secretRoot}/consultation/application.properties`],
 ['duplicate target',c=>c.applications[0].mounts[1].target=c.applications[0].mounts[0].target],
 ['wrong domain target',c=>c.applications[0].mounts[0].target='/product/refs/enforcement/key.p12'],
 ['reference secret SHA mismatch',c=>c.applications[0].mounts[0].sha256='b'.repeat(64)]
])test(`direct reference refuses ${name} before Docker`,()=>{const c=windowsReferences();mutate(c);const r=run(c,{start:true});assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);});
for(const [name,body] of [
 ['wrong drive',"$v.Mounts[3].Source=$v.Mounts[3].Source.Replace('/host/c/','/host/d/')"],
 ['uppercase drive mapping',"$v.Mounts[3].Source=$v.Mounts[3].Source.Replace('/host/c/','/host/C/')"],
 ['other file',"$v.Mounts[3].Source+='-wrong'"],
 ['other domain mapping',"$v.Mounts[3].Source=$v.Mounts[3].Source.Replace('/consultation/','/enforcement/')"],
 ['unmapped Windows actual mount',"$v.Mounts[3].Source=$v.HostConfig.Mounts[3].Source"],
 ['missing actual mount',"$v.Mounts=@($v.Mounts|Where-Object Destination -CNE '/product/refs/consultation/key.p12')"],
 ['writable actual mount',"$v.Mounts[3].RW=$true"],
 ['wrong HostConfig source',"$v.HostConfig.Mounts[3].Source='/run/desktop/mnt/host/c/unapproved/key.p12'"],
 ['writable HostConfig mount',"$v.HostConfig.Mounts[3].ReadOnly=$false"]
])test(`actual inspect refuses ${name} and restores environment`,()=>{const setup=wrapDocker(`if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -cne ('d'*64)){$v=$value|ConvertFrom-Json -AsHashtable;${body};$value=$v|ConvertTo-Json -Depth 25}`)+"\n[Environment]::SetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','PREEXISTING_VALUE','Process')";const r=run(windowsReferences(),{start:true,setup});assert.equal(r.status,'REFUSED');assert.equal(r.environmentRestored,true);assert.equal(r.unsetEnvironmentRestored,true);assert.equal(r.calls.filter(a=>a[0]==='create').length,3);assert.equal(r.calls.filter(a=>a[0]==='start').length,0);});
test('HostConfig translated source and actual mapped source identify the same protected file',()=>{const setup=wrapDocker("if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -cne ('d'*64)){$v=$value|ConvertFrom-Json -AsHashtable;foreach($m in $v.HostConfig.Mounts){if($m.Source -cmatch '^([A-Za-z]):/(.*)$'){$m.Source='/run/desktop/mnt/host/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2]}};$value=$v|ConvertTo-Json -Depth 25}");const r=run(windowsReferences(),{start:true,setup});assert.equal(r.status,'OK',r.message);});
test('direct reference create failure restores environment and never copies a reference',()=>{const r=run(windowsReferences(),{start:true,failCreate:2,setup:"[Environment]::SetEnvironmentVariable('SINGLE_DB_CONPROD_SVC_PASSWORD','PREEXISTING_VALUE','Process')"});assert.equal(r.status,'REFUSED');assert.equal(r.environmentRestored,true);assert.equal(r.unsetEnvironmentRestored,true);assert.equal(r.calls.filter(a=>a[0]==='create').length,2);assert.equal(r.calls.filter(a=>a[0]==='start').length,0);});
test('ordinary test file SHA, directory and parent-reparse gates remain enforced without private-root access',()=>{
 const localRoot=path.join(work,'local-reference-root');mkdirSync(path.join(localRoot,'consultation','certs'),{recursive:true});
 const localFile=path.join(localRoot,'consultation','certs','public-test-marker.txt');writeFileSync(localFile,'PUBLIC_NON_SECRET_REFERENCE_MARKER');const good=ref(localFile),bad={...good,sha256:'b'.repeat(64)};
 const localDirectory=path.join(localRoot,'consultation','certs');const link=path.join(localRoot,'consultation','junction');
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker {throw 'REAL_DOCKER_FORBIDDEN'}
$r=@{path=${q(good.path)};sha256=${q(good.sha256)};containerSource=${q(good.path)};target='/product/refs/consultation/marker.txt'}
$binding=Get-FinalReferenceBinding $r consultation ${q(localRoot)};Assert-FinalFile $r
$failed=0
foreach($candidate in @(@{path=${q(bad.path)};sha256=${q(bad.sha256)}},@{path=${q(localDirectory)};sha256=${q(good.sha256)}})){try {Assert-FinalFile $candidate} catch {$failed++}}
New-Item -ItemType Junction -Path ${q(link)} -Target ${q(localDirectory)}|Out-Null
try {$redirect=@{path=${q(path.join(link,'public-test-marker.txt'))};sha256=${q(good.sha256)}};try {Assert-FinalFile $redirect} catch {$failed++};try {Assert-FinalFile @{path=${q(link)};sha256=${q(good.sha256)}}} catch {$failed++}} finally {Remove-Item -LiteralPath ${q(link)} -Force}
if($failed -ne 4){throw 'REFERENCE_FILE_GATE_FAILED'}
Write-Output 'OWNED_PUBLIC_REFERENCE_FILE_GATES_OK'`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',cwd:root,timeout:10000});assert.equal(r.status,0,r.stdout+r.stderr);assert.equal(r.stdout.trim(),'OWNED_PUBLIC_REFERENCE_FILE_GATES_OK');
});
// Synthetic translated-original transport only; real physical file proof is recorded separately.
const physicalMock="function Get-FinalPhysicalWindowsReference($r,$root) {return (Get-FinalWindowsReferencePath $r.path).Replace('/AppData/Local/AgentPlatform/','/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/')}";
test('resolved physical reference create and inspect retain same readonly original',()=>{
 const c=windowsReferences(),r=run(c,{start:true,setup:physicalMock});assert.equal(r.status,'OK',r.message);
 const creates=r.calls.filter(a=>a[0]==='create');assert.equal(creates.length,3);assert.equal(r.calls.filter(a=>a[0]==='start').length,3);
 for(const [n,args] of creates.entries())for(const m of c.applications[n].mounts){const physical=m.path.replace('/AppData/Local/AgentPlatform/','/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/');assert.ok(args.includes(`type=bind,source=${physical},target=${m.target},readonly`));assert.ok(!args.includes(`type=bind,source=${m.path},target=${m.target},readonly`));}
 assert.ok(!r.calls.some(a=>['rename','rm','restart'].includes(a[0])));
});
test('resolved physical reference rejects old shadow mount before start',()=>{
 const drift=wrapDocker("if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -cne ('d'*64)){$v=$value|ConvertFrom-Json -AsHashtable;foreach($m in $v.Mounts){$m.Source=$m.Source.Replace('/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/','/AppData/Local/AgentPlatform/')};$value=$v|ConvertTo-Json -Depth 25}");
 const r=run(windowsReferences(),{start:true,setup:physicalMock+'\n'+drift});assert.equal(r.status,'REFUSED');assert.equal(r.calls.filter(a=>a[0]==='start').length,0);
});
test.after(()=>{const target=path.resolve(work);assert.ok(target.startsWith(evidence+path.sep));assert.match(path.basename(target),/^test-temp-/);rmSync(target,{recursive:true,force:true});});









// Real function with Docker tripwire; these are policy units, not application startup evidence.
function runNoModelPolicy(domain,changes={},remove=[]) {
 const settings={'product.consultation.model.enabled':false,'product.consultation.comparison.calculation.enabled':false,'product.source.engine.enabled':true,'product.source.semantic.enabled':false,'product.source.field-reranker.enabled':false,'adjudication.v2.materials.enabled':false,'adjudication.v2.materials.decisions.enabled':false,...changes};
 for(const k of remove)delete settings[k];
 const payload=Buffer.from(JSON.stringify(settings)).toString('base64');
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};$s=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${payload}'))|ConvertFrom-Json -AsHashtable;try {Assert-FinalNoModelRuntime '${domain}' $s;Write-Output 'ALLOWED_NO_MODEL'}catch{Write-Output $_.Exception.Message;exit 1}`;
 return spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',cwd:root,timeout:10000});
}
for(const domain of ['consultation','enforcement','adjudication'])test('explicit no-model branches accepted: '+domain,()=>{const r=runNoModelPolicy(domain);assert.equal(r.status,0,r.stdout+r.stderr);assert.equal(r.stdout.trim(),'ALLOWED_NO_MODEL');});
for(const [domain,key] of [['consultation','product.source.semantic.enabled'],['consultation','product.source.field-reranker.enabled'],['consultation','product.consultation.comparison.calculation.enabled'],['adjudication','adjudication.v2.materials.enabled'],['adjudication','adjudication.v2.materials.decisions.enabled'],['enforcement','product.consultation.model.enabled']])test('real model branch cannot pass generic-name gap: '+key,()=>{const change={[key]:true};if(key==='adjudication.v2.materials.decisions.enabled')change['adjudication.v2.materials.enabled']=true;const r=runNoModelPolicy(domain,change);assert.equal(r.status,1);assert.match(r.stdout,/FINAL_MODEL_ADMISSION_NOT_GRANTED/);});
test('missing branch cannot silently default off',()=>{const r=runNoModelPolicy('consultation',{},['product.source.semantic.enabled']);assert.equal(r.status,1);assert.match(r.stdout,/FINAL_EXPLICIT_MODEL_BRANCH_REQUIRED/);});
test('decision branch cannot outlive material provider branch',()=>{const r=runNoModelPolicy('adjudication',{'adjudication.v2.materials.decisions.enabled':true});assert.equal(r.status,1);assert.match(r.stdout,/FINAL_MODEL_DEPENDENCY_INVALID/);});
test('unknown future provider enabled still denied',()=>{const r=runNoModelPolicy('enforcement',{'future.deepseek.enabled':true});assert.equal(r.status,1);assert.match(r.stdout,/FINAL_MODEL_ADMISSION_NOT_GRANTED/);});

// Synthetic negative boundary inputs; never product execution evidence.
for(const [name,mutation] of [
 ['extra host bind',"$v.HostConfig.Mounts+=@{Type='bind';Source='/other';Target='/other';ReadOnly=$true}"],
 ['extra observed bind',"$v.Mounts+=@{Type='bind';Source='/other';Destination='/other';RW=$false}"],
 ['writable owned entry',"$v.HostConfig.Mounts[2].ReadOnly=$false"],
 ['observed writable entry',"$v.Mounts[2].RW=$true"],
 ['different entry source',"$v.HostConfig.Mounts[2].Source='/other.py'"],
 ['different observed entry source',"$v.Mounts[2].Source='/other.py'"],
 ['missing owned entry',"$v.HostConfig.Mounts=$v.HostConfig.Mounts[0..1]"],
 ['wrong entrypoint',"$v.Config.Entrypoint=@('sh')"],
 ['wrong entry command',"$v.Config.Cmd=@('/other.py')"],
 ['wrong data mount type',"$v.HostConfig.Mounts[0].Type='bind'"],
 ['readonly data',"$v.HostConfig.Mounts[0].ReadOnly=$true"],
 ['cross-volume observed data',"$v.Mounts[0].Name='other'"],
 ['swapped subpaths',"$v.HostConfig.Mounts[0].VolumeOptions.Subpath='logs'"],
 ['duplicate observed target',"$v.Mounts[1].Destination='/opt/dmdbms/data'"],
 ['observed readonly data',"$v.Mounts[0].RW=$false"]
])test('native mount boundary refuses '+name,()=>{const r=run(base,{start:true,setup:wrapDocker("if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -ceq ('d'*64)){$v=$value|ConvertFrom-Json -AsHashtable;"+mutation+";$value=$v|ConvertTo-Json -Depth 20}")});assert.equal(r.status,'REFUSED');assert.equal(r.calls.filter(a=>['create','start'].includes(a[0])).length,0);});
for(const [name,mutation] of [
 ['missing domain alias',"$v.NetworkSettings.Networks[$config.network].Aliases=@()"],
 ['foreign domain alias',"$v.NetworkSettings.Networks[$config.network].Aliases=@('foreign.agent-platform.internal')"],
 ['additional domain alias',"$v.NetworkSettings.Networks[$config.network].Aliases+=@('foreign.agent-platform.internal')"],
 ['wrong network identity',"$v.NetworkSettings.Networks[$config.network].NetworkID=('e'*64)"],
 ['extra network',"$v.NetworkSettings.Networks.other=@{NetworkID=('e'*64)}"]
])test('created network refuses '+name+' before start',()=>{const r=run(base,{start:true,setup:wrapDocker("if($Arguments[0] -ceq 'inspect' -and $Arguments[1] -cne ('d'*64)){$v=$value|ConvertFrom-Json -AsHashtable;"+mutation+";$value=$v|ConvertTo-Json -Depth 20}")+"\n$script:originalCreatedAssert=${function:Assert-FinalCreatedApplication}; function Assert-FinalCreatedApplication($Id,$App,$Config){try{& $script:originalCreatedAssert $Id $App $Config}catch{$script:rawCreatedFailure=$_.Exception.Message;throw}}"});assert.equal(r.status,'REFUSED');assert.equal(r.message,'FINAL_DEPLOYMENT_REFUSED_OR_PARTIAL_PRESERVED');assert.equal(r.rawCreatedFailure,'FINAL_CREATED_NETWORK_ALIAS_DRIFT');assert.equal(r.calls.filter(a=>a[0]==='start').length,0);});
test('HANDOFF_ONLY compiler enables existing start with exact source evidence but no calculation',()=>{const c=compiled.manifest.configurations.find(c=>c.domain==='consultation');assert.equal(c.settings['product.consultation.comparison.start.enabled'],true);assert.equal(c.settings['product.consultation.comparison.calculation.enabled'],false);const f=template.applications.find(a=>a.domain==='consultation').fields.find(f=>f.property==='product.consultation.comparison.start.enabled');assert.equal(f.sourceReferences.length,3);for(const r of f.sourceReferences){assert.equal(hash(readFileSync(r.path)),r.sha256);assert.match(readFileSync(r.path,'utf8').split(/\r?\n/)[r.line-1],/product.consultation.comparison.start.enabled/);}});

// Phased startup is a real launcher branch; the inputs and Docker below are synthetic tests only.
for(const domains of [['consultation'],['enforcement'],['adjudication'],['adjudication','consultation']]){
 test('phased approved subset '+domains.join(','),()=>{
  const c=structuredClone(base);c.applications=domains.map(d=>structuredClone(base.applications.find(a=>a.domain===d)));
  const dry=run(c);assert.equal(dry.status,'OK',dry.message);assert.equal(dry.calls.length,0);assert.equal(dry.secretPaths.length,0);
  const r=run(c,{start:true});assert.equal(r.status,'OK',r.message);
  const creates=r.calls.filter(a=>a[0]==='create');assert.equal(creates.length,domains.length);assert.equal(r.calls.filter(a=>a[0]==='start').length,domains.length);
  const ordered=base.applications.filter(a=>domains.includes(a.domain));
  for(const [i,args] of creates.entries()){const n=base.applications.findIndex(a=>a.domain===ordered[i].domain);assert.ok(args.includes((10001+n)+':'+(10001+n)));assert.ok(args.includes('127.0.0.1:'+(5446+n)+':8443'));assert.equal(args[args.indexOf('--name')+1],ordered[i].name);}
  const expected=ordered.flatMap(a=>Object.values(a.secrets).map(r=>r.path)).sort();assert.deepEqual([...r.secretPaths].sort(),expected);
  assert.ok(!r.calls.some(a=>['stop','rm','restart','exec'].includes(a[0])));assert.equal(r.result.businessVerified,false);assert.equal(r.result.modelAdmissionGranted,false);
 });
}
for(const [name,f] of [
 ['stale full inventory',c=>{c.inventoryReceipt=save('stale.json',{...inventoryEvidence,capturedAt:'2026-10-08T19:01:00Z'});} ],
 ['incomplete full inventory',c=>{c.inventoryReceipt=save('incomplete.json',{...inventoryEvidence,complete:false});}],
 ['missing unselected storage directory',c=>{const i=structuredClone(init);delete i.storage.files.directories.enforcement;c.initializationReceipt=save('missing-storage.json',i);}],
 ['wrong fixed domain UID',c=>c.applications[0].uid=10001]
])test('phased subset still refuses '+name,()=>{const c=structuredClone(base);c.applications=[structuredClone(base.applications[2])];f(c);const r=run(c,{start:true,clock:name==='stale full inventory'?'2026-10-08T19:30:00Z':undefined});assert.equal(r.status,'REFUSED');assert.equal(r.calls.length,0);assert.equal(r.secretPaths.length,0);});
