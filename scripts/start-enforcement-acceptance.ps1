param([int] $HttpsPort = 5444)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault = Join-Path $env:LOCALAPPDATA 'AgentPlatform\enforcement-product'

function Read-DeploymentSecret([string] $name) {
    $secure = Import-Clixml (Join-Path $vault $name)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$listener = Get-NetTCPConnection -State Listen -LocalPort $HttpsPort -ErrorAction SilentlyContinue
if ($listener) { throw "HTTPS port $HttpsPort is already in use" }

$classpathFile = Join-Path $env:TEMP 'enf-runtime-classpath.txt'
if (-not (Test-Path $classpathFile)) {
    & mvn -q -f (Join-Path $workspace 'backend\apps\platform-api\pom.xml') `
        dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve enforcement runtime dependencies' }
}
$moduleClasses = Get-ChildItem (Join-Path $workspace 'backend') -Directory -Recurse -Filter classes |
    Where-Object { $_.FullName -match '\\target\\classes$' } |
    Select-Object -ExpandProperty FullName
$classpath = ($moduleClasses + (Get-Content -Raw $classpathFile).Trim()) -join ';'

$retention = Join-Path $vault 'retention-v1.json'
if (-not (Test-Path $retention)) {
    [ordered]@{ policy = 'ENFORCEMENT_ACCEPTANCE'; version = 1; purpose = 'local product acceptance infrastructure' } |
        ConvertTo-Json | Set-Content -LiteralPath $retention -Encoding utf8
}
$retentionSha = (Get-FileHash $retention -Algorithm SHA256).Hash.ToLowerInvariant()

$env:SERVER_PORT = [string] $HttpsPort
$env:SPRING_DATASOURCE_URL = 'jdbc:dm://127.0.0.1:5311'
$env:SPRING_DATASOURCE_USERNAME = 'ENFACC_BIZ'
$env:SPRING_DATASOURCE_PASSWORD = Read-DeploymentSecret 'business-schema.credential.xml'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'dm.jdbc.driver.DmDriver'
$env:PRODUCT_IDENTITY_ENABLED = 'true'
$env:PRODUCT_IDENTITY_ISSUER = "https://127.0.0.1:$HttpsPort"
$env:PRODUCT_IDENTITY_AUDIENCE = "https://127.0.0.1:$HttpsPort/v2"
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
$env:ENFORCEMENT_V2_FILE_ROOT = Join-Path $vault 'files'
$env:ENFORCEMENT_PUBLIC_JOB_CANDIDATES = Join-Path $workspace 'data\enforcement\public-job-pack-candidates-v1\candidates.json'
$env:ENF_DOCUMENT_TEMPLATE_FILE_ROOT = Join-Path $vault 'document-templates'
$env:ENF_DOCUMENT_TEMPLATE_AVAILABLE_FONTS = 'SimSun'
$env:ENF_LEGAL_REFERENCE_CATALOG_FILE = Join-Path $workspace 'data\enforcement\legal-reference-v2-product-input-v1\catalog.json'
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

try {
    $process = Start-Process -FilePath 'D:\software\jdk-21\bin\java.exe' -ArgumentList @(
        '-cp', $classpath, 'cn.xinji.agentplatform.AgentPlatformApplication', '--product-enforcement',
        '--enforcement.v2.enabled=true',
        "--enforcement.v2.file-root=$(Join-Path $vault 'files')",
        "--enforcement.v2.job-pack.public-candidates-path=$(Join-Path $workspace 'data\enforcement\public-job-pack-candidates-v1\candidates.json')",
        "--enforcement.document-template.file-root=$(Join-Path $vault 'document-templates')",
        '--enforcement.document-template.available-fonts=SimSun',
        "--enforcement.legal-reference.catalog-file=$(Join-Path $workspace 'data\enforcement\legal-reference-v2-product-input-v1\catalog.json')") `
        -WorkingDirectory $workspace -RedirectStandardOutput (Join-Path $vault 'server-out.log') `
        -RedirectStandardError (Join-Path $vault 'server-error.log') -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $vault 'server.pid') -Value $process.Id
    Write-Output "Enforcement product API PID=$($process.Id) HTTPS port=$HttpsPort"
} finally {
    Get-ChildItem Env:PRODUCT_*, Env:SPRING_DATASOURCE_*, Env:SERVER_SSL_*, Env:ENFORCEMENT_* |
        Remove-Item
    Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue
}
