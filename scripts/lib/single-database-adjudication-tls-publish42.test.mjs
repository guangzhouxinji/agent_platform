import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import path from 'node:path';
const root=process.cwd(),lib=path.join(root,'scripts/lib/single-database-adjudication-tls-publish42.ps1');
const cli=readFileSync(path.join(root,'scripts/start-single-database-applications.ps1'),'utf8');
const q=s=>`'${s.replaceAll("'","''")}'`;
const fixtureManifest=`local-publish-test-42-${process.pid}`;
function shell(code){const text=`$ErrorActionPreference='Stop';. ${q(path.join(root,'scripts/lib/single-database-final-deployment.ps1'))};. ${q(lib)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};${code}`;
const r=spawnSync('pwsh',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(text,'utf16le').toString('base64')],{encoding:'utf8',timeout:20000});assert.ifError(r.error);assert.equal(r.status,0,r.stdout+r.stderr);return JSON.parse(r.stdout);}
function state({has=false,unknown=false,existsAfterUnknown=true,created='c'.repeat(64),running=false,mutateAt='',drift=false,validate=false,stop=false}={}){
return shell(`$script:events=[Collections.Generic.List[object]]::new();$script:created=$${has?'true':'false'};$script:dual=$${has?'true':'false'};$script:running=$${running?'true':'false'};$script:windows=0;$script:guards=0
$p=@{config=@{publisherName='publisher';targetId=('a'*64)};sha=('f'*64);deployment=@{manifestSha256=${q(fixtureManifest)};networkId=('b'*64)};approval=@{scope=@{}}}
function Get-Adj42LocalTlsPublicationPlan {return $p}
function Get-Adj42LocalTlsCreateArguments {return @('create','--name','publisher')}
function Assert-Adj42LocalTlsWindow {$script:windows++;if(${q(mutateAt)} -ceq ('window'+$script:windows)){throw 'WINDOW_EXPIRED'}}
function Assert-Adj42LocalTlsTarget {$script:guards++;if(${q(mutateAt)} -ceq ('guard'+$script:guards)){throw 'TARGET_CHANGED'};if(${q(mutateAt)} -ceq ('exitGuard'+$script:guards)){$script:running=$false};return @{targetIp='172.25.0.4';budget=@{}}}
function Assert-Adj42LocalTlsBudget {return @{memoryBytes=5502926848;nanoCpus=2800000000}}
function Assert-Adj42LocalTlsPublisher {param($plan,$id,[switch]$Running,[switch]$BridgeOnly);if($id -ceq ('a'*64) -or ${drift?'$true':'$false'}){throw 'PUBLISHER_DRIFT'};if($Running -and -not $script:running){throw 'LATE_EXIT_REFUSED'};return (Get-Adj42LocalTlsInspect $id)}
function Get-Adj42LocalTlsInspect {param($id);return @{Id=${q(created)};State=@{Status=$(if($script:running){'running'}elseif($script:created){'created'}else{'exited'});Running=$script:running;Pid=$(if($script:running){1}else{0})};NetworkSettings=@{Networks=$(if($script:dual){@{bridge=@{};internal=@{}}}else{@{bridge=@{}}})}}}
function Invoke-FinalDocker {param($a);$script:events.Add(@($a));switch($a[0]){
'ps'{if($script:created){return @('publisher')};return @()};
'create'{$script:created=$${existsAfterUnknown?'true':'false'};${unknown?"throw 'UNKNOWN_CREATE'":`return ${q(created)}`}};
'network'{$script:dual=$true;return ''};'start'{$script:running=$true;return ''};'stop'{$script:running=$false;return ''};default{throw 'UNEXPECTED_MUTATION'}}}
$out=try{${stop?`$r=Stop-FinalAdj42LocalTlsPublisher @{} ('f'*64) ${q(created)}`:`$r=Invoke-FinalAdj42LocalTlsPublisher @{targetId=('a'*64)} ('f'*64) ${validate?'-ValidateOnly':''}`};@{status='OK';result=$r;events=@($script:events)}}catch{@{status='REFUSED';code=$_.Exception.Message;events=@($script:events)}};$out|ConvertTo-Json -Depth 12`);}
test('existing CLI mutually excludes publication and recovery modes',()=>{assert.match(cli,/PublishLocalTls/);assert.match(cli,/StopLocalTls/);assert.match(cli,/\$ResumeCreatedContainer -or \$RecoverFailedApplication -or \$ResumeFailedContainer -or \$PrepareEvidenceDirectory/);assert.match(cli,/Invoke-FinalAdj42LocalTlsPublisher/);assert.match(cli,/Stop-FinalAdj42LocalTlsPublisher/);});
test('preflight cannot mutate any resource',()=>{const r=state({validate:true});assert.equal(r.status,'OK',JSON.stringify(r));assert.equal(r.events.some(a=>['create','network','start','stop'].includes(a[0])),false);});
test('one exact role is created connected and started once',()=>{const r=state();assert.equal(r.status,'OK');assert.deepEqual(r.events.map(a=>a[0]),['ps','create','network','start']);assert.equal(r.result.targetId,'a'.repeat(64));});
test('already running exact role no duplicate or restart',()=>{const r=state({has:true,running:true});assert.equal(r.status,'OK');assert.equal(r.events.some(a=>['create','network','start','restart'].includes(a[0])),false);});
test('unknown create result is preserved and never blindly retried',()=>{const r=state({unknown:true});assert.equal(r.status,'REFUSED');assert.equal(r.code,'LOCAL_TLS_CREATE_RESULT_UNKNOWN_RECONCILE_REQUIRED');assert.equal(r.events.filter(a=>a[0]==='create').length,1);assert.equal(r.events.some(a=>['start','network','rm'].includes(a[0])),false);});
test('unknown create without resource no alternate created',()=>{const r=state({unknown:true,existsAfterUnknown:false});assert.equal(r.status,'REFUSED');assert.equal(r.events.filter(a=>a[0]==='create').length,1);});
for(const point of ['window1','window2','window3','guard1','guard2'])test(`side effect admission refuses ${point}`,()=>{const r=state({mutateAt:point});assert.equal(r.status,'REFUSED');assert.equal(r.events.some(a=>['restart','rm','rename'].includes(a[0])),false);if(point==='window1'||point==='guard1')assert.equal(r.events.some(a=>a[0]==='create'),false);});
test('existing role immutable drift refuses before start',()=>{const r=state({has:true,drift:true});assert.equal(r.status,'REFUSED');assert.equal(r.events.some(a=>a[0]==='start'),false);});
test('reversible stop addresses publisher ID only',()=>{const r=state({has:true,running:true,stop:true});assert.equal(r.status,'OK');assert.deepEqual(r.events,[['stop','--time','10','c'.repeat(64)]]);});
test('stop never targets original application',()=>{const r=state({has:true,running:true,stop:true,created:'a'.repeat(64)});assert.equal(r.status,'REFUSED');assert.equal(r.events.some(a=>a[0]==='stop'),false);});
for(const [label,from] of [['before','2026-10-08T18:59:59Z'],['expired','2026-10-10T10:00:00Z']])test(`absolute window refuses ${label}`,()=>{const r=shell(`function Get-FinalDeploymentUtc{return [DateTimeOffset]::Parse(${q(from)})};$r=try{Assert-Adj42LocalTlsWindow @{scope=@{from='2026-10-09T03:00:00+08:00';until='2026-10-10T18:00:00+08:00'}};'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);assert.equal(r.result,'REFUSED');});
test('absolute window allows UTC-equivalent Beijing start',()=>{const r=shell(`function Get-FinalDeploymentUtc{return [DateTimeOffset]::Parse('2026-10-08T19:00:00Z')};Assert-Adj42LocalTlsWindow @{scope=@{from='2026-10-09T03:00:00+08:00';until='2026-10-10T18:00:00+08:00'}};@{result='OK'}|ConvertTo-Json`);assert.equal(r.result,'OK');});
test('strict dictionary rejects additions and missing fields',()=>{for(const obj of ['@{a=1;b=2}','@{}']){const r=shell(`$r=try{Assert-Adj42LocalTlsFields ${obj} @('a');'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);assert.equal(r.result,'REFUSED');}});
test('launch contains no secret mounts and pins locality and limits',()=>{const r=shell(`$p=@{config=@{publisherName='publisher';bridgeId='bridge';targetId='target'};sha='sha';jarSource='D:/relay.jar';runtimeSource='D:/runtime.properties';deployment=@{image='image'};launch='byte-relay'};@{args=@(Get-Adj42LocalTlsCreateArguments $p)}|ConvertTo-Json -Depth 6`);assert.ok(r.args.includes('127.0.0.1:5448:8443'));assert.ok(r.args.includes('net.ipv4.ip_forward=0'));assert.ok(r.args.includes('net.ipv6.conf.all.forwarding=0'));assert.equal(r.args.filter(x=>x==='--mount').length,2);assert.ok(!r.args.some(x=>/p12|credential|socket|product\/files/.test(x)));assert.ok(r.args.includes('128m'));assert.ok(r.args.includes('0.05'));assert.ok(r.args.includes('10012:10012'));});

function inspectCase(change='',{created=false}={}){
return shell(`$p=@{config=@{publisherName='publisher';targetId=('a'*64);bridgeId='bridge-id'};sha=('f'*64);deployment=@{image='image';network='internal';networkId='internal-id'};jarSource='D:/relay.jar';runtimeSource='D:/runtime.properties';launch='byte-relay'}
$a=@{Id=('c'*64);Name='/publisher';Image='image';State=@{Running=$true;Status='running';Pid=9};Config=@{User='10012:10012';Labels=@{task='IMP-SINGLE-DB-FINAL-BASELINE-01';'agent-platform.role'='adjudication-local-tls-publisher';'agent-platform.domain'='adjudication';'agent-platform.target-id'= ('a'*64);'agent-platform.publication-sha256'= ('f'*64)};Entrypoint=@('sh');Cmd=@('-c','byte-relay');Env=@('PATH=original')};
HostConfig=@{Privileged=$false;ReadonlyRootfs=$true;CapDrop=@('ALL');CapAdd=$null;SecurityOpt=@('no-new-privileges');Memory=134217728;NanoCpus=50000000;PidsLimit=64;RestartPolicy=@{Name='no'};NetworkMode='bridge-id';Sysctls=@{'net.ipv4.ip_forward'='0';'net.ipv6.conf.all.forwarding'='0'};Mounts=@(@{Target='/publisher/relay.jar';Source='D:/relay.jar';Type='bind';ReadOnly=$true},@{Target='/publisher/runtime.properties';Source='D:/runtime.properties';Type='bind';ReadOnly=$true});Tmpfs=@{'/tmp'='rw,nosuid,nodev,noexec,size=16m,uid=10012,gid=10012,mode=700'};PortBindings=@{'8443/tcp'=@(@{HostIp='127.0.0.1';HostPort='5448'})}};
Mounts=@(@{Destination='/publisher/relay.jar';Source='/run/desktop/mnt/host/d/relay.jar';Type='bind';RW=$false},@{Destination='/publisher/runtime.properties';Source='/run/desktop/mnt/host/d/runtime.properties';Type='bind';RW=$false});NetworkSettings=@{Networks=@{bridge=@{NetworkID='bridge-id'};internal=@{NetworkID='internal-id'}};Ports=@{'8443/tcp'=@(@{HostIp='127.0.0.1';HostPort='5448'})}}}
${change}
function Get-Adj42LocalTlsInspect{return $a};function Invoke-FinalDocker {param($v);if($v[0] -ceq 'exec'){if($script:badBytes){return 'BAD_BYTES'};return 'LOCAL_TLS_RUNTIME_BYTES_OK'};return (@{Config=@{Env=@('PATH=original');Volumes=$null}}|ConvertTo-Json -Depth 9)}
$r=try{$null=Assert-Adj42LocalTlsPublisher $p ('c'*64) ${created?'':'-Running'};'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);}
test('actual runtime guard accepts exact limited role without CapAdd',()=>assert.equal(inspectCase().result,'OK'));
for(const [label,change] of [
['extra cap',"$a.HostConfig.CapAdd=@('NET_ADMIN')"],['privileged',"$a.HostConfig.Privileged=$true"],['forwarding',"$a.HostConfig.Sysctls.'net.ipv4.ip_forward'='1'"],
['outward host port',"$a.HostConfig.PortBindings['8443/tcp'][0].HostIp='0.0.0.0'"],['missing actual port',"$a.NetworkSettings.Ports['8443/tcp']=@()"],
['secret env',"$a.Config.Env+=@('SINGLE_DB_SECRET=never-print')"],['private mount',"$a.HostConfig.Mounts+=@{Target='/key.p12'}"],['writable reference',"$a.Mounts[0].RW=$true"],
['third network',"$a.NetworkSettings.Networks['extra']=@{NetworkID='other'}"],['wrong target',"$a.Config.Labels.'agent-platform.target-id'=('b'*64)"],
['wrong command',"$a.Config.Cmd=@('-c','anything-else')"],['resource expansion',"$a.HostConfig.Memory=268435456"]])test(`actual runtime guard refuses ${label}`,()=>assert.equal(inspectCase(change).result,'REFUSED'));
function planCase(change=''){
 const evidence=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/adjudication-runtime40');
 return shell(`$script:config=Get-Content -LiteralPath ${q(path.join(evidence,'publisher42/publication42.json'))} -Raw|ConvertFrom-Json -AsHashtable -DateKind String
$script:decision=Get-Content -LiteralPath $script:config.decision.path -Raw|ConvertFrom-Json -AsHashtable -DateKind String
$script:deployment=Get-Content -LiteralPath $script:config.deployment.path -Raw|ConvertFrom-Json -AsHashtable -DateKind String
function Read-FinalDeploymentJson($p){if($p -ceq $script:config.decision.path){return $script:decision};if($p -ceq $script:config.deployment.path){return $script:deployment};return (Get-Content -LiteralPath $p -Raw|ConvertFrom-Json -AsHashtable -DateKind String)}
function Get-FinalDeploymentPlan($d){return @{applications=@(@{input=$d.applications[0]})}}
function Get-FinalApplicationLaunchCommand{return 'original-normal-adjudication-command'}
function Get-FinalPhysicalWindowsReference($r,$root){return $r.path}
function Get-FinalDeploymentUtc{return [DateTimeOffset]::Parse('2026-10-10T00:00:00Z')}
$c=@{}+$script:config
${change}
$r=try{$null=Get-Adj42LocalTlsPublicationPlan $c ('f'*64);'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);
}
test('actual delegated decision and fixed39 source profile forms plan without Docker',()=>assert.equal(planCase().result,'OK'));
for(const [label,change]of[
 ['extra option',"$c.newPurpose='other'"],['consultation schema',"$c.schema='SINGLE_DATABASE_LOCAL_TLS_PUBLICATION/v1'"],['consultation exact role',"$c.publisherName='agent-platform-single-final-consultation-publish-20261010'"],['consultation target',"$c.targetId='9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f'"],['different bridge',"$c.bridgeId=('a'*64)"],['decision SHA',"$c.decision.sha256=('a'*64)"],['user-verbatim fabrication',"$script:decision.derivedDecisionNotUserVerbatim=$false"],['wrong decision domain',"$script:decision.scope.domain='consultation'"],['outward host',"$script:decision.scope.hostIp='0.0.0.0'"],['other host port',"$script:decision.scope.hostPort=5446"],['different target port',"$script:decision.scope.targetPort=443"],['terminate TLS',"$script:decision.scope.tlsTermination=$true"],['private key',"$script:decision.scope.privateKeyReadOrCopy=$true"],['egress change',"$script:decision.scope.applicationEgressChange=$true"],['business bypass',"$script:decision.scope.businessPermissionChange=$true"],['memory increase',"$script:decision.scope.memoryBytes=268435456"],['window extension',"$script:decision.scope.until='2026-10-11T10:00:00Z'"],['old consultation jar',"$script:deployment.applications[0].jar.sha256='69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d'"],['properties drift',"$script:deployment.applications[0].properties.sha256=('a'*64)"],['source SHA drift',"$c.source.sha256=('a'*64)"],['guard SHA drift',"$c.guardSource.sha256=('a'*64)"],['foreign app domain',"$script:deployment.applications[0].domain='consultation'"]
])test('adjudication exact publication rejects '+label,()=>assert.equal(planCase(change).result,'REFUSED'));
test('CREATED exact readonly Windows sources admitted before Docker mapping',()=>assert.equal(inspectCase("$a.State=@{Status='created';Running=$false;Pid=0};$a.Mounts[0].Source='D:/relay.jar';$a.Mounts[1].Source='D:/runtime.properties'",{created:true}).result,'OK'));
test('RUNNING Windows metadata alone refuses without actual matching bytes',()=>assert.equal(inspectCase("$a.Mounts[0].Source='D:/relay.jar';$script:badBytes=$true").result,'REFUSED'));
test('RUNNING exact Windows metadata plus actual byte proof accepted',()=>assert.equal(inspectCase("$a.Mounts[0].Source='D:/relay.jar'").result,'OK'));
test('mapped running mount also refuses actual bytes mismatch',()=>assert.equal(inspectCase("$script:badBytes=$true").result,'REFUSED'));


function budgetCase(change=''){
 const e=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/adjudication-runtime40');
 return shell(`$d=Get-Content ${q(path.join(e,'publisher42/publisher-derived-decision42.json'))} -Raw|ConvertFrom-Json -AsHashtable -DateKind String
$b=Get-Content ${q(path.join(e,'application40-start-responsibility.json'))} -Raw|ConvertFrom-Json -AsHashtable -DateKind String
$p=@{config=@{targetId=$d.scope.targetId};approval=$d}
$script:roles=@{};foreach($r in $b.beforeRoles){$nets=@{};$i=0;foreach($n in $r.networkIds){$nets[('n'+$i++)]=@{NetworkID=$n}};$script:roles[$r.id]=@{State=@{Running=$true;Pid=$r.pid;StartedAt=$r.startedAt};HostConfig=@{Memory=$r.memoryBytes;NanoCpus=$r.nanoCpus};NetworkSettings=@{Networks=$nets}}}
$script:roles[$d.scope.targetId]=@{State=@{Running=$true;Pid=1};HostConfig=@{Memory=805306368;NanoCpus=500000000}}
${change}
function Assert-Adj42LocalTlsPredecessor {}
function Invoke-FinalDocker{return @($script:roles.Keys)};function Get-Adj42LocalTlsInspect($id){return $script:roles[$id]}
$r=try{$budget=Assert-Adj42LocalTlsBudget $p;'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);
}
test('known five live roles plus one reserved publisher fit original budget',()=>assert.equal(budgetCase().result,'OK'));
for(const [n,c]of[['unknown role',"$script:roles[('a'*64)]=@{}"],['memory exceeded',"$script:roles[$d.scope.targetId].HostConfig.Memory=7516192768"],['CPU exceeded',"$script:roles[$d.scope.targetId].HostConfig.NanoCpus=4000000000"],['protected PID changed',"$script:roles[$d.protectedRoles[0].id].State.Pid++"],['protected network changed',"$script:roles[$d.protectedRoles[0].id].NetworkSettings.Networks=@{}"],['database missing',"$script:roles.Remove('f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f')"],['target missing',"$script:roles.Remove($d.scope.targetId)"]])test('budget refuses '+n,()=>assert.equal(budgetCase(c).result,'REFUSED'));



test('publisher exiting during final slow target check is refused without restart/delete',()=>{const r=state({mutateAt:'exitGuard3'});assert.equal(r.status,'REFUSED');assert.equal(r.code,'LATE_EXIT_REFUSED');assert.equal(r.events.filter(a=>a[0]==='start').length,1);assert.equal(r.events.some(a=>['restart','rm','stop'].includes(a[0])),false);});
function predecessorCase(change=''){return shell(`$a=@{Id='c8247666d7f330340377392e7833879d748296bcb449cff00dce1b17594fbb36';Name='/agent-platform-single-final-adjudication-publish-20261010';State=@{Status='exited';Running=$false;Pid=0;ExitCode=1}};$b=@{Id='fcc426f1196d6111384698ba11435a316da795ecc133e31049b2c2aaef9fcf5a';Name='/agent-platform-single-final-adjudication-publish41-20261010';State=@{Status='exited';Running=$false;Pid=0;ExitCode=1}};${change};function Get-Adj42LocalTlsInspect($id){if($id -ceq 'fcc426f1196d6111384698ba11435a316da795ecc133e31049b2c2aaef9fcf5a'){return $b};return $a};$r=try{Assert-Adj42LocalTlsPredecessor;'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);}
test('original exact failed publisher stays stopped and preserved',()=>assert.equal(predecessorCase().result,'OK'));
for(const c of ["$a.State.Running=$true","$a.State.Pid=9","$a.State.ExitCode=0","$a.Id=('b'*64)"])test('failed predecessor changed refuses '+c,()=>assert.equal(predecessorCase(c).result,'REFUSED'));

for(const c of ["$b.State.Running=$true","$b.State.Pid=9","$b.State.ExitCode=0","$b.Id=('b'*64)"])test('second failed predecessor changed refuses '+c,()=>assert.equal(predecessorCase(c).result,'REFUSED'));
