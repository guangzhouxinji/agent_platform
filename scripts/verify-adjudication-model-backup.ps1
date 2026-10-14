param([Parameter(Mandatory)][string]$BackendPom,[Parameter(Mandatory)][string]$RunId)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[a-z][a-z0-9-]{1,45}$'){throw 'Safe unique run required'}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$evidence=Join-Path $workspace "docs/evidence/adjudication-llm-materials-20261004/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Evidence cannot be overwritten'}
New-Item -ItemType Directory -Path $evidence|Out-Null
$private=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1'
$owned=Join-Path $private "backup-validation-$RunId"
if(Test-Path -LiteralPath $owned){throw 'New private backup directory required'}
New-Item -ItemType Directory -Path $owned|Out-Null
$container="adj-model-recovery-$RunId";$created=$false;$exported=$false
$native="adj-$RunId.dmp";$source='agent-platform-con-product-primary'
$dump=Join-Path $owned $native;$envBefore=@{}
# Private backup bytes are owner-only, as are the original per-run key files.
$acl=Get-Acl -LiteralPath $owned;$owner=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.SetAccessRuleProtection($true,$false);$rule=[Security.AccessControl.FileSystemAccessRule]::new($owner,'FullControl','ContainerInherit,ObjectInherit','None','Allow');$acl.SetAccessRule($rule);Set-Acl -LiteralPath $owned -AclObject $acl
$keyFile=Join-Path $private 'keys/f5448bc3-0709-471f-82f1-95c2a52be6a4.key'
$keyBefore=(Get-FileHash -LiteralPath $keyFile -Algorithm SHA256).Hash

function Set-OwnedEnv([string]$name,[string]$value){if(-not $envBefore.ContainsKey($name)){$envBefore[$name]=[Environment]::GetEnvironmentVariable($name,'Process')};[Environment]::SetEnvironmentVariable($name,$value,'Process')}
function Read-PrivateSecret([string]$path){$v=Import-Clixml -LiteralPath $path;$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($v);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
try {
 & node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $evidence 'verify-before.log');if($LASTEXITCODE -ne 0){throw 'Pre-execution governance failed'}
 $existing=& docker ps -a --format '{{.Names}}';if($existing -contains $container){throw 'Refusing existing recovery container'}
 if(Get-NetTCPConnection -LocalPort 5369 -State Listen -ErrorAction SilentlyContinue){throw 'Independent recovery port unavailable'}
 $image=& docker inspect --format '{{.Config.Image}}' $source;if($LASTEXITCODE -ne 0 -or $image -ne 'cnxc/dm8:20251114-kylin'){throw 'Expected source image missing'}
 $password=Read-PrivateSecret (Join-Path $private 'dm8-model-schema.credential.xml')
 if($password -notmatch '^[A-Za-z0-9]{32}$'){throw 'Exact private model schema credential required'}
 $nativeKey='N9'+[Guid]::NewGuid().ToString('N').Substring(0,30)
 $export='IFS= read -r p; IFS= read -r k; p=$(printf %s "$p" | tr -d "\r"); k=$(printf %s "$k" | tr -d "\r"); test ! -e /tmp/'+$native+' || exit 91; /opt/dmdbms/bin/dexp "ADJLLM20261003/$p@127.0.0.1:5236" SCHEMAS=ADJLLM20261003 FILE='+$native+' DIRECTORY=/tmp ENCRYPT=Y ENCRYPT_PASSWORD="$k" NOLOGFILE=Y'
 & docker exec $source test ! -e "/tmp/$native" *> (Join-Path $owned 'export-preflight-private.log');if($LASTEXITCODE -ne 0){throw 'Refusing existing source-side export file'}
 $exported=$true
 @($password,$nativeKey) | & docker exec -i $source sh -c $export *> (Join-Path $owned 'native-export-private.log')
 if($LASTEXITCODE -ne 0){throw 'Native ADJ-only export failed; no source stop or restore'};$exported=$true
 & docker cp "$($source):/tmp/$native" $dump *> (Join-Path $owned 'copy-private.log');if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $dump)){throw 'Native backup copy failed'}
 $sha=(Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash.ToLower()
 $expiry='2026-11-01T16:00:00Z';if([DateTimeOffset]::UtcNow -ge [DateTimeOffset]::Parse($expiry)){throw 'Backup expired; no restore permitted'}
 Set-OwnedEnv 'SYSDBA_PWD' ('R9'+[Guid]::NewGuid().ToString('N').Substring(0,30))
 Set-OwnedEnv 'SYSAUDITOR_PWD' ('A9'+[Guid]::NewGuid().ToString('N').Substring(0,30))
 Set-OwnedEnv 'SYSSSO_PWD' ('S9'+[Guid]::NewGuid().ToString('N').Substring(0,30))
 & docker run --rm -d --name $container --shm-size=1g -p '127.0.0.1:5369:5236' -e SYSDBA_PWD -e SYSAUDITOR_PWD -e SYSSSO_PWD -e PAGE_SIZE=32 -e EXTENT_SIZE=32 -e UNICODE_FLAG=1 -e CASE_SENSITIVE=1 -e INSTANCE_NAME=ADJ_RECOVERY $image *> (Join-Path $owned 'container-private.log')
 if($LASTEXITCODE -ne 0){throw 'New isolated recovery instance failed'};$created=$true
 Set-OwnedEnv 'ADJ_BACKUP_SOURCE_URL' 'jdbc:dm://127.0.0.1:5301'
 Set-OwnedEnv 'ADJ_BACKUP_RESTORE_URL' 'jdbc:dm://127.0.0.1:5369'
 Set-OwnedEnv 'ADJ_BACKUP_USER' 'ADJLLM20261003';Set-OwnedEnv 'ADJ_BACKUP_PASSWORD' $password
 Set-OwnedEnv 'ADJ_BACKUP_KEY_DIRECTORY' (Join-Path $private 'keys')
 Set-OwnedEnv 'ADJ_BACKUP_EVIDENCE' $evidence;Set-OwnedEnv 'ADJ_MODEL_BACKUP_RECOVERY' 'true'
 $driver=(Get-ChildItem "$env:USERPROFILE/.m2/repository/com/dameng" -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
 $ready=$false
 for($attempt=0;$attempt -lt 24;$attempt++) {
  & java -cp $driver (Join-Path $workspace 'scripts/java/CreateAdjudicationRecoverySchema.java') *> (Join-Path $owned 'create-private.log')
  if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Seconds 5
 }
 if(-not $ready){throw 'Independent recovery database readiness/schema creation failed'}
 & docker cp $dump "$($container):/tmp/$native" *> (Join-Path $owned 'import-copy-private.log');if($LASTEXITCODE -ne 0){throw 'Recovery backup copy failed'}
 $import='IFS= read -r p; IFS= read -r k; p=$(printf %s "$p" | tr -d "\r"); k=$(printf %s "$k" | tr -d "\r"); /opt/dmdbms/bin/dimp "ADJLLM20261003/$p@127.0.0.1:5236" SCHEMAS=ADJLLM20261003 FILE='+$native+' DIRECTORY=/tmp ENCRYPT_PASSWORD="$k" NOLOGFILE=Y'
 @($password,$nativeKey)| & docker exec -i $container sh -c $import *> (Join-Path $owned 'native-import-private.log')
 if($LASTEXITCODE -ne 0){throw 'Native isolated ADJ import failed'}
 & mvn -f $BackendPom "-Dmaven.multiModuleProjectDirectory=$workspace" -pl apps/platform-api -am '-Dtest=MaterialModelBackupDm8IT' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $evidence 'tests.log')
 if($LASTEXITCODE -ne 0){throw 'Actual native-import recovery assertions failed; original budget unchanged'}
 $report=Join-Path (Split-Path -Parent $BackendPom) 'apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.adjudication.casework.MaterialModelBackupDm8IT.xml'
 Copy-Item -LiteralPath $report -Destination (Join-Path $evidence 'TEST-MaterialModelBackupDm8IT.xml')
 @{scope='NATIVE_ADJ_ONLY_ENCRYPTED_DATABASE_BACKUP';sourceSchema='ADJLLM20261003';sourceStopped=$false;sourceAuthorityWritten=$false;recoveryUrl='jdbc:dm://127.0.0.1:5369';backupSha256=$sha;backupBytes=(Get-Item -LiteralPath $dump).Length;expiresAt=$expiry;perRunKeysInBackup=$false;newProviderCalls=0;cleanupRequiredBeforeSuccess=$true}|ConvertTo-Json|Set-Content (Join-Path $evidence 'backup-manifest.json') -Encoding utf8
}finally{
 if($created){& docker stop $container *> (Join-Path $evidence 'recovery-cleanup.log');if($LASTEXITCODE -ne 0){throw 'New recovery instance cleanup failed'}}
 if($exported){& docker exec $source rm -- "/tmp/$native" *> (Join-Path $evidence 'source-export-cleanup.log');if($LASTEXITCODE -ne 0){throw 'This-round export cleanup failed'}}
 $root=[IO.Path]::GetFullPath($owned);$expected=[IO.Path]::GetFullPath((Join-Path $private "backup-validation-$RunId"));if($root -ne $expected){throw 'Cleanup root mismatch'}
 foreach($file in Get-ChildItem -LiteralPath $owned -File){if($file.DirectoryName -ne $root){throw 'Cleanup target escaped owned backup directory'};Remove-Item -LiteralPath $file.FullName -Force}
 Remove-Item -LiteralPath $owned -Force
 foreach($key in $envBefore.Keys){[Environment]::SetEnvironmentVariable($key,$envBefore[$key],'Process')}
}
@{newBackupDeleted=$true;newRecoveryInstanceDeleted=$true;originalRunKeysUntouched=((Get-FileHash -LiteralPath $keyFile -Algorithm SHA256).Hash -eq $keyBefore);cleanupCompletedAt=[DateTimeOffset]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content (Join-Path $evidence 'cleanup-summary.json') -Encoding utf8
& node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $evidence 'verify-after.log')
Write-Output "NATIVE_BACKUP_RECOVERY_EXIT=0 VERIFY_AFTER_EXIT=$LASTEXITCODE EVIDENCE=$evidence"
