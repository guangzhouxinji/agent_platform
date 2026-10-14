$ErrorActionPreference='Stop'
. './docs/evidence/consultation-agent-native-20261006/approved-run/approved-http.ps1'
$maint=New-ProductHttp;$helper=$null
try {
 Assert-ApprovedTime
 $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
 $facts=Get-Content (Join-Path $out 'facts-before-initialization.json') -Raw|ConvertFrom-Json
 $cfg=Get-Content (Join-Path $vault 'container-deployment.json') -Raw|ConvertFrom-Json -AsHashtable
 $declaration=(Get-Content (Join-Path $vault 'patentsview-candidate-v453.json') -Raw|ConvertFrom-Json -AsHashtable).declaration
 if($declaration.sourceVersion -ne 'CAPTURE_SHA256:54a18995b6272b4dd8d8a473f1ee6f059125bc29a580840459ff8d8e5971a639'){throw 'Exact original capture required'}
 $contract='patentsview-capture-contract-agent-20261006-01';$decisionName='source-patentsview-agent-20261006-01.json'
 $declaration.contractVersion=$contract;$declaration.validUntil=$cutoff.ToString('o');$declaration.requestedUses.TITLE=@($declaration.requestedUses.TITLE)+@('MODEL')
 if($declaration.requestedUses.CLAIMS -contains 'MODEL'){throw 'No raw claims model use'}
 $withdraw=Approved-Send $maint POST '/v2/consultation/source-contracts/PATENTSVIEW_ZENODO_2024/withdraw-product-use' @{expectedEpoch=[long]$facts.source[0].SOURCE_EPOCH} 'old-expired-source-withdraw'
 $candidate=Approved-Send $maint POST '/v2/consultation/source-contracts/candidates' $declaration 'new-source-candidate'
 $verified=Approved-Send $maint POST "/v2/consultation/source-contracts/candidates/PATENTSVIEW_ZENODO_2024/$contract/verify" @{expectedVersion=1;manifestRef='data/patents/patentsview-real-candidate-20261002-v3/manifest.json';manifestSha='54a18995b6272b4dd8d8a473f1ee6f059125bc29a580840459ff8d8e5971a639'} 'new-source-verify'
 if($verified.recordCount -ne 40){throw 'Approved record count must remain 40'}
 $decision=Get-Content (Join-Path $vault 'source-patentsview-decision-v453.json') -Raw|ConvertFrom-Json -AsHashtable
 $decision.decisionVersion='patentsview-agent-use-20261006-01';$decision.contractVersion=$contract;$decision.verificationId=$verified.verificationId;$decision.taskId=$task.taskId;$decision.scopeId=($task.dataScope -split ':')[0];$decision.validUntil=$cutoff.ToString('o');$decision.fieldUses.TITLE=@($decision.fieldUses.TITLE)+@('MODEL')
 if($decision.fieldUses.CLAIMS -contains 'MODEL'){throw 'No claims model approval'}
 $file=Join-Path $vault $decisionName;if(Test-Path $file){throw 'Preserve original decision'};$decision|ConvertTo-Json -Depth 25|Set-Content -LiteralPath $file -Encoding utf8
 $decisionSha=(Get-FileHash $file).Hash.ToLowerInvariant();$decision|ConvertTo-Json -Depth 25|Set-Content (Join-Path $out 'new-source-purpose-decision.json')
 if($cfg.evidenceRoot -ne '/mnt/docker-desktop-disk/agent-platform-product-files/consultation-evidence' -or $cfg.evidenceManifest -notmatch '^/mnt/docker-desktop-disk/agent-platform-product-files/[a-z0-9.-]+$'){throw 'Exact managed evidence required'}
 $helperName='consultation-source-decision-agent-20261006-01';if(@(docker ps -a --format '{{.Names}}') -contains $helperName){throw 'Preserve old helper'}
 $newManifest='/mnt/docker-desktop-disk/agent-platform-product-files/consultation-evidence-agent-20261006-01.sha256'
 $helper=(docker run -d --name $helperName --network none --read-only --user 0:0 --cap-drop ALL --cap-add CHOWN --cap-add FOWNER --cap-add DAC_OVERRIDE --tmpfs /tmp:rw,nosuid,size=8m --mount "type=bind,source=$($cfg.evidenceRoot),target=/evidence" --mount "type=bind,source=$($cfg.evidenceManifest),target=/oldmanifest,readonly" --mount 'type=bind,source=/mnt/docker-desktop-disk/agent-platform-product-files,target=/managed' $cfg.image sh -c 'sleep 1200').Trim();if($LASTEXITCODE -ne 0){throw 'Managed import helper failed'}
 docker cp $file "$($helper):/tmp/decision.json"|Out-Null;if($LASTEXITCODE -ne 0){throw 'Private immutable decision copy failed'}
 $script='set -eu; cd /evidence; test ! -e source-patentsview-agent-20261006-01.json; test ! -e /managed/consultation-evidence-agent-20261006-01.sha256; test "$(find . -type l | wc -l)" -eq 0; test "$(find . -type f | wc -l)" -eq "$(wc -l </oldmanifest)"; sha256sum -c /oldmanifest >/dev/null; test "$(sha256sum /tmp/decision.json | cut -d " " -f 1)" = "'+$decisionSha+'"; cp /tmp/decision.json source-patentsview-agent-20261006-01.json; chown 10001:10001 source-patentsview-agent-20261006-01.json; chmod 400 source-patentsview-agent-20261006-01.json; cat /oldmanifest > /managed/consultation-evidence-agent-20261006-01.sha256; sha256sum source-patentsview-agent-20261006-01.json >> /managed/consultation-evidence-agent-20261006-01.sha256; chown 10001:10001 /managed/consultation-evidence-agent-20261006-01.sha256; chmod 400 /managed/consultation-evidence-agent-20261006-01.sha256; sha256sum -c /managed/consultation-evidence-agent-20261006-01.sha256 >/dev/null; test "$(find . -type f | wc -l)" -eq "$(wc -l </managed/consultation-evidence-agent-20261006-01.sha256)"; sha256sum /managed/consultation-evidence-agent-20261006-01.sha256'
 $manifestHash=docker exec $helper sh -c $script;if($LASTEXITCODE -ne 0){throw 'Exact evidence manifest/decision import failed; preserve partial artifacts'}
 $manifestSha=(@($manifestHash)[0] -split ' ')[0];if($manifestSha -notmatch '^[a-f0-9]{64}$'){throw 'Manifest hash missing'}
 [ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');decisionFile=$decisionName;decisionSha=$decisionSha;evidenceManifest=$newManifest;evidenceManifestSha=$manifestSha;oldManifestPreserved=$true;oldEvidenceBytesVerified=$true;credentialsCopied=$false}|ConvertTo-Json|Set-Content (Join-Path $out 'new-source-evidence-stage.json')
 $enabled=Approved-Send $maint POST "/v2/consultation/source-contracts/PATENTSVIEW_ZENODO_2024/$contract/product-enable" @{expectedVersion=1;decisionRef=$decisionName;decisionSha=$decisionSha} 'new-source-enable'
 if($enabled.productStatus -ne 'PRODUCT_ENABLED' -or $enabled.rightsEvidenceStatus -ne 'UNRESOLVED'){throw 'No inference of third-party rights'}
 $scope=Approved-Send $maint POST '/v2/consultation/source-contracts/scopes/PATENTSVIEW_ZENODO_2024/authorize' @{taskId=$task.taskId;memberSubject='7b389c47-9f5b-4004-ad95-084600b351ef';taskVersion=1;sourceRowVersion=$enabled.sourceEpoch;expiresAt=$cutoff.ToString('o')} 'new-source-task-scope'
 Write-Output "NEW_SOURCE_USE=$contract sourceEpoch=$($enabled.sourceEpoch) approvedRecords=40 oldUseWithdrawn=true claimsModel=false"
}finally{Save-ApprovedTrace 'source-initialization';if($helper){docker stop $helper|Out-Null};$maint.Dispose();$http.Dispose();$password=$null}
