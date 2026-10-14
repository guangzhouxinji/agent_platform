$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$a=$PSScriptRoot;$e=Split-Path -Parent $a;$r=Read-FinalDeploymentJson "$a/current-resource-writer-discovery46-02.json";$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields
$network='agent-platform-single-final-20261009';$netId='047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65'
$sequence=@('9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f','6796847921a4c9542d8e2e2e7b203a72faae2a89d508ed6dd452ccb009baab2f','4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff','1bf91da6e10a789033932c7cd20aa806e7c6a81e3142509692247daa89af0df1','e0e5ddf6b3caac946c333ce8b581ebde6bbd8a6918b25a053e55b7b6c1a07449')
$events="$a/maintenance-network-return46.events.jsonl";if(Test-Path -LiteralPath $events){throw 'NEW_RETURN_OUTPUT_REQUIRED'};$null=[IO.File]::Open($events,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read).Dispose()
function Event($kind,$data){@{time=[DateTimeOffset]::Now.ToString('o');kind=$kind;data=$data}|ConvertTo-Json -Depth 10 -Compress|Add-Content -LiteralPath $events}
function Inspect($id){@(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable -DateKind String)[0]}
function Admission{$null=Assert-FinalApproval $ready}
$states=@();$failed=$null;$start=[DateTimeOffset]::Now
try{
 Admission
 foreach($id in $sequence){$v=Inspect $id;$orig=@($r.records|Where-Object id -CEQ $id)[0];if($v.Id -cne $id -or $v.Image -cne $orig.image -or $v.HostConfig.RestartPolicy.Name -cne 'no' -or $v.NetworkSettings.Networks[$network].NetworkID -cne $netId){throw 'EXACT_ORIGINAL_RETURN_RESOURCE_REQUIRED'}
 if($id -notin $r.actualDomainWriters.id -and ($v.State.Running -or $v.State.Pid -ne 0 -or $v.State.ExitCode -ne 1)){throw 'KNOWN_TERMINAL_TRANSPORT_STATE_REQUIRED'}
 if($id -notin $r.actualDomainWriters.id){foreach($m in $orig.mounts){$actual=@($v.Mounts|Where-Object Destination -CEQ $m.Destination)[0];if($actual.RW -or $actual.Source -cne $m.Source){throw 'TRANSPORT_ORIGINAL_FILE_BINDING_DRIFT'};$hash=(Get-FileHash -LiteralPath $m.Source -Algorithm SHA256).Hash.ToLowerInvariant();if(($orig.command -join ' ') -notmatch [regex]::Escape($hash)){throw 'TRANSPORT_ORIGINAL_FILE_SHA_DRIFT'}}}
 }
 foreach($w in $r.actualDomainWriters){Admission;$v=Inspect $w.id;if($v.State.Running){Event 'EXACT_APPLICATION_STOP_REQUESTED' @{id=$w.id};$err=$null;try{$null=Invoke-FinalDocker @('stop','--time','30',$w.id)}catch{$err=$_.Exception.Message};$v=Inspect $w.id};if($v.State.Running -or $v.State.Restarting -or $v.State.Pid -ne 0){throw 'APPLICATION_STOP_UNCONFIRMED'};Event 'EXACT_APPLICATION_STOP_CONFIRMED' @{id=$w.id;pid=0}}
 foreach($id in $sequence){
  Admission;$orig=@($r.records|Where-Object id -CEQ $id)[0];$v=Inspect $id;if($v.State.Running -or $v.State.Restarting -or $v.State.Pid -ne 0){throw 'RETURN_START_STATE_DRIFT'}
  Event 'ORIGINAL_RESOURCE_START_REQUESTED' @{id=$id;expectedIp=$orig.networks[$network].IPAddress};$err=$null;try{$null=Invoke-FinalDocker @('start',$id)}catch{$err=$_.Exception.Message};$v=Inspect $id
  if(-not $v.State.Running -or $v.State.Restarting -or $v.State.Pid -le 0 -or $v.Image -cne $orig.image -or $v.NetworkSettings.Networks[$network].IPAddress -cne $orig.networks[$network].IPAddress){throw 'ORIGINAL_RESOURCE_NETWORK_RETURN_UNCONFIRMED'}
  if($id -in $r.actualDomainWriters.id){$ok=$false;for($n=0;$n -lt 8;$n++){Start-Sleep -Seconds 5;$v=Inspect $id;if(-not $v.State.Running -or $v.State.Restarting){throw 'APPLICATION_STARTUP_TERMINATED'};$logs=Invoke-FinalDocker @('logs','--since',$v.State.StartedAt,'--tail','80',$id);if($logs -match 'Started Product(Consultation|Adjudication|Enforcement)Application in '){$ok=$true;break}};if(-not $ok){throw 'APPLICATION_CURRENT_STARTUP_UNCONFIRMED'}}else{Start-Sleep -Seconds 2;$v=Inspect $id;if(-not $v.State.Running -or $v.State.Pid -le 0){throw 'TRANSPORT_STARTUP_TERMINATED'}}
  $item=@{id=$id;running=$v.State.Running;pid=$v.State.Pid;startedAt=$v.State.StartedAt;image=$v.Image;internalIp=$v.NetworkSettings.Networks[$network].IPAddress;originalIpMatched=$true;originalImageMatched=$true};$states+=$item;Event 'ORIGINAL_RESOURCE_RETURN_CONFIRMED' $item
 }
 $memory=0L;$cpu=0L;$final=@();foreach($orig in @($r.records|Where-Object {$_.state.Running})){$v=Inspect $orig.id;if(-not $v.State.Running -or $v.Image -cne $orig.image -or $v.NetworkSettings.Networks[$network].IPAddress -cne $orig.networks[$network].IPAddress){throw 'SEVEN_ROLE_RETURN_DRIFT'};$memory+=[long]$v.HostConfig.Memory;$cpu+=[long]$v.HostConfig.NanoCpus;$final+=@{id=$v.Id;pid=$v.State.Pid;running=$v.State.Running;image=$v.Image;internalIp=$v.NetworkSettings.Networks[$network].IPAddress}}
 if($memory -gt 7516192768 -or $cpu -gt 4000000000){throw 'RETURN_RESOURCE_BUDGET_EXCEEDED'}
 Event 'SEVEN_ORIGINAL_ROLES_RETURNED' @{memoryBytes=$memory;nanoCpus=$cpu;roles=$final;oldEnvironmentTouched=$false;modelCalls=0}
}catch{$failed=$_.Exception.Message;Event 'RETURN_FAILURE_PRESERVED' @{code=$failed;automaticRetry=$false}}
finally{@{format='EXACT_ORIGINAL_NETWORK_MAINTENANCE_RETURN/v1';taskId='FIX-SINGLE-DB-MAINTENANCE-RETURN-01';startedAt=$start.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');failure=$failed;passed=($null -eq $failed);returned=$states;originalFileBytesChanged=$false;sourceDm8Stopped=$false;oldEnvironmentTouched=$false;strictTlsAndFullInventoryPending=$true;modelCalls=0;calculationCalls=0;matrixCalls=0}|ConvertTo-Json -Depth 12|Set-Content "$a/maintenance-network-return46-result.json"}
if($failed){throw $failed}
