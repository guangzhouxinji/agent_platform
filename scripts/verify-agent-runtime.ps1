[CmdletBinding()]
param(
 [string]$RunId=('agent-runtime-'+[Guid]::NewGuid().ToString('N').Substring(0,12)),
 [switch]$Dm8,
 [string]$JavaHome='D:/software/jdk-21'
)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($RunId -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe unique RunId required'}
$evidence=Join-Path $workspace "docs/evidence/agent-runtime-20261005/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Never overwrite prior verification evidence'}
if($Dm8){foreach($name in @('AGENT_DM8_URL','AGENT_DM8_ADMIN_USER','AGENT_DM8_ADMIN_PASSWORD','ADJ_DM8_PRIMARY_URL','ADJ_DM8_AUTHORITY_URL','ADJ_DM8_ADMIN_USER','ADJ_DM8_ADMIN_PASSWORD','ADJ_DM8_AUTHORITY_ADMIN_USER','ADJ_DM8_AUTHORITY_ADMIN_PASSWORD')){if(-not [Environment]::GetEnvironmentVariable($name,'Process')){throw "Missing $name; no implicit identity or database fallback"}}}
New-Item -ItemType Directory -Path $evidence | Out-Null
$previousJava=$env:JAVA_HOME;$previousPath=$env:PATH
Push-Location $workspace
try {
 $env:JAVA_HOME=$JavaHome;$env:PATH="$JavaHome/bin;$env:PATH"
 & node scripts/verify-all.mjs *> (Join-Path $evidence 'verify-before.log');$before=$LASTEXITCODE
 # Requirement drift is an immediate stop. Existing test-health red is retained, never treated as green.
 if($before -ne 0){$pre=Get-Content (Join-Path $evidence 'verify-before.log') -Raw;if($pre -notmatch 'Governance verification passed: \d+ baseline files and \d+ requirement ledger rows' -or $pre -notmatch 'TESTS_FAILING|TEST_EVIDENCE_STALE'){throw 'Unexpected baseline or governance failure; stop before implementation verification'}}
 $tests='AgentRuntimeTest,AgentCheckpointPersistenceTest,AgentModelGatewayTest,AdjudicationAgentTaskHttpTest,DeepSeekPatentAnalysisAdapterTest,ExternalModelIntentAdvisorTest,OpenAiConsultationAnswerAdapterTest,MaterialOrganizationHttpTest,MaterialDecisionHttpTest'
 if($Dm8){$tests+=',AgentCheckpointDm8Verification,AdjudicationAgentTaskDm8Verification'}
 & mvn -f backend/pom.xml -pl apps/platform-api -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId" test *> (Join-Path $evidence 'maven.log');$backend=$LASTEXITCODE
 $reports=Get-ChildItem backend -Recurse -File -Filter "TEST-*-$RunId.xml" | Where-Object {$_.Directory.Name -eq 'surefire-reports'}
 $missingReports=($reports.Count -ne ($tests -split ",").Count)
 $summary=@();foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $evidence $report.Name);[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;$summary+=@{name=$xml.testsuite.name;tests=[int]$xml.testsuite.tests;failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped}}
 & npm --prefix frontend test *> (Join-Path $evidence 'frontend-tests.log');$frontend=$LASTEXITCODE
 & npm --prefix frontend run build *> (Join-Path $evidence 'frontend-build.log');$build=$LASTEXITCODE
 & node scripts/verify-all.mjs *> (Join-Path $evidence 'verify-after.log');$after=$LASTEXITCODE
 @{runId=$RunId;scope='ENGINEERING_VERIFICATION_NOT_PROFESSIONAL_OR_HUMAN_ACCEPTANCE';dm8=[bool]$Dm8;verifyBefore=$before;backend=$backend;frontendTests=$frontend;frontendBuild=$build;verifyAfter=$after;missingReports=$missingReports;reports=$summary} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $evidence 'result.json') -Encoding utf8NoBOM
 if($missingReports -or $backend -ne 0 -or $frontend -ne 0 -or $build -ne 0 -or $after -ne 0){throw "Verification not fully green; preserve all evidence at $evidence"}
 Write-Output "Scoped verification passed: $evidence (not full-repository or professional acceptance)"
}finally{Pop-Location;$env:JAVA_HOME=$previousJava;$env:PATH=$previousPath}
