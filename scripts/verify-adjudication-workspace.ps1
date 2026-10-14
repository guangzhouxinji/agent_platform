param(
 [Parameter(Mandatory=$true)][string]$PrimaryCredentialFile,
 [Parameter(Mandatory=$true)][string]$AuthorityCredentialFile,
 [switch]$Recovery,
 [string]$BackendPom,
 [string]$RunId=('browser-'+[Guid]::NewGuid().ToString('N').Substring(0,12))
)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if($RunId -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe unique run ID required'}
$evidence=Join-Path $workspace "docs/evidence/adjudication-workspace-20261002/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing evidence overwrite'}
New-Item -ItemType Directory -Path $evidence | Out-Null
function Read-LocalSecret([string]$path){
 $secure=Import-Clixml -LiteralPath (Resolve-Path -LiteralPath $path).Path
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
if(-not $BackendPom){$BackendPom=Join-Path $workspace 'backend/pom.xml'}
$BackendPom=(Resolve-Path -LiteralPath $BackendPom).Path
$apiTarget=Join-Path (Split-Path -Parent $BackendPom) 'apps/platform-api/target'
try{
 $env:ADJ_WORKSPACE_TEST_CLASSES=Join-Path $apiTarget 'test-classes'
 $env:ADJ_DM8_PRIMARY_URL='jdbc:dm://127.0.0.1:5301';$env:ADJ_DM8_AUTHORITY_URL='jdbc:dm://127.0.0.1:5302'
 $env:ADJ_DM8_ADMIN_USER='SYSDBA';$env:ADJ_DM8_AUTHORITY_ADMIN_USER='SYSDBA'
 $env:ADJ_DM8_ADMIN_PASSWORD=Read-LocalSecret $PrimaryCredentialFile;$env:ADJ_DM8_AUTHORITY_ADMIN_PASSWORD=Read-LocalSecret $AuthorityCredentialFile
 $env:ADJ_WORKSPACE_EVIDENCE=$evidence;$env:ADJ_DM8_EVIDENCE=Join-Path $evidence 'api-compatibility'
 if($Recovery){$env:ADJ_WORKSPACE_RECOVERY='true'}else{Remove-Item Env:ADJ_WORKSPACE_RECOVERY -ErrorAction SilentlyContinue}
 $env:VITE_ADJUDICATION_CLIENT_ID='adjudication-workspace-browser'
 & node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $evidence 'verify-before.log')
 Write-Output "VERIFY_BEFORE_EXIT=$LASTEXITCODE"
 & npm --prefix (Join-Path $workspace 'frontend') run build *> (Join-Path $evidence 'frontend-build.log')
 if($LASTEXITCODE -ne 0){throw 'Actual frontend build failed'}
 & mvn -f $BackendPom "-Dmaven.multiModuleProjectDirectory=$workspace" -pl apps/platform-api -am '-Dtest=CaseWorkspaceBrowserIT,CaseWorkDm8ApiIT,CaseWorkHttpsLifecycleTest,CaseWorkHttpsNegativesTest,CaseWorkRecoveryFailuresTest,CaseWorkConcurrencyTest,CaseWorkProductContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $evidence 'tests.log')
 $test=$LASTEXITCODE
 Get-Content -LiteralPath (Join-Path $evidence 'tests.log') -Tail 12
 if($test -ne 0){throw 'Real browser/API/DM8 compatibility run failed'}
 Get-ChildItem -LiteralPath (Join-Path $apiTarget 'surefire-reports') -Filter 'TEST-cn.xinji.agentplatform.adjudication.casework.*.xml' | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $evidence}
 Write-Output "BROWSER_TEST_EXIT=$test EVIDENCE=$evidence"
}finally{
 & node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $evidence 'verify-after.log')
 Write-Output "VERIFY_AFTER_EXIT=$LASTEXITCODE EVIDENCE=$evidence"
 foreach($name in @('ADJ_DM8_ADMIN_PASSWORD','ADJ_DM8_AUTHORITY_ADMIN_PASSWORD','ADJ_DM8_PRIMARY_URL','ADJ_DM8_AUTHORITY_URL','ADJ_DM8_ADMIN_USER','ADJ_DM8_AUTHORITY_ADMIN_USER','ADJ_WORKSPACE_EVIDENCE','ADJ_DM8_EVIDENCE','VITE_ADJUDICATION_CLIENT_ID','ADJ_WORKSPACE_RECOVERY','ADJ_WORKSPACE_TEST_CLASSES')){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
}
