param([Parameter(Mandatory)][string]$BackendPom)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Split-Path -Parent (Split-Path -Parent $PSCommandPath))).Path
$evidence=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261004/exit-scope-01'
$run='ADJ03A_EXIT_20261004_01';$port=5411
$container='codex-enf-rbc-isolated-'+$run.ToLowerInvariant();$volume=$container+'-data'
$root=Join-Path $env:TEMP "enf-owned-$run/files";$build=Split-Path -Parent $BackendPom
$private=Join-Path $env:LOCALAPPDATA "AgentPlatform/adjudication-model-v1/exit-isolated-$run"
& docker info --format '{{.ServerVersion}}' *> "$evidence/isolated-daemon-preflight.log"
if($LASTEXITCODE -ne 0){throw 'Docker daemon unavailable; no startup, volume, schema, credential or product access'}
if(Test-Path $private){throw 'Existing private record forbidden'}
if(Test-Path (Split-Path -Parent $root)){throw 'Existing owned file root forbidden'}
if(@(docker ps -a --format '{{.Names}}') -contains $container -or @(docker volume ls --format '{{.Name}}') -contains $volume){throw 'Existing container/volume forbidden'}
if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw 'Occupied port: no stop or reuse'}
if(-not (Test-Path "$build/apps/platform-api/target/test-classes")){throw 'Current frozen compiled snapshot required'}
New-Item -ItemType Directory $private,$root|Out-Null
& icacls.exe $private /inheritance:r /grant:r "$([Security.Principal.WindowsIdentity]::GetCurrent().Name):(OI)(CI)F" *> "$evidence/private-acl.log"
if($LASTEXITCODE -ne 0){throw 'Private credential ACL failed'}
$before=@{};function Set-Owned([string]$n,[string]$v){if(-not $before.ContainsKey($n)){$before[$n]=[Environment]::GetEnvironmentVariable($n,'Process')};[Environment]::SetEnvironmentVariable($n,$v,'Process')}
$schema='ENFISO_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant()
$jobSchema='ADJREG_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant()
$facilitySchema='ADJREG_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant()
try {
 foreach($n in @('SYSDBA_PWD','SYSAUDITOR_PWD','SYSSSO_PWD','JOB_PACK_DM8_PASSWORD')){Set-Owned $n ('AdjI9!'+[Guid]::NewGuid().ToString('N'));ConvertTo-SecureString ([Environment]::GetEnvironmentVariable($n,'Process')) -AsPlainText -Force|Export-Clixml (Join-Path $private "$n.xml")}
 Set-Owned 'ENF_TEST_RUN_ID' $run;Set-Owned 'ENF_TEST_CONTAINER' $container;Set-Owned 'ENF_TEST_VOLUME' $volume;Set-Owned 'ENF_TEST_DB_SCHEMA' $schema;Set-Owned 'ENF_TEST_FILE_ROOT' $root
 Set-Owned 'ENF_TEST_MIGRATION_ROOT' "$workspace/db/dm8";Set-Owned 'ENF_TEST_EVIDENCE' $evidence
 Set-Owned 'JOB_PACK_DM8_URL' "jdbc:dm://127.0.0.1:$port";Set-Owned 'JOB_PACK_DM8_USER' $schema
 Set-Owned 'DM8_JDBC_URL' "jdbc:dm://127.0.0.1:$port";Set-Owned 'DM8_JDBC_USER' 'SYSDBA';Set-Owned 'DM8_JDBC_PASSWORD' $env:SYSDBA_PWD
 $image=(& docker image inspect cnxc/dm8:20251114-kylin --format '{{.Id}}').Trim();if($LASTEXITCODE -ne 0){throw 'Existing local image required; no pull'}
 & docker volume create --label agent-platform.role=enforcement-test --label "agent-platform.run-id=$run" $volume|Out-Null;if($LASTEXITCODE -ne 0){throw 'New isolated volume failed'}
 & docker run -d --pull never --name $container --label agent-platform.role=enforcement-test --label "agent-platform.run-id=$run" --memory 2g --cpus 2 --shm-size 128m -p "127.0.0.1:${port}:5236" --mount "type=volume,source=$volume,target=/opt/dmdbms/data" -e SYSDBA_PWD -e SYSAUDITOR_PWD -e SYSSSO_PWD -e INSTANCE_NAME=ADJEXIT -e PAGE_SIZE=16 $image|Out-Null;if($LASTEXITCODE -ne 0){throw 'New isolated instance failed'}
 $id=(& docker inspect --format '{{.Id}}' $container).Trim()
 function Assert-Owned {if((& docker inspect --format '{{.Id}}' $container).Trim() -ne $id -or $env:JOB_PACK_DM8_URL -ne 'jdbc:dm://127.0.0.1:5411' -or $env:DM8_JDBC_URL -ne 'jdbc:dm://127.0.0.1:5411'){throw 'Independent target changed'}}
 @{runId=$run;schema=$schema;volume=$volume;containerId=$id}|ConvertTo-Json|Set-Content "$root/.enf-test-ownership.json" -Encoding utf8NoBOM
 @{runId=$run;schema=$schema;jobSchema=$jobSchema;facilitySchema=$facilitySchema;container=$container;containerId=$id;volume=$volume;imageId=$image;port=$port;fileRoot=$root;scope='ADJ03A_ISOLATED_COMPATIBILITY_MECHANISM_NOT_ENF_PRODUCT_I';sourceOrAuthorityAccess=$false;resourcesRetained=$true}|ConvertTo-Json|Set-Content "$evidence/isolated-resource-manifest.json" -Encoding utf8NoBOM
 $deps=(Get-Content "$env:TEMP/enf-runtime-classpath.txt" -Raw).Trim();$cp="$build/enforcement/target/classes;$build/apps/platform-api/target/classes;$build/apps/platform-api/target/test-classes;$deps"
 & "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $cp -d $evidence "$workspace/scripts/java/EnforcementIsolatedSchemaPreparation88.java" "$workspace/scripts/java/PrepareAdjudicationExitRegression.java" *> "$evidence/isolated-initializer-compile.log";if($LASTEXITCODE -ne 0){throw 'Initializer compile failed'}
 Assert-Owned
 & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$cp" EnforcementIsolatedSchemaPreparation88 "$evidence/isolated-schema-preparation.json" *> "$evidence/isolated-schema-preparation.log";if($LASTEXITCODE -ne 0){throw 'New owned empty schema initialization failed'}
 Set-Owned 'ADJ_EXIT_ADDITIONAL_SCHEMA' $jobSchema
 & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$cp" PrepareAdjudicationExitRegression *> "$evidence/job-schema-preparation.log";if($LASTEXITCODE -ne 0){throw 'New empty job schema failed'}
 Set-Owned 'ADJ_EXIT_ADDITIONAL_SCHEMA' $facilitySchema
 & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$cp" PrepareAdjudicationExitRegression "$workspace/db/dm8/V080__enforcement_v2_document_rule.sql" *> "$evidence/facility-schema-preparation.log";if($LASTEXITCODE -ne 0){throw 'New facility parent migration failed'}
 $results=@()
 foreach($group in @(@{name='rbc';tests='RealBusinessCollectionDm8Test';schema=$schema},@{name='jobpack';tests='JobPackDm8IntegrationTest';schema=$jobSchema},@{name='random-schema';tests='DocumentTemplateDm8IntegrationTest,LegalReferenceDm8IntegrationTest,EnforcementFollowupDm8MigrationTest';schema=$jobSchema},@{name='facility';tests='EnforcementDocumentRuleFacilityFixtureTest';schema=$jobSchema})){
  Set-Owned 'JOB_PACK_DM8_USER' $group.schema
  Set-Owned 'ENF_ACCEPTANCE_BUSINESS_URL' 'jdbc:dm://127.0.0.1:5411';Set-Owned 'ENF_ACCEPTANCE_BUSINESS_USER' $facilitySchema;Set-Owned 'ENF_ACCEPTANCE_BUSINESS_PASSWORD' $env:JOB_PACK_DM8_PASSWORD
  Set-Owned 'ENF_DOCUMENT_RULE_MIGRATION_FILE' "$workspace/db/dm8/V081__enforcement_document_rule_attempt.sql"
  Assert-Owned
  & mvn -f $BackendPom "-Dmaven.multiModuleProjectDirectory=$workspace" "-DargLine=-Duser.dir=$workspace" -pl apps/platform-api -am "-Dtest=$($group.tests)" '-Dsurefire.failIfNoSpecifiedTests=false' test *> "$evidence/isolated-$($group.name).log"
  $exit=$LASTEXITCODE;$results+=@{name=$group.name;tests=$group.tests;exit=$exit;target='jdbc:dm://127.0.0.1:5411';schema=$group.schema}
  if($exit -ne 0){$results|ConvertTo-Json -Depth 5|Set-Content "$evidence/isolated-results.json" -Encoding utf8NoBOM;throw 'Isolated applicable regression failed; retain owned resources'}
 }
 $results|ConvertTo-Json -Depth 5|Set-Content "$evidence/isolated-results.json" -Encoding utf8NoBOM
 Write-Output 'ISOLATED_APPLICABLE_TESTS_FINISHED no_product_connections no_provider_calls resources_retained'
}finally{foreach($n in $before.Keys){[Environment]::SetEnvironmentVariable($n,$before[$n],'Process')}}
