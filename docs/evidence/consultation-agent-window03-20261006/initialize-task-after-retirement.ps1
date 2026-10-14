$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-window03-20261006/approved-http.ps1'
$maint=New-ProductHttp
try {
 $null=Login-ApprovedMember;Assert-ApprovedTime
 $me=Approved-Send $http GET '/v2/identity/me' $null 'member-before-task-after-conflict'
 if($me.subject -ne '7b389c47-9f5b-4004-ad95-084600b351ef' -or $me.issuer -ne $base){throw 'Exact formal member required'}
 $facts=Get-Content (Join-Path $out 'facts-after-create-conflict.json') -Raw|ConvertFrom-Json
 if($facts.organization.Count -ne 1 -or $facts.organization[0].STATUS -ne 'ACTIVE'){throw 'Current original organization unavailable'}
 $version=[long]$facts.organization[0].VERSION;$epoch=if($facts.createEpoch.Count -eq 0){0}else{[long]$facts.createEpoch[0].AUTHORIZATION_EPOCH}
 $old=@($facts.createGrant);if($old.Count -ne 1 -or $old[0].STATUS -ne 'ACTIVE' -or $old[0].GRANT_ID -ne $facts.createEpoch[0].CURRENT_GRANT_ID -or [DateTimeOffset]::Parse($old[0].EXPIRES_AT) -ge [DateTimeOffset]::Now){throw 'Exact already expired current CREATE required'}
 $revoke=Approved-Send $maint POST "/v2/identity/grants/$($old[0].GRANT_ID)/revoke" @{expectedVersion=[long]$old[0].GRANT_VERSION} 'old-expired-create-grant-retired'
 if($revoke.status -ne 'REVOKED' -or $revoke.epoch -ne $epoch+1){throw 'Exact retirement epoch mismatch'};$epoch=[long]$revoke.epoch
 $create=Approved-Send $maint POST '/v2/identity/grants' @{scope=@{issuer=$base;subject=$me.subject;organization='ORG-CON-PRODUCT';domain='CONSULTATION';taskScope='ORG-CON-PRODUCT';dataScope="ORG-CON-PRODUCT:$version";resourceType='ORGANIZATION';resourceId='ORG-CON-PRODUCT';resourceVersion=$version;action='CREATE';purpose='TASK_WORK'};expiresAt=$cutoff.ToString('o');expectedEpoch=$epoch} 'new-create-grant-after-retirement'
 $task=Approved-Send $http POST '/v2/consultation/tasks' @{organizationId='ORG-CON-PRODUCT';expiresAt=$cutoff.ToString('o');retentionRef='retention-v1.json';retentionVersion=1} 'new-task'
 if(-not $task.taskId -or $task.version -ne 1){throw 'New task exact receipt required'}
 $view=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)" $null 'new-task-read'
 # New task authorization version is 1 under creation D, before any Goal mutation.
 $write=Approved-Send $maint POST '/v2/identity/grants' @{scope=@{issuer=$base;subject=$me.subject;organization=$task.organizationId;domain='CONSULTATION';taskScope=$task.taskId;dataScope=$task.dataScope;resourceType='TASK';resourceId=$task.taskId;resourceVersion=1;action='WRITE';purpose='TASK_WORK'};expiresAt=$cutoff.ToString('o');expectedEpoch=0} 'new-write-grant'
 Write-Output "NEW_FORMAL_TASK=$($task.taskId) oldObjectsNotRenewed=true"
}finally{Save-ApprovedTrace 'task-initialization-retry';$maint.Dispose();$http.Dispose();$password=$null;$approvedToken=$null}
