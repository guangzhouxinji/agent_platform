# Final three-domain launcher: no DDL, grants, resource creation/replacement or business acceptance.
. (Join-Path $PSScriptRoot 'single-database-deployment.ps1')
. (Join-Path $PSScriptRoot 'single-database-owned-image-admission.ps1')
. (Join-Path $PSScriptRoot 'single-database-windows-reference.ps1')
function Read-FinalDeploymentJson($Path) {
    try { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable -DateKind String } catch { throw 'FINAL_INPUT_UNAVAILABLE' }
}
function Assert-FinalFile($Reference) {
    if($Reference -isnot [Collections.IDictionary] -or $Reference.sha256 -cnotmatch '^[a-f0-9]{64}$' -or -not [IO.Path]::IsPathRooted([string]$Reference.path)){throw 'FINAL_ABSOLUTE_SHA_REFERENCE_REQUIRED'}
    if(@($Reference.Keys|Where-Object {$_ -cnotin @('path','sha256','containerSource','target')}).Count){throw 'FINAL_UNREGISTERED_REFERENCE_FIELD'}
    $item=Get-Item -LiteralPath $Reference.path -ErrorAction Stop
    if($item.PSIsContainer){throw 'FINAL_REGULAR_FILE_REQUIRED'}
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'FINAL_REDIRECT_REFUSED'}
    $part=$item.Directory
    while($null -ne $part){if($part.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'FINAL_REDIRECT_REFUSED'};$part=$part.Parent}
    if((Get-FileHash -LiteralPath $Reference.path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Reference.sha256){throw 'FINAL_FILE_SHA_MISMATCH'}
}
function Get-FinalWindowsReferencePath([string]$Path) {
    # Do not resolve dot segments, UNC/device paths, ADS, commas or Windows alias spellings into approval.
    if($Path -cnotmatch '^[A-Za-z]:[\\/][A-Za-z0-9_ .\\/-]+$'){throw 'FINAL_WINDOWS_REFERENCE_PATH_REQUIRED'}
    $normalized=$Path.Replace('\','/')
    $segments=$normalized.Substring(3).Split('/')
    foreach($segment in $segments){
        if(-not $segment -or $segment -cin @('.','..') -or $segment -match '^[ .]|[ .]$' -or
           $segment -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)'){throw 'FINAL_WINDOWS_REFERENCE_PATH_REQUIRED'}
    }
    return $normalized.Substring(0,1).ToUpperInvariant()+$normalized.Substring(1)
}
function Get-FinalReferenceBinding($Reference,[string]$Domain,[string]$SecretRoot) {
    if($Domain -cnotin @('consultation','enforcement','adjudication') -or $Reference -isnot [Collections.IDictionary] -or
       $Reference.target -cnotmatch "^/product/refs/$Domain/[a-zA-Z0-9_.-]+$" -or
       ($Reference.target.Split('/')[-1] -cin @('.','..'))){throw 'FINAL_DOMAIN_READONLY_REFERENCE_REQUIRED'}
    $source=[string]$Reference.containerSource
    if($source -cmatch "^/mnt/docker-desktop-disk/agent-platform-single-final/$Domain/[a-zA-Z0-9_./-]+$" -and $source -cnotmatch '/\.\.?(/|$)'){
        return @{source=$source;inspectSource=$source;windows=$false}
    }
    $source=Get-FinalWindowsReferencePath $source
    $file=Get-FinalWindowsReferencePath ([string]$Reference.path)
    $root=Get-FinalWindowsReferencePath $SecretRoot
    if($source -cne $file -or -not $source.StartsWith($root+'/'+$Domain+'/',[StringComparison]::Ordinal)){throw 'FINAL_MANAGED_WINDOWS_REFERENCE_REQUIRED'}
    $source=Get-FinalPhysicalWindowsReference $Reference $root
    # Observed Docker Desktop drive mapping; only the drive letter is lowered, not file path segments.
    $mapped='/run/desktop/mnt/host/'+$source.Substring(0,1).ToLowerInvariant()+'/'+$source.Substring(3)
    return @{source=$source;inspectSource=$mapped;windows=$true}
}

function Assert-FinalNoModelRuntime([string]$Domain,$Settings) {
    # Deployment admission, not source/task/Worker/model use authorization.
    $external=@('product.consultation.model.enabled')
    $local=@()
    switch -CaseSensitive ($Domain) {
        'consultation' { $external+=@('product.consultation.comparison.calculation.enabled');$local+=@('product.source.semantic.enabled','product.source.field-reranker.enabled') }
        'enforcement' { }
        'adjudication' { $external+=@('adjudication.v2.materials.enabled','adjudication.v2.materials.decisions.enabled') }
        default { throw 'FINAL_MODEL_DOMAIN_REQUIRED' }
    }
    if($Domain -ceq 'adjudication' -and [string]$Settings['adjudication.v2.materials.decisions.enabled'] -ieq 'true' -and [string]$Settings['adjudication.v2.materials.enabled'] -ine 'true'){throw 'FINAL_MODEL_DEPENDENCY_INVALID'}
    if($Domain -ceq 'consultation' -and [string]$Settings['product.source.field-reranker.enabled'] -ieq 'true' -and [string]$Settings['product.source.engine.enabled'] -ine 'true'){throw 'FINAL_MODEL_DEPENDENCY_INVALID'}
    foreach($key in @($external+$local)) {
        if(-not $Settings.Contains($key)){throw 'FINAL_EXPLICIT_MODEL_BRANCH_REQUIRED'}
        if([string]$Settings[$key] -ine 'false'){throw 'FINAL_MODEL_ADMISSION_NOT_GRANTED'}
    }
    # Additional deny guard for unrecognized future providers.
    foreach($key in $Settings.Keys){if($key -match '(?:model|deepseek).*enabled$' -and [string]$Settings[$key] -ine 'false'){throw 'FINAL_MODEL_ADMISSION_NOT_GRANTED'}}
}

function Get-FinalDeploymentUtc { return [DateTimeOffset]::UtcNow }
function Assert-FinalApproval($Config) {
    Assert-FinalFile $Config.approval; Assert-FinalFile $Config.request
    if($Config.approval.sha256 -cne '649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7'){throw 'FINAL_ORIGINAL_APPROVAL_SHA_REQUIRED'}
    $a=Read-FinalDeploymentJson $Config.approval.path; $r=Read-FinalDeploymentJson $Config.request.path
    if($a.format -cne 'SINGLE_DATABASE_RESOURCE_APPROVAL/v1' -or $a.approvalGranted -ne $true -or $a.taskId -cne 'IMP-SINGLE-DB-FINAL-BASELINE-01' -or
       $a.requestOriginalSha256 -cne $Config.request.sha256 -or $Config.request.sha256 -cne '8c6c5b46f977732b4dcb1825b54620ae4edac1b700004a3c2f967a70738d3a17' -or
       $a.approvedLimits.totalMemoryBytes -ne 7516192768 -or $a.approvedLimits.totalNanoCpuLimit -ne 4000000000 -or $a.approvedLimits.concurrentDm8Instances -ne 1 -or $r.newNetwork -cne $Config.network){throw 'FINAL_ORIGINAL_APPROVAL_SCOPE_REQUIRED'}
    $from=[DateTimeOffset]::Parse($a.operationWindowBeijing.from);$until=[DateTimeOffset]::Parse($a.operationWindowBeijing.until);$now=Get-FinalDeploymentUtc
    if($from -ne [DateTimeOffset]::Parse('2026-10-08T19:00:00Z') -or $until -ne [DateTimeOffset]::Parse('2026-10-10T10:00:00Z') -or $now -lt $from -or $now -ge $until){throw 'FINAL_APPROVAL_WINDOW_REFUSED'}
    return @{approval=$a;request=$r}
}
function Invoke-FinalInstallationCheck($Config) {
    # Reuses real compiler full-structure/effective-grant validation, not an approximate second catalog.
    $code=@"
import {readFile} from 'node:fs/promises';
import {compileInstallation} from './scripts/lib/single-database-installation.mjs';
import {canonical,hash} from './scripts/lib/single-database-lifecycle-runtime.mjs';
try {const [requestFile,compiledFile,receiptFile]=process.argv.slice(1);const q=JSON.parse(await readFile(requestFile,'utf8'));const c=JSON.parse(await readFile(compiledFile,'utf8'));const receipt=JSON.parse(await readFile(receiptFile,'utf8'));if(q.stage!=='application-settings'||receipt.inventorySha256!==hash(canonical(q.inventory)))throw Error();const result=await compileInstallation({...q,workspaceRoot:process.cwd()});if(c.format!==result.format||c.stage!==result.stage||c.executionAuthorized!==false||c.manifestSha256!==hash(canonical(c.manifest))||canonical(c)!==canonical(result))throw Error();console.log('FINAL_COMPILER_INVENTORY_OK');} catch {console.error('FINAL_COMPILER_INVENTORY_REFUSED');process.exitCode=1;}
"@
    $result=& node --input-type=module -e $code $Config.installationRequest.path $Config.compiledInstallation.path $Config.inventoryReceipt.path 2>&1
    if($LASTEXITCODE -ne 0 -or [string]$result -cne 'FINAL_COMPILER_INVENTORY_OK'){throw 'FINAL_COMPILER_INVENTORY_REFUSED'}
}
function Get-FinalDeploymentPlan($Config) {
    if(@($Config.Keys|Where-Object {$_ -cnotin @('schema','approval','request','compiledInstallation','installationRequest','initializationReceipt','inventoryReceipt','manifestSha256','releaseKind','releaseEvidence','image','network','networkId','secretRoot','filesVolume','applications')}).Count){throw 'FINAL_UNREGISTERED_FIELD'}
    if($Config.schema -cne 'SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1'){throw 'FINAL_DEPLOYMENT_VERSION_REQUIRED'}
    $approved=Assert-FinalApproval $Config
    foreach($ref in @($Config.compiledInstallation,$Config.installationRequest,$Config.initializationReceipt,$Config.inventoryReceipt)){Assert-FinalFile $ref}
    Invoke-FinalInstallationCheck $Config
    $compiled=Read-FinalDeploymentJson $Config.compiledInstallation.path; $m=$compiled.manifest; $d=$m.deployment
    if($m.format -cne 'SINGLE_DATABASE_BOUND_APPLICATION_SETTINGS/v1' -or $m.containsSecretValues -ne $false -or $compiled.manifestSha256 -cne $Config.manifestSha256 -or $d.schema -cne 'SINGLE_DM8_DEPLOYMENT/v2'){throw 'FINAL_MANIFEST_BINDING_REQUIRED'}
    $plan=Get-SingleDatabaseDeploymentPlan $d;$jdbc='jdbc:dm://agent-platform-single-final-primary-20261009:5236'
    if($d.database.jdbcUrl -cne $jdbc -or $d.database.ownerTask -cne 'IMP-SINGLE-DB-FINAL-BASELINE-01' -or $d.database.volume -cne 'agent-platform-single-final-primary-data-20261009' -or $plan.accountCount -ne 15){throw 'FINAL_NEW_PRIMARY_REQUIRED'}
    foreach($ref in @($Config.initializationReceipt,$Config.inventoryReceipt)){
        $receipt=Read-FinalDeploymentJson $ref.path
        if($receipt.targetId -cne $m.targetId -or $receipt.manifestSha256 -cne $Config.manifestSha256 -or $receipt.installationRequestSha256 -cne $Config.installationRequest.sha256 -or $receipt.actualJdbc -ne $true -or $receipt.complete -ne $true -or $receipt.errors -isnot [Array] -or $receipt.errors.Count -ne 0){throw 'FINAL_INITIALIZATION_INVENTORY_EVIDENCE_REQUIRED'}
        if($receipt.capturedAt -isnot [string] -or $receipt.capturedAt -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$'){throw 'FINAL_ABSOLUTE_CAPTURE_TIME_REQUIRED'}
        $captured=[DateTimeOffset]::Parse($receipt.capturedAt)
        if($captured -gt (Get-FinalDeploymentUtc) -or $captured -lt [DateTimeOffset]::Parse('2026-10-08T19:00:00Z') -or ($ref.path -ceq $Config.inventoryReceipt.path -and $captured -lt (Get-FinalDeploymentUtc).AddMinutes(-15))){throw 'FINAL_INVENTORY_STALE'}
    }
    $init=Read-FinalDeploymentJson $Config.initializationReceipt.path
    $inventoryEvidence=Read-FinalDeploymentJson $Config.inventoryReceipt.path
    if($init.format -cne 'SINGLE_DATABASE_APPLICATION_INITIALIZATION/v1' -or $inventoryEvidence.format -cne 'SINGLE_DATABASE_APPLICATION_INVENTORY/v1'){throw 'FINAL_PREREQUISITE_FORMAT_REQUIRED'}
    foreach($kind in @('database','files')){
        $storage=$init.storage[$kind];Assert-FinalFile $storage.proof;$volumeName=if($kind -ceq 'database'){$d.database.volume}else{$Config.filesVolume}
        if($storage.device -cnotmatch '^/dev/agent-platform-single-final-[a-z0-9-]+-loop$' -or $storage.volume -cne $volumeName -or $storage.hardLimitBytes -ne 3221225472 -or $storage.enospcVerified -ne $true -or $storage.noExpansionPath -ne $true -or $storage.filesystemUuid -cnotmatch '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' ){throw 'FINAL_HARD_QUOTA_EVIDENCE_REQUIRED'}
        if($storage.backingKind -ceq 'owned-image-volume'){$null=Read-FinalOwnedImageBinding $storage $m.targetId}
        elseif($storage.backingKind -or $storage.backingFile -cnotmatch '^/mnt/docker-desktop-disk/[a-zA-Z0-9_./-]+\.ext4$' -or $storage.backingFile -match '/\.\.?(/|$)'){throw 'FINAL_HARD_QUOTA_EVIDENCE_REQUIRED'}
    }
    if($init.storage.database.subpaths -isnot [Array] -or ($init.storage.database.subpaths -join ',') -cne 'db,logs'){throw 'FINAL_DB_STORAGE_SUBPATH_REQUIRED'}
    if($init.storage.files.directories.Count -ne 3){throw 'FINAL_FILES_UID_EVIDENCE_REQUIRED'}
    foreach($binding in @(@('consultation',10001),@('enforcement',10002),@('adjudication',10003))){$dir=$init.storage.files.directories[$binding[0]];if($dir.subpath -cne ('files/'+$binding[0]) -or $dir.uid -ne $binding[1] -or $dir.gid -ne $binding[1] -or $dir.mode -cne '0700'){throw 'FINAL_FILES_UID_EVIDENCE_REQUIRED'}}
    if($Config.releaseKind -cnotin @('engineering-candidate','final-release') -or $Config.image -cne $approved.request.pinnedLocalImageReferences.jdkToolAndApplicationBase -or $Config.networkId -cnotmatch '^[a-f0-9]{64}$' -or $Config.network -cne 'agent-platform-single-final-20261009' -or $Config.secretRoot -cne $approved.request.managedCredentialRootRequested){throw 'FINAL_RELEASE_RESOURCE_BINDING_REQUIRED'}
    if($Config.releaseKind -ceq 'final-release'){
        Assert-FinalFile $Config.releaseEvidence;$e=Read-FinalDeploymentJson $Config.releaseEvidence.path
        if($e.format -cne 'SINGLE_DATABASE_FINAL_RELEASE/v1' -or $e.manifestSha256 -cne $Config.manifestSha256 -or $e.ready -ne $true){throw 'FINAL_RELEASE_EVIDENCE_REQUIRED'}
    }
    # Complete shared installation stays mandatory; only application startup can be phased.
    $domains=@('consultation','enforcement','adjudication')
    if($Config.applications -isnot [Array] -or $Config.applications.Count -lt 1 -or $Config.applications.Count -gt 3 -or @($m.configurations).Count -ne 3){throw 'FINAL_APPROVED_APPLICATION_SUBSET_REQUIRED'}
    $selected=@($Config.applications|ForEach-Object domain)
    if(@($selected|Where-Object {$_ -cnotin $domains}).Count -or @($selected|Select-Object -Unique).Count -ne $selected.Count){throw 'FINAL_DISTINCT_APPLICATIONS_REQUIRED'}
    $apps=@()
    foreach($domain in $domains){
        if($domain -cnotin $selected){continue}
        $index=[Array]::IndexOf($domains,$domain)
        $matches=@($Config.applications|Where-Object domain -CEQ $domain);$settingsMatches=@($m.configurations|Where-Object domain -CEQ $domain)
        if($matches.Count -ne 1 -or $settingsMatches.Count -ne 1){throw 'FINAL_DISTINCT_APPLICATIONS_REQUIRED'}
        $a=$matches[0];if(@($a.Keys|Where-Object {$_ -cnotin @('domain','name','uid','jar','properties','environment','secrets','files','mounts')}).Count){throw 'FINAL_UNREGISTERED_APPLICATION_FIELD'};$c=$settingsMatches[0];$b=@($d.domains|Where-Object domain -CEQ $domain)[0];$approvalApp=@($approved.approval.approvedApplications|Where-Object domain -CEQ $domain)[0]
        $uid=10001+$index;$port=5446+$index
        $main=switch($domain){consultation {'cn.xinji.agentplatform.security.product.ProductConsultationApplication'} enforcement {'cn.xinji.agentplatform.security.product.ProductEnforcementApplication'} adjudication {'cn.xinji.agentplatform.adjudication.casework.ProductAdjudicationApplication'}}
        if($c.mainClass -cne $main -or $a.name -cne $approvalApp.containerName -or $approvalApp.hostBinding -cne "127.0.0.1:$port" -or $approvalApp.memoryLimitBytes -ne 805306368 -or $approvalApp.nanoCpuLimit -ne 500000000 -or $a.uid -ne $uid){throw 'FINAL_MAIN_RESOURCE_BINDING_REQUIRED'}
        Assert-FinalFile $a.jar; Assert-FinalFile $a.properties
        if($init.storage.database.backingKind -ceq 'owned-image-volume'){Assert-FinalSelectedIdentityBootstrap $init $domain $m.targetId $a.jar.sha256}
        if((Get-Content -LiteralPath $a.properties.path -Raw) -cne $c.propertiesContent){throw 'FINAL_PROPERTIES_MUST_MATCH_COMPILER'}
        if($Config.releaseKind -ceq 'final-release'){$artifact=@($e.applications|Where-Object domain -CEQ $domain);if($artifact.Count -ne 1 -or $artifact[0].jarSha256 -cne $a.jar.sha256){throw 'FINAL_RELEASE_ARTIFACT_BINDING_REQUIRED'}}
        if(@($a.files.Keys|Where-Object {$_ -cnotin @('volume','subpath','target')}).Count){throw 'FINAL_UNREGISTERED_FILES_FIELD'}
        $values=@{};$secretRefs=@{};$expected=@($c.requiredEnvironment|ForEach-Object environment)
        if($a.environment -isnot [Collections.IDictionary] -or $a.secrets -isnot [Collections.IDictionary] -or @($a.environment.Keys|Where-Object {$_ -cnotin $expected}).Count -or @($a.secrets.Keys|Where-Object {$_ -cnotin $expected}).Count){throw 'FINAL_UNREGISTERED_ENVIRONMENT_REFUSED'}
        foreach($entry in $c.requiredEnvironment){
            $key=$entry.environment
            if($entry.valueSource -ceq 'managed-secret'){
                if($a.environment.Contains($key) -or -not $a.secrets.Contains($key)){throw 'FINAL_MANAGED_SECRET_REQUIRED'}
                $ref=$a.secrets[$key];Assert-FinalFile $ref
                $root=[IO.Path]::GetFullPath($Config.secretRoot).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
                if(-not [IO.Path]::GetFullPath($ref.path).StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw 'FINAL_SECRET_ROOT_REQUIRED'}
                $secretRefs[$key]=$ref
            } elseif($entry.valueSource -ceq 'deployment-config'){
                if(-not $a.environment.Contains($key) -or $a.secrets.Contains($key) -or [string]::IsNullOrWhiteSpace([string]$a.environment[$key]) -or [string]$a.environment[$key] -match '[\r\n\x00]'){throw 'FINAL_COMPLETE_CONFIG_REQUIRED'}
                $values[$key]=[string]$a.environment[$key]
            } else {throw 'FINAL_UNKNOWN_VALUE_SOURCE'}
        }
        $resolved=@{}
        foreach($key in $c.settings.Keys){$v=$c.settings[$key];if($v -is [string] -and $v -cmatch '^\$\{([A-Z][A-Z0-9_]+)\}$'){$envKey=$Matches[1];if($values.Contains($envKey)){$v=$values[$envKey]}elseif($secretRefs.Contains($envKey)){$v='MANAGED_SECRET'}else{throw 'FINAL_UNRESOLVED_PROPERTY'}};$resolved[$key]=$v}
        foreach($pair in @(@('spring.datasource.url',$jdbc),@('spring.datasource.username',$b.business.runtimeUser),@('spring.datasource.hikari.schema',$b.business.schema),@('product.identity.authority.jdbc-url',$jdbc),@('product.identity.authority.username',$b.authority.runtimeUser),@('product.identity.authority.schema',$b.authority.schema),@('product.identity.authority.topology','shared-instance'),@('product.identity.issuer',$b.issuer))){if([string]$resolved[$pair[0]] -cne [string]$pair[1]){throw 'FINAL_RUNTIME_SCHEMA_ISSUER_REQUIRED'}}
        if([string]$resolved['server.ssl.client-auth'] -cnotin @('want','need')){throw 'FINAL_TLS_CLIENT_AUTH_REQUIRED'}
        if($b.issuer -cne "https://$domain.agent-platform.internal:$port" -or [string]$resolved['server.ssl.enabled'] -ine 'true' -or [string]$resolved['server.port'] -cne '8443' -or [string]$resolved['spring.flyway.enabled'] -ine 'false' -or [string]$resolved['spring.liquibase.enabled'] -ine 'false' -or $resolved['spring.sql.init.mode'] -cne 'never'){throw 'FINAL_TLS_NO_INITIALIZATION_REQUIRED'}
        Assert-FinalNoModelRuntime $domain $resolved
        if($a.files.volume -cne $Config.filesVolume -or $a.files.subpath -cne "files/$domain" -or $a.files.target -cne "/product/files/$domain" -or ($Config.filesVolume -cne $d.database.volume -and $Config.filesVolume -cnotmatch '^agent-platform-single-final-files-[a-z0-9-]+$')){throw 'FINAL_DOMAIN_SUBPATH_REQUIRED'}
        if(@($a.mounts).Count -lt 2){throw 'FINAL_DOMAIN_CERTIFICATE_REFERENCES_REQUIRED'}
        $targets=@('/app/platform-api.jar','/app/application.properties',"/product/files/$domain")
        foreach($mount in $a.mounts){
            $null=Get-FinalReferenceBinding $mount $domain $Config.secretRoot
            if($mount.target -cin $targets){throw 'FINAL_DOMAIN_READONLY_REFERENCE_REQUIRED'}
            Assert-FinalFile $mount
            $targets+=$mount.target
        }
        foreach($key in @('server.ssl.key-store','server.ssl.trust-store')){if(([string]$resolved[$key] -replace '^file:/*','/') -cnotin $targets){throw 'FINAL_TLS_MOUNT_REQUIRED'}}
        foreach($key in $resolved.Keys){if($resolved[$key] -is [string] -and ($resolved[$key] -match '^/' -or $resolved[$key] -match '^file:/')){$path=[string]$resolved[$key] -replace '^file:/*','/';if($path -match '/\.\.?(/|$)' -or ($path -cnotin $targets -and $path -cne "/product/files/$domain" -and -not $path.StartsWith("/product/files/$domain/",[StringComparison]::Ordinal))){throw 'FINAL_PROPERTY_FILE_REFERENCE_OUTSIDE_DOMAIN'}}}
        foreach($ref in @($a.jar,$a.properties)){if($ref.containerSource -cnotmatch "^/mnt/docker-desktop-disk/agent-platform-single-final/$domain/[a-zA-Z0-9_.-]+$" ){throw 'FINAL_ARTIFACT_CONTAINER_SOURCE_REQUIRED'}}
        $apps+=@{input=$a;configuration=$c;values=$values;secretRefs=$secretRefs;uid=$uid;port=$port}
    }
    return @{applications=$apps;deployment=$d;releaseKind=$Config.releaseKind;manifestSha256=$Config.manifestSha256}
}
function Read-FinalManagedSecret($Reference) {
    Assert-FinalFile $Reference
    try {
        if([IO.Path]::GetExtension($Reference.path) -ceq '.dpapi'){
            # ConvertFrom-SecureString current-user DPAPI text is commonly saved with a terminal newline.
            $encrypted=(Get-Content -LiteralPath $Reference.path -Raw -ErrorAction Stop).Trim()
            if($encrypted -cnotmatch '^01000000d08c9ddf[0-9a-fA-F]+$'){throw 'INVALID'}
            $credential=ConvertTo-SecureString -String $encrypted -ErrorAction Stop
        } elseif([IO.Path]::GetExtension($Reference.path) -ceq '.xml'){
            $credential=Import-Clixml -LiteralPath $Reference.path -ErrorAction Stop
        } else {throw 'INVALID'}
        if($credential -is [PSCredential]){$credential=$credential.Password}
        if($credential -isnot [Security.SecureString]){throw 'INVALID'}
        $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($credential)
        try {$value=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer);if([string]::IsNullOrWhiteSpace($value)){throw 'INVALID'};return $value} finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
    } catch {throw 'FINAL_DPAPI_SECRET_UNAVAILABLE'} finally {$credential=$null;$encrypted=$null}
}
function Invoke-FinalDocker([string[]]$Arguments) {
    $output=& docker @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw 'FINAL_DOCKER_OPERATION_FAILED'}
    return $output
}
function Assert-FinalExecutionInputs($Config) {
    $null=Assert-FinalApproval $Config
    foreach($ref in @($Config.compiledInstallation,$Config.installationRequest,$Config.initializationReceipt,$Config.inventoryReceipt)){Assert-FinalFile $ref}
    if($Config.releaseKind -ceq 'final-release'){Assert-FinalFile $Config.releaseEvidence}
    $receipt=Read-FinalDeploymentJson $Config.inventoryReceipt.path
    if([DateTimeOffset]::Parse($receipt.capturedAt) -lt (Get-FinalDeploymentUtc).AddMinutes(-15)){throw 'FINAL_INVENTORY_STALE'}
    $init=Read-FinalDeploymentJson $Config.initializationReceipt.path;foreach($kind in @('database','files')){Assert-FinalFile $init.storage[$kind].proof}
    foreach($a in $Config.applications){foreach($mount in $a.mounts){$null=Get-FinalReferenceBinding $mount $a.domain $Config.secretRoot};foreach($ref in @($a.jar,$a.properties)+@($a.mounts)+@($a.secrets.Values)){Assert-FinalFile $ref}}
}
function Assert-FinalApplicationNetwork($Actual,$App,$Config) {
    $a=$App.input;$networks=$Actual.NetworkSettings.Networks
    if($networks -isnot [Collections.IDictionary] -or $networks.Count -ne 1 -or -not $networks.Contains($Config.network) -or $Actual.HostConfig.NetworkMode -cne $Config.networkId){throw 'FINAL_CREATED_NETWORK_ALIAS_DRIFT'}
    $network=$networks[$Config.network]
    $pending=$Actual.State.Status -ceq 'created' -and $Actual.State.Running -eq $false -and $Actual.State.Pid -eq 0 -and [string]::IsNullOrEmpty([string]$network.EndpointID)
    if(($network.NetworkID -cne $Config.networkId -and -not ($pending -and [string]::IsNullOrEmpty([string]$network.NetworkID))) -or
       @($network.Aliases|Where-Object {$_ -ceq "$($a.domain).agent-platform.internal"}).Count -ne 1 -or
       @($network.Aliases|Where-Object {$_ -clike '*.agent-platform.internal' -and $_ -cne "$($a.domain).agent-platform.internal"}).Count){throw 'FINAL_CREATED_NETWORK_ALIAS_DRIFT'}
}
function Assert-FinalCreatedApplication($Id,$App,$Config,[switch]$RetainedFailedInstance) {
    $actual=@(Invoke-FinalDocker @('inspect',$Id)|ConvertFrom-Json -AsHashtable)[0];$a=$App.input
    if($RetainedFailedInstance -and ($actual.State.Status -cne 'exited' -or $actual.State.Running -ne $false -or $actual.State.Pid -ne 0 -or $actual.State.ExitCode -eq 0)){throw 'FINAL_ONLY_RETAINED_FAILED_INSTANCE_REQUIRED'}
    if($actual.Id -cne $Id -or $actual.Name -cne ('/'+$a.name) -or $actual.Config.User -cne "$($App.uid):$($App.uid)" -or
       $actual.Config.Labels.task -cne 'IMP-SINGLE-DB-FINAL-BASELINE-01' -or $actual.Config.Labels.'agent-platform.domain' -cne $a.domain -or
       $actual.Config.Labels.'agent-platform.manifest-sha256' -cne $Config.manifestSha256 -or $actual.Config.Labels.'agent-platform.release-kind' -cne $Config.releaseKind -or
       $actual.HostConfig.Privileged -or -not $actual.HostConfig.ReadonlyRootfs -or $actual.HostConfig.CapDrop.Count -ne 1 -or $actual.HostConfig.CapDrop[0] -cne 'ALL' -or
       $actual.HostConfig.Memory -ne 805306368 -or $actual.HostConfig.NanoCpus -ne 500000000 -or $actual.HostConfig.NetworkMode -cne $Config.networkId -or
       $actual.HostConfig.RestartPolicy.Name -cne 'no' -or $actual.HostConfig.SecurityOpt -cnotcontains 'no-new-privileges' -or
       (@($actual.Config.Entrypoint) -join ',') -cne 'sh' -or (@($actual.Config.Cmd) -join "`n") -cne ('-c'+"`n"+$App.launchCommand)){throw 'FINAL_CREATED_CONTAINER_DRIFT'}
    Assert-FinalApplicationNetwork $actual $App $Config
    $ports=$actual.HostConfig.PortBindings
    if($ports.Count -ne 1 -or @($ports['8443/tcp']).Count -ne 1 -or $ports['8443/tcp'][0].HostIp -cne '127.0.0.1' -or $ports['8443/tcp'][0].HostPort -cne [string]$App.port){throw 'FINAL_CREATED_PORT_DRIFT'}
    $mounts=@($actual.HostConfig.Mounts)
    if($mounts.Count -ne 3+@($a.mounts).Count){throw 'FINAL_CREATED_MOUNT_DRIFT'}
    $files=@($mounts|Where-Object Target -CEQ $a.files.target)
    if($files.Count -ne 1 -or $files[0].Type -cne 'volume' -or $files[0].Source -cne $a.files.volume -or $files[0].ReadOnly -or $files[0].VolumeOptions.Subpath -cne $a.files.subpath -or -not $files[0].VolumeOptions.NoCopy){throw 'FINAL_CREATED_FILES_DRIFT'}
    # Artifacts stay on the VM release root; only ordinary mounts get managed Windows references.
    foreach($ref in @(@{containerSource=$a.jar.containerSource;target='/app/platform-api.jar'},@{containerSource=$a.properties.containerSource;target='/app/application.properties'})){
        $mount=@($mounts|Where-Object Target -CEQ $ref.target)
        if($mount.Count -ne 1 -or $mount[0].Type -cne 'bind' -or -not $mount[0].ReadOnly -or $mount[0].Source -cne $ref.containerSource){throw 'FINAL_CREATED_REFERENCE_DRIFT'}
    }
    foreach($ref in $a.mounts){
        if($RetainedFailedInstance){
            $logical=Get-FinalWindowsReferencePath $ref.path
            if((Get-FinalWindowsReferencePath $ref.containerSource) -cne $logical){throw 'FINAL_RETAINED_REFERENCE_DRIFT'}
            $binding=@{windows=$true;source=$logical;inspectSource=('/run/desktop/mnt/host/'+$logical.Substring(0,1).ToLowerInvariant()+'/'+$logical.Substring(3))}
        }else{$binding=Get-FinalReferenceBinding $ref $a.domain $Config.secretRoot}
        $mount=@($mounts|Where-Object Target -CEQ $ref.target)
        if($mount.Count -ne 1 -or $mount[0].Type -cne 'bind' -or -not $mount[0].ReadOnly){throw 'FINAL_CREATED_REFERENCE_DRIFT'}
        if($binding.windows){
            # HostConfig may retain the client Windows source or the daemon-translated path.
            $hostSource=[string]$mount[0].Source
            if($hostSource -cne $binding.inspectSource -and (Get-FinalWindowsReferencePath $hostSource) -cne $binding.source){throw 'FINAL_CREATED_REFERENCE_DRIFT'}
            # Actual mount inventory MUST identify the observed mapped daemon source, readonly.
            $actualMount=@($actual.Mounts|Where-Object Destination -CEQ $ref.target)
            if($actualMount.Count -ne 1 -or $actualMount[0].Type -cne 'bind' -or $actualMount[0].RW -ne $false -or $actualMount[0].Source -cne $binding.inspectSource){throw 'FINAL_CREATED_REFERENCE_DRIFT'}
        } elseif($mount[0].Source -cne $binding.inspectSource){throw 'FINAL_CREATED_REFERENCE_DRIFT'}
    }
}
function Assert-FinalDatabaseMounts($Db,[string]$Volume) {
    $entryPath=Join-Path $PSScriptRoot 'single-database-owned-dm8-entry.py'
    Assert-FinalFile @{path=$entryPath;sha256='c4e74293652039522c4b2b8918efbb208b4551f09e0ca8012d5ab42029ac157b'}
    $source=Get-FinalWindowsReferencePath $entryPath
    $mapped='/run/desktop/mnt/host/'+$source.Substring(0,1).ToLowerInvariant()+'/'+$source.Substring(3)
    if(@($Db.HostConfig.Mounts).Count -ne 3 -or @($Db.Mounts).Count -ne 3 -or
       (@($Db.Config.Entrypoint) -join ',') -cne 'python3' -or (@($Db.Config.Cmd) -join ',') -cne '/entry.py'){throw 'FINAL_DATABASE_SUBPATH_MOUNTS_REQUIRED'}
    foreach($pair in @(@('/opt/dmdbms/data','db'),@('/opt/dmdbms/log','logs'))){
        $mount=@($Db.HostConfig.Mounts|Where-Object Target -CEQ $pair[0])
        $actual=@($Db.Mounts|Where-Object Destination -CEQ $pair[0])
        if($mount.Count -ne 1 -or $mount[0].Type -cne 'volume' -or $mount[0].Source -cne $Volume -or $mount[0].ReadOnly -or $mount[0].VolumeOptions.Subpath -cne $pair[1] -or
           $actual.Count -ne 1 -or $actual[0].Type -cne 'volume' -or $actual[0].Name -cne $Volume -or $actual[0].RW -ne $true){throw 'FINAL_DATABASE_SUBPATH_MOUNTS_REQUIRED'}
    }
    $entry=@($Db.HostConfig.Mounts|Where-Object Target -CEQ '/entry.py')
    $actualEntry=@($Db.Mounts|Where-Object Destination -CEQ '/entry.py')
    if($entry.Count -ne 1 -or $entry[0].Type -cne 'bind' -or $entry[0].ReadOnly -ne $true -or $entry[0].Source -cne $mapped -or
       $actualEntry.Count -ne 1 -or $actualEntry[0].Type -cne 'bind' -or $actualEntry[0].Source -cne $mapped -or $actualEntry[0].RW -ne $false){throw 'FINAL_DATABASE_OWNED_ENTRY_REQUIRED'}
}
function Assert-FinalOwnedRuntime($Config,$Plan) {
        $network=@(Invoke-FinalDocker @('network','inspect',$Config.network)|ConvertFrom-Json -AsHashtable)[0]
        if($network.Id -cne $Config.networkId -or $network.Name -cne $Config.network -or $network.Labels.task -cne $plan.deployment.database.ownerTask -or -not $network.Internal -or -not $network.Containers.Contains($plan.deployment.database.containerId)){throw 'FINAL_EXACT_INTERNAL_NETWORK_REQUIRED'}
        $db=@(Invoke-FinalDocker @('inspect',$plan.deployment.database.containerId)|ConvertFrom-Json -AsHashtable)[0]
        if($db.Id -cne $plan.deployment.database.containerId -or -not $db.State.Running -or $db.Name -cne '/agent-platform-single-final-primary-20261009' -or $db.Config.Labels.task -cne $plan.deployment.database.ownerTask -or [string]::IsNullOrWhiteSpace($db.Config.User) -or $db.HostConfig.Privileged -or $db.Config.User -match '^(root|0)(:|$)' -or -not $db.HostConfig.ReadonlyRootfs -or $db.HostConfig.CapDrop -cnotcontains 'ALL' -or $db.HostConfig.Memory -ne 4026531840 -or $db.HostConfig.NanoCpus -ne 2000000000){throw 'FINAL_DATABASE_ACTUAL_RESOURCE_REQUIRED'}
        Assert-FinalDatabaseMounts $db $plan.deployment.database.volume
        $init=Read-FinalDeploymentJson $Config.initializationReceipt.path
        foreach($kind in @('database','files')){
            $storage=$init.storage[$kind]
            $volume=@(Invoke-FinalDocker @('volume','inspect',$storage.volume)|ConvertFrom-Json -AsHashtable)[0]
            if($volume.Name -cne $storage.volume -or $volume.Labels.task -cne $plan.deployment.database.ownerTask -or $volume.Driver -cne 'local' -or $volume.Options.type -cne 'ext4' -or $volume.Options.device -cne $storage.device -or ($volume.Options.o.Split(',') -cnotcontains 'nodev') -or ($volume.Options.o.Split(',') -cnotcontains 'nosuid') -or ($volume.Options.o.Split(',') -cnotcontains 'noexec')){throw 'FINAL_OWNED_EXT4_VOLUME_REQUIRED'}
            if($storage.backingKind -ceq 'owned-image-volume'){$p=Read-FinalOwnedImageBinding $storage $plan.deployment.database.containerId;Assert-FinalOwnedImageRuntime $p $db}
        }
}
function Get-FinalApplicationLaunchCommand($App) {
    $a=$App.input
    $checks=@('set -eu',"if ! (test -d '$($a.files.target)' && test -w '$($a.files.target)' && test `"`$(stat -c '%u:%g:%a' '$($a.files.target)')`" = '$($app.uid):$($app.uid):700'); then echo FINAL_RUNTIME_DOMAIN_DIRECTORY_REQUIRED >&2; exit 78; fi")
    foreach($ref in @(@{target='/app/platform-api.jar';sha256=$a.jar.sha256},@{target='/app/application.properties';sha256=$a.properties.sha256})+@($a.mounts)){
        $checks+="if ! (test -f '$($ref.target)' && test ! -L '$($ref.target)' && test `"`$(sha256sum '$($ref.target)' | cut -d ' ' -f1)`" = '$($ref.sha256)'); then echo FINAL_RUNTIME_REFERENCE_BYTES_REQUIRED >&2; exit 78; fi"
    }
    $checks+="exec java -Xmx512m -Dloader.main=$($app.configuration.mainClass) -cp /app/platform-api.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.config.location=file:/app/application.properties"
    return $checks -join '; '
}
function Invoke-FinalApplications($Config,[switch]$ValidateOnly) {
    $plan=Get-FinalDeploymentPlan $Config
    if($ValidateOnly){return @{scope='OFFLINE_CONFIGURATION_ONLY';releaseKind=$plan.releaseKind;manifestSha256=$plan.manifestSha256;applicationsStarted=$false;secretsDecrypted=$false;businessVerified=$false;modelAdmissionGranted=$false}}
    $prepared=@();$created=@()
    try {
        foreach($app in $plan.applications){$secrets=@{};$prepared+=@{app=$app;secrets=$secrets};foreach($key in $app.secretRefs.Keys){$secrets[$key]=Read-FinalManagedSecret $app.secretRefs[$key]}}
        $null=Assert-FinalApproval $Config
        Assert-FinalOwnedRuntime $Config $plan
        $image=@(Invoke-FinalDocker @('image','inspect',$Config.image)|ConvertFrom-Json -AsHashtable)[0]
        if($image.Id -cne $Config.image -or ($image.Config.Volumes -and $image.Config.Volumes.Count)){throw 'FINAL_PINNED_IMAGE_NO_IMPLICIT_VOLUME_REQUIRED'}
        $names=@(Invoke-FinalDocker @('ps','-a','--format','{{.Names}}'))
        if('agent-platform-single-final-restore-20261009' -cin $names){
            $restore=@(Invoke-FinalDocker @('inspect','agent-platform-single-final-restore-20261009')|ConvertFrom-Json -AsHashtable)[0]
            if($restore.Name -cne '/agent-platform-single-final-restore-20261009' -or $restore.Config.Labels.task -cne $plan.deployment.database.ownerTask -or $restore.State.Running){throw 'FINAL_ONE_ONLINE_DM8_REQUIRED'}
        }
        foreach($app in $plan.applications){if($app.input.name -cin $names){throw 'FINAL_PRESERVE_EXISTING_CONTAINER'}}
        foreach($entry in $prepared){
            $app=$entry.app;$a=$app.input;$old=@{};$keys=@();$envValues=@{}+$app.values+$entry.secrets
            try {
                $args=@('create','--name',$a.name,'--user',"$($app.uid):$($app.uid)",'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','768m','--cpus','0.5','--restart','no','--network',$Config.networkId,'--network-alias',"$($a.domain).agent-platform.internal",'--label',"task=$($plan.deployment.database.ownerTask)",'--label',"agent-platform.domain=$($a.domain)",'--label',"agent-platform.manifest-sha256=$($plan.manifestSha256)",'--label',"agent-platform.release-kind=$($plan.releaseKind)",'--tmpfs',"/tmp:rw,nosuid,nodev,noexec,size=64m,uid=$($app.uid),gid=$($app.uid),mode=700",'--publish',"127.0.0.1:$($app.port):8443",'--mount',"type=volume,source=$($a.files.volume),target=$($a.files.target),volume-subpath=$($a.files.subpath),volume-nocopy",'--mount',"type=bind,source=$($a.jar.containerSource),target=/app/platform-api.jar,readonly",'--mount',"type=bind,source=$($a.properties.containerSource),target=/app/application.properties,readonly")
                foreach($mount in $a.mounts){$binding=Get-FinalReferenceBinding $mount $a.domain $Config.secretRoot;$args+=@('--mount',"type=bind,source=$($binding.source),target=$($mount.target),readonly")}
                foreach($key in $envValues.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');$keys+=$key;[Environment]::SetEnvironmentVariable($key,[string]$envValues[$key],'Process');$args+=@('--env',$key)}
                # Verify Docker-host mounted bytes before Java (host-local SHA alone does not prove bind source).
                $app.launchCommand=Get-FinalApplicationLaunchCommand $app
                $args+=@('--entrypoint','sh',$Config.image,'-c',$app.launchCommand)
                Assert-FinalExecutionInputs $Config
                Assert-FinalOwnedRuntime $Config $plan
                $currentNames=@(Invoke-FinalDocker @('ps','-a','--format','{{.Names}}'))
                if($a.name -cin $currentNames){throw 'FINAL_PRESERVE_EXISTING_CONTAINER'}
                $null=Assert-FinalApproval $Config
                $id=[string](Invoke-FinalDocker $args)
                if($id -cnotmatch '^[a-f0-9]{64}$'){throw 'FINAL_CREATED_ID_REQUIRED'};$created+=@{id=$id;app=$app}
            } finally {foreach($key in $keys){if($null -eq $old[$key]){[Environment]::SetEnvironmentVariable($key,[NullString]::Value,'Process')}else{[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}};$envValues.Clear();$entry.secrets.Clear()}
        }
        foreach($candidate in $created){Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan;Assert-FinalCreatedApplication $candidate.id $candidate.app $Config;$null=Assert-FinalApproval $Config;Invoke-FinalDocker @('start',$candidate.id)|Out-Null;Assert-FinalCreatedApplication $candidate.id $candidate.app $Config}
        return @{scope='START_COMMANDS_ONLY_NOT_BUSINESS_ACCEPTANCE';releaseKind=$plan.releaseKind;containerIds=@($created|ForEach-Object id);applicationsStarted=$true;businessVerified=$false;modelAdmissionGranted=$false}
    } catch {throw 'FINAL_DEPLOYMENT_REFUSED_OR_PARTIAL_PRESERVED'} finally {foreach($entry in $prepared){$entry.secrets.Clear()}}
}










function Resume-FinalCreatedApplication($Config,[string]$Id) {
    if($Id -cnotmatch '^[a-f0-9]{64}$'){throw 'FINAL_EXACT_CREATED_CONTAINER_REQUIRED'}
    $plan=Get-FinalDeploymentPlan $Config
    if(@($plan.applications).Count -ne 1){throw 'FINAL_SINGLE_CREATED_RECOVERY_REQUIRED'}
    $app=$plan.applications[0];$app.launchCommand=Get-FinalApplicationLaunchCommand $app
    Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan
    $actual=@(Invoke-FinalDocker @('inspect',$Id)|ConvertFrom-Json -AsHashtable)[0]
    if($actual.State.Status -cne 'created' -or $actual.State.Running -ne $false -or $actual.State.Pid -ne 0){throw 'FINAL_ONLY_UNSTARTED_CREATED_RECOVERY_REQUIRED'}
    Assert-FinalCreatedApplication $Id $app $Config
    $expected=@{}+$app.values
    try {
        foreach($key in $app.secretRefs.Keys){$expected[$key]=Read-FinalManagedSecret $app.secretRefs[$key]}
        $observed=@{};foreach($entry in $actual.Config.Env){$pair=$entry.Split('=',2);if($pair.Count -eq 2){if($observed.Contains($pair[0])){throw 'FINAL_CREATED_ENVIRONMENT_DRIFT'};$observed[$pair[0]]=$pair[1]}}
        foreach($key in $expected.Keys){if(-not $observed.Contains($key) -or $observed[$key] -cne [string]$expected[$key]){throw 'FINAL_CREATED_ENVIRONMENT_DRIFT'}}
        foreach($key in $observed.Keys){if($key -clike 'SINGLE_DB_*' -and -not $expected.Contains($key)){throw 'FINAL_CREATED_ENVIRONMENT_DRIFT'}}
        $null=Assert-FinalApproval $Config;Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan;Assert-FinalCreatedApplication $Id $app $Config
        Invoke-FinalDocker @('start',$Id)|Out-Null
        Assert-FinalCreatedApplication $Id $app $Config
        return @{scope='RESUMED_START_COMMAND_ONLY_NOT_BUSINESS_ACCEPTANCE';releaseKind=$Config.releaseKind;containerIds=@($Id);applicationsStarted=$true;businessVerified=$false;modelAdmissionGranted=$false;newContainersCreated=0}
    } finally {$expected.Clear();if($observed){$observed.Clear()};$actual=$null}
}
