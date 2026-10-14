[CmdletBinding()]
param([string]$RunId=('history-readback-'+[Guid]::NewGuid().ToString('N').Substring(0,12)))
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($RunId -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe unique run ID required'}
$evidence=Join-Path $workspace "docs/evidence/agent-runtime-20261005/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Never overwrite historical or new evidence'}
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$base=Join-Path $workspace 'docs/evidence/enforcement-followup-final-20260930'
function Secret([string]$file){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $file)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
# These packets are the exact references in the frozen ENF13-FINAL-20261001-101 script, not new receipts.
$settings=@{
 ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';
 ENF_DOCUMENT_RULE_TEMPLATE_ROOT=(Join-Path $vault 'document-templates');
 ENF_DOCUMENT_RULE_FINAL_CATALOG=(Join-Path $workspace 'data/enforcement/document-rule-v2-product-input-v1/catalog.json');
 ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST=(Join-Path $base 'ENF13-FINAL-20261001-101-input-manifest.json');
 ENF_DOCUMENT_RULE_DEPENDENCY_RESULT=(Join-Path $base 'ENF13-DEPENDENCY-UI-20261001-85-rule-browser/result.json');
 ENF_DOCUMENT_RULE_RECOVERY_BASELINE=(Join-Path $base 'ENF13-RECOVERY-SEED-20261001-81-rule-browser/result.json');
 ENF_DOCUMENT_RULE_UNKNOWN_RESULT=(Join-Path $base 'ENF13-UNKNOWN-AUDIT-20261001-98-rule-browser/result.json');
 ENF_DOCUMENT_RULE_TYPE_RESULT=(Join-Path $base 'ENF13-TYPE-AUDIT-20261001-99-rule-browser/result.json');
 ENF_DOCUMENT_RULE_ATTEMPT_BROWSER_RESULT=(Join-Path $base 'ENF13-ATTEMPT-20261001-57-rule-browser/result.json')
}
foreach($key in $settings.Keys){if($key -ne 'ENF_ACCEPTANCE_BUSINESS_URL' -and $key -ne 'ENF_ACCEPTANCE_BUSINESS_USER' -and -not(Test-Path -LiteralPath $settings[$key])){throw "Recorded dependency missing: $key"}}
New-Item -ItemType Directory -Path $evidence|Out-Null
$settings.ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
$settings.ENF_DOCUMENT_RULE_NEGATIVE_OUTPUT=Join-Path $evidence 'negatives'
$settings.ENF_DOCUMENT_RULE_DEPENDENCY_OUTPUT=Join-Path $evidence 'dependency.json'
$settings.ENF_DOCUMENT_RULE_RECOVERY_OUTPUT=Join-Path $evidence 'recovery.json'
$settings.ENF_DOCUMENT_RULE_ATTEMPT_OUTPUT=Join-Path $evidence 'attempt.json'
$prior=@{};foreach($key in $settings.Keys){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')}
$previousJava=$env:JAVA_HOME;$previousPath=$env:PATH
Push-Location $workspace
try {
 $env:JAVA_HOME='D:/software/jdk-21';$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 & mvn -f backend/pom.xml -pl apps/platform-api -am '-Dtest=DocumentRuleFrozenNegativeRuntimeDm8Test,DocumentRuleDependencyRuntimeDm8Test,DocumentRuleNegativeRuntimeDm8Test,DocumentRuleRecoveryRuntimeDm8Test,DocumentRuleAttemptRuntimeDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'maven.log');$code=$LASTEXITCODE
 $reports=Get-ChildItem backend/apps/platform-api/target/surefire-reports -File -Filter "TEST-*-$RunId.xml"
 foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $evidence $report.Name)}
 $inputs=@();foreach($key in $settings.Keys){if($key -match 'RESULT|BASELINE|MANIFEST|CATALOG' -and (Test-Path -LiteralPath $settings[$key] -PathType Leaf)){$inputs+=@{name=$key;path=$settings[$key];sha256=(Get-FileHash -LiteralPath $settings[$key] -Algorithm SHA256).Hash.ToLowerInvariant()}}}
 @{runId=$RunId;scope='READ_ONLY_ORIGINAL_FROZEN_DB_BROWSER_ARTIFACT_RECONCILIATION';mavenExit=$code;reportCount=$reports.Count;inputs=$inputs;noGrantsWritten=$true;noMigrationApplied=$true} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $evidence 'result.json') -Encoding utf8NoBOM
 if($code -ne 0 -or $reports.Count -ne 5){throw "Historical readback failed; original evidence preserved at $evidence"}
 foreach($report in $reports){[xml]$x=Get-Content -LiteralPath $report.FullName -Raw;if([int]$x.testsuite.tests -lt 1 -or [int]$x.testsuite.failures -ne 0 -or [int]$x.testsuite.errors -ne 0 -or [int]$x.testsuite.skipped -ne 0){throw 'Historical report was not fully executed'}}
 Write-Output "Read-only historical reconciliation passed: $evidence"
}finally{Pop-Location;$env:JAVA_HOME=$previousJava;$env:PATH=$previousPath;foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}}
