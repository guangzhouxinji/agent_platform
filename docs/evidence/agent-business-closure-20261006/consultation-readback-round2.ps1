$ErrorActionPreference='Stop'
$evidence=Join-Path (Get-Location) 'docs/evidence/agent-business-closure-20261006/consultation/round2'
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$prior=Get-Content docs/evidence/consultation-product-bundle-20261006/managed-readback-resources.json -Raw|ConvertFrom-Json
$owned=@();$records=@()
try {
 foreach($d in $prior.databases){
  $c=(docker inspect $d.containerId|ConvertFrom-Json)[0]
  $ports=@($c.HostConfig.PortBindings.'5236/tcp')
  if($c.Id -ne $d.containerId -or $ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or [int]$ports[0].HostPort -ne $d.port){throw 'Original managed scope mismatch'}
  $was=[bool]$c.State.Running
  $records+=[ordered]@{id=$c.Id;port=$d.port;wasRunning=$was;startedThisTurn=(-not $was)}
  if(-not $was){docker start $c.Id|Out-Null;if($LASTEXITCODE -ne 0){throw 'Start failed'};$owned+=$c.Id}
 }
 $records|ConvertTo-Json|Set-Content (Join-Path $evidence 'resource-before.json')
 $vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/consultation-product'
 function Secret($name) {
  $secure=Import-Clixml -LiteralPath (Join-Path $vault $name)
  $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try {[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)} finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 }
 $env:READBACK_PRIMARY_URL='jdbc:dm://127.0.0.1:5301';$env:READBACK_PRIMARY_USER='CONPROD';$env:READBACK_PRIMARY_PASSWORD=Secret 'conprod-schema.credential.xml'
 $env:READBACK_AUTHORITY_URL='jdbc:dm://127.0.0.1:5302';$env:READBACK_AUTHORITY_USER='CONAUTH';$env:READBACK_AUTHORITY_PASSWORD=Secret 'conauth-schema.credential.xml'
 $env:JAVA_HOME='D:/software/jdk-21'
 $cp=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim()
 Copy-Item docs/evidence/consultation-product-bundle-20261006/ConsultationDeploymentReadback.java -Destination (Join-Path $evidence 'ConsultationDeploymentReadback.java')
 & "$env:JAVA_HOME/bin/javac.exe" -cp $cp -d $evidence (Join-Path $evidence 'ConsultationDeploymentReadback.java') *> (Join-Path $evidence 'compile.log')
 if($LASTEXITCODE -ne 0){throw 'Readback compilation failed'}
 $exit=1
 for($i=0;$i -lt 15;$i++) {
  & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$cp" ConsultationDeploymentReadback (Join-Path $evidence 'current-admission.json') *> (Join-Path $evidence "readback-attempt-$i.log")
  $exit=$LASTEXITCODE;if($exit -eq 0){break};Start-Sleep -Seconds 2
 }
 [ordered]@{exit=$exit;observedAt=[DateTimeOffset]::Now.ToString('o');attempts=$i+1}|ConvertTo-Json|Set-Content (Join-Path $evidence 'readback-exit.json')
 if($exit -ne 0){throw 'SELECT readback failed; do not infer admission'}
 [ordered]@{containersVerified=$true;startedIds=$owned;scope='SELECT_ONLY_NO_API_NO_DDL_NO_GRANT'}|ConvertTo-Json|Set-Content (Join-Path $evidence 'resource-scope.json')
}finally {
 foreach($name in @('READBACK_PRIMARY_URL','READBACK_PRIMARY_USER','READBACK_PRIMARY_PASSWORD','READBACK_AUTHORITY_URL','READBACK_AUTHORITY_USER','READBACK_AUTHORITY_PASSWORD')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
 foreach($id in $owned){docker stop $id|Out-Null}
 [ordered]@{stoppedOnlyStartedIds=$owned;observedAt=[DateTimeOffset]::Now.ToString('o')}|ConvertTo-Json|Set-Content (Join-Path $evidence 'resource-restoration-inventory.json')
}
