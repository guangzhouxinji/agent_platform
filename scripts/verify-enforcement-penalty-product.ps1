param([Parameter(Mandatory=$true)][string]$RunId,[string]$ExistingRuleRunId='',[string]$RuntimeBaselineRunId='')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
function Read-PenaltySecret([string]$name) {
 $secure=Import-Clixml (Join-Path $vault $name)
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
function Invoke-PenaltyPhase([string]$test,[string]$suffix) {
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE
 $xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence}
 if($exit -ne 0){throw "Actual phase failed: $test; evidence retained"}
 [xml]$report=Get-Content -LiteralPath $xml -Raw
 if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'One real zero-skip report required'}
}
try {
 if($ExistingRuleRunId) {
  $evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
  if(Test-Path -LiteralPath $evidence){throw 'Fresh continuation evidence required'}
  New-Item -ItemType Directory -Path $evidence | Out-Null
  $runtimeBaseline=if($RuntimeBaselineRunId){$RuntimeBaselineRunId}else{$ExistingRuleRunId}
  $baseline=Get-Content -LiteralPath (Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$runtimeBaseline-build-build-inputs.json") -Raw|ConvertFrom-Json
  $runtime=[int](Get-Content -LiteralPath (Join-Path $vault 'server-5451.pid'))
  $process=Get-CimInstance Win32_Process -Filter "ProcessId=$runtime"
  if(-not $process.CommandLine.Contains("enf-isolated-build-$runtimeBaseline-build") -or -not $process.CommandLine.Contains('--product-enforcement')){throw 'Actual source runtime baseline mismatch'}
  $verifiedSources=0
  foreach($file in $baseline.files) {
   $normalized=$file.path.Replace('\','/')
   if($normalized -match '/src/main/.*penalty/' -or $normalized.EndsWith('/ProductEnforcementApplication.java')) {
    if((Get-FileHash -LiteralPath (Join-Path $workspace $file.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256){throw 'Production source changed; cannot reuse actual runtime baseline'}
    $verifiedSources++
   }
  }
  if($verifiedSources -lt 10){throw 'Incomplete production source baseline; continuation refused'}
  & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
  $env:ENF_PENALTY_RUN_ID=$RunId
  $env:ENF_PENALTY_RESUME_DRAFT='true'
 } else {
  & (Join-Path $workspace 'scripts/verify-enforcement-penalty-store.ps1') -RunId $RunId
  Remove-Item Env:ENF_PENALTY_RESUME_DRAFT -ErrorAction SilentlyContinue
 }
 $evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw|ConvertFrom-Json
 if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit controlled technical identity required'}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
 $env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
 $env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Read-PenaltySecret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312'
 $env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC'
 $env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Read-PenaltySecret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
 $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
 $env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RULE_ID="ENF15-$(if($ExistingRuleRunId){$ExistingRuleRunId}else{$RunId})"
 $env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $evidence 'product-input-and-grants.json'
 Invoke-PenaltyPhase 'cn.xinji.agentplatform.security.product.EnforcementPenaltyGrantIT' "$RunId-product-preparation"
 $env:ENF_PENALTY_PRODUCT_FILE_ROOT=Join-Path $vault "penalty-product/$(if($ExistingRuleRunId){$ExistingRuleRunId}else{$RunId})"
 if(-not $ExistingRuleRunId) {
  if(Test-Path -LiteralPath $env:ENF_PENALTY_PRODUCT_FILE_ROOT){throw 'New product original root required'}
  $env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$env:ENF_PENALTY_PRODUCT_FILE_ROOT
  & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort 5451 -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -BuildRoot $env:ENF_MAVEN_BUILD_ROOT
 } elseif(-not(Test-Path -LiteralPath $env:ENF_PENALTY_PRODUCT_FILE_ROOT)){throw 'Existing immutable product originals unavailable'}
 $ready=$false
 for($n=0;$n -lt 40;$n++) {
  $http=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' https://127.0.0.1:5451/v2/identity/me
  if($http -eq '401'){$ready=$true;break}
  Start-Sleep -Milliseconds 500
 }
 if(-not $ready){throw 'Actual enforcement HTTPS runtime unavailable'}
 $env:VITE_ENFORCEMENT_PENALTY_CLIENT_ID='enforcement-penalty-20261001'
 if(-not(Get-NetTCPConnection -State Listen -LocalPort 5178 -ErrorAction SilentlyContinue)) {
  & (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port 5178 -BackendPort 5451
 } else {
  # Reuse only this task's recorded frontend process lineage; never stop an unrelated service.
  $recorded=[int](Get-Content -LiteralPath (Join-Path $vault 'vite-5178.pid'))
  $owner=(Get-NetTCPConnection -State Listen -LocalPort 5178).OwningProcess|Select-Object -First 1
  $matches=$false
  for($depth=0;$depth -lt 6;$depth++) {
   if($owner -eq $recorded){$matches=$true;break}
   $process=Get-CimInstance Win32_Process -Filter "ProcessId=$owner"
   if(-not $process){break};$owner=$process.ParentProcessId
  }
  if(-not $matches){throw 'Existing frontend not owned by this verification context'}
 }
 $frontendReady=$false
 for($n=0;$n -lt 40;$n++) {
  $http=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' https://127.0.0.1:5178/app/enforcement/penalty
  if($http -eq '200'){$frontendReady=$true;break}
  Start-Sleep -Milliseconds 500
 }
 if(-not $frontendReady){throw 'Actual penalty product frontend did not become ready'}
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName
 $env:ENF_FOLLOWUP_PASSWORD=Read-PenaltySecret 'functional-a.credential.xml'
 $env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-product.mjs') *> (Join-Path $evidence 'browser-execution.log')
 if($LASTEXITCODE -ne 0){throw 'Actual penalty browser execution failed; no acceptance claim'}
 $env:ENF_PENALTY_RECONCILIATION_EVIDENCE=Join-Path $evidence 'product-db-files-events-receipts.json'
 Invoke-PenaltyPhase 'cn.xinji.agentplatform.enforcement.penalty.PenaltyBrowserReconciliationIT' "$RunId-browser-reconciliation"
 Write-Output 'Partial ENF15 product HTTP/browser/DM8 reconciliation passed; full rule/suggestion/recovery acceptance remains in progress'
}finally{
 foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
}
