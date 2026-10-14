$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-browser-window04-20261006/approved-http.ps1'
try {
 $null=Login-ApprovedMember
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
 $run=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)/agent-runs/9a15427c-afba-370e-b7ae-a21a4a8d53ff" $null 'post-restart-same-run-read'
 $view=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)/retrieval-workspace" $null 'post-restart-workspace'
 if($run.checkpoint.task.id -ne $task.taskId -or $run.checkpoint.outcome.reason -ne 'COMPLETED' -or $view.dialogue.Count -ne 1 -or $view.goal.queryObject -ne 'soldering blades rotor'){throw 'Same task result mismatch'}
 $hits=@($view.result.members|Where-Object {$_.matchedTerms.Count -eq 3 -and $_.matchFields -contains 'TITLE'})
 [ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');taskId=$task.taskId;runId=$run.checkpoint.runId;goalId=$view.goal.goalId;inputId=$view.goal.inputId;resultSetId=$view.result.resultSetId;resultSha=$view.result.resultSha;turnId=$view.dialogue[0].turnId;approvedMotherSet=40;candidateCount=$view.result.candidateCount;pageCount=$view.result.members.Count;titleAllTermsHits=$hits.Count;checkpointVersion=$run.checkpoint.version;runtimeCompleted=$true;officialBusinessClosed=$false;goalExpiresAt=$view.goal.expiresAt;sourceExpiresAt=$view.sources[0].expiresAt}|ConvertTo-Json|Set-Content (Join-Path $out 'post-restart-same-task-run-result.json')
 Write-Output 'SAME_TASK_RUN_ANSWER_SOURCE_READ_VERIFIED'
}finally{Save-ApprovedTrace 'read-post-restart-result';$http.Dispose();$password=$null;$approvedToken=$null}
