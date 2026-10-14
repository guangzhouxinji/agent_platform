# Run in an administrator PowerShell only. No automatic elevation, CA import, DB or application action.
$ErrorActionPreference='Stop'
$root='D:\daima\xinji\agent_platform\docs\evidence\single-database-installation-20261009\approved-execution'
$hostsFile='C:\Windows\System32\drivers\etc\hosts'
$approval=Join-Path $root 'resource-approval01.json'
if((Get-FileHash -LiteralPath $approval -Algorithm SHA256).Hash.ToLowerInvariant() -ne '649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7'){throw 'ORIGINAL_APPROVAL_CHANGED'}
if([DateTimeOffset]::UtcNow -ge [DateTimeOffset]::Parse('2026-10-10T10:00:00Z')){throw 'ORIGINAL_WINDOW_EXPIRED'}
$backup=Join-Path $root 'formal-dns16-hosts-original.bin'
$sha=(Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
if($sha -ne 'C31E109208C2BE70058AC8A7FFE4B78953B6D0126302307AD7311C6A0A371DD9' -or (Get-FileHash -LiteralPath $hostsFile -Algorithm SHA256).Hash -ne $sha){throw 'HOSTS_CHANGED_RECONCILIATION_REQUIRED'}
$domains=@('consultation.agent-platform.internal','enforcement.agent-platform.internal','adjudication.agent-platform.internal')
$append="`r`n# AgentPlatform approved single-final-20261009 internal HTTPS; loopback only`r`n"+(($domains|ForEach-Object {'127.0.0.1 '+$_}) -join "`r`n")+"`r`n"
[IO.File]::AppendAllText($hostsFile,$append,[Text.UTF8Encoding]::new($false))
$resolved=@($domains|ForEach-Object {@{domain=$_;addresses=@([Net.Dns]::GetHostAddresses($_)|ForEach-Object {$_.IPAddressToString})}})
foreach($r in $resolved){if($r.addresses.Count -ne 1 -or $r.addresses[0] -ne '127.0.0.1'){throw 'HOST_MAPPING_WRITTEN_BUT_RESOLUTION_NOT_PROVEN'}}
$out=Join-Path $root ('formal-dns16-admin-execution-'+[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'.json')
if(Test-Path -LiteralPath $out){throw 'EVIDENCE_ALREADY_EXISTS'}
@{format='SINGLE_DATABASE_APPROVED_ADMIN_DNS_PREPARATION/v1';capturedAt=[DateTimeOffset]::UtcNow.ToString('o');beforeEvidence=$backup;beforeSha256=$sha.ToLowerInvariant();afterSha256=(Get-FileHash -LiteralPath $hostsFile -Algorithm SHA256).Hash.ToLowerInvariant();resolutions=$resolved;databaseRequests=0;applicationsStarted=0;tlsOrBrowserTrustProven=$false}|ConvertTo-Json -Depth 5|Set-Content -Encoding utf8 -LiteralPath $out
