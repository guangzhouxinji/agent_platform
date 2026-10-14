[CmdletBinding()]
param([string]$JavaHome='D:/software/jdk-21', [switch]$RunDm8, [string]$BuildName='isolated-build-round3')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if($BuildName -notmatch '^isolated-build[-a-z0-9]*$'){throw 'Safe dedicated build name required'};$out=Join-Path $PSScriptRoot $BuildName
if(Test-Path -LiteralPath $out){throw 'Build directory exists; preserve it. Use the existing compiled runner or a new evidence run directory.'}
New-Item -ItemType Directory -Path $out | Out-Null
$lib=Join-Path $out 'lib';$classes=Join-Path $out 'classes'
New-Item -ItemType Directory -Path $lib,$classes | Out-Null
$package=Join-Path $root 'backend/apps/platform-api/target/agent-platform-single-db-engineering-20261007.jar'
# Read only an explicit engineering artifact. Never inspect live containers, application secrets or data.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($package)
try {
 foreach($entry in $archive.Entries){
  $destination=$null
  if($entry.FullName -match '^BOOT-INF/lib/([^/]+\.jar)$'){$destination=Join-Path $lib $Matches[1]}
  elseif($entry.FullName.StartsWith('BOOT-INF/classes/') -and $entry.FullName.EndsWith('.class')){
   $destination=Join-Path $classes $entry.FullName.Substring(17)
  }
  if($destination){New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$false)}
 }
}finally{$archive.Dispose()}
$m2=Join-Path $HOME '.m2/repository'
$jars=@(
 'org/junit/jupiter/junit-jupiter-api/5.10.5/junit-jupiter-api-5.10.5.jar',
 'org/junit/jupiter/junit-jupiter-engine/5.10.5/junit-jupiter-engine-5.10.5.jar',
 'org/junit/platform/junit-platform-engine/1.10.5/junit-platform-engine-1.10.5.jar',
 'org/junit/platform/junit-platform-commons/1.10.5/junit-platform-commons-1.10.5.jar',
 'org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar',
 'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar',
 'org/assertj/assertj-core/3.25.3/assertj-core-3.25.3.jar'
)
foreach($jar in $jars){$file=Join-Path $m2 $jar;if(-not(Test-Path -LiteralPath $file)){throw "Local dependency unavailable; no downloads: $jar"};Copy-Item -LiteralPath $file -Destination $lib}
$test=Join-Path $root 'backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductSingleInstancePersistentDm8IT.java'
$runner=Join-Path $PSScriptRoot 'PersistentDm8JupiterRunner.java'
$cp="$classes;$lib/*"
Push-Location $root
try {
 & "$JavaHome/bin/javac.exe" --release 21 -encoding UTF-8 -cp $cp -d $classes $test $runner *> (Join-Path $out 'javac.log')
 if($LASTEXITCODE -ne 0){throw 'Isolated compilation failed; see javac.log'}
 $inputs=@($package,$test,$runner)+@(Get-ChildItem $lib -File | ForEach-Object FullName)
 $inputs | ForEach-Object {$hash=Get-FileHash -Algorithm SHA256 -LiteralPath $_;@{path=$hash.Path;sha256=$hash.Hash.ToLowerInvariant()}} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $out 'inputs.json') -Encoding utf8NoBOM
 if($RunDm8){
  & "$JavaHome/bin/java.exe" -cp $cp PersistentDm8JupiterRunner *> (Join-Path $out 'dm8-junit.log')
  if($LASTEXITCODE -ne 0){throw 'Persistent DM8 engineering tests did not pass'}
 }else{
  $names=@('OWNER','PHASE','URL','STORAGE_PROOF','BASELINE','RESOURCE_ID')
  $saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable("SINGLE_DB_PERSISTENT_$name",'Process');[Environment]::SetEnvironmentVariable("SINGLE_DB_PERSISTENT_$name",$null,'Process')}
  try {
   & "$JavaHome/bin/java.exe" -cp $cp PersistentDm8JupiterRunner 'Required owned engineering setting: OWNER' *> (Join-Path $out 'offline-owner-guard.log')
   if($LASTEXITCODE -ne 0){throw 'Missing-owner guard failed'}
   $env:SINGLE_DB_PERSISTENT_OWNER='VER-AGENT-SINGLE-DB-PERSISTENT-01'
   & "$JavaHome/bin/java.exe" -cp $cp PersistentDm8JupiterRunner 'Required owned engineering setting: PHASE' *> (Join-Path $out 'offline-phase-guard.log')
   if($LASTEXITCODE -ne 0){throw 'Missing-phase guard failed'}
   $env:SINGLE_DB_PERSISTENT_PHASE='after-restart';$env:SINGLE_DB_PERSISTENT_URL='jdbc:dm://127.0.0.1:5301'
   & "$JavaHome/bin/java.exe" -cp $cp PersistentDm8JupiterRunner '5492' *> (Join-Path $out 'offline-forbidden-url-guard.log')
   if($LASTEXITCODE -ne 0){throw 'Forbidden-url guard failed'}
   $env:SINGLE_DB_PERSISTENT_URL='jdbc:dm://127.0.0.1:5492'
   & "$JavaHome/bin/java.exe" -cp $cp PersistentDm8JupiterRunner 'Required owned engineering setting: STORAGE_PROOF' *> (Join-Path $out 'offline-quota-guard.log')
   if($LASTEXITCODE -ne 0){throw 'Missing-quota guard failed'}
  }finally{foreach($name in $names){[Environment]::SetEnvironmentVariable("SINGLE_DB_PERSISTENT_$name",$saved[$name],'Process')}}
 }
 @{compiled=$true;offlineGuardChecks=$(if($RunDm8){0}else{4});dm8Executed=[bool]$RunDm8;sharedTargetWritten=$false;packageSha256=(Get-FileHash $package -Algorithm SHA256).Hash.ToLowerInvariant()} | ConvertTo-Json | Set-Content (Join-Path $out 'build-result.json') -Encoding utf8NoBOM
}finally{Pop-Location}


