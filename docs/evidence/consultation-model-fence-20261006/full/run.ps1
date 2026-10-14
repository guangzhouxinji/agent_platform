$ErrorActionPreference='Stop'
$root=(Resolve-Path '.').Path;$evidence=Join-Path $root 'docs/evidence/consultation-model-fence-20261006/full'
if(Test-Path -LiteralPath (Join-Path $evidence 'full-round02-exit.json')){throw 'Never overwrite full-run evidence'}
$run=(Get-Content (Join-Path $evidence 'full-run-id.txt') -Raw).Trim();$build=Join-Path $env:TEMP "enf-isolated-build-$run/backend"
function Owned($idFile,$port) {
 $id=(Get-Content (Join-Path $evidence $idFile) -Raw).Trim()
 $manifest=Get-Content (Join-Path $root "docs/evidence/enforcement-document-draft-20261002/$id/isolated-resource-manifest.json") -Raw|ConvertFrom-Json
 $c=(docker inspect $manifest.container|ConvertFrom-Json)[0]
 if($LASTEXITCODE -ne 0 -or $c.Id -ne $manifest.containerId -or $c.Config.Labels.'agent-platform.run-id' -ne $id -or $c.Config.Labels.'agent-platform.role' -ne 'enforcement-test' -or -not $c.State.Running){throw 'Owned container mismatch'}
 $binding=@($c.HostConfig.PortBindings.'5236/tcp');if($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or [int]$binding[0].HostPort -ne $port){throw 'Port mismatch'}
 $owners=@(docker ps -a --filter "volume=$($manifest.volume)" --format '{{.ID}}');if($owners.Count -ne 1 -or -not $c.Id.StartsWith($owners[0])){throw 'Volume must have one owned container'}
 return @{manifest=$manifest;admin=($c.Config.Env|Where-Object {$_ -like 'SYSDBA_PWD=*'}).Substring(11)}
}
$biz=Owned 'rbc-run-id.txt' 5381;$authority=Owned 'jobpack-run-id.txt' 5382;$generic=Owned 'full-run-id.txt' 5384
$prepared=Get-Content (Join-Path $root "docs/evidence/enforcement-document-draft-20261002/$run/isolated-prepared.json") -Raw|ConvertFrom-Json
$credential=Import-Clixml -LiteralPath $prepared.credentialFile
if($credential.UserName -ne $generic.manifest.schema -or (Test-Path -LiteralPath (Join-Path $generic.manifest.fileRoot '.enf-rbc-run-started'))){throw 'RBC requires the original unused empty schema'}
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
function Secret($file){$value=Import-Clixml -LiteralPath (Join-Path $vault $file);$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($value);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
$settings=@{
 DM8_JDBC_URL='jdbc:dm://127.0.0.1:5384';DM8_JDBC_USER='SYSDBA';DM8_JDBC_PASSWORD=$generic.admin;
 JOB_PACK_DM8_URL='jdbc:dm://127.0.0.1:5384';JOB_PACK_DM8_USER=$credential.UserName;JOB_PACK_DM8_PASSWORD=$credential.GetNetworkCredential().Password;
 ENF_TEST_RUN_ID=$run;ENF_TEST_CONTAINER=$generic.manifest.container;ENF_TEST_VOLUME=$generic.manifest.volume;ENF_TEST_DB_SCHEMA=$credential.UserName;ENF_TEST_FILE_ROOT=$generic.manifest.fileRoot;ENF_TEST_MIGRATION_ROOT=(Join-Path $root 'db/dm8');ENF_TEST_EVIDENCE=$evidence;
 ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5381';ENF_ACCEPTANCE_BUSINESS_USER=('ENFFIX_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant());ENF_ACCEPTANCE_BUSINESS_PASSWORD=('Fixture9!'+[Guid]::NewGuid().ToString('N'));ENF_ACCEPTANCE_BUSINESS_ADMIN=$biz.admin;
 ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5382';ENF_ACCEPTANCE_AUTHORITY_USER=('ENFFIX_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant());ENF_ACCEPTANCE_AUTHORITY_PASSWORD=('Fixture9!'+[Guid]::NewGuid().ToString('N'));ENF_ACCEPTANCE_AUTHORITY_ADMIN=$authority.admin;
 ENF_ACCEPTANCE_ISSUER='https://isolated-full-fixture.invalid';ENF_ACCEPTANCE_SUBJECT='isolated-full-member';ENF_ACCEPTANCE_ORGANIZATION='ISOLATED-FULL-VALIDATION';ENF_ACCEPTANCE_TEMPLATE_ID='isolated-full-template';ENF_ACCEPTANCE_REVOKE_TEMPLATE_DOWNLOAD='false';
 AGENT_JOBPACK_JOB_PACK_DM8_URL='jdbc:dm://127.0.0.1:5382';AGENT_JOBPACK_JOB_PACK_DM8_USER=('ENFFIX_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant());AGENT_JOBPACK_JOB_PACK_DM8_PASSWORD=('Fixture9!'+[Guid]::NewGuid().ToString('N'));AGENT_JOBPACK_JOB_PACK_DM8_ADMIN=$authority.admin;
 HISTORICAL_URL='jdbc:dm://127.0.0.1:5311';HISTORICAL_USER='ENFACC_BIZ';HISTORICAL_PASSWORD=(Secret 'business-schema.credential.xml')
}
$settings.ENF_ACCEPTANCE_AUTHORITY_SCHEMA=$settings.ENF_ACCEPTANCE_AUTHORITY_USER
$old=@{};foreach($item in Get-ChildItem Env:){if($item.Name -match '^(ENF_|JOB_PACK_|DM8_JDBC_|AGENT_JOBPACK_|AGENT_GRANT_|HISTORICAL_|CON_MODEL_FENCE_)'){$old[$item.Name]=$item.Value;[Environment]::SetEnvironmentVariable($item.Name,$null,'Process')}}
$settings.CON_MODEL_FENCE_DM8_URL='jdbc:dm://127.0.0.1:5384';$settings.CON_MODEL_FENCE_DM8_USER='SYSDBA';$settings.CON_MODEL_FENCE_DM8_PASSWORD=$generic.admin
$oldJava=$env:JAVA_HOME;$oldPath=$env:PATH
try {
 foreach($name in $settings.Keys){[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
 $env:JAVA_HOME='D:/software/jdk-21';$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $dependencies=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim()
 $classes=@(Get-ChildItem -LiteralPath (Join-Path $root 'backend') -Directory -Recurse | Where-Object {$_.Name -eq 'classes' -and $_.Parent.Name -eq 'target'} | ForEach-Object {$_.FullName}) -join ';'
 $cp="$classes;$dependencies"
 & "$env:JAVA_HOME/bin/javac.exe" -cp $cp -d $evidence (Join-Path $evidence 'IsolatedGrantFixturePreparation.java') (Join-Path $evidence 'AgentPreparePublicKb.java') (Join-Path $evidence 'FullFixturePreparation.java') *> (Join-Path $evidence 'full-round02-fixture-compile.log')
 if($LASTEXITCODE -ne 0){throw 'Fixture compilation failed'}
 & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$cp" FullFixturePreparation *> (Join-Path $evidence 'full-round02-fixture-prepare.log')
 if($LASTEXITCODE -ne 0){throw 'Safe fixture preparation failed'}
 foreach($name in @($settings.Keys|Where-Object {$_ -like 'ENF_ACCEPTANCE_*'})){[Environment]::SetEnvironmentVariable('AGENT_GRANT_'+$name,$settings[$name],'Process')}
 $base=Join-Path $root 'docs/evidence/enforcement-followup-final-20260930'
 $historical=@{
  ENF_ACCEPTANCE_BUSINESS_URL=$settings.HISTORICAL_URL;ENF_ACCEPTANCE_BUSINESS_USER=$settings.HISTORICAL_USER;ENF_ACCEPTANCE_BUSINESS_PASSWORD=$settings.HISTORICAL_PASSWORD;
  ENF_DOCUMENT_RULE_TEMPLATE_ROOT=(Join-Path $vault 'document-templates');ENF_DOCUMENT_RULE_FINAL_CATALOG="$root/data/enforcement/document-rule-v2-product-input-v1/catalog.json";
  ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST="$base/ENF13-FINAL-20261001-101-input-manifest.json";ENF_DOCUMENT_RULE_DEPENDENCY_RESULT="$base/ENF13-DEPENDENCY-UI-20261001-85-rule-browser/result.json";ENF_DOCUMENT_RULE_RECOVERY_BASELINE="$base/ENF13-RECOVERY-SEED-20261001-81-rule-browser/result.json";ENF_DOCUMENT_RULE_UNKNOWN_RESULT="$base/ENF13-UNKNOWN-AUDIT-20261001-98-rule-browser/result.json";ENF_DOCUMENT_RULE_TYPE_RESULT="$base/ENF13-TYPE-AUDIT-20261001-99-rule-browser/result.json";ENF_DOCUMENT_RULE_ATTEMPT_BROWSER_RESULT="$base/ENF13-ATTEMPT-20261001-57-rule-browser/result.json";
  ENF_DOCUMENT_RULE_NEGATIVE_OUTPUT="$evidence/full-negatives";ENF_DOCUMENT_RULE_DEPENDENCY_OUTPUT="$evidence/full-dependency.json";ENF_DOCUMENT_RULE_RECOVERY_OUTPUT="$evidence/full-recovery.json";ENF_DOCUMENT_RULE_ATTEMPT_OUTPUT="$evidence/full-attempt.json";ENF_DOCUMENT_RULE_MIGRATION_FILE="$root/db/dm8/V081__enforcement_document_rule_attempt.sql"
 }
 foreach($name in $historical.Keys){[Environment]::SetEnvironmentVariable($name,$historical[$name],'Process')}
 [ordered]@{runId=$run;genericDatabase='5384 owned SYSDBA';rbcSchema=$credential.UserName;jobpackSchema=$settings.AGENT_JOBPACK_JOB_PACK_DM8_USER;grantBusinessSchema=$settings.ENF_ACCEPTANCE_BUSINESS_USER;grantAuthoritySchema=$settings.ENF_ACCEPTANCE_AUTHORITY_USER;historicalReadback='5311 existing original frozen packets, facility prechecked SELECT-only, no original authority used';command='mvn -f WORKSPACE/backend/pom.xml -Dmaven.multiModuleProjectDirectory=WORKSPACE test';testFilter=$false}|ConvertTo-Json|Set-Content (Join-Path $evidence 'full-round02-scope.json')
 $captured=Get-Content -LiteralPath (Join-Path $evidence 'source-inputs.json') -Raw|ConvertFrom-Json
 foreach($file in $captured.files){if((Get-FileHash -LiteralPath (Join-Path $root $file.path)).Hash.ToLowerInvariant() -ne $file.sha256){throw 'Workspace source differs from captured input; stop before full run'}}
 $startedAt=[DateTimeOffset]::UtcNow
 [ordered]@{startedAt=$startedAt.ToString('o');capturedFiles=$captured.files.Count;sourceVerified=$true;workingTree=$root;reason='Current workspace sources captured before and after unfiltered run; real repository-relative inputs retained'}|ConvertTo-Json|Set-Content (Join-Path $evidence 'full-round02-input-check.json')
 & mvn -f backend/pom.xml "-Dmaven.multiModuleProjectDirectory=$root" test *> (Join-Path $evidence 'full-round02-unfiltered.log')
 $exit=$LASTEXITCODE
 foreach($file in $captured.files){if((Get-FileHash -LiteralPath (Join-Path $root $file.path)).Hash.ToLowerInvariant() -ne $file.sha256){throw 'Workspace source changed during full run; preserve reports but reject evidence'}}
 [ordered]@{exit=$exit;finishedAt=[DateTimeOffset]::UtcNow.ToString('o');runId=$run;sourceSnapshot=$build;unfiltered=$true}|ConvertTo-Json|Set-Content (Join-Path $evidence 'full-round02-exit.json')
 Write-Output "FULL_EXIT=$exit";Get-Content (Join-Path $evidence 'full-round02-unfiltered.log') -Tail 15
}finally{
 foreach($item in Get-ChildItem Env:){if($item.Name -match '^(ENF_|JOB_PACK_|DM8_JDBC_|AGENT_JOBPACK_|AGENT_GRANT_|HISTORICAL_|CON_MODEL_FENCE_)'){[Environment]::SetEnvironmentVariable($item.Name,$null,'Process')}}
 foreach($name in $old.Keys){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')};$env:JAVA_HOME=$oldJava;$env:PATH=$oldPath
}
