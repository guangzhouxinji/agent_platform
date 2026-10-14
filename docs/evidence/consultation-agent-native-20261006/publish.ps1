$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path;$evidence=Join-Path $workspace 'docs/evidence/consultation-agent-native-20261006';$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/consultation-product'
$report=Get-Content -LiteralPath (Join-Path $evidence 'migrate-report.json') -Raw|ConvertFrom-Json
if(-not $report.originalPrimaryUnchanged -or @($report.newTables).Count -ne 3){throw 'Migration evidence incomplete'}
$source=Join-Path $workspace 'backend/apps/platform-api/target/agent-platform-consultation-product-0.0.1-SNAPSHOT.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($source)
try{
 foreach($name in @('BOOT-INF/classes/cn/xinji/agentplatform/security/product/ProductConsultationAgentController.class','BOOT-INF/classes/cn/xinji/agentplatform/runtime/AuthorizedAgentExecution.class','BOOT-INF/classes/cn/xinji/agentplatform/runtime/AgentRuntimeConfiguration.class','BOOT-INF/classes/static/app/consultation.html')){if(-not $zip.GetEntry($name)){throw 'Required final product entry missing'}}
 $assetEntries=@($zip.Entries|Where-Object {$_.FullName -like 'BOOT-INF/classes/static/app/assets/*' -and -not $_.FullName.EndsWith('/')});$assets=@();$client=$false
 foreach($entry in $assetEntries){$relative=$entry.FullName.Substring('BOOT-INF/classes/static/app/'.Length);$actual=Join-Path $workspace ('frontend/dist-consultation/'+$relative);if(-not(Test-Path -LiteralPath $actual)){throw 'Stale JAR asset'};$stream=$entry.Open();try{$memory=[IO.MemoryStream]::new();$stream.CopyTo($memory);$bytes=$memory.ToArray()}finally{$stream.Dispose()};$digest=[Security.Cryptography.SHA256]::Create();try{$sha=([BitConverter]::ToString($digest.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()}finally{$digest.Dispose()};if($sha -ne (Get-FileHash -LiteralPath $actual).Hash.ToLowerInvariant()){throw 'Packaged asset differs from current build'};$assets+=@{path=$relative;sha256=$sha};if($relative.EndsWith('.js') -and [Text.Encoding]::UTF8.GetString($bytes).Contains('consultation-workspace-product')){$client=$true}}
 if(-not $client){throw 'Official OAuth client not present in package'}
}finally{$zip.Dispose()}
$configPath=Join-Path $vault 'container-deployment.json';$oldConfig=Get-Content -LiteralPath $configPath -Raw|ConvertFrom-Json -AsHashtable
if((Get-FileHash -LiteralPath $oldConfig.jar).Hash.ToLowerInvariant() -ne $oldConfig.jarSha){throw 'Existing release SHA mismatch'}
$old=(docker inspect $oldConfig.appName|ConvertFrom-Json)[0];if($LASTEXITCODE -ne 0 -or $old.Name -ne '/agent-platform-consultation-api'){throw 'Original product container missing'}
$originalId='40f8efdff24f';if(-not $old.Id.StartsWith($originalId)){throw 'Preserve changed original product'}
$jar=Join-Path $vault 'agent-platform-consultation-agent-20261006-01.jar';$newConfig=Join-Path $vault 'container-deployment-agent-20261006-01.json';$backup=Join-Path $vault 'container-deployment-pre-agent-20261006.json';$key=Join-Path $vault 'agent-runtime-key-20261006.credential.xml'
foreach($p in @($jar,$newConfig,$backup,$key)){if(Test-Path -LiteralPath $p){throw 'Preserve existing issuance/release/key'}}
Copy-Item -LiteralPath $configPath -Destination $backup
$secretBytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($secretBytes);$sealed=ConvertTo-SecureString ([Convert]::ToBase64String($secretBytes)) -AsPlainText -Force;$sealed|Export-Clixml -LiteralPath $key;[Array]::Clear($secretBytes,0,$secretBytes.Length);$sealed=$null
Copy-Item -LiteralPath $source -Destination $jar
$oldConfig.jar=$jar;$oldConfig.jarSha=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant();$oldConfig.settings['AGENT_PLATFORM_RUNTIME_ENABLED']='true';$oldConfig.secretFiles['AGENT_PLATFORM_RUNTIME_CHECKPOINT_KEY']=$key
$oldConfig|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $newConfig
[ordered]@{preparedAt=[DateTimeOffset]::Now.ToString('o');jarSha=$oldConfig.jarSha;jarName=[IO.Path]::GetFileName($jar);keyLength=32;keyDPAPIStored=$true;runtimeEnabled=$true;registeredOAuthClient='consultation-workspace-product';assets=$assets;previousContainerId=$old.Id;previousWasRunning=[bool]$old.State.Running;existingWorkerSubject=$oldConfig.settings.PRODUCT_CONSULTATION_WORKSPACE_WORKER_SUBJECT;workerReauthorized=$false;domainOrSourceOrGrantWrites=0}|ConvertTo-Json -Depth 7|Set-Content -LiteralPath (Join-Path $evidence 'release-preparation.json')
& (Join-Path $workspace 'scripts/start-consultation-container.ps1') -Configuration $newConfig -ReplaceContainerId $old.Id *> (Join-Path $evidence 'publish.log')
if(-not $?) {throw 'Controlled publication failed; original script rollback retained'}
$active=(docker inspect 'agent-platform-consultation-api'|ConvertFrom-Json)[0];if(-not $active.State.Running){throw 'Candidate not resident'}
Copy-Item -LiteralPath $newConfig -Destination $configPath
[ordered]@{publishedAt=[DateTimeOffset]::Now.ToString('o');id=$active.Id;name=$active.Name;running=[bool]$active.State.Running;port=5443;jarSha=$oldConfig.jarSha;runtimeEnabled=$true;currentPointerUpdated=$true;currentTaskGrantCreated=$false;currentSourceDecisionCreated=$false;existingWorkerExpiresNotChanged=$true}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'resident-product.json')
Write-Output "CONSULTATION_NATIVE_PUBLISHED=$($active.Id) port=5443"
