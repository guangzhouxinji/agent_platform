param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ManifestFile,[Parameter(Mandatory=$true)][string]$DependencyRunId)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if($RunId -notmatch '^[A-Za-z0-9_-]+$' -or $DependencyRunId -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid execution ID'}
if(-not $env:ENF_MAVEN_POM -or -not $env:ENF_MAVEN_BUILD_ROOT){throw 'Exclusive current build required'}
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing prior report overwrite'}
New-Item -ItemType Directory -Path $evidence | Out-Null
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
function Read-SafeSecret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
$env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Read-SafeSecret 'business-schema.credential.xml'
$env:ENF_DOCUMENT_RULE_TEMPLATE_ROOT=Join-Path $vault 'document-templates'
$env:ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST=$ManifestFile
$env:ENF_DOCUMENT_RULE_NEGATIVE_OUTPUT=Join-Path $evidence 'negatives'
$env:ENF_DOCUMENT_RULE_DEPENDENCY_RESULT=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$DependencyRunId-rule-browser/result.json"
$env:ENF_DOCUMENT_RULE_DEPENDENCY_OUTPUT=Join-Path $evidence 'dependency-reconciliation.json'
try{
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am '-Dtest=DocumentRuleFrozenNegativeRuntimeDm8Test,DocumentRuleDependencyRuntimeDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'maven.log')
 $exit=$LASTEXITCODE
 $reports=Get-ChildItem (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend/apps/platform-api/target/surefire-reports') -Filter "*-$RunId.xml"
 foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination $evidence}
 if($exit -ne 0){throw 'Real frozen negative/dependency reconciliation failed; raw logs/reports retained'}
 if($reports.Count -ne 2){throw 'Two fresh reports required'}
 $total=0
 foreach($report in $reports){[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;if([int]$xml.testsuite.tests -le 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Zero failure/error/skip required'};$total+=[int]$xml.testsuite.tests}
 if($total -ne 10){throw 'Ten actual frozen assertions cases required'}
 Write-Output "PASSED $RunId TESTS=10 FAILURES=0 ERRORS=0 SKIPPED=0"
}finally{[Environment]::SetEnvironmentVariable('ENF_ACCEPTANCE_BUSINESS_PASSWORD',$null,'Process')}
