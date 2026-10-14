param([switch]$Worker)
$ErrorActionPreference='Stop';$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$root=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1/product-runtime'
if(-not $Worker){
 if(Get-NetTCPConnection -LocalPort 5445 -State Listen -ErrorAction SilentlyContinue){throw 'Preserve existing listener'}
 $p=Start-Process pwsh.exe -WindowStyle Hidden -ArgumentList @('-NoProfile','-File',$PSCommandPath,'-Worker') -PassThru -RedirectStandardOutput "$root/service-wrapper.log" -RedirectStandardError "$root/service-wrapper-error.log"
 Write-Output "ADJ_SERVICE_START_DISPATCHED_PID=$($p.Id)";return
}
function Secret([string]$file){$s=Import-Clixml -LiteralPath $file;$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
if(-not $env:DEEPSEEK_API_KEY){throw 'Approved provider credential must be supplied securely; no fallback'}
$e=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261003'
$approval=Get-Content "$e/user-approval-v1.json" -Raw|ConvertFrom-Json;$manifest=Get-Content "$e/user-approved-material-package-v1/manifest.json" -Raw|ConvertFrom-Json
function TextSha([string]$s){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($s))).ToLower()}
$policy=[ordered]@{approvalRef=$approval.reference;approvalSha256=(TextSha ([IO.File]::ReadAllText("$e/user-approval-v1.json")));supplierApprovalSha256=(TextSha ([IO.File]::ReadAllText("$e/supplier-approval-v1.json")));caseId='adjudication-materials-case-01';validUntil='2026-11-01T16:00:00Z';permittedTextHashes=@($manifest.files|ForEach-Object {$_.sha256});modelVersion='deepseek-flash'}
$policy|ConvertTo-Json|Set-Content "$root/material-policy.json" -Encoding utf8
$config=[ordered]@{
 'server.address'='127.0.0.1';'server.port'=5445;'server.ssl.enabled'=$true;'server.ssl.key-store'="$root/server.p12";'server.ssl.key-store-type'='PKCS12';'server.ssl.key-store-password'=(Secret "$root/tls.credential.xml");'server.ssl.trust-store'="$root/trust.p12";'server.ssl.trust-store-type'='PKCS12';'server.ssl.trust-store-password'=(Secret "$root/tls.credential.xml");'server.ssl.client-auth'='want';
 'spring.profiles.active'='adjudication-product';'spring.main.banner-mode'='off';'logging.level.root'='WARN';
 'spring.datasource.url'='jdbc:dm://127.0.0.1:5301';'spring.datasource.username'='ADJLLM20261003';'spring.datasource.password'=(Secret (Join-Path (Split-Path -Parent $root) 'dm8-model-schema.credential.xml'));'spring.datasource.driver-class-name'='dm.jdbc.driver.DmDriver';
 'product.identity.enabled'=$true;'product.identity.issuer'='https://localhost:5445';'product.identity.audience'='adjudication-work-api';'product.identity.master-key'=(Secret "$root/identity-master.credential.xml");'product.identity.token-verification-window'='PT10M';'product.identity.activation-lifetime'='PT10M';'product.identity.minimum-password-length'=16;
 'product.identity.authority.jdbc-url'='jdbc:dm://127.0.0.1:5370';'product.identity.authority.username'='ADJAUTH_SVC';'product.identity.authority.password'=(Secret "$root/authority-service.credential.xml");'product.identity.authority.schema'='ADJAUTH20261004';
 'adjudication.v2.enabled'=$true;'adjudication.v2.identity-administration.enabled'=$true;'adjudication.v2.identity-administration.organization'='project-adjudication';
 'adjudication.v2.materials.enabled'=$true;'adjudication.v2.materials.policy-file'="$root/material-policy.json";'adjudication.v2.materials.key-directory'=(Join-Path (Split-Path -Parent $root) 'keys')
}
$releaseJar="$root/application.jar"
if(Test-Path -LiteralPath "$root/current-release.json"){$release=Get-Content "$root/current-release.json" -Raw|ConvertFrom-Json;$full=[IO.Path]::GetFullPath($release.jar);if(-not $full.StartsWith([IO.Path]::GetFullPath($root)+[IO.Path]::DirectorySeparatorChar) -or (Get-FileHash -LiteralPath $full).Hash.ToLower() -ne $release.sha256){throw 'Controlled release pointer invalid'};$releaseJar=$full}
$previous=$env:SPRING_APPLICATION_JSON
try {
 $env:SPRING_APPLICATION_JSON=$config|ConvertTo-Json -Compress -Depth 8
 $p=Start-Process "$env:JAVA_HOME/bin/java.exe" -WindowStyle Hidden -ArgumentList @('-Dloader.main=cn.xinji.agentplatform.adjudication.casework.ProductAdjudicationApplication','-cp',$releaseJar,'org.springframework.boot.loader.launch.PropertiesLauncher') -WorkingDirectory $workspace -PassThru -RedirectStandardOutput "$root/service.log" -RedirectStandardError "$root/service-error.log"
 $p.Id|Set-Content "$root/runtime.pid" -Encoding ascii;$p.WaitForExit();if($p.ExitCode -ne 0){throw 'ADJ service exited unsuccessfully; inspect private startup diagnostics'}
}finally{$env:SPRING_APPLICATION_JSON=$previous}
