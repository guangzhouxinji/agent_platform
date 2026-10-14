# USER-ONLY: Codex must not run this script or supply the password/activation.
$ErrorActionPreference='Stop'
$receipt=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'safe-delivery51.json') -Raw|ConvertFrom-Json
if([DateTimeOffset]::Now -ge [DateTimeOffset]::Parse($receipt.registeredAt).AddMinutes(10)){throw 'ONE_TIME_ACTIVATION_EXPIRED_DO_NOT_RETRY'}
if([DateTimeOffset]::Now -ge [DateTimeOffset]::Parse('2026-10-10T18:00:00+08:00')){throw 'OPERATION_WINDOW_CLOSED'}
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'activated-by-user51.json')){throw 'ACTIVATION_ALREADY_RECORDED_NO_RETRY'}
$secureResponse=ConvertTo-SecureString -String (Get-Content -LiteralPath $receipt.privateDpapiPath -Raw).Trim()
$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureResponse)
try{$registration=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)|ConvertFrom-Json}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}
$password=Read-Host '请本人设置咨询登录密码（至少16字符，不回显）' -AsSecureString
if($password.Length -lt 16){throw 'PASSWORD_MINIMUM_16'}
$passPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($password);$plain=$null;$json=$null
try{
 $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($passPtr)
 $json=@{activation=$registration.oneTimeActivation;password=$plain}|ConvertTo-Json -Compress
 $root='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/root-ca.cer'
 # Secrets only on private stdin, never argv/environment/output/files.
 $status=($json|& java -Xmx64m -cp $PSScriptRoot NormalSubjectActivation51 $root $receipt.subjectId 2>$null) -join "`n"
 if($LASTEXITCODE -ne 0 -or $status -cne 'NORMAL_SUBJECT_ACTIVATED_BY_USER_PASSWORD_NOT_RECORDED'){throw 'ACTIVATION_UNKNOWN_OR_REFUSED_NO_RETRY'}
 [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'activated-by-user51.json'),(@{activatedAt=[DateTimeOffset]::Now.ToString('o');subjectId=$receipt.subjectId;activatedByUser=$true;passwordOrActivationRecorded=$false}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
 '本人激活完成。登录名：'+$receipt.loginName+'；请正常登录咨询页面。'
}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passPtr);$plain=$null;$json=$null;$registration=$null;$password=$null;$secureResponse=$null}
