param([Parameter(Mandatory=$true)][ValidateSet('initialize','after-restart','diagnostic')][string]$Phase)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$name='agent-platform-single-persistent-primary-dm8-02-20261007'
$i=(docker inspect $name | ConvertFrom-Json)[0]
$other=(docker inspect 'agent-platform-single-persistent-restore-dm8-20261007' | ConvertFrom-Json)[0]
if($other.State.Running -or -not $i.State.Running -or $i.Config.Labels.task -ne 'VER-AGENT-SINGLE-DB-PERSISTENT-01' -or $i.Config.User -ne '1000:1000' -or $i.HostConfig.Privileged -or $i.Image -ne 'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760'){throw 'Exact sequential owned running resource required'}
$total=[long]$i.HostConfig.Memory
foreach($name in @('agent-platform-single-persistent-primary-helper-20261007','agent-platform-single-persistent-restore-helper-20261007')){$h=(docker inspect $name | ConvertFrom-Json)[0];if(-not $h.State.Running -or $h.Config.Labels.task -ne 'VER-AGENT-SINGLE-DB-PERSISTENT-01'){throw 'Mount helper ownership lost'};$total += [long]$h.HostConfig.Memory}
if($total -gt 4294967296 -or $i.HostConfig.NanoCpus -gt 2000000000){throw 'Owned budget exceeded'}
foreach($f in (Get-Content -Raw "$PSScriptRoot/frozen-inputs.json" | ConvertFrom-Json)){
 if($f.path -like 'backend/*' -or $f.path -like 'db/*'){if((Get-FileHash (Join-Path $root $f.path)).Hash.ToLowerInvariant() -ne $f.sha256){throw 'Frozen backend or SQL changed; coordinate before running'}}
}
function Plain($ss){if($ss -is [string]){$ss=ConvertTo-SecureString $ss};$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
$own=Import-Clixml "$PSScriptRoot/.private-vault/managed-secrets.dpapi.xml"
$parent=Import-Clixml (Join-Path $PSScriptRoot '../.private-vault/managed-secrets-round2.dpapi.xml')
$saved=@{}
function Put($n,$v){$script:saved[$n]=[Environment]::GetEnvironmentVariable($n,'Process');[Environment]::SetEnvironmentVariable($n,$v,'Process')}
Push-Location $root
try{
 Put 'V102_ENGINEERING_OWNER' 'VER-CON-COMPARISON-START-DM8-01'
 Put 'V102_ENGINEERING_URL' 'jdbc:dm://127.0.0.1:5492'
 Put 'V102_ENGINEERING_OUTPUT' $PSScriptRoot
 foreach($u in @('STARTMAIN','STARTMAIN_SVC','STARTEMPTY','STARTEMPTY_SVC')){Put "V102_ENGINEERING_${u}_PASSWORD" (Plain $own[$u])}
 Put 'V102_ENGINEERING_SYSDBA_PASSWORD' (Plain $parent['SYSDBA'])
 $cp="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*"
 & D:/software/jdk-21/bin/java.exe -cp $cp cn.xinji.agentplatform.security.product.StartCommandsDm8Harness $Phase *> "$PSScriptRoot/$Phase-java.log"
 $code=$LASTEXITCODE
 Get-Content "$PSScriptRoot/$Phase-java.log"
 [ordered]@{recordedAt=(Get-Date -Format o);phase=$Phase;exitCode=$code;containerId=$i.Id;actualDm8=$true;configuredMemoryBytes=$total;frozenBackendSqlShaMatched=$true} | ConvertTo-Json | Set-Content "$PSScriptRoot/$Phase-execution-result.json" -Encoding utf8
 if($code -ne 0){Write-Output "Engineering phase returned $code; preserve anomalies; do not alter migrations."}
}finally{foreach($n in $saved.Keys){[Environment]::SetEnvironmentVariable($n,$saved[$n],'Process')};Pop-Location}
