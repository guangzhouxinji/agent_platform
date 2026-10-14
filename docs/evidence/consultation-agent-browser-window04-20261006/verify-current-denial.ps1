$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-browser-window04-20261006/approved-http.ps1'
try {
 $null=Login-ApprovedMember
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
 $null=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)/agent-runs/9a15427c-afba-370e-b7ae-a21a4a8d53ff" $null 'lost-read-run-denied' 404
 $null=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)/retrieval-workspace" $null 'lost-read-workspace-denied' 404
 Write-Output 'FRESH_SESSION_WITHOUT_READ_DENIED_404'
}finally{Save-ApprovedTrace 'fresh-session-read-denied';$http.Dispose();$password=$null;$approvedToken=$null}
