$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$a=$PSScriptRoot;$e=Split-Path -Parent $a
$c=Read-FinalDeploymentJson "$a/source-snapshot-config46.json"
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields
$r=Read-FinalDeploymentJson "$a/current-resource-writer-discovery46-02.json"
$events="$a/source-protection-execution46.events.jsonl"
if(Test-Path -LiteralPath $events){throw 'NEW_EXECUTION_OUTPUT_REQUIRED'}
$null=[IO.File]::Open($events,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read).Dispose()
$inspect={param($id) @(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable -DateKind String)[0]}
function Record-Event($kind,$data){@{time=[DateTimeOffset]::Now.ToString('o');kind=$kind;data=$data}|ConvertTo-Json -Depth 12 -Compress|Add-Content -LiteralPath $events}
function Save-New($name,$data){$p=Join-Path $a $name;$f=[IO.File]::Open($p,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read);try{$b=[Text.UTF8Encoding]::new($false).GetBytes(($data|ConvertTo-Json -Depth 15)+"`n");$f.Write($b,0,$b.Length)}finally{$f.Dispose()}}
function Current-Admission { $null=Assert-FinalApproval $ready;if([DateTimeOffset]::UtcNow -ge [DateTimeOffset]::Parse($c.approval.expiresAt)){throw 'SOURCE_WINDOW_EXPIRED'} }
$stopped=[Collections.Generic.List[string]]::new();$resumed=[Collections.Generic.List[object]]::new();$failure=$null;$snapshotExit=$null;$releaseConfirmed=$false;$start=[DateTimeOffset]::Now
try{
 Current-Admission
 $db=& $inspect $c.targetId
 if($db.Id -cne $c.targetId -or -not $db.State.Running -or $db.State.Restarting){throw 'EXACT_LIVE_SOURCE_REQUIRED'}
 foreach($w in $r.actualDomainWriters){
  $v=& $inspect $w.id;$orig=@($r.records|Where-Object id -CEQ $w.id)[0]
  if(-not $v.State.Running -or $v.State.Restarting -or $v.State.Pid -ne $orig.state.Pid -or $v.State.StartedAt -cne $orig.state.StartedAt -or $v.Image -cne $orig.image -or $v.Config.Labels.'agent-platform.domain' -cne $w.domain -or $v.HostConfig.RestartPolicy.Name -cne 'no'){throw 'SOURCE_WRITER_BASELINE_DRIFT'}
 }
 Record-Event 'PRE_STOP_EXACT_BASELINE_CONFIRMED' @{source=$c.targetId;writers=$r.actualDomainWriters.id;oldEnvironmentTouched=$false;nativeAttempts=0}
 foreach($w in $r.actualDomainWriters){
  Current-Admission
  Record-Event 'EXACT_WRITER_STOP_REQUESTED' @{id=$w.id;domain=$w.domain}
  $stopError=$null;try{$output=Invoke-FinalDocker @('stop','--time','30',$w.id)}catch{$stopError=$_.Exception.Message}
  $v=& $inspect $w.id
  if($v.State.Running -or $v.State.Restarting -or $v.State.Pid -ne 0){Record-Event 'WRITER_STOP_NOT_CONFIRMED' @{id=$w.id;running=$v.State.Running;pid=$v.State.Pid;unknownResultPreserved=$true};throw 'ACTUAL_STOPPED_WRITER_REQUIRED'}
  $stopped.Add($w.id);Record-Event 'EXACT_WRITER_STOP_CONFIRMED' @{id=$w.id;running=$false;pid=0;finishedAt=$v.State.FinishedAt;exitCode=$v.State.ExitCode;commandError=$stopError}
 }
 $proof=@();foreach($id in $stopped){$v=& $inspect $id;if($v.State.Running -or $v.State.Restarting -or $v.State.Pid -ne 0){throw 'STOPPED_WRITER_DRIFT'};$proof+=@{id=$id;running=$false;pid=0;finishedAt=$v.State.FinishedAt;image=$v.Image}}
 Save-New 'source-actual-stopped-writers46.json' @{capturedAt=[DateTimeOffset]::Now.ToString('o');targetId=$c.targetId;writers=$proof;allRegisteredApplicationsActuallyStopped=$true;maintenanceResponsibility=$c.approval.maintenanceExclusivityEvidence;fullFenceStillMustBeAcquired=$true}
 Current-Admission
 $bindings=Read-FinalDeploymentJson "$e/mainline-protection-consultation45/managed-protection-secret-bindings45.json"
 $admin=(Read-FinalDeploymentJson "$a/current-full-inventory46-result.json").credentialReference
 $references=@{SINGLE_DB_ADMIN_PASSWORD=$admin};foreach($k in $bindings.bindings.Keys){$references[$k]=$bindings.bindings[$k]}
 $saved=@{}
 try{
  foreach($k in $references.Keys){$saved[$k]=[Environment]::GetEnvironmentVariable($k,'Process');[Environment]::SetEnvironmentVariable($k,(Read-FinalManagedSecret $references[$k]),'Process')}
  Record-Event 'SINGLE_SNAPSHOT_CLI_STARTED' @{targetId=$c.targetId;configuration='source-snapshot-config46.json';keyValuesSaved=$false;nativeAttemptsMaximum=1}
  & node scripts/snapshot-single-database-lifecycle.mjs "$a/source-snapshot-config46.json" "$a/source-snapshot-receipt46.json" *> "$a/source-snapshot46.raw.log"
  $snapshotExit=$LASTEXITCODE
  Record-Event 'SNAPSHOT_CLI_TERMINATED' @{exitCode=$snapshotExit;receiptExists=(Test-Path -LiteralPath "$a/source-snapshot-receipt46.json");packageExists=(Test-Path -LiteralPath $c.packageOutput);noRetry=$true}
 }finally{foreach($k in $saved.Keys){[Environment]::SetEnvironmentVariable($k,$saved[$k],'Process')};$saved=$null;$references=$null;$admin=$null}
 if($snapshotExit -ne 0){throw 'SOURCE_SNAPSHOT_CLI_FAILED_SCENE_PRESERVED'}
}catch{$failure=$_.Exception.Message;Record-Event 'SOURCE_PHASE_FAILURE' @{code=$failure;nativeRetryPerformed=$false;scenePreserved=$true}}
finally{
 try{
  $top=Invoke-FinalDocker @('top',$c.native.fence.dockerFence.containerId,'-eo','pid,user,comm')
  if((Test-Path -LiteralPath $c.native.maintenanceLock) -or ($top -match '(?m)\bjava\s*$')){throw 'SOURCE_FENCE_RELEASE_NOT_CONFIRMED'}
  $releaseConfirmed=$true;Record-Event 'FENCE_RELEASE_AND_TOOL_QUIESCENCE_CONFIRMED' @{lockAbsent=$true;activeJavaMaintenance=$false}
  foreach($id in $stopped){
   Current-Admission;$v=& $inspect $id
   if($v.State.Restarting){throw 'SOURCE_RESTART_STATE_UNKNOWN'}
   if(-not $v.State.Running){Record-Event 'ORIGINAL_WRITER_RESUME_REQUESTED' @{id=$id};$err=$null;try{$null=Invoke-FinalDocker @('start',$id)}catch{$err=$_.Exception.Message};$v=& $inspect $id;if(-not $v.State.Running -or $v.State.Restarting -or $v.State.Pid -le 0){throw 'SOURCE_APPLICATION_RETURN_UNCONFIRMED'}}
   $orig=@($r.records|Where-Object id -CEQ $id)[0];if($v.Image -cne $orig.image){throw 'SOURCE_APPLICATION_IMAGE_DRIFT'}
   $item=@{id=$id;running=$v.State.Running;pid=$v.State.Pid;startedAt=$v.State.StartedAt;image=$v.Image;originalImageUnchanged=$true};$resumed.Add($item);Record-Event 'ORIGINAL_WRITER_RUNNING_CONFIRMED' $item
  }
 }catch{$returnFailure=$_.Exception.Message;if(-not $failure){$failure=$returnFailure};Record-Event 'SOURCE_RETURN_FAILURE' @{code=$returnFailure;unknownResultsNotRetried=$true;scenePreserved=$true}}
 Save-New 'source-protection-execution46-result.json' @{format='ACTUAL_SOURCE_NATIVE_PROTECTION_EXECUTION/v1';startedAt=$start.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');targetId=$c.targetId;snapshotExitCode=$snapshotExit;failure=$failure;stoppedIds=@($stopped);returnedApplications=@($resumed);fenceReleaseConfirmed=$releaseConfirmed;sourceDatabaseStopped=$false;restoreStarted=$false;oldEnvironmentTouched=$false;sourceOperationSucceeded=($snapshotExit -eq 0 -and -not $failure);strictTlsAndPostInventoryStillPending=$true;modelCalls=0;calculationCalls=0;matrixCalls=0}
}
if($failure){throw $failure}
