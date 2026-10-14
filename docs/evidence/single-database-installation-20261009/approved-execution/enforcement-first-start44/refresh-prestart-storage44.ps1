$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot;$a=$PSScriptRoot
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields;$null=Assert-FinalApproval $ready
$decision=Read-FinalDeploymentJson "$a/delegated-client-page-decision44.json";foreach($ref in @($decision.delegation,$decision.humanMessage,$decision.resourceApproval)){Assert-FinalFile $ref}
$p=Read-FinalDeploymentJson "$e/adjudication-runtime40/publisher42/storage42-binding-proof.json"
foreach($ref in @($p.probeSource,$p.probeContainer,$p.probeOutput)){Assert-FinalFile $ref}
$helper='70f37b7af36bca58842dfde517279dfd5882f4202c3835a1fb90acd99c364f77'
$h=@(Invoke-FinalDocker @('inspect',$helper)|ConvertFrom-Json -AsHashtable)[0];$record=@(Read-FinalDeploymentJson $p.probeContainer.path)[0]
if($h.Id -cne $helper -or $h.Id -cne $record.Id -or $h.State.Running -or $h.State.Pid -ne 0 -or $h.State.ExitCode -ne 0 -or
 $h.Image -cne $record.Image -or $h.Name -cne $record.Name -or $h.HostConfig.NetworkMode -cne 'none' -or $h.HostConfig.Memory -ne 134217728 -or $h.HostConfig.NanoCpus -ne 250000000 -or
 $h.HostConfig.Privileged -or -not $h.HostConfig.ReadonlyRootfs -or $h.HostConfig.CapDrop -cnotcontains 'ALL' -or $h.HostConfig.SecurityOpt -cnotcontains 'no-new-privileges' -or
 (@($h.Config.Entrypoint)-join ',') -cne 'python3' -or (@($h.Config.Cmd)-join ',') -cne '-B,/proof.py' -or
 $h.Mounts.Count -ne 3 -or @($h.Mounts|Where-Object RW -EQ $true).Count -or $h.HostConfig.Devices.Count -ne 1 -or $h.HostConfig.Devices[0].CgroupPermissions -cne 'r' -or
 $h.HostConfig.Devices[0].PathOnHost -cne '/dev/agent-platform-single-final-primary-loop' -or $h.HostConfig.Devices[0].PathInContainer -cne '/dev/owned-loop'){throw 'EXISTING_READ_ONLY_PROBE_DRIFT'}
$mem=0L;$cpu=0L;$roles=@()
foreach($id in @(& docker ps -q)){$v=@(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable)[0];if($v.Config.Labels.task -ceq 'IMP-SINGLE-DB-FINAL-BASELINE-01'){$mem+=[long]$v.HostConfig.Memory;$cpu+=[long]$v.HostConfig.NanoCpus;$roles+=$v.Id};if($v.Config.Labels.'agent-platform.domain' -ceq 'enforcement'){throw 'ENF_APP_ALREADY_RUNNING_PROBE_BUDGET_REASSESS_REQUIRED'}}
if($roles.Count -ne 6 -or ($mem+134217728) -gt 7516192768 -or ($cpu+250000000) -gt 4000000000 -or ($mem+939524096) -gt 7516192768 -or ($cpu+550000000) -gt 4000000000){throw 'READ_ONLY_PROBE_OR_FINAL_RESOURCE_BUDGET_REFUSED'}
foreach($name in @('storage44-actual-probe.json','storage44-actual-container.json','storage44-actual-volumes.json')){if(Test-Path "$a/$name"){throw 'EXCLUSIVE_REFRESH_EVIDENCE_ALREADY_EXISTS'}}
@{capturedAt=[DateTimeOffset]::Now.ToString('o');containerId=$helper;targetId=$p.targetId;source=$p.probeSource;restartOnlyPriorSuccessfulReadOnlyHelper=$true;noWritersOrOriginalAppRestart=$true;beforeMemoryBytes=$mem;beforeNanoCpus=$cpu;probeConcurrentNanoCpus=($cpu+250000000);projectedFinalNanoCpus=($cpu+550000000)}|ConvertTo-Json -Depth 7|Set-Content "$a/storage44-readonly-responsibility.json"
$null=Assert-FinalApproval $ready;$started=[DateTimeOffset]::Now;$code=$null
try{
 Invoke-FinalDocker @('start','-a',$helper)|Set-Content "$a/storage44-actual-probe.json" -Encoding utf8
 $actual=Read-FinalDeploymentJson "$a/storage44-actual-probe.json";Assert-FinalOwnedImageRecord $p $actual $p.targetId
 Invoke-FinalDocker @('inspect',$helper)|Set-Content "$a/storage44-actual-container.json" -Encoding utf8
 Invoke-FinalDocker @('volume','inspect',$p.imageVolume.Name,$p.boundedVolume.Name)|Set-Content "$a/storage44-actual-volumes.json" -Encoding utf8
 $code=0
}finally{@{scope='same exact prior successful readonly helper; refreshed BEFORE ENF startup, no app restart';exitCode=$code;startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o')}|ConvertTo-Json|Set-Content "$a/storage44-readonly-result.json"}
