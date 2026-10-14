$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-browser-window04-20261006/approved-http.ps1'
$resourceCutoff=[DateTimeOffset](Get-Content (Join-Path $out 'authorized-resource-window.json') -Raw|ConvertFrom-Json).taskSourceWorkloadExpiry
$worker=Get-Content (Join-Path $out 'new-worker-registration.json') -Raw|ConvertFrom-Json
$task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
$maint=New-ProductHttp
try {
 $grant=Approved-Send $maint POST "/v2/identity/workloads/$($worker.subjectId)/task-grants" @{taskId=$task.taskId;expiresAt=$resourceCutoff.ToString('o');expectedEpoch=0} 'new-worker-task-grant-after-goal'
 Copy-Item (Join-Path $out 'new-worker-task-grant-after-goal.json') (Join-Path $out 'new-worker-task-grant.json')
 Write-Output "WORKER_TASK_GRANTED=$($grant.grantId) epoch=$($grant.epoch)"
}finally{Save-ApprovedTrace 'worker-grant-after-confirmed-goal';$maint.Dispose();$http.Dispose();$password=$null}
