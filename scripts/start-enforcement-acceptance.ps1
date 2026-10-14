param([int] $HttpsPort = 5444, [int] $BusinessPort = 5311,
    [string] $TemplateFileRoot = "", [string] $IdentityIssuer = "",
    [string] $ProcedureSourceFile = "", [string] $ProcedureApprovedBasis = "", [string] $LegalCatalogFile = "", [string] $MaterialFileRoot = "", [string] $DocumentRuleCatalogFile = "", [string] $BuildRoot = "",
    [int] $CurrentDependencyBusinessPort = 0, [string] $CurrentDocumentRuleCatalogFile = "",
    [string] $ControlledReadPolicyFile = "", [string] $ControlledReadPolicySha256 = "",
    [string] $ControlledReadReleaseFile = "", [string] $ControlledReadReleaseSha256 = "")

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault = Join-Path $env:LOCALAPPDATA 'AgentPlatform\enforcement-product'
if (-not $TemplateFileRoot) { $TemplateFileRoot = Join-Path $vault 'document-templates' }
if (-not $MaterialFileRoot) { $MaterialFileRoot = Join-Path $vault 'files' }
if (-not $IdentityIssuer) { $IdentityIssuer = "https://127.0.0.1:$HttpsPort" }
if (-not $LegalCatalogFile) { $LegalCatalogFile = Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/catalog.json' }
$runtimeLogPrefix = if ($HttpsPort -eq 5444) { "server" } else { "server-$HttpsPort" }
$controlledRead = [bool]($ControlledReadPolicyFile -or $ControlledReadPolicySha256 -or
    $ControlledReadReleaseFile -or $ControlledReadReleaseSha256)
$controlledClasspath = $null
if ($controlledRead) {
    $runtimeLogPrefix = 'controlled-read-' + [Guid]::NewGuid().ToString('N')
    if ($HttpsPort -ne 5444 -or $BusinessPort -ne 5311 -or $IdentityIssuer -ne 'https://127.0.0.1:5444') {
        throw 'Controlled-read launcher is limited to the original 5444/5311 product; isolated tests require owned wiring'
    }
    if (-not $ControlledReadPolicyFile -or $ControlledReadPolicySha256 -notmatch '^[0-9a-f]{64}$' -or
        -not $ControlledReadReleaseFile -or $ControlledReadReleaseSha256 -notmatch '^[0-9a-f]{64}$' -or
        $CurrentDependencyBusinessPort -gt 0 -or $CurrentDocumentRuleCatalogFile) {
        throw 'Complete controlled-read deployment inputs required; cross-database override refused'
    }
    if (-not (Test-Path -LiteralPath $ControlledReadPolicyFile -PathType Leaf) -or
        (Get-FileHash -LiteralPath $ControlledReadPolicyFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ControlledReadPolicySha256) {
        throw 'Controlled-read policy SHA mismatch'
    }
    $policy = Get-Content -LiteralPath $ControlledReadPolicyFile -Raw | ConvertFrom-Json
    if ($policy.issuer -ne $IdentityIssuer -or [DateTimeOffset]::Parse($policy.expiresAt) -le [DateTimeOffset]::UtcNow) {
        throw 'Controlled-read issuer or expiry mismatch'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $vault 'retention-v1.json') -PathType Leaf)) {
        throw 'Controlled-read cannot manufacture retention configuration'
    }
    . (Join-Path $PSScriptRoot 'Test-EnforcementControlledReadRelease.ps1')
    $controlledClasspath = Get-EnforcementControlledReadClasspath -ManifestFile $ControlledReadReleaseFile -ManifestSha256 $ControlledReadReleaseSha256 -Workspace $workspace
}
foreach($name in @('ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_DEPENDENCY_URL','ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_DEPENDENCY_USER','ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_DEPENDENCY_PASSWORD','ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_CATALOG_FILE')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}

function Read-DeploymentSecret([string] $name) {
    $secure = Import-Clixml (Join-Path $vault $name)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
if($CurrentDependencyBusinessPort -gt 0){
    $env:ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_DEPENDENCY_URL="jdbc:dm://127.0.0.1:$CurrentDependencyBusinessPort"
    $env:ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_DEPENDENCY_USER='ENFACC_BIZ'
    $env:ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_DEPENDENCY_PASSWORD=Read-DeploymentSecret 'business-schema.credential.xml'
}
if($CurrentDocumentRuleCatalogFile){$env:ENFORCEMENT_V2_DOCUMENT_RULE_CURRENT_CATALOG_FILE=$CurrentDocumentRuleCatalogFile}

$listener = Get-NetTCPConnection -State Listen -LocalPort $HttpsPort -ErrorAction SilentlyContinue
if ($listener) { throw "HTTPS port $HttpsPort is already in use" }

$classpathFile = Join-Path $env:TEMP 'enf-runtime-classpath.txt'
if (-not $controlledRead -and -not (Test-Path $classpathFile)) {
    & mvn -q -f (Join-Path $workspace 'backend\apps\platform-api\pom.xml') `
        dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve enforcement runtime dependencies' }
}
if ($controlledRead) { $classpath = $controlledClasspath } else {
    $compiledRoot = if ($BuildRoot) { $BuildRoot } else { $workspace }
    $moduleClasses = Get-ChildItem (Join-Path $compiledRoot 'backend') -Directory -Recurse -Filter classes |
        Where-Object { $_.FullName -match '\\target\\classes$' } |
        Select-Object -ExpandProperty FullName
    $classpath = ($moduleClasses + (Get-Content -Raw $classpathFile).Trim()) -join ';'
}

$retention = Join-Path $vault 'retention-v1.json'
if (-not (Test-Path $retention)) {
    [ordered]@{ policy = 'ENFORCEMENT_ACCEPTANCE'; version = 1; purpose = 'local product acceptance infrastructure' } |
        ConvertTo-Json | Set-Content -LiteralPath $retention -Encoding utf8
}
$retentionSha = (Get-FileHash $retention -Algorithm SHA256).Hash.ToLowerInvariant()

try {
$env:SERVER_PORT = [string] $HttpsPort
$env:SPRING_DATASOURCE_URL = "jdbc:dm://127.0.0.1:$BusinessPort"
$env:SPRING_DATASOURCE_USERNAME = 'ENFACC_BIZ'
$env:SPRING_DATASOURCE_PASSWORD = Read-DeploymentSecret 'business-schema.credential.xml'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'dm.jdbc.driver.DmDriver'
$env:PRODUCT_IDENTITY_ENABLED = 'true'
$env:PRODUCT_IDENTITY_ISSUER = $IdentityIssuer
$env:PRODUCT_IDENTITY_AUDIENCE = "$IdentityIssuer/v2"
$env:PRODUCT_IDENTITY_MASTER_KEY = Read-DeploymentSecret 'identity-master.credential.xml'
$env:PRODUCT_IDENTITY_TOKEN_VERIFICATION_WINDOW = 'PT5M'
$env:PRODUCT_IDENTITY_ACTIVATION_LIFETIME = 'PT30M'
$env:PRODUCT_IDENTITY_MINIMUM_PASSWORD_LENGTH = '16'
$env:PRODUCT_IDENTITY_AUTHORITY_JDBC_URL = 'jdbc:dm://127.0.0.1:5312'
$env:PRODUCT_IDENTITY_AUTHORITY_SCHEMA = 'ENFACC_AUTH'
$env:PRODUCT_IDENTITY_AUTHORITY_USERNAME = 'ENFACC_ASVC'
$env:PRODUCT_IDENTITY_AUTHORITY_PASSWORD = Read-DeploymentSecret 'authority-service.credential.xml'
$env:PRODUCT_SOURCE_ENGINE_ENABLED = 'false'
$env:PRODUCT_SOURCE_EVIDENCE_ROOT = Join-Path $vault 'evidence'
$env:ENFORCEMENT_V2_ENABLED = 'true'
if ($ProcedureSourceFile) { $env:ENFORCEMENT_V2_PROCEDURE_SOURCE_FILE = $ProcedureSourceFile }
if ($ProcedureApprovedBasis) { $env:ENFORCEMENT_V2_PROCEDURE_APPROVED_BASIS = $ProcedureApprovedBasis }
$env:ENFORCEMENT_V2_FILE_ROOT = $MaterialFileRoot
$publicCandidates = if ($controlledRead) { '' } else { Join-Path $workspace 'data\enforcement\public-job-pack-candidates-v1\candidates.json' }
$env:ENFORCEMENT_PUBLIC_JOB_CANDIDATES = $publicCandidates
$env:ENF_DOCUMENT_TEMPLATE_FILE_ROOT = $TemplateFileRoot
$env:ENF_DOCUMENT_TEMPLATE_AVAILABLE_FONTS = 'SimSun'
$env:ENF_LEGAL_REFERENCE_CATALOG_FILE = $LegalCatalogFile
$env:ENFORCEMENT_V2_LEGAL_REFERENCE_CATALOG_FILE = $LegalCatalogFile
foreach ($prefix in @('PRODUCT_CONSULTATION_RETENTION', 'PRODUCT_CONSULTATION_GOAL_RETENTION',
        'PRODUCT_SOURCE_REGISTRY_RETENTION')) {
    [Environment]::SetEnvironmentVariable("${prefix}_REF", 'retention-v1.json', 'Process')
    [Environment]::SetEnvironmentVariable("${prefix}_VERSION", '1', 'Process')
    [Environment]::SetEnvironmentVariable("${prefix}_EVIDENCE_SHA256", $retentionSha, 'Process')
    [Environment]::SetEnvironmentVariable("${prefix}_MAX_AGE",
        $(if ($prefix -like '*GOAL*') { 'PT1H' } else { 'PT2H' }), 'Process')
}
$env:PRODUCT_CONSULTATION_GOAL_RETENTION_MAX_BYTES = '16384'
$env:SERVER_SSL_ENABLED = 'true'
$env:SERVER_SSL_KEY_STORE = ([Uri](Join-Path $vault 'server.p12')).AbsoluteUri
$env:SERVER_SSL_KEY_STORE_TYPE = 'PKCS12'
$env:SERVER_SSL_KEY_STORE_PASSWORD = Read-DeploymentSecret 'certificate-store.credential.xml'
$env:SERVER_SSL_TRUST_STORE = ([Uri](Join-Path $vault 'trust.p12')).AbsoluteUri
$env:SERVER_SSL_TRUST_STORE_TYPE = 'PKCS12'
$env:SERVER_SSL_TRUST_STORE_PASSWORD = $env:SERVER_SSL_KEY_STORE_PASSWORD
$env:SERVER_SSL_CLIENT_AUTH = 'want'

    $controlledArguments = if ($controlledRead) { @(
        '--enforcement.deployment.controlled-read.enabled=true',
        "--enforcement.deployment.controlled-read.policy-file=$ControlledReadPolicyFile",
        "--enforcement.deployment.controlled-read.policy-sha256=$ControlledReadPolicySha256",
        '--enforcement.v2.cleanup.enabled=false', '--server.address=127.0.0.1',
        '--server.forward-headers-strategy=none', '--spring.sql.init.mode=never',
        '--spring.flyway.enabled=false', '--spring.liquibase.enabled=false',
        '--spring.mvc.hiddenmethod.filter.enabled=false'
    ) } else { @() }
    $arguments = @(
        '-cp', $classpath, 'cn.xinji.agentplatform.AgentPlatformApplication', '--product-enforcement',
        '--enforcement.v2.enabled=true',
        "--enforcement.v2.file-root=$MaterialFileRoot",
        "--enforcement.v2.job-pack.public-candidates-path=$publicCandidates",
        "--enforcement.document-template.file-root=$TemplateFileRoot",
        '--enforcement.document-template.available-fonts=SimSun',
        "--enforcement.v2.legal-reference.catalog-file=$LegalCatalogFile",
        "--enforcement.v2.document-rule.catalog-file=$DocumentRuleCatalogFile") + $controlledArguments
    if ($controlledRead) {
        $arguments = @($arguments | ForEach-Object {
            if ($_.Contains('"')) { throw 'Quote in controlled deployment argument refused' }
            '"' + [regex]::Replace($_,'(\\+)$','$1$1') + '"'
        })
    }
    $process = Start-Process -FilePath 'D:\software\jdk-21\bin\java.exe' -ArgumentList $arguments `
        -WorkingDirectory $workspace -RedirectStandardOutput (Join-Path $vault "$runtimeLogPrefix-out.log") `
        -RedirectStandardError (Join-Path $vault "$runtimeLogPrefix-error.log") -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $vault "$runtimeLogPrefix.pid") -Value $process.Id
    Write-Output "Enforcement product API PID=$($process.Id) HTTPS port=$HttpsPort"
} finally {
    Get-ChildItem Env:PRODUCT_*, Env:SPRING_DATASOURCE_*, Env:SERVER_SSL_*, Env:ENFORCEMENT_* |
        Remove-Item
    Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue
}
