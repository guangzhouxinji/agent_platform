param([string]$RunId = "ENF-FOLLOWUP-DM8-20260930-34")
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
try {
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw | ConvertFrom-Json
 $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
 $env:ENF_LEGAL_L06_REFERENCE_ID='cd258224-21d7-4802-b061-0f09b0fbcffa'
 $env:ENF_LEGAL_L06_CATALOG=Join-Path $workspace 'docs/evidence/enforcement-followup-final-20260930/ENF17-L06-20260930-30/catalog-after.json'
 $env:ENF_LEGAL_L06_ORIGINAL=Join-Path $workspace 'data/shared/source-review/cnipa-rules-documents-v1/raw/patent-implementing-rules-2023.html'
 $env:ENF_LEGAL_L06_HISTORICAL_ORIGINAL=Join-Path $workspace 'docs/evidence/enforcement-followup-final-20260930/ENF17-L06-20260930-30/cnipa-rules-2010.html'
 $env:ENF_LEGAL_L06_OUTPUT=Join-Path $evidence 'l06-reconciliation.json'
 $env:ENF_DOCUMENT_RULE_STORE_EVIDENCE=Join-Path $evidence 'document-rule-store-reconciliation.json'
 & mvn -q -f (Join-Path $workspace 'backend/pom.xml') -pl apps/platform-api -am '-Dtest=LegalReferenceSourceUpdateDm8Test,DocumentRuleStoreDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test
 if($LASTEXITCODE -ne 0){throw 'Real DM8 validation failed'}
 foreach($entry in @('enforcement.legalreference.LegalReferenceSourceUpdateDm8Test','enforcement.documentrule.DocumentRuleStoreDm8Test')) {
  $xml=Join-Path $workspace "backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.$entry-$RunId.xml"
  [xml]$report=Get-Content -LiteralPath $xml -Raw
  if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'Fresh zero-skip XML required'}
  Copy-Item -LiteralPath $xml -Destination $evidence
 }
}finally{
 foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
}

