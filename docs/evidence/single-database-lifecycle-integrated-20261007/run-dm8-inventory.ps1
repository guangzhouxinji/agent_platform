param([string]$RunName="handoff-01")
$ErrorActionPreference='Stop'
if($RunName -notmatch '^[a-z][a-z0-9-]{0,31}$'){throw 'Safe dedicated run name required'}
foreach($kind in @('config','cli','exit','live')){if(Test-Path -LiteralPath "$PSScriptRoot/dm8-inventory-$kind-$RunName.json"){throw 'Preserve original evidence; select a new run name before any DB start'}}
$root=(Resolve-Path "$PSScriptRoot/../../..").Path
$primary='e5e5000c0d55a3da109f7963067416bd12e0014800391856cb74c2647318d1b2'
$restore='a2df7cece3db3f81dbaeae5d72564092a0f24b975a7afdfba3fb2751035f3233'
$owner='VER-AGENT-SINGLE-DB-PERSISTENT-01'
$cs=@(docker inspect $primary $restore | ConvertFrom-Json)
if($cs.Count -ne 2 -or ($cs|Where-Object {$_.State.Running}).Count -ne 0){throw 'Both engineering DBs must be stopped before exclusive inspection'}
$hs=@(docker inspect 890fe1df39678b712ef2c42c52e326531f7a1a612390d67b58239536107345ce c1b0d00b5eb6019129ac0d2bb4b58a9617ae4b2e4983e2c688d8c2f5f9b011e9 | ConvertFrom-Json)
for($n=0;$n -lt 2;$n++) {
 $c=$cs[$n];$h=$hs[$n];$volume=if($n -eq 0){'agent-platform-single-persistent-primary-ext4-20261007'}else{'agent-platform-single-persistent-restore-ext4-20261007'}
 $v=(docker volume inspect $volume|ConvertFrom-Json)[0]
 if($c.Config.Labels.task -ne $owner -or $c.HostConfig.Memory -ne 4026531840 -or $c.HostConfig.NanoCpus -ne 2000000000 -or $c.HostConfig.Privileged -or $c.Config.User -ne '1000:1000' -or $c.Image -ne 'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760'){throw 'Exact DB ownership/limits/image mismatch'}
 if(-not $h.State.Running -or $h.Config.Labels.task -ne $owner -or $h.HostConfig.Memory -ne 134217728 -or $h.HostConfig.NanoCpus -ne 2000000000 -or $v.Labels.task -ne $owner -or $v.Options.device -ne "/proc/$($h.State.Pid)/root/dev/owned-loop" -or -not ($c.Mounts|Where-Object {$_.Name -eq $volume -and $_.Destination -eq '/opt/dmdbms/data'})){throw 'Exact helper PID/volume/mount mismatch'}
 $status=docker exec --user 1000:1000 $h.Id cat /proc/1/status
 if($LASTEXITCODE -ne 0 -or -not ($status|Select-String '^Uid:\s+1000\s+1000\s+1000\s+1000') -or @($status|Select-String '^Cap(Inh|Prm|Eff|Bnd|Amb):\s+0000000000000000').Count -ne 5){throw 'Resident helper must be capless UID1000'}
}
$config=@{targetId=$primary;schemas=@('CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH');accounts=@('CONPROD','CONPROD_SVC','CONAUTH','CONAUTH_SVC','ENFPROD','ENFPROD_SVC','ENFAUTH','ENFAUTH_SVC','ADJPROD','ADJPROD_SVC','ADJAUTH','ADJAUTH_SVC');inventoryJdbc=@{java='D:/software/jdk-21/bin/java.exe';classpath="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*";mainClass='LifecycleJdbcHarness';url='jdbc:dm://127.0.0.1:5492';user='SYSDBA';passwordEnv='LIFECYCLE_INSPECT_PASSWORD'}}
$config['deployment']=Get-Content -Raw "$root/docs/evidence/single-database-persistent-20261007/deployment-preflight-engineering-configuration.json"|ConvertFrom-Json
$config|ConvertTo-Json -Depth 8|Set-Content -Encoding utf8NoBOM "$PSScriptRoot/dm8-inventory-config-$RunName.json"
$vault=Import-Clixml "$root/docs/evidence/single-database-persistent-20261007/.private-vault/managed-secrets-round2.dpapi.xml"
$secure=$vault['SYSDBA'];if($secure -is [string]){$secure=ConvertTo-SecureString $secure}
$bstr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$saved=$env:LIFECYCLE_INSPECT_PASSWORD
try {
 $env:LIFECYCLE_INSPECT_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
 docker start $primary > "$PSScriptRoot/dm8-start-$RunName.log"
 if($LASTEXITCODE -ne 0){throw 'Owned start failed'}
 $ready=$false
 for($n=0;$n -lt 60;$n++){Start-Sleep -Seconds 2;$client=[Net.Sockets.TcpClient]::new();try{$client.Connect('127.0.0.1',5492);$ready=$true;break}catch{}finally{$client.Dispose()}}
 if(-not $ready){throw 'DM8 did not become ready'}
 node "$root/scripts/inventory-single-database-lifecycle.mjs" "$PSScriptRoot/dm8-inventory-config-$RunName.json" "$PSScriptRoot/dm8-inventory-live-$RunName.json" > "$PSScriptRoot/dm8-inventory-cli-$RunName.log"
 $code=$LASTEXITCODE
 @{exitCode=$code;actualDm8=$true;writeStatements=0;startedAt=(Get-Date).ToString('o');wholeSnapshotProven=$false}|ConvertTo-Json|Set-Content "$PSScriptRoot/dm8-inventory-exit-$RunName.json"
}finally {
 $env:LIFECYCLE_INSPECT_PASSWORD=$saved;[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
 # Only the exact self-owned instance started here is stopped. Never alter any other service.
 docker stop --time 30 $primary > "$PSScriptRoot/dm8-stop-$RunName.log"
 docker inspect $primary $restore | Set-Content "$PSScriptRoot/resources-after-inventory-$RunName.json"
}
