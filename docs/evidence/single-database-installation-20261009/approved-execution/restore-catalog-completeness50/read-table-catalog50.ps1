$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
$admin=(Read-FinalDeploymentJson "$e/native-parameters48/current-full-inventory48-result.json").credentialReference
$old=$env:SINGLE_DB_ADMIN_PASSWORD
try{$env:SINGLE_DB_ADMIN_PASSWORD=Read-FinalManagedSecret $admin
 & node "$PSScriptRoot/read-table-catalog50.mjs" *> "$PSScriptRoot/read-table-catalog50.log"
 if($LASTEXITCODE -ne 0){throw 'READ_CATALOG_PROBE_FAILED'}
}finally{$env:SINGLE_DB_ADMIN_PASSWORD=$old;$old=$null}


