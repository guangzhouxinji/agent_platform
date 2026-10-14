$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
$admin=(Read-FinalDeploymentJson "$e/native-parameters48/current-full-inventory48-result.json").credentialReference
$old=$env:SINGLE_DB_ADMIN_PASSWORD;$code=$null
try{if([DateTimeOffset]::UtcNow -ge [DateTimeOffset]::Parse('2026-10-10T10:00:00Z')){throw 'WINDOW_CLOSED'};$env:SINGLE_DB_ADMIN_PASSWORD=Read-FinalManagedSecret $admin
 & node scripts/inventory-single-database-lifecycle.mjs "$e/restore-profile51/readonly-profile-config51.json" "$PSScriptRoot/post-source-full-profile54d.json" *> "$PSScriptRoot/post-source-full-profile54d.log"
 $code=$LASTEXITCODE;if($code -ne 0){throw 'POST_SOURCE_PROFILE_FAILED'}
}finally{$env:SINGLE_DB_ADMIN_PASSWORD=$old;$old=$null;@{capturedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;credentialReference=$admin;scope='read-only post independent target stop and original source return, no writes'}|ConvertTo-Json -Depth 5|Set-Content "$PSScriptRoot/post-source-full-profile54d-result.json"}
