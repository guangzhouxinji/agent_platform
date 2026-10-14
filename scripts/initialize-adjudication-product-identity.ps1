param([switch]$ResumeBeforeBootstrap)
$ErrorActionPreference='Stop';$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$root=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1/product-runtime';$e=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01'
function Secret([string]$file){$s=Import-Clixml -LiteralPath $file;$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
$jar=Join-Path $env:TEMP 'adj-isolated-build-adj-subject-20261004-01/backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar'
if(-not(Test-Path -LiteralPath $jar)){throw 'Verified isolated release jar required'}
if(Test-Path -LiteralPath "$root/application.jar"){if(-not $ResumeBeforeBootstrap -or (Get-FileHash -LiteralPath "$root/application.jar").Hash -ne (Get-FileHash -LiteralPath $jar).Hash){throw 'Preserve existing release'}}else{Copy-Item -LiteralPath $jar -Destination "$root/application.jar";[IO.Compression.ZipFile]::ExtractToDirectory("$root/application.jar","$root/boot")}
$container='agent-platform-adj-authority-20261004';$exists=((& docker ps -a --format '{{.Names}}') -contains $container);if($exists -and -not $ResumeBeforeBootstrap){throw 'Preserve existing authority'}
if(-not $exists -and (Get-NetTCPConnection -LocalPort 5370 -State Listen -ErrorAction SilentlyContinue)){throw 'Authority port unavailable'}
$set=@{};function EnvSet([string]$n,[string]$v){$set[$n]=[Environment]::GetEnvironmentVariable($n,'Process');[Environment]::SetEnvironmentVariable($n,$v,'Process')}
try{
 EnvSet 'SYSDBA_PWD' (Secret "$root/authority-admin.credential.xml");EnvSet 'SYSAUDITOR_PWD' ('A9'+[Guid]::NewGuid().ToString('N'));EnvSet 'SYSSSO_PWD' ('S9'+[Guid]::NewGuid().ToString('N'))
 if(-not $exists){ & docker run -d --name $container --shm-size=1g -p '127.0.0.1:5370:5236' -e SYSDBA_PWD -e SYSAUDITOR_PWD -e SYSSSO_PWD -e PAGE_SIZE=32 -e EXTENT_SIZE=32 -e UNICODE_FLAG=1 -e CASE_SENSITIVE=1 -e INSTANCE_NAME=ADJ_AUTHORITY cnxc/dm8:20251114-kylin *> "$root/authority-creation.log";if($LASTEXITCODE -ne 0){throw 'New ADJ authority instance failed'}}
 $driver=(Get-ChildItem "$env:USERPROFILE/.m2/repository/com/dameng" -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
 $ready=$false;for($i=0;$i -lt 30;$i++){if(Get-NetTCPConnection -LocalPort 5370 -State Listen -ErrorAction SilentlyContinue){Start-Sleep -Seconds 12;$ready=$true;break};Start-Sleep -Seconds 4};if(-not $ready){throw 'ADJ authority did not start'}
 EnvSet 'PRODUCT_IDENTITY_DB_URL' 'jdbc:dm://127.0.0.1:5301';EnvSet 'PRODUCT_IDENTITY_DB_DEPLOY_USER' 'ADJLLM20261003';EnvSet 'PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD' (Secret (Join-Path (Split-Path -Parent $root) 'dm8-model-schema.credential.xml'))
 EnvSet 'PRODUCT_IDENTITY_AUTHORITY_DB_URL' 'jdbc:dm://127.0.0.1:5370';EnvSet 'PRODUCT_IDENTITY_AUTHORITY_DEPLOY_USER' 'ADJAUTH20261004';EnvSet 'PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD' (Secret "$root/authority-owner.credential.xml")
 EnvSet 'PRODUCT_IDENTITY_AUTHORITY_SERVICE_USER' 'ADJAUTH_SVC';EnvSet 'PRODUCT_IDENTITY_AUTHORITY_BACKUP_USER' 'ADJAUTH_BKP'
 EnvSet 'ADJ_AUTHORITY_SERVICE_PASSWORD' (Secret "$root/authority-service.credential.xml");EnvSet 'ADJ_AUTHORITY_BACKUP_PASSWORD' (Secret "$root/authority-backup.credential.xml");EnvSet 'ADJ_AUTHORITY_ADMIN_PASSWORD' (Secret "$root/authority-admin.credential.xml")
 $trust=Get-Content "$e/selected-trust.json" -Raw|ConvertFrom-Json;EnvSet 'PRODUCT_IDENTITY_TRUST_SHA256' $trust.trustCaSha256
 & "$env:JAVA_HOME/bin/java.exe" -cp "$root/boot/BOOT-INF/classes;$root/boot/BOOT-INF/lib/*;$driver" (Join-Path $workspace 'scripts/java/InitializeAdjudicationIdentityDomains.java') "$root/identity-bootstrap.json" *> "$e/database-initialization.log"
 if($LASTEXITCODE -ne 0){throw 'Controlled ADJ initialization failed; retain instance and original budget, do not rerun bootstrap blindly'}
 [ordered]@{issuer='https://localhost:5445';organization='project-adjudication';primaryUrl='jdbc:dm://127.0.0.1:5301';primarySchema='ADJLLM20261003';authorityUrl='jdbc:dm://127.0.0.1:5370';authoritySchema='ADJAUTH20261004';authorityContainer=$container;authorityContainerId=(& docker inspect --format '{{.Id}}' $container);releaseSha256=(Get-FileHash "$root/application.jar").Hash.ToLower();maintainer=$trust.maintainer;initialModelCalls=1;businessGrantsCreated=0;consultationAuthorityMaintainerChanged=$false;realPersonIdentityVerified=$false}|ConvertTo-Json|Set-Content "$e/deployment-initialization.json" -Encoding utf8
 Write-Output 'CONTROLLED_ADJ_DEPLOYMENT_INITIALIZED_EXISTING_BUDGET_RETAINED'
}finally{foreach($n in $set.Keys){[Environment]::SetEnvironmentVariable($n,$set[$n],'Process')}}
