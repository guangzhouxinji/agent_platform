$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'; . (Join-Path $workspace 'scripts/lib/single-database-final-deployment.ps1')
$base=Split-Path -Parent $PSScriptRoot
if([DateTimeOffset]::UtcNow -ge [DateTimeOffset]::Parse('2026-10-10T18:00:00+08:00')){throw 'WINDOW_CLOSED'}
$c=Read-FinalDeploymentJson "$base/native-parameters48/source-snapshot-config48.json"
if(Test-Path -LiteralPath $c.native.maintenanceLock){throw 'ACTIVE_MAINTENANCE_NO_BUSINESS_WRITE'}
$entries=Read-FinalDeploymentJson "$base/final-entry-client23/public-client-and-binding-inputs.json"; $m=@($entries.clients|Where-Object domain -CEQ 'consultation')[0].maintenance
foreach($r in @($m.privateStoreReference,$m.credentialReference,$m.maintainerCertificate,$m.trustCertificate)){Assert-FinalFile $r}
$pre=Read-FinalDeploymentJson "$PSScriptRoot/pre-registration-full-inventory51.json"; $s=@($pre.schemas|Where-Object schema -CEQ 'CONPROD')[0]
if(-not $pre.complete -or $pre.errors.Count -or @($s.rows|Where-Object table -CEQ 'ID2_SUBJECT')[0].count -ne 0){throw 'EXACT_EMPTY_SUBJECT_SCENE_REQUIRED_NO_RETRY'}
$target=Read-FinalDeploymentJson "$base/native-parameters48/current-resource-writer-discovery48.json"
$actual=@(Invoke-FinalDocker @('inspect','9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f')|ConvertFrom-Json -AsHashtable)[0]
if(-not $actual.State.Running -or $actual.State.Restarting){throw 'FIXED_NORMAL_APPLICATION_REQUIRED'}
$jar=@($actual.Mounts|Where-Object {$_.Destination -match '\.jar$'})
if($jar.Count -ne 1 -or ((Invoke-FinalDocker @('exec',$actual.Id,'sha256sum','/app/platform-api.jar')) -split '\s+')[0] -cne '69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d'){throw 'FIXED25_JAR_REQUIRED'}
$private=Split-Path -Parent $m.credentialReference.path; $rootItem=Get-Item -LiteralPath $private; $part=$rootItem
while($null -ne $part){if($part.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'PRIVATE_ROOT_REDIRECT'};$part=$part.Parent}
$acl=Get-Acl -LiteralPath $private
if($acl.Owner -cne [Security.Principal.WindowsIdentity]::GetCurrent().Name){throw 'CURRENT_USER_OWNED_DPAPI_ROOT_REQUIRED'}
$protected=Join-Path $private 'normal-subject-20261010-51.activation.dpapi'; $marker="$PSScriptRoot/registration-attempt51.json"
if((Test-Path -LiteralPath $protected) -or (Test-Path -LiteralPath $marker)){throw 'EXISTING_OR_UNKNOWN_REGISTRATION_NO_BLIND_RETRY'}
$fd=[IO.File]::Open($protected,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
$old=$env:CONSULTATION_ORIGINAL_STORE_PASSWORD;$raw=$null;$secure=$null;$bytes=$null;$received=$null;$failure=$null;$began=[DateTimeOffset]::Now
try{
 [IO.File]::WriteAllText($marker,(@{startedAt=$began.ToString('o');loginName='consultation-owner-20261010';singleAttempt=$true;responseUnknownUntilSafeReceipt=$true;noAutomaticRetry=$true}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
 if(Test-Path -LiteralPath $c.native.maintenanceLock){throw 'MAINTENANCE_CHANGED_NO_BUSINESS_WRITE'}
 $env:CONSULTATION_ORIGINAL_STORE_PASSWORD=Read-FinalManagedSecret $m.credentialReference
 # Never redirect Java stdout to a log: it contains a one-time activation response.
 $raw=(& java -Xmx64m -cp $PSScriptRoot NormalSubjectDelivery51 $m.privateStoreReference.path $m.trustCertificate.path 'consultation-owner-20261010' 2>$null) -join "`n"
 if($LASTEXITCODE -ne 0){throw 'REGISTRATION_UNKNOWN_OR_REFUSED_NO_RETRY'}
 $received=$raw|ConvertFrom-Json -AsHashtable
 if($received.subjectId -cnotmatch '^[a-f0-9-]{36}$' -or $received.oneTimeActivation -cnotmatch '^[A-Za-z0-9_-]{43}$'){throw 'UNKNOWN_REGISTRATION_RESPONSE_NO_RETRY'}
 $secure=ConvertTo-SecureString -String $raw -AsPlainText -Force
 $encrypted=ConvertFrom-SecureString -SecureString $secure
 $bytes=[Text.Encoding]::UTF8.GetBytes($encrypted);$fd.Write($bytes,0,$bytes.Length);$fd.Flush($true)
 $receipt=@{format='NORMAL_SUBJECT_SAFE_DELIVERY/v1';taskId='VER-SINGLE-DB-CON-NORMAL-INPUT-01';registeredAt=$began.ToString('o');protectedAt=[DateTimeOffset]::Now.ToString('o');issuer='https://consultation.agent-platform.internal:5446';subjectId=$received.subjectId;loginName='consultation-owner-20261010';status='PENDING';activationLifetime='PT10M';passwordMinimumLength=16;privateDpapiPath=$protected;currentUserDpapi=$true;existingAclUnchanged=$true;privateKeyCopied=$false;activationOrPasswordInEvidence=$false;originalMtlsAndServerPins=$true;normalJarSha256='69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d';activatedByAgent=$false;taskCreated=$false;grantsCreated=0;sourceCreated=$false;modelCalls=0;newWritesMustBeIncludedInNextProtection=$true}
 [IO.File]::WriteAllText("$PSScriptRoot/safe-delivery51.json",($receipt|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
 'SUBJECT_REGISTERED_PENDING_CURRENT_USER_DPAPI_DELIVERY_READY'
}catch{$failure='REGISTRATION_UNKNOWN_OR_SAFE_DELIVERY_UNCONFIRMED_NO_AUTOMATIC_RETRY';throw $failure}
finally{$env:CONSULTATION_ORIGINAL_STORE_PASSWORD=$old;$old=$null;$raw=$null;$received=$null;$secure=$null;$encrypted=$null;if($bytes){[Array]::Clear($bytes,0,$bytes.Length)};$fd.Dispose()}

