import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
const root=resolve(import.meta.dirname,'../..');
const helper=join(root,'scripts/lib/single-database-deployment.ps1');
const work=mkdtempSync(join(tmpdir(),'single-db-preflight-'));
const base={schema:'SINGLE_DM8_DEPLOYMENT/v1',database:{containerId:'a'.repeat(64),volume:'agent-platform-own-persistent',ownerTask:'VER-AGENT-SINGLE-DB-PERSISTENT-01',jdbcUrl:'jdbc:dm://127.0.0.1:5492',memoryLimitBytes:4294967296,nanoCpuLimit:2000000000},domains:['consultation','enforcement','adjudication'].map((domain,index)=>({domain,issuer:`https://${domain}.example/issuer`,writerContainerIds:[String(index+1).repeat(64)],business:{schema:`DOM${index}B`,ownerUser:`DOM${index}B`,runtimeUser:`DOM${index}B_SVC`,jdbcUrl:'jdbc:dm://127.0.0.1:5492'},authority:{schema:`DOM${index}A`,ownerUser:`DOM${index}A`,runtimeUser:`DOM${index}A_SVC`,jdbcUrl:'jdbc:dm://127.0.0.1:5492'}}))};
const actual={State:{Running:true},NetworkSettings:{Ports:{'5236/tcp':[{HostIp:'127.0.0.1',HostPort:'5492'}]}},Id:base.database.containerId,Config:{User:'1000:1000',Labels:{task:base.database.ownerTask}},HostConfig:{Memory:4026531840,NanoCpus:2000000000},Mounts:['/opt/dmdbms/data','/opt/dmdbms/log'].map(Destination=>({Destination,Type:'volume',Name:base.database.volume,RW:true}))};
const volume={Name:base.database.volume,Labels:{task:base.database.ownerTask}};
const quote=s=>`'${s.replaceAll("'","''")}'`;
function run(config,resource=null,vol=volume,running=false){
 const file=join(work,'input.json');writeFileSync(file,JSON.stringify(config));
 const state=join(work,'state.json');writeFileSync(state,JSON.stringify({resource,vol,running}));
 const code=`$ErrorActionPreference='Stop'
. ${quote(helper)}
$config=Get-Content -LiteralPath ${quote(file)} -Raw|ConvertFrom-Json -AsHashtable
$state=Get-Content -LiteralPath ${quote(state)} -Raw|ConvertFrom-Json
function docker {
 param([Parameter(ValueFromRemainingArguments=$true)]$args)
 $global:LASTEXITCODE=0
 if($args[0] -eq 'volume'){return ($state.vol|ConvertTo-Json -Depth 8 -Compress)}
 if($args[1] -eq $config.database.containerId){return ($state.resource|ConvertTo-Json -Depth 8 -Compress)}
 foreach($d in $config.domains){if($args[1] -in $d.writerContainerIds){return (@{Id=$args[1];Config=@{Labels=@{'agent-platform.domain'=$d.domain}};State=@{Running=$state.running}}|ConvertTo-Json -Depth 8 -Compress)}}
 throw 'Unexpected Docker operation'
}
try {
 $plan=Get-SingleDatabaseDeploymentPlan -Config $config
 if($state.resource){$report=Invoke-SingleDatabaseInspection -Config $config -RequireStoppedWriters; $report|ConvertTo-Json -Compress}
 else {if($plan.domains.Count -ne 3 -or $plan.credentialsIncluded){throw 'Invalid plan'};foreach($d in $plan.domains){if($d.settings.SPRING_DATASOURCE_HIKARI_SCHEMA -cne $config.domains.Where({$_.domain -ceq $d.domain})[0].business.schema -or $d.settings.PRODUCT_IDENTITY_AUTHORITY_TOPOLOGY -cne 'shared-instance' -or $d.settings.SPRING_DATASOURCE_URL -cne $d.settings.PRODUCT_IDENTITY_AUTHORITY_JDBC_URL){throw 'Incorrect shared binding'}};Write-Output 'OFFLINE_OK'}
} catch {Write-Output 'REFUSED';exit 1}
`;
 const r=spawnSync('pwsh',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',timeout:20000});assert.ifError(r.error);return r;
}
const altered=fn=>{const c=structuredClone(base);fn(c);return c;};
test('one instance maps all three domains without merging schemas/accounts/issuers',()=>{const r=run(base);assert.equal(r.status,0,r.stdout+r.stderr);assert.match(r.stdout,/OFFLINE_OK/);});
for(const [name,fn] of [
 ['missing domain',c=>c.domains.pop()],['duplicate domain',c=>c.domains[1].domain='consultation'],
 ['collapsed Issuer',c=>c.domains[1].issuer=c.domains[0].issuer],['collapsed schema',c=>c.domains[1].business.schema=c.domains[0].authority.schema],
 ['shared runtime account',c=>c.domains[2].authority.runtimeUser=c.domains[0].business.runtimeUser],['owner used as runtime',c=>c.domains[0].business.runtimeUser=c.domains[0].business.ownerUser],
 ['system account',c=>c.domains[1].authority.runtimeUser='SYSDBA'],['second instance',c=>c.domains[2].authority.jdbcUrl='jdbc:dm://127.0.0.1:5493'],
 ['unbounded memory',c=>c.database.memoryLimitBytes=0],['unbounded CPU',c=>c.database.nanoCpuLimit=0],
 ['missing writer inventory',c=>delete c.domains[0].writerContainerIds],['duplicated writer',c=>c.domains[1].writerContainerIds=c.domains[0].writerContainerIds],
 ['password in config',c=>c.database.password='SENTINEL_PRIVATE_NEVER_PRINT'],['untrusted URL query',c=>c.database.jdbcUrl+='?user=SYSDBA']]){
 test(`rejects ${name} before inspecting runtime`,()=>{const r=run(altered(fn));assert.equal(r.status,1,r.stdout+r.stderr);assert.ok(!r.stdout.includes('SENTINEL_PRIVATE_NEVER_PRINT'));});
}
test('exact stopped writers and persistent owned bounded resource pass limited read-only inspection',()=>{const r=run(base,actual);assert.equal(r.status,0,r.stdout+r.stderr);const x=JSON.parse(r.stdout.trim());assert.equal(x.resourceVerified,true);assert.equal(x.enumeratedWritersStopped,true);assert.equal(x.unlistedWritersProvenAbsent,false);assert.equal(x.diskCapacityVerified,false);});
for(const [name,fn] of [
 ['privileged DM8',a=>a.HostConfig.Privileged=true],['root DM8',a=>a.Config.User='0:1000'],
 ['wrong bound JDBC port',a=>a.NetworkSettings.Ports['5236/tcp'][0].HostPort='5301'],['exposed JDBC binding',a=>a.NetworkSettings.Ports['5236/tcp'][0].HostIp='0.0.0.0'],
 ['wrong exact container',a=>a.Id='f'.repeat(64)],['wrong resource owner',a=>a.Config.Labels.task='OTHER-TASK'],
 ['unbounded actual memory',a=>a.HostConfig.Memory=0],['excess actual CPUs',a=>a.HostConfig.NanoCpus=3000000000],
 ['tmpfs data',a=>a.Mounts[0].Type='tmpfs'],['wrong persistent volume',a=>a.Mounts[0].Name='another-volume'],['missing log volume',a=>a.Mounts.pop()]]){
 test(`rejects ${name} at Docker boundary`,()=>{const a=structuredClone(actual);fn(a);const r=run(base,a);assert.equal(r.status,1,r.stdout+r.stderr);});
}
test('stopped owned DB validates configured port without claiming live connectivity',()=>{const a=structuredClone(actual);a.State.Running=false;a.HostConfig.PortBindings=a.NetworkSettings.Ports;a.NetworkSettings.Ports={};const r=run(base,a);assert.equal(r.status,0,r.stdout+r.stderr);const x=JSON.parse(r.stdout.trim());assert.equal(x.databaseRunning,false);assert.equal(x.databaseConnectivityVerified,false);});
test('empty explicit inventory is valid before deployment but cannot admit a backup',()=>{const c=altered(x=>x.domains[0].writerContainerIds=[]);assert.equal(run(c).status,0);assert.equal(run(c,actual).status,1);});
test('active writer prevents backup static admission without stopping it',()=>assert.equal(run(base,actual,volume,true).status,1));
test('wrong volume ownership prevents backup static admission',()=>assert.equal(run(base,actual,{...volume,Labels:{task:'OTHER-TASK'}}).status,1));
test.after(()=>rmSync(work,{recursive:true,force:true}));
