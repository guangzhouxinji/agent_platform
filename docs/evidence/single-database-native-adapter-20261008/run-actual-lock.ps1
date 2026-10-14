$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../../..").Path
if(Test-Path -LiteralPath "$PSScriptRoot/actual-lock-result.json"){throw 'Preserve original probe; do not repeat'}
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

$vault=Import-Clixml "$root/docs/evidence/single-database-persistent-20261007/.private-vault/managed-secrets-round2.dpapi.xml"
$secure=$vault['SYSDBA'];if($secure -is [string]){$secure=ConvertTo-SecureString $secure}
$bstr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$keys=@('LIFECYCLE_FENCE_URL','LIFECYCLE_FENCE_USER','LIFECYCLE_FENCE_PASSWORD','ENGINEERING_JAVA','ENGINEERING_CP')
foreach($key in $keys){if([Environment]::GetEnvironmentVariable($key,'Process')){throw 'Preserve existing environment'}}
try {
 $env:LIFECYCLE_FENCE_URL='jdbc:dm://127.0.0.1:5492';$env:LIFECYCLE_FENCE_USER='SYSDBA';$env:LIFECYCLE_FENCE_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
 $env:ENGINEERING_JAVA='D:/software/jdk-21/bin/java.exe';$env:ENGINEERING_CP="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*"
 docker start $primary > "$PSScriptRoot/owned-start.log"
 if($LASTEXITCODE -ne 0){throw 'Owned isolated start failed'}
 $ready=$false
 for($n=0;$n -lt 60;$n++){Start-Sleep -Seconds 2;$client=[Net.Sockets.TcpClient]::new();try{$client.Connect('127.0.0.1',5492);$ready=$true;break}catch{}finally{$client.Dispose()}}
 if(-not $ready){throw 'Owned DM8 not ready'}
 & $env:ENGINEERING_JAVA -Xmx128m -cp $env:ENGINEERING_CP ActualDmFenceProbe > "$PSScriptRoot/actual-lock-result.json" 2> "$PSScriptRoot/actual-lock-error.log"
 @{exit=$LASTEXITCODE;observedAt=(Get-Date -Format o);purpose='ISOLATED_ENGINEERING_LOCK_SEMANTICS_NO_BACKUP_OR_DATA_READ';currentServiceChanged=$false}|ConvertTo-Json|Set-Content "$PSScriptRoot/actual-lock-exit.json"
}finally {
 foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$null,'Process')};[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
 docker stop --time 30 $primary > "$PSScriptRoot/owned-stop.log"
 docker inspect --format '{{.Id}} {{.State.Status}}' $primary $restore > "$PSScriptRoot/owned-after.txt"
}
