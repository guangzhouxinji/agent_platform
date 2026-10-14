param([Parameter(Mandatory)][string]$Configuration,[switch]$OfflineOnly,[switch]$RequireStoppedWriters)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/single-database-deployment.ps1')
if($OfflineOnly -and $RequireStoppedWriters){throw 'Offline validation cannot inspect writer state'}
try {
    $config=Get-Content -LiteralPath $Configuration -Raw|ConvertFrom-Json -AsHashtable
    $plan=Get-SingleDatabaseDeploymentPlan -Config $config
    if($OfflineOnly){@{scope='OFFLINE_CONFIG_ONLY';domains=3;schemas=6;accounts=$plan.accountCount;runtimeVerified=$false}|ConvertTo-Json}
    else {Invoke-SingleDatabaseInspection -Config $config -RequireStoppedWriters:$RequireStoppedWriters|ConvertTo-Json}
} catch {throw 'Single database preflight refused; check exact resource, domain bindings and stopped-writer evidence'}
