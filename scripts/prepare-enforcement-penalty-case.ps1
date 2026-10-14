param([Parameter(Mandatory=$true)][string]$RunId,[int]$HttpsPort=5454,[int]$FrontendPort=5181)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if($RunId -notmatch '^[A-Za-z0-9-]{1,60}$' -or (Test-Path -LiteralPath $evidence)){throw 'Fresh safe run required'}
if(Get-NetTCPConnection -State Listen -LocalPort $HttpsPort,$FrontendPort -ErrorAction SilentlyContinue){throw 'Exclusive unused runtime ports required'}
New-Item -ItemType Directory -Path $evidence | Out-Null
function Secret([string]$name){$secure=Import-Clixml (Join-Path $vault $name);$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}}
function Phase([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$report=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $report){Copy-Item -LiteralPath $report -Destination $evidence}
 if($exit -ne 0){throw "Preparation failed: $test; not domain I"}
 [xml]$xml=Get-Content -LiteralPath $report -Raw
 if([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Fresh zero-skip preparation XML required'}
}
try {
 $env:JAVA_HOME='D:/software/jdk-21'; & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $identity=Get-Content -LiteralPath (Join-Path $vault 'functional-b.identity.json') -Raw|ConvertFrom-Json
 if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Only labelled technical identity permitted'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_PENALTY_WORKSPACE=$workspace;$env:ENF_PENALTY_GRANT_REVISION='1';$env:ENF_PENALTY_SEED_INPUT='true'
 $env:ENF_PENALTY_POSITIVE_INPUT_ROOT=Join-Path $vault "penalty-positive-input/$RunId";$env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $evidence 'controlled-input-and-grants.json'
 $env:ENF_PENALTY_PRODUCT_CLIENT_ID="enforcement-penalty-$RunId";$env:ENF_PENALTY_FRONTEND_PORT=[string]$FrontendPort
 $env:ENF_PENALTY_PRODUCT_FILE_ROOT=Join-Path $vault "penalty-positive-originals/$RunId";New-Item -ItemType Directory -Path $env:ENF_PENALTY_PRODUCT_FILE_ROOT|Out-Null
 Phase 'cn.xinji.agentplatform.security.product.EnforcementPenaltyPositiveInputIT' "$RunId-legal-preparation"
 $env:ENF_PENALTY_CASE_INPUT_ROOT=Join-Path $vault "penalty-case-input/$RunId";$env:ENF_PENALTY_CASE_PREP_EVIDENCE=Join-Path $evidence 'case-input-preparation.json'
 $env:ENF_ACCEPTANCE_AUTHORITY_DEPLOY_PASSWORD=Secret 'authority-deploy.credential.xml'
 Phase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyControlledCasePreparationIT' "$RunId-case-preparation"
 $case=Get-Content -LiteralPath $env:ENF_PENALTY_CASE_PREP_EVIDENCE -Raw|ConvertFrom-Json
 $env:ENFORCEMENT_V2_EVIDENCE_CASE_INPUT_FILE=$case.caseFile;$env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$env:ENF_PENALTY_PRODUCT_FILE_ROOT
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort $HttpsPort -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -LegalCatalogFile (Join-Path $env:ENF_PENALTY_POSITIVE_INPUT_ROOT 'catalog.json') -MaterialFileRoot $case.materialRoot -BuildRoot $env:ENF_MAVEN_BUILD_ROOT
 $env:VITE_ENFORCEMENT_PENALTY_CLIENT_ID=$env:ENF_PENALTY_PRODUCT_CLIENT_ID
 & (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port $FrontendPort -BackendPort $HttpsPort -ProductClientId $env:ENF_PENALTY_PRODUCT_CLIENT_ID
 [ordered]@{runId=$RunId;scope='INPUT_PREPARATION_NOT_DOMAIN_I';httpsPort=$HttpsPort;frontendPort=$FrontendPort;caseId=$case.input.caseId;caseFile=$case.caseFile;materialRoot=$case.materialRoot;originalRoot=$env:ENF_PENALTY_PRODUCT_FILE_ROOT;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'runtime-preparation.json') -Encoding utf8
}catch { [ordered]@{runId=$RunId;status='FAILED';scope='PREPARATION_NOT_DOMAIN_I';reason=$_.Exception.Message}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'failure.json') -Encoding utf8;throw }
finally {foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_DEPLOY_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
