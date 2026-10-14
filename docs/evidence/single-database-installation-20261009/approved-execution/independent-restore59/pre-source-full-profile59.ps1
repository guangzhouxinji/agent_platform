$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
$admin=(Read-FinalDeploymentJson "$e/native-parameters48/current-full-inventory48-result.json").credentialReference
$old=$env:SINGLE_DB_ADMIN_PASSWORD;$code=$null
try{& node --input-type=module -e "import {readContinuation55} from './scripts/lib/single-database-continuous-window-admission.mjs';readContinuation55(process.cwd(),'fresh-fenced-source-protection');";if($LASTEXITCODE -ne 0){throw 'VERIFIED_CONTINUATION_REQUIRED'};$env:SINGLE_DB_ADMIN_PASSWORD=Read-FinalManagedSecret $admin
 & node scripts/inventory-single-database-lifecycle.mjs "$e/restore-profile51/readonly-profile-config51.json" "$PSScriptRoot/pre-source-full-profile-inventory59.json" *> "$PSScriptRoot/pre-source-full-profile-inventory59.log"
 $code=$LASTEXITCODE;if($code -ne 0){throw 'CURRENT_FULL_PROFILE_INVENTORY_FAILED'}
}finally{$env:SINGLE_DB_ADMIN_PASSWORD=$old;$old=$null;@{capturedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;credentialReference=$admin;scope='read-only current full profile, no stop/start/export/restore/DDL/DML'}|ConvertTo-Json -Depth 5|Set-Content "$PSScriptRoot/pre-source-full-profile-inventory59-result.json"}
