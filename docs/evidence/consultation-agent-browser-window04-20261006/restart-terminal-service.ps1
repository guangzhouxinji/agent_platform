$ErrorActionPreference='Stop'
$e=Join-Path (Get-Location).Path 'docs/evidence/consultation-agent-browser-window04-20261006'
$r=Get-Content (Join-Path $e 'resident-product.json') -Raw|ConvertFrom-Json
$w=Get-Content (Join-Path $e 'authorized-resource-window.json') -Raw|ConvertFrom-Json
if([DateTimeOffset]::Now -ge [DateTimeOffset]$w.taskSourceWorkloadExpiry){throw 'Resource window elapsed; no restart/read continuation'}
$c=(docker inspect $r.id|ConvertFrom-Json)[0]
if($LASTEXITCODE -ne 0 -or $c.Id -ne $r.id -or $c.Name -ne '/agent-platform-consultation-api' -or -not $c.State.Running){throw 'Exact resident restart required'}
$before=$c.State.StartedAt
docker restart $r.id|Out-Null;if($LASTEXITCODE -ne 0){throw 'Controlled restart failed'}
$started=(docker inspect --format '{{.State.StartedAt}}' $r.id).Trim();$deadline=[DateTimeOffset]::Now.AddSeconds(55)
do{$live=(docker inspect $r.id|ConvertFrom-Json)[0];if(-not $live.State.Running){throw 'Resident exited during restart'};$logs=docker logs --since $started --tail 70 $r.id 2>&1;if($logs -match 'Started AgentPlatformApplication'){break};Start-Sleep -Milliseconds 500}while([DateTimeOffset]::Now -lt $deadline)
if(-not($logs -match 'Started AgentPlatformApplication')){throw 'Restart readiness deadline exceeded'}
[ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');id=$r.id;beforeStartedAt=$before;afterStartedAt=$started;readiness=$true;scope='TERMINAL_SERVICE_RESTART_NOT_MODEL_IN_FLIGHT_CRASH';newRunStarted=$false;retrievalRequested=$false;modelRequested=$false}|ConvertTo-Json|Set-Content (Join-Path $e 'terminal-service-restart.json')
Write-Output 'EXACT_TERMINAL_RESIDENT_RESTART_READY_NORMAL_BROWSER_LOGIN_NEXT'
