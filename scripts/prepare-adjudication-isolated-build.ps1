param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9-]{1,65}$'){throw 'Invalid isolated build ID'}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$root=Join-Path $env:TEMP "adj-isolated-build-$RunId"
if(Test-Path -LiteralPath $root){throw 'Refusing build snapshot overwrite'}
New-Item -ItemType Directory -Path $root | Out-Null
$files=& rg --files backend --hidden -g '!**/target/**' -g '!**/.git/**'
$manifest=@()
foreach($relative in $files){
 if(-not $relative.Contains('/src/') -and -not $relative.Contains('\src\') -and -not $relative.EndsWith('pom.xml')){continue}
 $source=(Resolve-Path -LiteralPath (Join-Path $workspace $relative)).Path
 $dest=[IO.Path]::GetFullPath((Join-Path $root $relative))
 if(-not $source.StartsWith($workspace+'\backend\') -or -not $dest.StartsWith($root+'\')){throw 'Snapshot path escaped intended source/root'}
 New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
 $before=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
 Copy-Item -LiteralPath $source -Destination $dest
 if($before -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -or $before -ne (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash){throw 'Concurrent source drift during snapshot'}
 $manifest+=@{path=$relative.Replace('\','/');sha256=$before.ToLowerInvariant()}
}
$record=Join-Path $workspace "docs/evidence/adjudication-product-20261003/$RunId-build.json"
@{schema='ISOLATED_SAME_PRODUCT_BUILD/v1';root=$root;capturedAt=[DateTimeOffset]::UtcNow.ToString('o');files=$manifest} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $record -Encoding utf8
Write-Output (Join-Path $root 'backend/pom.xml')
