# PC-COMPARISON-RELEASE-PREFLIGHT-01: offline configuration only, not approval or Worker startup.
# Reads managed credentials locally; never writes environment, calls Docker, or emits their values.
function Get-ConsultationDeploymentSettings {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Config)
    $settings=[ordered]@{}
    try {
        if($Config.schema -cne 'CONSULTATION_CONTAINER/v1' -or
            $Config.settings -isnot [System.Collections.IDictionary] -or
            ($null -ne $Config.secretFiles -and $Config.secretFiles -isnot [System.Collections.IDictionary])){
            throw 'Invalid deployment settings dictionary'
        }
        foreach($entry in $Config.settings.GetEnumerator()){
            if([string]$entry.Key -cnotmatch '^[A-Z][A-Z0-9_]*$' -or
                ($null -ne $entry.Value -and $entry.Value -isnot [string] -and $entry.Value -isnot [ValueType])){
                throw 'Invalid deployment setting'
            }
            $settings[$entry.Key]=[string]$entry.Value
        }
        if($settings.PRODUCT_SOURCE_EVIDENCE_ROOT -cne '/product/evidence'){throw 'Single product evidence Root required'}
        if($null -ne $Config.secretFiles){
            foreach($entry in $Config.secretFiles.GetEnumerator()){
                if([string]$entry.Key -cnotmatch '^[A-Z][A-Z0-9_]*$' -or $settings.Contains($entry.Key)){
                    throw 'Ambiguous or invalid secret setting'
                }
                $credential=$null
                try {
                    if($entry.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($entry.Value)){
                        throw 'Managed credential reference required'
                    }
                    $credential=Import-Clixml -LiteralPath $entry.Value -ErrorAction Stop
                    if($credential -is [Security.SecureString]){
                        $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($credential)
                        try {$settings[$entry.Key]=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
                        finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
                    } elseif($credential -is [PSCredential]){
                        $settings[$entry.Key]=$credential.GetNetworkCredential().Password
                    } else {throw 'Managed credential required'}
                } catch {throw 'Managed deployment credential unavailable or invalid'}
                finally {$credential=$null}
            }
        }
        $enabled=$settings.PRODUCT_CONSULTATION_COMPARISON_ENABLED
        if($null -ne $enabled -and $enabled -notmatch '\A(?i:true|false)\z'){throw 'Invalid comparison enabled flag'}
        $calculation=$settings.PRODUCT_CONSULTATION_COMPARISON_CALCULATION_ENABLED
        if($null -ne $calculation -and $calculation -notmatch '\A(?i:true|false)\z'){throw 'Invalid calculation enabled flag'}
        if($calculation -ieq 'true' -and $enabled -ine 'true'){throw 'Calculation requires comparison feature'}
        # Absent new flag preserves validation of the pinned legacy packages; new layering is explicit.
        $requiresModel=($null -eq $calculation -or $calculation -ieq 'true')
        if($enabled -ieq 'true'){
            foreach($key in @('PRODUCT_IDENTITY_ENABLED','PRODUCT_SOURCE_ENGINE_ENABLED','SERVER_SSL_ENABLED')){
                if($settings[$key] -ine 'true'){throw 'Comparison requires identity, source engine and TLS enabled'}
            }
            if($requiresModel){
            $reference=$settings.PRODUCT_CONSULTATION_COMPARISON_APPROVAL_REF
            # Same lexical contract as ProductSourceEvidenceFiles; authority stays with runtime admission.
            if($reference -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._/-]{0,499}\z' -or $reference.Contains('..')){
                throw 'Comparison approval evidence relative reference required'
            }
            if($settings.PRODUCT_CONSULTATION_COMPARISON_APPROVAL_SHA256 -cnotmatch '\A[0-9a-fA-F]{64}\z'){
                throw 'Comparison approval SHA256 required'
            }
            if([string]::IsNullOrWhiteSpace($settings.PRODUCT_CONSULTATION_MODEL_API_KEY)){throw 'Comparison model credential required'}
            }
            foreach($key in @('SERVER_SSL_KEY_STORE_PASSWORD','SERVER_SSL_TRUST_STORE_PASSWORD')){
                if([string]::IsNullOrWhiteSpace($settings[$key])){throw 'Comparison model and TLS credentials required'}
            }
            if($settings.SERVER_SSL_CLIENT_AUTH -inotmatch '\A(want|need)\z'){throw 'Worker TLS client authentication required'}
            # Existing Spring server SSL stores must resolve to the existing read-only deployment mounts.
            foreach($key in @('SERVER_SSL_KEY_STORE','SERVER_SSL_TRUST_STORE')){
                $target=[string]$settings[$key]
                if($target.StartsWith('file:///')){$target=$target.Substring(7)}
                elseif($target.StartsWith('file:/') -and -not $target.StartsWith('file://')){$target=$target.Substring(5)}
                if($target -cnotmatch '\A/[A-Za-z0-9._/-]+\z' -or
                    @($target.Split('/')|Where-Object {$_ -in @('.','..','')}).Count -ne 1){
                    throw 'Managed Worker TLS store mount required'
                }
                $mounts=@($Config.mounts|Where-Object {$_.target -ceq $target})
                if($mounts.Count -ne 1 -or $mounts[0].source -isnot [string] -or
                    -not(Test-Path -LiteralPath $mounts[0].source -PathType Leaf -ErrorAction Stop)){
                    throw 'Managed Worker TLS store mount required'
                }
            }
        }
        $start=$settings.PRODUCT_CONSULTATION_COMPARISON_START_ENABLED
        if($null -ne $start -and $start -notmatch '\A(?i:true|false)\z'){throw 'Invalid explicit comparison start flag'}
        if($start -ieq 'true'){
            if($enabled -ine 'true'){throw 'Comparison start requires comparison feature enabled'}
            foreach($key in @('PRODUCT_CONSULTATION_COMPARISON_START_WORKER_SUBJECT','PRODUCT_CONSULTATION_COMPARISON_START_WORKER_PASSWORD','PRODUCT_CONSULTATION_COMPARISON_START_TRUSTSTORE_PASSWORD')){
                if([string]::IsNullOrWhiteSpace($settings[$key])){throw 'Managed comparison start binding and TLS secrets required'}
            }
            if($null -ne $calculation){
                $version=$settings.PRODUCT_CONSULTATION_COMPARISON_START_CONFIGURATION_VERSION
                if([string]::IsNullOrWhiteSpace($version) -or $version.Length -gt 180 -or $version -match '[\x00-\x1f]'){throw 'Managed handoff configuration version required'}
            }
            [long]$lease=0
            if(-not [long]::TryParse($settings.PRODUCT_CONSULTATION_COMPARISON_START_LEASE_MILLIS,[ref]$lease) -or $lease -lt 1 -or $lease -gt 90000){throw 'Finite managed comparison start lease required'}
            [Uri]$origin=$null
            if(-not [Uri]::TryCreate($settings.PRODUCT_CONSULTATION_COMPARISON_START_PRODUCT_ORIGIN,[UriKind]::Absolute,[ref]$origin) -or
                $origin.Scheme -cne 'https' -or [string]::IsNullOrEmpty($origin.Host) -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/'){
                throw 'Exact HTTPS product origin required for comparison start'
            }
            foreach($key in @('PRODUCT_CONSULTATION_COMPARISON_START_WORKER_KEYSTORE','PRODUCT_CONSULTATION_COMPARISON_START_TRUSTSTORE')){
                $target=[string]$settings[$key]
                if($target -cnotmatch '\A/[A-Za-z0-9._/-]+\z' -or @($target.Split('/')|Where-Object {$_ -in @('.','..','')}).Count -ne 1){throw 'Managed comparison start TLS path required'}
                $mounts=@($Config.mounts|Where-Object {$_.target -ceq $target})
                if($mounts.Count -ne 1 -or $mounts[0].source -isnot [string] -or -not(Test-Path -LiteralPath $mounts[0].source -PathType Leaf -ErrorAction Stop)){throw 'Managed comparison start TLS read-only mount required'}
            }
            # Runtime separately checks approved model timeout, certificate identity, exact source and budget.
        }
        return ,$settings
    } catch {
        $settings.Clear()
        throw 'Offline deployment configuration invalid; check settings, managed credentials and TLS mounts'
    }
}


