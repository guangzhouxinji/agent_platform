param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$BrowserRunId)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
foreach($id in @($RunId,$BrowserRunId)){if($id -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid evidence ID'}}
if(-not $env:ENF_MAVEN_BUILD_ROOT -or -not $env:ENF_MAVEN_POM){throw 'Exclusive current build required'}
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
$restoreRoot=Join-Path $env:TEMP "$RunId-product-files"
$gate=Join-Path $env:TEMP "$RunId-gate"
$volume='codex-enf13-restore-'+$RunId.ToLowerInvariant()
$container=$volume
foreach($path in @($evidence,$restoreRoot)){if(Test-Path -LiteralPath $path){throw 'Refusing recovery evidence/root reuse'}}
if(Get-ChildItem -LiteralPath $env:TEMP -Filter "$RunId-gate*"){throw 'Refusing prior gate'}
if((& docker volume ls --format '{{.Name}}') -contains $volume){throw 'Refusing prior recovery volume'}
foreach($port in @(5325,5450)){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Recovery port $port is occupied"}}
New-Item -ItemType Directory -Path $evidence,$restoreRoot | Out-Null
function Secret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
$env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
$env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312'
$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC'
$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
$env:ENF_ACCEPTANCE_AUTHORITY_SCHEMA='ENFACC_AUTH'
$env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
$identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw|ConvertFrom-Json
$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
$baseline=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$BrowserRunId-rule-browser/result.json"
$input=Get-Content -LiteralPath $baseline -Raw|ConvertFrom-Json
if(-not $input.passed -or $input.scenario -ne 'recovery_seed'){throw 'Actual active product browser seed required'}
$env:ENF_DOCUMENT_RULE_ID=$input.ruleId;$env:ENF_DOCUMENT_RULE_REVISION='2'
$env:ENF_ACCEPTANCE_TEMPLATE_ID=$input.db.history[1].templateId
$sourceCatalog=Join-Path $workspace 'data/enforcement/document-rule-v2-product-input-v1/catalog.json'
$env:ENF_DOCUMENT_RULE_RECOVERY_BASELINE=$baseline
$env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates'
$env:ENF_DOCUMENT_RULE_FINAL_CATALOG=$sourceCatalog
$sourceStopped=$false
function Test-Actual([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE
 $reports=Get-ChildItem (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend/apps/platform-api/target/surefire-reports') -Filter "*-$suffix.xml"
 foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination $evidence}
 if($exit -ne 0){throw "Actual test failed: $suffix"}
 if($reports.Count -ne 1){throw 'Exactly one fresh XML required'}
 [xml]$xml=Get-Content -LiteralPath $reports[0].FullName -Raw
 if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Zero failure/error/skip actual I required'}
}
function Gate-Wait([string]$suffix){$until=(Get-Date).AddMinutes(15);while((Get-Date) -lt $until){if(Test-Path -LiteralPath "$gate$suffix"){return};if($browser.HasExited){throw "Recovery browser stopped before $suffix"};Start-Sleep -Milliseconds 250};throw "Gate timeout: $suffix"}
function Gate-Signal([string]$suffix){New-Item -ItemType File -Path "$gate$suffix" | Out-Null}
function Start-Actual([hashtable]$settings){
 if(-not $settings.ContainsKey('ProcedureSourceFile')){$settings.ProcedureSourceFile=Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/target-procedure-input.json'}
 if(-not $settings.ContainsKey('ProcedureApprovedBasis')){$settings.ProcedureApprovedBasis='PROJECT-METHOD-ENF17|1|TECHNICAL_TEST_ONLY|PROJECT_TEST_NOT_LEGAL|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_REVIEW'}
 $port=if($settings.HttpsPort){$settings.HttpsPort}else{5444};$prefix=if($port -eq 5444){'server'}else{"server-$port"}
 for($n=1;$n -le 4;$n++){
  & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') @settings -BuildRoot $env:ENF_MAVEN_BUILD_ROOT
  $pidValue=[int](Get-Content (Join-Path $vault "$prefix.pid"));$until=(Get-Date).AddSeconds(45)
  while((Get-Date) -lt $until){$status=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' "https://127.0.0.1:$port/v2/identity/me";if($status -eq '401'){return};if(-not(Get-Process -Id $pidValue -ErrorAction SilentlyContinue)){break};Start-Sleep -Milliseconds 500}
  Copy-Item -LiteralPath (Join-Path $vault "$prefix-out.log") -Destination (Join-Path $evidence "startup-$port-$n.log")
  if(Get-Process -Id $pidValue -ErrorAction SilentlyContinue){throw 'Live runtime failed health check'}
 }
 throw 'Actual restored runtime startup failed'
}
try{
 $env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'source-reconciliation.json'
 Test-Actual 'DocumentRuleRecoveryRuntimeDm8Test' "$RunId-source-I"
 $source=Get-Content -LiteralPath $env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT -Raw|ConvertFrom-Json
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-a.credential.xml'
 $env:ENF_DOCUMENT_RULE_RECOVERY_RUN="$RunId-browser";$env:ENF_DOCUMENT_RULE_RECOVERY_GATE=$gate
 $env:ENF_DOCUMENT_RULE_RECOVERY_ORIGINAL=Join-Path $restoreRoot "document-templates/$($source.templateFileKey)"
 $browser=Start-Process -FilePath 'D:/software/nodejs/node.exe' -ArgumentList 'e2e/enforcement-document-rule-recovery-product.mjs' -WorkingDirectory (Join-Path $workspace 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'browser-out.log') -RedirectStandardError (Join-Path $evidence 'browser-error.log')
 Gate-Wait '.session-ready'
 $runtimePid=[int](Get-Content (Join-Path $vault 'server.pid'));$runtime=Get-Process -Id $runtimePid
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$runtimePid"
 if((Get-NetTCPConnection -State Listen -LocalPort 5444).OwningProcess -ne $runtimePid -or $process.CommandLine -notmatch 'cn.xinji.agentplatform.AgentPlatformApplication'){throw 'Unverified enforcement source owner'}
 Stop-Process -Id $runtimePid;if(-not $runtime.WaitForExit(15000)){throw 'Enforcement runtime did not stop'}
 $inspection=& docker inspect codex-enf-i-business|ConvertFrom-Json
 if($inspection[0].Mounts.Name -notcontains 'codex-enf-i-business-data'){throw 'Unverified enforcement source volume'}
 & docker stop codex-enf-i-business|Out-Null;if($LASTEXITCODE -ne 0){throw 'Source DM8 stop failed'};$sourceStopped=$true
 & docker volume create $volume|Out-Null;if($LASTEXITCODE -ne 0){throw 'New recovery volume creation failed'}
 $image=$inspection[0].Config.Image
 & docker run --rm --entrypoint /bin/sh -v 'codex-enf-i-business-data:/source:ro' -v "${volume}:/target" $image -c 'cp -a /source/. /target/ && sync'|Out-Null
 if($LASTEXITCODE -ne 0){throw 'Physical DB copy failed'}
 foreach($pair in @(@('codex-enf-i-business-data','source'),@($volume,'target'))){
  & docker run --rm --entrypoint /bin/sh -v "$($pair[0]):/snapshot:ro" $image -c 'cd /snapshot && find . -type f -exec sha256sum {} +' *> (Join-Path $evidence "$($pair[1])-physical-db-sha.txt")
  if($LASTEXITCODE -ne 0){throw 'Physical DB SHA failed'}
 }
 $sourceHashes=Get-Content (Join-Path $evidence 'source-physical-db-sha.txt')|Sort-Object;$targetHashes=Get-Content (Join-Path $evidence 'target-physical-db-sha.txt')|Sort-Object
 if(-not $sourceHashes -or (Compare-Object $sourceHashes $targetHashes)){throw 'Physical DB bytes differ before startup'}
 Copy-Item -LiteralPath (Join-Path $vault 'document-templates') -Destination $restoreRoot -Recurse
 Copy-Item -LiteralPath (Join-Path $vault 'files') -Destination $restoreRoot -Recurse
 $catalog=Get-Content -LiteralPath $sourceCatalog -Raw|ConvertFrom-Json
 $values=Join-Path (Split-Path -Parent $sourceCatalog) $catalog.schemas[0].sourceFile
 Copy-Item -LiteralPath $values -Destination (Join-Path $restoreRoot 'values.json')
 $catalog.schemas[0].sourceFile='values.json';$catalog|ConvertTo-Json -Depth 30|Set-Content -LiteralPath (Join-Path $restoreRoot 'catalog.json') -Encoding utf8
 $fileHashes=Get-ChildItem -LiteralPath $restoreRoot -File -Recurse|ForEach-Object{[ordered]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}}
 $fileHashes|ConvertTo-Json -Depth 4|Set-Content (Join-Path $evidence 'checkpoint-files-sha.json') -Encoding utf8
 & docker start codex-enf-i-business|Out-Null;if($LASTEXITCODE -ne 0){throw 'Source DM8 restart failed'}
 Start-Actual @{DocumentRuleCatalogFile=$sourceCatalog};$sourceStopped=$false
 $arguments=@('create','--name',$container,'--privileged','--shm-size=1g','-p','127.0.0.1:5325:5236','-v',"${volume}:/opt/dmdbms/data")
 foreach($entry in $inspection[0].Config.Env){$pair=$entry -split '=',2;if($pair[0] -match '^(SYSDBA_PWD|SYSAUDITOR_PWD|SYSSSO_PWD|INSTANCE_NAME|PAGE_SIZE|EXTENT_SIZE|BLANK_PAD_MODE|LOG_SIZE|CASE_SENSITIVE|UNICODE_FLAG|LENGTH_IN_CHAR|CHARSET)$'){[Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process');$arguments+=@('-e',$pair[0])}}
 $arguments+=$image;& docker @arguments|Out-Null;if($LASTEXITCODE -ne 0){throw 'Recovery container creation failed'}
 & docker start $container|Out-Null;if($LASTEXITCODE -ne 0){throw 'Recovery container start failed'}
 Start-Actual @{HttpsPort=5450;BusinessPort=5325;IdentityIssuer='https://127.0.0.1:5444';DocumentRuleCatalogFile=(Join-Path $restoreRoot 'catalog.json');CurrentDependencyBusinessPort=5311;CurrentDocumentRuleCatalogFile=$sourceCatalog;TemplateFileRoot=(Join-Path $restoreRoot 'document-templates');MaterialFileRoot=(Join-Path $restoreRoot 'files')}
 Gate-Signal '.restore-ready';Gate-Wait '.read-ready'
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5325';$env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $restoreRoot 'document-templates';$env:ENF_DOCUMENT_RULE_FINAL_CATALOG=Join-Path $restoreRoot 'catalog.json'
 $env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'restored-reconciliation.json'
 Test-Actual 'DocumentRuleRecoveryRuntimeDm8Test' "$RunId-restored-I"
 & node -e "const fs=require('node:fs');const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;const [a,b]=process.argv.slice(1).map(p=>JSON.stringify(canonical(JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,'')))));if(a!==b)process.exit(1)" (Join-Path $evidence 'source-reconciliation.json') $env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT
 if($LASTEXITCODE -ne 0){throw 'Restored DB/file/SHA/receipt/event ledger differs'}
 Gate-Signal '.reconciled';Gate-Wait '.effects-ready'
 $env:ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_RULES='3';$env:ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_ATTEMPTS='6'
 $env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'restored-failure-effects.json'
 Test-Actual 'DocumentRuleRecoveryRuntimeDm8Test' "$RunId-effects-I"
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_DOCUMENT_RULE_REVOKE_READ='true';$env:ENF_DOCUMENT_RULE_PREP_OUTPUT=Join-Path $evidence 'withdrawal.json'
 Test-Actual 'EnforcementDocumentRuleGrantFixtureTest' "$RunId-withdrawal-preparation"
 Gate-Signal '.withdraw-ready';$browser.WaitForExit(90000);if(-not $browser.HasExited -or $browser.ExitCode -ne 0){throw 'Actual recovery browser verification failed'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5325';$env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'restored-post-withdrawal.json'
 Test-Actual 'DocumentRuleRecoveryRuntimeDm8Test' "$RunId-post-withdrawal-restored-I"
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates';$env:ENF_DOCUMENT_RULE_FINAL_CATALOG=$sourceCatalog;$env:ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_RULES='2';$env:ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_ATTEMPTS='2';$env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'source-post-withdrawal.json'
 Test-Actual 'DocumentRuleRecoveryRuntimeDm8Test' "$RunId-post-withdrawal-source-I"
 [ordered]@{runId=$RunId;sourceDb=5311;targetDb=5325;sourceVolume='codex-enf-i-business-data';targetVolume=$volume;sourceFileRoot=(Join-Path $vault 'document-templates');targetFileRoot=(Join-Path $restoreRoot 'document-templates');independentAuthority='5312_NOT_RESTORED';currentDependencyDb='5311_NOT_ROLLED_BACK';physicalDbShaEqual=$true;ledgerEqual=$true;browser='PASSED';businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content (Join-Path $evidence 'result.json') -Encoding utf8
 Write-Output "PASSED $RunId ACTUAL_PHYSICAL_DM8_FILES_BROWSER"
}finally{
 if($sourceStopped){& docker start codex-enf-i-business|Out-Null}
 if(-not(Get-NetTCPConnection -State Listen -LocalPort 5444 -ErrorAction SilentlyContinue)){Start-Actual @{DocumentRuleCatalogFile=$sourceCatalog}}
 if($browser -and -not $browser.HasExited){Stop-Process -Id $browser.Id}
 foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD','SYSDBA_PWD','SYSAUDITOR_PWD','SYSSSO_PWD','ENF_DOCUMENT_RULE_REVOKE_READ','ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_RULES','ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_ATTEMPTS')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
}
