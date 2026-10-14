param([string]$RunId = "ENF17-L06-20260930-30-execution")
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
    $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw | ConvertFrom-Json
    if ($identity.testNature -ne 'FUNCTIONAL_VALIDATION') { throw 'Technical identity required' }
    $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
    $env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
    Remove-Item Env:ENF_LEGAL_PREP_MODE -ErrorAction SilentlyContinue
    $env:ENF_LEGAL_PREP_TARGET_TYPE='PROCEDURE_REPORT'
    $env:ENF_LEGAL_PREP_SOURCE_ID='CNIPA_PATENT_IMPLEMENTING_RULES'
    $env:ENF_LEGAL_L06_AFTER=Join-Path $workspace 'docs/evidence/enforcement-followup-final-20260930/ENF17-L06-20260930-30/catalog-after.json'
    $env:ENF_LEGAL_L06_RUNTIME=Join-Path $env:TEMP 'enf17-l06-30-catalog.json'
    $env:ENF_LEGAL_PREP_OUTPUT=Join-Path $evidence 'target-input.json'
    & mvn -q -f (Join-Path $workspace 'backend/pom.xml') -pl apps/platform-api -am '-Dtest=EnforcementLegalAcceptanceGrantFixtureTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$RunId-input" test
    if ($LASTEXITCODE -ne 0) { throw 'Legal source/target preparation failed' }
    $xml=Join-Path $workspace "backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.EnforcementLegalAcceptanceGrantFixtureTest-$RunId-input.xml"
    Copy-Item -LiteralPath $xml -Destination $evidence
    $env:ENF_FOLLOWUP_LOGIN=$identity.loginName
    $env:ENF_FOLLOWUP_SUBJECT=$identity.subjectId
    $env:ENF_FOLLOWUP_PASSWORD=Read-EnfTargetSecret 'functional-a.credential.xml'
    $env:ENF_LEGAL_BROWSER_RUN_ID="$RunId-browser"
    Push-Location (Join-Path $workspace 'frontend')
    try { & node e2e/enforcement-legal-source-update-product.mjs; if ($LASTEXITCODE -ne 0) { throw 'Legal browser execution failed' } }
    finally { Pop-Location }
} finally {
    foreach ($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name,$null,'Process')
    }
}

