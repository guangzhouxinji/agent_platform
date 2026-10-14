param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$BaselineRunId)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
$baseline=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$BaselineRunId"
if($RunId -notmatch '^[A-Za-z0-9-]{1,60}$' -or $BaselineRunId -notmatch '^[A-Za-z0-9-]{1,60}$' -or (Test-Path -LiteralPath $evidence)){throw 'Fresh safe evidence ID and exact baseline required'}
$manifest=Get-Content -LiteralPath "$baseline-build-build-inputs.json" -Raw | ConvertFrom-Json
foreach($file in $manifest.files){if($file.path.Replace('\','/') -match '/src/main/.*penalty/' -or $file.path.EndsWith('ProductEnforcementApplication.java')){if((Get-FileHash -LiteralPath (Join-Path $workspace $file.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256){throw 'Actual runtime penalty source differs; cannot reuse source product'}}}
$ownedPid=[int](Get-Content -LiteralPath (Join-Path $vault 'server-5453.pid'))
$ownedProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid"
if(-not $ownedProcess.CommandLine.Contains("enf-isolated-build-$BaselineRunId-build") -or -not $ownedProcess.CommandLine.Contains('--product-enforcement')){throw 'Exact real source runtime required'}
New-Item -ItemType Directory -Path $evidence | Out-Null
function Secret([string]$name){$secure=Import-Clixml (Join-Path $vault $name);$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
function Phase([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence}
 if($exit -ne 0){throw "Actual phase failed: $test; evidence retained"}
 [xml]$xml=Get-Content -LiteralPath $report -Raw
 if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'One fresh zero-failure zero-skip report required'}
}
try{
 $env:JAVA_HOME='D:/software/jdk-21'
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw|ConvertFrom-Json
 if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit technical identity only'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_WORKSPACE=$workspace;$env:ENF_PENALTY_RUN_ID=$BaselineRunId;$env:ENF_PENALTY_NEGATIVE_RUN_ID=$RunId
 $env:ENF_PENALTY_SEED_INPUT='false';$env:ENF_PENALTY_GRANT_REVISION='3'
 $env:ENF_PENALTY_POSITIVE_INPUT_ROOT=Join-Path $vault "penalty-positive-input/$BaselineRunId"
 $env:ENF_PENALTY_PRODUCT_FILE_ROOT=Join-Path $vault "penalty-positive-originals/$BaselineRunId"
 $env:ENF_PENALTY_PRODUCT_CLIENT_ID="enforcement-penalty-$BaselineRunId";$env:ENF_PENALTY_FRONTEND_PORT='5180'
 $env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $evidence 'current-grant-preparation.json'
 Phase 'cn.xinji.agentplatform.security.product.EnforcementPenaltyPositiveInputIT' "$RunId-preparation"
 $env:ENF_PENALTY_BASELINE_RESULT=Join-Path $baseline 'browser/result.json'
 $env:ENF_PENALTY_BASELINE_RECONCILIATION=Join-Path $baseline 'product-db-files-events-receipts.json'
 $env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser'
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-b.credential.xml'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-transition-negatives.mjs') *> (Join-Path $evidence 'browser-execution.log')
 if($LASTEXITCODE -ne 0){throw 'Actual transition negative failed; no acceptance claim'}
 $env:ENF_PENALTY_RECONCILIATION_EVIDENCE=Join-Path $evidence 'product-db-files-events-receipts.json'
 Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyTransitionNegativesDm8IT' "$RunId-reconciliation"
 [ordered]@{runId=$RunId;baselineRunId=$BaselineRunId;status='PASSED';scope='PARTIAL_ENF15_REAL_HTTP_NEGATIVES_NOT_FULL_IMP_ACCEPTANCE';domainTests=1;failures=0;errors=0;skipped=0;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'Actual ENF15 transition replay/old-success fail-closed negatives passed with DB/file/event/receipt no-mutation reconciliation'
}catch{[ordered]@{runId=$RunId;baselineRunId=$BaselineRunId;status='FAILED';reason=$_.Exception.Message;scope='NO_IMP_COMPLETION_CLAIM'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8;throw}
finally{foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
