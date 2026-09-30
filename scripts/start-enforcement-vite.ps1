param([int] $Port = 5174, [int] $BackendPort = 5444)

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
$env:VITE_ENFORCEMENT_PRODUCT_CLIENT_ID = (Get-Content (Join-Path $vault 'product-client-id.txt') -Raw).Trim()
$env:VITE_ENFORCEMENT_DOCUMENT_CLIENT_ID = 'enforcement-document-template-20260929'
$env:VITE_ENFORCEMENT_LEGAL_CLIENT_ID = 'enforcement-legal-reference-20260929'
try {
    $process = Start-Process -FilePath 'D:\software\nodejs\npm.cmd' -ArgumentList @(
        'run', 'dev', '--', '--host', '127.0.0.1', '--port', [string] $Port) `
        -WorkingDirectory (Join-Path $workspace 'frontend') `
        -RedirectStandardOutput (Join-Path $vault 'vite-out.log') `
        -RedirectStandardError (Join-Path $vault 'vite-error.log') -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $vault 'vite.pid') -Value $process.Id
    Write-Output "Enforcement Vite PID=$($process.Id) HTTPS port=$Port"
} finally {
    Get-ChildItem Env:VITE_* | Remove-Item
}
