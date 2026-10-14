param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$InputRunId,[int]$HttpsPort=5456,[int]$FrontendPort=5184,[switch]$ConsolidateInputs,[string]$ComponentBaselineRunId='')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
foreach($id in @($RunId,$InputRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe fresh run required'}}
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId";$previous=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$InputRunId"
if((Test-Path -LiteralPath $evidence) -or (Get-NetTCPConnection -State Listen -LocalPort $HttpsPort,$FrontendPort -ErrorAction SilentlyContinue)){throw 'Exclusive new evidence and ports required'}
New-Item -ItemType Directory -Path $evidence|Out-Null
function Secret([string]$name){$secure=Import-Clixml (Join-Path $vault $name);$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}}
try {
 $env:JAVA_HOME='D:/software/jdk-21'; & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 if($ComponentBaselineRunId){
  if($ComponentBaselineRunId -notmatch '^[A-Za-z0-9-]{1,60}$'){throw 'Safe baseline ID required'}
  $baseline=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ComponentBaselineRunId"
  $snapshot=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$ComponentBaselineRunId-build-build-inputs.json") -Raw|ConvertFrom-Json
  foreach($f in $snapshot.files|Where-Object {$_.path.Replace('\','/') -match '/src/main/java/cn/xinji/agentplatform/enforcement/penalty/'}){if((Get-FileHash -LiteralPath (Join-Path $workspace $f.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.sha256){throw 'Changed penalty implementation; component rerun required'}}
  $reports=@(Get-ChildItem -LiteralPath $baseline -Filter "TEST-*-$ComponentBaselineRunId-component.xml");$total=0
  foreach($r in $reports){[xml]$x=Get-Content -LiteralPath $r.FullName -Raw;$total+=[int]$x.testsuite.tests;if([int]$x.testsuite.failures -ne 0 -or [int]$x.testsuite.errors -ne 0 -or [int]$x.testsuite.skipped -ne 0){throw 'Failed/skipped baseline cannot be reused'}}
  if($reports.Count -ne 4 -or $total -ne 29){throw 'Complete exact-source 29 case baseline required'}
  [ordered]@{scope='REUSED_EXACT_SOURCE_COMPONENT_BASELINE_NOT_NEW_TEST_RUN';baselineRunId=$ComponentBaselineRunId;tests=$total;files=@($reports|ForEach-Object {[ordered]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $evidence 'component-baseline-reference.json') -Encoding utf8
 }else{
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am '-Dtest=PenaltyFormulaTest,PenaltyPublishChecksTest,PenaltyLifecycleTest,PenaltyAmountSourceTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId-component" test *> (Join-Path $evidence 'component-execution.log')
 if($LASTEXITCODE -ne 0){throw 'Current penalty component suite failed; no runtime started'}
 $reports=@(Get-ChildItem -LiteralPath (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend') -Recurse -Filter "TEST-*-$RunId-component.xml");$total=0
 foreach($r in $reports){Copy-Item -LiteralPath $r.FullName -Destination $evidence;[xml]$x=Get-Content -LiteralPath $r.FullName -Raw;$total+=[int]$x.testsuite.tests;if([int]$x.testsuite.failures -ne 0 -or [int]$x.testsuite.errors -ne 0 -or [int]$x.testsuite.skipped -ne 0){throw 'Component zero skip required'}}
 if($reports.Count -ne 4 -or $total -ne 29){throw 'Current 29 component cases required; not domain I'}
 }
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw|ConvertFrom-Json;if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit technical identity required'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RUN_ID=$InputRunId;$env:ENF_PENALTY_GRANT_REVISION='1';$env:ENF_PENALTY_SEED_INPUT='false';$env:ENF_PENALTY_POSITIVE_INPUT_ROOT=Join-Path $vault "penalty-positive-input/$InputRunId"
 $env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $evidence 'controlled-input-and-grants.json';$env:ENF_PENALTY_PRODUCT_CLIENT_ID="enforcement-penalty-$RunId";$env:ENF_PENALTY_FRONTEND_PORT=[string]$FrontendPort
 $suffix="$RunId-client-preparation";$test='cn.xinji.agentplatform.security.product.EnforcementPenaltyPositiveInputIT'
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml";if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence};if($exit -ne 0){throw 'Actual client preparation failed'}
 Copy-Item -LiteralPath (Join-Path $previous 'case-input-preparation.json') -Destination (Join-Path $evidence 'case-input-preparation.json')
 $case=Get-Content -LiteralPath (Join-Path $evidence 'case-input-preparation.json') -Raw|ConvertFrom-Json;$originalRoot=Join-Path $vault "penalty-positive-originals/$InputRunId"
 $legalCatalog=Join-Path $env:ENF_PENALTY_POSITIVE_INPUT_ROOT 'catalog.json'
 if($ConsolidateInputs){
  $env:ENF_PENALTY_ORIGINALS_BASE=$vault;$env:ENF_PENALTY_WORKSPACE=$workspace;$env:ENF_PENALTY_RUN_ID=$RunId
  $env:ENF_PENALTY_RUNTIME_INPUT_ROOT=Join-Path $vault "penalty-runtime-input/$RunId";$env:ENF_PENALTY_RUNTIME_INPUT_EVIDENCE=Join-Path $evidence 'retained-runtime-input.json'
  $test='cn.xinji.agentplatform.enforcement.penalty.PenaltyRuntimeInputPreparationIT';$suffix="$RunId-runtime-preparation"
  & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
  $exit=$LASTEXITCODE;$report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml";if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence};if($exit -ne 0){throw 'Retained deployment input verification failed; no runtime started'}
  $retained=Get-Content -LiteralPath $env:ENF_PENALTY_RUNTIME_INPUT_EVIDENCE -Raw|ConvertFrom-Json;$originalRoot=$retained.originalRoot;$legalCatalog=$retained.catalog
 }
 $env:ENFORCEMENT_V2_EVIDENCE_CASE_INPUT_FILE=$case.caseFile;$env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$originalRoot
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort $HttpsPort -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -LegalCatalogFile $legalCatalog -MaterialFileRoot $case.materialRoot -BuildRoot $env:ENF_MAVEN_BUILD_ROOT
 $env:VITE_ENFORCEMENT_PENALTY_CLIENT_ID=$env:ENF_PENALTY_PRODUCT_CLIENT_ID;& (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port $FrontendPort -BackendPort $HttpsPort -ProductClientId $env:ENF_PENALTY_PRODUCT_CLIENT_ID
 [ordered]@{runId=$RunId;inputRunId=$InputRunId;scope='REUSED_RETAINED_INPUT_NOT_NEW_BUSINESS_FACT';componentTests=$total;httpsPort=$HttpsPort;frontendPort=$FrontendPort;caseFile=$case.caseFile;materialRoot=$case.materialRoot;originalRoot=$originalRoot;legalCatalog=$legalCatalog;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'runtime-preparation.json') -Encoding utf8
}finally{foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
