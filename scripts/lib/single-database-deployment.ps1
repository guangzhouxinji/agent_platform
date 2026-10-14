# Only configuration and read-only Docker inspection. No credentials, DDL or writer lifecycle actions.
function Get-SingleDatabaseDeploymentPlan {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Config)
    if($Config.schema -cnotin @('SINGLE_DM8_DEPLOYMENT/v1','SINGLE_DM8_DEPLOYMENT/v2')){throw 'Single database configuration version required'}
    if(@($Config.Keys|Where-Object {$_ -cnotin @('schema','database','domains')}).Count){throw 'Unsupported deployment configuration field'}
    $full=$Config.schema -ceq 'SINGLE_DM8_DEPLOYMENT/v2'
    $db=$Config.database
    if($db -isnot [System.Collections.IDictionary] -or $db.containerId -cnotmatch '^[a-f0-9]{64}$' -or
        $db.volume -cnotmatch '^agent-platform-[a-z0-9-]+$' -or
        $db.ownerTask -cnotmatch '^[A-Z][A-Z0-9-]+$' -or
        $db.jdbcUrl -cnotmatch '^jdbc:dm://[A-Za-z0-9.-]+:[0-9]{1,5}$') {throw 'Exact managed single DM8 resource required'}
    if(@($db.Keys|Where-Object {$_ -cnotin @('containerId','volume','ownerTask','jdbcUrl','memoryLimitBytes','nanoCpuLimit')}).Count){throw 'Unsupported database field'}
    $port=[int]($db.jdbcUrl -split ':')[-1]
    if($port -lt 1 -or $port -gt 65535){throw 'Valid DM8 port required'}
    [long]$memory=0; [long]$cpu=0
    if(-not [long]::TryParse([string]$db.memoryLimitBytes,[ref]$memory) -or $memory -lt 1 -or $memory -gt 4294967296 -or
       -not [long]::TryParse([string]$db.nanoCpuLimit,[ref]$cpu) -or $cpu -lt 1 -or $cpu -gt 2000000000){throw 'Finite DM8 engineering CPU and memory limits required'}
    $domains=@($Config.domains)
    if($domains.Count -ne 3){throw 'Exactly three domain bindings required'}
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $issuers=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $schemas=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $users=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $ids=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $bindings=@()
    foreach($domain in $domains){
        if($domain -isnot [System.Collections.IDictionary] -or $domain.domain -cnotin @('consultation','enforcement','adjudication') -or
           -not $names.Add($domain.domain)){throw 'Three independent domain bindings required'}
        if(@($domain.Keys|Where-Object {$_ -cnotin @('domain','issuer','business','authority','writerContainerIds')}).Count){throw 'Unsupported domain field'}
        [Uri]$issuer=$null
        if(-not [Uri]::TryCreate([string]$domain.issuer,[UriKind]::Absolute,[ref]$issuer) -or
           $issuer.Scheme -cne 'https' -or $issuer.UserInfo -or $issuer.Query -or $issuer.Fragment -or
           -not $issuers.Add($issuer.AbsoluteUri)){throw 'Distinct exact HTTPS Issuers required'}
        if($full -and ($domain.issuer.EndsWith('/') -or $issuer.Host -cin @('localhost','127.0.0.1','[::1]'))){throw 'Fixed non-local Issuer required'}
        foreach($kind in @('business','authority')){
            $scope=$domain[$kind]
            if($scope -isnot [System.Collections.IDictionary] -or $scope.jdbcUrl -cne $db.jdbcUrl -or
               $scope.schema -cnotmatch '^[A-Z][A-Z0-9_]{2,63}$' -or -not $schemas.Add($scope.schema)){
                throw 'Six distinct schemas at one exact DM8 URL required'
            }
            $accountKeys=@('ownerUser','runtimeUser'); if($full -and $kind -ceq 'authority'){$accountKeys+=@('backupUser')}
            if(@($scope.Keys|Where-Object {$_ -cnotin (@('schema','jdbcUrl')+$accountKeys)}).Count){throw 'Unsupported account binding field'}
            foreach($key in $accountKeys){
                if($scope[$key] -cnotmatch '^[A-Z][A-Z0-9_]{0,63}$' -or
                   $scope[$key] -cin @('SYSDBA','SYSAUDITOR','SYSSSO') -or -not $users.Add($scope[$key])){
                    throw 'Twelve distinct non-system schema/runtime accounts required'
                }
            }
        }
        $writers=@($domain.writerContainerIds)
        if(-not $domain.Contains('writerContainerIds') -or $domain.writerContainerIds -isnot [Array]){throw 'Explicit writer inventory array required'}
        foreach($id in $writers){if($id -cnotmatch '^[a-f0-9]{64}$' -or -not $ids.Add($id)){throw 'Exact unique writer container IDs required'}}
        $bindings+=@{domain=$domain.domain;issuer=$domain.issuer;writerContainerIds=$writers;settings=[ordered]@{
            SPRING_DATASOURCE_URL=$db.jdbcUrl;SPRING_DATASOURCE_USERNAME=$domain.business.runtimeUser
            SPRING_DATASOURCE_HIKARI_SCHEMA=$domain.business.schema;PRODUCT_IDENTITY_ISSUER=$domain.issuer
            PRODUCT_IDENTITY_AUTHORITY_TOPOLOGY='shared-instance';PRODUCT_IDENTITY_AUTHORITY_JDBC_URL=$db.jdbcUrl
            PRODUCT_IDENTITY_AUTHORITY_SCHEMA=$domain.authority.schema;PRODUCT_IDENTITY_AUTHORITY_USERNAME=$domain.authority.runtimeUser
        }}
    }
    return @{database=$db;domains=$bindings;accountCount=$users.Count;credentialsIncluded=$false;businessSourceApprovalVerified=$false}
}
function Invoke-SingleDatabaseInspection {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Config,[switch]$RequireStoppedWriters)
    $Plan=Get-SingleDatabaseDeploymentPlan -Config $Config
    $raw=& docker inspect $Plan.database.containerId
    if($LASTEXITCODE -ne 0){throw 'Single database inspection failed'}
    $actual=@($raw|ConvertFrom-Json)
    if($actual.Count -ne 1 -or $actual[0].Id -cne $Plan.database.containerId -or
       $actual[0].Config.Labels.task -cne $Plan.database.ownerTask){throw 'Database exact identity or ownership mismatch'}
    $resource=$actual[0]
    if($resource.HostConfig.Privileged -or [string]::IsNullOrWhiteSpace($resource.Config.User) -or $resource.Config.User -match '^(?:root|0)(?::|$)'){throw 'Owned non-root unprivileged DM8 process required'}
    $endpoint=[Uri]($Plan.database.jdbcUrl.Replace('jdbc:dm://','http://'))
    if($endpoint.Host -cin @('127.0.0.1','localhost','host.docker.internal')){
        $bindings=if($resource.State.Running){$resource.NetworkSettings.Ports.'5236/tcp'}else{$resource.HostConfig.PortBindings.'5236/tcp'}
        $ports=@($bindings|Where-Object {$_.HostIp -ceq '127.0.0.1' -and $_.HostPort -ceq [string]$endpoint.Port})
        if($ports.Count -ne 1){throw 'JDBC endpoint does not bind the exact inspected loopback DM8 container'}
    } else {
        $aliases=@($resource.NetworkSettings.Networks.PSObject.Properties|ForEach-Object {$_.Value.Aliases})
        if($endpoint.Port -ne 5236 -or ($resource.Name.TrimStart('/') -cne $endpoint.Host -and $endpoint.Host -cnotin $aliases)){
            throw 'JDBC endpoint does not bind the exact inspected managed DM8 alias'
        }
    }
    if($resource.HostConfig.Memory -lt 1 -or $resource.HostConfig.Memory -gt $Plan.database.memoryLimitBytes -or
       $resource.HostConfig.NanoCpus -lt 1 -or $resource.HostConfig.NanoCpus -gt $Plan.database.nanoCpuLimit){throw 'Database resource bounds mismatch'}
    foreach($target in @('/opt/dmdbms/data','/opt/dmdbms/log')){
        $mount=@($resource.Mounts|Where-Object {$_.Destination -ceq $target})
        if($mount.Count -ne 1 -or $mount[0].Type -cne 'volume' -or $mount[0].Name -cne $Plan.database.volume -or -not $mount[0].RW){throw 'Exact persistent DM8 data/log volume required'}
    }
    $raw=& docker volume inspect $Plan.database.volume
    if($LASTEXITCODE -ne 0){throw 'Persistent volume unavailable'}
    $volumes=@($raw|ConvertFrom-Json)
    if($volumes.Count -ne 1 -or $volumes[0].Name -cne $Plan.database.volume -or $volumes[0].Labels.task -cne $Plan.database.ownerTask){throw 'Persistent volume ownership mismatch'}
    if($RequireStoppedWriters){
        foreach($domain in $Plan.domains){
            if(@($domain.writerContainerIds).Count -lt 1){throw 'Stopped-writer admission requires nonempty inventory for every domain'}
            foreach($id in $domain.writerContainerIds){
            $raw=& docker inspect $id
            if($LASTEXITCODE -ne 0){throw 'Writer inspection failed'}
            $writers=@($raw|ConvertFrom-Json)
            if($writers.Count -ne 1 -or $writers[0].Id -cne $id -or
               $writers[0].Config.Labels.'agent-platform.domain' -cne $domain.domain -or $writers[0].State.Running){throw 'All enumerated domain writers must match and remain stopped'}
        }}
    }
    return @{scope='READ_ONLY_RESOURCE_PREFLIGHT_NOT_RECOVERY_OR_RELEASE';resourceVerified=$true
        enumeratedWritersStopped=[bool]$RequireStoppedWriters;unlistedWritersProvenAbsent=$false;diskCapacityVerified=$false
        databaseRunning=[bool]$resource.State.Running;databaseConnectivityVerified=$false}
}
