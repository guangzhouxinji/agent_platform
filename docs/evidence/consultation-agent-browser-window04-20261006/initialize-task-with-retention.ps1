$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-browser-window04-20261006/approved-http.ps1'
$maint=New-ProductHttp
try {
 $null=Login-ApprovedMember;Assert-ApprovedTime
 $me=Approved-Send $http GET '/v2/identity/me' $null 'member-before-retention-bounded-task'
 $cfg=Get-Content (Join-Path $vault 'container-deployment.json') -Raw|ConvertFrom-Json -AsHashtable
 $taskAge=[System.Xml.XmlConvert]::ToTimeSpan($cfg.settings.PRODUCT_CONSULTATION_RETENTION_MAX_AGE)
 $sourceAge=[System.Xml.XmlConvert]::ToTimeSpan($cfg.settings.PRODUCT_SOURCE_REGISTRY_RETENTION_MAX_AGE)
 $age=if($taskAge -le $sourceAge){$taskAge}else{$sourceAge}
 $until=[DateTimeOffset]::Now.Add($age).AddSeconds(-30);if($until -gt $cutoff){$until=$cutoff}
 if($until -le [DateTimeOffset]::Now){throw 'No approved retention window'}
 $creates=@($me.grants|Where-Object {$_.resourceType -eq 'ORGANIZATION' -and $_.resourceId -eq 'ORG-CON-PRODUCT' -and $_.action -eq 'CREATE' -and [DateTimeOffset]$_.expiresAt -gt [DateTimeOffset]::Now})
 if($creates.Count -eq 1 -and [DateTimeOffset]$creates[0].expiresAt -lt $until){$until=[DateTimeOffset]$creates[0].expiresAt}
 if($creates.Count -ne 1){throw 'Exact current CREATE required'}
 [ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');approvedCutoff=$cutoff.ToString('o');taskSourceWorkloadExpiry=$until.ToString('o');taskRetentionMaxAge=$cfg.settings.PRODUCT_CONSULTATION_RETENTION_MAX_AGE;sourceRetentionMaxAge=$cfg.settings.PRODUCT_SOURCE_REGISTRY_RETENTION_MAX_AGE;goalRetentionMaxAge=$cfg.settings.PRODUCT_CONSULTATION_GOAL_RETENTION_MAX_AGE;resultsRetentionMaxAge=$cfg.settings.PRODUCT_SOURCE_RESULTS_RETENTION_MAX_AGE;existingCurrentNewCreateGrant=$creates[0].grantId;noRetentionConfigurationChanged=$true}|ConvertTo-Json|Set-Content (Join-Path $out 'authorized-resource-window.json')
 $task=Approved-Send $http POST '/v2/consultation/tasks' @{organizationId='ORG-CON-PRODUCT';expiresAt=$until.ToString('o');retentionRef='retention-v1.json';retentionVersion=1} 'new-task-retention-bounded'
 if(-not $task.taskId -or $task.version -ne 1){throw 'Exact Task receipt required'}
 Copy-Item -LiteralPath (Join-Path $out 'new-task-retention-bounded.json') -Destination (Join-Path $out 'new-task.json')
 $view=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)" $null 'new-task-read'
 $write=Approved-Send $maint POST '/v2/identity/grants' @{scope=@{issuer=$base;subject=$me.subject;organization=$task.organizationId;domain='CONSULTATION';taskScope=$task.taskId;dataScope=$task.dataScope;resourceType='TASK';resourceId=$task.taskId;resourceVersion=1;action='WRITE';purpose='TASK_WORK'};expiresAt=$until.ToString('o');expectedEpoch=0} 'new-write-grant'
 Write-Output "NEW_RETENTION_BOUNDED_TASK=$($task.taskId) expires=$($until.ToString('o')) oldObjectsNotRenewed=true"
}finally{Save-ApprovedTrace 'task-retention-bounded-initialization';$maint.Dispose();$http.Dispose();$password=$null;$approvedToken=$null}
