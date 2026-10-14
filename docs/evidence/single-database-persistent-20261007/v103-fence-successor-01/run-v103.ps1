$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$primary='agent-platform-single-persistent-primary-dm8-02-20261007'
$restore='agent-platform-single-persistent-restore-dm8-20261007'
$task='VER-AGENT-SINGLE-DB-PERSISTENT-01'
$p=(docker inspect $primary|ConvertFrom-Json)[0]
$a=(docker inspect $restore|ConvertFrom-Json)[0]
if($a.State.Running){throw 'A5493 running; sequential 4GiB budget refuses start. Do not stop A.'}
if($p.Config.Labels.task -ne $task -or $p.Image -ne 'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760' -or $p.Config.User -ne '1000:1000' -or $p.HostConfig.Privileged -or $p.HostConfig.NanoCpus -gt 2000000000){throw 'Primary resource guard mismatch'}
$port=$p.HostConfig.PortBindings.'5236/tcp'
if($port.Count -ne 1 -or $port[0].HostIp -ne '127.0.0.1' -or $port[0].HostPort -ne '5492'){throw 'Exact primary port mismatch'}
$total=[long]$p.HostConfig.Memory
$helpers=@()
foreach($n in @('agent-platform-single-persistent-primary-helper-20261007','agent-platform-single-persistent-restore-helper-20261007')){
 $h=(docker inspect $n|ConvertFrom-Json)[0];if(-not $h.State.Running -or $h.Config.Labels.task -ne $task){throw 'Helper ownership/mount lost'};$total += [long]$h.HostConfig.Memory;$helpers+=@{name=$n;memory=$h.HostConfig.Memory;running=$true;id=$h.Id}
}
if($total -gt 4294967296){throw 'Sequential budget exceeds4GiB'}
foreach($f in (Get-Content -Raw "$PSScriptRoot/frozen-inputs.json"|ConvertFrom-Json)){
 if($f.path -like 'backend/*' -or $f.path -like 'db/*' -or $f.path -like '*StartCommandsDm8Harness.java'){
  if((Get-FileHash -LiteralPath (Join-Path $root $f.path)).Hash.ToLowerInvariant() -ne $f.sha256){throw 'Frozen execution input drift'}
 }
}
if(Test-Path "$PSScriptRoot/execution-result.json"){throw 'Original phase result exists; do not overwrite or blindly rerun'}
[ordered]@{recordedAt=(Get-Date -Format o);primaryId=$p.Id;primaryWasRunning=$p.State.Running;restore5493Running=$a.State.Running;helpers=$helpers;totalConfiguredSequentialMemoryBytes=$total;memoryLimitBytes=4294967296;cpuLimit=2;fixedImage=$p.Image;port='127.0.0.1:5492';noAStateChanges=$true}|ConvertTo-Json -Depth 6|Set-Content "$PSScriptRoot/resource-preflight.json" -Encoding utf8
if(-not $p.State.Running){if(@(Get-NetTCPConnection -LocalPort 5492 -State Listen -ErrorAction SilentlyContinue).Count){throw '5492 occupied'};docker start $primary|Out-Null;if($LASTEXITCODE -ne 0){throw 'Owned primary start failed'}}
Start-Sleep -Seconds 15
# Recheck A before database execution. Main has granted this sequential window.
$a=(docker inspect $restore|ConvertFrom-Json)[0];if($a.State.Running){docker stop $primary|Out-Null;throw 'A started; only own primary stopped to restore budget'}
function Plain($s){if($s -is [string]){$s=ConvertTo-SecureString $s};$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}}
$own=Import-Clixml "$PSScriptRoot/.private-vault/managed-secrets.dpapi.xml"
$old=Import-Clixml "$root/docs/evidence/single-database-persistent-20261007/v102-engineering-01/.private-vault/managed-secrets.dpapi.xml"
$parent=Import-Clixml "$root/docs/evidence/single-database-persistent-20261007/.private-vault/managed-secrets-round2.dpapi.xml"
$saved=@{}
function Put($k,$v){$script:saved[$k]=[Environment]::GetEnvironmentVariable($k,'Process');[Environment]::SetEnvironmentVariable($k,$v,'Process')}
Push-Location $root
try{
 Put 'V103_OUTPUT' $PSScriptRoot
 foreach($u in $own.Keys){Put "V103_${u}_PASSWORD" (Plain $own[$u])}
 foreach($u in @('STARTMAIN','STARTMAIN_SVC')){Put "V103_${u}_PASSWORD" (Plain $old[$u])}
 Put 'V103_SYSDBA_PASSWORD' (Plain $parent['SYSDBA'])
 $cp="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*"
 & D:/software/jdk-21/bin/java.exe -cp $cp cn.xinji.agentplatform.security.product.FencePresenceDm8Harness *> "$PSScriptRoot/java.log"
 $code=$LASTEXITCODE
 Get-Content "$PSScriptRoot/java.log"
 [ordered]@{recordedAt=(Get-Date -Format o);exitCode=$code;actualDm8=$true;databasePort=5492;sharedTargetUsed=$false;noAStateChanges=$true}|ConvertTo-Json|Set-Content "$PSScriptRoot/java-exit.json" -Encoding utf8
}finally{
 foreach($k in $saved.Keys){[Environment]::SetEnvironmentVariable($k,$saved[$k],'Process')}
 Pop-Location
 # Release only own5492 sequential window; no A start/stop operations.
 docker stop $primary|Out-Null
 $p=(docker inspect $primary|ConvertFrom-Json)[0]
 [ordered]@{recordedAt=(Get-Date -Format o);primaryId=$p.Id;running=$p.State.Running;pid=$p.State.Pid;noResourcesRemoved=$true;newSchemasRetained=@('V103NEG','V103EMPTY');noAStateChanges=$true}|ConvertTo-Json|Set-Content "$PSScriptRoot/final-primary-state.json" -Encoding utf8
}
