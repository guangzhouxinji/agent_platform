$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$fullEvidenceRoot=Join-Path $workspace 'docs/evidence/consultation-approved-release-20261007/current-full-regression-final'
if(Test-Path -LiteralPath $fullEvidenceRoot){throw 'Never overwrite raw full-run evidence'}
New-Item -ItemType Directory -Path $fullEvidenceRoot|Out-Null
function Snapshot($file){$items=@(Get-ChildItem (Join-Path $workspace 'backend') -Recurse -File | Where-Object {$_.FullName -notmatch '[\\/]target[\\/]'} | Sort-Object FullName | ForEach-Object {@{path=$_.FullName.Substring($workspace.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}});$items|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 -LiteralPath $file}
Snapshot (Join-Path $fullEvidenceRoot 'source-before.json')
$rbc=Get-Content (Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/rbc-prepared-ENFREG-RBC-FULL-20261007-02/resource-manifest.json') -Raw|ConvertFrom-Json
$actual=& docker inspect --format '{{.Id}}|{{.State.Running}}' $rbc.containerId
if($actual.Trim() -ne ($rbc.containerId+'|true')){throw 'Fresh actual RBC resource required'}
if(Test-Path -LiteralPath (Join-Path $rbc.fileRoot '.enf-rbc-run-started')){throw 'RBC marker already consumed; cannot reimport'}
$wrapper=Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/Invoke-SafeEnforcementRegressionEnvironment.ps1'
$inspection=Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/safe-full-inspection-02/inspection.json'
& $wrapper -InspectionFile $inspection -Action {
 $restore=@{}
 $keys=@($rbc.environment.PSObject.Properties.Name)+@('JOB_PACK_DM8_PASSWORD','JAVA_HOME','PATH')
 foreach($key in $keys){$restore[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 try{
  foreach($property in $rbc.environment.PSObject.Properties){[Environment]::SetEnvironmentVariable($property.Name,[string]$property.Value,'Process')}
  $credential=Import-Clixml -LiteralPath $rbc.passwordCredentialXml
  $secure=if($credential -is [PSCredential]){$credential.Password}else{$credential}
  $p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try{$env:JOB_PACK_DM8_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}
  $two=Get-Content (Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/two-errors-regression-01/execution.json') -Raw|ConvertFrom-Json
  if($two.exitCode -ne 0){throw 'Actual fixed two-case result required'}
  foreach($p in $two.environment.PSObject.Properties){
   if($p.Name -notmatch '^AGENT_GRANT_' -and $p.Name -ne 'ENF_ACCEPTANCE_SUBJECT'){continue}
   $restore[$p.Name]=[Environment]::GetEnvironmentVariable($p.Name,'Process')
   [Environment]::SetEnvironmentVariable($p.Name,[string]$p.Value,'Process')
  }
  foreach($b in $two.credentialBindings){
   if(@($b.variables|Where-Object {$_ -notmatch '^AGENT_GRANT_'}).Count){continue}
   if(-not ([IO.Path]::GetFullPath($b.file)).StartsWith((Join-Path $env:LOCALAPPDATA 'AgentPlatform/single-db-engineering-20261007-01/'),[StringComparison]::OrdinalIgnoreCase)){throw 'Only owned engineering fixture vault binding allowed'}
   $secret=Import-Clixml -LiteralPath $b.file;$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
   try{foreach($key in $b.variables){$restore[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer),'Process')}}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
  }
  $env:JAVA_HOME='D:\software\jdk-21';$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
  $started=Get-Date -Format o
  & mvn -f (Join-Path $workspace 'backend/pom.xml') test '-Dsurefire.reportNameSuffix=20261007-HANDOFF-FINAL' *> (Join-Path $fullEvidenceRoot 'full-backend.log')
  $exit=$LASTEXITCODE
  @{exit=$exit;startedAt=$started;finishedAt=(Get-Date -Format o);command='mvn -f backend/pom.xml test -Dsurefire.reportNameSuffix=20261007-HANDOFF-FINAL';scope='FULL_UNFILTERED_CURRENT_SOURCE_REAL_ISOLATED_ENVIRONMENT';genericAdminEnvironmentExported=$false}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $fullEvidenceRoot 'full-backend-exit.json')
 }finally{foreach($key in $restore.Keys){[Environment]::SetEnvironmentVariable($key,$restore[$key],'Process')}}
}
Snapshot (Join-Path $fullEvidenceRoot 'source-after.json')
$before=(Get-FileHash (Join-Path $fullEvidenceRoot 'source-before.json')).Hash;$after=(Get-FileHash (Join-Path $fullEvidenceRoot 'source-after.json')).Hash
@{beforeSha=$before;afterSha=$after;unchanged=($before -eq $after);fixedJarSha=(Get-FileHash (Join-Path $workspace 'backend/apps/platform-api/target/agent-platform-consultation-comparison-20261007-125036.jar')).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $fullEvidenceRoot 'source-stability.json')
Get-Content (Join-Path $fullEvidenceRoot 'full-backend-exit.json')
Get-Content (Join-Path $fullEvidenceRoot 'full-backend.log') -Tail 22
