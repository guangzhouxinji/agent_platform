param([Parameter(Mandatory)][string]$Script,[string[]]$ScriptArguments=@())
$ErrorActionPreference='Stop'
$root=Join-Path $PSScriptRoot 'resource-request-original01.json'
$r=Get-Content -LiteralPath $root -Raw|ConvertFrom-Json
$a=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'resource-approval01.json') -Raw|ConvertFrom-Json
$now=[DateTimeOffset]::UtcNow
if(-not $a.approvalGranted -or $now -lt [DateTimeOffset]$r.operationWindowBeijing.from -or $now -ge [DateTimeOffset]$r.operationWindowBeijing.until){throw 'APPROVAL_WINDOW_REQUIRED'}
$old=@{}
try {
 foreach($ref in @('SINGLE_DB_ADMIN_PASSWORD')+$r.accountSecretReferences){
  $old[$ref]=[Environment]::GetEnvironmentVariable($ref,'Process')
  $sec=ConvertTo-SecureString ((Get-Content -LiteralPath (Join-Path $r.managedCredentialRootRequested "$ref.dpapi") -Raw).Trim())
  $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try {[Environment]::SetEnvironmentVariable($ref,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr),'Process')}
  finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 }
 & node $Script @ScriptArguments
 $exit=$LASTEXITCODE
} finally {foreach($ref in $old.Keys){[Environment]::SetEnvironmentVariable($ref,$old[$ref],'Process')}}
exit $exit
