param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^model-v[0-9]+$'){throw 'Invalid release ID'}
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$baseline=Join-Path $env:LOCALAPPDATA 'Temp/search-isolated-build-search-v655'
$isolated=Join-Path $env:LOCALAPPDATA "Temp/consultation-$RunId"
if(Test-Path -LiteralPath $isolated){throw 'Preserve previous release'}
$inputs=Get-Content (Join-Path $workspace 'docs/evidence/consultation-search-20261002/search-v655-build-inputs.json') -Raw|ConvertFrom-Json
$prefix='backend/apps/platform-api/src/'
$overlay=@(
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalDialogue.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalDialogueOutput.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalDialogueConfiguration.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalDialogueController.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalWorkspace.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalWorkspaceController.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalWorkspaceConfiguration.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductConsultationWorkspacePageController.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalWorkflow.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductRetrievalMtls.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductSourceScopeRebinding.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductSourceScopeGrants.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductSourceScopeController.java'),
 ($prefix+'test/java/cn/xinji/agentplatform/security/product/ProductRetrievalWorkflowTest.java'),
 ($prefix+'test/java/cn/xinji/agentplatform/security/product/ProductSourceScopeRebindingTest.java'),
 ($prefix+'test/java/cn/xinji/agentplatform/security/product/ProductConsultationWorkspacePageControllerTest.java'),
 ($prefix+'test/java/cn/xinji/agentplatform/security/product/ProductRetrievalDialogueOutputTest.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductSourceCaptureIdentity.java'),
 ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductSourceAdmission.java'),
 ($prefix+'test/java/cn/xinji/agentplatform/security/product/ProductSourcePurposeVersionTest.java'),
 'backend/consultation/src/main/java/cn/xinji/agentplatform/consultation/application/ConsultationRetrievalModelPort.java',
 'backend/adapters/model-deepseek/src/main/java/cn/xinji/agentplatform/consultation/answer/adapter/out/openai/OpenAiRetrievalDialogueAdapter.java')
New-Item -ItemType Directory -Path $isolated|Out-Null
$manifest=@()
foreach($entry in $inputs.files){
 $relative=$entry.path.Replace('\','/')
 $source=Join-Path $baseline $relative
 if((Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -ne $entry.sha256){throw 'Approved source drift'}
 $target=[IO.Path]::GetFullPath((Join-Path $isolated $relative))
 if(-not $target.StartsWith($isolated+'\')){throw 'Path outside release'}
 New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
 Copy-Item -LiteralPath $source -Destination $target
}
foreach($added in $overlay){
 $target=Join-Path $isolated $added
 New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
 Copy-Item -LiteralPath (Join-Path $workspace $added) -Destination $target
}
# Mechanical application of this slice's only shared-route change to the sealed baseline.
$security=Join-Path $isolated ($prefix+'main/java/cn/xinji/agentplatform/security/product/ProductIdentityRouteSecurityConfiguration.java')
$content=Get-Content -LiteralPath $security -Raw
$anchor='"/v2/consultation/tasks/*/goals/current", "/v2/consultation/tasks/*/cancel",'
if(-not $content.Contains($anchor)){throw 'Route patch anchor drift'}
$content.Replace($anchor,$anchor+"`n                        `"/v2/consultation/tasks/*/dialogue/**`",`n                        `"/v2/consultation/tasks/*/retrieval-workspace`",`n                        `"/v2/consultation/tasks/*/retrievals/execute`",")|Set-Content -LiteralPath $security -Encoding utf8
$frontendRoute=Join-Path $isolated ($prefix+'main/java/cn/xinji/agentplatform/web/ReactFrontendController.java')
$routes=Get-Content -LiteralPath $frontendRoute -Raw
if(-not $routes.Contains('"/app/consultation", ')){throw 'Consultation page route anchor drift'}
$routes.Replace('"/app/consultation", ','')|Set-Content -LiteralPath $frontendRoute -Encoding utf8
foreach($relative in @($inputs.files.path)+$overlay|Select-Object -Unique){
 $manifest+=[ordered]@{path=$relative;sha256=(Get-FileHash -LiteralPath (Join-Path $isolated $relative)).Hash.ToLowerInvariant()}
}
foreach($name in @('data','db','docs')){
 New-Item -ItemType Junction -Path (Join-Path $isolated $name) -Target (Join-Path $workspace $name)|Out-Null
 New-Item -ItemType Junction -Path (Join-Path $isolated "backend/$name") -Target (Join-Path $workspace $name)|Out-Null
}
[ordered]@{runId=$RunId;capturedAt=[DateTimeOffset]::UtcNow.ToString('o');buildRoot=$isolated;
 purpose='FINAL_V656_RETRIEVAL_PLUS_APPROVED_REAL_MODEL_DIALOGUE';baseline='search-v655-build-inputs.json';overlay=$overlay;
 routePatch='Authenticated task dialogue only';files=$manifest}|ConvertTo-Json -Depth 4|
 Set-Content -LiteralPath (Join-Path $workspace "docs/evidence/consultation-search-20261002/$RunId-build-inputs.json") -Encoding utf8
Write-Output "source_snapshot=$isolated files=$($manifest.Count)"
