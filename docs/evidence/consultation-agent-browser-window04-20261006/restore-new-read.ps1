$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-browser-window04-20261006/approved-http.ps1'
$resourceCutoff=[DateTimeOffset](Get-Content (Join-Path $out 'authorized-resource-window.json') -Raw|ConvertFrom-Json).taskSourceWorkloadExpiry
$maint=New-ProductHttp
try {
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
 $restore=Approved-Send $maint POST '/v2/identity/grants' @{scope=@{issuer=$base;subject='7b389c47-9f5b-4004-ad95-084600b351ef';organization=$task.organizationId;domain='CONSULTATION';taskScope=$task.taskId;dataScope=$task.dataScope;resourceType='TASK';resourceId=$task.taskId;resourceVersion=1;action='READ';purpose='TASK_WORK'};expiresAt=$resourceCutoff.ToString('o');expectedEpoch=2} 'another-new-read-grant'
 if($restore.status -ne 'ACTIVE' -or $restore.grantId -eq $task.grantId -or $restore.epoch -ne 3){throw 'No revival of previous Grant allowed'}
 Write-Output 'ANOTHER_NEW_READ_GRANTED_ORIGINAL_STAYS_REVOKED'
}finally{Save-ApprovedTrace 'restore-new-read';$maint.Dispose();$http.Dispose();$password=$null}
