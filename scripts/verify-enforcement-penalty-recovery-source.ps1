param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe unique execution identifier required'}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Fresh evidence required'}
New-Item -ItemType Directory -Path $evidence | Out-Null
try {
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault 'business-schema.credential.xml')))
 try {$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
 $env:ENF_PENALTY_ORIGINALS_BASE=$vault;$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence 'source-db-files-receipts-events.json'
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am '-Dtest=PenaltyRecoverySourceDm8IT' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'execution.log')
 $exit=$LASTEXITCODE
 $xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoverySourceDm8IT-$RunId.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence}
 if($exit -ne 0 -or -not(Test-Path -LiteralPath $xml)){throw 'Actual source inventory failed; no physical restore claim'}
 [xml]$report=Get-Content -LiteralPath $xml -Raw
 if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'One actual source I with zero skip required'}
 [ordered]@{runId=$RunId;scope='SOURCE_PRECHECK_NOT_PHYSICAL_RESTORE';exitCode=$exit;tests=1;failures=0;errors=0;skipped=0;xmlSha=(Get-FileHash -LiteralPath $xml -Algorithm SHA256).Hash.ToLowerInvariant();physicalRestoreExecuted=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'Actual full penalty source DB/files/SHA/receipt/event inventory passed; physical restore NOT_RUN'
}finally{Remove-Item Env:ENF_ACCEPTANCE_BUSINESS_PASSWORD -ErrorAction SilentlyContinue}
