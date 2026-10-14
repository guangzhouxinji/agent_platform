param([Parameter(Mandatory)][string]$Configuration,
    [Parameter(Mandatory)][string]$SecretDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$BatchId,
    [Parameter(Mandatory)][string]$ExpectedContainerId,
    [Parameter(Mandatory)][string]$RetentionRef,
    [Parameter(Mandatory)][long]$RetentionVersion,
    [switch]$ResumeWriterOnSuccess,
    [switch]$WriterAlreadyStopped,
    [string]$ContainerOutputDirectory='')
$ErrorActionPreference='Stop'
if($BatchId -notmatch '^[a-z][a-z0-9-]{2,80}$'){throw 'Exact backup batch identifier required'}
if($RetentionRef -notmatch '^backup-retention-v[0-9]+\.json$' -or $RetentionVersion -lt 1){throw 'Versioned physical retention required'}
& "$PSScriptRoot/start-consultation-container.ps1" -Configuration $Configuration -ValidateOnly
$config=Get-Content -LiteralPath $Configuration -Raw|ConvertFrom-Json -AsHashtable
function Read-BackupSecret([string]$Name){
    $secure=Import-Clixml -LiteralPath (Join-Path $SecretDirectory $Name)
    if($secure -isnot [Security.SecureString]){throw 'Managed backup secret required'}
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
    finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
function Invoke-BackupDocker([string[]]$Arguments){
    $result=& docker @Arguments
    if($LASTEXITCODE -ne 0){throw 'Backup Docker operation failed'}
    return $result
}
$actual=Invoke-BackupDocker @('inspect','--format','{{.Id}}|{{.State.Running}}',$config.appName)
$expectedRunning=if($WriterAlreadyStopped){'false'}else{'true'}
if($actual -ne "$ExpectedContainerId|$expectedRunning"){throw 'Exact writer identity/state required'}
if($WriterAlreadyStopped -and $ResumeWriterOnSuccess){throw 'Already stopped writer cannot be automatically resumed'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'New independent backup directory required'}
$output=(New-Item -ItemType Directory -Path $OutputDirectory).FullName
$containerOutput=$output
if($ContainerOutputDirectory){
    if($ContainerOutputDirectory -notmatch '^/mnt/docker-desktop-disk/agent-platform-product-files/backup-[a-z][a-z0-9-]{2,80}$' -or
       $ContainerOutputDirectory -ne "/mnt/docker-desktop-disk/agent-platform-product-files/backup-$BatchId"){
        throw 'Exact independent managed backup output required'
    }
    $containerOutput=$ContainerOutputDirectory
    $parent='/mnt/docker-desktop-disk/agent-platform-product-files'
    Invoke-BackupDocker @('run','--rm','--read-only','--network','none','--mount',"type=bind,source=$parent,target=/managed",$config.image,
        'sh','-c',"set -eu; test ! -e /managed/backup-$BatchId; mkdir -m 0750 /managed/backup-$BatchId; chown 10001:10001 /managed/backup-$BatchId")|Out-Null
}
$mountedJar=if($config.containerJar){$config.containerJar}else{$config.jar}
$primary=$config.databases[0];$authority=$config.databases[1]
$primaryIp=Invoke-BackupDocker @('inspect','--format','{{.NetworkSettings.Networks.bridge.IPAddress}}',$primary.name)
$authorityIp=Invoke-BackupDocker @('inspect','--format','{{.NetworkSettings.Networks.bridge.IPAddress}}',$authority.name)
if($primaryIp -notmatch '^[0-9.]+$' -or $authorityIp -notmatch '^[0-9.]+$'){throw 'Existing private database network required'}
$nativeName="$BatchId.dmp"
$backupPassword=Read-BackupSecret 'conprod-backup.credential.xml'
$nativeKey=Read-BackupSecret 'dm8-export-key.credential.xml'
$retentionSha=(Invoke-BackupDocker @('run','--rm','--read-only','--network','none','--user','10001:10001','--cap-drop','ALL',
    '--mount',"type=bind,source=$($config.evidenceRoot),target=/evidence,readonly",$config.image,
    'sha256sum',"/evidence/$RetentionRef")) -split ' ' | Select-Object -First 1
$settings=[ordered]@{
    PRODUCT_TASK_BACKUP_KEY_BASE64=(Read-BackupSecret 'backup-bundle-key.credential.xml')
    PRODUCT_TASK_BACKUP_DB_URL="jdbc:dm://${primaryIp}:5236";PRODUCT_TASK_BACKUP_DB_SCHEMA='CONPROD'
    PRODUCT_TASK_BACKUP_DB_USER='CONPROD_BKP';PRODUCT_TASK_BACKUP_DB_PASSWORD=$backupPassword
    PRODUCT_TASK_BACKUP_AUTHORITY_URL="jdbc:dm://${authorityIp}:5236";PRODUCT_TASK_BACKUP_AUTHORITY_SCHEMA='CONAUTH'
    PRODUCT_TASK_BACKUP_AUTHORITY_USER='CONAUTH_BKP';PRODUCT_TASK_BACKUP_AUTHORITY_PASSWORD=(Read-BackupSecret 'conauth-backup.credential.xml')
    PRODUCT_TASK_BACKUP_ISSUER=$config.settings.PRODUCT_IDENTITY_ISSUER
    PRODUCT_TASK_BACKUP_EVIDENCE_ROOT='/product/evidence';PRODUCT_TASK_BACKUP_EVIDENCE_MAX_BYTES=[string]$config.settings.PRODUCT_SOURCE_EVIDENCE_MAX_BYTES
    PRODUCT_TASK_BACKUP_RETENTION_REF=$RetentionRef;PRODUCT_TASK_BACKUP_RETENTION_VERSION=[string]$RetentionVersion
    PRODUCT_TASK_BACKUP_RETENTION_SHA256=$retentionSha;PRODUCT_TASK_BACKUP_BATCH_ID=$BatchId
    PRODUCT_TASK_BACKUP_KEY_ID='backup-bundle-key-v1';PRODUCT_TASK_BACKUP_EXPIRES_AT=[DateTimeOffset]::UtcNow.AddHours(1).ToString('o')
    PRODUCT_TASK_BACKUP_NATIVE_EXPORT="/output/$nativeName";PRODUCT_TASK_BACKUP_PACKAGE="/output/$BatchId.conbkp"
    TZ='Asia/Shanghai'
}
$setKeys=[Collections.Generic.List[string]]::new();$stopped=$false;$sealed=$false
try {
    foreach($key in $settings.Keys){
        if($null -ne [Environment]::GetEnvironmentVariable($key,'Process')){throw "Preserve existing variable $key"}
        [Environment]::SetEnvironmentVariable($key,$settings[$key],'Process');$setKeys.Add($key)
    }
    if(-not $WriterAlreadyStopped){Invoke-BackupDocker @('stop',$config.appName)|Out-Null}
    $stopped=$true
    $exportScript='IFS= read -r p; IFS= read -r k; p=$(printf %s "$p" | tr -d "\r"); k=$(printf %s "$k" | tr -d "\r"); /opt/dmdbms/bin/dexp "CONPROD_BKP/$p@127.0.0.1:5236" SCHEMAS=CONPROD FILE='+$nativeName+' DIRECTORY=/tmp ENCRYPT=Y ENCRYPT_PASSWORD="$k" NOLOGFILE=Y'
    $lines=(@($backupPassword,$nativeKey) -join "`n")|docker exec -i $primary.name sh -c $exportScript 2>&1
    $exportExit=$LASTEXITCODE
    $lines|ForEach-Object {([string]$_).Replace($backupPassword,'[REDACTED]').Replace($nativeKey,'[REDACTED]')}|
        Tee-Object -FilePath (Join-Path $output 'native-export.log')|Out-Null
    $problems=@($lines|Where-Object {$_ -match '(?i)(?:warnings?|errors?|警告|错误)\s*[:：]?\s*[1-9][0-9]*' -or ($_ -match '(?i)(?:failed|失败)' -and $_ -notmatch '(?i)(?:failed|失败)\s*[:：]?\s*0\b')})
    if($exportExit -ne 0 -or $problems.Count){throw 'Native export failed or warned; no package published'}
    Invoke-BackupDocker @('cp',"$($primary.name):/tmp/$nativeName",(Join-Path $output $nativeName))|Out-Null
    if((Get-Item -LiteralPath (Join-Path $output $nativeName)).Length -lt 10000){throw 'Incomplete native export'}
    if($ContainerOutputDirectory){
        $encoded=[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $output $nativeName)))
        $copied=$encoded | docker run --rm -i --read-only --network none --user 10001:10001 --cap-drop ALL --mount "type=bind,source=$containerOutput,target=/output" $config.image sh -c "set -eu; test ! -e /output/$nativeName; tr -d '\r\n' | base64 -d > /output/$nativeName; sha256sum /output/$nativeName"
        if($LASTEXITCODE -ne 0 -or ($copied -split ' ')[0] -ne (Get-FileHash -LiteralPath (Join-Path $output $nativeName)).Hash.ToLowerInvariant()){throw 'Managed native export SHA mismatch'}
        $encoded=$null
    }
    $sealContainer="agent-platform-backup-$BatchId"
    $lifecycle=if($ContainerOutputDirectory){@('--name',$sealContainer,'--label',"agent-platform.task=PC-COMPARISON-APPROVED-RELEASE-01")}else{@('--rm')}
    $arguments=@('run')+$lifecycle+@('--read-only','--user','10001:10001','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--memory','2g','--tmpfs','/tmp:rw,nosuid,size=128m',
        '--mount',"type=bind,source=$mountedJar,target=/app/platform-api.jar,readonly",
        '--mount',"type=bind,source=$($config.evidenceRoot),target=/product/evidence,readonly",
        '--mount',"type=bind,source=$containerOutput,target=/output")
    foreach($key in $settings.Keys){$arguments+=@('--env',$key)}
    $arguments+=@($config.image,'java','-jar','/app/platform-api.jar','--task-backup','seal')
    Invoke-BackupDocker $arguments
    if($ContainerOutputDirectory){Invoke-BackupDocker @('cp',"${sealContainer}:/output/$BatchId.conbkp",(Join-Path $output "$BatchId.conbkp"))|Out-Null}
    $package=Get-Item -LiteralPath (Join-Path $output "$BatchId.conbkp")
    $sealed=$true
    Write-Output "sealed_batch=$BatchId bytes=$($package.Length) sha=$((Get-FileHash -LiteralPath $package.FullName).Hash.ToLowerInvariant()) evidenceRoot=$($config.evidenceRoot)"
} finally {
    foreach($key in $setKeys){Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue}
    $settings.Clear();$backupPassword=$null;$nativeKey=$null
    if($stopped -and $sealed -and $ResumeWriterOnSuccess){Invoke-BackupDocker @('start',$config.appName)|Out-Null}
    elseif($stopped){Write-Warning 'Writer remains stopped; failed or release-protection backups never automatically resume writes.'}
}
