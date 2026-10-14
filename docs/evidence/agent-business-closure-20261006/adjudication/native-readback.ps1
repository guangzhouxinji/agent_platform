$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$root=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1/product-runtime'
$primary=Get-Content (Join-Path $env:LOCALAPPDATA 'AgentPlatform/consultation-product/container-deployment.json') -Raw|ConvertFrom-Json
$owner=Get-Content (Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01/deployment-initialization.json') -Raw|ConvertFrom-Json
$targets=@(@{id=$primary.databases[0].id;name=$primary.databases[0].name;port='5301';mode='PRIMARY';credential=(Join-Path (Split-Path -Parent $root) 'dm8-model-schema.credential.xml')},@{id=$owner.authorityContainerId;name=$owner.authorityContainer;port='5370';mode='AUTHORITY';credential="$root/authority-service.credential.xml"})
$started=@();$before=@();$results=@()
try {
 foreach($target in $targets){
  if($target.id -notmatch '^[a-f0-9]{64}$'){throw 'Full owned ID required'}
  $raw=& docker inspect --format '{{json .Id}}|{{json .Name}}|{{json .State.Status}}|{{json .HostConfig.PortBindings}}' $target.id
  if($LASTEXITCODE -ne 0){throw 'Exact native database not accessible'}
  $parts=$raw -split '\|';$id=$parts[0]|ConvertFrom-Json;$name=$parts[1]|ConvertFrom-Json;$status=$parts[2]|ConvertFrom-Json;$ports=$parts[3]|ConvertFrom-Json
  if($id -cne $target.id -or $name -cne '/'+$target.name -or $ports.'5236/tcp'.Count -ne 1 -or $ports.'5236/tcp'[0].HostIp -ne '127.0.0.1' -or $ports.'5236/tcp'[0].HostPort -ne $target.port){throw 'Owner identity or port drift'}
  $before+=@{id=$id;name=$name;port=$target.port;status=$status}
  if($status -eq 'exited'){& docker start $id > "$PSScriptRoot/$($target.mode)-start.log";if($LASTEXITCODE -ne 0){throw 'Start failed'};$started+=$id}elseif($status -ne 'running'){throw 'Preserve unsupported state'}
 }
 Start-Sleep -Seconds 18
 foreach($target in $targets){
  $secret=Import-Clixml -LiteralPath $target.credential;$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
  try{$password=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer);$password | & java -cp "$root/boot/BOOT-INF/lib/*" "$PSScriptRoot/NativeAdmission.java" $target.mode *> "$PSScriptRoot/native-$($target.mode.ToLower()).log";$results+=@{mode=$target.mode;exit=$LASTEXITCODE};$password=$null}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
 }
}finally {
 foreach($id in $started){& docker stop $id > "$PSScriptRoot/restore-$($id.Substring(0,12)).log";if($LASTEXITCODE -ne 0){Write-Warning 'Database restoration needs attention'}}
 $after=@();foreach($target in $targets){$state=& docker inspect --format '{{.State.Status}}' $target.id;$after+=@{id=$target.id;status=$state;startedThisProbe=($started -contains $target.id)}}
 @{observedAt=[DateTimeOffset]::UtcNow.ToString('o');before=$before;after=$after;queries=$results;modelApiStarted=$false;workerStarted=$false;authorizationWrites=$false;databaseMutationStatements=0;probeSha256=(Get-FileHash "$PSScriptRoot/NativeAdmission.java").Hash.ToLower()}|ConvertTo-Json -Depth 6|Set-Content "$PSScriptRoot/native-service-state.json" -Encoding utf8
}
if(@($results|Where-Object {$_.exit -ne 0}).Count -gt 0){throw 'SELECT probe failed; inspect retained diagnostics'}
