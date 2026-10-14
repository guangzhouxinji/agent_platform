$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields
$null=Assert-FinalApproval $ready
$root=[IO.Path]::GetFullPath($ready.secretRoot)
if($root -cne 'C:\Users\60329\AppData\Local\AgentPlatform\single-final-20261009' -or (Get-Item -LiteralPath $root).LinkType){throw 'EXACT_MANAGED_SECRET_ROOT_REQUIRED'}
$prior=Read-FinalDeploymentJson "$e/mainline-protection-consultation45/managed-protection-secret-bindings45.json"
foreach($ref in $prior.bindings.Values){Assert-FinalFile $ref}
$name='SINGLE_DB_FINAL_NATIVE48_KEY';$path=Join-Path $root ($name+'.dpapi')
if(Test-Path -LiteralPath $path){throw 'EXISTING_NEW_KEY_REFERENCE_RECONCILE_NO_OVERWRITE'}
$alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';$chars=[char[]]::new(44)
$plain=$null;$secure=$null;$encrypted=$null;$fd=$null;$recovered=$null
try{
 do{for($i=0;$i -lt $chars.Length;$i++){$chars[$i]=$alphabet[[Security.Cryptography.RandomNumberGenerator]::GetInt32($alphabet.Length)]};$plain=-join $chars}while($plain -cnotmatch '[A-Z]' -or $plain -cnotmatch '[a-z]' -or $plain -notmatch '[0-9]')
 $null=Assert-FinalApproval $ready
 $secure=ConvertTo-SecureString -String $plain -AsPlainText -Force;$encrypted=ConvertFrom-SecureString -SecureString $secure
 $fd=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 $sealed=[Text.Encoding]::UTF8.GetBytes($encrypted);try{$fd.Write($sealed,0,$sealed.Length);$fd.Flush($true)}finally{[Array]::Clear($sealed,0,$sealed.Length)}
 $fd.Dispose();$fd=$null
 $ref=@{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
 $recovered=Read-FinalManagedSecret $ref
 if($recovered -cne $plain -or $recovered.Length -ne 44 -or $recovered -cnotmatch '[A-Z]' -or $recovered -cnotmatch '[a-z]' -or $recovered -notmatch '[0-9]' -or $recovered -cmatch '[^A-Za-z0-9]'){throw 'MANAGED_NATIVE_KEY_ROUNDTRIP_REFUSED'}
 foreach($old in $prior.bindings.Values){Assert-FinalFile $old}
 $bindings=@{SINGLE_DB_FINAL_SNAPSHOT45_KEY=$prior.bindings.SINGLE_DB_FINAL_SNAPSHOT45_KEY;SINGLE_DB_FINAL_NATIVE48_KEY=$ref}
 @{format='EXACT_MANAGED_PROTECTION_ENVIRONMENT_REFERENCE_BINDINGS/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');bindings=$bindings;secretValuesIncluded=$false;onlyTemporaryProcessEnvironmentAllowed=$true;currentUserRoundTripPassed=$true;oldReferencesUnchanged=$true;sourceApproval="$e/resource-approval01.json";generationPolicy='44 uniform cryptographic random ASCII alphanumeric characters with all three categories; no truncation or key reuse';databaseOperationPerformed=$false;sourceServicesStopped=$false}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $PSScriptRoot 'managed-protection-secret-bindings48.json')
}finally{if($fd){$fd.Dispose()};if($secure){$secure.Dispose()};[Array]::Clear($chars,0,$chars.Length);$plain=$null;$secure=$null;$encrypted=$null;$recovered=$null}
