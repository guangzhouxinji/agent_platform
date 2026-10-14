import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readEntryFacts,readEntryResourceFacts,ENTRY68} from './consultation-activation-recovery-entry.mjs';
import {canonical} from './single-database-lifecycle-runtime.mjs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {isIP} from 'node:net';

const exec=promisify(execFile);
export const EXTERNAL76=Object.freeze({
  id:'35d790643744cb8405a6755b348f6fd0ab2ab9b2e8937421ae6288cd655e3395',
  name:'/entservice-dm8',
  image:'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760',
  network:'3fc351592a63a334572db0a34af42b586da11586350ffc4e6450835bc956c5bb',
});
const require=(yes,code)=>{if(!yes)throw Error(code);};
const HOST_CANDIDATE92=/^(node|java|python|pwsh|powershell|cmd|wsl|bash|sh|disql|dbeaver|navicat|docker)(\.exe)?$/i;
const DIRECT_DB92=/^(java|wsl|bash|sh|disql|dbeaver|navicat)(\.exe)?$/i;
const ACTIVE_TCP92=new Set(['SynSent','SynReceived','Established','FinWait1','FinWait2','CloseWait','Closing','LastAck']);
const INACTIVE_TCP92=new Set(['Closed','Listen','Bound','TimeWait','DeleteTcb']);
export function projectHostScope92(sample,scope){
  require(sample?.format==='ACTR92_HOST_IMPACT_OBSERVATION/v1'&&Array.isArray(sample.processes)&&sample.processes.length<=8192&&Array.isArray(sample.connections)&&sample.connections.length<=65536&&Number.isFinite(Date.parse(sample.capturedAt))&&Number.isFinite(Date.parse(sample.connectionsCapturedAt))&&Date.parse(sample.connectionsCapturedAt)<=Date.parse(sample.capturedAt),'PROJECT92_COMPLETE_HOST_OBSERVATION_REQUIRED');
  require(Array.isArray(scope?.roots)&&scope.roots.length>=1&&scope.roots.every(p=>Number.isSafeInteger(p)&&p>0)&&Array.isArray(scope.ports)&&scope.ports.every(p=>Number.isSafeInteger(p)&&p>0&&p<=65535)&&Array.isArray(scope.databaseIps)&&scope.databaseIps.every(p=>typeof p==='string'&&p.length<=64),'PROJECT92_EXACT_SCOPE_REQUIRED');
  const by=new Map();
  for(const p of sample.processes){
    require(p&&Number.isSafeInteger(p.pid)&&p.pid>0&&Number.isSafeInteger(p.parentId)&&p.parentId>=0&&typeof p.name==='string'&&Number.isFinite(Date.parse(p.createdAt))&&typeof p.projectReferenced==='boolean'&&!by.has(p.pid),'PROJECT92_PROCESS_IDENTITY_REQUIRED');
    by.set(p.pid,p);
  }
  const exitedDuringCapture=[],rechecked=new Set();
  require(sample.commandRechecks===undefined||Array.isArray(sample.commandRechecks),'PROJECT93_EXACT_RECHECK_REQUIRED');
  for(const check of sample.commandRechecks??[]){
    const original=by.get(check?.pid);
    require(original&&!original.commandSha256&&HOST_CANDIDATE92.test(original.name)&&!rechecked.has(check.pid)&&Number.isFinite(Date.parse(check.recheckedAt))&&Date.parse(check.recheckedAt)>=Date.parse(original.createdAt)&&Date.parse(check.recheckedAt)<=Date.parse(sample.capturedAt),'PROJECT93_EXACT_RECHECK_REQUIRED');
    rechecked.add(check.pid);
    if(check.process===null){
      by.delete(check.pid);
      exitedDuringCapture.push({pid:original.pid,parentId:original.parentId,name:original.name,createdAt:original.createdAt,recheckedAt:check.recheckedAt});
    }else{
      const current=check.process;
      require(current&&['pid','parentId','name','createdAt'].every(k=>current[k]===original[k])&&typeof current.projectReferenced==='boolean','PROJECT93_RECHECK_IDENTITY_CHANGED');
      require(/^[a-f0-9]{64}$/.test(current.commandSha256??''),'PROJECT93_LIVE_HOST_COMMAND_UNREADABLE_'+original.pid);
      by.set(check.pid,current);
    }
  }
  for(const pid of scope.roots)require(by.has(pid),'PROJECT92_EXECUTION_ROOT_MISSING');
  const connected=new Set();
  for(const c of sample.connections){
    require(c&&Number.isSafeInteger(c.pid)&&c.pid>=0&&Number.isSafeInteger(c.remotePort)&&c.remotePort>=0&&c.remotePort<=65535&&isIP(c.remoteAddress)>0&&(ACTIVE_TCP92.has(c.state)||INACTIVE_TCP92.has(c.state)),'PROJECT92_TCP_OBSERVATION_REQUIRED');
    if(ACTIVE_TCP92.has(c.state)&&(scope.ports.includes(c.remotePort)||c.remotePort===5236&&scope.databaseIps.includes(c.remoteAddress))){require(c.pid>0&&by.has(c.pid)&&Date.parse(by.get(c.pid).createdAt)<=Date.parse(sample.connectionsCapturedAt),'PROJECT92_CONNECTION_OWNER_UNKNOWN');connected.add(c.pid);}
  }
  const ancestry=p=>{
    const seen=new Set();let current=p;
    while(current){
      require(!seen.has(current.pid),'PROJECT92_PARENT_CYCLE');seen.add(current.pid);
      if(scope.roots.includes(current.pid))return {executionChain:true,complete:true};
      if(current.parentId===0)return {executionChain:false,complete:true};
      const parent=by.get(current.parentId);if(!parent)return {executionChain:false,complete:false};
      if(Date.parse(parent.createdAt)>Date.parse(current.createdAt))return {executionChain:false,complete:false};current=parent;
    }
  };
  const hostProcesses=[],observedUnrelated=[];
  for(const p of [...by.values()].filter(p=>HOST_CANDIDATE92.test(p.name)||p.projectReferenced||connected.has(p.pid)||scope.roots.includes(p.pid))){
    require(/^[a-f0-9]{64}$/.test(p.commandSha256??''),'PROJECT92_HOST_COMMAND_UNREADABLE_'+p.pid);
    const a=ancestry(p),identity={pid:p.pid,parentId:p.parentId,name:p.name,createdAt:p.createdAt,commandSha256:p.commandSha256};
    const impact={executionChain:a.executionChain,ancestryComplete:a.complete,directDbClient:DIRECT_DB92.test(p.name),projectReferenced:p.projectReferenced,projectConnected:connected.has(p.pid)};
    if(impact.executionChain||impact.directDbClient||impact.projectReferenced||impact.projectConnected)hostProcesses.push(identity);
    else observedUnrelated.push({...identity,impact});
  }
  return {hostProcesses,hostScopeObservation:{format:sample.format,capturedAt:sample.capturedAt,scope,observedUnrelated,exitedDuringCapture,projectConnectionOwners:[...connected].sort((a,b)=>a-b),unrelatedProcessesGrantedRoles:false}};
}
export const IMPACT_SCRIPT92=`
$ErrorActionPreference='Stop'
$scope=ConvertFrom-Json ([Environment]::GetEnvironmentVariable('ACTR92_SCOPE_JSON','Process'))
$sha=[Security.Cryptography.SHA256]::Create()
function Convert-HostRow93($item) {
  $line=[string]$item.CommandLine
  $normalized=$line.Replace([char]92,[char]47).ToLowerInvariant()
  $referenced=$false
  foreach($ref in $scope.references){if($normalized.Contains([string]$ref)){$referenced=$true;break}}
  @{pid=[int]$item.ProcessId;parentId=[int]$item.ParentProcessId;name=[string]$item.Name;createdAt=$item.CreationDate.ToUniversalTime().ToString('o');commandSha256=if($line){([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($line)))).Replace('-','').ToLowerInvariant()}else{$null};projectReferenced=$referenced}
}
$connections=@(Get-NetTCPConnection -ErrorAction Stop|ForEach-Object {@{pid=[int]$_.OwningProcess;remotePort=[int]$_.RemotePort;remoteAddress=[string]$_.RemoteAddress;state=[string]$_.State}})
$connectionsCapturedAt=[DateTime]::UtcNow.ToString('o')
$processes=@(Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -gt 0 -and $_.ProcessId -ne $PID}|ForEach-Object {Convert-HostRow93 $_})
$rechecks=@(foreach($row in $processes){
  if(-not $row.commandSha256 -and $row.name -match '^(node|java|python|pwsh|powershell|cmd|wsl|bash|sh|disql|dbeaver|navicat|docker)([.]exe)?$'){
    $current=@(Get-CimInstance Win32_Process -Filter ('ProcessId = '+$row.pid) -ErrorAction Stop)
    if($current.Count -gt 1){throw 'PROJECT93_DUPLICATE_CURRENT_PID'}
    @{pid=$row.pid;recheckedAt=[DateTime]::UtcNow.ToString('o');process=if($current.Count -eq 1){Convert-HostRow93 $current[0]}else{$null}}
  }
})
@{format='ACTR92_HOST_IMPACT_OBSERVATION/v1';capturedAt=[DateTime]::UtcNow.ToString('o');connectionsCapturedAt=$connectionsCapturedAt;processes=$processes;connections=$connections;commandRechecks=$rechecks}|ConvertTo-Json -Depth 6 -Compress
`;
async function readProjectHostScope92(facts){
  const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..').replaceAll('\\','/').toLowerCase();
  const ports=[...new Set(facts.containers.flatMap(c=>Object.values(c.HostConfig.PortBindings??{}).flatMap(rows=>(rows??[]).map(r=>Number(r.HostPort)))))].sort((a,b)=>a-b);
  const databases=facts.containers.filter(c=>[ENTRY68.source,ENTRY68.target].includes(c.Id));
  const scope={roots:[process.pid,process.ppid],ports,databaseIps:databases.flatMap(c=>Object.values(c.NetworkSettings.Networks??{}).map(n=>n.IPAddress).filter(Boolean)),references:[root,'c:/users/60329/appdata/local/agentplatform/single-final-20261009',...facts.containers.flatMap(c=>[c.Id.slice(0,12),c.Name.replace(/^\//,'').toLowerCase()]),ENTRY68.network.slice(0,12),'jdbc:dm:']};
  const env=Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('SINGLE_DB_')));env.ACTR92_SCOPE_JSON=JSON.stringify(scope);
  const result=await exec('pwsh',['-NoLogo','-NoProfile','-NonInteractive','-Command',IMPACT_SCRIPT92],{shell:false,windowsHide:true,timeout:30000,maxBuffer:4*1024*1024,env});
  return projectHostScope92(JSON.parse(result.stdout),scope);
}
const expectedMounts=[{Destination:'/opt/dmdbms/data',Mode:'',Propagation:'rprivate',RW:true,Source:'D:\\docker\\dm8\\entservice-data',Type:'bind'}];
const expectedPorts={'5236/tcp':[{HostIp:'',HostPort:'5236'}]};
export function partitionProjectResources76(facts,external){
  require(Array.isArray(facts.ownedRunning)&&Array.isArray(facts.containers)&&Array.isArray(external),'PROJECT76_RESOURCE_OBSERVATION_REQUIRED');
  require(new Set(facts.ownedRunning).size===facts.ownedRunning.length,'PROJECT76_DUPLICATE_RUNNING_ID');
  const projectIds=new Set(facts.containers.map(c=>c.Id));
  require(projectIds.size===9&&facts.containers.length===9,'PROJECT76_NINE_RESOURCES_REQUIRED');
  const foreignIds=facts.ownedRunning.filter(id=>!projectIds.has(id));
  require(external.length===foreignIds.length&&new Set(external.map(c=>c.Id)).size===external.length,'PROJECT76_EXTERNAL_OBSERVATION_MISMATCH');
  for(const c of external){
    require(foreignIds.includes(c.Id)&&c.Id===EXTERNAL76.id&&c.Name===EXTERNAL76.name&&c.Image===EXTERNAL76.image&&canonical(c.Labels)===canonical({}),'PROJECT76_UNKNOWN_EXTERNAL_RESOURCE');
    require(c.NetworkMode==='bridge'&&canonical(Object.keys(c.Networks??{}))===canonical(['bridge'])&&c.Networks.bridge.NetworkID===EXTERNAL76.network&&c.Networks.bridge.NetworkID!==ENTRY68.network,'PROJECT76_EXTERNAL_NETWORK_CHANGED');
    require(canonical(c.Mounts)===canonical(expectedMounts)&&canonical(c.Ports)===canonical(expectedPorts),'PROJECT76_EXTERNAL_STORAGE_OR_PORT_CHANGED');
  }
  const ordered=mounts=>[...mounts].sort((a,b)=>canonical(a).localeCompare(canonical(b)));
  const containers=facts.containers.map(c=>({...c,...(c.Mounts?{Mounts:ordered(c.Mounts)}:{}),...(c.HostConfig?.Mounts?{HostConfig:{...c.HostConfig,Mounts:ordered(c.HostConfig.Mounts)}}:{})}));
  return {...facts,containers,allRunning:[...facts.ownedRunning],externalResources:external,ownedRunning:facts.ownedRunning.filter(id=>projectIds.has(id))};
}

export async function readProjectFacts76(options={},ports={}){
  const reader=options.projectImpactScope===true?(ports.readResources??readEntryResourceFacts):(ports.readFull??readEntryFacts);
  const facts=await reader(options),projectIds=new Set(facts.containers.map(c=>c.Id));
  const foreignIds=facts.ownedRunning.filter(id=>!projectIds.has(id));
  let external=[];
  if(foreignIds.length){
    // Never collect container environment values or execute inside another project's container.
    const format='{"Id":{{json .Id}},"Name":{{json .Name}},"Image":{{json .Image}},"Labels":{{json .Config.Labels}},"Mounts":{{json .Mounts}},"Ports":{{json .HostConfig.PortBindings}},"NetworkMode":{{json .HostConfig.NetworkMode}},"Networks":{{json .NetworkSettings.Networks}}}';
    const result=await exec('docker',['inspect','--format',format,...foreignIds],{shell:false,windowsHide:true,timeout:15000,maxBuffer:1024*1024});
    external=result.stdout.trim().split(/\r?\n/).map(line=>JSON.parse(line));
  }
  const partitioned=partitionProjectResources76(facts,external);
  return options.projectImpactScope===true?{...partitioned,...await (ports.readImpact??readProjectHostScope92)(partitioned)}:partitioned;
}
