param([string]$RunId = "ENF13-PRODUCT-20260930-36",[ValidateSet('template','mapping')][string]$StartStage='template',[string]$TemplateRunId='',
      [ValidateSet('lifecycle','recovery_seed','unknown_path','wrong_type','missing_party','missing_date','missing_material','no_interview','unconfirmed_amount','unconfirmed_legal','dependency','unreadable_source')][string]$Scenario='lifecycle',
      [string]$InputCatalogFile='')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault = Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
function Read-EnfTargetSecret([string]$name) {
    $secure = Import-Clixml (Join-Path $vault $name)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
$env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Read-EnfTargetSecret 'business-schema.credential.xml'
$env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312'
$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC'
$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Read-EnfTargetSecret 'authority-service.credential.xml'
$env:ENF_ACCEPTANCE_AUTHORITY_SCHEMA='ENFACC_AUTH'
$env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing prior evidence overwrite'}
New-Item -ItemType Directory -Path $evidence | Out-Null
function Invoke-Enf13Prep([string]$test,[string]$suffix) {
 & mvn -q -f $(if($env:ENF_MAVEN_POM){$env:ENF_MAVEN_POM}else{Join-Path $workspace 'backend/pom.xml'}) -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE
 $xml=Join-Path $(if($env:ENF_MAVEN_BUILD_ROOT){$env:ENF_MAVEN_BUILD_ROOT}else{$workspace}) "backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.$test-$suffix.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence}
 if($exit -ne 0){throw "$test preparation failed; original report/log retained"}
 [xml]$doc=Get-Content -LiteralPath $xml -Raw
 if([int]$doc.testsuite.tests -ne 1 -or [int]$doc.testsuite.failures -ne 0 -or [int]$doc.testsuite.errors -ne 0 -or [int]$doc.testsuite.skipped -ne 0){throw 'Zero skip preparation XML required'}
}
try {
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw | ConvertFrom-Json
 if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Controlled technical identity required'}
 $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
 $env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $inputRun=if($TemplateRunId){$TemplateRunId}else{$RunId}
 $env:ENF_ACCEPTANCE_TEMPLATE_ID="ENF13-TEMPLATE-$inputRun"
 $env:ENF_DOCUMENT_RULE_TEMPLATE_RESULT=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$inputRun-template-browser/result.json"
 $env:ENF_DOCUMENT_RULE_ID="ENF13-RULE-$RunId"
 if(-not $InputCatalogFile){$InputCatalogFile=Join-Path $workspace 'data/enforcement/document-rule-v2-product-input-v1/catalog.json'}
 $inputCatalog=Get-Content -LiteralPath $InputCatalogFile -Raw|ConvertFrom-Json
 $env:ENF_DOCUMENT_RULE_SOURCE_ID=$inputCatalog.schemas[0].sourceId
 $env:ENF_DOCUMENT_RULE_SOURCE_REVISION=[string]$inputCatalog.schemas[0].sourceRevision
 $env:ENF_DOCUMENT_RULE_CATALOG=$InputCatalogFile
 $env:ENF_DOCUMENT_RULE_SCENARIO=$Scenario
 $env:ENF_DOCUMENT_RULE_MIGRATION_FILE=Join-Path $workspace 'db/dm8/V081__enforcement_document_rule_attempt.sql'
 Invoke-Enf13Prep 'EnforcementDocumentRuleFacilityFixtureTest' "$RunId-attempt-migration"
 Invoke-Enf13Prep 'EnforcementFollowupAcceptanceGrantFixtureTest' "$RunId-template-grants"
 $runtimePid=[int](Get-Content (Join-Path $vault 'server.pid'))
 $listener=Get-NetTCPConnection -State Listen -LocalPort 5444 -ErrorAction SilentlyContinue
 if($listener){
  $runtime=Get-Process -Id $runtimePid
  if($listener.OwningProcess -notcontains $runtimePid -or $runtime.ProcessName -ne 'java'){throw 'Runtime ownership mismatch'}
  Stop-Process -Id $runtimePid
  if(-not $runtime.WaitForExit(15000)){throw 'Owned enforcement runtime did not stop'}
  for($n=0;$n -lt 30;$n++){
   if(-not(Get-NetTCPConnection -State Listen -LocalPort 5444 -ErrorAction SilentlyContinue)){break}
   Start-Sleep -Milliseconds 200
  }
  if(Get-NetTCPConnection -State Listen -LocalPort 5444 -ErrorAction SilentlyContinue){throw 'Enforcement HTTPS port not released'}
 }
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -DocumentRuleCatalogFile $InputCatalogFile -ProcedureSourceFile (Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/target-procedure-input.json') -ProcedureApprovedBasis 'PROJECT-METHOD-ENF17|1|TECHNICAL_TEST_ONLY|PROJECT_TEST_NOT_LEGAL|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_REVIEW' -BuildRoot $env:ENF_MAVEN_BUILD_ROOT
 $healthy=$false
 for($attempt=0;$attempt -lt 60;$attempt++) {
  try{ $status=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' https://127.0.0.1:5444/v2/identity/me; if($status -eq '401'){$healthy=$true;break} }catch{}
  Start-Sleep -Seconds 1
 }
 if(-not $healthy){throw 'Enforcement runtime not ready'}
 $env:ENF_FOLLOWUP_LOGIN=$identity.loginName
 $env:ENF_FOLLOWUP_SUBJECT=$identity.subjectId
 $env:ENF_FOLLOWUP_PASSWORD=Read-EnfTargetSecret 'functional-a.credential.xml'
 $env:ENF_FOLLOWUP_TEMPLATE_ID=$env:ENF_ACCEPTANCE_TEMPLATE_ID
 $env:ENF_FOLLOWUP_RUN_ID="$RunId-template-browser"
 $env:ENF_FOLLOWUP_OUTPUT_ROOT=Split-Path -Parent $evidence
 if($StartStage -eq 'template'){
  if(-not(Get-NetTCPConnection -State Listen -LocalPort 5174 -ErrorAction SilentlyContinue)){& (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port 5174 -BackendPort 5444}
  $templateFrontendReady=$false
  for($n=0;$n -lt 30;$n++){
   $status=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' https://127.0.0.1:5174/app/enforcement/document-templates
   if($status -eq '200'){$templateFrontendReady=$true;break}
   Start-Sleep -Seconds 1
  }
  if(-not $templateFrontendReady){throw 'Product template input frontend unavailable'}
  Push-Location (Join-Path $workspace 'frontend')
  try{& node e2e/enforcement-document-rule-template-input.mjs;if($LASTEXITCODE -ne 0){throw 'Actual template input browser failed'}}finally{Pop-Location}
 }else{
  $record=Get-Content -LiteralPath $env:ENF_DOCUMENT_RULE_TEMPLATE_RESULT -Raw | ConvertFrom-Json
  if($record.templateId -ne $env:ENF_ACCEPTANCE_TEMPLATE_ID -or $record.state -ne 'PUBLISHED' -or $record.requests.Where({$_.status -ge 400}).Count -gt 0){throw 'Prior successful product template evidence required'}
 }
 $env:ENF_DOCUMENT_RULE_REVISION='1'
 $env:ENF_DOCUMENT_RULE_PREP_OUTPUT=Join-Path $evidence 'mapping-input.json'
 Invoke-Enf13Prep 'EnforcementDocumentRuleGrantFixtureTest' "$RunId-rule-grants"
 if(-not (Get-NetTCPConnection -State Listen -LocalPort 5176 -ErrorAction SilentlyContinue)){& (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port 5176 -BackendPort 5444}
 $frontendReady=$false
 for($attempt=0;$attempt -lt 30;$attempt++){
  $status=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' https://127.0.0.1:5176/app/enforcement/document-rules
  if($status -eq '200'){$frontendReady=$true;break}
  Start-Sleep -Seconds 1
 }
 if(-not $frontendReady){throw 'Product frontend not ready'}
 $env:ENF_DOCUMENT_RULE_BROWSER_RUN="$RunId-rule-browser"
 $env:ENF_DOCUMENT_RULE_SCENARIO=$Scenario
 $env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates'
 Push-Location (Join-Path $workspace 'frontend')
 try{& node e2e/enforcement-document-rule-v2-product.mjs;if($LASTEXITCODE -ne 0){throw 'Actual mapping browser failed'}}finally{Pop-Location}
}finally{
 foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
}
