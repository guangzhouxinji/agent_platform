$ErrorActionPreference='Stop'
$root=(Get-Location).Path;$evidence=Join-Path $root 'docs/evidence/agent-business-closure-20261006/full-integrated'
$records=@()
foreach($entry in @(@{file='rbc-run-id.txt';port=5381},@{file='jobpack-run-id.txt';port=5382})) {
 $run=(Get-Content (Join-Path $evidence $entry.file) -Raw).Trim()
 $manifest=Get-Content (Join-Path $root "docs/evidence/enforcement-document-draft-20261002/$run/isolated-resource-manifest.json") -Raw|ConvertFrom-Json
 $c=(docker inspect $manifest.containerId|ConvertFrom-Json)[0];$ports=@($c.HostConfig.PortBindings.'5236/tcp')
 if($c.Id -ne $manifest.containerId -or $c.Config.Labels.'agent-platform.run-id' -ne $run -or $c.Config.Labels.'agent-platform.role' -ne 'enforcement-test' -or $ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or [int]$ports[0].HostPort -ne $entry.port){throw 'Fixture ownership mismatch'}
 $owners=@(docker ps -a --filter "volume=$($manifest.volume)" --format '{{.ID}}');if($owners.Count -ne 1 -or -not $c.Id.StartsWith($owners[0])){throw 'Fixture has multiple owners'}
 $was=[bool]$c.State.Running
 $records+=[ordered]@{id=$c.Id;port=$entry.port;wasRunning=$was;startedThisTurn=(-not $was);runId=$run}
 if(-not $was){docker start $c.Id|Out-Null;if($LASTEXITCODE -ne 0){throw 'Fixture start failed'}}
}
$records|ConvertTo-Json|Set-Content (Join-Path $evidence 'existing-owned-before.json')
Write-Output 'Verified exact owned fixtures, started only prior stopped validation databases.'
