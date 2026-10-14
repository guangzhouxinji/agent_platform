param([ValidateSet('read','migrate')][string]$Mode='read')
$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path;$evidence=Join-Path $workspace 'docs/evidence/consultation-agent-window03-20261006';$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/consultation-product'
$targets=@(@{id='8d9d4abcb1646396716565fd35477a7a921f370cb9a4812efb96c28d28549d52';port=5301;name='agent-platform-con-product-primary'},@{id='43ef58d3c34df4a3c8075e8a2efd55fa59a8f1cd4cc97f5a4f8f574bdc1cf888';port=5302;name='agent-platform-con-product-authority'})
$before=@();foreach($d in $targets){$c=(docker inspect $d.id|ConvertFrom-Json)[0];if($LASTEXITCODE -ne 0){throw 'Docker inspect failed'};$ports=@($c.HostConfig.PortBindings.'5236/tcp');if($c.Id -ne $d.id -or $c.Name -ne '/'+$d.name -or $ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or [int]$ports[0].HostPort -ne $d.port){throw 'Exact original database identity/port mismatch'};$before+=[ordered]@{id=$c.Id;port=$d.port;wasRunning=[bool]$c.State.Running;startedThisDeployment=(-not $c.State.Running)};if(-not $c.State.Running){docker start $c.Id|Out-Null;if($LASTEXITCODE -ne 0){throw 'Database start failed'}}}
$beforeFile=Join-Path $evidence 'database-before.json';if(-not(Test-Path -LiteralPath $beforeFile)){$before|ConvertTo-Json|Set-Content -LiteralPath $beforeFile}
Start-Sleep -Seconds 1
function Set-PrivateSecret([string]$Name,[string]$File){if([Environment]::GetEnvironmentVariable($Name,'Process')){throw 'Preserve preexisting variable'};$s=Import-Clixml -LiteralPath (Join-Path $vault $File);$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s);try{[Environment]::SetEnvironmentVariable($Name,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p),'Process')}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
try{
 $env:CON_NATIVE_PRIMARY_URL='jdbc:dm://127.0.0.1:5301';$env:CON_NATIVE_PRIMARY_USER='CONPROD';Set-PrivateSecret 'CON_NATIVE_PRIMARY_PASSWORD' 'conprod-schema.credential.xml'
 $env:CON_NATIVE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5302';$env:CON_NATIVE_AUTHORITY_USER='CONAUTH_SVC';Set-PrivateSecret 'CON_NATIVE_AUTHORITY_PASSWORD' 'conauth-service.credential.xml'
 $cp=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim()
 & 'D:/software/jdk-21/bin/javac.exe' -cp $cp -d $evidence (Join-Path $evidence 'ConsultationNativeMigration.java') (Join-Path $evidence 'ConsultationWindow03ScopeFacts.java') *> (Join-Path $evidence "facts-source-rebind-compile.log");if($LASTEXITCODE -ne 0){throw 'Compile failed'}
 $report=Join-Path $evidence "facts-source-rebind.json";if(Test-Path -LiteralPath $report){throw 'Preserve existing report'}
 & 'D:/software/jdk-21/bin/java.exe' -cp "$evidence;$cp" ConsultationWindow03ScopeFacts $report *> (Join-Path $evidence "facts-source-rebind.log");$exit=$LASTEXITCODE
 [ordered]@{mode=$Mode;exit=$exit;finishedAt=[DateTimeOffset]::Now.ToString('o');primarySchema='CONPROD';authorityWrites=0;originalDomainWrites=0}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence "facts-source-rebind-exit.json")
 if($exit -ne 0){throw 'Readback/migration failed; raw failure retained, no automatic retry of DDL'}
}finally{foreach($n in @('CON_NATIVE_PRIMARY_URL','CON_NATIVE_PRIMARY_USER','CON_NATIVE_PRIMARY_PASSWORD','CON_NATIVE_AUTHORITY_URL','CON_NATIVE_AUTHORITY_USER','CON_NATIVE_AUTHORITY_PASSWORD')){[Environment]::SetEnvironmentVariable($n,$null,'Process')}}


