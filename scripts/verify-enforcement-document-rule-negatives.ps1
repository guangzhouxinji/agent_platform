param([string]$RunId='ENF13-R02-RECONCILE-20261001-50',
      [string]$UnknownRunId='ENF13-R02-UNKNOWN-20261001-48',
      [string]$TypeRunId='ENF13-R02-TYPE-20261001-49')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
foreach($id in @($RunId,$UnknownRunId,$TypeRunId)){if($id -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid evidence ID'}}
if(-not $env:ENF_MAVEN_POM -or -not $env:ENF_MAVEN_BUILD_ROOT){throw 'Exclusive build context required'}
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing previous evidence overwrite'}
New-Item -ItemType Directory -Path $evidence|Out-Null
$secure=Import-Clixml (Join-Path $vault 'business-schema.credential.xml')
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try{$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
try{
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
 $env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
 $env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates'
 $env:ENF_DOCUMENT_RULE_UNKNOWN_RESULT=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$UnknownRunId-rule-browser/result.json"
 $env:ENF_DOCUMENT_RULE_TYPE_RESULT=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$TypeRunId-rule-browser/result.json"
 foreach($resultFile in @($env:ENF_DOCUMENT_RULE_UNKNOWN_RESULT,$env:ENF_DOCUMENT_RULE_TYPE_RESULT)){
  $result=Get-Content -LiteralPath $resultFile -Raw|ConvertFrom-Json
  if($result.passed -ne $true -or $result.filesUnchanged -ne $true){throw 'Actual passed browser/file evidence required'}
 }
 $started=[DateTimeOffset]::UtcNow
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am '-Dtest=DocumentRuleNegativeRuntimeDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'maven.log')
 $code=$LASTEXITCODE
 $xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleNegativeRuntimeDm8Test-$RunId.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence}
 if($code -ne 0){Get-Content (Join-Path $evidence 'maven.log') -Tail 12;throw 'Actual negative DM8 reconciliation failed'}
 [xml]$suite=Get-Content -LiteralPath $xml -Raw
 if([int]$suite.testsuite.tests -ne 2 -or [int]$suite.testsuite.failures -ne 0 -or [int]$suite.testsuite.errors -ne 0 -or [int]$suite.testsuite.skipped -ne 0){throw 'Exact two zero failure/error/skip tests required'}
 if((Get-Item -LiteralPath $xml).LastWriteTimeUtc -lt $started.UtcDateTime){throw 'Old report rejected'}
 [ordered]@{runId=$RunId;scope='R02_REAL_PRODUCT_BROWSER_DM8_FILES';startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::UtcNow.ToString('o');exitCode=$code;tests=2;failures=0;errors=0;skipped=0;unknownResult=$env:ENF_DOCUMENT_RULE_UNKNOWN_RESULT;typeResult=$env:ENF_DOCUMENT_RULE_TYPE_RESULT;xmlSha256=(Get-FileHash -LiteralPath $xml -Algorithm SHA256).Hash.ToLowerInvariant();businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'PASSED R02_RUNTIME_DM8=2 FAILURES=0 ERRORS=0 SKIPPED=0'
}finally{[Environment]::SetEnvironmentVariable('ENF_ACCEPTANCE_BUSINESS_PASSWORD',$null,'Process')}
