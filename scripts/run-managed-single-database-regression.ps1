param([Parameter(Mandatory)][string]$RequestFile)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
Push-Location $workspace
try {
 # No Docker mutation, old input reads, secret loading or Maven before current metadata gate.
 & node scripts/preflight-single-database-regression.mjs --request $RequestFile
 if($LASTEXITCODE -ne 0){throw 'Current exact regression preflight refused; no execution'}
 $requestSha=(Get-FileHash -LiteralPath $RequestFile -Algorithm SHA256).Hash
 $request=Get-Content -LiteralPath $RequestFile -Raw|ConvertFrom-Json
 $rbc=Get-Content -LiteralPath $request.rbcManifestFile -Raw|ConvertFrom-Json
 $engineering='e318769a540a04b8406eeecf93f47615f1133ef8b035d30cd00bf984635e3b45'
 $live=& docker inspect --format '{{.Id}}|{{.State.Running}}|{{index .Config.Labels "task"}}|{{index .Config.Labels "agent-platform.role"}}' $engineering
 if($LASTEXITCODE -ne 0 -or $live.Trim() -ne ($engineering+'|true|VER-AGENT-SINGLE-DB-ISOLATED-01|single-db-engineering')){throw 'Existing approved engineering target not live; will not start it'}
 $live=& docker inspect --format '{{.Id}}|{{.State.Running}}|{{index .Config.Labels "agent-platform.role"}}|{{index .Config.Labels "agent-platform.run-id"}}' $rbc.containerId
 if($LASTEXITCODE -ne 0 -or $live.Trim() -ne ($rbc.containerId+'|true|enforcement-test|'+$rbc.runId)){throw 'Fresh exact live RBC required'}
 & node scripts/preflight-single-database-regression.mjs --request $RequestFile
 if($LASTEXITCODE -ne 0){throw 'Preflight changed before managed injection'}
 $output=[IO.Path]::GetFullPath($request.outputRoot)
 if(Test-Path -LiteralPath $output){throw 'Never overwrite full-run evidence'}
 New-Item -ItemType Directory -Path $output|Out-Null
 . (Join-Path $PSScriptRoot 'lib/single-database-regression-bindings.ps1')
 function Assert-CurrentRegressionDecision {
  if((Get-FileHash -LiteralPath $RequestFile -Algorithm SHA256).Hash -ne $requestSha){throw 'Original regression request changed; no Maven'}
  $at=[DateTimeOffset]::UtcNow
  if([DateTimeOffset]::Parse($request.approval.expiresAt) -le $at -or @($request.approval.sourceRights|Where-Object {[DateTimeOffset]::Parse($_.useUntil) -le $at}).Count){throw 'Regression decision or input rights expired; no Maven'}
 }
 Assert-CurrentRegressionDecision
 $wrapper=Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/Invoke-SafeEnforcementRegressionEnvironment.ps1'
 & $wrapper -InspectionFile $request.inspectionFile -Action {
  Invoke-SingleDatabaseRegressionBindings -Bindings $rbc.environment -Action {
   Assert-CurrentRegressionDecision
   # Historical wrapper loaded approved namespaced credentials; RBC variables are restored in finally.
   & node scripts/preflight-single-database-regression.mjs --environment
   if($LASTEXITCODE -ne 0){throw 'Same-process managed environment incomplete; Maven not started'}
   $started=Get-Date -Format o
   & mvn -f (Join-Path $workspace 'backend/pom.xml') verify ('-Dsurefire.reportNameSuffix='+$request.reportSuffix) *> (Join-Path $output 'backend.log')
   $result=$LASTEXITCODE
   @{exit=$result;startedAt=$started;finishedAt=(Get-Date -Format o);scope='FULL_UNFILTERED_MANAGED_HISTORICAL_FIXTURE_REGRESSION_NOT_FINAL_SINGLE_DB';command='mvn -f backend/pom.xml verify';requestSha256=(Get-FileHash -LiteralPath $RequestFile -Algorithm SHA256).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content -Encoding utf8 -LiteralPath (Join-Path $output 'exit.json')
  }
 }
 if(-not (Test-Path -LiteralPath (Join-Path $output 'exit.json'))){throw 'No execution receipt'}
 $exit=(Get-Content -LiteralPath (Join-Path $output 'exit.json') -Raw|ConvertFrom-Json).exit
} finally {Pop-Location}
exit ([int]$exit)
