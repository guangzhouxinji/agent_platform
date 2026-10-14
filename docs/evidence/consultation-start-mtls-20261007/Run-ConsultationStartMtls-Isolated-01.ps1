$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$output=Join-Path $workspace 'var/engineering/consultation-start-mtls-20261007/build-01'
$raw=Join-Path $workspace 'docs/evidence/consultation-start-mtls-20261007/原件'
if(Test-Path -LiteralPath $output){throw 'Independent output already exists; do not overwrite an original run'}
New-Item -ItemType Directory -Path (Join-Path $output 'classes') -Force | Out-Null
$cpFile=Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/jvm-six-snapshot-03/classpath.txt'
# Use immutable dependency jars only. No historical/current project class directories or target outputs.
$jars=@((Get-Content -LiteralPath $cpFile -Raw).Trim().Split(';') | Where-Object {$_ -like '*.jar'})
foreach($jar in $jars){if(-not(Test-Path -LiteralPath $jar)){throw 'Recorded dependency jar missing'}}
$cp=$jars -join ';'
$files=@(Get-ChildItem (Join-Path $workspace 'backend') -Recurse -File -Filter '*.java' | Where-Object {$_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' -and $_.FullName -notmatch '[\\/]target[\\/]'})
$files+=Get-Item (Join-Path $workspace 'backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductComparisonStartMtlsIntegrationTest.java')
$files+=Get-Item (Join-Path $workspace 'backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductComparisonStartMtlsEngineeringSupport.java')
$manifest=@($files | Sort-Object FullName | ForEach-Object {@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $raw 'compiled-source-before.json') -Encoding utf8
@($jars|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'dependency-jars.json') -Encoding utf8
$argsFile=Join-Path $output 'javac.args'
$arguments=@('--release','21','-proc:none','-encoding','UTF-8','-classpath','"'+$cp.Replace('\','/')+'"','-d','"'+(Join-Path $output 'classes').Replace('\','/')+'"')
$arguments+=@($files|ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'})
$arguments|Set-Content -LiteralPath $argsFile -Encoding utf8NoBOM
$started=Get-Date -Format o
& 'D:/software/jdk-21/bin/javac.exe' '-J-Xmx2g' ('@'+$argsFile) *> (Join-Path $raw 'compile-01.log')
$compileExit=$LASTEXITCODE
@{exit=$compileExit;startedAt=$started;finishedAt=(Get-Date -Format o);output=$output;productionSourceCount=$files.Count-2;newTestSourceCount=2;sharedTargetUsed=$false;mavenExecuted=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'compile-01-exit.json') -Encoding utf8
if($compileExit -ne 0){Get-Content (Join-Path $raw 'compile-01.log');exit $compileExit}
$runArgs=Join-Path $output 'java.args'
@('-Xmx1g','-XX:+EnableDynamicAgentLoading','-classpath','"'+((Join-Path $output 'classes')+';'+$cp).Replace('\','/')+'"','cn.xinji.agentplatform.security.product.ProductComparisonStartMtlsIntegrationTest','"'+$raw.Replace('\','/')+'"')|Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
$started=Get-Date -Format o
Push-Location $workspace
try{& 'D:/software/jdk-21/bin/java.exe' ('@'+$runArgs) *> (Join-Path $raw 'focused-01.log');$runExit=$LASTEXITCODE}finally{Pop-Location}
@{exit=$runExit;startedAt=$started;finishedAt=(Get-Date -Format o);testClass='ProductComparisonStartMtlsIntegrationTest';output=$output;sharedTargetUsed=$false;fullRegression=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'focused-01-exit.json') -Encoding utf8
$after=@($files|Sort-Object FullName|ForEach-Object {@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$after|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $raw 'compiled-source-after.json') -Encoding utf8
$changes=@(Compare-Object ($manifest|ForEach-Object {$_.path+'|'+$_.sha256}) ($after|ForEach-Object {$_.path+'|'+$_.sha256}))
@{compiledSourcesUnchanged=($changes.Count -eq 0);changedPaths=@($changes|ForEach-Object {$_.InputObject});note='Concurrent main-thread changes, if any, are recorded; compiled snapshot remains the before manifest'}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $raw 'compiled-source-stability.json') -Encoding utf8
Get-Content (Join-Path $raw 'focused-01.log') -Tail 30
exit $runExit
