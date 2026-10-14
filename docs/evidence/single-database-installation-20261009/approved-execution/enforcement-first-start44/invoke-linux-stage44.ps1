param()
$ErrorActionPreference='Stop'
$w='D:/daima/xinji/agent_platform';Set-Location $w
. "$w/scripts/lib/single-database-final-deployment.ps1"
$e="$w/docs/evidence/single-database-installation-20261009/approved-execution";$o="$e/enforcement-first-start44"
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields;$null=Assert-FinalApproval $ready
$art=Read-FinalDeploymentJson "$o/engineering-receipt44.json";$app=@($ready.applications|Where-Object domain -eq enforcement)[0]
$c=@{applications=@(@{jar=$art.jar;properties=$app.properties})}
$tool='ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096'
$items=@(@{source='/jdbc/enforcement44/runtime.jar';entry='runtime.jar';target='/mnt/docker-desktop-disk/agent-platform-single-final/enforcement/platform-api-44.jar';sha=$c.applications[0].jar.sha256},@{source='/jdbc/enforcement44/application.properties';entry='application.properties';target='/mnt/docker-desktop-disk/agent-platform-single-final/enforcement/application-44.properties';sha=$c.applications[0].properties.sha256})
@{format='ENFORCEMENT_APPROVED_ARTIFACT_STAGE_RESPONSIBILITY/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');owner='current-main-session';hostPid=$PID;toolContainerId=$tool;items=$items;overwriteAllowed=$false;privateKeyCopyAllowed=$false;modelCalls=0;applicationStarted=$false}|ConvertTo-Json -Depth 8|Set-Content "$o/linux-stage44-responsibility.json"
foreach($v in $items){
 $line=& docker exec $tool sha256sum $v.source
 if($LASTEXITCODE -ne 0 -or ([string]$line).Split(' ')[0].Trim() -cne $v.sha){throw 'EXACT_MOUNTED_SOURCE_SHA_REQUIRED'}
 $sh="set -eu; set -C; test -d /mnt/docker-desktop-disk/agent-platform-single-final/enforcement; test ! -e '$($v.target)'; tar -xOf - '$($v.entry)' > '$($v.target)'; chmod 0444 '$($v.target)'; echo '$($v.sha)  $($v.target)' | sha256sum -c -; stat -c '%n %F %u:%g:%a %s' '$($v.target)'"
 $sh|Set-Content "$o/linux-stage44-$($v.entry)-command.sh"
 $started=[DateTimeOffset]::Now;$code=$null
 try{
  # Native-to-native binary archive pipe (PowerShell 7.6); no text conversion, secret, or overwrite.
  & docker cp "${tool}:$($v.source)" - | & wsl.exe -d docker-desktop -- sh -c $sh > "$o/linux-stage44-$($v.entry).log"
  $code=$LASTEXITCODE
 }finally{@{startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');source=$v.source;target=$v.target;sha256=$v.sha;exitCode=$code;overwriteAllowed=$false;newContainerCreated=$false}|ConvertTo-Json|Set-Content "$o/linux-stage44-$($v.entry)-result.json"}
 if($code -ne 0){throw 'STAGE_FAILED_PRESERVE_PARTIAL_NO_BLIND_RETRY'}
}

