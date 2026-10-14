param()
$ErrorActionPreference='Stop'
$w='D:/daima/xinji/agent_platform';Set-Location $w
. "$w/scripts/lib/single-database-final-deployment.ps1"
$e="$w/docs/evidence/single-database-installation-20261009/approved-execution";$a="$e/enforcement-first-start44"
$r=Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json"
$null=Assert-FinalApproval $r.readyApplicationFields
$n=@($r.normalIdentityInputs.applications|Where-Object domain -eq enforcement)[0]
$art=Read-FinalDeploymentJson "$a/engineering-receipt44.json"
Assert-FinalFile @{path=$art.jar.path;sha256=$art.jar.sha256}
$chain=Read-FinalDeploymentJson "$e/shared-bootstrap25/original674-exit-chain.json"
foreach($ref in @($chain.receipt,$chain.originalExit,$chain.originalStdout)){Assert-FinalFile $ref}
if($chain.originalExitCode -ne 0 -or -not $chain.fullItemAndShaChainMatched -or (Test-Path -LiteralPath $chain.maintenanceLock) -or (Test-Path -LiteralPath $chain.grantLock)){throw 'ORIGINAL674_NOT_CLOSED'}
$tool='ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096';$target='f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f';$net='047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65'
$t= @(& docker inspect $tool|ConvertFrom-Json -AsHashtable)[0];if($LASTEXITCODE -ne 0 -or $t.Id -cne $tool -or -not $t.State.Running -or $t.Config.User -cne '1000:1000' -or -not $t.HostConfig.ReadonlyRootfs -or $t.HostConfig.Memory -ne 536870912 -or $t.HostConfig.NanoCpus -ne 250000000 -or $t.Mounts.Count -ne 1 -or $t.Mounts[0].Destination -cne '/jdbc' -or $t.Mounts[0].RW -or $t.Mounts[0].Source.Replace('\','/') -cne "$e/jdbc-native05"){throw 'EXACT_EXISTING_TOOL_REQUIRED'}
if($t.NetworkSettings.Networks.Count -ne 1 -or @($t.NetworkSettings.Networks.Values)[0].NetworkID -cne $net){throw 'EXACT_TOOL_NETWORK_REQUIRED'}
$d= @(& docker inspect $target|ConvertFrom-Json -AsHashtable)[0];if($LASTEXITCODE -ne 0 -or $d.Id -cne $target -or -not $d.State.Running){throw 'EXACT_LIVE_TARGET_REQUIRED'}
$ni= @(& docker network inspect $net|ConvertFrom-Json -AsHashtable)[0];if($LASTEXITCODE -ne 0 -or -not $ni.Internal){throw 'INTERNAL_NETWORK_REQUIRED'}
$mem=0L;$cpu=0L;$protected=@()
foreach($id in @(& docker ps -q)){$v= @(& docker inspect $id|ConvertFrom-Json -AsHashtable)[0];if($v.Config.Labels.task -ceq 'IMP-SINGLE-DB-FINAL-BASELINE-01'){$mem+=[long]$v.HostConfig.Memory;$cpu+=[long]$v.HostConfig.NanoCpus};if($v.Config.Labels.'agent-platform.domain' -ceq 'enforcement'){throw 'CONCURRENT_ENF_WRITER_REFUSED'};if($v.Id -in @($target,'9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f','6796847921a4c9542d8e2e2e7b203a72faae2a89d508ed6dd452ccb009baab2f','4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff','1bf91da6e10a789033932c7cd20aa806e7c6a81e3142509692247daa89af0df1',$tool)){$protected+=@{id=$v.Id;pid=$v.State.Pid;startedAt=$v.State.StartedAt;networks=$v.NetworkSettings.Networks}}}
if($mem -gt 7516192768 -or $cpu -gt 4000000000 -or $protected.Count -ne 6){throw 'RESOURCE_OR_PROTECTED_ROLES_REQUIRED'}
$mapping=Read-FinalDeploymentJson "$a/public-path-mapping44.json"
foreach($f in $mapping.files){Assert-FinalFile $f;$line=& docker exec $tool /usr/bin/sha256sum $f.target;if($LASTEXITCODE -ne 0 -or ([string]$line).Split(' ')[0].Trim() -cne $f.sha256){throw 'MOUNTED_SHA_REQUIRED'};$line|Add-Content "$a/mounted-public-shas44.log"}
foreach($ref in $n.managedSecretReferences.Values){Assert-FinalFile $ref}
if(Test-Path -LiteralPath "$a/bootstrap44-actual-result.json"){throw 'NO_BOOTSTRAP_REPLAY'}
$lease=$null;$old=@{};$code=$null;$attempt=$false;$started=[DateTimeOffset]::Now
try{
 $lease=[IO.File]::Open($chain.maintenanceLock,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
 $body=[Text.Encoding]::UTF8.GetBytes((@{owner='current-main-session';hostPid=$PID;domain='enforcement';operation='normal first shared-instance bootstrap';targetId=$target}|ConvertTo-Json -Compress));$lease.Write($body,0,$body.Length);$lease.Flush($true)
 $admin=@{path='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/SINGLE_DB_ADMIN_PASSWORD.dpapi';sha256='ef0e6ed0881f2d5efb2a6af1446b248155d93b84060a406c4e483453e3421eb4'}
 $old.SINGLE_DB_ADMIN_PASSWORD=[Environment]::GetEnvironmentVariable('SINGLE_DB_ADMIN_PASSWORD','Process');[Environment]::SetEnvironmentVariable('SINGLE_DB_ADMIN_PASSWORD',(Read-FinalManagedSecret $admin),'Process')
 $invStart=[DateTimeOffset]::Now
 & 'D:/software/nodejs/node.exe' scripts/inventory-single-database-lifecycle.mjs "$e/primary-migration-config07.json" "$a/fresh-pre-bootstrap44.json" *> "$a/fresh-pre-bootstrap44.log"
 $ic=$LASTEXITCODE;@{startedAt=$invStart.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$ic;readOnly=$true}|ConvertTo-Json|Set-Content "$a/fresh-pre-bootstrap44-result.json"
 if($ic -ne 0){throw 'FRESH_INVENTORY_REQUIRED'}
 & 'D:/software/nodejs/node.exe' "$a/verify-fresh-bootstrap-attempt44.mjs" *> "$a/fresh-prebootstrap-permission44.log";if($LASTEXITCODE -ne 0){throw 'FRESH_FULL_PERMISSION_AND_EMPTY_IDENTITY_REQUIRED'}
 $null=Assert-FinalApproval $r.readyApplicationFields
 if(([DateTimeOffset]::Now-$invStart).TotalMinutes -gt 15){throw 'STALE_INVENTORY'}
 $args=@('exec')
 foreach($key in $n.environmentBindings.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$n.environmentBindings[$key],'Process');$args+=@('--env',$key)}
 foreach($key in $n.managedSecretReferences.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,(Read-FinalManagedSecret $n.managedSecretReferences[$key]),'Process');$args+=@('--env',$key)}
 @{format='NORMAL_ENF_FIRST_BOOTSTRAP_RESPONSIBILITY/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');owner='current-main-session';hostPid=$PID;toolContainerId=$tool;targetId=$target;networkId=$net;jar=$art.jar;mainClass=$n.mainClass;mappingEvidence="$a/public-path-mapping44.json";freshPermissionEvidence="$a/fresh-prebootstrap-permission-attempt44.json";protectedBefore=$protected;memoryBytes=$mem;nanoCpus=$cpu;existingToolReused=$true;dclAllowed=$false;membersOrGrantsAllowed=$false;noRetryWithoutReconciliation=$true}|ConvertTo-Json -Depth 12|Set-Content "$a/bootstrap44-writer-responsibility.json"
 $args+=@($tool,'/opt/java/openjdk/bin/java','-Xmx384m','-XX:ActiveProcessorCount=1',"-Dloader.main=$($n.mainClass)",'-cp','/jdbc/enforcement44/runtime.jar','org.springframework.boot.loader.launch.PropertiesLauncher','--shared-instance','/jdbc/enforcement44/identity-bootstrap.json')
  $baseline=Read-FinalDeploymentJson "$a/fresh-resource44.json"
 foreach($p in $protected){$orig=@($baseline.roles|Where-Object id -ceq $p.id)[0];if($null -eq $orig -or $orig.state.Pid -ne $p.pid -or $orig.state.StartedAt -cne $p.startedAt){throw 'PROTECTED_ROLE_BASELINE_DRIFT'}}
 $null=Assert-FinalApproval $r.readyApplicationFields
 $attempt=$true
 & docker @args *> "$a/bootstrap44-actual.log";$code=$LASTEXITCODE
 if($code -ne 0){throw 'BOOTSTRAP_FAILED_PRESERVE_RECONCILE_NO_RETRY'}
}finally{
 foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
 @{format='ACTUAL_ENF_NORMAL_FIRST_BOOTSTRAP_ATTEMPT/v1';startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');toolContainerId=$tool;targetId=$target;jar=$art.jar;bootstrapInvoked=$attempt;exitCode=$code;postconditionsStillRequired=$true;toolRestarted=$false;consultationRestarted=$false;retryPerformed=$false;modelCalls=0}|ConvertTo-Json -Depth 8|Set-Content "$a/bootstrap44-actual-result.json"
  if($null -ne $lease){$lease.Dispose();Remove-Item -LiteralPath $chain.maintenanceLock}
}



