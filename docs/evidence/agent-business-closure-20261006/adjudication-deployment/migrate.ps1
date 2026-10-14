$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path;$evidence=Join-Path $workspace 'docs/evidence/agent-business-closure-20261006/adjudication-deployment'
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1/product-runtime'
$targets=@(@{id='8d9d4abcb1646396716565fd35477a7a921f370cb9a4812efb96c28d28549d52';port=5301;name='agent-platform-con-product-primary'},@{id='4ac68d0d910f37df36eba59ef35c8e0d74512bb7752ad74fb8883cbfc57ed271';port=5370;name='agent-platform-adj-authority-20261004'})
$before=@();$started=@()
foreach($d in $targets){$c=(docker inspect $d.id|ConvertFrom-Json)[0];$ports=@($c.HostConfig.PortBindings.'5236/tcp');if($c.Id -ne $d.id -or $c.Name -ne '/'+$d.name -or $ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or [int]$ports[0].HostPort -ne $d.port){throw 'Original identity mismatch'};$was=[bool]$c.State.Running;$before+=[ordered]@{id=$c.Id;port=$d.port;wasRunning=$was;startedThisDeployment=(-not $was)};if(-not $was){docker start $c.Id|Out-Null;$started+=$c.Id}}
$before|ConvertTo-Json|Set-Content (Join-Path $evidence 'resources-before.json')
try {
 Start-Sleep -Seconds 18
 $env:ADJ_MIGRATION_URL='jdbc:dm://127.0.0.1:5301';$env:ADJ_MIGRATION_USER='ADJLLM20261003'
 $secure=Import-Clixml -LiteralPath (Join-Path (Split-Path -Parent $vault) 'dm8-model-schema.credential.xml');$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try {$env:ADJ_MIGRATION_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 $cp=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim()
 & 'D:/software/jdk-21/bin/javac.exe' -cp $cp -d $evidence (Join-Path $evidence 'AdjudicationRuntimeMigration.java') *> (Join-Path $evidence 'migration-compile.log');if($LASTEXITCODE -ne 0){throw 'Migration compile failed'}
 & 'D:/software/jdk-21/bin/java.exe' -cp "$evidence;$cp" AdjudicationRuntimeMigration (Join-Path $workspace 'db/dm8/V096__agent_runtime_checkpoint.sql') *> (Join-Path $evidence 'migration.log');$exit=$LASTEXITCODE
 [ordered]@{exit=$exit;finishedAt=[DateTimeOffset]::Now.ToString('o');schema='ADJLLM20261003';migration='V096';authorityWrites=0;sourceOrGrantWrites=0}|ConvertTo-Json|Set-Content (Join-Path $evidence 'migration-exit.json')
 if($exit -ne 0){throw 'Migration failed, original log retained; do not overwrite partial state'}
}finally {foreach($name in @('ADJ_MIGRATION_URL','ADJ_MIGRATION_USER','ADJ_MIGRATION_PASSWORD')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}}
