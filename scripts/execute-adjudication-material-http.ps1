param([switch]$ResumePreparedCase)
$ErrorActionPreference='Stop';$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$e=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01'
. (Join-Path $PSScriptRoot 'lib/adjudication-product-http.ps1')
if(Test-Path -LiteralPath "$root/http-model-run.json"){throw 'Actual run already recorded; do not duplicate paid invocation'}
function Audit([string]$name,[int]$calls){$old1=$env:ADJ_PRODUCT_AUDIT_PASSWORD;$old2=$env:ADJ_PRODUCT_AUDIT_AUTHORITY_PASSWORD;$old3=$env:ADJ_PRODUCT_AUDIT_EXPECT_CALLS;try{$env:ADJ_PRODUCT_AUDIT_PASSWORD=AdjSecret (Join-Path (Split-Path -Parent $root) 'dm8-model-schema.credential.xml');$env:ADJ_PRODUCT_AUDIT_AUTHORITY_PASSWORD=AdjSecret "$root/authority-owner.credential.xml";$env:ADJ_PRODUCT_AUDIT_EXPECT_CALLS=[string]$calls;& "$env:JAVA_HOME/bin/java.exe" -cp "$root/boot/BOOT-INF/classes;$root/boot/BOOT-INF/lib/*" (Join-Path $PSScriptRoot 'java/AuditAdjudicationProductRuntime.java') "$e/$name" *> "$e/audit-$name.log";if($LASTEXITCODE -ne 0){throw 'Persistent budget pre/postcondition failed; no automatic model retry'}}finally{$env:ADJ_PRODUCT_AUDIT_PASSWORD=$old1;$env:ADJ_PRODUCT_AUDIT_AUTHORITY_PASSWORD=$old2;$env:ADJ_PRODUCT_AUDIT_EXPECT_CALLS=$old3}}
function Sha([string]$s){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($s))).ToLower()}
try{
 $registered=Get-Content "$root/operator-registration.json" -Raw|ConvertFrom-Json;$password=AdjSecret "$root/operator.credential.xml";$token=AdjLogin $registered.loginName $password
 $packRoot=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261003/user-approved-material-package-v1';$manifest=Get-Content "$packRoot/manifest.json" -Raw|ConvertFrom-Json
 $scope=@{caseId='adjudication-materials-case-01';organization='project-adjudication';dataScope='adjudication-materials-case-01-materials'}
 $original='连接组件材料整理';$context=@{id='case-context';version='v1';locator='text:case-context';sha256=(Sha $original);kind='PROJECT_AUTHORED'}
 $input=@{id=$scope.caseId;organization=$scope.organization;dataScope=$scope.dataScope;name=$original;applicant='项目申请方';respondent='项目答辩方';issues=@('核查双方关于连接结构的材料陈述');region=$null;procedure=$null;relevantDate=$null;source=$context}
 $sources=@(@{source=$context;uses=@('READ','SAVE','ANALYZE')});$materials=@();$index=0
 foreach($f in $manifest.files){$index++;if($f.path -notmatch '^[a-z-]+-v1\.txt$'){throw 'Approved original filename required'};$text=[IO.File]::ReadAllText("$packRoot/$($f.path)");if((Sha $text) -ne $f.sha256){throw 'Approved original changed'};$source=@{id="material-$index";version='v1';locator="text:$($f.path)";sha256=$f.sha256;kind='PROJECT_AUTHORED'};$sources+=@{source=$source;uses=@('READ','SAVE','ANALYZE','MODEL')};$materials+=@{id="material-$index";caseId=$scope.caseId;side=$f.providerSide;submitter=if($f.providerSide -eq 'APPLICANT'){'项目申请方'}else{'项目答辩方'};content=$text;assertedPurpose='双方材料陈述整理';source=$source;readable=$true;supersedesMaterialId=$null}}
 $approvalFile=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261003/user-approval-v1.json';$receipts=@()
 if(-not $ResumePreparedCase){ foreach($s in $sources){$use=@{scope=$scope;source=$s.source;allowedUses=$s.uses;validUntil='2026-11-01T16:00:00Z';evidenceRef='ADJ-LLM-MATERIALS-USER-20261003-01+ADJ-LLM-SUPPLIER-USER-20261003-01';evidenceSha256=(Sha ([IO.File]::ReadAllText($approvalFile)))};$receipt=AdjSend $maintenance 'POST' '/v2/adjudication/source-uses' $use $null $null 200;$receipts+=@{source=$s.source;allowedUses=$s.uses;receipt=$receipt};$receipts|ConvertTo-Json -Depth 12|Set-Content "$root/source-receipts.json" -Encoding utf8}
 }
 $basePath="/v2/adjudication/case-works/$($scope.caseId)"
 if(-not $ResumePreparedCase){ AdjSend $member 'POST' '/v2/adjudication/case-works' @{input=$input;originalText=$original} 'adj-case-create-20261004-01' $token 200|Out-Null
 foreach($m in $materials){$head=AdjSend $member 'GET' $basePath $null $null $token 200;AdjSend $member 'POST' "$basePath/materials" @{expectedVersion=$head.version;material=$m} "adj-material-$($m.id)-20261004-01" $token 200|Out-Null}
 }
 $head=AdjSend $member 'GET' $basePath $null $null $token 200;foreach($m in $materials){$actual=@($head.materials|Where-Object {$_.id -eq $m.id});if($actual.Count -ne 1 -or (Sha $actual[0].content) -ne $m.source.sha256 -or $actual[0].source.version -ne 'v1'){throw 'Actual prepared case source/version differs; no model call'}};$expected=[long]$head.version
 $body=@{expectedVersion=$expected;materialIds=@('material-1','material-2')};$key='adj03a-http-20261004-01'
 Audit 'actual-runtime-before-model.json' 1
 # Exactly one necessary paid invocation; reserve/uncertainty is handled by the production API and persistent ledger.
 $run=AdjSend $member 'POST' "$basePath/material-organization/runs" $body $key $token 200
 if(-not $run.runId -or $run.model -ne 'deepseek-flash' -or $run.candidates.Count -lt 1){throw 'Real provider result contract missing'}
 $loaded=AdjSend $member 'GET' "$basePath/material-organization/runs/$($run.runId)" $null $null $token 200
 $runJson=$run|ConvertTo-Json -Depth 30 -Compress;$loadedJson=$loaded|ConvertTo-Json -Depth 30 -Compress;if($runJson -ne $loadedJson){throw 'API persisted round differs from real generation'}
 @{caseId=$scope.caseId;runId=$run.runId;requestKey=$key;expectedVersion=$expected;workVersion=$run.workVersion;inputVersion=$run.inputVersion;model=$run.model;candidateCount=$run.candidates.Count;candidateViewSha256=(Sha $runJson);stale=$run.stale;subject=$registered.subjectId;issuer=$base;organization=$scope.organization;sourceVersions=@($materials|ForEach-Object {$_.source});realProviderAndApi=$true;realDm8=$true;normalTlsJwtPkce=$true;professionalAcceptance=$false}|ConvertTo-Json -Depth 20|Set-Content "$root/http-model-run.json" -Encoding utf8
 Copy-Item -LiteralPath "$root/http-model-run.json" -Destination "$e/actual-http-model-run.json"
 Audit 'actual-runtime-after-model.json' 2
 $trace|ConvertTo-Json -Depth 8|Set-Content "$e/material-model-http.json" -Encoding utf8
 Write-Output 'REAL_ADJ_HTTP_OFFICIAL_MODEL_DM8_SAME_CASE_GENERATE_AND_QUERY_PASSED_ONE_NEW_CALL'
}finally{$trace|ConvertTo-Json -Depth 8|Set-Content "$e/material-model-http-attempt.json" -Encoding utf8;$token=$null;$password=$null;$run=$null;$loaded=$null;$maintenance.Dispose();$member.Dispose()}
