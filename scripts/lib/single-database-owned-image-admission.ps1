# Exact existing owned primary image; no generic path relaxation or storage mutation.
function Assert-FinalOwnedImageRecord($P,$Live,$TargetId) {
    $owner='IMP-SINGLE-DB-FINAL-BASELINE-01';$name='agent-platform-single-final-primary-data-20261009'
    if($P.format -cne 'SINGLE_DATABASE_OWNED_IMAGE_BINDING/v1' -or $P.targetId -cne $TargetId -or $P.ownerTask -cne $owner -or
       $P.imageVolume.Name -cne ($name+'-image') -or $P.imageVolume.Driver -cne 'local' -or $P.imageVolume.Labels.task -cne $owner -or $P.imageVolume.Labels.role -cne 'primary-owned-image' -or
       $P.imageVolume.Mountpoint -cne ('/var/lib/docker/volumes/'+$name+'-image/_data') -or ($P.imageVolume.Options -and $P.imageVolume.Options.Count) -or
       $P.boundedVolume.Name -cne $name -or $P.boundedVolume.Driver -cne 'local' -or $P.boundedVolume.Labels.task -cne $owner -or $P.boundedVolume.Labels.role -cne 'primary-bounded-data' -or
       $P.boundedVolume.Options.type -cne 'ext4' -or $P.boundedVolume.Options.device -cne '/dev/agent-platform-single-final-primary-loop' -or $P.boundedVolume.Options.o -cne 'nodev,nosuid,noexec'){throw 'FINAL_OWNED_IMAGE_VOLUME_BINDING_REQUIRED'}
    $capture=[DateTimeOffset]::Parse($Live.capturedAt);$now=Get-FinalDeploymentUtc
    if($Live.format -cne 'ACTUAL_OWNED_IMAGE_LOOP_PROOF/v1' -or $capture -gt $now -or $capture -lt $now.AddMinutes(-15) -or $Live.readOnly -ne $true -or $Live.executedWrites -ne 0 -or
       $Live.imagePath -cne '/primary.ext4' -or $Live.kernelBackingFile -cne '/primary.ext4' -or $Live.imageBytes -ne 3221225472 -or $Live.imageUid -ne 0 -or $Live.imageGid -ne 0 -or $Live.imageMode -cne '0600' -or
       $Live.imageStatDevice -le 0 -or $Live.imageInode -le 0 -or $Live.loopBackingDevice -ne $Live.imageStatDevice -or $Live.loopBackingInode -ne $Live.imageInode -or
       $Live.loopIndex -ne 491040 -or $Live.loopOffset -ne 0 -or $Live.loopSizeLimit -ne 0 -or $Live.kernelSectors -ne 6291456 -or $Live.blockDevice -ne 2011170592 -or $Live.mountedFilesystemDevice -ne $Live.blockDevice -or $Live.autoclear -ne $false -or
       $Live.filesystemUuid -cne '29678a4e-4538-4341-88cb-4718a7c1f5d8' -or $Live.capacityBytes -ne 3094126592 -or
       $Live.ownerMarker -cne "IMP-SINGLE-DB-FINAL-BASELINE-01`nprimary`n491040`n"){throw 'FINAL_OWNED_IMAGE_PHYSICAL_PROOF_REQUIRED'}
    if($Live.directories.Count -ne 3){throw 'FINAL_OWNED_IMAGE_DIRECTORY_PROOF_REQUIRED'}
    foreach($b in @(@('consultation',10001),@('enforcement',10002),@('adjudication',10003))){$d=$Live.directories[$b[0]];if($d.subpath -cne ('files/'+$b[0]) -or $d.uid -ne $b[1] -or $d.gid -ne $b[1] -or $d.mode -cne '0700'){throw 'FINAL_OWNED_IMAGE_DIRECTORY_PROOF_REQUIRED'}}
}
function Read-FinalOwnedImageBinding($Storage,[string]$TargetId) {
    Assert-FinalFile $Storage.proof;$p=Read-FinalDeploymentJson $Storage.proof.path
    foreach($ref in @($p.probeSource,$p.probeOutput,$p.probeContainer)){Assert-FinalFile $ref}
    if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'single-database-owned-image-proof.py') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $p.probeSource.sha256){throw 'FINAL_OWNED_IMAGE_PROBE_SOURCE_REQUIRED'}
    $live=Read-FinalDeploymentJson $p.probeOutput.path;Assert-FinalOwnedImageRecord $p $live $TargetId
    $container=@(Read-FinalDeploymentJson $p.probeContainer.path)[0]
    if($container.State.ExitCode -ne 0 -or $container.State.Running -or $container.State.OOMKilled -or $container.Config.Image -cne 'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760' -or
       $container.Config.Labels.task -cne $p.ownerTask -or $container.Config.Labels.'agent-platform.role' -cne 'read-only-storage-inspection' -or
       (@($container.Config.Entrypoint) -join ',') -cne 'python3' -or (@($container.Config.Cmd) -join ',') -cne '-B,/proof.py' -or
       $container.HostConfig.NetworkMode -cne 'none' -or $container.HostConfig.Memory -ne 134217728 -or $container.HostConfig.NanoCpus -ne 250000000 -or
       $container.HostConfig.Privileged -or -not $container.HostConfig.ReadonlyRootfs -or $container.HostConfig.CapDrop -cnotcontains 'ALL' -or ($container.HostConfig.CapAdd -and $container.HostConfig.CapAdd.Count) -or $container.HostConfig.SecurityOpt -cnotcontains 'no-new-privileges' -or
       @($container.HostConfig.Devices).Count -ne 1 -or $container.HostConfig.Devices[0].PathOnHost -cne '/dev/agent-platform-single-final-primary-loop' -or $container.HostConfig.Devices[0].PathInContainer -cne '/dev/owned-loop' -or $container.HostConfig.Devices[0].CgroupPermissions -cne 'r' -or @($container.Mounts).Count -ne 3){throw 'FINAL_OWNED_IMAGE_READ_ONLY_HELPER_REQUIRED'}
    foreach($b in @(@('/image',$p.imageVolume.Name),@('/bounded',$p.boundedVolume.Name))){$m=@($container.Mounts|Where-Object Destination -CEQ $b[0]);if($m.Count -ne 1 -or $m[0].Type -cne 'volume' -or $m[0].Name -cne $b[1] -or $m[0].RW -ne $false){throw 'FINAL_OWNED_IMAGE_READ_ONLY_HELPER_REQUIRED'}}
    $mount=@($container.Mounts|Where-Object Destination -CEQ '/proof.py');$mapped=(Get-FinalWindowsReferencePath $p.probeSource.path);$mapped='/run/desktop/mnt/host/'+$mapped.Substring(0,1).ToLowerInvariant()+'/'+$mapped.Substring(3)
    if($mount.Count -ne 1 -or $mount[0].Type -cne 'bind' -or $mount[0].RW -ne $false -or $mount[0].Source -cne $mapped){throw 'FINAL_OWNED_IMAGE_PROBE_SOURCE_REQUIRED'}
    $originalHashes=@{source02='c7f0d58d5b7579e6410e059c241345aa5706caac768c6e0b38bf744e8b560b6d';log02='045826ada677eb07c9c5b0835bb2790b3f8239e156d467ac52289dcca1cf6a8a';source03='bd9cc226a02bc7b63604300a7ac32ab0311c8ace6a6e96d1b88745399838155f';log03='b9b6290e66d750ead442754e9115cd9664366ac8d1f480995c7d22bffd89cbc5';state03='47d732282475d9146692077c19bca49e6fd1709bce670c48619dc79f97a2fbe1'}
    $historyRoot=Join-Path (Split-Path (Split-Path $PSScriptRoot)) 'docs/evidence/single-database-installation-20261009/approved-execution'
    foreach($entry in @(@('source02','storage-primary-resume02.py'),@('log02','storage-primary-resume02.log'),@('source03','storage-primary-resume03.py'),@('log03','storage-primary-resume03.log'),@('state03','storage-primary-resume03-state.json'))){$ref=$p.historicalEnospc[$entry[0]];Assert-FinalFile $ref;if($ref.sha256 -cne $originalHashes[$entry[0]]){throw 'FINAL_ORIGINAL_ENOSPC_EVIDENCE_REQUIRED'};if([IO.Path]::GetFullPath($ref.path) -cne [IO.Path]::GetFullPath((Join-Path $historyRoot $entry[1]))){throw 'FINAL_ORIGINAL_ENOSPC_EVIDENCE_REQUIRED'}}
    $source=Get-Content -LiteralPath $p.historicalEnospc.source02.path -Raw;$failure=Get-Content -LiteralPath $p.historicalEnospc.log02.path -Raw
    $resumed=Read-FinalDeploymentJson $p.historicalEnospc.log03.path;$state=Read-FinalDeploymentJson $p.historicalEnospc.state03.path
    if(-not $source.Contains('assert overflow==errno.ENOSPC') -or -not $failure.Contains('line 28') -or -not $failure.Contains('PermissionError') -or $state.ExitCode -ne 0 -or $state.OOMKilled -or $resumed.format -cne 'SINGLE_DATABASE_OWNED_STORAGE_PROOF/v1' -or
       $resumed.taskId -cne $p.ownerTask -or $resumed.loopIndex -ne $live.loopIndex -or $resumed.imageBytes -ne $live.imageBytes -or $resumed.filesystemUuid -cne $live.filesystemUuid -or $resumed.capacityBytes -ne $live.capacityBytes -or $resumed.autoclear -ne $false -or
       $resumed.quotaEvidence -cne 'storage-primary-resume02.py:ENOSPC assertion passed before marker failure; complete unmodified failure log retained'){throw 'FINAL_ORIGINAL_ENOSPC_EVIDENCE_REQUIRED'}
    if($Storage.backingKind -cne 'owned-image-volume' -or $Storage.volume -cne $p.boundedVolume.Name -or $Storage.device -cne $p.boundedVolume.Options.device -or $Storage.backingFile -cne ($p.imageVolume.Mountpoint+'/primary.ext4') -or $Storage.filesystemUuid -cne $live.filesystemUuid){throw 'FINAL_OWNED_IMAGE_STORAGE_REFERENCE_REQUIRED'}
    return $p
}
function Assert-FinalOwnedImageRuntime($P,$Db) {
    $v=@(Invoke-FinalDocker @('volume','inspect',$P.imageVolume.Name)|ConvertFrom-Json -AsHashtable)[0]
    if($v.Name -cne $P.imageVolume.Name -or $v.Driver -cne $P.imageVolume.Driver -or $v.Mountpoint -cne $P.imageVolume.Mountpoint -or $v.Labels.task -cne $P.ownerTask -or $v.Labels.role -cne 'primary-owned-image' -or ($v.Options -and $v.Options.Count) -or
       @($Db.HostConfig.Devices).Count -or ($Db.HostConfig.CapAdd -and $Db.HostConfig.CapAdd.Count) -or $Db.HostConfig.SecurityOpt -cnotcontains 'no-new-privileges'){throw 'FINAL_OWNED_IMAGE_NO_EXPANSION_REQUIRED'}
    # Existing caller verifies non-root, readonly rootfs, caps ALL and the exact db/log/entry mounts.
}

function Assert-FinalSelectedIdentityBootstrap($Init,$Domain,$TargetId,$JarSha) {
    $i=$Init.identityInitialization
    if($Domain -cnotin @('consultation','adjudication','enforcement') -or @($i.initializedDomains).Count -ne 1 -or $i.initializedDomains[0] -cne $Domain -or $i.globalIdentityInitialized -ne $false){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
    $refs=$i.evidence[$Domain];Assert-FinalFile $refs.execution;Assert-FinalFile $refs.reconciliation
    $exec=Read-FinalDeploymentJson $refs.execution.path;$post=Read-FinalDeploymentJson $refs.reconciliation.path
    if($Domain -ceq 'consultation'){
        # Preserve the original consultation receipt contract; it never proves another domain.
        if($exec.format -cne 'ACTUAL_CONSULTATION_SHARED_BOOTSTRAP_ATTEMPT/v1' -or $exec.exitCode -ne 0 -or $exec.targetId -cne $TargetId -or $exec.jar.sha256 -cne $JarSha -or
           $post.format -cne 'ACTUAL_CONSULTATION_SHARED_BOOTSTRAP_RECONCILIATION/v1' -or $post.actualJdbc -ne $true -or $post.consultationIdentityInitialized -ne $true -or $post.globalIdentityInitialized -ne $false -or $post.memberOrBusinessGrantCreated -ne $false -or $post.requiredObjectGrants -ne 674 -or $post.jar.sha256 -cne $JarSha -or $post.postconditions.postconditionsPassed -ne $true){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
        return
    }
    if($Domain -ceq 'enforcement'){
        # D6.25: this admitted domain's actual maintenance bootstrap, never business membership.
        $pc=$post.postconditions
        if($exec.format -cne 'ACTUAL_ENF_NORMAL_FIRST_BOOTSTRAP_ATTEMPT/v1' -or $exec.exitCode -ne 0 -or
           $exec.bootstrapInvoked -ne $true -or $exec.retryPerformed -ne $false -or $exec.targetId -cne $TargetId -or $exec.jar.sha256 -cne $JarSha -or
           $post.format -cne 'ACTUAL_ENFORCEMENT_SHARED_BOOTSTRAP_RECONCILIATION/v1' -or $post.domain -cne $Domain -or $post.targetId -cne $TargetId -or
           $post.actualJdbc -ne $true -or $post.enforcementIdentityInitialized -ne $true -or $post.globalIdentityInitialized -ne $false -or
           $post.memberOrBusinessGrantCreated -ne $false -or $post.requiredObjectGrants -ne 674 -or $post.jar.sha256 -cne $JarSha -or
           $post.onlyExpectedThreeAuthorityRowsChanged -ne $true -or $post.structureAccountsPrivilegesUnchanged -ne $true -or
           $pc.format -cne 'ACTUAL_ENFORCEMENT_BOOTSTRAP_READ_ONLY_POSTCONDITIONS/v1' -or $pc.actualJdbc -ne $true -or
           $pc.postconditionsPassed -ne $true -or $pc.executedWrites -ne 0 -or $pc.executedDcl -ne 0){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
        foreach($table in @('ID2_SUBJECT','ID2_SUBJECT_EVENT','ID2_KEY_CONTROL','ID2_SESSION','ID2_GRANT','ID2_GRANT_EVENT','ID2_CURRENT_EPOCH')){
            if($pc.primaryIdentityRowCounts[$table] -ne 0){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
        }
        if(@($pc.maintainers).Count -ne 1 -or @($pc.watermarks).Count -ne 1 -or @($pc.bootstrapAudit).Count -ne 1){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
        $m=$pc.maintainers[0];$w=$pc.watermarks[0];$b=$pc.bootstrapAudit[0]
        if($m.SINGLETON_KEY -ne 1 -or $m.SUBJECT_REF -cne 'enforcement-acceptance-maintainer-20260929' -or $m.ORGANIZATION -cne 'ORG-ENF-ACCEPTANCE' -or
           $m.CERTIFICATE_SHA -cne '74d07e92c1602dda7d6d8335d53538cbd8ac2f27c1c0973c9b1295b3f7c72ba3' -or
           $m.ALLOWED_ROLES -cne 'SUBJECTS,GRANTS,KEYS,SOURCES' -or $m.STATUS -cne 'ACTIVE' -or $m.VERSION -ne 1 -or
           $w.ISSUER -cne 'https://enforcement.agent-platform.internal:5447' -or $w.GENERATION -ne 0 -or $w.STATE -cne 'READY' -or
           $null -ne $w.INTENT_ID -or $w.HISTORY_SHA -cne 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' -or
           $b.SUBJECT_REF -cne $m.SUBJECT_REF -or $b.CERTIFICATE_SHA -cne $m.CERTIFICATE_SHA -or $b.ROLE_CODE -cne 'BOOTSTRAP' -or $b.DECISION -cne 'BOOTSTRAP'){
            throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'
        }
        return
    }
    # Only the admitted adjudication first-start contract, not a generic receipt/identity bypass.
    $pc=$post.postconditions
    if($exec.format -cne 'ACTUAL_ADJ_NORMAL_FIRST_BOOTSTRAP_ATTEMPT/v1' -or $exec.exitCode -ne 0 -or $exec.bootstrapInvoked -ne $true -or $exec.retryPerformed -ne $false -or $exec.targetId -cne $TargetId -or $exec.jar.sha256 -cne $JarSha -or
       $post.format -cne 'ACTUAL_ADJUDICATION_SHARED_BOOTSTRAP_RECONCILIATION/v1' -or $post.domain -cne $Domain -or $post.targetId -cne $TargetId -or $post.actualJdbc -ne $true -or $post.adjudicationIdentityInitialized -ne $true -or $post.globalIdentityInitialized -ne $false -or $post.memberOrBusinessGrantCreated -ne $false -or $post.requiredObjectGrants -ne 674 -or $post.jar.sha256 -cne $JarSha -or
       $pc.format -cne 'ACTUAL_ADJUDICATION_BOOTSTRAP_READ_ONLY_POSTCONDITIONS/v1' -or $pc.actualJdbc -ne $true -or $pc.postconditionsPassed -ne $true -or $pc.executedWrites -ne 0 -or $pc.executedDcl -ne 0){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
    foreach($table in @('ID2_SUBJECT','ID2_SUBJECT_EVENT','ID2_KEY_CONTROL','ID2_SESSION','ID2_GRANT','ID2_GRANT_EVENT','ID2_CURRENT_EPOCH')){
        if($pc.primaryIdentityRowCounts[$table] -ne 0){throw 'FINAL_SELECTED_IDENTITY_BOOTSTRAP_REQUIRED'}
    }
}

