param([Parameter(Mandatory)][string]$Configuration,
    [string]$ReplaceContainerId='', [switch]$ValidateOnly, [switch]$OfflineOnly)
$ErrorActionPreference='Stop'
if($ValidateOnly -and $OfflineOnly){throw 'ValidateOnly and OfflineOnly are mutually exclusive'}
. (Join-Path $PSScriptRoot 'lib/consultation-comparison-deployment.ps1')
try {
    $config=Get-Content -LiteralPath $Configuration -Raw|ConvertFrom-Json -AsHashtable
    if($config -isnot [System.Collections.IDictionary]){throw 'Deployment dictionary required'}
} catch {throw 'Deployment configuration unavailable or invalid'}
$settings=Get-ConsultationDeploymentSettings -Config $config
try {
if($OfflineOnly){
    Write-Output 'offline_deployment_configuration=verified; runtime approvals and Docker checks not performed'
    return
}
function Invoke-DockerChecked([string[]]$Arguments) {
    $result=& docker @Arguments
    if($LASTEXITCODE -ne 0){throw 'Docker deployment operation failed'}
    return $result
}
if($config.schema -ne 'CONSULTATION_CONTAINER/v1' -or
    $config.image -notmatch '^eclipse-temurin@sha256:[a-f0-9]{64}$' -or
    $config.jarSha -notmatch '^[a-f0-9]{64}$' -or
    $config.appName -notmatch '^[a-z][a-z0-9-]+$') {throw 'Invalid final deployment configuration'}
if((Get-FileHash -LiteralPath $config.jar).Hash.ToLowerInvariant() -ne $config.jarSha){throw 'Release SHA mismatch'}
$containerJar = if($config.containerJar){[string]$config.containerJar}else{[string]$config.jar}
if($config.runtimeRoot){
    if($config.runtimeRoot -notmatch '^/mnt/docker-desktop-disk/agent-platform-product-files/runtime-consultation-[a-z0-9-]+$' -or
       $containerJar -ne $config.runtimeRoot+'/platform-api.jar' -or $config.runtimeManifestSha -notmatch '^[a-f0-9]{64}$'){throw 'Exact private runtime root/manifest required'}
    foreach($mount in $config.mounts){
        if(-not $mount.containerSource -or -not $mount.containerSource.StartsWith($config.runtimeRoot+'/') -or
           @($mount.containerSource.Split('/')|Where-Object {$_ -in @('.','..')}).Count -gt 0){throw 'Runtime mount outside exact private root'}
    }
}
if($config.evidenceRoot -notmatch '^/mnt/docker-desktop-disk/agent-platform-product-files/[a-z0-9-]+$' -or
    $config.evidenceManifest -notmatch '^/mnt/docker-desktop-disk/agent-platform-product-files/[a-z0-9.-]+$' -or
    $config.evidenceManifestSha -notmatch '^[a-f0-9]{64}$'){throw 'Exact managed evidence Root/manifest required'}
if($config.network -and $config.network -notmatch '^agent-platform-consultation-product-[a-z0-9-]+$'){throw 'Exact managed product network required'}

foreach($database in $config.databases){
    $actual=Invoke-DockerChecked @('inspect','--format','{{.Id}}', $database.name)
    if($actual -ne $database.id){throw 'Existing database identity mismatch'}
}
if($config.network){
    if($config.network -notmatch '^agent-platform-consultation-product-[a-z0-9-]+$'){throw 'Exact managed product network required'}
    $network=(Invoke-DockerChecked @('network','inspect',$config.network)|ConvertFrom-Json)[0]
    if($network.Labels.'agent-platform.role' -ne 'consultation-product'){throw 'Network ownership mismatch'}
    foreach($database in $config.databases){if(-not $network.Containers.PSObject.Properties[$database.id]){throw 'Original database absent from managed network'}}
}
$volumes=Invoke-DockerChecked @('image','inspect','--format','{{json .Config.Volumes}}',$config.image)
if($volumes -ne 'null'){throw 'Image must not create data volumes'}
$probeArgs=@('run','--rm','--read-only','--user','10001:10001','--network','none','--cap-drop','ALL',
    '--mount',"type=bind,source=$($config.evidenceRoot),target=/evidence,readonly",
    '--mount',"type=bind,source=$($config.evidenceManifest),target=/manifest,readonly",$config.image,
    'sh','-c','set -eu; cd /evidence; test -r retention-v1.json; test ! -w retention-v1.json; test "$(find . -type l | wc -l)" -eq 0; test "$(find . -type f | wc -l)" -eq "$(wc -l </manifest)"; sha256sum /manifest; sha256sum -c /manifest >/dev/null; echo complete_evidence_readonly=verified')
$probe=Invoke-DockerChecked $probeArgs
if($probe[0] -notlike "$($config.evidenceManifestSha) *"){throw 'Evidence manifest SHA mismatch'}
$jarProbe=Invoke-DockerChecked @('run','--rm','--read-only','--user','10001:10001','--network','none','--cap-drop','ALL',
    '--mount',"type=bind,source=$containerJar,target=/release.jar,readonly",$config.image,
    'sh','-c','set -eu; test -f /release.jar; sha256sum /release.jar')
if($jarProbe -notlike "$($config.jarSha) *"){throw 'Actual mounted release SHA mismatch'}
if($config.runtimeRoot){
    $runtimeProbe=Invoke-DockerChecked @('run','--rm','--read-only','--user','10001:10001','--network','none','--cap-drop','ALL',
        '--mount',"type=bind,source=$($config.runtimeRoot),target=/runtime,readonly",$config.image,
        'sh','-c','set -eu; cd /runtime; test ! -w .; test "$(find . -type l | wc -l)" -eq 0; test "$(find . -type f | wc -l)" -eq "$(( $(wc -l < runtime-manifest.sha256) + 1 ))"; sha256sum runtime-manifest.sha256; sha256sum -c runtime-manifest.sha256 >/dev/null')
    if(@($runtimeProbe)[0] -notlike "$($config.runtimeManifestSha) *"){throw 'Private runtime manifest SHA mismatch'}
}
Write-Output 'release/database/complete_evidence/actual_mounted_release/readonly=verified'
if($ValidateOnly){return}

$names=@(Invoke-DockerChecked @('ps','-a','--format','{{.Names}}'))
$staging="$($config.appName)-next"
$previous="$($config.appName)-previous-$(Get-Date -Format yyyyMMddHHmmss)"
if($names -contains $staging -or $names -contains $previous){throw 'Preserve existing deployment object'}
if($ReplaceContainerId){
    $actual=Invoke-DockerChecked @('inspect','--format','{{.Id}}',$config.appName)
    if($actual -ne $ReplaceContainerId){throw 'Preserve unknown application'}
} elseif($names -contains $config.appName){throw 'Exact replacement identity required'}
$setKeys=[Collections.Generic.List[string]]::new()
$candidate=$null;$oldStopped=$false;$oldRenamed=$false;$candidateRenamed=$false
try {
    $arguments=@('create','--name',$staging,'--read-only','--user','10001:10001','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--memory','4g','--cpus','8','--restart','no',
        '--tmpfs','/tmp:rw,exec,nosuid,size=256m,uid=10001,gid=10001,mode=1777',
        '--publish','127.0.0.1:5443:5443','--publish','[::1]:5443:5443',
        '--mount',"type=bind,source=$containerJar,target=/app/platform-api.jar,readonly",
        '--mount',"type=bind,source=$($config.evidenceRoot),target=/product/evidence,readonly")
    if($config.network){$arguments+=@('--network',$config.network)}
    foreach($mount in $config.mounts){
        if(-not(Test-Path -LiteralPath $mount.source)){throw 'Required deployment file missing'}
        $containerSource=if($mount.containerSource){$mount.containerSource}else{$mount.source}
        $arguments+=@('--mount',"type=bind,source=$containerSource,target=$($mount.target),readonly")
    }
    foreach($key in $settings.Keys){
        if($null -ne [Environment]::GetEnvironmentVariable($key,'Process')){throw "Preserve existing variable $key"}
        [Environment]::SetEnvironmentVariable($key,$settings[$key],'Process');$setKeys.Add($key)
        $arguments+=@('--env',$key)
    }
    $arguments+=@($config.image,'sh','-c','java -version && mkdir -p /tmp/product-home /tmp/product-model-cache && exec java -jar /app/platform-api.jar --product-consultation --spring.datasource.hikari.data-source-properties.pstmtPoolSize=0 --spring.datasource.hikari.data-source-properties.enRsCache=false --spring.datasource.hikari.data-source-properties.compress=0 --spring.datasource.hikari.data-source-properties.compressID=0 --server.http2.enabled=false')
    $candidate=Invoke-DockerChecked $arguments
    $published=Invoke-DockerChecked @('inspect','--format','{{json .HostConfig.PortBindings}}',$candidate) | ConvertFrom-Json
    $loopbacks=@($published.'5443/tcp')
    if(@($published.PSObject.Properties).Count -ne 1 -or $loopbacks.Count -ne 2 -or
            $loopbacks.HostIp -notcontains '127.0.0.1' -or $loopbacks.HostIp -notcontains '::1' -or
            @($loopbacks | Where-Object HostPort -ne '5443').Count -ne 0){throw 'Exact dual-loopback HTTPS binding required'}
    if($ReplaceContainerId){
        $oldWasRunning=Invoke-DockerChecked @('inspect','--format','{{.State.Running}}',$ReplaceContainerId)
        if($oldWasRunning -eq 'true'){Invoke-DockerChecked @('stop',$config.appName)|Out-Null;$oldStopped=$true}
        Invoke-DockerChecked @('rename',$config.appName,$previous)|Out-Null;$oldRenamed=$true
    }
    Invoke-DockerChecked @('rename',$staging,$config.appName)|Out-Null;$candidateRenamed=$true
    Invoke-DockerChecked @('start',$config.appName)|Out-Null
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(55)
    do {
        $running=Invoke-DockerChecked @('inspect','--format','{{.State.Running}}',$candidate)
        if($running -ne 'true'){throw 'Candidate exited during startup'}
        $ready=Invoke-DockerChecked @('logs','--tail','60',$candidate)
        if($ready -match 'Started AgentPlatformApplication'){break}
        Start-Sleep -Milliseconds 500
    } while([DateTimeOffset]::UtcNow -lt $deadline)
    if(-not($ready -match 'Started AgentPlatformApplication')){throw 'Candidate startup deadline exceeded'}
    Write-Output "application=$($config.appName) id=$candidate previous=$previous evidenceRoot=$($config.evidenceRoot)"
} catch {
    if($candidate){Invoke-DockerChecked @('stop',$candidate)|Out-Null}
    if($candidateRenamed){Invoke-DockerChecked @('rename',$config.appName,"$staging-failed-$(Get-Date -Format yyyyMMddHHmmss)")|Out-Null}
    if($oldRenamed){Invoke-DockerChecked @('rename',$previous,$config.appName)|Out-Null}
    if($oldStopped){Write-Warning 'Previous application remains stopped: old-JAR write compatibility requires separately verified approval.'}
    throw
} finally {
    foreach($key in $setKeys){Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue}
    $settings.Clear();$credential=$null
}

} finally {
    $settings.Clear()
}
