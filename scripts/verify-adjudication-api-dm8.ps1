param(
 [Parameter(Mandatory=$true)][string]$PrimaryCredentialFile,
 [Parameter(Mandatory=$true)][string]$AuthorityCredentialFile,
 [string]$PrimaryJdbcUrl='jdbc:dm://127.0.0.1:5301',
 [string]$AuthorityJdbcUrl='jdbc:dm://127.0.0.1:5302',
 [string]$PrimaryAdmin='SYSDBA',
 [string]$AuthorityAdmin='SYSDBA',
 [string]$RunId=('dm8-api-'+[Guid]::NewGuid().ToString('N').Substring(0,12))
)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if($RunId -notmatch '^[A-Za-z0-9-]{1,75}$' -or $PrimaryJdbcUrl -eq $AuthorityJdbcUrl){throw 'Distinct databases and a safe unique run ID are required'}
$evidence=Join-Path $workspace "docs/evidence/adjudication-api-20261002/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing evidence overwrite'}
New-Item -ItemType Directory -Path $evidence | Out-Null
function Read-LocalSecret([string]$path){
 $secure=Import-Clixml -LiteralPath (Resolve-Path -LiteralPath $path).Path
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
try{
 $env:ADJ_DM8_PRIMARY_URL=$PrimaryJdbcUrl;$env:ADJ_DM8_AUTHORITY_URL=$AuthorityJdbcUrl
 $env:ADJ_DM8_ADMIN_USER=$PrimaryAdmin;$env:ADJ_DM8_AUTHORITY_ADMIN_USER=$AuthorityAdmin
 $env:ADJ_DM8_ADMIN_PASSWORD=Read-LocalSecret $PrimaryCredentialFile
 $env:ADJ_DM8_AUTHORITY_ADMIN_PASSWORD=Read-LocalSecret $AuthorityCredentialFile
 $env:ADJ_DM8_EVIDENCE=$evidence
 & node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $evidence 'verify-before.log')
 if($LASTEXITCODE -ne 0){Write-Output 'Pre-execution verification did not pass; raw result retained, no baseline bypass'}
 & mvn -f (Join-Path $workspace 'backend/pom.xml') -pl apps/platform-api -am '-Dtest=CaseWorkDm8ApiIT' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $evidence 'dm8-api-tests.log')
 $testExit=$LASTEXITCODE
 if($testExit -ne 0){Get-Content -LiteralPath (Join-Path $evidence 'dm8-api-tests.log') -Tail 18;throw 'Actual isolated DM8 HTTPS/recovery test failed'}
 $report=Join-Path $workspace 'backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.adjudication.casework.CaseWorkDm8ApiIT.xml'
 Copy-Item -LiteralPath $report -Destination (Join-Path $evidence 'TEST-CaseWorkDm8ApiIT.xml')
 & node (Join-Path $workspace 'scripts/verify-all.mjs') *> (Join-Path $evidence 'verify-after.log')
 Write-Output "DM8_API_TEST_EXIT=$testExit VERIFY_EXIT=$LASTEXITCODE EVIDENCE=$evidence"
}finally{
 foreach($name in @('ADJ_DM8_ADMIN_PASSWORD','ADJ_DM8_AUTHORITY_ADMIN_PASSWORD','ADJ_DM8_PRIMARY_URL','ADJ_DM8_AUTHORITY_URL','ADJ_DM8_ADMIN_USER','ADJ_DM8_AUTHORITY_ADMIN_USER','ADJ_DM8_EVIDENCE')){
  Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
 }
}
