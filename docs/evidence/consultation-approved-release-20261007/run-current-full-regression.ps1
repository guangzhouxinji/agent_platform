$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$evidence=Join-Path $workspace 'docs/evidence/consultation-approved-release-20261007/current-full-regression'
if(Test-Path -LiteralPath $evidence){throw 'Never overwrite raw full-run evidence'}
New-Item -ItemType Directory -Path $evidence|Out-Null
function Snapshot($file){$items=@(Get-ChildItem (Join-Path $workspace 'backend') -Recurse -File | Where-Object {$_.FullName -notmatch '[\\/]target[\\/]'} | Sort-Object FullName | ForEach-Object {@{path=$_.FullName.Substring($workspace.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}});$items|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 -LiteralPath $file}
Snapshot (Join-Path $evidence 'source-before.json')
$rbc=Get-Content (Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/rbc-prepared-ENFREG-RBC-FULL-20261007-01/resource-manifest.json') -Raw|ConvertFrom-Json
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
  $env:JAVA_HOME='D:\software\jdk-21';$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
  $started=Get-Date -Format o
  & mvn -f (Join-Path $workspace 'backend/pom.xml') test '-Dsurefire.reportNameSuffix=20261007-HANDOFF-FULL' *> (Join-Path $evidence 'full-backend.log')
  $exit=$LASTEXITCODE
  @{exit=$exit;startedAt=$started;finishedAt=(Get-Date -Format o);command='mvn -f backend/pom.xml test -Dsurefire.reportNameSuffix=20261007-HANDOFF-FULL';scope='FULL_UNFILTERED_CURRENT_SOURCE_REAL_ISOLATED_ENVIRONMENT';genericAdminEnvironmentExported=$false}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $evidence 'full-backend-exit.json')
 }finally{foreach($key in $restore.Keys){[Environment]::SetEnvironmentVariable($key,$restore[$key],'Process')}}
}
Snapshot (Join-Path $evidence 'source-after.json')
$before=(Get-FileHash (Join-Path $evidence 'source-before.json')).Hash;$after=(Get-FileHash (Join-Path $evidence 'source-after.json')).Hash
@{beforeSha=$before;afterSha=$after;unchanged=($before -eq $after);fixedJarSha=(Get-FileHash (Join-Path $workspace 'backend/apps/platform-api/target/agent-platform-consultation-comparison-20261007-125036.jar')).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $evidence 'source-stability.json')
Get-Content (Join-Path $evidence 'full-backend-exit.json')
Get-Content (Join-Path $evidence 'full-backend.log') -Tail 22
