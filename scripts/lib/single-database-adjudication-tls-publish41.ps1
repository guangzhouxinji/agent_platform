# Final transport role only. This helper is invoked by the existing application deployment CLI.
function Assert-Adj41LocalTlsFields($Object,[string[]]$Keys) {
    if($Object -isnot [Collections.IDictionary] -or $Object.Count -ne $Keys.Count -or @($Object.Keys|Where-Object {$_ -cnotin $Keys}).Count){throw 'LOCAL_TLS_UNREGISTERED_FIELD'}
}
function Assert-Adj41LocalTlsWindow($Approval) {
    $s=$Approval.scope;$now=Get-FinalDeploymentUtc
    $from=[DateTimeOffset]::Parse($s.from);$until=[DateTimeOffset]::Parse($s.until)
    if($from -ne [DateTimeOffset]::Parse('2026-10-08T19:00:00Z') -or $until -ne [DateTimeOffset]::Parse('2026-10-10T10:00:00Z') -or $now -lt $from -or $now -ge $until){throw 'LOCAL_TLS_APPROVAL_WINDOW_REFUSED'}
}
function Get-Adj41LocalTlsImmutableBinding($Config,[string]$InputSha) {
    if(-not $Config.Contains('originalPublication')){return $InputSha}
    Assert-FinalFile $Config.originalPublication
    $original=Read-FinalDeploymentJson $Config.originalPublication.path
    Assert-Adj41LocalTlsFields $original @('schema','decision','deployment','source','buildReceipt','guardSource','jar','runtime','artifactRoot','publisherName','targetId','bridgeId')
    if((Get-FinalWindowsReferencePath $Config.artifactRoot) -cne (Get-FinalWindowsReferencePath $original.artifactRoot)){throw 'LOCAL_TLS_IMMUTABLE_PROFILE_DRIFT'}
    foreach($key in @('schema','publisherName','targetId','bridgeId')){if($Config[$key] -cne $original[$key]){throw 'LOCAL_TLS_IMMUTABLE_PROFILE_DRIFT'}}
    foreach($key in @('decision','guardSource','source','buildReceipt','jar','runtime')){
        if($Config[$key].path -cne $original[$key].path -or $Config[$key].sha256 -cne $original[$key].sha256){throw 'LOCAL_TLS_IMMUTABLE_PROFILE_DRIFT'}}
    Assert-FinalFile $original.deployment
    $before=Read-FinalDeploymentJson $original.deployment.path;$current=Read-FinalDeploymentJson $Config.deployment.path
    if($before.Count -ne $current.Count){throw 'LOCAL_TLS_IMMUTABLE_DEPLOYMENT_DRIFT'}
    foreach($key in $before.Keys){
        if($key -cin @('initializationReceipt','inventoryReceipt')){continue}
        if((ConvertTo-Json $before[$key] -Depth 40 -Compress) -cne (ConvertTo-Json $current[$key] -Depth 40 -Compress)){throw 'LOCAL_TLS_IMMUTABLE_DEPLOYMENT_DRIFT'}}
    return $Config.originalPublication.sha256
}
function Get-Adj41LocalTlsPublicationPlan($Config,[string]$ConfigSha,[switch]$ForStop) {
    $fields=@('schema','decision','deployment','source','buildReceipt','guardSource','jar','runtime','artifactRoot','publisherName','targetId','bridgeId')
    if($Config -is [Collections.IDictionary] -and $Config.Contains('originalPublication')){$fields+='originalPublication'}
    Assert-Adj41LocalTlsFields $Config $fields
    if($Config.schema -cne 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v2' -or $ConfigSha -cnotmatch '^[a-f0-9]{64}$' -or
       $Config.targetId -cne '4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff' -or
       $Config.bridgeId -cne '3fc351592a63a334572db0a34af42b586da11586350ffc4e6450835bc956c5bb' -or
       $Config.publisherName -cne 'agent-platform-single-final-adjudication-publish41-20261010'){throw 'LOCAL_TLS_EXACT_SCOPE_REQUIRED'}
    $refs=@($Config.decision,$Config.deployment,$Config.buildReceipt,$Config.guardSource)
    if(-not $ForStop){$refs+=@($Config.source,$Config.jar,$Config.runtime)}
    foreach($r in $refs){Assert-FinalFile $r}
    $bindingSha=Get-Adj41LocalTlsImmutableBinding $Config $ConfigSha
    if($Config.decision.sha256 -cne '2dc48138566a965131e4bca1f3350fbce6f3e5b6372b5a6974a33368af599b1d'){throw 'LOCAL_TLS_EXACT_DERIVED_DECISION_REQUIRED'}
    $approval=Read-FinalDeploymentJson $Config.decision.path
    $s=$approval.scope
    if($approval.format -cne 'ADJUDICATION_LOCAL_TLS_TECHNICAL_DECISION/v2' -or $approval.derivedDecisionNotUserVerbatim -ne $true -or
       $s.role -cne 'formal-localhost-tcp-tls-passthrough' -or $s.domain -cne 'adjudication' -or $s.targetId -cne $Config.targetId -or
       $s.targetNetworkId -cne '047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65' -or
       $s.hostIp -cne '127.0.0.1' -or $s.hostPort -ne 5448 -or $s.targetPort -ne 8443 -or
       $s.memoryBytes -ne 134217728 -or $s.nanoCpus -ne 50000000 -or $s.totalMemoryBytes -ne 7516192768 -or $s.totalNanoCpus -ne 4000000000 -or
       $s.tlsTermination -ne $false -or $s.privateKeyReadOrCopy -ne $false -or $s.applicationEgressChange -ne $false -or $s.businessPermissionChange -ne $false){throw 'LOCAL_TLS_DERIVED_SCOPE_REQUIRED'}
    foreach($ref in @($approval.delegation,$approval.humanMessage,$approval.resourceApproval)){Assert-FinalFile $ref}
    if($approval.delegation.sha256 -cne '3bc8455877a3fac13b67eebb7f26e5f13c22f3d9192606821f1ccfe7b9b7c1c4' -or $approval.humanMessage.sha256 -cne '8691cf9557a3004e1f3fb5a52e68f44b40b94ee78fe0e0871c4ef66783b143ce' -or $approval.resourceApproval.sha256 -cne '649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7'){throw 'LOCAL_TLS_ORIGINAL_DELEGATION_REQUIRED'}
    $delegation=Read-FinalDeploymentJson $approval.delegation.path
    if($delegation.format -cne 'VERIFIED_HUMAN_TECHNICAL_DECISION_DELEGATION/v1' -or $delegation.sourceThreadId -cne '01a12159-ccfb-7402-b371-57986aeb1e88' -or $delegation.humanMessageId -cne '01a1225a-3b8c-74f1-9229-4fd938eedc93' -or $delegation.notUserVerbatimPlan -ne $true){throw 'LOCAL_TLS_ORIGINAL_DELEGATION_REQUIRED'}
    $guard=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'single-database-adjudication-tls-publish41.ps1'))
    if([IO.Path]::GetFullPath($Config.guardSource.path) -cne $guard){throw 'LOCAL_TLS_GUARD_SOURCE_REQUIRED'}
    Assert-Adj41LocalTlsWindow $approval
    $deployment=Read-FinalDeploymentJson $Config.deployment.path
    if($ForStop){
        # Rollback never requires a fresh DB inventory, target uptime or decryption of target credentials.
        if($deployment.schema -cne 'SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1' -or $deployment.image -cne 'sha256:14731f0ec76c841877c8858e1a29413d7acacda5c5ce567eaf6e5f19913b7cb1' -or $deployment.applications.Count -ne 1){throw 'LOCAL_TLS_FIXED_DEPLOYMENT_REQUIRED'}
        $plan=@{applications=@(@{input=$deployment.applications[0]})}
    }else{$plan=Get-FinalDeploymentPlan $deployment}
    if($plan.applications.Count -ne 1 -or $plan.applications[0].input.domain -cne 'adjudication' -or
       $plan.applications[0].input.jar.sha256 -cne '8ce3e700ec9ab0237ba366a453a1e8c447e173a7507c75a87b95c8718ae4b1bc' -or
       $plan.applications[0].input.properties.sha256 -cne 'e4d000b4b61d0c309c16a0d5917ef3fbf5f42b3be89b4233aa78cfaa7d194302' -or
       $deployment.networkId -cne $s.targetNetworkId){throw 'LOCAL_TLS_FIXED25_REQUIRED'}
    $root=Get-FinalWindowsReferencePath $Config.artifactRoot
    $expectedRoot=Get-FinalWindowsReferencePath ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../docs/evidence/single-database-installation-20261009/approved-execution/adjudication-runtime40/publisher41')))
    if($root -cne $expectedRoot -or (Get-FinalWindowsReferencePath $Config.source.path) -cne (Get-FinalWindowsReferencePath ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../java/AdjudicationLocalTlsPublisher.java')))) -or
       (Get-FinalWindowsReferencePath $Config.jar.path) -cne ($root+'/adjudication-local-tls-publisher.jar') -or
       (Get-FinalWindowsReferencePath $Config.runtime.path) -cne ($root+'/runtime.properties')){throw 'LOCAL_TLS_ARTIFACT_SCOPE_REQUIRED'}
    $build=Read-FinalDeploymentJson $Config.buildReceipt.path
    if($build.format -cne 'ADJUDICATION_LOCAL_TLS_PUBLISHER_BUILD/v1' -or $build.exitCode -ne 0 -or $build.source.sha256 -cne $Config.source.sha256 -or $build.jar.sha256 -cne $Config.jar.sha256 -or $build.guardSource.sha256 -cne $Config.guardSource.sha256 -or $Config.source.sha256 -cne '1e2f5798e0d4ddb89b2a0118fc1ed0dadf55c59ea9ecdf25db1e45a5173ad148' -or $Config.jar.sha256 -cne '638a26c1577ab47b215ea7a062c48f48ba634951d79f0035fcb1c9ba9818ed40' -or $build.tests.passed -ne 10 -or $build.tests.failed -ne 0 -or $build.tests.skipped -ne 0){throw 'LOCAL_TLS_EXACT_RELAY_BUILD_REQUIRED'}
    foreach($receipt in @($build.compileResult,$build.testResult,$build.testOutput,$build.specialization)){Assert-FinalFile $receipt}
    $compile=Read-FinalDeploymentJson $build.compileResult.path;$test=Read-FinalDeploymentJson $build.testResult.path;$specialization=Read-FinalDeploymentJson $build.specialization.path
    if($compile.exitCode -ne 0 -or $test.exitCode -ne 0 -or $specialization.onlyClassNameAndExactTargetChanged -ne $true -or $specialization.newSourceSha256 -cne $Config.source.sha256){throw 'LOCAL_TLS_ACTUAL_RELAY_BUILD_REQUIRED'}
    if($ForStop){
        # Read only the approved directory handle: stopping the owned role must work even if an artifact is missing.
        $physicalRoot=Get-FinalHandleWindowsPath $root $true
        Assert-FinalPhysicalReferencePaths ($root+'/adjudication-local-tls-publisher.jar') $root ($physicalRoot+'/adjudication-local-tls-publisher.jar') $physicalRoot
        $jarSource=$physicalRoot+'/adjudication-local-tls-publisher.jar';$runtimeSource=$physicalRoot+'/runtime.properties'
    }else{
        $jarSource=Get-FinalPhysicalWindowsReference $Config.jar $root
        $runtimeSource=Get-FinalPhysicalWindowsReference $Config.runtime $root
    }
    $app=$plan.applications[0];if(-not $ForStop){$app.launchCommand=Get-FinalApplicationLaunchCommand $app}
    $launch="if ! (test -f /publisher/relay.jar && test ! -L /publisher/relay.jar && test `"`$(sha256sum /publisher/relay.jar | cut -d ' ' -f1)`" = '$($Config.jar.sha256)' && test -f /publisher/runtime.properties && test ! -L /publisher/runtime.properties && test `"`$(sha256sum /publisher/runtime.properties | cut -d ' ' -f1)`" = '$($Config.runtime.sha256)'); then echo LOCAL_TLS_REFERENCE_BYTES_REQUIRED >&2; exit 78; fi; exec java -Xms16m -Xmx48m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:MaxDirectMemorySize=8m -Xss256k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -jar /publisher/relay.jar /publisher/runtime.properties"
    return @{config=$Config;sha=$bindingSha;inputSha=$ConfigSha;approval=$approval;deployment=$deployment;deploymentPlan=$plan;app=$app;jarSource=$jarSource;runtimeSource=$runtimeSource;launch=$launch}
}
function Get-Adj41LocalTlsInspect([string]$Id) {return @(Invoke-FinalDocker @('inspect',$Id)|ConvertFrom-Json -AsHashtable)[0]}
function Assert-Adj41LocalTlsPredecessor {
    $id='c8247666d7f330340377392e7833879d748296bcb449cff00dce1b17594fbb36'
    $a=Get-Adj41LocalTlsInspect $id
    if($a.Id -cne $id -or $a.Name -cne '/agent-platform-single-final-adjudication-publish-20261010' -or $a.State.Running -or $a.State.Status -cne 'exited' -or $a.State.Pid -ne 0 -or $a.State.ExitCode -ne 1){throw 'LOCAL_TLS_FAILED_PREDECESSOR_DRIFT'}
}
function Assert-Adj41LocalTlsBudget($P,[string]$ExistingPublisher='') {
    Assert-Adj41LocalTlsPredecessor
    $ids=@(Invoke-FinalDocker @('ps','--no-trunc','--filter','label=task=IMP-SINGLE-DB-FINAL-BASELINE-01','--format','{{.ID}}'))
    $allowed=@($P.config.targetId,'f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f','ff4cbe405e70662b96fdb0e06d3f1704aa50a44a834c50a7f603c8deb3c7a096','9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f','6796847921a4c9542d8e2e2e7b203a72faae2a89d508ed6dd452ccb009baab2f')
    if($ExistingPublisher){$allowed+=$ExistingPublisher}
    foreach($protected in $P.approval.protectedRoles){$actual=Get-Adj41LocalTlsInspect $protected.id
        $actualNetworks=(@($actual.NetworkSettings.Networks.Values|ForEach-Object NetworkID)|Sort-Object)-join ','
        $expectedNetworks=(@($protected.networkIds)|Sort-Object)-join ','
        if(-not $actual.State.Running -or $actual.State.Pid -ne $protected.pid -or $actual.State.StartedAt -cne $protected.startedAt -or $actualNetworks -cne $expectedNetworks){throw 'LOCAL_TLS_PROTECTED_ROLE_DRIFT'}}
    [long]$memory=0;[long]$cpu=0
    foreach($id in $ids){if($id -cnotin $allowed){throw 'LOCAL_TLS_UNKNOWN_TARGET_WRITER'};$a=Get-Adj41LocalTlsInspect $id
        if(-not $a.State.Running -or $a.HostConfig.Memory -le 0 -or $a.HostConfig.NanoCpus -le 0){throw 'LOCAL_TLS_ACTUAL_LIMIT_REQUIRED'}
        $memory+=$a.HostConfig.Memory;$cpu+=$a.HostConfig.NanoCpus}
    if($P.config.targetId -cnotin $ids -or $allowed[1] -cnotin $ids){throw 'LOCAL_TLS_REQUIRED_RUNTIME_MISSING'}
    if(-not $ExistingPublisher -or $ExistingPublisher -cnotin $ids){$memory+=134217728;$cpu+=50000000}
    if($memory -gt 7516192768 -or $cpu -gt 4000000000){throw 'LOCAL_TLS_TOTAL_BUDGET_REFUSED'}
    return @{memoryBytes=$memory;nanoCpus=$cpu}
}
function Assert-Adj41LocalTlsTarget($P,[string]$ExistingPublisher='') {
    $null=Assert-FinalApproval $P.deployment;Assert-FinalExecutionInputs $P.deployment;Assert-FinalOwnedRuntime $P.deployment $P.deploymentPlan
    $a=Get-Adj41LocalTlsInspect $P.config.targetId
    if(-not $a.State.Running -or $a.State.Pid -le 0 -or $a.Image -cne $P.deployment.image){throw 'LOCAL_TLS_TARGET_NOT_RUNNING'}
    Assert-FinalCreatedApplication $P.config.targetId $P.app $P.deployment
    $expected=@{}+$P.app.values;$observed=@{}
    try {
        foreach($key in $P.app.secretRefs.Keys){$expected[$key]=Read-FinalManagedSecret $P.app.secretRefs[$key]}
        foreach($entry in $a.Config.Env){$pair=$entry.Split('=',2);if($pair.Count -eq 2){if($observed.Contains($pair[0])){throw 'LOCAL_TLS_TARGET_ENV_DRIFT'};$observed[$pair[0]]=$pair[1]}}
        foreach($key in $expected.Keys){if(-not $observed.Contains($key) -or $observed[$key] -cne [string]$expected[$key]){throw 'LOCAL_TLS_TARGET_ENV_DRIFT'}}
        foreach($key in $observed.Keys){if($key -clike 'SINGLE_DB_*' -and -not $expected.Contains($key)){throw 'LOCAL_TLS_TARGET_ENV_DRIFT'}}
    }finally{$expected.Clear();$observed.Clear()}
    $network=$a.NetworkSettings.Networks[$P.deployment.network]
    if($network.IPAddress -cnotmatch '^172\.25\.(?:[0-9]{1,3})\.(?:[0-9]{1,3})$' -or $network.DNSNames -cnotcontains $P.config.targetId.Substring(0,12) -or [string]::IsNullOrEmpty($network.EndpointID)){throw 'LOCAL_TLS_TARGET_DNS_REQUIRED'}
    $n=@(Invoke-FinalDocker @('network','inspect',$P.deployment.networkId)|ConvertFrom-Json -AsHashtable)[0]
    if($n.IPAM.Config.Count -ne 1 -or $n.IPAM.Config[0].Subnet -cne '172.25.0.0/16'){throw 'LOCAL_TLS_INTERNAL_SUBNET_DRIFT'}
    foreach($id in $n.Containers.Keys){if($id -ceq $P.config.targetId){continue};$peer=Get-Adj41LocalTlsInspect $id
        $binding=$peer.NetworkSettings.Networks[$P.deployment.network]
        if($binding.DNSNames -ccontains $P.config.targetId.Substring(0,12) -or $binding.Aliases -ccontains $P.config.targetId.Substring(0,12)){throw 'LOCAL_TLS_TARGET_DNS_COLLISION'}}
    $bridge=@(Invoke-FinalDocker @('network','inspect',$P.config.bridgeId)|ConvertFrom-Json -AsHashtable)[0]
    if($bridge.Id -cne $P.config.bridgeId -or $bridge.Name -cne 'bridge' -or $bridge.Internal -or $bridge.Driver -cne 'bridge' -or
       $bridge.Options.'com.docker.network.bridge.default_bridge' -cne 'true' -or $bridge.IPAM.Config.Count -ne 1 -or $bridge.IPAM.Config[0].Subnet -cne '172.17.0.0/16'){throw 'LOCAL_TLS_EXISTING_BRIDGE_REQUIRED'}
    $text="version=LOCAL_TLS_PUBLISH/v1`ntargetId=$($P.config.targetId)`ntargetDns=$($P.config.targetId.Substring(0,12))`ntargetIp=$($network.IPAddress)`ninternalSubnet=172.25.0.0/16`npublishSubnet=172.17.0.0/16`nlistenPort=8443`ntargetPort=8443`nmaxConnections=16`nconnectMillis=5000`nidleMillis=30000`n"
    if((Get-Content -LiteralPath $P.config.runtime.path -Raw) -cne $text){throw 'LOCAL_TLS_RUNTIME_TARGET_DRIFT'}
    $checks=@("test `"`$(stat -c '%u:%g:%a' /product/files/adjudication)`" = '10003:10003:700'")
    foreach($r in @(@{target='/app/platform-api.jar';sha256=$P.app.input.jar.sha256},@{target='/app/application.properties';sha256=$P.app.input.properties.sha256})+@($P.app.input.mounts)){
        $checks+="test -f '$($r.target)' && test ! -L '$($r.target)' && test `"`$(sha256sum '$($r.target)' | cut -d ' ' -f1)`" = '$($r.sha256)'"}
    $shell='if ! ('+($checks -join ' && ')+'); then exit 78; fi; echo LOCAL_TLS_TARGET_SIX_BYTES_OK'
    $output=Invoke-FinalDocker @('exec','--user','10003:10003',$P.config.targetId,'sh','-c',$shell)
    if([string]$output -cne 'LOCAL_TLS_TARGET_SIX_BYTES_OK'){throw 'LOCAL_TLS_TARGET_BYTES_REFUSED'}
    $budget=Assert-Adj41LocalTlsBudget $P $ExistingPublisher
    return @{targetIp=$network.IPAddress;endpointId=$network.EndpointID;budget=$budget}
}
function Get-Adj41LocalTlsCreateArguments($P) {
    return @('create','--name',$P.config.publisherName,'--user','10012:10012','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','128m','--cpus','0.05','--pids-limit','64','--sysctl','net.ipv4.ip_forward=0','--sysctl','net.ipv6.conf.all.forwarding=0','--restart','no','--network',$P.config.bridgeId,
        '--label','task=IMP-SINGLE-DB-FINAL-BASELINE-01','--label','agent-platform.role=adjudication-local-tls-publisher','--label','agent-platform.domain=adjudication','--label',"agent-platform.target-id=$($P.config.targetId)",'--label',"agent-platform.publication-sha256=$($P.sha)",
        '--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=16m,uid=10012,gid=10012,mode=700','--publish','127.0.0.1:5448:8443',
        '--mount',"type=bind,source=$($P.jarSource),target=/publisher/relay.jar,readonly",'--mount',"type=bind,source=$($P.runtimeSource),target=/publisher/runtime.properties,readonly",'--entrypoint','sh',$P.deployment.image,'-c',$P.launch)
}
function Assert-Adj41LocalTlsRuntimeBytes($P,[string]$Id) {
    $shell="if ! (test -f /publisher/relay.jar && test ! -L /publisher/relay.jar && test `"`$(sha256sum /publisher/relay.jar | cut -d ' ' -f1)`" = '$($P.config.jar.sha256)' && test -f /publisher/runtime.properties && test ! -L /publisher/runtime.properties && test `"`$(sha256sum /publisher/runtime.properties | cut -d ' ' -f1)`" = '$($P.config.runtime.sha256)'); then exit 78; fi; echo LOCAL_TLS_RUNTIME_BYTES_OK"
    if([string](Invoke-FinalDocker @('exec','--user','10012:10012',$Id,'sh','-c',$shell)) -cne 'LOCAL_TLS_RUNTIME_BYTES_OK'){throw 'LOCAL_TLS_ACTUAL_MOUNTED_BYTES_REQUIRED'}
}
function Assert-Adj41LocalTlsPublisher($P,[string]$Id,[switch]$Running,[switch]$BridgeOnly,[switch]$ForStop) {
    if($Id -cnotmatch '^[a-f0-9]{64}$' -or $Id -ceq $P.config.targetId){throw 'LOCAL_TLS_EXACT_PUBLISHER_ID_REQUIRED'}
    $a=Get-Adj41LocalTlsInspect $Id
    if($a.Id -cne $Id -or $a.Name -cne ('/'+$P.config.publisherName) -or $a.Image -cne $P.deployment.image -or
       $a.Config.User -cne '10012:10012' -or $a.Config.Labels.task -cne 'IMP-SINGLE-DB-FINAL-BASELINE-01' -or
       $a.Config.Labels.'agent-platform.role' -cne 'adjudication-local-tls-publisher' -or $a.Config.Labels.'agent-platform.domain' -cne 'adjudication' -or
       $a.Config.Labels.'agent-platform.target-id' -cne $P.config.targetId -or $a.Config.Labels.'agent-platform.publication-sha256' -cne $P.sha -or
       (@($a.Config.Entrypoint) -join ',') -cne 'sh' -or (@($a.Config.Cmd) -join "`n") -cne ('-c'+"`n"+$P.launch) -or
       $a.HostConfig.Privileged -or -not $a.HostConfig.ReadonlyRootfs -or @($a.HostConfig.CapDrop).Count -ne 1 -or $a.HostConfig.CapDrop[0] -cne 'ALL' -or
       ($a.HostConfig.CapAdd -and @($a.HostConfig.CapAdd).Count) -or $a.HostConfig.SecurityOpt -cnotcontains 'no-new-privileges' -or
       $a.HostConfig.Memory -ne 134217728 -or $a.HostConfig.NanoCpus -ne 50000000 -or $a.HostConfig.PidsLimit -ne 64 -or $a.HostConfig.RestartPolicy.Name -cne 'no' -or
       $a.HostConfig.NetworkMode -cne $P.config.bridgeId -or $a.HostConfig.Sysctls.Count -ne 2 -or
       $a.HostConfig.Sysctls.'net.ipv4.ip_forward' -cne '0' -or $a.HostConfig.Sysctls.'net.ipv6.conf.all.forwarding' -cne '0'){throw 'LOCAL_TLS_PUBLISHER_DRIFT'}
    $image=@(Invoke-FinalDocker @('image','inspect',$P.deployment.image)|ConvertFrom-Json -AsHashtable)[0]
    if(($image.Config.Volumes -and $image.Config.Volumes.Count) -or ((@($a.Config.Env)|Sort-Object)-join "`n") -cne ((@($image.Config.Env)|Sort-Object)-join "`n")){throw 'LOCAL_TLS_NO_SECRET_ENV_REQUIRED'}
    if($a.HostConfig.Mounts.Count -ne 2 -or $a.Mounts.Count -ne 2 -or $a.HostConfig.Tmpfs.Count -ne 1 -or $a.HostConfig.Tmpfs['/tmp'] -cne 'rw,nosuid,nodev,noexec,size=16m,uid=10012,gid=10012,mode=700'){throw 'LOCAL_TLS_NO_SECRET_MOUNTS_REQUIRED'}
    foreach($x in @(@('/publisher/relay.jar',$P.jarSource),@('/publisher/runtime.properties',$P.runtimeSource))){
        $mapped='/run/desktop/mnt/host/'+$x[1].Substring(0,1).ToLowerInvariant()+'/'+$x[1].Substring(3)
        $m=@($a.HostConfig.Mounts|Where-Object Target -CEQ $x[0]);$actual=@($a.Mounts|Where-Object Destination -CEQ $x[0])
        $created=$a.State.Status -ceq 'created' -and $a.State.Running -eq $false -and $a.State.Pid -eq 0
        # Docker Desktop may keep the exact handle-resolved Windows source even when running.
        # Such metadata alone is insufficient: every running admission separately verifies both mounted byte SHAs.
        $sourceMatches=$actual.Count -eq 1 -and ($actual[0].Source -ceq $mapped -or (($created -or $a.State.Running -or $ForStop) -and $actual[0].Source -ceq $x[1]))
        if($m.Count -ne 1 -or $m[0].Type -cne 'bind' -or $m[0].ReadOnly -ne $true -or $m[0].Source -cne $x[1] -or
           $actual.Count -ne 1 -or $actual[0].Type -cne 'bind' -or -not $sourceMatches -or $actual[0].RW -ne $false){throw 'LOCAL_TLS_REFERENCE_MOUNT_DRIFT'}}
    $ports=$a.HostConfig.PortBindings
    if($ports.Count -ne 1 -or @($ports['8443/tcp']).Count -ne 1 -or $ports['8443/tcp'][0].HostIp -cne '127.0.0.1' -or $ports['8443/tcp'][0].HostPort -cne '5448'){throw 'LOCAL_TLS_LOCALHOST_ONLY_REQUIRED'}
    $nets=$a.NetworkSettings.Networks;$count=if($BridgeOnly){1}else{2}
    if($nets.Count -ne $count -or -not $nets.Contains('bridge') -or (-not $BridgeOnly -and -not $nets.Contains($P.deployment.network))){throw 'LOCAL_TLS_EXACT_NETWORKS_REQUIRED'}
    foreach($binding in @(@('bridge',$P.config.bridgeId),@($P.deployment.network,$P.deployment.networkId))){
        if($BridgeOnly -and $binding[0] -cne 'bridge'){continue};$n=$nets[$binding[0]]
        if($n.NetworkID -cne $binding[1] -and -not ($a.State.Status -ceq 'created' -and [string]::IsNullOrEmpty($n.NetworkID))){throw 'LOCAL_TLS_EXACT_NETWORKS_REQUIRED'}}
    if($a.State.Running -and -not $ForStop){Assert-Adj41LocalTlsRuntimeBytes $P $Id}
    if($Running -and -not $ForStop){
        $p=@($a.NetworkSettings.Ports['8443/tcp'])
        if(-not $a.State.Running -or $a.State.Pid -le 0 -or $p.Count -ne 1 -or $p[0].HostIp -cne '127.0.0.1' -or $p[0].HostPort -cne '5448'){throw 'LOCAL_TLS_ACTUAL_LOCALHOST_PORT_REQUIRED'}
    }elseif(-not $ForStop -and $a.State.Status -cne 'created' -and -not $a.State.Running){throw 'LOCAL_TLS_PRESERVE_FAILED_PUBLISHER'}
    return $a
}
function Invoke-FinalAdj41LocalTlsPublisher($Config,[string]$ConfigSha,[switch]$ValidateOnly) {
    $p=Get-Adj41LocalTlsPublicationPlan $Config $ConfigSha
    $mutex=[Threading.Mutex]::new($false,('Local\AgentPlatform-Final-Recovery-'+$p.deployment.manifestSha256+'-adjudication'));$locked=$false;$id=''
    try {
        if(-not $mutex.WaitOne(0)){throw 'LOCAL_TLS_ALREADY_OWNED'};$locked=$true
        $names=@(Invoke-FinalDocker @('ps','-a','--format','{{.Names}}'))
        if($p.config.publisherName -cin $names){$id=(Get-Adj41LocalTlsInspect $p.config.publisherName).Id}
        $target=Assert-Adj41LocalTlsTarget $p $id
        if($ValidateOnly){return @{scope='EXACT_LOCAL_TLS_PUBLICATION_PREFLIGHT_ONLY';targetId=$Config.targetId;targetIp=$target.targetIp;budget=$target.budget;publisherCreated=$false;secretsInPublisher=$false}}
        if(-not $id){
            Assert-Adj41LocalTlsWindow $p.approval
            try{$created=[string](Invoke-FinalDocker (Get-Adj41LocalTlsCreateArguments $p));if($created -cnotmatch '^[a-f0-9]{64}$'){throw 'LOCAL_TLS_CREATE_RESULT_UNKNOWN'};$id=$created}
            catch{
                # Do not repeat create after an unknown exit; only exact-name/complete immutable binding may resume.
                $known=@(Invoke-FinalDocker @('ps','-a','--format','{{.Names}}'))
                if($p.config.publisherName -cnotin $known){throw 'LOCAL_TLS_CREATE_REFUSED_PRESERVED'}
                $id=(Get-Adj41LocalTlsInspect $p.config.publisherName).Id;$null=Assert-Adj41LocalTlsPublisher $p $id -BridgeOnly
                throw 'LOCAL_TLS_CREATE_RESULT_UNKNOWN_RECONCILE_REQUIRED'
            }
        }
        $actual=Get-Adj41LocalTlsInspect $id
        if($actual.NetworkSettings.Networks.Count -eq 1){
            $null=Assert-Adj41LocalTlsPublisher $p $id -BridgeOnly
            if($actual.State.Status -cne 'created'){throw 'LOCAL_TLS_CREATED_CONNECTION_REQUIRED'}
            Assert-Adj41LocalTlsWindow $p.approval;$null=Invoke-FinalDocker @('network','connect',$p.deployment.networkId,$id)
        }
        $actual=Assert-Adj41LocalTlsPublisher $p $id
        if(-not $actual.State.Running){
            $null=Assert-Adj41LocalTlsTarget $p $id;Assert-Adj41LocalTlsWindow $p.approval;$null=Invoke-FinalDocker @('start',$id)
        }
        $null=Assert-Adj41LocalTlsPublisher $p $id -Running
        $null=Assert-Adj41LocalTlsTarget $p $id
        $budget=Assert-Adj41LocalTlsBudget $p $id
        $final=Assert-Adj41LocalTlsPublisher $p $id -Running
        $finalObservedAt=(Get-FinalDeploymentUtc).ToString('o')
        return @{scope='ACTUAL_LOCAL_TLS_TRANSPORT_NOT_TLS_OR_BUSINESS_ACCEPTANCE';publisherId=$id;targetId=$Config.targetId;hostBinding='127.0.0.1:5448';tlsTerminated=$false;privateKeyMounted=$false;applicationEgressChanged=$false;budget=$budget;finalPublisherRunning=$final.State.Running;finalPublisherPid=$final.State.Pid;finalObservedAt=$finalObservedAt;businessVerified=$false}
    }finally{if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
function Stop-FinalAdj41LocalTlsPublisher($Config,[string]$ConfigSha,[string]$Id) {
    # Stop requires original immutable publication binding, not target health or a new business authority.
    $p=Get-Adj41LocalTlsPublicationPlan $Config $ConfigSha -ForStop;Assert-Adj41LocalTlsWindow $p.approval
    $mutex=[Threading.Mutex]::new($false,('Local\AgentPlatform-Final-Recovery-'+$p.deployment.manifestSha256+'-adjudication'));$locked=$false
    try{if(-not $mutex.WaitOne(0)){throw 'LOCAL_TLS_ALREADY_OWNED'};$locked=$true
        $before=Assert-Adj41LocalTlsPublisher $p $Id -ForStop;if(-not $before.State.Running){return @{scope='EXACT_PUBLISHER_ALREADY_STOPPED_PRESERVED';publisherId=$Id;preserved=$true;originalApplicationStopped=$false}};Assert-Adj41LocalTlsWindow $p.approval
        $null=Invoke-FinalDocker @('stop','--time','10',$Id);$a=Get-Adj41LocalTlsInspect $Id
        if($a.State.Running -or $a.State.Pid -ne 0){throw 'LOCAL_TLS_STOP_UNCERTAIN'}
        return @{scope='EXACT_PUBLISHER_REVERSIBLE_STOP_ONLY';publisherId=$Id;preserved=$true;originalApplicationStopped=$false}
    }finally{if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
}


