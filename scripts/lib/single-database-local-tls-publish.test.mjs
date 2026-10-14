import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import path from 'node:path';
const root=process.cwd(),lib=path.join(root,'scripts/lib/single-database-local-tls-publish.ps1');
const cli=readFileSync(path.join(root,'scripts/start-single-database-applications.ps1'),'utf8');
const q=s=>`'${s.replaceAll("'","''")}'`;
function shell(code){const text=`$ErrorActionPreference='Stop';. ${q(lib)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};${code}`;
const r=spawnSync('pwsh',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(text,'utf16le').toString('base64')],{encoding:'utf8',timeout:20000});assert.ifError(r.error);assert.equal(r.status,0,r.stdout+r.stderr);return JSON.parse(r.stdout);}
function state({has=false,unknown=false,existsAfterUnknown=true,created='c'.repeat(64),running=false,mutateAt='',drift=false,validate=false,stop=false}={}){
return shell(`$script:events=[Collections.Generic.List[object]]::new();$script:created=$${has?'true':'false'};$script:dual=$${has?'true':'false'};$script:running=$${running?'true':'false'};$script:windows=0;$script:guards=0
$p=@{config=@{publisherName='publisher';targetId=('a'*64)};sha=('f'*64);deployment=@{manifestSha256='local-publish-test';networkId=('b'*64)};approval=@{scope=@{}}}
function Get-LocalTlsPublicationPlan {return $p}
function Get-LocalTlsCreateArguments {return @('create','--name','publisher')}
function Assert-LocalTlsWindow {$script:windows++;if(${q(mutateAt)} -ceq ('window'+$script:windows)){throw 'WINDOW_EXPIRED'}}
function Assert-LocalTlsTarget {$script:guards++;if(${q(mutateAt)} -ceq ('guard'+$script:guards)){throw 'TARGET_CHANGED'};return @{targetIp='172.25.0.4';budget=@{}}}
function Assert-LocalTlsBudget {return @{memoryBytes=5502926848;nanoCpus=2800000000}}
function Assert-LocalTlsPublisher {param($plan,$id,[switch]$Running,[switch]$BridgeOnly);if($id -ceq ('a'*64) -or ${drift?'$true':'$false'}){throw 'PUBLISHER_DRIFT'};return (Get-LocalTlsInspect $id)}
function Get-LocalTlsInspect {param($id);return @{Id=${q(created)};State=@{Status=$(if($script:running){'running'}elseif($script:created){'created'}else{'exited'});Running=$script:running;Pid=$(if($script:running){1}else{0})};NetworkSettings=@{Networks=$(if($script:dual){@{bridge=@{};internal=@{}}}else{@{bridge=@{}}})}}}
function Invoke-FinalDocker {param($a);$script:events.Add(@($a));switch($a[0]){
'ps'{if($script:created){return @('publisher')};return @()};
'create'{$script:created=$${existsAfterUnknown?'true':'false'};${unknown?"throw 'UNKNOWN_CREATE'":`return ${q(created)}`}};
'network'{$script:dual=$true;return ''};'start'{$script:running=$true;return ''};'stop'{$script:running=$false;return ''};default{throw 'UNEXPECTED_MUTATION'}}}
$out=try{${stop?`$r=Stop-FinalLocalTlsPublisher @{} ('f'*64) ${q(created)}`:`$r=Invoke-FinalLocalTlsPublisher @{targetId=('a'*64)} ('f'*64) ${validate?'-ValidateOnly':''}`};@{status='OK';result=$r;events=@($script:events)}}catch{@{status='REFUSED';code=$_.Exception.Message;events=@($script:events)}};$out|ConvertTo-Json -Depth 12`);}
test('existing CLI mutually excludes publication and recovery modes',()=>{assert.match(cli,/PublishLocalTls/);assert.match(cli,/StopLocalTls/);assert.match(cli,/\$ResumeCreatedContainer -or \$RecoverFailedApplication -or \$ResumeFailedContainer -or \$PrepareEvidenceDirectory/);assert.match(cli,/Invoke-FinalLocalTlsPublisher/);assert.match(cli,/Stop-FinalLocalTlsPublisher/);});
test('preflight cannot mutate any resource',()=>{const r=state({validate:true});assert.equal(r.status,'OK');assert.equal(r.events.some(a=>['create','network','start','stop'].includes(a[0])),false);});
test('one exact role is created connected and started once',()=>{const r=state();assert.equal(r.status,'OK');assert.deepEqual(r.events.map(a=>a[0]),['ps','create','network','start']);assert.equal(r.result.targetId,'a'.repeat(64));});
test('already running exact role no duplicate or restart',()=>{const r=state({has:true,running:true});assert.equal(r.status,'OK');assert.equal(r.events.some(a=>['create','network','start','restart'].includes(a[0])),false);});
test('unknown create result is preserved and never blindly retried',()=>{const r=state({unknown:true});assert.equal(r.status,'REFUSED');assert.equal(r.code,'LOCAL_TLS_CREATE_RESULT_UNKNOWN_RECONCILE_REQUIRED');assert.equal(r.events.filter(a=>a[0]==='create').length,1);assert.equal(r.events.some(a=>['start','network','rm'].includes(a[0])),false);});
test('unknown create without resource no alternate created',()=>{const r=state({unknown:true,existsAfterUnknown:false});assert.equal(r.status,'REFUSED');assert.equal(r.events.filter(a=>a[0]==='create').length,1);});
for(const point of ['window1','window2','window3','guard1','guard2'])test(`side effect admission refuses ${point}`,()=>{const r=state({mutateAt:point});assert.equal(r.status,'REFUSED');assert.equal(r.events.some(a=>['restart','rm','rename'].includes(a[0])),false);if(point==='window1'||point==='guard1')assert.equal(r.events.some(a=>a[0]==='create'),false);});
test('existing role immutable drift refuses before start',()=>{const r=state({has:true,drift:true});assert.equal(r.status,'REFUSED');assert.equal(r.events.some(a=>a[0]==='start'),false);});
test('reversible stop addresses publisher ID only',()=>{const r=state({has:true,running:true,stop:true});assert.equal(r.status,'OK');assert.deepEqual(r.events,[['stop','--time','10','c'.repeat(64)]]);});
test('stop never targets original application',()=>{const r=state({has:true,running:true,stop:true,created:'a'.repeat(64)});assert.equal(r.status,'REFUSED');assert.equal(r.events.some(a=>a[0]==='stop'),false);});
for(const [label,from] of [['before','2026-10-09T16:52:52Z'],['expired','2026-10-10T10:00:00Z']])test(`absolute window refuses ${label}`,()=>{const r=shell(`function Get-FinalDeploymentUtc{return [DateTimeOffset]::Parse(${q(from)})};$r=try{Assert-LocalTlsWindow @{scope=@{from='2026-10-10T00:52:53+08:00';until='2026-10-10T18:00:00+08:00'}};'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);assert.equal(r.result,'REFUSED');});
test('absolute window allows UTC-equivalent Beijing start',()=>{const r=shell(`function Get-FinalDeploymentUtc{return [DateTimeOffset]::Parse('2026-10-09T16:52:53Z')};Assert-LocalTlsWindow @{scope=@{from='2026-10-10T00:52:53+08:00';until='2026-10-10T18:00:00+08:00'}};@{result='OK'}|ConvertTo-Json`);assert.equal(r.result,'OK');});
test('strict dictionary rejects additions and missing fields',()=>{for(const obj of ['@{a=1;b=2}','@{}']){const r=shell(`$r=try{Assert-LocalTlsFields ${obj} @('a');'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);assert.equal(r.result,'REFUSED');}});
test('launch contains no secret mounts and pins locality and limits',()=>{const r=shell(`$p=@{config=@{publisherName='publisher';bridgeId='bridge';targetId='target'};sha='sha';jarSource='D:/relay.jar';runtimeSource='D:/runtime.properties';deployment=@{image='image'};launch='byte-relay'};@{args=@(Get-LocalTlsCreateArguments $p)}|ConvertTo-Json -Depth 6`);assert.ok(r.args.includes('127.0.0.1:5446:8443'));assert.ok(r.args.includes('net.ipv4.ip_forward=0'));assert.ok(r.args.includes('net.ipv6.conf.all.forwarding=0'));assert.equal(r.args.filter(x=>x==='--mount').length,2);assert.ok(!r.args.some(x=>/p12|credential|socket|product\/files/.test(x)));assert.ok(r.args.includes('128m'));assert.ok(r.args.includes('0.05'));assert.ok(r.args.includes('10010:10010'));});

function inspectCase(change='',{created=false}={}){
return shell(`$p=@{config=@{publisherName='publisher';targetId=('a'*64);bridgeId='bridge-id'};sha=('f'*64);deployment=@{image='image';network='internal';networkId='internal-id'};jarSource='D:/relay.jar';runtimeSource='D:/runtime.properties';launch='byte-relay'}
$a=@{Id=('c'*64);Name='/publisher';Image='image';State=@{Running=$true;Status='running';Pid=9};Config=@{User='10010:10010';Labels=@{task='IMP-SINGLE-DB-FINAL-BASELINE-01';'agent-platform.role'='consultation-local-tls-publisher';'agent-platform.domain'='consultation';'agent-platform.target-id'= ('a'*64);'agent-platform.publication-sha256'= ('f'*64)};Entrypoint=@('sh');Cmd=@('-c','byte-relay');Env=@('PATH=original')};
HostConfig=@{Privileged=$false;ReadonlyRootfs=$true;CapDrop=@('ALL');CapAdd=$null;SecurityOpt=@('no-new-privileges');Memory=134217728;NanoCpus=50000000;PidsLimit=64;RestartPolicy=@{Name='no'};NetworkMode='bridge-id';Sysctls=@{'net.ipv4.ip_forward'='0';'net.ipv6.conf.all.forwarding'='0'};Mounts=@(@{Target='/publisher/relay.jar';Source='D:/relay.jar';Type='bind';ReadOnly=$true},@{Target='/publisher/runtime.properties';Source='D:/runtime.properties';Type='bind';ReadOnly=$true});Tmpfs=@{'/tmp'='rw,nosuid,nodev,noexec,size=16m,uid=10010,gid=10010,mode=700'};PortBindings=@{'8443/tcp'=@(@{HostIp='127.0.0.1';HostPort='5446'})}};
Mounts=@(@{Destination='/publisher/relay.jar';Source='/run/desktop/mnt/host/d/relay.jar';Type='bind';RW=$false},@{Destination='/publisher/runtime.properties';Source='/run/desktop/mnt/host/d/runtime.properties';Type='bind';RW=$false});NetworkSettings=@{Networks=@{bridge=@{NetworkID='bridge-id'};internal=@{NetworkID='internal-id'}};Ports=@{'8443/tcp'=@(@{HostIp='127.0.0.1';HostPort='5446'})}}}
${change}
function Get-LocalTlsInspect{return $a};function Invoke-FinalDocker {param($v);if($v[0] -ceq 'exec'){if($script:badBytes){return 'BAD_BYTES'};return 'LOCAL_TLS_RUNTIME_BYTES_OK'};return (@{Config=@{Env=@('PATH=original');Volumes=$null}}|ConvertTo-Json -Depth 9)}
$r=try{$null=Assert-LocalTlsPublisher $p ('c'*64) ${created?'':'-Running'};'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);}
test('actual runtime guard accepts exact limited role without CapAdd',()=>assert.equal(inspectCase().result,'OK'));
for(const [label,change] of [
['extra cap',"$a.HostConfig.CapAdd=@('NET_ADMIN')"],['privileged',"$a.HostConfig.Privileged=$true"],['forwarding',"$a.HostConfig.Sysctls.'net.ipv4.ip_forward'='1'"],
['outward host port',"$a.HostConfig.PortBindings['8443/tcp'][0].HostIp='0.0.0.0'"],['missing actual port',"$a.NetworkSettings.Ports['8443/tcp']=@()"],
['secret env',"$a.Config.Env+=@('SINGLE_DB_SECRET=never-print')"],['private mount',"$a.HostConfig.Mounts+=@{Target='/key.p12'}"],['writable reference',"$a.Mounts[0].RW=$true"],
['third network',"$a.NetworkSettings.Networks['extra']=@{NetworkID='other'}"],['wrong target',"$a.Config.Labels.'agent-platform.target-id'=('b'*64)"],
['wrong command',"$a.Config.Cmd=@('-c','anything-else')"],['resource expansion',"$a.HostConfig.Memory=268435456"]])test(`actual runtime guard refuses ${label}`,()=>assert.equal(inspectCase(change).result,'REFUSED'));
function budgetCase({foreign=false,unbounded=false,tooLarge=false}={}){return shell(`$p=@{config=@{targetId=('a'*64)}};function Invoke-FinalDocker{return @(('a'*64),'f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f'${foreign?",('b'*64)":''})};function Get-LocalTlsInspect{return @{State=@{Running=$true};HostConfig=@{Memory=${unbounded?0:tooLarge?7516192768:805306368};NanoCpus=500000000}}};$r=try{$null=Assert-LocalTlsBudget $p;'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);}
test('actual cumulative budget admits limited role',()=>assert.equal(budgetCase().result,'OK'));
for(const [label,args] of [['unknown writer',{foreign:true}],['unlimited resource',{unbounded:true}],['sum exceeds',{tooLarge:true}]])test(`actual cumulative budget refuses ${label}`,()=>assert.equal(budgetCase(args).result,'REFUSED'));

function planCase(change=''){
const evidence=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33');
const old=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/consultation-deployment32.json');
return shell(`function Read-FinalDeploymentJson($p){if($p -ceq 'mock-deployment'){return $script:deployment};if($p -ceq ${q(path.join(evidence,'approval.json'))}){return $script:approval};return (Get-Content -LiteralPath $p -Raw|ConvertFrom-Json -AsHashtable)}
function Assert-FinalFile($r){if($r.path -ceq 'mock-deployment'){return};if((Get-FileHash -LiteralPath $r.path).Hash.ToLowerInvariant() -cne $r.sha256){throw 'FILE_SHA_MISMATCH'}}
function Get-FinalDeploymentUtc{return [DateTimeOffset]::Parse('2026-10-09T17:30:00Z')}
function Get-FinalWindowsReferencePath($p){return [IO.Path]::GetFullPath($p).Replace([char]92,[char]47)}
function Get-FinalPhysicalWindowsReference($r,$root){return $r.path}
function Get-FinalApplicationLaunchCommand{return 'original-fixed-command'}
function Get-FinalDeploymentPlan($d){return @{applications=@(@{input=$d.applications[0]})}}
function ref($p){return @{path=$p;sha256=(Get-FileHash -LiteralPath $p).Hash.ToLowerInvariant()}}
$script:deployment=Get-Content -LiteralPath ${q(old)} -Raw|ConvertFrom-Json -AsHashtable
$script:approval=Get-Content -LiteralPath ${q(path.join(evidence,'approval.json'))} -Raw|ConvertFrom-Json -AsHashtable
$c=@{schema='SINGLE_DATABASE_LOCAL_TLS_PUBLICATION/v1';approval=(ref ${q(path.join(evidence,'approval.json'))});deployment=@{path='mock-deployment';sha256=('a'*64)};source=(ref ${q(path.join(root,'scripts/java/LocalTlsPublisher.java'))});buildReceipt=(ref ${q(path.join(evidence,'build-receipt02.json'))});jar=(ref ${q(path.join(evidence,'local-tls-publisher.jar'))});runtime=(ref ${q(path.join(evidence,'runtime.properties'))});artifactRoot=${q(evidence.replaceAll('\\','/'))};publisherName='agent-platform-single-final-consultation-publish-20261010';targetId='9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f';bridgeId='3fc351592a63a334572db0a34af42b586da11586350ffc4e6450835bc956c5bb'}
${change}
$r=try{$null=Get-LocalTlsPublicationPlan $c ('f'*64);'OK'}catch{'REFUSED'};@{result=$r}|ConvertTo-Json`);}
test('exact source approval fixed-25 inputs form publication plan without Docker',()=>assert.equal(planCase().result,'OK'));
for(const [label,change] of [
['unregistered option',"$c['newPurpose']='other'"],['authority false',"$script:approval.approvalGranted=$false"],
['wrong human provenance',"$script:approval.sourceUserMessageId='fabricated'"],['public interface',"$script:approval.scope.hostIp='0.0.0.0'"],
['different target',"$script:approval.scope.targetId=('b'*64)"],['different port',"$script:approval.scope.targetPort=443"],
['TLS termination',"$script:approval.scope.tlsTermination=$true"],['private key access',"$script:approval.scope.privateKeyReadOrCopy=$true"],
['app egress',"$script:approval.scope.applicationEgressChange=$true"],['permission bypass',"$script:approval.scope.businessPermissionChange=$true"],
['resource increase',"$script:approval.scope.memoryBytes=268435456"],['window extension',"$script:approval.scope.until='2026-10-11T18:00:00+08:00'"],
['changed fixed jar',"$script:deployment.applications[0].jar.sha256=('b'*64)"],['source drift',"$c.source.sha256=('b'*64)"]
])test(`publication input plan refuses ${label}`,()=>assert.equal(planCase(change).result,'REFUSED'));

test('CREATED exact readonly Windows sources admitted before Docker mapping',()=>assert.equal(inspectCase("$a.State=@{Status='created';Running=$false;Pid=0};$a.Mounts[0].Source='D:/relay.jar';$a.Mounts[1].Source='D:/runtime.properties'",{created:true}).result,'OK'));
test('RUNNING Windows metadata alone refuses without actual matching bytes',()=>assert.equal(inspectCase("$a.Mounts[0].Source='D:/relay.jar';$script:badBytes=$true").result,'REFUSED'));
test('RUNNING exact Windows metadata plus actual byte proof accepted',()=>assert.equal(inspectCase("$a.Mounts[0].Source='D:/relay.jar'").result,'OK'));
test('mapped running mount also refuses actual bytes mismatch',()=>assert.equal(inspectCase("$script:badBytes=$true").result,'REFUSED'));

test('fresh admission refs preserve original immutable publisher binding',()=>assert.equal(planCase(`$c.originalPublication=(ref ${q(path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/publication.json'))})`).result,'OK'));
test('fresh admission cannot switch immutable artifact profile',()=>assert.equal(planCase(`$c.originalPublication=(ref ${q(path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/publication.json'))});$c.runtime.sha256=('b'*64)`).result,'REFUSED'));
