param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$BuildRoot,[string]$TestName='EnforcementDocumentDraftPublicAdmissionIT',[string]$LinkedCaseRoot='',[string]$MaterialRoot='', [string]$MappingReport='')
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9-]+$'){throw 'Invalid evidence ID'}
$workspacePath=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vaultPath=Join-Path $env:LOCALAPPDATA 'AgentPlatform\enforcement-product'
$outputPath=Join-Path $workspacePath "docs/evidence/enforcement-document-draft-20261002/$RunId"
if(Test-Path -LiteralPath $outputPath){throw 'Fresh evidence directory required'}
New-Item -ItemType Directory -Path $outputPath | Out-Null
function Read-LocalSecret([string]$name){
 $secure=Import-Clixml (Join-Path $vaultPath $name)
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
try {
 $identityA=Get-Content (Join-Path $vaultPath 'functional-a.identity.json') -Raw | ConvertFrom-Json
 $identityB=Get-Content (Join-Path $vaultPath 'functional-b.identity.json') -Raw | ConvertFrom-Json
 if($identityA.testNature -ne 'FUNCTIONAL_VALIDATION' -or $identityB.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Recorded controlled technical identities required'}
 $env:ENF_DRAFT_IDENTITY_NATURE='FUNCTIONAL_VALIDATION'
 $env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
 $env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
 $env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Read-LocalSecret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312'
 $env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC'
 $env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Read-LocalSecret 'authority-service.credential.xml'
 $env:ENF_ACCEPTANCE_SUBJECT=$identityA.subjectId
 $env:ENF_DRAFT_VERIFIER_SUBJECT=$identityB.subjectId
 $env:ENF_ACCEPTANCE_ORGANIZATION=$identityA.organization
 $env:ENF_DRAFT_LOGIN_A=$identityA.loginName
 $env:ENF_DRAFT_LOGIN_B=$identityB.loginName
 $env:ENF_DRAFT_PASSWORD_A=Read-LocalSecret 'functional-a.credential.xml'
 $env:ENF_DRAFT_PASSWORD_B=Read-LocalSecret 'functional-b.credential.xml'
 $env:ENF_DRAFT_ROOT_CERT=Join-Path $vaultPath 'root-ca.cer'
 $env:ENF_DRAFT_PUBLIC_RUN=$RunId
 $env:ENF_DRAFT_EXECUTION_TEST=$TestName
 $env:ENF_DRAFT_LINKED_CASE_ROOT=$LinkedCaseRoot
 $env:ENF_DRAFT_MATERIAL_ROOT=$MaterialRoot
 $env:ENF_DRAFT_MAPPING_REPORT=$MappingReport
 $env:ENF_DRAFT_PUBLIC_OUTPUT=$outputPath
 $env:ENF_DRAFT_PUBLIC_ASSETS=Join-Path $workspacePath 'docs/evidence/enforcement-document-draft-20261002/ENF14-PUBLIC-INPUT-20261002-40'
 $env:ENF_MAVEN_POM=Join-Path $BuildRoot 'backend/pom.xml'
 $env:JAVA_HOME='D:\software\jdk-21'
 & node (Join-Path $workspacePath 'frontend/e2e/enforcement-document-draft-public-admission.mjs')
 if($LASTEXITCODE -ne 0){throw 'Public admission actual execution failed; retained evidence'}
} finally {
 Get-ChildItem Env:ENF_DRAFT_*,Env:ENF_ACCEPTANCE_* | Remove-Item
}
