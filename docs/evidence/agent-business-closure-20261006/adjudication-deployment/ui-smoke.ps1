$ErrorActionPreference='Stop';$e=(Resolve-Path docs/evidence/agent-business-closure-20261006/adjudication-deployment).Path
. scripts/lib/adjudication-product-http.ps1
try {
 $page=AdjSend $member 'GET' '/app/adjudication' $null $null $null 200
 $assets=[regex]::Matches($page,'(?:src|href)="(/app/assets/[^"\s]+)"')|ForEach-Object {$_.Groups[1].Value}
 if(-not $assets.Count){throw 'Actual page has no current build assets'}
 $result=@();foreach($asset in $assets){$r=$member.GetAsync("$base$asset").GetAwaiter().GetResult();$bytes=$r.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();if([int]$r.StatusCode -ne 200){throw 'Actual resource unavailable'};$result+=[ordered]@{path=$asset;status=[int]$r.StatusCode;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}}
 [ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');actualEntry='https://localhost:5445/app/adjudication';status=200;trustedConfiguredCaAndHostname=$true;assets=$result;scope='NATIVE_TLS_HTML_ASSET_SMOKE_NOT_BROWSER_BUSINESS_E2E'}|ConvertTo-Json -Depth 5|Set-Content "$e/native-ui-smoke.json"
 Write-Output 'NATIVE_TRUSTED_TLS_PAGE_AND_ASSETS_OK_NOT_TASK_COMPLETION'
}finally{$maintenance.Dispose();$member.Dispose()}
