$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'single-database-final-deployment.ps1')
$reference=@{path='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer';sha256='ab658c0492f34775181a5a97cc46fd2ecf04bcf1ecbe08f073124fadd0eeb9f0'}
try {
    $physical=Get-FinalPhysicalWindowsReference $reference 'C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009'
    @{format='ACTR82_PUBLIC_CA_REFERENCE/v1';logical=$reference.path;path=$physical;sha256=$reference.sha256}|ConvertTo-Json -Compress
} catch {
    [Console]::Error.Write('RECONCILIATION_PUBLIC_CA_BINDING_UNAVAILABLE')
    exit 1
}
