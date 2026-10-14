$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$a=$PSScriptRoot;$e=Split-Path -Parent $a
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields;$null=Assert-FinalApproval $ready
$target='f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f';$network='047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65'
$known=@{'9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f'='consultation';'e0e5ddf6b3caac946c333ce8b581ebde6bbd8a6918b25a053e55b7b6c1a07449'='enforcement';'4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff'='adjudication'}
$records=@();$mem=0L;$cpu=0L;$volumeUsers=@();$domainWriters=@();$excluded=@()
foreach($id in @(& docker ps -aq --no-trunc)){
 $v=@(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable -DateKind String)[0]
 $ours=$v.Config.Labels.task -ceq 'IMP-SINGLE-DB-FINAL-BASELINE-01'
 $uses=@($v.Mounts|Where-Object {$_.Name -in @('agent-platform-single-final-primary-data-20261009','agent-platform-single-final-primary-data-20261009-image')})
 if(-not $ours -and $uses.Count -eq 0){continue}
 $item=@{id=$v.Id;name=$v.Name;image=$v.Image;state=$v.State;user=$v.Config.User;labels=$v.Config.Labels;entrypoint=$v.Config.Entrypoint;command=$v.Config.Cmd;environmentNames=@($v.Config.Env|ForEach-Object {($_ -split '=',2)[0]});environmentValuesSaved=$false;memoryBytes=$v.HostConfig.Memory;nanoCpus=$v.HostConfig.NanoCpus;readonlyRootfs=$v.HostConfig.ReadonlyRootfs;restartPolicy=$v.HostConfig.RestartPolicy;devices=$v.HostConfig.Devices;mounts=$v.Mounts;ports=$v.HostConfig.PortBindings;networks=$v.NetworkSettings.Networks}
 $records+=$item
 if($uses.Count){$volumeUsers+=@{id=$v.Id;running=$v.State.Running;mounts=$uses}}
 if($v.State.Running -and $ours){$mem+=[long]$v.HostConfig.Memory;$cpu+=[long]$v.HostConfig.NanoCpus}
 if($known.ContainsKey($id)){
  if(-not $v.State.Running -or $v.Config.Labels.'agent-platform.domain' -cne $known[$id]){throw 'CURRENT_NORMAL_APP_BINDING_DRIFT'}
  $domainWriters+=@{id=$id;domain=$known[$id];kind='CONTAINER';owner='current-main-session under original resource application approval';responsibilitySource="$a/verified-priority45.json";state='CURRENTLY_RUNNING_NOT_STOPPED';actualPid=$v.State.Pid;actualStartedAt=$v.State.StartedAt;ordinaryBusinessVerified=$false}
 }elseif($v.State.Running -and $id -ne $target -and $id -ne 'ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096'){
  $excluded+=@{id=$id;kind=$v.Config.Labels.'agent-platform.role';reason='transport-only candidate; precise no-secret/no-business-files/no-domain-write proof must be rechecked before asserting complete quiescence';currentlyRunning=$true}
 }
}
$net=@(Invoke-FinalDocker @('network','inspect',$network)|ConvertFrom-Json -AsHashtable -DateKind String)[0]
if($domainWriters.Count -ne 3 -or -not $net.Internal -or $net.Id -cne $network -or $mem -gt 7516192768 -or $cpu -gt 4000000000){throw 'EXACT_CURRENT_RESOURCE_BASELINE_REQUIRED'}
$net|ConvertTo-Json -Depth 16|Set-Content "$a/internal-network45.json"
@{format='ACTUAL_CURRENT_SINGLE_DB_WRITER_RESOURCE_DISCOVERY/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');scope='readonly exact new single-final target, not old environment; technical discovery not stopped-writer attestation';targetId=$target;records=$records;currentRunningMemoryBytes=$mem;currentRunningNanoCpus=$cpu;actualDomainWriters=$domainWriters;primaryVolumeUsers=$volumeUsers;excludedTransportRoles=$excluded;knownMaintenanceRole='ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096';maintenanceLockNotAcquired=$true;allWritersStopped=$false;completeRegistryClaimed=$false;unknownResultsMustBeReconciled=$true;oldEnvironmentTouched=$false;modelCalls=0}|ConvertTo-Json -Depth 25|Set-Content "$a/current-resource-writer-discovery45.json"
foreach($id in $known.Keys){$domain=$known[$id];$commands=@('exec',$id,'sh','-c',"set -eu; test -d '/product/files/$domain'; stat -c '%n %F %u:%g:%a' '/product/files/$domain'; find '/product/files/$domain' -xdev -type f -exec sha256sum '{}' ';'; find '/product/files/$domain' -xdev -type l -print")
 Invoke-FinalDocker $commands|Set-Content "$a/$domain-current-files45.log"
 Invoke-FinalDocker @('top',$id,'-eo','pid,user,comm')|Set-Content "$a/$domain-current-processes45.log"
}
Invoke-FinalDocker @('top','ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096','-eo','pid,user,comm')|Set-Content "$a/current-maintenance-processes45.log"
$admin=@{path='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/SINGLE_DB_ADMIN_PASSWORD.dpapi';sha256='ef0e6ed0881f2d5efb2a6af1446b248155d93b84060a406c4e483453e3421eb4'}
$old=$env:SINGLE_DB_ADMIN_PASSWORD;$code=$null;$started=[DateTimeOffset]::Now
try{$env:SINGLE_DB_ADMIN_PASSWORD=Read-FinalManagedSecret $admin
 & node scripts/inventory-single-database-lifecycle.mjs "$e/primary-migration-config07.json" "$a/current-full-inventory45.json" *> "$a/current-full-inventory45.log";$code=$LASTEXITCODE
 if($code -ne 0){throw 'READONLY_FULL_INVENTORY_FAILED'}
}finally{$env:SINGLE_DB_ADMIN_PASSWORD=$old;$old=$null;@{capturedAt=[DateTimeOffset]::Now.ToString('o');startedAt=$started.ToString('o');exitCode=$code;credentialReference=$admin;scope='readonly six-schema inventory, no stop/start/fence/export/restore/SQL writes/DDL'}|ConvertTo-Json -Depth 5|Set-Content "$a/current-full-inventory45-result.json"}
