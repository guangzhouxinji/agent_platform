$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-native-20261006/approved-run/approved-http.ps1'
$maint=New-ProductHttp
try {
 $null=Login-ApprovedMember
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json;$run=(Get-Content (Join-Path $out 'native-run-read.json') -Raw|ConvertFrom-Json).checkpoint;$taskId=$task.taskId;$runId=$run.runId
 $revoke=Approved-Send $maint POST "/v2/identity/grants/$($task.grantId)/revoke" @{expectedVersion=1} 'new-read-grant-revoke'
 if($revoke.status -ne 'REVOKED' -or $revoke.epoch -ne 2){throw 'Exact current new grant revocation required'}
 $deniedRun=Approved-Send $http GET "/v2/consultation/tasks/$taskId/agent-runs/$runId" $null 'lost-read-run-denied' 404
 $deniedView=Approved-Send $http GET "/v2/consultation/tasks/$taskId/retrieval-workspace" $null 'lost-read-workspace-denied' 404
 $deniedExecute=Approved-Send $http POST "/v2/consultation/tasks/$taskId/agent-runs/$runId/execute" $null 'lost-read-execute-denied' 404
 # Issue another new READ object; never reactivate the revoked one or extend the cutoff.
 $restore=Approved-Send $maint POST '/v2/identity/grants' @{scope=@{issuer=$base;subject='7b389c47-9f5b-4004-ad95-084600b351ef';organization=$task.organizationId;domain='CONSULTATION';taskScope=$taskId;dataScope=$task.dataScope;resourceType='TASK';resourceId=$taskId;resourceVersion=1;action='READ';purpose='TASK_WORK'};expiresAt=$cutoff.ToString('o');expectedEpoch=2} 'another-new-read-grant'
 if($restore.status -ne 'ACTIVE' -or $restore.grantId -eq $task.grantId -or $restore.epoch -ne 3){throw 'No revival of previous Grant allowed'}
 $read=Approved-Send $http GET "/v2/consultation/tasks/$taskId/agent-runs/$runId" $null 'access-restored-run-read'
 if($read.checkpoint.version -ne $run.version){throw 'Denied requests altered checkpoint'}
 $resident=Get-Content (Join-Path $out 'resident-product.json') -Raw|ConvertFrom-Json;$c=(docker inspect $resident.id|ConvertFrom-Json)[0]
 if($LASTEXITCODE -ne 0 -or $c.Id -ne $resident.id -or $c.Name -ne '/agent-platform-consultation-api' -or -not $c.State.Running){throw 'Exact resident restart required'}
 Assert-ApprovedTime;docker restart $resident.id|Out-Null;if($LASTEXITCODE -ne 0){throw 'Controlled restart failed'}
 $started=(docker inspect --format '{{.State.StartedAt}}' $resident.id).Trim();$deadline=[DateTimeOffset]::Now.AddSeconds(55)
 do{$live=(docker inspect $resident.id|ConvertFrom-Json)[0];if(-not $live.State.Running){throw 'Resident exited during restart'};$logs=docker logs --since $started --tail 70 $resident.id 2>&1;if($logs -match 'Started AgentPlatformApplication'){break};Start-Sleep -Milliseconds 500}while([DateTimeOffset]::Now -lt $deadline)
 if(-not($logs -match 'Started AgentPlatformApplication')){throw 'Restart readiness deadline exceeded'}
 $http.Dispose()
 # Normal fresh product login after restart, not retained token injection.
 $handler=[Net.Http.HttpClientHandler]::new();$handler.CookieContainer=[Net.CookieContainer]::new();$handler.UseCookies=$true;$handler.AllowAutoRedirect=$false;$handler.SslProtocols=[Security.Authentication.SslProtocols]::Tls12;$handler.ServerCertificateCustomValidationCallback=[ProductDeploymentTrust]::Callback;$http=[Net.Http.HttpClient]::new($handler)
 $null=Login-ApprovedMember
 $recovered=Approved-Send $http GET "/v2/consultation/tasks/$taskId/agent-runs/$runId" $null 'post-restart-same-run-read'
 $view=Approved-Send $http GET "/v2/consultation/tasks/$taskId/retrieval-workspace" $null 'post-restart-workspace'
 $replay=Approved-Send $http POST "/v2/consultation/tasks/$taskId/agent-runs/$runId/execute" $null 'post-restart-same-run-execute'
 $before=Get-Content (Join-Path $out 'same-task-run-result.json') -Raw|ConvertFrom-Json
 if($recovered.checkpoint.runId -ne $runId -or $recovered.checkpoint.version -ne $run.version -or $replay.checkpoint.version -ne $run.version -or $view.result.resultSha -ne $before.resultSha -or $view.dialogue.Count -ne 1 -or $view.dialogue[0].turnId -ne $before.turnId){throw 'Persistent recovery changed same run/result/answer'}
 [ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');taskId=$taskId;runId=$runId;lostReadRunStatus=404;lostReadWorkspaceStatus=404;lostReadExecuteStatus=404;revokedGrant=$task.grantId;anotherNewReadGrant=$restore.grantId;oldRevokedGrantNotRevived=$true;fixedExpiry=$cutoff.ToString('o');checkpointVersion=$run.version;checkpointVersionUnchanged=$true;resultSha=$view.result.resultSha;turnId=$view.dialogue[0].turnId;serviceRestarted=$resident.id;persistedTerminalRecovery=$true;midActionCrashRecoveryTested=$false;newPkceAfterRestart=$true;noExtraInvocationClaimPendingDatabaseAudit=$true;browserE2e=$false}|ConvertTo-Json -Depth 20|Set-Content (Join-Path $out 'loss-and-recovery-result.json')
 Write-Output "CURRENT_PERMISSION_LOSS_DENIED_AND_SAME_RUN_TERMINAL_RESTART_RECOVERED run=$runId version=$($run.version)"
}finally{Save-ApprovedTrace 'loss-and-recovery';$maint.Dispose();$http.Dispose();$password=$null;$approvedToken=$null}
