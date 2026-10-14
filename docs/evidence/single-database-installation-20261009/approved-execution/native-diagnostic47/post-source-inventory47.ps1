$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$a=$PSScriptRoot;$e=Split-Path -Parent $a
$run=Read-FinalDeploymentJson "$a/source-protection-execution47-result.json"
if(-not $run.fenceReleaseConfirmed -or $run.returnFailure){throw 'SOURCE_RETURN_REQUIRED_BEFORE_INVENTORY'}
$admin=(Read-FinalDeploymentJson "$a/current-full-inventory47-result.json").credentialReference
$old=$env:SINGLE_DB_ADMIN_PASSWORD;$code=$null
try{$env:SINGLE_DB_ADMIN_PASSWORD=Read-FinalManagedSecret $admin
 & node scripts/inventory-single-database-lifecycle.mjs "$e/primary-migration-config07.json" "$a/post-source-full-inventory47.json" *> "$a/post-source-full-inventory47.log";$code=$LASTEXITCODE
 if($code -ne 0){throw 'POST_SOURCE_FULL_INVENTORY_FAILED'}
}finally{$env:SINGLE_DB_ADMIN_PASSWORD=$old;$old=$null;@{capturedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;scope='read-only six-schema full inventory after source attempt and confirmed original-role return'}|ConvertTo-Json|Set-Content "$a/post-source-inventory47-result.json"}
