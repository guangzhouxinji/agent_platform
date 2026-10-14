param([string]$RunName='run01')
$ErrorActionPreference='Stop'
if($RunName -notmatch '^run[0-9]{2}$'){throw 'Dedicated run name required'}
$root=(Resolve-Path "$PSScriptRoot/../../..").Path
$base="$PSScriptRoot/engineering-db/$RunName/lifecycle"
if((Test-Path "$base.mv.db") -or (Test-Path "$PSScriptRoot/.private-vault/$RunName.dpapi.xml") -or (Test-Path "$PSScriptRoot/h2-bootstrap-$RunName.json")){throw 'Preserve previous actual DB. New run required'}
New-Item -ItemType Directory -Force "$PSScriptRoot/.private-vault"|Out-Null
$names=@('SA','CONPROD','CONPROD_SVC','CONAUTH','CONAUTH_SVC','ENFPROD','ENFPROD_SVC','ENFAUTH','ENFAUTH_SVC','ADJPROD','ADJPROD_SVC','ADJAUTH','ADJAUTH_SVC')
$vault=@{};$saved=@{}
try {
 foreach($n in $names){$envName="LIFECYCLE_H2_${n}_PASSWORD";$saved[$envName]=[Environment]::GetEnvironmentVariable($envName,'Process');$bytes=[byte[]]::new(24);[Security.Cryptography.RandomNumberGenerator]::Fill($bytes);$value=[Convert]::ToHexString($bytes).ToLowerInvariant();[Environment]::SetEnvironmentVariable($envName,$value,'Process');$vault[$n]=ConvertTo-SecureString $value -AsPlainText -Force}
 $backupBytes=[byte[]]::new(32);[Security.Cryptography.RandomNumberGenerator]::Fill($backupBytes);$env:LIFECYCLE_H2_SNAPSHOT_KEY=[Convert]::ToBase64String($backupBytes);$vault['SNAPSHOT_KEY']=ConvertTo-SecureString $env:LIFECYCLE_H2_SNAPSHOT_KEY -AsPlainText -Force
 $vault|Export-Clixml "$PSScriptRoot/.private-vault/$RunName.dpapi.xml"
 & icacls "$PSScriptRoot/.private-vault" /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" 'SYSTEM:(OI)(CI)F' > "$PSScriptRoot/vault-acl-$RunName.log"
 $cp="$PSScriptRoot/classes;$root/docs/evidence/single-database-persistent-20261007/isolated-build-round5/lib/*;D:/software/m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar"
 & D:/software/jdk-21/bin/java.exe -Xmx256m -cp $cp BootstrapOwnedH2 $base > "$PSScriptRoot/h2-bootstrap-$RunName.json"
 if($LASTEXITCODE -ne 0){throw 'Bootstrap failed'}
 $env:LIFECYCLE_H2_BASE=$base;$env:LIFECYCLE_H2_CLASSPATH=$cp;$env:LIFECYCLE_H2_RUN=$RunName
 node "$PSScriptRoot/verify-h2-engineering.mjs" *> "$PSScriptRoot/h2-engineering-$RunName.log"
 $code=$LASTEXITCODE
 @{exitCode=$code;actualFileH2=$true;javaMaxHeapMiB=256;noIssuerChainClaim=$true}|ConvertTo-Json|Set-Content "$PSScriptRoot/h2-engineering-$RunName-exit.json"
 Get-Content "$PSScriptRoot/h2-engineering-$RunName.log"
 if($code -ne 0){throw 'Actual H2 lifecycle checks failed; preserve failure artifacts'}
}finally{foreach($n in $saved.Keys){[Environment]::SetEnvironmentVariable($n,$saved[$n],'Process')};Remove-Item Env:LIFECYCLE_H2_BASE,Env:LIFECYCLE_H2_CLASSPATH,Env:LIFECYCLE_H2_RUN,Env:LIFECYCLE_H2_SNAPSHOT_KEY -ErrorAction SilentlyContinue}
