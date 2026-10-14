param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$RuntimeRunId,[Parameter(Mandatory=$true)][string]$ProductRunId,[int]$RestorePort=5327,[int]$HttpsPort=5459,[int]$FrontendPort=5187,[string]$ChangeRunId="")
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
foreach($id in @($RunId,$RuntimeRunId,$ProductRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe run IDs required'}}
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId";$restoreRoot=Join-Path $env:TEMP "$RunId-independent-product";$volume='codex-enf-penalty-'+$RunId.ToLowerInvariant();$container=$volume
if((Test-Path -LiteralPath $evidence) -or (Test-Path -LiteralPath $restoreRoot) -or (Get-NetTCPConnection -State Listen -LocalPort $RestorePort,$HttpsPort,$FrontendPort -ErrorAction SilentlyContinue)){throw 'Fresh recovery roots and exclusive ports required'}
if((& docker volume ls --format '{{.Name}}') -contains $volume){throw 'New recovery volume required'}
New-Item -ItemType Directory -Path $evidence,$restoreRoot|Out-Null
function Secret([string]$name){$secure=Import-Clixml (Join-Path $vault $name);$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
function Phase([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml";if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence}
 if($exit -ne 0){throw "Actual phase failed: $suffix; retained original failure"};[xml]$x=Get-Content -LiteralPath $report -Raw
 if([int]$x.testsuite.tests -ne 1 -or [int]$x.testsuite.failures -ne 0 -or [int]$x.testsuite.errors -ne 0 -or [int]$x.testsuite.skipped -ne 0){throw 'New one-test zero-skip XML required'}
}
$sourceStopped=$false
function Wait-Http([string]$url,[string]$expected){for($n=0;$n -lt 90;$n++){$http=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' $url;if($http -eq $expected){return};Start-Sleep -Milliseconds 500};throw "Actual startup endpoint unavailable: $url"}
try {
 $runtime=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RuntimeRunId/runtime-preparation.json") -Raw|ConvertFrom-Json
 $product=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ProductRunId/browser/result.json") -Raw|ConvertFrom-Json
 $accepted=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ProductRunId/execution.json") -Raw|ConvertFrom-Json;if($accepted.status -ne 'PASSED' -or $accepted.skipped -ne 0){throw 'Current actual browser/domain reconciliation must pass before recovery'}
 $case=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RuntimeRunId/case-input-preparation.json") -Raw|ConvertFrom-Json
 $ruleRevision=5;$ruleLabel="[TEST ONLY] Complete lifecycle $ProductRunId";$expectedState="PENDING_REVIEW"
 if($ChangeRunId){
  if($ChangeRunId -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe change run ID required'}
  $changeDir=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ChangeRunId";$changeGate=Get-Content -LiteralPath (Join-Path $changeDir 'execution.json') -Raw|ConvertFrom-Json
  if($changeGate.status -ne 'PASSED' -or $changeGate.skipped -ne 0){throw 'Actual rule/source change must pass before recovery'}
  $change=Get-Content -LiteralPath (Join-Path $changeDir 'browser/result.json') -Raw|ConvertFrom-Json;if($change.ruleId -ne $product.ruleIds[0] -or $change.changedSource.state -ne 'WAITING_INPUT'){throw 'Unexpected changed checkpoint'}
  $ruleRevision=[long]$change.ruleRevision;$ruleLabel="[TEST ONLY] Rule revision update $ChangeRunId";$expectedState='WAITING_INPUT'
 }
 $env:ENF_PENALTY_CURRENT_RULE_REVISION=[string]$ruleRevision;$env:ENF_PENALTY_CURRENT_RULE_LABEL=$ruleLabel;$env:ENF_PENALTY_EXPECTED_RESTORED_STATE=$expectedState
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw|ConvertFrom-Json;if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Labelled technical identity required'}
 $env:JAVA_HOME='D:/software/jdk-21';& (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RULE_ID=$product.ruleIds[0];$env:ENF_PENALTY_RESTORED_SUGGESTION_ID="ENF16-RESTORED-$RunId"
 $scopes=@([ordered]@{type='PENALTY_RULE';id=$env:ENF_PENALTY_RULE_ID;revision=$ruleRevision;action='TRIAL';purpose='PENALTY_ANALYSIS'},[ordered]@{type='PENALTY_RULE';id=$env:ENF_PENALTY_RULE_ID;revision=$ruleRevision;action='READ';purpose='PENALTY_ANALYSIS'})
 foreach($action in @('CREATE','READ')){$scopes+=[ordered]@{type='PENALTY_SUGGESTION';id=$env:ENF_PENALTY_RESTORED_SUGGESTION_ID;revision=1;action=$action;purpose='PENALTY_ANALYSIS'}}
 $env:ENF_PENALTY_SCENARIO_SCOPES=Join-Path $evidence 'exact-restored-product-scopes.json';ConvertTo-Json -InputObject $scopes -Depth 4|Set-Content -LiteralPath $env:ENF_PENALTY_SCENARIO_SCOPES -Encoding utf8
 $env:ENF_PENALTY_SCENARIO_GRANT_EVIDENCE=Join-Path $evidence 'client-grant-preparation.json';$env:ENF_PENALTY_SCENARIO_CLIENT_ID="enforcement-penalty-$RunId";$env:ENF_PENALTY_SCENARIO_REDIRECT_URI="https://127.0.0.1:$FrontendPort/app/enforcement/penalty"
 Phase 'cn.xinji.agentplatform.security.product.EnforcementPenaltyScenarioGrantsIT' "$RunId-preparation"
 $env:ENF_PENALTY_ORIGINALS_BASE=$vault;$env:ENF_PENALTY_SOURCE_FILE_ROOT=$runtime.originalRoot;$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence 'source-inventory.json'
 Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoverySourceDm8IT' "$RunId-source"
 $inventory=Get-Content -LiteralPath $env:ENF_PENALTY_SOURCE_INVENTORY -Raw|ConvertFrom-Json
 $inspection=& docker inspect codex-enf-i-business|ConvertFrom-Json;if($inspection[0].Mounts.Name -notcontains 'codex-enf-i-business-data'){throw 'Source volume not verified'};$image=$inspection[0].Config.Image
 & docker stop codex-enf-i-business|Out-Null;if($LASTEXITCODE -ne 0){throw 'Actual source stop failed'};$sourceStopped=$true
 & docker volume create $volume|Out-Null;if($LASTEXITCODE -ne 0){throw 'New target volume failed'}
 & docker run --rm --entrypoint /bin/sh -v 'codex-enf-i-business-data:/source:ro' -v "$($volume):/target" $image -c 'cp -a /source/. /target/ && sync'|Out-Null;if($LASTEXITCODE -ne 0){throw 'Actual cold DM8 physical copy failed'}
 foreach($pair in @(@('codex-enf-i-business-data','source'),@($volume,'target'))){& docker run --rm --entrypoint /bin/sh -v "$($pair[0]):/snapshot:ro" $image -c 'cd /snapshot && find . -type f -exec sha256sum {} +' *> (Join-Path $evidence "$($pair[1])-physical-db-sha.txt");if($LASTEXITCODE -ne 0){throw 'Physical DB SHA read failed'}}
 $a=Get-Content -LiteralPath (Join-Path $evidence 'source-physical-db-sha.txt')|Sort-Object;$b=Get-Content -LiteralPath (Join-Path $evidence 'target-physical-db-sha.txt')|Sort-Object;if(-not $a -or (Compare-Object $a $b)){throw 'Physical DB bytes differ'}
 $penaltyRoot=Join-Path $restoreRoot 'penalty-originals';New-Item -ItemType Directory -Path $penaltyRoot|Out-Null
 foreach($record in $inventory.records){if((Get-FileHash -LiteralPath $record.originalPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record.sha256){throw 'Source original changed at cold checkpoint'};Copy-Item -LiteralPath $record.originalPath -Destination (Join-Path $penaltyRoot $record.row.ARTIFACT_KEY)}
 Copy-Item -LiteralPath $runtime.materialRoot -Destination (Join-Path $restoreRoot 'material') -Recurse;Copy-Item -LiteralPath $runtime.caseFile -Destination (Join-Path $restoreRoot 'case.json');Copy-Item -LiteralPath (Join-Path $vault 'document-templates') -Destination (Join-Path $restoreRoot 'document-templates') -Recurse
 $materialSource=Join-Path $runtime.materialRoot "$($case.materialKey).blob";$materialTarget=Join-Path (Join-Path $restoreRoot 'material') "$($case.materialKey).blob";if((Get-FileHash -LiteralPath $materialTarget -Algorithm SHA256).Hash.ToLowerInvariant() -ne $case.materialSha256){throw 'Restored input original SHA mismatch'}
 [ordered]@{sourceCase=$runtime.caseFile;targetCase=(Join-Path $restoreRoot 'case.json');caseSha=(Get-FileHash -LiteralPath $runtime.caseFile -Algorithm SHA256).Hash.ToLowerInvariant();sourceMaterial=$materialSource;targetMaterial=$materialTarget;materialSha=$case.materialSha256;currentLegalSourceOutsideBackup=$runtime.legalCatalog}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'case-material-checkpoint.json') -Encoding utf8
 if((Get-FileHash -LiteralPath (Join-Path $restoreRoot 'case.json') -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $runtime.caseFile -Algorithm SHA256).Hash){throw 'Restored case bytes differ'}
 & docker start codex-enf-i-business|Out-Null;if($LASTEXITCODE -ne 0){throw 'Source restart failed'};$sourceStopped=$false
 $arguments=@('create','--name',$container,'--privileged','--shm-size=1g','-p',"127.0.0.1:$($RestorePort):5236",'-v',"$($volume):/opt/dmdbms/data")
 foreach($entry in $inspection[0].Config.Env){$pair=$entry -split '=',2;if($pair[0] -match '^(SYSDBA_PWD|SYSAUDITOR_PWD|SYSSSO_PWD|INSTANCE_NAME|PAGE_SIZE|EXTENT_SIZE|BLANK_PAD_MODE|LOG_SIZE|CASE_SENSITIVE|UNICODE_FLAG|LENGTH_IN_CHAR|CHARSET)$'){[Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process');$arguments+=@('-e',$pair[0])}};$arguments+=$image
 & docker @arguments|Out-Null;if($LASTEXITCODE -ne 0){throw 'Independent target creation failed'};& docker start $container|Out-Null;if($LASTEXITCODE -ne 0){throw 'Target start failed'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL="jdbc:dm://127.0.0.1:$RestorePort"
 $classRoots=@(Get-ChildItem -LiteralPath (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend') -Directory -Recurse|Where-Object {$_.Name -in @('test-classes','classes') -and $_.Parent.Name -eq 'target'}|ForEach-Object {$_.FullName});$classpath=($classRoots+((Get-Content -LiteralPath (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim())) -join ';'
 & 'D:/software/jdk-21/bin/java.exe' -cp $classpath cn.xinji.agentplatform.enforcement.penalty.PenaltyDm8Readiness *> (Join-Path $evidence 'restore-sql-readiness.log');if($LASTEXITCODE -ne 0){throw 'Actual restored SQL not ready; product I NOT_RUN'}
 $env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$penaltyRoot;$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_URL='jdbc:dm://127.0.0.1:5311';$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_USER='ENFACC_BIZ';$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_PASSWORD=$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD;$env:ENFORCEMENT_V2_PENALTY_CURRENT_FILE_ROOT=$runtime.originalRoot;$env:ENFORCEMENT_V2_EVIDENCE_CASE_INPUT_FILE=Join-Path $restoreRoot 'case.json'
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort $HttpsPort -BusinessPort $RestorePort -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -BuildRoot $env:ENF_MAVEN_BUILD_ROOT -MaterialFileRoot (Join-Path $restoreRoot 'material') -TemplateFileRoot (Join-Path $restoreRoot 'document-templates') -LegalCatalogFile $runtime.legalCatalog
 Wait-Http "https://127.0.0.1:$HttpsPort/v2/identity/me" '401'
 $env:ENF_PENALTY_SOURCE_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_PENALTY_RESTORE_ORIGINALS=$penaltyRoot;$env:ENF_PENALTY_TARGET_INVENTORY=Join-Path $evidence 'target-before-browser.json'
 Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoveryTargetDm8IT' "$RunId-target-before"
 $env:VITE_ENFORCEMENT_PENALTY_CLIENT_ID=$env:ENF_PENALTY_SCENARIO_CLIENT_ID;& (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port $FrontendPort -BackendPort $HttpsPort -ProductClientId $env:ENF_PENALTY_SCENARIO_CLIENT_ID
 Wait-Http "https://127.0.0.1:$FrontendPort/app/enforcement/penalty" '200'
 $selected=@($inventory.records|Where-Object {$_.row.RESOURCE_ID -eq $env:ENF_PENALTY_RULE_ID -and $_.row.REVISION -eq $ruleRevision});if($selected.Count -ne 1){throw 'Actual current product rule original missing'}
 $env:ENF_PENALTY_RECOVERY_ORIGINAL=Join-Path $penaltyRoot $selected[0].row.ARTIFACT_KEY;$env:ENF_PENALTY_RECOVERY_MATERIAL=$materialTarget;$env:ENF_PENALTY_FRONTEND_PORT=[string]$FrontendPort;$env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_PENALTY_BASELINE_RUN_ID=$ProductRunId;$env:ENF_PENALTY_CASE_PREP_EVIDENCE=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RuntimeRunId/case-input-preparation.json";$env:ENF_PENALTY_PRODUCT_BASELINE=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ProductRunId/browser/result.json"
 $env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser';$env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-b.credential.xml'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-current-recovery-product.mjs') *> (Join-Path $evidence 'browser-execution.log');if($LASTEXITCODE -ne 0){throw 'Actual restored browser failed'}
 $env:ENF_PENALTY_RECONCILIATION_EVIDENCE=Join-Path $evidence 'target-after-browser.json';Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRestoredGenerationDm8IT' "$RunId-target-after"
 [ordered]@{runId=$RunId;status='PASSED';scope='REAL_ENF15_ENF16_JOINT_PHYSICAL_RECOVERY_NOT_FINAL_IMP_ACCEPTANCE';domainTests=2;failures=0;errors=0;skipped=0;sourceVolume='codex-enf-i-business-data';targetVolume=$volume;targetDb="jdbc:dm://127.0.0.1:$RestorePort";sourceDomainOriginals=$inventory.records.Count;physicalDbShaExact=$true;currentAuthorityOutsideBackup=$true;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
}catch{[ordered]@{runId=$RunId;status='FAILED';reason=$_.Exception.Message;scope='ACTUAL_RECOVERY_FAILURE_NOT_ACCEPTED'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'failure.json') -Encoding utf8;throw}
finally{if($sourceStopped){& docker start codex-enf-i-business|Out-Null};foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD','ENFORCEMENT_V2_PENALTY_CURRENT_DB_PASSWORD','SYSDBA_PWD','SYSAUDITOR_PWD','SYSSSO_PWD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
