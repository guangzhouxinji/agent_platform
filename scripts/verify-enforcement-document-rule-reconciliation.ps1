param([string]$RunId = "ENF13-RECONCILE-20260930-46",
      [string]$BrowserRunId = "ENF13-PRODUCT-20260930-45")
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
if(Test-Path -LiteralPath $evidence){throw 'Prior evidence exists'}
New-Item -ItemType Directory -Path $evidence | Out-Null
try {
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw | ConvertFrom-Json
 $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
 $browserResult=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$BrowserRunId-rule-browser/result.json"
 $result=Get-Content -LiteralPath $browserResult -Raw | ConvertFrom-Json
 if($result.passed -ne $true){throw 'Actual passed browser result required'}
 $env:ENF_DOCUMENT_RULE_FINAL_ID=$result.ruleId
 if(-not $env:ENF_DOCUMENT_RULE_FINAL_ID){throw 'Actual browser ruleId required'}
 $env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates'
 $env:ENF_DOCUMENT_RULE_FINAL_CATALOG=Join-Path $workspace 'data/enforcement/document-rule-v2-product-input-v1/catalog.json'
 $env:ENF_DOCUMENT_RULE_BROWSER_RESULT=$browserResult
 $env:ENF_DOCUMENT_RULE_FINAL_OUTPUT=Join-Path $evidence 'product-reconciliation.json'
 $env:ENF_DOCUMENT_RULE_ATTEMPT_BROWSER_RESULT=$browserResult
 $env:ENF_DOCUMENT_RULE_ATTEMPT_OUTPUT=Join-Path $evidence 'attempt-reconciliation.json'
 $buildRoot=$workspace
 $pom=Join-Path $workspace 'backend/pom.xml'
 if($env:ENF_MAVEN_BUILD_ROOT){$buildRoot=$env:ENF_MAVEN_BUILD_ROOT}
 if($env:ENF_MAVEN_POM){$pom=$env:ENF_MAVEN_POM}
 & mvn -q -f $pom -pl apps/platform-api -am '-Dtest=DocumentMappingValidatorTest,DocumentRuleServiceTest,DocumentRuleRuntimeDm8Test,DocumentRuleAttemptRuntimeDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'maven.log')
 $code=$LASTEXITCODE
 $expected=@(@('enforcement','documentrule.DocumentMappingValidatorTest',6),@('enforcement','documentrule.DocumentRuleServiceTest',7),@('apps/platform-api','documentrule.DocumentRuleRuntimeDm8Test',1),@('apps/platform-api','documentrule.DocumentRuleAttemptRuntimeDm8Test',1))
 foreach($item in $expected){
  $xml=Join-Path $buildRoot "backend/$($item[0])/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.$($item[1])-$RunId.xml"
  if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence}
 }
 if($code -ne 0){Get-Content (Join-Path $evidence 'maven.log') -Tail 15;throw 'Current code C/runtime DM8 reconciliation failed'}
 foreach($xml in Get-ChildItem -LiteralPath $evidence -Filter 'TEST-*.xml'){
  [xml]$suite=Get-Content -LiteralPath $xml.FullName -Raw
  if([int]$suite.testsuite.tests -le 0 -or [int]$suite.testsuite.failures -ne 0 -or [int]$suite.testsuite.errors -ne 0 -or [int]$suite.testsuite.skipped -ne 0){throw 'Fresh zero failure/error/skip XML required'}
 }
 if((Get-ChildItem -LiteralPath $evidence -Filter 'TEST-*.xml').Count -ne 4){throw 'Four exact reports required'}
 Write-Output 'PASSED CURRENT_C=13 RUNTIME_DM8=2 FAILURES=0 ERRORS=0 SKIPPED=0'
}finally{
 foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
}
