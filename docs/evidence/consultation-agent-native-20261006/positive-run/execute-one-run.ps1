$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-native-20261006/positive-run/approved-http.ps1'
$http.Timeout=[TimeSpan]::FromMinutes(3)
try {
 $null=Login-ApprovedMember
 $admission=Get-Content (Join-Path $out 'current-admission.json') -Raw|ConvertFrom-Json;$taskId=$admission.taskId
 $view=Approved-Send $http GET "/v2/consultation/tasks/$taskId/retrieval-workspace" $null 'before-run-workspace'
 if($view.result -or $view.goal.inputId -ne $admission.inputId -or $view.task.version -ne $admission.taskVersion){throw 'No preexecuted retrieval or different goal allowed'}
 $start=Approved-Send $http POST "/v2/consultation/tasks/$taskId/agent-runs" @{expectedTaskVersion=$admission.taskVersion} 'native-run-start'
 $runId=$start.checkpoint.runId;if(-not $runId -or $start.checkpoint.task.id -ne $taskId){throw 'Formal same task run required'}
 $executed=Approved-Send $http POST "/v2/consultation/tasks/$taskId/agent-runs/$runId/execute" $null 'native-run-execute'
 $read=Approved-Send $http GET "/v2/consultation/tasks/$taskId/agent-runs/$runId" $null 'native-run-read'
 $workspace=Approved-Send $http GET "/v2/consultation/tasks/$taskId/retrieval-workspace" $null 'after-run-workspace'
 if($executed.checkpoint.runId -ne $runId -or $read.checkpoint.runId -ne $runId -or $workspace.task.id -ne $taskId){throw 'Same task run result binding mismatch'}
 $answerRefs=@($read.checkpoint.observations|Where-Object {$_.reference -like 'answer-turn:*'})
 $turn=$null;if($answerRefs.Count -eq 1){$turnId=$answerRefs[0].reference.Substring('answer-turn:'.Length);$turn=Approved-Send $http GET "/v2/consultation/tasks/$taskId/dialogue/$turnId" $null 'native-answer-read'}
 $again=Approved-Send $http POST "/v2/consultation/tasks/$taskId/agent-runs/$runId/execute" $null 'native-run-execute-replay'
 $afterReplay=Approved-Send $http GET "/v2/consultation/tasks/$taskId/retrieval-workspace" $null 'after-replay-workspace'
 if($read.checkpoint.phase -eq 'STOP' -and ($again.checkpoint.version -ne $read.checkpoint.version -or $afterReplay.result.resultSetId -ne $workspace.result.resultSetId -or $afterReplay.result.resultSha -ne $workspace.result.resultSha)){throw 'Terminal replay changed persisted task result'}
 [ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');taskId=$taskId;runId=$runId;inputId=$workspace.goal.inputId;goalId=$workspace.goal.goalId;phase=$read.checkpoint.phase;runtimeOutcome=$read.checkpoint.outcome;resultSetId=$workspace.result.resultSetId;resultSha=$workspace.result.resultSha;candidateCount=$workspace.result.candidateCount;returnedMembers=@($workspace.result.members).Count;citationCount=@($workspace.result.citations).Count;turnId=$turn.turnId;answerNonblank=($turn -and -not[string]::IsNullOrWhiteSpace($turn.output.answer));sameTaskRun=$true;terminalReplayVersionUnchanged=($again.checkpoint.version -eq $read.checkpoint.version);resultReplayUnchanged=($afterReplay.result.resultSha -eq $workspace.result.resultSha);browserE2e=$false;professionalAcceptance=$false;businessTargetAcceptance=$false;coverageGap='Bounded original 40-record corpus; semantic and citation review still separate from Runtime COMPLETED; no professional/full-corpus conclusion.'}|ConvertTo-Json -Depth 30|Set-Content (Join-Path $out 'same-task-run-result.json')
 Write-Output "SAME_TASK_RUN_EXECUTED task=$taskId run=$runId phase=$($read.checkpoint.phase) outcome=$($read.checkpoint.outcome.reason) members=$(@($workspace.result.members).Count) answerNonblank=$($turn -and -not[string]::IsNullOrWhiteSpace($turn.output.answer))"
}finally{Save-ApprovedTrace 'native-run';$http.Dispose();$password=$null;$approvedToken=$null}
