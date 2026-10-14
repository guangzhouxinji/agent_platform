param([Parameter(Mandatory=$true)][string]$RunId,[int]$HttpsPort=5453,[int]$FrontendPort=5180,[string]$PreviousFailedRuleId='')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if($RunId -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe fresh execution ID required'}
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
$inputRoot=Join-Path $vault "penalty-positive-input/$RunId"
$originalRoot=Join-Path $vault "penalty-positive-originals/$RunId"
if((Test-Path -LiteralPath $evidence) -or (Test-Path -LiteralPath $inputRoot) -or (Test-Path -LiteralPath $originalRoot)){throw 'New run, input, original and exclusive report roots required'}
if(Get-NetTCPConnection -State Listen -LocalPort $HttpsPort,$FrontendPort -ErrorAction SilentlyContinue){throw 'Dedicated run ports must be unoccupied; never stop unrelated runtime'}
New-Item -ItemType Directory -Path $evidence,$originalRoot | Out-Null
function Secret([string]$name){$secure=Import-Clixml (Join-Path $vault $name);$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}}
function Phase([string]$test,[string]$suffix) {
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE
 $report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence}
 if($exit -ne 0){throw "Actual phase failed: $test; failed report and logs retained"}
 [xml]$xml=Get-Content -LiteralPath $report -Raw
 if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Fresh real one-test zero-skip report required'}
}
try {
 $env:JAVA_HOME='D:/software/jdk-21'
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw | ConvertFrom-Json
 if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit technical identity required; never use it as business acceptance'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_PENALTY_GRANT_REVISION='1';$env:ENF_PENALTY_SEED_INPUT='true'
 $env:ENF_PENALTY_POSITIVE_INPUT_ROOT=$inputRoot;$env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $evidence 'controlled-input-and-grants.json'
 $env:ENF_PENALTY_PRODUCT_CLIENT_ID="enforcement-penalty-$RunId";$env:ENF_PENALTY_FRONTEND_PORT=[string]$FrontendPort
 $env:ENF_PENALTY_WORKSPACE=$workspace;$env:ENF_PENALTY_PRODUCT_FILE_ROOT=$originalRoot
 $env:ENF_PENALTY_PREVIOUS_FAILED_RULE_ID=$PreviousFailedRuleId
 & mvn -q -f $env:ENF_MAVEN_POM -pl enforcement -am '-Dtest=PenaltyFormulaTest,PenaltyPublishChecksTest,PenaltyLifecycleTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId-component" test *> (Join-Path $evidence 'component-execution.log')
 $componentExit=$LASTEXITCODE;$componentTests=0
 $componentReports=@(Get-ChildItem -LiteralPath (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend/enforcement/target/surefire-reports') -Filter "TEST-*-$RunId-component.xml")
 foreach($report in $componentReports){Copy-Item -LiteralPath $report.FullName -Destination $evidence;[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;$componentTests+=[int]$xml.testsuite.tests;if([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Component failure/skip preserved; real I not started'}}
 if($componentExit -ne 0 -or $componentReports.Count -ne 3 -or $componentTests -ne 25){throw 'Complete frozen component suite required; not counted as real I'}
 Phase 'cn.xinji.agentplatform.security.product.EnforcementPenaltyPositiveInputIT' "$RunId-preparation"
 $env:ENF_PENALTY_SEED_INPUT='false'
 $env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$originalRoot
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort $HttpsPort -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -LegalCatalogFile (Join-Path $inputRoot 'catalog.json') -BuildRoot $env:ENF_MAVEN_BUILD_ROOT
 $ready=$false
 for($n=0;$n -lt 90;$n++){$status=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' "https://127.0.0.1:$HttpsPort/v2/identity/me";if($status -eq '401'){$ready=$true;break};Start-Sleep -Milliseconds 500}
 if(-not $ready){throw 'Real HTTPS identity endpoint did not become ready'}
 $env:VITE_ENFORCEMENT_PENALTY_CLIENT_ID=$env:ENF_PENALTY_PRODUCT_CLIENT_ID
 & (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port $FrontendPort -BackendPort $HttpsPort -ProductClientId $env:ENF_PENALTY_PRODUCT_CLIENT_ID
 $ready=$false
 for($n=0;$n -lt 60;$n++){$status=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' "https://127.0.0.1:$FrontendPort/app/enforcement/penalty";if($status -eq '200'){$ready=$true;break};Start-Sleep -Milliseconds 500}
 if(-not $ready){throw 'Real frontend did not become ready'}
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-b.credential.xml'
 $env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-lifecycle-product.mjs') *> (Join-Path $evidence 'browser-execution.log')
 if($LASTEXITCODE -ne 0){throw 'Actual lifecycle browser failed; cannot mark accepted'}
 $env:ENF_PENALTY_RECONCILIATION_EVIDENCE=Join-Path $evidence 'product-db-files-events-receipts.json'
 Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyPositiveBrowserReconciliationIT' "$RunId-reconciliation"
 [ordered]@{runId=$RunId;status='PASSED';scope='PARTIAL_ENF15_REAL_PRODUCT_LIFECYCLE_NOT_FULL_IMP_ACCEPTANCE';domainTests=1;failures=0;errors=0;skipped=0;httpsPort=$HttpsPort;frontendPort=$FrontendPort;inputRoot=$inputRoot;fileRoot=$originalRoot;businessAcceptance='PENDING'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'Actual ENF15 browser/HTTP/DM8/file/SHA/events/receipts lifecycle passed; full ENF16 acceptance remains in progress'
}catch {
 [ordered]@{runId=$RunId;status='FAILED';reason=$_.Exception.Message;scope='NO_IMP_COMPLETION_CLAIM';businessAcceptance='PENDING'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 throw
}finally {foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
