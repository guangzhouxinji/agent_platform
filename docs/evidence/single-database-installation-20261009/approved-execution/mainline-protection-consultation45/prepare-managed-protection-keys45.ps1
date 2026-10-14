$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields
$null=Assert-FinalApproval $ready
$root=[IO.Path]::GetFullPath($ready.secretRoot)
if($root -cne 'C:\Users\60329\AppData\Local\AgentPlatform\single-final-20261009' -or (Get-Item -LiteralPath $root).LinkType){throw 'EXACT_MANAGED_SECRET_ROOT_REQUIRED'}
$names=@('SINGLE_DB_FINAL_SNAPSHOT45_KEY','SINGLE_DB_FINAL_NATIVE45_KEY')
foreach($name in $names){if(Test-Path -LiteralPath (Join-Path $root ($name+'.dpapi'))){throw 'EXISTING_NEW_KEY_REFERENCE_RECONCILE_NO_OVERWRITE'}}
$references=@()
foreach($name in $names){
 $null=Assert-FinalApproval $ready
 $bytes=[Security.Cryptography.RandomNumberGenerator]::GetBytes(32);$plain=$null;$secure=$null;$encrypted=$null;$fd=$null
 try{
  $plain=if($name -eq $names[0]){[Convert]::ToBase64String($bytes)}else{[Convert]::ToHexString($bytes).ToLowerInvariant()}
  $secure=ConvertTo-SecureString -String $plain -AsPlainText -Force
  $encrypted=ConvertFrom-SecureString -SecureString $secure
  $p=Join-Path $root ($name+'.dpapi');$fd=[IO.File]::Open($p,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
  $sealed=[Text.Encoding]::UTF8.GetBytes($encrypted);try{$fd.Write($sealed,0,$sealed.Length);$fd.Flush($true)}finally{[Array]::Clear($sealed,0,$sealed.Length)}
  $fd.Dispose();$fd=$null
  $references+=@{environmentReference=$name;path=$p;sha256=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant();keyKind=if($name -eq $names[0]){'AES_256_GCM_WRAPPER'}else{'DM8_NATIVE_EXPORT_ENCRYPTION'};freshlyGenerated=$true;existingTLSOrMasterKeyCopied=$false;rawKeySavedToWorkspace=$false}
 }finally{if($fd){$fd.Dispose()};[Array]::Clear($bytes,0,$bytes.Length);$plain=$null;$secure=$null;$encrypted=$null}
}
@{format='MANAGED_FINAL_SINGLE_DATABASE_PROTECTION_KEY_REFERENCES/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');sourceApproval="$e/resource-approval01.json";taskId='PREP-SINGLE-DB-FINAL-PROTECTION-01';references=$references;databaseOperationPerformed=$false;sourceServicesStopped=$false;keysUsedForExportOrImport=$false;newKeyStorage='existing approved current-user DPAPI root, inherited access policy; references only in repository'}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $PSScriptRoot 'managed-protection-key-references45.json')