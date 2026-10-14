$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'single-database-final-deployment.ps1')
$reference=@{path='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/SINGLE_DB_ADMIN_PASSWORD.dpapi';sha256='ef0e6ed0881f2d5efb2a6af1446b248155d93b84060a406c4e483453e3421eb4'}
try {
    $value=Read-FinalManagedSecret $reference
    [Console]::Out.Write($value)
} catch {
    [Console]::Error.Write('RECONCILIATION_MANAGED_SECRET_UNAVAILABLE')
    exit 1
} finally {$value=$null}
