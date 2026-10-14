param([string]$RunId = "ENF17-TARGET-20260930-17", [ValidateSet('draft','publish','report')][string]$StartStage = 'draft')
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
$env:ENF_LEGAL_PREP_MODE='PROCEDURE_INPUT'
$evidence = Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if (Test-Path -LiteralPath $evidence) { throw 'Refusing to overwrite prior execution evidence' }
New-Item -ItemType Directory -Path $evidence | Out-Null
try {
    $start = $false
    foreach ($step in @(@('draft','a','1'),@('publish','b','1'),@('report','a','2'))) {
        if ($step[0] -eq $StartStage) { $start = $true }
        if (-not $start) { continue }
        $identity=Get-Content (Join-Path $vault "functional-$($step[1]).identity.json") -Raw | ConvertFrom-Json
        if ($identity.testNature -ne 'FUNCTIONAL_VALIDATION') { throw 'Explicit technical identity required' }
        $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
        $env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
        $env:ENF_LEGAL_PREP_RULE_VERSION=$step[2]
        $env:ENF_LEGAL_PREP_OUTPUT=Join-Path $evidence "$($step[0])-input.json"
        $suffix="$RunId-$($step[0])-grants"
        & mvn -q -f (Join-Path $workspace 'backend/pom.xml') -pl apps/platform-api -am '-Dtest=EnforcementLegalAcceptanceGrantFixtureTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test
        if ($LASTEXITCODE -ne 0) { throw "Grant preparation failed: $($step[0])" }
        $xml=Join-Path $workspace "backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.EnforcementLegalAcceptanceGrantFixtureTest-$suffix.xml"
        Copy-Item -LiteralPath $xml -Destination $evidence
        [xml]$report=Get-Content -LiteralPath $xml
        if ([int]$report.testsuite.tests -lt 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0) { throw 'Non-passing or skipped preparation XML' }
        $env:ENF_FOLLOWUP_LOGIN=$identity.loginName
        $env:ENF_FOLLOWUP_SUBJECT=$identity.subjectId
        $env:ENF_FOLLOWUP_PASSWORD=Read-EnfTargetSecret "functional-$($step[1]).credential.xml"
        $env:ENF_TARGET_STAGE=$step[0]
        $env:ENF_TARGET_RUN_ID="$RunId-$($step[0])-browser"
        Push-Location (Join-Path $workspace 'frontend')
        try { & node e2e/enforcement-legal-target-product.mjs; if ($LASTEXITCODE -ne 0) { throw "Browser stage failed: $($step[0])" } }
        finally { Pop-Location }
    }
} finally {
    foreach ($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name,$null,'Process')
    }
}
