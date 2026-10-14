# Read-only verifier for a pinned, previously evidenced enforcement release. No build, start, or writes.
function Get-EnforcementControlledReadClasspath {
    param([Parameter(Mandatory=$true)][string]$ManifestFile,
          [Parameter(Mandatory=$true)][string]$ManifestSha256,
          [Parameter(Mandatory=$true)][string]$Workspace)
    $ErrorActionPreference='Stop'
    if($ManifestSha256 -notmatch '^[0-9a-f]{64}$' -or -not (Test-Path -LiteralPath $ManifestFile -PathType Leaf)){
        throw 'Pinned release manifest required'
    }
    if((Get-Item -LiteralPath $ManifestFile).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked release manifest refused'}
    if((Get-FileHash -LiteralPath $ManifestFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ManifestSha256){
        throw 'Release manifest SHA mismatch'
    }
    $manifest=Get-Content -LiteralPath $ManifestFile -Raw | ConvertFrom-Json
    if($manifest.schemaVersion -ne 1 -or -not $manifest.releaseId -or -not $manifest.issuanceEvidence){
        throw 'Existing release provenance required'
    }
    $workspaceRoot=(Resolve-Path -LiteralPath $Workspace).Path
    $evidence=[IO.Path]::GetFullPath((Join-Path $workspaceRoot $manifest.issuanceEvidence.path))
    if(-not $evidence.StartsWith($workspaceRoot+'\docs\evidence\',[StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $evidence -PathType Leaf) -or
        (Get-FileHash -LiteralPath $evidence -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.issuanceEvidence.sha256){
        throw 'Release issuance evidence is not pinned'
    }
    $issuance=Get-Content -LiteralPath $evidence -Raw | ConvertFrom-Json
    if($issuance.scope -ne 'ENFORCEMENT_PRODUCT_RELEASE' -or $issuance.releaseId -ne $manifest.releaseId){
        throw 'Source/build logs are not evidence of an issued enforcement release'
    }
    $artifactRoot=(Resolve-Path -LiteralPath $manifest.artifactRoot).Path.TrimEnd('\')
    if($artifactRoot.Length -le 3 -or (Get-Item -LiteralPath $artifactRoot).Attributes -band [IO.FileAttributes]::ReparsePoint){
        throw 'Concrete non-linked release root required'
    }
    $seen=@{};$classpath=@()
    foreach($entry in @($manifest.entries)){
        if(-not $entry.path -or [IO.Path]::IsPathRooted($entry.path) -or $seen.ContainsKey($entry.path)){
            throw 'Exact non-duplicate release entry required'
        }
        $seen[$entry.path]=$true
        $path=[IO.Path]::GetFullPath((Join-Path $artifactRoot $entry.path))
        if(-not $path.StartsWith($artifactRoot+'\',[StringComparison]::OrdinalIgnoreCase)){
            throw 'Release entry escaped artifact root'
        }
        $item=Get-Item -LiteralPath $path
        $ancestor=$item
        while($ancestor.FullName -ne $artifactRoot){
            if($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked release entry refused'}
            $ancestor=Get-Item -LiteralPath (Split-Path -Parent $ancestor.FullName)
        }
        if($entry.type -eq 'file'){
            if($item.PSIsContainer -or $item.Extension -ne '.jar' -or
                (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256){
                throw 'Release JAR is missing or changed'
            }
        }elseif($entry.type -eq 'directory'){
            if(-not $item.PSIsContainer){throw 'Release classes directory missing'}
            $all=@(Get-ChildItem -LiteralPath $path -Recurse -Force)
            if(@($all|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count -gt 0){
                throw 'Linked release content refused'
            }
            $actual=@($all|Where-Object {-not $_.PSIsContainer})
            $expected=@($entry.files);$expectedPaths=@{};$expectedTargets=@{}
            if($actual.Count -eq 0 -or $actual.Count -ne $expected.Count){throw 'Complete release inventory mismatch'}
            foreach($file in $expected){
                if(-not $file.path -or $expectedPaths.ContainsKey($file.path) -or [IO.Path]::IsPathRooted($file.path)){
                    throw 'Invalid release file inventory'
                }
                $expectedPaths[$file.path]=$true
                $target=[IO.Path]::GetFullPath((Join-Path $path $file.path))
                if($expectedTargets.ContainsKey($target)){throw 'Aliased release file refused'}
                $expectedTargets[$target]=$true
                if(-not $target.StartsWith($path+'\',[StringComparison]::OrdinalIgnoreCase) -or
                    -not (Test-Path -LiteralPath $target -PathType Leaf) -or
                    $target -match '\.(p12|key)$|credential\.xml$' -or
                    (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256){
                    throw 'Release file is missing or changed'
                }
            }
            foreach($file in $actual){if(-not $expectedTargets.ContainsKey($file.FullName)){throw 'Unrecorded release file refused'}}
        }else{throw 'Unknown release entry type'}
        $classpath+=$path
    }
    if($classpath.Count -eq 0){throw 'Empty release classpath refused'}
    return ($classpath -join ';')
}
