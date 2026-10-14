$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-native-20261006/approved-run/approved-http.ps1'
$maint=New-ProductHttp
try {
 $null=Login-ApprovedMember
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
 $worker=Get-Content (Join-Path $out 'new-worker-registration.json') -Raw|ConvertFrom-Json
 $view=Approved-Send $http GET "/v2/consultation/tasks/$($task.taskId)/retrieval-workspace" $null 'before-goal-workspace'
 $goal=Approved-Send $http POST "/v2/consultation/tasks/$($task.taskId)/goals/confirm" @{expectedTaskVersion=$view.task.version;messageText='在已批准的40篇PatentsView资料范围内检索机器人吸尘器专利。公开非保密，仅辅助检索，不作法律或专业结论；明确披露该样本覆盖限制和证据缺口。';purpose='QUERY';queryObject='robot vacuum';sourceIds=@('PATENTSVIEW_ZENODO_2024');requestedFields=@('TITLE');requiredFeatures=@();confirmed=$true;retentionRef='retention-v1.json';retentionVersion=1;filters=@{publicationFrom=$null;publicationTo=$null;ipcPrefixes=@();cpcPrefixes=@()}} 'new-confirmed-goal'
 $grant=Approved-Send $maint POST "/v2/identity/workloads/$($worker.subjectId)/task-grants" @{taskId=$task.taskId;expiresAt=$cutoff.ToString('o');expectedEpoch=0} 'new-worker-task-grant'
 Write-Output "CONFIRMED_GOAL=$($goal.goalId) input=$($goal.inputId) taskVersion=$($goal.taskVersion) worker=$($worker.subjectId)"
}finally{Save-ApprovedTrace 'goal-worker-initialization';$maint.Dispose();$http.Dispose();$password=$null;$approvedToken=$null}
