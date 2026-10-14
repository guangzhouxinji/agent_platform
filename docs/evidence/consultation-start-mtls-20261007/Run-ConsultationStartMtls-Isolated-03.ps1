$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$output=Join-Path $workspace 'var/engineering/consultation-start-mtls-20261007/build-03'
$raw=Join-Path $workspace 'docs/evidence/consultation-start-mtls-20261007/原件'
if(Test-Path -LiteralPath $output){throw 'Never overwrite independent run'}
New-Item -ItemType Directory -Path (Join-Path $output 'classes'),(Join-Path $output 'source'),(Join-Path $output 'raw-ascii'),(Join-Path $output 'db/dm8') -Force | Out-Null
$lib=Join-Path $workspace 'docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib'
# Compile ALL current production source; exclude historical project jars so they cannot substitute business classes.
$jars=@(Get-ChildItem -LiteralPath $lib -File -Filter '*.jar'|Where-Object {$_.Name -notlike 'agent-platform-*'}|ForEach-Object {$_.FullName})
$selection=Get-Content -LiteralPath (Join-Path $raw 'supplemental-test-dependency-selection.json') -Raw|ConvertFrom-Json
$jars+=@($selection|ForEach-Object {$_.path})
foreach($jar in $jars){if(-not(Test-Path -LiteralPath $jar)){throw 'Existing dependency missing'}}
$cp=$jars -join ';'
@($jars|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'dependency-jars-03.json') -Encoding utf8
$files=@(Get-ChildItem (Join-Path $workspace 'backend') -Recurse -File -Filter '*.java'|Where-Object {$_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' -and $_.FullName -notmatch '[\\/]target[\\/]'})
$files+=Get-Item (Join-Path $workspace 'backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductComparisonStartMtlsIntegrationTest.java')
$files+=Get-Item (Join-Path $workspace 'backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductComparisonStartMtlsEngineeringSupport.java')
$manifest=@();$copied=@()
foreach($file in $files){
 $relative=$file.FullName.Substring($workspace.Length+1)
 $destination=Join-Path (Join-Path $output 'source') $relative
 New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force|Out-Null
 Copy-Item -LiteralPath $file.FullName -Destination $destination
 # Snapshot hash is from exact bytes read by javac, not an assumed concurrently stable live workspace.
 $sha=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
 $manifest+=@{originalPath=$file.FullName;compiledSnapshotPath=$destination;sha256=$sha};$copied+=$destination
}
$migrations=@('V095__product_comparison_input.sql','V098__product_comparison_worker_delegation.sql','V099__product_comparison_candidate_lease.sql','V100__product_comparison_matrix.sql','V102__product_comparison_start_command.sql','V103__comparison_start_fence_presence.sql')
$sqlManifest=@();foreach($name in $migrations){$from=Join-Path $workspace ('db/dm8/'+$name);$to=Join-Path $output ('db/dm8/'+$name);Copy-Item -LiteralPath $from -Destination $to;$sqlManifest+=@{originalPath=$from;snapshotPath=$to;sha256=(Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash.ToLowerInvariant()}}
$manifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $raw 'compiled-source-snapshot-03.json') -Encoding utf8
$sqlManifest|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $raw 'migration-snapshot-03.json') -Encoding utf8
$argsFile=Join-Path $output 'javac.args'
$arguments=@('--release','21','-proc:none','-encoding','UTF-8','-classpath',('"'+$cp.Replace('\','/')+'"'),'-d',('"'+(Join-Path $output 'classes').Replace('\','/')+'"'))
$arguments+=@($copied|ForEach-Object {'"'+$_.Replace('\','/')+'"'})
$arguments|Set-Content -LiteralPath $argsFile -Encoding utf8NoBOM
$started=Get-Date -Format o
& 'D:/software/jdk-21/bin/javac.exe' '-J-Xmx2g' ('@'+$argsFile) *> (Join-Path $raw 'compile-03.log')
$compileExit=$LASTEXITCODE
@{exit=$compileExit;startedAt=$started;finishedAt=(Get-Date -Format o);output=$output;productionSourceCount=$files.Count-2;newTestSourceCount=2;exactCopiedCurrentSource=$true;sharedTargetUsed=$false;mavenExecuted=$false;primaryExistingLib=$lib;excludedHistoricalProjectJars=$true}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'compile-03-exit.json') -Encoding utf8
if($compileExit -ne 0){Get-Content (Join-Path $raw 'compile-03.log');exit $compileExit}
$runArgs=Join-Path $output 'java.args';$asciiRaw=Join-Path $output 'raw-ascii'
@('-Xmx1g','-XX:+EnableDynamicAgentLoading','-classpath',('"'+((Join-Path $output 'classes')+';'+$cp).Replace('\','/')+'"'),'cn.xinji.agentplatform.security.product.ProductComparisonStartMtlsIntegrationTest',('"'+$asciiRaw.Replace('\','/')+'"'))|Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
$started=Get-Date -Format o
Push-Location $output
try{& 'D:/software/jdk-21/bin/java.exe' ('@'+$runArgs) *> (Join-Path $raw 'focused-03.log');$runExit=$LASTEXITCODE}finally{Pop-Location}
@{exit=$runExit;startedAt=$started;finishedAt=(Get-Date -Format o);output=$output;testClass='ProductComparisonStartMtlsIntegrationTest';primaryLib=$lib;sharedTargetUsed=$false;fullRegression=$false;sqlReadFromCopiedSnapshot=$true}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'focused-03-exit.json') -Encoding utf8
$copies=@();foreach($pair in @(@('focused-result.json','focused-03-result.json'),@('focused-junit.xml','focused-03-junit.xml'))){$from=Join-Path $asciiRaw $pair[0];$to=Join-Path $raw $pair[1];if(Test-Path -LiteralPath $to){throw 'Never overwrite original'};Copy-Item -LiteralPath $from -Destination $to;$before=(Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash;$after=(Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash;if($before-ne $after){throw 'Original bytes altered'};$copies+=@{from=$from;to=$to;sha256=$after.ToLowerInvariant();bytePreserved=$true}}
$copies|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $raw 'raw-copy-03.json') -Encoding utf8
$changes=@();$snapshotChanges=@();foreach($row in $manifest){if((Get-FileHash -LiteralPath $row.originalPath -Algorithm SHA256).Hash.ToLowerInvariant()-ne $row.sha256){$changes+=$row.originalPath};if((Get-FileHash -LiteralPath $row.compiledSnapshotPath -Algorithm SHA256).Hash.ToLowerInvariant()-ne $row.sha256){$snapshotChanges+=$row.compiledSnapshotPath}}
@{compiledSnapshotUnchanged=($snapshotChanges.Count-eq 0);snapshotChanges=$snapshotChanges;liveWorkspaceChangesSinceSnapshot=$changes;note='Compile and execute exact copied snapshot; main-thread later changes do not alter inputs'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $raw 'source-stability-03.json') -Encoding utf8
Get-Content (Join-Path $raw 'focused-03.log') -Tail 20
exit $runExit
