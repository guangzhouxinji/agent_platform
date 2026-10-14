$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-browser-window04-20261006/approved-http.ps1'
$maint=New-ProductHttp
try {
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
 $revoke=Approved-Send $maint POST "/v2/identity/grants/$($task.grantId)/revoke" @{expectedVersion=1} 'new-read-grant-revoke'
 if($revoke.status -ne 'REVOKED' -or $revoke.epoch -ne 2){throw 'Exact new READ revoke mismatch'}
 Write-Output 'NEW_TASK_READ_REVOKED_BROWSER_DENIAL_NEXT'
}finally{Save-ApprovedTrace 'revoke-new-read';$maint.Dispose();$http.Dispose();$password=$null}
