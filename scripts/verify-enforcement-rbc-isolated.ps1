param([Parameter(Mandatory=$true)][string]$RunId,[int]$Port=5381,[ValidateSet("RealBusinessCollectionDm8Test","JobPackDm8IntegrationTest","DocumentDraftWorkflowIsolatedDm8IT","RealBusinessClaimSafetyIsolatedDm8IT")][string]$TestClass="RealBusinessCollectionDm8Test",[switch]$PrepareOnly)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Z0-9_-]{8,80}$' -or $Port -in @(5236,5237,5301,5302,5311,5312,5319,5367,5370)){throw 'Unsafe isolated run or port'}
$workspace=(Resolve-Path -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSCommandPath))).Path
$evidence=Join-Path $workspace "docs/evidence/enforcement-document-draft-20261002/$RunId"
$build=Join-Path $env:TEMP "enf-isolated-build-$RunId/backend"
if(-not (Test-Path -LiteralPath $evidence) -or -not (Test-Path -LiteralPath "$build/apps/platform-api/target/test-classes")){throw 'Evidence/source snapshot missing'}
$container='codex-enf-rbc-isolated-'+$RunId.ToLowerInvariant();$volume=$container+'-data'
if(@(docker ps -a --format '{{.Names}}') -contains $container -or @(docker volume ls --format '{{.Name}}') -contains $volume){throw 'Prior container or volume: refuse reuse'}
if(@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count -gt 0){throw 'Port occupied: never stop other service'}
$credentialRoot=Join-Path $env:LOCALAPPDATA 'AgentPlatform/isolated-verification'
$credentialFile=Join-Path $credentialRoot "$RunId.credential.xml"
if($PrepareOnly -and (Test-Path -LiteralPath $credentialFile)){throw 'Never replace a prepared credential'}
$fileRoot=Join-Path $env:TEMP "enf-owned-$RunId/files"
if(Test-Path -LiteralPath (Split-Path -Parent $fileRoot)){throw 'Prior file root: refuse reuse'}
New-Item -ItemType Directory -Path $fileRoot | Out-Null
$schema='ENFISO_'+([Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant())
try {
 $env:SYSDBA_PWD='EnfI9!'+[Guid]::NewGuid().ToString('N')
 $env:SYSAUDITOR_PWD='EnfI9!'+[Guid]::NewGuid().ToString('N')
 $env:SYSSSO_PWD='EnfI9!'+[Guid]::NewGuid().ToString('N')
 $env:JOB_PACK_DM8_PASSWORD='EnfI9!'+[Guid]::NewGuid().ToString('N')
 $env:ENF_TEST_RUN_ID=$RunId;$env:ENF_TEST_CONTAINER=$container;$env:ENF_TEST_VOLUME=$volume;$env:ENF_TEST_DB_SCHEMA=$schema;$env:ENF_TEST_FILE_ROOT=$fileRoot
 $env:ENF_TEST_MIGRATION_ROOT=Join-Path $workspace "db/dm8";$env:ENF_TEST_EVIDENCE=$evidence
 $env:JOB_PACK_DM8_URL="jdbc:dm://127.0.0.1:$Port";$env:JOB_PACK_DM8_USER=$schema;$env:JAVA_HOME='D:/software/jdk-21'
 $image=(& docker image inspect cnxc/dm8:20251114-kylin --format '{{.Id}}').Trim();if($LASTEXITCODE -ne 0){throw 'Existing local DM8 image unavailable; no pull'}
 & docker volume create --label agent-platform.role=enforcement-test --label "agent-platform.run-id=$RunId" $volume | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Independent volume creation failed'}
 & docker run -d --pull never --name $container --label agent-platform.role=enforcement-test --label "agent-platform.run-id=$RunId" --memory 2g --cpus 2 --shm-size 128m -p ("127.0.0.1:"+$Port+":5236") --mount "type=volume,source=$volume,target=/opt/dmdbms/data" -e SYSDBA_PWD -e SYSAUDITOR_PWD -e SYSSSO_PWD -e INSTANCE_NAME=ENFISO88 -e PAGE_SIZE=16 $image | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Isolated container creation failed; keep resources for evidence'}
 $containerId=(& docker inspect --format '{{.Id}}' $container).Trim()
 $marker=[ordered]@{runId=$RunId;schema=$schema;volume=$volume;containerId=$containerId}
 $marker | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fileRoot '.enf-test-ownership.json') -Encoding utf8NoBOM
 $manifest=[ordered]@{runId=$RunId;createdAt=[DateTimeOffset]::UtcNow.ToString('o');container=$container;containerId=$containerId;volume=$volume;port=$Port;imageId=$image;schema=$schema;fileRoot=$fileRoot;scope='ISOLATED_MECHANISM_ONLY_NOT_PRODUCT_OR_REAL_HUMAN';automaticCleanup=$false}
 $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'isolated-resource-manifest.json') -Encoding utf8NoBOM
 $classpath=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim()
 $classpath="$build/enforcement/target/classes;$build/apps/platform-api/target/classes;$build/apps/platform-api/target/test-classes;$classpath"
 & "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $classpath -d $evidence (Join-Path $workspace 'scripts/java/EnforcementIsolatedSchemaPreparation88.java') *> (Join-Path $evidence 'schema-compile.log')
 if($LASTEXITCODE -ne 0){throw 'Isolated initializer compile failed'}
 & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$classpath" EnforcementIsolatedSchemaPreparation88 (Join-Path $evidence 'isolated-schema-preparation.json') *> (Join-Path $evidence 'schema-initialization.log')
 if($LASTEXITCODE -ne 0){throw 'Isolated initializer failed; preserve evidence'}
 if($PrepareOnly){
  New-Item -ItemType Directory -Path $credentialRoot -Force | Out-Null
  $secure=ConvertTo-SecureString $env:JOB_PACK_DM8_PASSWORD -AsPlainText -Force
  [PSCredential]::new($schema,$secure)|Export-Clixml -LiteralPath $credentialFile
  [ordered]@{runId=$RunId;testExecuted=$false;credentialFile=$credentialFile;scope='OWNED_ISOLATED_PREPARATION_ONLY'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'isolated-prepared.json') -Encoding utf8NoBOM
  Write-Output 'Isolated empty schema prepared; no test executed; protected credential stored locally'
  return
 }
 & 'D:/software/apache-maven-3.9.16/bin/mvn.cmd' -q -f "$build/pom.xml" -pl apps/platform-api -am "-Dtest=$TestClass" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId-REAL-DM8" "-Dmaven.multiModuleProjectDirectory=$workspace" test *> (Join-Path $evidence 'real-isolated-dm8.log')
 $exit=$LASTEXITCODE
 [ordered]@{runId=$RunId;mavenExit=$exit;finishedAt=[DateTimeOffset]::UtcNow.ToString('o');resourcesRetained=$true;mainAccess=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'isolated-execution-result.json') -Encoding utf8NoBOM
 if($exit -ne 0){throw 'Isolated real DM8 test failed; no cleanup or false pass'}
 Write-Output 'Isolated real DM8 test finished; all owned resources retained'
}finally{
 foreach($name in @('SYSDBA_PWD','SYSAUDITOR_PWD','SYSSSO_PWD','JOB_PACK_DM8_PASSWORD','JOB_PACK_DM8_URL','JOB_PACK_DM8_USER','ENF_TEST_RUN_ID','ENF_TEST_CONTAINER','ENF_TEST_VOLUME','ENF_TEST_DB_SCHEMA','ENF_TEST_FILE_ROOT','ENF_TEST_MIGRATION_ROOT','ENF_TEST_EVIDENCE')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
}
