param()
$ErrorActionPreference='Stop'
$workspace='D:/daima/xinji/agent_platform'
Set-Location $workspace
. "$workspace/scripts/lib/single-database-final-deployment.ps1"
$e="$workspace/docs/evidence/single-database-installation-20261009/approved-execution"
$out="$e/shared-bootstrap25"
$r=Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json"
$ready=$r.readyApplicationFields
$null=Assert-FinalApproval $ready
$normal=@($r.normalIdentityInputs.applications|Where-Object domain -eq consultation)[0]
$refs=@($r.containerIdentityInputs.applications|Where-Object domain -eq consultation)[0]
$artifact=Read-FinalDeploymentJson "$out/artifact-inspection.json"
Assert-FinalFile @{path=$artifact.jar.path;sha256=$artifact.jar.sha256}
foreach($f in $refs.readonlyToolMountReferences){Assert-FinalFile $f}
foreach($p in $normal.managedSecretReferences.GetEnumerator()){Assert-FinalFile $p.Value}
$exit=Read-FinalDeploymentJson "$out/original674-exit-chain.json"
if($exit.originalExitCode -ne 0 -or -not $exit.fullItemAndShaChainMatched -or (Test-Path $exit.maintenanceLock) -or (Test-Path $exit.grantLock)){throw 'ORIGINAL_AUTHORIZATION_NOT_EXITED'}
$fresh=Read-FinalDeploymentJson "$out/fresh-prebootstrap-permission-verification02.json"
$when=Read-FinalDeploymentJson "$out/fresh-prebootstrap-full-inventory02-result.json"
if(-not $fresh.rawFinalInventoryMatched -or $fresh.presentRequiredObjectGrants -ne 674 -or $when.exitCode -ne 0 -or ([DateTimeOffset]::UtcNow-[DateTimeOffset]$when.finishedAt).TotalMinutes -gt 15){throw 'FRESH_FULL_PERMISSION_REQUIRED'}
$network=(& docker network inspect $ready.network --format '{{.Id}}').Trim()
if($LASTEXITCODE -ne 0 -or $network -cne $ready.networkId){throw 'EXACT_NETWORK_REQUIRED'}
$target=(& docker inspect $r.normalIdentityInputs.targetId --format '{{.Id}}').Trim()
if($LASTEXITCODE -ne 0 -or $target -cne $r.normalIdentityInputs.targetId){throw 'EXACT_TARGET_REQUIRED'}
$running=@(& docker ps -q)
$mem=0L;$cpu=0L
foreach($id in $running){$inspect=(& docker inspect $id|ConvertFrom-Json)[0];if($inspect.Config.Labels.task -ceq 'IMP-SINGLE-DB-FINAL-BASELINE-01'){$mem+=[long]$inspect.HostConfig.Memory;$cpu+=[long]$inspect.HostConfig.NanoCpus};if($inspect.Config.Labels.'agent-platform.role' -match 'application|bootstrap'){throw 'UNREGISTERED_CONCURRENT_PRODUCT_WRITER'}}
if($mem+536870912 -gt 7516192768 -or $cpu+250000000 -gt 4000000000){throw 'APPROVED_RESOURCE_LIMIT_REQUIRED'}
$args=@('create','--name','agent-platform-single-final-postconditions25-20261009','--label','task=IMP-SINGLE-DB-FINAL-BASELINE-01','--label','agent-platform.role=read-only-identity-verification','--label','agent-platform.domain=consultation','--memory','512m','--cpus','0.25','--read-only','--tmpfs','/tmp:rw,size=33554432','--network',$ready.networkId,'--mount','type=bind,source=/mnt/docker-desktop-disk/agent-platform-single-final/consultation/platform-api-25.jar,target=/product/runtime.jar,readonly')
foreach($ref in $refs.readonlyToolMountReferences){$copy=Join-Path "$out/public-refs-attempt02" ([IO.Path]::GetFileName($ref.target));Assert-FinalFile @{path=$copy;sha256=$ref.sha256};$args+=@('--mount',"type=bind,source=$copy,target=$($ref.target),readonly")}
foreach($p in $normal.environmentBindings.GetEnumerator()){$args+=@('--env',"$($p.Key)=$($p.Value)")}
$args+=@('--mount',"type=bind,source=$out/postcondition-tool-classes,target=/tool,readonly")
$args+=@('--entrypoint','/bin/sleep',$ready.image,'900')
$id=(& docker @args).Trim()
if($LASTEXITCODE -ne 0){throw 'BOOTSTRAP_TOOL_CREATE_FAILED'}
$writer=@{format='NORMAL_CONSULTATION_BOOTSTRAP_EXECUTION_RESPONSIBILITY/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');owner='current-main-session';hostPid=$PID;toolContainerId=$id;targetId=$target;networkId=$network;jar=$artifact.jar;domain='consultation';mainClass='ReadConsultationBootstrap25';readonlyToolMountReferences=$refs.readonlyToolMountReferences;beforeMemoryBytes=$mem;beforeNanoCpus=$cpu;toolMemoryBytes=536870912;toolNanoCpus=250000000;original674NaturallyExited=$true;originalLocksAbsent=$true;freshPermissionEvidence="$out/fresh-prebootstrap-permission-verification02.json";noMembersOrBusinessGrantsAllowed=$true;dclAllowed=$false;noRetryWithoutReconciliation=$true}
$writer|ConvertTo-Json -Depth 12|Set-Content "$out/postconditions25-execution-responsibility.json"
& docker start $id|Out-Null;if($LASTEXITCODE -ne 0){throw 'BOOTSTRAP_TOOL_START_FAILED'}
& docker exec $id /bin/sh -c 'sha256sum /product/runtime.jar'|Set-Content "$out/postconditions25-mounted-artifact-sha.txt"
$mounted=(Get-Content "$out/postconditions25-mounted-artifact-sha.txt" -Raw).Split(' ')[0].Trim()
if($LASTEXITCODE -ne 0 -or $mounted -cne $artifact.jar.sha256){throw 'MOUNTED_ARTIFACT_SHA_MISMATCH'}
foreach($ref in $refs.readonlyToolMountReferences){
 $line=& docker exec $id /bin/sh -c "test -f '$($ref.target)' && test ! -L '$($ref.target)' && sha256sum '$($ref.target)'"
 if($LASTEXITCODE -ne 0 -or ([string]$line).Split(' ')[0].Trim() -cne $ref.sha256){& docker stop --time 5 $id|Out-Null;throw 'BOOTSTRAP_PUBLIC_MOUNT_PREFLIGHT_FAILED'}
 $line|Add-Content "$out/postconditions25-public-mounted-shas.txt"
}
$old=@{};$started=[DateTimeOffset]::Now;$code=$null
try{
 $execArgs=@('exec')
 foreach($p in $normal.managedSecretReferences.GetEnumerator()|Where-Object {$_.Key -in @('PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD','PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD')}){$old[$p.Key]=[Environment]::GetEnvironmentVariable($p.Key,'Process');[Environment]::SetEnvironmentVariable($p.Key,(Read-FinalManagedSecret $p.Value),'Process');$execArgs+=@('--env',$p.Key)}
 $execArgs+=@($id,'/opt/java/openjdk/bin/java','-Xmx384m','-XX:ActiveProcessorCount=1','-Dloader.main=ReadConsultationBootstrap25','-Dloader.path=/tool','-cp','/product/runtime.jar','org.springframework.boot.loader.launch.PropertiesLauncher')
 & docker @execArgs 2>&1|Tee-Object "$out/bootstrap25-postconditions-actual.json"
 $code=$LASTEXITCODE
}finally{
 foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
 @{format='ACTUAL_READ_ONLY_CONSULTATION_POSTCONDITION_ATTEMPT/v1';startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');toolContainerId=$id;targetId=$target;jar=$artifact.jar;exitCode=$code;secretDecoder='existing Read-FinalManagedSecret';memberActivationOrBusinessGrantCreated=$false;postconditionQueryTool=$true;retryPerformed=$false}|ConvertTo-Json -Depth 8|Set-Content "$out/bootstrap25-postconditions-result.json"
 & docker stop --time 5 $id|Out-Null
}
if($code -ne 0){throw 'BOOTSTRAP_FAILED_PRESERVE_AND_RECONCILE_NO_RETRY'}