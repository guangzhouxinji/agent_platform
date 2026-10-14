$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$primary='agent-platform-single-persistent-primary-dm8-02-20261007'
$other='agent-platform-single-persistent-restore-dm8-20261007'
function Guard {
 $p=(docker inspect $primary|ConvertFrom-Json)[0]
 # State-only budget observation of paired resource; never connect to/operate A's database.
 $a=(docker inspect --format '{{.State.Running}}' $other).Trim()
 if($a -ne 'false'){throw 'A5493 running; no5492 start, wait for sequential budget'}
 if($p.Config.Labels.task -ne 'VER-AGENT-SINGLE-DB-PERSISTENT-01' -or $p.Image -ne 'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760' -or $p.Config.User -ne '1000:1000' -or $p.HostConfig.Privileged -or $p.HostConfig.NanoCpus -gt 2000000000){throw 'Owned primary guard failed'}
 $total=[long]$p.HostConfig.Memory
 foreach($n in @('agent-platform-single-persistent-primary-helper-20261007','agent-platform-single-persistent-restore-helper-20261007')){$h=(docker inspect $n|ConvertFrom-Json)[0];if(-not $h.State.Running -or $h.Config.Labels.task -ne 'VER-AGENT-SINGLE-DB-PERSISTENT-01'){throw 'Owned mount helper lost'};$total += [long]$h.HostConfig.Memory}
 if($total -gt 4294967296){throw 'Sequential4GiB budget exceeded'}
 $b=$p.HostConfig.PortBindings.'5236/tcp';if($b.Count -ne 1 -or $b[0].HostIp -ne '127.0.0.1' -or $b[0].HostPort -ne '5492'){throw 'Exact loopback port guard failed'}
 return $p
}
function StartOwned {
 $p=Guard
 if(-not $p.State.Running){if(@(Get-NetTCPConnection -LocalPort 5492 -State Listen -ErrorAction SilentlyContinue).Count){throw '5492 occupied'};docker start $primary|Out-Null;if($LASTEXITCODE){throw 'Primary start failed'};Start-Sleep -Seconds 15}
 Guard|Out-Null
}
function Plain($s){if($s -is [string]){$s=ConvertTo-SecureString $s};$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
foreach($f in (Get-Content -Raw "$PSScriptRoot/frozen-inputs.json"|ConvertFrom-Json)){if($f.path -like 'backend/*' -or $f.path -like 'db/*' -or $f.path -like 'data/*'){if((Get-FileHash -LiteralPath (Join-Path $root $f.path)).Hash.ToLowerInvariant() -ne $f.sha256){throw 'Frozen source/input drift'}}}
if(Test-Path "$PSScriptRoot/native-explicit-remap-probe-result.json"){throw 'Mapping probe already exists; do not rerun'}
$p=Guard
[ordered]@{recordedAt=(Get-Date -Format o);task='VER-ADJ-SINGLE-DB-REPORT-RECOVERY-01';primaryId=$p.Id;primaryWasRunning=$p.State.Running;pairedDatabaseRunning=$false;configuredTotalMemoryBytes=4294967296;cpuEach=2;fixedImage=$p.Image;boundEndpoint='127.0.0.1:5492';existingHardLimitedLoopUnchanged=$true;noAConnectionOrStateChanges=$true}|ConvertTo-Json|Set-Content "$PSScriptRoot/resource-preflight-mapping.json" -Encoding utf8
$vault=Import-Clixml "$PSScriptRoot/.private-vault/managed-secrets.dpapi.xml"
$parent=Import-Clixml "$root/docs/evidence/single-database-persistent-20261007/.private-vault/managed-secrets-round2.dpapi.xml"
$saved=@{}
function Put($k,$v){$script:saved[$k]=[Environment]::GetEnvironmentVariable($k,'Process');[Environment]::SetEnvironmentVariable($k,$v,'Process')}
$cp="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*"
function Phase($phase){Guard|Out-Null;& D:/software/jdk-21/bin/java.exe -cp $cp cn.xinji.agentplatform.adjudication.casework.AdjudicationPersistentHarness $phase *> "$PSScriptRoot/$phase-java.log";$code=$LASTEXITCODE;Get-Content "$PSScriptRoot/$phase-java.log";if($code){throw "Owned product phase $phase returned $code; preserve original"}}
function Envelope($action){Guard|Out-Null;& D:/software/jdk-21/bin/java.exe -cp $cp cn.xinji.agentplatform.adjudication.casework.AdjudicationEnvelope $action *> "$PSScriptRoot/envelope-$action.log";$code=$LASTEXITCODE;Get-Content "$PSScriptRoot/envelope-$action.log";if($code){throw "Owned envelope $action failed; preserve original"}}
Push-Location $root
try{
 Put 'ADJ_OUTPUT' $PSScriptRoot
 Put 'ADJ_RUN_COORDINATE' 'r3'
 foreach($u in @('ADJENGMAIN','ADJENGMAIN_SVC','ADJENGREST','ADJENGREST_SVC','ADJENGAUTH','ADJENGAUTH_SVC')){Put "ADJ_${u}_PASSWORD" (Plain $vault[$u])}
 Put 'ADJ_ENVELOPE_KEY' (Plain $vault['ENVELOPE_KEY'])
 Put 'ADJ_SYSDBA_PASSWORD' (Plain $parent['SYSDBA'])
 StartOwned
 docker exec --user 1000:1000 $primary /bin/sh -c 'df -B1 /opt/dmdbms/data; grep " /opt/dmdbms/data " /proc/mounts' *> "$PSScriptRoot/actual-loop-capacity-mapping.log"
 & D:/software/jdk-21/bin/java.exe -cp $cp cn.xinji.agentplatform.adjudication.casework.AdjudicationImportMappingProbe *> "$PSScriptRoot/native-mapping-probe-java.log"
 $code=$LASTEXITCODE;Get-Content "$PSScriptRoot/native-mapping-probe-java.log";if($code){throw 'Explicit mapping unavailable; evidence kept and source/protected checked'}
 Phase 'after-restore'
 [ordered]@{recordedAt=(Get-Date -Format o);exitCode=0;actualOrchestrationSucceeded=$true;noAStateChanges=$true}|ConvertTo-Json|Set-Content "$PSScriptRoot/orchestration-result.json" -Encoding utf8
}finally{
 foreach($k in $saved.Keys){[Environment]::SetEnvironmentVariable($k,$saved[$k],'Process')}
 Pop-Location
 docker stop $primary|Out-Null
 $p=(docker inspect $primary|ConvertFrom-Json)[0]
 [ordered]@{recordedAt=(Get-Date -Format o);primaryId=$p.Id;running=$p.State.Running;pid=$p.State.Pid;noAStateChanges=$true;noResourcesDeleted=$true}|ConvertTo-Json|Set-Content "$PSScriptRoot/final-primary-state-mapping.json" -Encoding utf8
}




