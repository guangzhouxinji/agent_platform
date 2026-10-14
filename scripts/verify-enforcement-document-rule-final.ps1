param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$RecoveryRunId,[Parameter(Mandatory=$true)][string]$ManifestFile)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
foreach($id in @($RunId,$RecoveryRunId)){if($id -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid execution ID'}}
if(-not $env:ENF_MAVEN_POM -or -not $env:ENF_MAVEN_BUILD_ROOT){throw 'Fresh exclusive build required'}
$root=Join-Path $workspace 'docs/evidence/enforcement-followup-final-20260930'
$recovery=Get-Content (Join-Path $root "$RecoveryRunId/result.json") -Raw|ConvertFrom-Json
if(-not $recovery.physicalDbShaEqual -or -not $recovery.ledgerEqual -or $recovery.browser -ne 'PASSED'){throw 'Actual physical recovery must already have passed'}
$evidence=Join-Path $root $RunId;if(Test-Path -LiteralPath $evidence){throw 'Refusing prior evidence overwrite'};New-Item -ItemType Directory -Path $evidence|Out-Null
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
function Secret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
$env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
$env:ENF_ACCEPTANCE_SUBJECT=(Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw|ConvertFrom-Json).subjectId
$env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates'
$env:ENF_DOCUMENT_RULE_FINAL_CATALOG=Join-Path $workspace 'data/enforcement/document-rule-v2-product-input-v1/catalog.json'
$env:ENF_DOCUMENT_RULE_BROWSER_RESULT=Join-Path $root 'ENF13-ATTEMPT-20261001-57-rule-browser/result.json'
$env:ENF_DOCUMENT_RULE_FINAL_ID=(Get-Content $env:ENF_DOCUMENT_RULE_BROWSER_RESULT -Raw|ConvertFrom-Json).ruleId
$env:ENF_DOCUMENT_RULE_FINAL_OUTPUT=Join-Path $evidence 'lifecycle-reconciliation.json'
$env:ENF_DOCUMENT_RULE_ATTEMPT_BROWSER_RESULT=$env:ENF_DOCUMENT_RULE_BROWSER_RESULT;$env:ENF_DOCUMENT_RULE_ATTEMPT_OUTPUT=Join-Path $evidence 'attempt-reconciliation.json'
$env:ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST=$ManifestFile;$env:ENF_DOCUMENT_RULE_NEGATIVE_OUTPUT=Join-Path $evidence 'negatives'
$env:ENF_DOCUMENT_RULE_DEPENDENCY_RESULT=Join-Path $root 'ENF13-DEPENDENCY-UI-20261001-85-rule-browser/result.json';$env:ENF_DOCUMENT_RULE_DEPENDENCY_OUTPUT=Join-Path $evidence 'dependency-reconciliation.json'
$env:ENF_DOCUMENT_RULE_RECOVERY_BASELINE=Join-Path $root 'ENF13-RECOVERY-SEED-20261001-81-rule-browser/result.json';$env:ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'recovery-source-post-reconciliation.json'
$start=[DateTimeOffset]::UtcNow
try{
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am '-Dtest=DocumentMappingValidatorTest,DocumentRuleServiceTest,DocumentRuleRuntimeDm8Test,DocumentRuleAttemptRuntimeDm8Test,DocumentRuleFrozenNegativeRuntimeDm8Test,DocumentRuleDependencyRuntimeDm8Test,DocumentRuleRecoveryRuntimeDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'maven.log')
 $exit=$LASTEXITCODE;$reports=@()
 foreach($module in @('enforcement','apps/platform-api')){foreach($file in Get-ChildItem (Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/$module/target/surefire-reports") -Filter "*-$RunId.xml"){
  Copy-Item -LiteralPath $file.FullName -Destination $evidence
  [xml]$suite=Get-Content -LiteralPath $file.FullName -Raw
  $reports+=[ordered]@{name=$suite.testsuite.name;tests=[int]$suite.testsuite.tests;failures=[int]$suite.testsuite.failures;errors=[int]$suite.testsuite.errors;skipped=[int]$suite.testsuite.skipped;timestamp=$suite.testsuite.timestamp;path=$file.FullName;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
 }}
 [ordered]@{runId=$RunId;startedAt=$start.ToString('o');completedAt=[DateTimeOffset]::UtcNow.ToString('o');exit=$exit;reports=$reports;recoveryRunId=$RecoveryRunId;businessAcceptance='PENDING'}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $evidence 'execution.json') -Encoding utf8
 if($exit -ne 0){throw 'Final current ENF-13 validation failed; original reports retained'}
 if($reports.Count -ne 7 -or ($reports.tests|Measure-Object -Sum).Sum -ne 26){throw 'Seven exact suites / 26 actual tests required'}
 foreach($report in $reports){if($report.tests -le 0 -or $report.failures -ne 0 -or $report.errors -ne 0 -or $report.skipped -ne 0){throw 'Every suite must actually run and pass without skipping'}}
 Write-Output "PASSED $RunId TESTS=26 FAILURES=0 ERRORS=0 SKIPPED=0"
}finally{[Environment]::SetEnvironmentVariable('ENF_ACCEPTANCE_BUSINESS_PASSWORD',$null,'Process')}
