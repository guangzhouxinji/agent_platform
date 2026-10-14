$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
if([DateTimeOffset]::Now -ge [DateTimeOffset]::Parse('2026-10-10T18:00:00+08:00')){throw 'WINDOW_CLOSED'}
$admin=(Read-FinalDeploymentJson "$e/native-parameters48/current-full-inventory48-result.json").credentialReference
$old=$env:SINGLE_DB_ADMIN_PASSWORD;$code=$null
try{$env:SINGLE_DB_ADMIN_PASSWORD=Read-FinalManagedSecret $admin
 & node scripts/inventory-single-database-lifecycle.mjs "$e/primary-migration-config07.json" "$PSScriptRoot/post-probe-full-inventory50.json" *> "$PSScriptRoot/post-probe-full-inventory50.log"
 $code=$LASTEXITCODE;if($code -ne 0){throw 'POST_PROBE_FULL_INVENTORY_FAILED'}
}finally{$env:SINGLE_DB_ADMIN_PASSWORD=$old;$old=$null;@{capturedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;scope='read-only full inventory after metadata SELECT probes'}|ConvertTo-Json|Set-Content "$PSScriptRoot/post-probe-full-inventory50-result.json"}

