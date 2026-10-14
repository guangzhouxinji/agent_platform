param([ValidateSet('ProductIdentityDm8SchemaTest','ProductTaskDm8PhysicalTest','ProductConsultationModelFenceDm8Test')][string]$Test,[ValidatePattern('^[a-z0-9-]+$')][string]$Attempt)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../../..").Path
if(Test-Path -LiteralPath "$PSScriptRoot/$Attempt-exit.json"){throw 'Never replay an existing attempt'}
$before=Get-Content "$PSScriptRoot/verify-before-exit.json" -Raw|ConvertFrom-Json
if($before.exitCode -ne 0){throw 'Current baseline not verified'}
$primary='e5e5000c0d55a3da109f7963067416bd12e0014800391856cb74c2647318d1b2'
$restore='a2df7cece3db3f81dbaeae5d72564092a0f24b975a7afdfba3fb2751035f3233'
$owner='VER-AGENT-SINGLE-DB-PERSISTENT-01'
$cs=@(docker inspect $primary $restore|ConvertFrom-Json)
$hs=@(docker inspect 890fe1df39678b712ef2c42c52e326531f7a1a612390d67b58239536107345ce c1b0d00b5eb6019129ac0d2bb4b58a9617ae4b2e4983e2c688d8c2f5f9b011e9|ConvertFrom-Json)
if($cs.Count -ne 2 -or ($cs|Where-Object {$_.State.Running}).Count -ne 0){throw 'Sequential owned DBs must both begin stopped'}
for($n=0;$n -lt 2;$n++){
 $c=$cs[$n];$h=$hs[$n];$volume=if($n -eq 0){'agent-platform-single-persistent-primary-ext4-20261007'}else{'agent-platform-single-persistent-restore-ext4-20261007'}
 $port=if($n -eq 0){'5492'}else{'5493'};$v=(docker volume inspect $volume|ConvertFrom-Json)[0]
 if($c.Config.Labels.task -ne $owner -or $c.HostConfig.Memory -ne 4026531840 -or $c.HostConfig.MemorySwap -ne 4026531840 -or $c.HostConfig.NanoCpus -ne 2000000000 -or -not $c.HostConfig.ReadonlyRootfs -or $c.HostConfig.Privileged -or $c.Config.User -ne '1000:1000' -or $c.Image -ne 'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760'){throw 'Exact owned DB limits/image/owner mismatch'}
 $binding=$c.HostConfig.PortBindings.'5236/tcp'
 if($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne $port){throw 'No default DB fallback'}
 if(-not $h.State.Running -or $h.Config.Labels.task -ne $owner -or $h.HostConfig.Memory -ne 134217728 -or $h.HostConfig.NanoCpus -ne 2000000000 -or $v.Labels.task -ne $owner -or $v.Options.device -ne "/proc/$($h.State.Pid)/root/dev/owned-loop" -or -not ($c.Mounts|Where-Object {$_.Name -eq $volume -and $_.Destination -eq '/opt/dmdbms/data'})){throw 'Exact bounded persistent mount mismatch'}
 $status=docker exec --user 1000:1000 $h.Id cat /proc/1/status
 if($LASTEXITCODE -ne 0 -or -not ($status|Select-String '^Uid:\s+1000\s+1000\s+1000\s+1000') -or @($status|Select-String '^Cap(Inh|Prm|Eff|Bnd|Amb):\s+0000000000000000').Count -ne 5){throw 'Capless helper required'}
}
$buildLock=$null
$dir=(Get-Content "$PSScriptRoot/current-source-inputs.json" -Raw|ConvertFrom-Json).isolatedDirectory
$buildLock=[IO.File]::Open("$dir/owned-physical-tests.lock",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$vault=Import-Clixml "$root/docs/evidence/single-database-persistent-20261007/.private-vault/managed-secrets-round2.dpapi.xml"
$secure=$vault['SYSDBA'];if($secure -is [string]){$secure=ConvertTo-SecureString $secure};$bstr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$keys=@('DM8_JDBC_URL','DM8_JDBC_USER','DM8_JDBC_PASSWORD','CON_MODEL_FENCE_DM8_URL','CON_MODEL_FENCE_DM8_USER','CON_MODEL_FENCE_DM8_PASSWORD','JAVA_HOME','MAVEN_OPTS')
$saved=@{};foreach($key in $keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
foreach($key in @('DM8_JDBC_URL','DM8_JDBC_USER','DM8_JDBC_PASSWORD','CON_MODEL_FENCE_DM8_URL','CON_MODEL_FENCE_DM8_USER','CON_MODEL_FENCE_DM8_PASSWORD')){if($saved[$key]){throw 'Refuse existing DB environment'} }
$started=$false;$process=$null;$password=$null
try{
 $password=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
 $env:DM8_JDBC_URL='jdbc:dm://127.0.0.1:5492';$env:DM8_JDBC_USER='SYSDBA';$env:DM8_JDBC_PASSWORD=$password
 if($Test -eq 'ProductConsultationModelFenceDm8Test'){$env:CON_MODEL_FENCE_DM8_URL=$env:DM8_JDBC_URL;$env:CON_MODEL_FENCE_DM8_USER=$env:DM8_JDBC_USER;$env:CON_MODEL_FENCE_DM8_PASSWORD=$password}
 $env:JAVA_HOME='D:\software\jdk-21';$env:MAVEN_OPTS='-Xmx512m'
 $admission=@{task='VER-CON-DM8-ASSUMPTION-EXECUTION-01';test=$Test;attempt=$Attempt;observedAt=(Get-Date -Format o);databaseId=$primary;otherDatabaseStopped=$true;memoryTotalConfiguredBytes=4294967296;cpuPerContainer=2;managedSecretReference='existing DPAPI SYSDBA; no secret value recorded';engineeringApproval='single-database-engineering-20261007/user-engineering-approval.json plus current continuation';newResources=0;currentOrExpiredBusinessObjectsRead=0;modelDispatches=0;onlyNewRandomTestSchemas=$true;approvedEngineeringHasNoSeparateRecordedDeadline=$true}
 $admission|ConvertTo-Json|Set-Content "$PSScriptRoot/$Attempt-admission.json"
 docker start $primary > "$PSScriptRoot/$Attempt-owned-start.log";if($LASTEXITCODE -ne 0){throw 'Owned start failed'};$started=$true
 $ready=$false;for($n=0;$n -lt 60;$n++){Start-Sleep -Seconds 2;$client=[Net.Sockets.TcpClient]::new();try{$client.Connect('127.0.0.1',5492);$ready=$true;break}catch{}finally{$client.Dispose()}}
 if(-not $ready){throw 'Owned DM8 startup timeout'}
 $cp="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*"
 & "$env:JAVA_HOME/bin/java.exe" -Xmx128m -cp $cp OwnedSchemaInventory > "$PSScriptRoot/$Attempt-inventory-before.json" 2> "$PSScriptRoot/$Attempt-inventory-before-error.log";if($LASTEXITCODE -ne 0){throw 'Baseline inventory failed'}
 $private=Join-Path $dir ("conditional-tests/"+$Attempt);if(Test-Path -LiteralPath $private){throw 'Preserve existing private attempt'};New-Item -ItemType Directory $private|Out-Null
 $report="$dir/backend/apps/platform-api/target/surefire-reports"
 $exactReport="$report/TEST-cn.xinji.agentplatform.security.product.$Test.xml"
 if(Test-Path -LiteralPath $exactReport){throw "Exact selected raw report already exists; reconcile, never overwrite"}
 $reportStart=Get-Date
 $stdout="$private/maven-stdout.raw.log";$stderr="$private/maven-stderr.raw.log"
 $args=@('-f',"$dir/backend/pom.xml","-Dmaven.multiModuleProjectDirectory=$dir",'-pl','apps/platform-api','-am',"-Dplatform.api.outputDirectory=$dir/backend/apps/platform-api/target/consultation-product-classes","-Dtest=$Test",'-Dsurefire.failIfNoSpecifiedTests=false','-DargLine=-Xmx384m','test')
 $process=Start-Process 'D:/software/apache-maven-3.9.16/bin/mvn.cmd' -ArgumentList $args -WorkingDirectory $dir -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
 if(-not $process.WaitForExit(600000)){throw 'Bounded Maven timeout; keep partial test scene'};$process.Refresh();$exit=$process.ExitCode
 foreach($raw in @($stdout,$stderr)){
  $text=[IO.File]::ReadAllText($raw);$redacted=$text.Replace($password,'[MANAGED_SECRET_REDACTED]');$dest=if($raw -eq $stdout){"$Attempt-test.log"}else{"$Attempt-test-error.log"};[IO.File]::WriteAllText("$PSScriptRoot/$dest",$redacted)
 }
 $reports=@();foreach($file in Get-Item -LiteralPath $exactReport -ErrorAction SilentlyContinue){
  if($file.LastWriteTime -lt $reportStart){throw 'Stale report refused'}
  Copy-Item -LiteralPath $file.FullName -Destination "$private/$($file.Name)"

  $text=[IO.File]::ReadAllText($file.FullName).Replace($password,'[MANAGED_SECRET_REDACTED]');$target="$PSScriptRoot/$Attempt-$($file.Name)";[IO.File]::WriteAllText($target,$text)
  [xml]$xml=$text;$suite=$xml.testsuite;$reports+=@{name=$suite.name;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped;rawPath=$file.FullName;rawSha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower();publicPath=$target}
 }
 & "$env:JAVA_HOME/bin/java.exe" -Xmx128m -cp $cp OwnedSchemaInventory > "$PSScriptRoot/$Attempt-inventory-after.json" 2> "$PSScriptRoot/$Attempt-inventory-after-error.log";if($LASTEXITCODE -ne 0){throw 'After inventory failed'}
 $a=Get-Content "$PSScriptRoot/$Attempt-inventory-before.json" -Raw|ConvertFrom-Json;$b=Get-Content "$PSScriptRoot/$Attempt-inventory-after.json" -Raw|ConvertFrom-Json
 $unchanged=($a.tableInventorySha256 -eq $b.tableInventorySha256 -and $a.temporarySchemaCount -eq $b.temporarySchemaCount)
 @{exitCode=$exit;test=$Test;attempt=$Attempt;observedAt=(Get-Date -Format o);reports=$reports;existingSixSchemaTableInventoryUnchanged=$unchanged;rawStdout=$stdout;rawStdoutSha256=(Get-FileHash -LiteralPath $stdout -Algorithm SHA256).Hash.ToLower();productionOperations=0;modelCalls=0;realDm8=$true}|ConvertTo-Json -Depth 6|Set-Content "$PSScriptRoot/$Attempt-exit.json"
 if(-not $unchanged){throw 'Owned schema inventory changed'}
 if($exit -ne 0){throw 'Real test failed; preserved raw evidence'}
 if($reports.Count -ne 1 -or $reports[0].skipped -ne 0 -or $reports[0].tests -lt 1){throw 'Missing executed evidence or condition still skipped'}
}finally{
 if($process -and -not $process.HasExited){Stop-Process -Id $process.Id -ErrorAction SilentlyContinue}
 foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')};$password=$null;[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
 if($started){docker stop --time 30 $primary > "$PSScriptRoot/$Attempt-owned-stop.log"}
 docker inspect --format '{{.Id}} {{.State.Status}}' $primary $restore > "$PSScriptRoot/$Attempt-owned-after.txt"
 if($buildLock){$buildLock.Dispose()}
}
