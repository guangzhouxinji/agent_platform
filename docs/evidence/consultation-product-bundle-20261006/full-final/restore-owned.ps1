$ErrorActionPreference='Stop'
$root=(Resolve-Path '.').Path;$base=Join-Path $root 'docs/evidence/consultation-product-bundle-20261006/full-final'
if(-not (Test-Path -LiteralPath (Join-Path $base 'full-round02-exit.json'))){throw 'Full Maven command has not finished: do not stop verification DBs'}
if(Test-Path -LiteralPath (Join-Path $base 'resource-restoration.json')){throw 'Do not overwrite restoration evidence'}
$requests=@()
foreach($name in @('rbc-run-id.txt','jobpack-run-id.txt')){
 $id=(Get-Content (Join-Path $base $name) -Raw).Trim()
 $state=Get-Content (Join-Path $base ($name+'.state.json')) -Raw|ConvertFrom-Json
 $requests+=@{runId=$id;wasRunning=$state.wasRunning;startedThisTurn=$state.startedThisTurn;expectedId=$state.containerId;port=$state.port}
}
$id=(Get-Content (Join-Path $base 'full-run-id.txt') -Raw).Trim()
$m=Get-Content (Join-Path $root "docs/evidence/enforcement-document-draft-20261002/$id/isolated-resource-manifest.json") -Raw|ConvertFrom-Json
$requests+=@{runId=$id;wasRunning=$false;startedThisTurn=$true;expectedId=$m.containerId;port=5388}
$validated=@()
foreach($r in $requests){
 $m=Get-Content (Join-Path $root "docs/evidence/enforcement-document-draft-20261002/$($r.runId)/isolated-resource-manifest.json") -Raw|ConvertFrom-Json
 $c=(docker inspect $m.container|ConvertFrom-Json)[0]
 if($LASTEXITCODE -ne 0 -or $c.Id -ne $r.expectedId -or $c.Id -ne $m.containerId -or $c.Config.Labels.'agent-platform.run-id' -ne $r.runId -or $c.Config.Labels.'agent-platform.role' -ne 'enforcement-test'){throw 'Exact owned ID/label mismatch'}
 $p=@($c.HostConfig.PortBindings.'5236/tcp');if($p.Count -ne 1 -or $p[0].HostIp -ne '127.0.0.1' -or [int]$p[0].HostPort -ne $r.port){throw 'Owned loopback/port mismatch'}
 $owners=@(docker ps -a --filter "volume=$($m.volume)" --format '{{.ID}}');if($owners.Count -ne 1 -or -not $c.Id.StartsWith($owners[0])){throw 'Owned volume owner mismatch'}
 $validated+=@{request=$r;manifest=$m}
}
$results=@()
foreach($v in $validated){
 $r=$v.request;$m=$v.manifest
 if($r.startedThisTurn -and -not $r.wasRunning){docker stop $m.containerId|Out-Null;if($LASTEXITCODE -ne 0){throw 'Owned validation DB stop failed'}}
 $after=(docker inspect $m.containerId|ConvertFrom-Json)[0]
 if([bool]$after.State.Running -ne [bool]$r.wasRunning){throw 'Verification DB state restoration mismatch'}
 $results+=@{container=$m.container;containerId=$m.containerId;port=$r.port;wasRunning=$r.wasRunning;isRunning=$after.State.Running;volumeRetained=$true;credentialsRetainedOutsideRepository=$true}
}
@{restoredAt=[DateTimeOffset]::Now.ToString('o');scope='Only exact execution-owned isolated DBs; original product services not changed';databases=$results}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $base 'resource-restoration.json') -Encoding utf8NoBOM
Write-Output 'Three exact owned regression DBs restored to stopped state; volumes retained'
