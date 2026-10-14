param([Parameter(Mandatory)][string]$Configuration,[switch]$ValidateOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/single-database-final-deployment.ps1')
try {
    $config=Read-FinalDeploymentJson $Configuration
    Invoke-FinalApplications -Config $config -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12
} catch {
    Write-Error 'FINAL_APPLICATION_DEPLOYMENT_REFUSED; inspect approved references and preserved resources' -ErrorAction Continue
    exit 1
}
