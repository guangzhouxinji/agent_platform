param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid isolated build ID'}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$isolated=Join-Path $env:TEMP "enf-isolated-build-$RunId"
if(Test-Path -LiteralPath $isolated){throw 'Refusing prior build reuse'}
New-Item -ItemType Directory -Path $isolated | Out-Null
$files=& rg --files backend --hidden -g '!**/target/**' -g '!**/.git/**'
if($LASTEXITCODE -ne 0){throw 'Source enumeration failed'}
$manifest=@()
foreach($relative in $files){
 $normalized=$relative.Replace('\','/')
 if(-not $normalized.Contains('/src/') -and -not $normalized.EndsWith('/pom.xml')){continue}
 $source=(Resolve-Path -LiteralPath (Join-Path $workspace $relative)).Path
 if(-not $source.StartsWith($workspace+'\backend\')){throw 'Source outside backend'}
 $target=[IO.Path]::GetFullPath((Join-Path $isolated $relative))
 if(-not $target.StartsWith($isolated+'\')){throw 'Invalid snapshot target'}
 New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
 $before=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
 Copy-Item -LiteralPath $source -Destination $target
 $after=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
 $copied=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
 if($before -ne $after -or $before -ne $copied){throw "Source changed during capture: $relative"}
 $manifest+=[ordered]@{path=$relative;sha256=$copied}
}
$record=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId-build-inputs.json"
[ordered]@{runId=$RunId;capturedAt=[DateTimeOffset]::UtcNow.ToString('o');buildRoot=$isolated;sourceWorkspace=$workspace;purpose='ISOLATED_BUILD_OF_SAME_FINAL_PRODUCT';files=$manifest}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath $record -Encoding utf8
$env:ENF_MAVEN_POM=Join-Path $isolated 'backend/pom.xml'
$env:ENF_MAVEN_BUILD_ROOT=$isolated
Write-Output "Isolated build ready: $isolated; captured files=$($manifest.Count)"

