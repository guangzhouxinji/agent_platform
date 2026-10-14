param([Parameter(Mandatory=$true)][string]$RunId,[string]$PhysicalRunId='ENF15-16-COLD-20261001-132')
$ErrorActionPreference='Stop';foreach($id in @($RunId,$PhysicalRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe unique execution IDs required'}}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId";$gate=Join-Path $env:TEMP "$RunId-gate"
if((Test-Path -LiteralPath $evidence) -or (Get-ChildItem -LiteralPath $env:TEMP -Filter "$RunId-gate*")){throw 'Fresh evidence/gate required'};New-Item -ItemType Directory -Path $evidence|Out-Null
function Secret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
function Actual([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence};if($exit -ne 0 -or -not(Test-Path -LiteralPath $xml)){throw "Actual phase failed: $suffix"}
 [xml]$report=Get-Content -LiteralPath $xml -Raw;if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'Actual phase requires 1/0/0/0'}
}
try {
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml';$env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw|ConvertFrom-Json;if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Only recorded test identity may be revoked'}
 $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization;$env:ENF_PENALTY_RULE_ID='ENF15-ENF15-PRODUCT-20261001-119'
 $env:ENF_PENALTY_ORIGINALS_BASE=$vault;$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence 'source-before.json';Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoverySourceDm8IT' "$RunId-source-before"
 $before=Get-Content -LiteralPath $env:ENF_PENALTY_SOURCE_INVENTORY -Raw|ConvertFrom-Json
 $env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser';$env:ENF_PENALTY_REVOCATION_GATE=$gate;$env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-a.credential.xml'
 $browser=Start-Process -FilePath 'D:/software/nodejs/node.exe' -ArgumentList (Join-Path $workspace 'frontend/e2e/enforcement-penalty-revocation-product.mjs') -WorkingDirectory $workspace -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'browser-out.log') -RedirectStandardError (Join-Path $evidence 'browser-error.log')
 $until=(Get-Date).AddMinutes(4);while(-not(Test-Path -LiteralPath "$gate.sessions-ready")){if($browser.HasExited -or (Get-Date) -gt $until){throw 'Actual dual product sessions not ready; no revocation prepared'};Start-Sleep -Milliseconds 250}
 $env:ENF_PENALTY_REVOCATION_EVIDENCE=Join-Path $evidence 'technical-grant-revocation.json';Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyTechnicalGrantRevokeIT' "$RunId-grant-preparation"
 New-Item -ItemType File -Path "$gate.revoked"|Out-Null
 $until=(Get-Date).AddMinutes(3);while(-not $browser.HasExited){if((Get-Date) -gt $until){throw 'Actual revocation browser did not finish'};Start-Sleep -Milliseconds 250};if($browser.ExitCode -ne 0){throw 'Actual revocation browser failed'}
 $env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence 'source-after.json';Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoverySourceDm8IT' "$RunId-source-after"
 $after=Get-Content -LiteralPath $env:ENF_PENALTY_SOURCE_INVENTORY -Raw|ConvertFrom-Json
 foreach($field in @('records','receipts','events')){if((ConvertTo-Json $before.$field -Depth 50 -Compress) -ne (ConvertTo-Json $after.$field -Depth 50 -Compress)){throw "Revocation caused unexpected domain change: $field"}}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5326';$env:ENF_PENALTY_SOURCE_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$PhysicalRunId/source-inventory.json";$env:ENF_PENALTY_RESTORE_ORIGINALS=Join-Path $env:TEMP "$PhysicalRunId-physical-product/penalty";$env:ENF_PENALTY_TARGET_INVENTORY=Join-Path $evidence 'old-restored-after.json';Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoveryTargetDm8IT' "$RunId-old-restore-after"
 [ordered]@{runId=$RunId;scope='REAL_SOURCE_AND_OLD_PHYSICAL_RESTORE_PERMISSION_REPLAY_FAILURE_CLOSE_NOT_FINAL_ACCEPTANCE';domainTests=3;failures=0;errors=0;skipped=0;grantPreparationTests=1;sourceTrial=403;oldRestoredTrial=503;sourceOldSaveReplay=403;oldRestoreSaveReplay=503;domainDbOriginalsEventsReceiptsUnchanged=$true;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'Actual source403/old physical restore503 and old command replay fail-closed; domain DB/files/events/receipts unchanged; final IMP still pending'
}catch{[ordered]@{runId=$RunId;status='FAILED';message=$_.Exception.Message}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'failure.json') -Encoding utf8;throw}
finally{foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
