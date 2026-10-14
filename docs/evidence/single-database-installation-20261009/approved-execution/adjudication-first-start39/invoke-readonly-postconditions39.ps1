param()
$ErrorActionPreference='Stop';$w='D:/daima/xinji/agent_platform';Set-Location $w
. "$w/scripts/lib/single-database-final-deployment.ps1"
$e="$w/docs/evidence/single-database-installation-20261009/approved-execution";$a="$e/adjudication-first-start39"
$r=Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json";$null=Assert-FinalApproval $r.readyApplicationFields
$n=@($r.normalIdentityInputs.applications|Where-Object domain -eq adjudication)[0]
$exec=Read-FinalDeploymentJson "$a/bootstrap39-actual-result.json";if($exec.exitCode -ne 0 -or -not $exec.bootstrapInvoked){throw 'ACTUAL_SUCCESS_REQUIRED_NO_RETRY'}
$compiled=Read-FinalDeploymentJson "$a/postcondition39-javac-result.json";$mapping=Read-FinalDeploymentJson "$a/public-path-mapping39.json"
$tool=$exec.toolContainerId
foreach($f in $mapping.files){Assert-FinalFile $f;$line=& docker exec $tool /usr/bin/sha256sum $f.target;if($LASTEXITCODE -ne 0 -or ([string]$line).Split(' ')[0].Trim() -cne $f.sha256){throw 'MOUNTED_SHA_REQUIRED'}}
$line=& docker exec $tool /usr/bin/sha256sum /jdbc/adjudication39/postclasses/ReadAdjudicationBootstrap39.class
if($LASTEXITCODE -ne 0 -or ([string]$line).Split(' ')[0].Trim() -cne $compiled.classSha256){throw 'READ_ONLY_TOOL_SHA_REQUIRED'}
$old=@{};$code=$null;$started=[DateTimeOffset]::Now
try{
 $args=@('exec')
 foreach($key in $n.environmentBindings.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$n.environmentBindings[$key],'Process');$args+=@('--env',$key)}
 foreach($key in @('PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD','PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD')){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,(Read-FinalManagedSecret $n.managedSecretReferences[$key]),'Process');$args+=@('--env',$key)}
 $args+=@($tool,'/opt/java/openjdk/bin/java','-Xmx384m','-XX:ActiveProcessorCount=1','-Dloader.main=ReadAdjudicationBootstrap39','-Dloader.path=/jdbc/adjudication39/postclasses','-cp','/jdbc/adjudication39/runtime.jar','org.springframework.boot.loader.launch.PropertiesLauncher')
 & docker @args *> "$a/bootstrap39-postconditions-actual.json";$code=$LASTEXITCODE
 if($code -ne 0){throw 'POSTCONDITIONS_FAILED_PRESERVE_NO_BOOTSTRAP_RETRY'}
 $p=Read-FinalDeploymentJson "$a/bootstrap39-postconditions-actual.json";if(-not $p.actualJdbc -or -not $p.postconditionsPassed -or $p.executedWrites -ne 0 -or $p.executedDcl -ne 0){throw 'ACTUAL_READ_ONLY_POSTCONDITION_REQUIRED'}
}finally{
 foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
 @{format='ACTUAL_ADJ_READ_ONLY_POSTCONDITION_ATTEMPT/v1';startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;toolContainerId=$tool;targetId=$exec.targetId;sourceSha256=$compiled.sourceSha256;classSha256=$compiled.classSha256;executedWrites=0;executedDcl=0;toolRestarted=$false;modelCalls=0}|ConvertTo-Json|Set-Content "$a/bootstrap39-postconditions-result.json"
}
