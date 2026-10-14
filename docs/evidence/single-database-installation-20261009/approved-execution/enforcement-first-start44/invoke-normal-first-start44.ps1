$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$a=$PSScriptRoot;$e=Split-Path -Parent $a
$c=Read-FinalDeploymentJson "$a/enforcement-deployment44.json";$null=Assert-FinalApproval $c
$val=Read-FinalDeploymentJson "$a/deployment44-validate-only-result.json";if($val.exitCode -ne 0){throw 'VALIDATE_ONLY_REQUIRED'}
foreach($name in @('application44-actual-start-result.json','application44-actual-start.log','application44-actual-metadata.json')){if(Test-Path "$a/$name"){throw 'ACTUAL_START_RESULT_EXISTS_RECONCILE_NO_RETRY'}}
$before=Read-FinalDeploymentJson "$a/fresh-resource44.json";$roles=@();$mem=0L;$cpu=0L
foreach($r in $before.roles){$v=@(Invoke-FinalDocker @('inspect',$r.id)|ConvertFrom-Json -AsHashtable -DateKind String)[0];if(-not $v.State.Running -or $v.State.Pid -ne $r.state.Pid -or $v.State.StartedAt -cne $r.state.StartedAt){throw 'PROTECTED_ROLE_DRIFT'};$roles+=@{id=$v.Id;pid=$v.State.Pid;startedAt=$v.State.StartedAt;memoryBytes=$v.HostConfig.Memory;nanoCpus=$v.HostConfig.NanoCpus;networkIds=@($v.NetworkSettings.Networks.Values|ForEach-Object NetworkID)};$mem+=[long]$v.HostConfig.Memory;$cpu+=[long]$v.HostConfig.NanoCpus}
if($roles.Count -ne 6 -or ($mem+939524096) -gt 7516192768 -or ($cpu+550000000) -gt 4000000000){throw 'RESERVED_APP_AND_RELAY_BUDGET_REFUSED'}
@{format='ENFORCEMENT_NORMAL_FIRST_START_RESPONSIBILITY/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');owner='current-main-session';hostPid=$PID;configuration=@{path="$a/enforcement-deployment44.json";sha256=(Get-FileHash "$a/enforcement-deployment44.json").Hash.ToLowerInvariant()};beforeRoles=$roles;beforeMemoryBytes=$mem;beforeNanoCpus=$cpu;expectedAppMemoryBytes=805306368;expectedAppNanoCpus=500000000;reservedPublisherMemoryBytes=134217728;reservedPublisherNanoCpus=50000000;bootstrapOrDclAllowed=$false;registeredReleaseClaimed=$false;modelCalls=0}|ConvertTo-Json -Depth 10|Set-Content "$a/application44-start-responsibility.json"
$null=Assert-FinalApproval $c;$started=[DateTimeOffset]::Now;$code=$null
try{& ./scripts/start-single-database-applications.ps1 -Configuration "$a/enforcement-deployment44.json" *> "$a/application44-actual-start.log";$code=$LASTEXITCODE}
finally{@{scope='NORMAL_EXISTING_DEPLOYMENT_CLI_NO_BOOTSTRAP';startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;retryPerformed=$false;httpAndClientStillRequireProof=$true}|ConvertTo-Json|Set-Content "$a/application44-actual-start-result.json"}
if($code -ne 0){throw 'ACTUAL_START_FAILED_PRESERVE_RESOURCES_RECONCILE_NO_RETRY'}
$out=Read-FinalDeploymentJson "$a/application44-actual-start.log";$id=$out.containerIds[0]
if($id -cnotmatch '^[a-f0-9]{64}$'){throw 'FULL_CREATED_ID_FROM_RECEIPT_REQUIRED'}
$v=@(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable -DateKind String)[0]
@{capturedAt=[DateTimeOffset]::Now.ToString('o');id=$v.Id;name=$v.Name;image=$v.Image;state=$v.State;user=$v.Config.User;entrypoint=$v.Config.Entrypoint;command=$v.Config.Cmd;environmentNames=@($v.Config.Env|ForEach-Object {($_ -split '=',2)[0]});environmentValuesSaved=$false;labels=$v.Config.Labels;hostConfig=$v.HostConfig;mounts=$v.Mounts;ports=$v.NetworkSettings.Ports;networks=$v.NetworkSettings.Networks}|ConvertTo-Json -Depth 20|Set-Content "$a/application44-actual-metadata.json"
Invoke-FinalDocker @('logs',$id)|Set-Content "$a/application44-runtime.log"
foreach($r in $roles){$z=@(Invoke-FinalDocker @('inspect',$r.id)|ConvertFrom-Json -AsHashtable -DateKind String)[0];if(-not $z.State.Running -or $z.State.Pid -ne $r.pid -or $z.State.StartedAt -cne $r.startedAt){throw 'PROTECTED_ROLE_DRIFT_AFTER_START'}}

