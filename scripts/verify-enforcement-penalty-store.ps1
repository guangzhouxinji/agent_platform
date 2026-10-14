param([Parameter(Mandatory=$true)][string]$RunId,[switch]$FinalRegression,[string]$ProductFileRoot="")
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if($RunId -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe unique execution identifier required'}
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
$fileRoot=Join-Path $env:LOCALAPPDATA "AgentPlatform/enforcement-product/penalty-originals/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing reused evidence'}
if($ProductFileRoot){
 $fileRoot=(Resolve-Path -LiteralPath $ProductFileRoot).Path;$allowed=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product/penalty-runtime-input'))
 if(-not $FinalRegression -or -not $fileRoot.StartsWith($allowed+'\') -or -not(Test-Path -LiteralPath $fileRoot -PathType Container)){throw 'Final real regression needs verified canonical product root'}
 New-Item -ItemType Directory -Path $evidence|Out-Null
}else{if(Test-Path -LiteralPath $fileRoot){throw 'Fresh original root required'};New-Item -ItemType Directory -Path $evidence,$fileRoot|Out-Null}
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
try {
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $secure=Import-Clixml (Join-Path $vault 'business-schema.credential.xml')
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try {$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
 $env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
 $env:ENF_PENALTY_RUN_ID=$RunId
 $env:ENF_PENALTY_FILE_ROOT=$fileRoot
 $env:ENF_PENALTY_EVIDENCE=$evidence
 $env:ENF_PENALTY_MIGRATION=Join-Path $workspace 'db/dm8/V082__enforcement_v2_penalty.sql'
 $testNames='PenaltyFormulaTest,PenaltyPublishChecksTest,PenaltyLifecycleTest,PenaltyStoreDm8IT';$expectedReports=4;$expectedTests=27
 if($FinalRegression){$testNames+=',PenaltyAmountSourceTest';$expectedReports=5;$expectedTests=31}
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$testNames" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'execution.log')
 $exit=$LASTEXITCODE
 $reports=@(Get-ChildItem -LiteralPath (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend/enforcement/target/surefire-reports') -Filter "TEST-*-$RunId.xml" -ErrorAction SilentlyContinue)
 $reports+=@(Get-ChildItem -LiteralPath (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend/apps/platform-api/target/surefire-reports') -Filter "TEST-*-$RunId.xml" -ErrorAction SilentlyContinue)
 $total=0
 foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination $evidence;[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;$total += [int]$xml.testsuite.tests
  if([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw 'Actual failing/skip report retained; not passed'}
 }
 if($exit -ne 0 -or $reports.Count -ne $expectedReports -or $total -ne $expectedTests){throw "Execution not complete: exit=$exit reports=$($reports.Count) tests=$total"}
 [ordered]@{runId=$RunId;scope='C_AND_REAL_DM8_STORE_I_NOT_PRODUCT_ACCEPTANCE';exitCode=$exit;tests=$total;failures=0;errors=0;skipped=0;fileRoot=$fileRoot;reports=@($reports|ForEach-Object{[ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output "C + real DM8 store I passed: tests=$total failures=0 errors=0 skipped=0; product acceptance remains pending"
}finally{Remove-Item Env:ENF_ACCEPTANCE_BUSINESS_PASSWORD -ErrorAction SilentlyContinue}
