param([Parameter(Mandatory)][string]$RbcManifestFile,[Parameter(Mandatory)][string]$InspectionFile)
$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$fullEvidenceRoot=Join-Path $workspace 'docs/evidence/consultation-start-races-20261008/full-regression-rbc10'
if(Test-Path -LiteralPath $fullEvidenceRoot){throw 'Never overwrite raw full-run evidence'}
New-Item -ItemType Directory -Path $fullEvidenceRoot|Out-Null
function Snapshot($file){
 $items=@(foreach($directory in @('backend','db','scripts')){Get-ChildItem (Join-Path $workspace $directory) -Recurse -File | Where-Object {$_.FullName -notmatch '[\\/]target[\\/]'}})
 $items=@($items|Sort-Object FullName|ForEach-Object {@{path=$_.FullName.Substring($workspace.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
 $items|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 -LiteralPath $file
}
Snapshot (Join-Path $fullEvidenceRoot 'source-before.json')
$evidenceOwner=(Resolve-Path (Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007')).Path+'\'
foreach($input in @($RbcManifestFile,$InspectionFile)){if(-not (Resolve-Path -LiteralPath $input).Path.StartsWith($evidenceOwner,[StringComparison]::OrdinalIgnoreCase)){throw 'Existing engineering-owned exact input files required'}}
$rbc=Get-Content -LiteralPath $RbcManifestFile -Raw|ConvertFrom-Json
$actual=& docker inspect --format '{{.Id}}|{{.State.Running}}' $rbc.containerId
if($actual.Trim() -ne ($rbc.containerId+'|true')){throw 'Fresh actual RBC resource required'}
if(Test-Path -LiteralPath (Join-Path $rbc.fileRoot '.enf-rbc-run-started')){throw 'RBC marker already consumed; cannot reimport'}
$wrapper=Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/Invoke-SafeEnforcementRegressionEnvironment.ps1'
$inspection=$InspectionFile
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
  & mvn -f (Join-Path $workspace 'backend/pom.xml') test '-Dsurefire.reportNameSuffix=20261008-START-RACE-FULL10' *> (Join-Path $fullEvidenceRoot 'full-backend.log')
  $exit=$LASTEXITCODE
  @{exit=$exit;startedAt=$started;finishedAt=(Get-Date -Format o);command='mvn -f backend/pom.xml test -Dsurefire.reportNameSuffix=20261008-START-RACE-FULL10';scope='FULL_UNFILTERED_CURRENT_SOURCE_REAL_ISOLATED_ENVIRONMENT';genericAdminEnvironmentExported=$false}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $fullEvidenceRoot 'full-backend-exit.json')
 }finally{foreach($key in $restore.Keys){[Environment]::SetEnvironmentVariable($key,$restore[$key],'Process')}}
}
Snapshot (Join-Path $fullEvidenceRoot 'source-after.json')
$before=(Get-FileHash (Join-Path $fullEvidenceRoot 'source-before.json')).Hash;$after=(Get-FileHash (Join-Path $fullEvidenceRoot 'source-after.json')).Hash
@{beforeSha=$before;afterSha=$after;unchanged=($before -eq $after);fixedJarSha=(Get-FileHash (Join-Path $workspace 'backend/apps/platform-api/target/agent-platform-consultation-comparison-20261007-125036.jar')).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $fullEvidenceRoot 'source-stability.json')
Get-Content (Join-Path $fullEvidenceRoot 'full-backend-exit.json')
Get-Content (Join-Path $fullEvidenceRoot 'full-backend.log') -Tail 22




