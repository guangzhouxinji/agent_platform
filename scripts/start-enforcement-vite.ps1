param([int] $Port = 5174, [int] $BackendPort = 5444, [string] $ProductClientId = "")

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault = Join-Path $env:LOCALAPPDATA 'AgentPlatform\enforcement-product'

function Read-DeploymentSecret([string] $name) {
    $secure = Import-Clixml (Join-Path $vault $name)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Vite port $Port is already in use"
}
$env:VITE_DEV_HTTPS_PFX = Join-Path $vault 'server.p12'
$env:VITE_DEV_HTTPS_PFX_PASSWORD = Read-DeploymentSecret 'certificate-store.credential.xml'
$env:VITE_V2_PROXY_TARGET = "https://127.0.0.1:$BackendPort"
$env:VITE_V2_PROXY_SECURE = 'false'
$env:VITE_ENFORCEMENT_PRODUCT_CLIENT_ID = if ($ProductClientId) { $ProductClientId } else { (Get-Content (Join-Path $vault 'product-client-id.txt') -Raw).Trim() }
$logPrefix = if ($Port -eq 5174) { 'vite' } else { "vite-$Port" }
$env:VITE_ENFORCEMENT_DOCUMENT_CLIENT_ID = 'enforcement-document-template-20260929'
$env:VITE_ENFORCEMENT_LEGAL_CLIENT_ID = 'enforcement-legal-reference-20260929'
$env:VITE_ENFORCEMENT_DOCUMENT_RULE_CLIENT_ID = 'enforcement-document-rule-20260930'
try {
    $process = Start-Process -FilePath 'D:\software\nodejs\npm.cmd' -ArgumentList @(
        'run', 'dev', '--', '--host', '127.0.0.1', '--port', [string] $Port) `
        -WorkingDirectory (Join-Path $workspace 'frontend') `
        -RedirectStandardOutput (Join-Path $vault "$logPrefix-out.log") `
        -RedirectStandardError (Join-Path $vault "$logPrefix-error.log") -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $vault "$logPrefix.pid") -Value $process.Id
    Write-Output "Enforcement Vite PID=$($process.Id) HTTPS port=$Port"
} finally {
    Get-ChildItem Env:VITE_* | Remove-Item
}
