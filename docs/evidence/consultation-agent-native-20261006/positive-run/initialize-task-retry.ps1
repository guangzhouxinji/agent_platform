$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-native-20261006/positive-run/approved-http.ps1'
$maint=New-ProductHttp
try {
 $null=Login-ApprovedMember;Assert-ApprovedTime
 $me=Approved-Send $http GET '/v2/identity/me' $null 'member-before-task-retry2'
 if($me.subject -ne '7b389c47-9f5b-4004-ad95-084600b351ef' -or $me.issuer -ne $base){throw 'Exact formal member required'}
 $facts=Get-Content (Join-Path $out 'facts-before-initialization.json') -Raw|ConvertFrom-Json
 if($facts.organization.Count -ne 1 -or $facts.organization[0].STATUS -ne 'ACTIVE'){throw 'Current original organization unavailable'}
 $version=[long]$facts.organization[0].VERSION;$epoch=if($facts.createEpoch.Count -eq 0){0}else{[long]$facts.createEpoch[0].AUTHORIZATION_EPOCH}
 $currentCreates=@($me.grants|Where-Object {$_.resourceType -eq 'ORGANIZATION' -and $_.resourceId -eq 'ORG-CON-PRODUCT' -and $_.action -eq 'CREATE' -and $_.resourceVersion -eq $version -and [DateTimeOffset]$_.expiresAt -ge $cutoff})
 if($currentCreates.Count -ne 1){throw 'One existing current organization CREATE Grant covering new approved task required; no renewal'}
 $task=Approved-Send $http POST '/v2/consultation/tasks' @{organizationId='ORG-CON-PRODUCT';expiresAt=$cutoff.ToString('o');retentionRef='retention-v1.json';retentionVersion=1} 'new-task'
 if(-not $task.taskId -or $task.version -ne 1){throw 'New task exact receipt required'}
 $view=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)" $null 'new-task-read'
 # New task authorization version is 1 under creation D, before any Goal mutation.
 $write=Approved-Send $maint POST '/v2/identity/grants' @{scope=@{issuer=$base;subject=$me.subject;organization=$task.organizationId;domain='CONSULTATION';taskScope=$task.taskId;dataScope=$task.dataScope;resourceType='TASK';resourceId=$task.taskId;resourceVersion=1;action='WRITE';purpose='TASK_WORK'};expiresAt=$cutoff.ToString('o');expectedEpoch=0} 'new-write-grant'
 Write-Output "NEW_FORMAL_TASK=$($task.taskId) oldObjectsNotRenewed=true"
}finally{Save-ApprovedTrace 'task-initialization-retry2';$maint.Dispose();$http.Dispose();$password=$null;$approvedToken=$null}
