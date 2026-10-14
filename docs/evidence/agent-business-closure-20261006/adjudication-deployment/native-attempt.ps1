$ErrorActionPreference='Stop'
$evidence=Join-Path (Get-Location) 'docs/evidence/agent-business-closure-20261006/adjudication-deployment'
. scripts/lib/adjudication-product-http.ps1
$report=[ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');inputNature='EXISTING_APPROVED_DEIDENTIFIED_MATERIAL_CASE_NOT_PROFESSIONAL_GOLDEN';modelEnabled=$false;modelCallsSent=0;grantOrSourceWrites=0;normalOAuthPkce=$false;taskRun=$null}
try {
 $registered=Get-Content "$root/operator-registration.json" -Raw|ConvertFrom-Json
 $password=AdjSecret "$root/operator.credential.xml";$token=AdjLogin $registered.loginName $password;$password=$null
 $report.normalOAuthPkce=$true
 $caseId='adjudication-materials-case-01';$path="/v2/adjudication/case-works/$caseId"
 $head=AdjSend $member 'GET' $path $null $null $token 200
 $report.currentCaseVersion=[long]$head.version;$report.currentInputVersion=[long]$head.inputVersion;$report.readableMaterialCount=@($head.materials|Where-Object {$_.readable}).Count
 $body=@{expectedVersion=[long]$head.version;modelMaterialIds=@()};$key='adj-agent-native-20261006-01'
 [ordered]@{caseId=$caseId;body=$body;idempotencyKey=$key;purpose='NO_MODEL_APPROVED_MATERIAL_REPORT_ONLY'}|ConvertTo-Json -Depth 4|Set-Content "$root/agent-native-request-20261006.json"
 $run=AdjSend $member 'POST' "$path/agent-runs" $body $key $token 200
 $report.taskRun=$run.checkpoint.runId
 $result=AdjSend $member 'POST' "$path/agent-runs/$($run.checkpoint.runId)/execute" @{} $null $token 200
 $report.checkpointVersion=$result.checkpoint.version;$report.phase=$result.checkpoint.phase;$report.outcome=$result.checkpoint.outcome
 $report.observations=@($result.checkpoint.observations)
 $again=AdjSend $member 'GET' "$path/agent-runs/$($run.checkpoint.runId)" $null $null $token 200
 $report.sameRunReadVersionMatches=($again.checkpoint.version -eq $result.checkpoint.version)
 $report.notFullProfessionalAcceptance=$true
}catch {$report.failure=$_.Exception.Message;$report.notFullProfessionalAcceptance=$true}
finally {
 $report.trace=@($trace);$report.finishedAt=[DateTimeOffset]::Now.ToString('o')
 $report|ConvertTo-Json -Depth 9|Set-Content (Join-Path $evidence 'native-task-attempt.json')
 $token=$null;$password=$null;$maintenance.Dispose();$member.Dispose()
}
Write-Output "NORMAL_PKCE=$($report.normalOAuthPkce) PHASE=$($report.phase) FAILURE=$($report.failure)"
