param()
$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$ev=Join-Path $workspace 'docs/evidence/consultation-workbench-product-feedback-20261008'
$deadline=(Get-Date).AddMinutes(25)
$stabilityFile=Join-Path $ev 'full-regression-rbc18/source-stability.json'
while(-not(Test-Path -LiteralPath $stabilityFile)){if((Get-Date) -gt $deadline){throw 'Bounded wait exceeded: keep current state and raw run, do not mark DONE'};Start-Sleep -Seconds 15}
$exitReceipt=Get-Content (Join-Path $ev 'full-regression-rbc18/full-backend-exit.json') -Raw|ConvertFrom-Json
$stability=Get-Content $stabilityFile -Raw|ConvertFrom-Json
if($exitReceipt.exit -ne 0 -or -not $stability.unchanged){throw 'Full run failed or source drifted: preserve raw evidence, fix before closing'}
$implementation=Get-Content (Join-Path $ev 'verify-implementation-exit.json') -Raw|ConvertFrom-Json
if($implementation.exit -ne 0){throw 'Implementation governance not closed'}
python (Join-Path $ev 'collect-full-evidence18.py') *> (Join-Path $ev 'collect-full18.log')
if($LASTEXITCODE -ne 0){Get-Content (Join-Path $ev 'collect-full18.log');throw 'Evidence collection failed'}
$priorJava=$env:JAVA_HOME;$priorPath=$env:PATH
try{
 $env:JAVA_HOME='D:\software\jdk-21';$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
 $start=Get-Date -Format o
 & mvn -f (Join-Path $workspace 'backend/pom.xml') compile -DskipTests *> (Join-Path $ev 'backend-compile.log')
 $code=$LASTEXITCODE
 @{exit=$code;startedAt=$start;finishedAt=(Get-Date -Format o);command='mvn -f backend/pom.xml compile -DskipTests';newJarCreated=$false}|ConvertTo-Json|Set-Content (Join-Path $ev 'backend-compile-exit.json')
 if($code -ne 0){throw 'Backend engineering build failed'}
}finally{$env:JAVA_HOME=$priorJava;$env:PATH=$priorPath}
$manifest=Get-Content (Join-Path $workspace 'docs/evidence/enforcement-regression-repair-20261007/rbc-prepared-ENFREG-RBC-FULL-20261008-18/resource-manifest.json') -Raw|ConvertFrom-Json
$actual=& docker inspect --format '{{.Id}}|{{.State.Running}}|{{index .Config.Labels "agent-platform.role"}}|{{index .Config.Labels "agent-platform.run-id"}}' $manifest.containerId
if($LASTEXITCODE -ne 0 -or $actual.Trim() -ne ($manifest.containerId+'|true|enforcement-test|ENFREG-RBC-FULL-20261008-18')){throw 'Exact new owned resource check failed; no other resource stopped'}
& docker stop -t 30 $manifest.containerId *> (Join-Path $ev 'rbc18-stop.log')
$stopCode=$LASTEXITCODE
@{exit=$stopCode;containerId=$manifest.containerId;markerPreserved=(Test-Path -LiteralPath (Join-Path $manifest.fileRoot '.enf-rbc-run-started'));volumePreserved=$true;finishedAt=(Get-Date -Format o);deletions=0}|ConvertTo-Json|Set-Content (Join-Path $ev 'rbc18-stop-exit.json')
if($stopCode -ne 0){throw 'Owned RBC18 stop failed; preserve resource and report'}
& docker inspect --format '{{.Id}}|{{.State.Running}}' 2b9ed145accef3b09f10fca0987b1de915f314e3b268541e92f895104ce562ef |Set-Content (Join-Path $ev 'original-service-final.txt')
if($LASTEXITCODE -ne 0){throw 'Current service inspection failed'}
python (Join-Path $ev 'finalize-delivery.py')
if($LASTEXITCODE -ne 0){throw 'Precise delivery evidence/plan synchronization failed'}
$start=Get-Date -Format o
node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $ev 'verify-final.log')
$code=$LASTEXITCODE
@{exit=$code;startedAt=$start;finishedAt=(Get-Date -Format o)}|ConvertTo-Json|Set-Content (Join-Path $ev 'verify-final-exit.json')
Get-Content (Join-Path $ev 'collect-full18.log');Get-Content (Join-Path $ev 'verify-final.log') -Tail 6
exit $code
