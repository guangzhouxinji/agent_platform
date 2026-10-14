param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$InputRunId,[int]$FrontendPort=5181)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
foreach($id in @($RunId,$InputRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe run ID required'}}
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId";$inputs=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$InputRunId"
if(Test-Path -LiteralPath $evidence){throw 'Exclusive new evidence required'};New-Item -ItemType Directory -Path $evidence|Out-Null
function Secret([string]$name){$secure=Import-Clixml (Join-Path $vault $name);$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}}
try {
 $env:JAVA_HOME='D:/software/jdk-21'; & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw|ConvertFrom-Json;if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit technical identity required'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_PENALTY_WORKSPACE=$workspace;$env:ENF_PENALTY_FRONTEND_PORT=[string]$FrontendPort
 $env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $inputs 'controlled-input-and-grants.json';$env:ENF_PENALTY_CASE_PREP_EVIDENCE=Join-Path $inputs 'case-input-preparation.json'
 $runtime=Get-Content -LiteralPath (Join-Path $inputs 'runtime-preparation.json') -Raw|ConvertFrom-Json
 $env:ENF_PENALTY_PRODUCT_FILE_ROOT=$runtime.originalRoot;$env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser'
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-b.credential.xml'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-complete-product.mjs') *> (Join-Path $evidence 'browser-execution.log')
 if($LASTEXITCODE -ne 0){throw 'Actual browser verification failed; no completion claim'}
 $env:ENF_PENALTY_RECONCILIATION_EVIDENCE=Join-Path $evidence 'product-db-files-events-receipts.json'
 $test='cn.xinji.agentplatform.enforcement.penalty.PenaltyCompleteBrowserReconciliationIT';$suffix="$RunId-reconciliation"
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml";if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence}
 if($exit -ne 0){throw 'Real product reconciliation failed; no acceptance claim'};[xml]$xml=Get-Content -LiteralPath $report -Raw
 if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Actual new zero-skip domain reconciliation XML required'}
 [ordered]@{runId=$RunId;status='PASSED';scope='REAL_ENF15_ENF16_BROWSER_RECONCILIATION_NOT_FINAL_IMP_ACCEPTANCE';tests=1;failures=0;errors=0;skipped=0;inputRunId=$InputRunId;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
}catch{[ordered]@{runId=$RunId;status='FAILED';reason=$_.Exception.Message;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8;throw}
finally{foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
