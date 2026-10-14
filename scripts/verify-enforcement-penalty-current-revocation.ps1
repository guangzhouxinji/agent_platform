param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$RuntimeRunId,[Parameter(Mandatory=$true)][string]$PhysicalRunId,[Parameter(Mandatory=$true)][string]$ChangeRunId,[int]$SourceFrontendPort=5186,[int]$RestoreFrontendPort=5190,[int]$RestorePort=5330)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
foreach($id in @($RunId,$RuntimeRunId,$PhysicalRunId,$ChangeRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe identifiers required'}}
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId";$gate=Join-Path $env:TEMP "$RunId-gate"
if((Test-Path -LiteralPath $evidence) -or (Get-ChildItem -LiteralPath $env:TEMP -Filter "$RunId-gate*")){throw 'Fresh exclusive gate required'};New-Item -ItemType Directory -Path $evidence|Out-Null
function Secret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
function Phase([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml";if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence};if($exit -ne 0){throw "Actual phase failed: $suffix"};[xml]$report=Get-Content -LiteralPath $xml -Raw
 if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'Fresh 1/0/0/0 XML required'}
}
function Inventory([string]$tag,[string]$phase,[string]$url,[string]$root,[string]$base){$env:ENF_ACCEPTANCE_BUSINESS_URL=$url;$env:ENF_PENALTY_ORIGINALS_BASE=$base;$env:ENF_PENALTY_SOURCE_FILE_ROOT=$root;$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence "$tag-$phase.json";Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoverySourceDm8IT' "$RunId-$tag-$phase-audit"}
try {
 $physical=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$PhysicalRunId/execution.json") -Raw|ConvertFrom-Json;if($physical.status -ne 'PASSED' -or $physical.skipped -ne 0 -or $physical.targetDb -ne "jdbc:dm://127.0.0.1:$RestorePort"){throw 'Actual passed independent physical target required'}
 $runtime=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RuntimeRunId/runtime-preparation.json") -Raw|ConvertFrom-Json
 $change=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ChangeRunId/browser/result.json") -Raw|ConvertFrom-Json
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw|ConvertFrom-Json;if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit technical identity required'}
 $env:JAVA_HOME='D:/software/jdk-21';& (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml';$env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization;$env:ENF_PENALTY_RULE_ID=$change.ruleId
 $targetRoot=Join-Path $env:TEMP "$PhysicalRunId-independent-product";$targetFiles=Join-Path $targetRoot 'penalty-originals'
 Inventory 'source' 'before' 'jdbc:dm://127.0.0.1:5311' $runtime.originalRoot $vault
 Inventory 'target' 'before' "jdbc:dm://127.0.0.1:$RestorePort" $targetFiles $targetRoot
 $env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence 'source-before.json';$env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser';$env:ENF_PENALTY_REVOCATION_GATE=$gate;$env:ENF_PENALTY_RUN_ID=$RunId
 $env:ENF_PENALTY_CURRENT_RULE_LABEL="[TEST ONLY] Rule revision update $ChangeRunId";$env:ENF_PENALTY_CURRENT_RULE_REVISION=[string]$change.ruleRevision;$env:ENF_PENALTY_SOURCE_FRONTEND_PORT=[string]$SourceFrontendPort;$env:ENF_PENALTY_RESTORE_FRONTEND_PORT=[string]$RestoreFrontendPort
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-b.credential.xml'
 $browser=Start-Process -FilePath 'D:/software/nodejs/node.exe' -ArgumentList (Join-Path $workspace 'frontend/e2e/enforcement-penalty-revocation-product.mjs') -WorkingDirectory $workspace -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'browser-out.log') -RedirectStandardError (Join-Path $evidence 'browser-error.log')
 $until=(Get-Date).AddMinutes(4);while(-not(Test-Path -LiteralPath "$gate.sessions-ready")){if($browser.HasExited -or (Get-Date) -gt $until){throw 'Actual dual product normal trials not ready; no revocation performed'};Start-Sleep -Milliseconds 250}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_PENALTY_REVOKE_TRIAL_REVISION=[string]$change.ruleRevision;$env:ENF_PENALTY_REVOKE_EDIT_REVISION=[string]($change.ruleRevision-1);$env:ENF_PENALTY_REVOKE_DATA_SCOPE='EXPLICIT_SIMULATED_INPUT_NOT_LEGAL';$env:ENF_PENALTY_REVOCATION_EVIDENCE=Join-Path $evidence 'grant-revocation-preparation.json'
 Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyTechnicalGrantRevokeIT' "$RunId-authorization-preparation"
 New-Item -ItemType File -Path "$gate.revoked"|Out-Null
 $until=(Get-Date).AddMinutes(3);while(-not $browser.HasExited){if((Get-Date) -gt $until){throw 'Actual revoked browser did not finish'};Start-Sleep -Milliseconds 250};if($browser.ExitCode -ne 0){throw 'Actual revoked browser failed'}
 Inventory 'source' 'after' 'jdbc:dm://127.0.0.1:5311' $runtime.originalRoot $vault
 Inventory 'target' 'after' "jdbc:dm://127.0.0.1:$RestorePort" $targetFiles $targetRoot
 $env:ENF_PENALTY_REVOCATION_RECONCILIATION_ROOT=$evidence;Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyCurrentRevocationDm8IT' "$RunId-domain-reconciliation"
 [ordered]@{runId=$RunId;status='PASSED';scope='REAL_CURRENT_SOURCE_OLD_PHYSICAL_RESTORE_REVOCATION_NOT_FINAL_ACCEPTANCE';domainTests=1;auditPreparationTests=5;failures=0;errors=0;skipped=0;sourceTrial=403;oldPhysicalRestoreTrial=503;domainMutations=0;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
}catch{[ordered]@{runId=$RunId;status='FAILED';reason=$_.Exception.Message}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'failure.json') -Encoding utf8;throw}
finally{foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
