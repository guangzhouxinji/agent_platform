param([switch]$WithdrawSource)
$ErrorActionPreference='Stop';$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$e=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01'
. (Join-Path $PSScriptRoot 'lib/adjudication-product-http.ps1')
function Sha([string]$s){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($s))).ToLower()}
function Audit([string]$name){$old1=$env:ADJ_PRODUCT_AUDIT_PASSWORD;$old2=$env:ADJ_PRODUCT_AUDIT_AUTHORITY_PASSWORD;$old3=$env:ADJ_PRODUCT_AUDIT_EXPECT_CALLS;try{$env:ADJ_PRODUCT_AUDIT_PASSWORD=AdjSecret (Join-Path (Split-Path -Parent $root) 'dm8-model-schema.credential.xml');$env:ADJ_PRODUCT_AUDIT_AUTHORITY_PASSWORD=AdjSecret "$root/authority-owner.credential.xml";$env:ADJ_PRODUCT_AUDIT_EXPECT_CALLS='2';& "$env:JAVA_HOME/bin/java.exe" -cp "$root/boot/BOOT-INF/classes;$root/boot/BOOT-INF/lib/*" (Join-Path $PSScriptRoot 'java/AuditAdjudicationProductRuntime.java') "$e/$name" *> "$e/audit-$name.log";if($LASTEXITCODE -ne 0){throw 'Persistent allowance changed unexpectedly; stop'}}finally{$env:ADJ_PRODUCT_AUDIT_PASSWORD=$old1;$env:ADJ_PRODUCT_AUDIT_AUTHORITY_PASSWORD=$old2;$env:ADJ_PRODUCT_AUDIT_EXPECT_CALLS=$old3}}
try{
 $op=Get-Content "$root/operator-registration.json" -Raw|ConvertFrom-Json;$token=AdjLogin $op.loginName (AdjSecret "$root/operator.credential.xml");$run=Get-Content "$root/http-model-run.json" -Raw|ConvertFrom-Json
 $path="/v2/adjudication/case-works/$($run.caseId)";$modelPath="$path/material-organization/runs";$viewPath="$modelPath/$($run.runId)"
 $view=AdjSend $member 'GET' $viewPath $null $null $token 200;if((Sha ($view|ConvertTo-Json -Depth 30 -Compress)) -ne $run.candidateViewSha256){throw 'Same case/run changed across actual service restart'}
 $candidateSha=Sha ($view.candidates|ConvertTo-Json -Depth 30 -Compress)
 $body=@{expectedVersion=[long]$run.expectedVersion;materialIds=@('material-1','material-2')}
 AdjSend $member 'POST' $modelPath $body 'adj-cookie-no-bearer-20261004-01' $null 403|Out-Null
 $anonymous=AdjClient $false;try{AdjSend $anonymous 'POST' $modelPath $body 'adj-no-jwt-20261004-01' $null 401|Out-Null}finally{$anonymous.Dispose()}
 AdjSend $member 'POST' $modelPath @{expectedVersion=$run.workVersion;materialIds=@('material-1','material-2');model='unapproved-model'} 'adj-override-model-20261004-01' $token 400|Out-Null
 AdjSend $member 'GET' "/v2/adjudication/case-works/unapproved-case/material-organization/runs/$($run.runId)" $null $null $token 404|Out-Null
 AdjSend $member 'POST' $modelPath $body 'adj-stale-head-20261004-01' $token 409|Out-Null
 $replay=AdjSend $member 'POST' $modelPath $body $run.requestKey $token 200;if($replay.runId -ne $run.runId){throw 'Idempotent request reused provider or changed artifact'}
 $grants=Get-Content "$root/case-grants.json" -Raw|ConvertFrom-Json;$modelGrant=@($grants|Where-Object {$_.action -eq 'MODEL_EXECUTE'})[0]
 $revoked=AdjSend $maintenance 'POST' "/v2/adjudication/grants/$($modelGrant.receipt.grantId)/revoke" @{expectedVersion=$modelGrant.receipt.grantVersion} 'adj-revoke-model-20261004-01' $null 200
 AdjSend $member 'GET' $viewPath $null $null $token 403|Out-Null
 AdjSend $member 'POST' $modelPath @{expectedVersion=$run.workVersion;materialIds=@('material-1','material-2')} 'adj-revoked-execute-20261004-01' $token 403|Out-Null
 AdjSend $member 'GET' $path $null $null $token 200|Out-Null
 $newGrant=AdjSend $maintenance 'POST' '/v2/adjudication/grants' @{scope=$modelGrant.scope;expiresAt='2026-11-01T16:00:00Z';expectedEpoch=$revoked.epoch} 'adj-reissue-model-20261004-01' $null 200
 $modelGrant.receipt=$newGrant;$grants|ConvertTo-Json -Depth 12|Set-Content "$root/case-grants.json" -Encoding utf8
 $restored=AdjSend $member 'GET' $viewPath $null $null $token 200;if((Sha ($restored.candidates|ConvertTo-Json -Depth 30 -Compress)) -ne $candidateSha){throw 'Current reauthorization changed original candidates'}
 $head=AdjSend $member 'GET' $path $null $null $token 200
 $first=@($head.materials|Where-Object {$_.id -eq 'material-1'})[0];$newSource=@{id=$first.source.id;version='v2';locator=$first.source.locator;sha256=$first.source.sha256;kind=$first.source.kind}
 # Source version change preserves the original approved bytes; MODEL is deliberately not granted to the new version.
 $sourceUse=@{scope=@{caseId=$run.caseId;organization='project-adjudication';dataScope='adjudication-materials-case-01-materials'};source=$newSource;allowedUses=@('READ','SAVE','ANALYZE');validUntil='2026-11-01T16:00:00Z';evidenceRef='ADJ-SUBJECT-SELECTION-USER-20261004-01_SOURCE_VERSION_VALIDATION_SAME_APPROVED_BYTES';evidenceSha256=(Sha ([IO.File]::ReadAllText("$e/user-approval.json")))}
 $newSourceReceipt=AdjSend $maintenance 'POST' '/v2/adjudication/source-uses' $sourceUse $null $null 200
 $material=@{id='material-1-v2';caseId=$run.caseId;side=$first.side;submitter=$first.submitter;content=$first.content;assertedPurpose=$first.assertedPurpose;source=$newSource;readable=$true;supersedesMaterialId='material-1'}
 AdjSend $member 'POST' "$path/materials" @{expectedVersion=$head.version;material=$material} 'adj-material-version-20261004-01' $token 200|Out-Null
 $current=AdjSend $member 'GET' $path $null $null $token 200
 $stale=AdjSend $member 'GET' $viewPath $null $null $token 200;if(-not $stale.stale -or (Sha ($stale.candidates|ConvertTo-Json -Depth 30 -Compress)) -ne $candidateSha){throw 'Version change did not preserve and invalidate the old result correctly'}
 AdjSend $member 'POST' $modelPath @{expectedVersion=$head.version;materialIds=@('material-1','material-2')} 'adj-cas-conflict-20261004-01' $token 409|Out-Null
 AdjSend $member 'POST' $modelPath @{expectedVersion=$current.version;materialIds=@('material-1-v2','material-2')} 'adj-new-source-no-model-20261004-01' $token 403|Out-Null
 $sourceWithdrawn=$false
 if($WithdrawSource){$sources=Get-Content "$root/source-receipts.json" -Raw|ConvertFrom-Json;$s=@($sources|Where-Object {$_.source.id -eq 'material-2'})[0];AdjSend $maintenance 'POST' "/v2/adjudication/source-uses/$($s.receipt.sourceKey)/revoke" @{expectedVersion=$s.receipt.version} $null $null 200|Out-Null;AdjSend $member 'GET' $viewPath $null $null $token 403|Out-Null;AdjSend $member 'GET' $path $null $null $token 403|Out-Null;$sourceWithdrawn=$true}
 Audit 'actual-runtime-after-negatives.json'
 $after=Get-Content "$e/actual-runtime-after-negatives.json" -Raw|ConvertFrom-Json;$before=Get-Content "$e/actual-runtime-after-model.json" -Raw|ConvertFrom-Json
 $b=@($before.modelRuns|Where-Object {$_.RUN_ID -eq $run.runId})[0];$a=@($after.modelRuns|Where-Object {$_.RUN_ID -eq $run.runId})[0];if($a.PAYLOAD_SHA -ne $b.PAYLOAD_SHA -or $a.STATE -ne 'COMMITTED' -or $after.modelRuns.Count -ne $before.modelRuns.Count){throw 'Failures overwrote or appended model artifacts'}
 @{scope='ACTUAL_TLS_OAUTH_API_DM8_SAME_CASE_NEGATIVES_NO_ADDITIONAL_PROVIDER_REQUEST';passed=$true;runId=$run.runId;caseId=$run.caseId;serviceRestartReadIdentical=$true;anonymousNoJwt401=$true;webSessionWithoutBearer403=$true;clientModelOverride400=$true;crossCase404=$true;oldVersion409=$true;idempotentSameRun=$true;currentModelGrantRevoked403=$true;normalGrantReissueAndReadRecovered=$true;sourceVersionChangeOldRunStale=$true;newVersionWithoutModel403=$true;sourceRevokedRead403=$sourceWithdrawn;failureDidNotOverwriteCipher=$true;cumulativeCalls=2;newProviderCalls=0;currentWorkVersion=$current.version;currentInputVersion=$current.inputVersion;currentSourceWithdrawn=$sourceWithdrawn;currentModelRunStale=$true;professionalAcceptance=$false}|ConvertTo-Json -Depth 8|Set-Content "$e/actual-http-negatives-summary.json" -Encoding utf8
 $trace|ConvertTo-Json -Depth 8|Set-Content "$e/actual-http-negatives.json" -Encoding utf8
 Write-Output 'ACTUAL_API_RESTART_PERMISSION_VERSION_SOURCE_NEGATIVES_PASSED_ZERO_NEW_MODEL_CALLS'
}finally{$trace|ConvertTo-Json -Depth 8|Set-Content "$e/actual-http-negatives-attempt.json" -Encoding utf8;$token=$null;$view=$null;$replay=$null;$restored=$null;$stale=$null;$maintenance.Dispose();$member.Dispose()}
