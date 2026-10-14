$ErrorActionPreference='Stop'
$root=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1/product-runtime'
$evidence=Join-Path (Get-Location) 'docs/evidence/agent-business-closure-20261006/adjudication-deployment'
if(Get-NetTCPConnection -LocalPort 5445 -State Listen -ErrorAction SilentlyContinue){throw 'Preserve existing service'}
$old=Get-Content "$root/current-release.json" -Raw|ConvertFrom-Json
$previous=(Get-FileHash -LiteralPath $old.jar).Hash.ToLowerInvariant();if($previous -ne $old.sha256){throw 'Old release pointer invalid'}
$keyFile=Join-Path $root 'runtime-checkpoint-key-20261006.credential.xml'
if(Test-Path -LiteralPath $keyFile){throw 'Never replace current runtime key'}
$bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$key=[Convert]::ToBase64String($bytes);ConvertTo-SecureString $key -AsPlainText -Force|Export-Clixml -LiteralPath $keyFile
$fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();[Array]::Clear($bytes,0,$bytes.Length);$key=$null
[ordered]@{createdAt=[DateTimeOffset]::Now.ToString('o');bytes=32;generation='SYSTEM_CRYPTO_RANDOM_NOT_TEST_DEFAULT';protectedStorage='WINDOWS_CURRENT_USER_DPAPI';keyFingerprint=$fingerprint;credentialPath=$keyFile;existingKeyReplaced=$false}|ConvertTo-Json|Set-Content "$root/runtime-checkpoint-key-20261006-metadata.json"
$jar=(Resolve-Path backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar).Path
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try {
 $required=@('cn/xinji/agentplatform/adjudication/casework/ProductAdjudicationApplication.class','cn/xinji/agentplatform/adjudication/casework/ProductAdjudicationAgentController.class','cn/xinji/agentplatform/runtime/AgentRuntimeConfiguration.class','cn/xinji/agentplatform/runtime/AuthorizedAgentExecution.class','static/app/index.html')
 foreach($entry in $required){if(-not $zip.GetEntry('BOOT-INF/classes/'+$entry)){throw "Current release missing $entry"}}
}finally{$zip.Dispose()}
$new=Join-Path $root 'application-api-agent-20261006-01.jar';if(Test-Path -LiteralPath $new){throw 'Never overwrite existing release'};Copy-Item -LiteralPath $jar -Destination $new
$sha=(Get-FileHash -LiteralPath $new).Hash.ToLowerInvariant();if($sha -ne (Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant()){throw 'Copied release differs'}
Copy-Item -LiteralPath "$root/current-release.json" -Destination "$root/current-release-pre-agent-20261006.json"
[ordered]@{jar=$new;sha256=$sha}|ConvertTo-Json|Set-Content "$root/current-release.json"
[ordered]@{issuedAt=[DateTimeOffset]::Now.ToString('o');previousJar=$old.jar;previousSha=$old.sha256;newJar=$new;newSha=$sha;formalRuntimeKeyBytes=32;keyFingerprint=$fingerprint;requiredComponents=$required;runtimeEnabled=$true;materialModelsEnabled=$false;providerCalls=0;originalIdentityOrModelKeyReplaced=$false}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $evidence 'release-key-configuration.json')
