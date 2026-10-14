$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
. (Join-Path (Get-Location) 'scripts/lib/single-database-enforcement-tls-publish44.ps1')
$a=$PSScriptRoot;$pub="$a/publisher44"
$exec=Read-FinalDeploymentJson "$pub/publication44-actual-result.json";if($exec.exitCode -ne 0){throw 'ACTUAL_TRANSPORT_RESULT_REQUIRED'}
$actual=Read-FinalDeploymentJson "$pub/publication44-actual.log";if(-not $actual.finalPublisherRunning -or $actual.finalPublisherPid -le 0){throw 'ACTUAL_PUBLISHER_FINAL_STATE_REQUIRED'}
$c=Read-FinalDeploymentJson "$pub/publication44-02.json";$p=Get-Enf44LocalTlsPublicationPlan $c ((Get-FileHash "$pub/publication44-02.json").Hash.ToLowerInvariant())
Assert-Enf44LocalTlsWindow $p.approval;$null=Assert-Enf44LocalTlsPublisher $p $actual.publisherId -Running;$null=Assert-Enf44LocalTlsBudget $p $actual.publisherId
$input=Read-FinalDeploymentJson "$a/java-https44-exact-inputs.json";foreach($r in $input.physicalRefs.Values){Assert-FinalFile $r}
foreach($op in $input.operations){
 Assert-FinalFile $op.source;Assert-FinalFile $op.body
 foreach($cls in $op.classes){Assert-FinalFile @{path="$a/https-classes/$($cls.name)";sha256=$cls.sha256}}
 $directory="$pub/https44-$($op.name)";if(Test-Path $directory){throw 'EXISTING_SINGLE_OPERATION_RECONCILE_NO_RETRY'};$null=New-Item -ItemType Directory -Path $directory
 $old=$env:ENFORCEMENT_ORIGINAL_STORE_PASSWORD;$started=[DateTimeOffset]::Now;$code=$null
 try{
  Assert-Enf44LocalTlsWindow $p.approval;$env:ENFORCEMENT_ORIGINAL_STORE_PASSWORD=Read-FinalManagedSecret $input.physicalRefs.credential
  & 'D:/software/jdk-21/bin/java.exe' -cp "$a/https-classes" $op.mainClass $input.physicalRefs.p12.path $input.physicalRefs.ca.path $op.body.path $input.physicalRefs.cert.path $directory *> "$pub/normal-https44-$($op.name).log";$code=$LASTEXITCODE
 }finally{
  $env:ENFORCEMENT_ORIGINAL_STORE_PASSWORD=$old;$old=$null
  @{exitCode=$code;startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');publisherId=$actual.publisherId;source=$op.source;requestBody=$op.body;idempotencyKey=$op.idempotencyKey;retryPerformed=$false;privateKeyCopied=$false;credentialPrinted=$false;scope='single selected client strict original JSSE CA/SAN/pin/mTLS/normal API + registered PKCE, no ordinary login'}|ConvertTo-Json -Depth 8|Set-Content "$pub/normal-https44-$($op.name)-result.json"
 }
 if($code -ne 0){throw 'SINGLE_HTTPS_OPERATION_PARTIAL_RESULT_RECONCILE_NO_RETRY'}
}
