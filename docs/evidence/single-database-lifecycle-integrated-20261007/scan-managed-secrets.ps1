$ErrorActionPreference='Stop'
$secrets=[Collections.Generic.HashSet[string]]::new()
$vaultPaths=@(Get-ChildItem -LiteralPath "$PSScriptRoot/.private-vault" -Filter '*.dpapi.xml' -File)+@(Get-ChildItem -LiteralPath "$PSScriptRoot/../single-database-persistent-20261007/.private-vault" -Filter '*.dpapi.xml' -File)
foreach($file in $vaultPaths){$vault=Import-Clixml -LiteralPath $file.FullName;foreach($v in $vault.Values){$secure=if($v -is [string]){ConvertTo-SecureString $v}else{$v};$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[void]$secrets.Add([Runtime.InteropServices.Marshal]::PtrToStringBSTR($p))}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}}
$matches=@();$count=0
foreach($file in Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse){if($file.FullName.Contains('\.private-vault\') -or $file.Extension -notin @('.json','.log','.txt','.md','.mjs','.java','.ps1')){continue};$text=[IO.File]::ReadAllText($file.FullName);$count++;foreach($secret in $secrets){if($secret.Length -gt 0 -and $text.Contains($secret)){$matches+=@{file=$file.FullName;secretValueDisclosed=$false};break}}}
@{filesScanned=$count;managedSecretValuesChecked=$secrets.Count;leaks=$matches;passed=($matches.Count -eq 0);encryptedRawInspectOriginalsRetained=$true;packageBodiesOpened=$false}|ConvertTo-Json -Depth 6|Set-Content -Encoding utf8NoBOM "$PSScriptRoot/managed-secret-scan.json"
if($matches.Count){throw 'Managed secret found in published evidence. Do not deliver.'}
