$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force "$PSScriptRoot/.private-vault"|Out-Null
foreach($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.json' -File){
 $body=Get-Content -Raw -LiteralPath $file.FullName
 try{$objects=ConvertFrom-Json -InputObject $body}catch{continue}
 $changed=$false
 foreach($container in @($objects)) {
  if($container.Config -and $container.Config.Env -and @($container.Config.Env|Where-Object {-not $_.EndsWith('=[REDACTED_CONFIG_ENV_VALUE]')}).Count -gt 0){$container.Config.Env=@($container.Config.Env|ForEach-Object {($_ -split '=',2)[0]+'=[REDACTED_CONFIG_ENV_VALUE]'});$changed=$true}
 }
 if($changed) {
  $bytes=[IO.File]::ReadAllBytes($file.FullName)
  $sealed=[Security.Cryptography.ProtectedData]::Protect($bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
  $archive="$PSScriptRoot/.private-vault/$($file.Name).raw.dpapi"
  if(Test-Path -LiteralPath $archive){throw 'Preserve protected raw original; do not overwrite'}
  [IO.File]::WriteAllBytes($archive,$sealed)
  ConvertTo-Json -InputObject $objects -Depth 100|Set-Content -Encoding utf8NoBOM -LiteralPath $file.FullName
 }
}
& icacls "$PSScriptRoot/.private-vault" /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" 'SYSTEM:(OI)(CI)F' > "$PSScriptRoot/raw-vault-acl.log"
