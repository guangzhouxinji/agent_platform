$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path;$evidence=Join-Path $workspace 'docs/evidence/consultation-agent-native-20261006';$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/consultation-product'
$inputPath=Join-Path $vault 'container-deployment-agent-20261006-01.json';$config=Get-Content -LiteralPath $inputPath -Raw|ConvertFrom-Json -AsHashtable
$runtime='/mnt/docker-desktop-disk/agent-platform-product-files/runtime-consultation-agent-20261006-01';$parent='/mnt/docker-desktop-disk/agent-platform-product-files';$helper='consultation-private-runtime-stage-20261006-01';$newConfig=Join-Path $vault 'container-deployment-agent-linux-20261006-01.json'
if(Test-Path -LiteralPath $newConfig){throw 'Preserve existing staged configuration'}
if(@(docker ps -a --format '{{.Names}}') -contains $helper){throw 'Preserve prior helper'}
$create=@('run','-d','--name',$helper,'--network','none','--read-only','--user','0:0','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','FOWNER','--cap-add','DAC_OVERRIDE','--mount',"type=bind,source=$parent,target=/managed",$config.image,'sh','-c','set -eu; test ! -e /managed/runtime-consultation-agent-20261006-01; umask 077; mkdir /managed/runtime-consultation-agent-20261006-01; mkdir /managed/runtime-consultation-agent-20261006-01/certs /managed/runtime-consultation-agent-20261006-01/models; touch /managed/runtime-consultation-agent-20261006-01/.stage-ready; sleep 1800')
$id=(& docker @create).Trim();if($LASTEXITCODE -ne 0){throw 'Private stage helper failed'}
try{
 for($i=0;$i -lt 20;$i++){docker exec $id test -f /managed/runtime-consultation-agent-20261006-01/.stage-ready 2>$null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Milliseconds 200};if($LASTEXITCODE -ne 0){throw 'Private stage ownership not ready'}
 $manifest=[Collections.Generic.List[string]]::new()
 function Add-File([string]$Source,[string]$Relative){if((Get-Item -LiteralPath $Source).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refuse linked stage original'};$sha=(Get-FileHash -LiteralPath $Source).Hash.ToLowerInvariant();$manifest.Add("$sha  $Relative")}
 docker cp $config.jar "$($id):/managed/runtime-consultation-agent-20261006-01/platform-api.jar"|Out-Null;if($LASTEXITCODE -ne 0){throw 'JAR stage copy failed'};Add-File $config.jar 'platform-api.jar'
 foreach($mount in $config.mounts){
  if($mount.target -notmatch '^/product/(certs|models)/[a-z0-9.-]+$'){throw 'Unexpected exact product mount'}
  $relative=$mount.target.Substring('/product/'.Length);$item=Get-Item -LiteralPath $mount.source;if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refuse linked mount'}
  docker cp $mount.source "$($id):/managed/runtime-consultation-agent-20261006-01/$relative"|Out-Null;if($LASTEXITCODE -ne 0){throw 'Mount stage copy failed'}
  if($item.PSIsContainer){foreach($file in Get-ChildItem -LiteralPath $mount.source -File -Recurse){$suffix=$file.FullName.Substring($item.FullName.TrimEnd('\','/').Length).TrimStart('\','/').Replace('\','/');Add-File $file.FullName ($relative+'/'+$suffix)}}else{Add-File $item.FullName $relative}
  $mount['containerSource']=$runtime+'/'+$relative
 }
 # Only the exact marker created by this helper, inside its verified new runtime root, is removed.
 docker exec $id sh -c 'test -f /managed/runtime-consultation-agent-20261006-01/.stage-ready; rm /managed/runtime-consultation-agent-20261006-01/.stage-ready';if($LASTEXITCODE -ne 0){throw 'Marker completion failed'}
 $manifestPath=Join-Path $vault 'runtime-manifest-agent-20261006-01.sha256';if(Test-Path -LiteralPath $manifestPath){throw 'Preserve previous manifest'}
 [IO.File]::WriteAllText($manifestPath,([string]::Join("`n",@($manifest|Sort-Object))+"`n"),[Text.UTF8Encoding]::new($false))
 docker cp $manifestPath "$($id):/managed/runtime-consultation-agent-20261006-01/runtime-manifest.sha256"|Out-Null;if($LASTEXITCODE -ne 0){throw 'Manifest stage failed'}
 docker exec $id sh -c 'set -eu; cd /managed/runtime-consultation-agent-20261006-01; test "$(find . -type l | wc -l)" -eq 0; sha256sum -c runtime-manifest.sha256 >/dev/null; chown -R 10001:10001 .; find . -type d -exec chmod 500 {} \;; find . -type f -exec chmod 400 {} \;';if($LASTEXITCODE -ne 0){throw 'Private stage digest/permissions failed'}
 $config['runtimeRoot']=$runtime;$config['containerJar']=$runtime+'/platform-api.jar';$config['runtimeManifestSha']=(Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant();$config|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $newConfig
 [ordered]@{stagedAt=[DateTimeOffset]::Now.ToString('o');root=$runtime;artifactFiles=$manifest.Count;manifestSha=$config.runtimeManifestSha;jarSha=$config.jarSha;certificatePasswordsCopied=$false;owner=10001;directoryMode='500';fileMode='400';inProductEvidenceDirectory=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'private-runtime-stage.json')
}finally{docker stop $id|Out-Null}
$current=(docker inspect 'agent-platform-consultation-api'|ConvertFrom-Json)[0];if(-not $current.Id.StartsWith('40f8efdff24f') -or $current.State.Running){throw 'Original stopped product identity changed'}
& (Join-Path $workspace 'scripts/start-consultation-container.ps1') -Configuration $newConfig -ReplaceContainerId $current.Id *> (Join-Path $evidence 'publish-linux.log')
if(-not $?){throw 'Staged controlled publication failed'}
$active=(docker inspect 'agent-platform-consultation-api'|ConvertFrom-Json)[0];if(-not $active.State.Running){throw 'Candidate not resident'}
Copy-Item -LiteralPath $newConfig -Destination (Join-Path $vault 'container-deployment.json')
[ordered]@{publishedAt=[DateTimeOffset]::Now.ToString('o');id=$active.Id;name=$active.Name;running=[bool]$active.State.Running;port=5443;jarSha=$config.jarSha;runtimeEnabled=$true;privateImmutableRuntimeRoot=$runtime;currentPointerUpdated=$true;currentTaskGrantCreated=$false;currentSourceDecisionCreated=$false;existingWorkerExpiresNotChanged=$true}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'resident-product.json')
Write-Output "CONSULTATION_NATIVE_PUBLISHED=$($active.Id) port=5443"
