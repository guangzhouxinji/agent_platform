param(
 [Parameter(Mandatory=$true)][string]$PrimaryCredentialFile,
 [Parameter(Mandatory=$true)][string]$BackendPom,
 [Parameter(Mandatory=$true)][string]$RunId,
 [switch]$RestoreOnly
)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9-]{1,65}$'){throw 'Safe unique evidence ID required'}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$evidence=Join-Path $workspace "docs/evidence/adjudication-llm-materials-20261003/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Refusing evidence overwrite'}
New-Item -ItemType Directory -Path $evidence | Out-Null
$private=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1'
New-Item -ItemType Directory -Force -Path $private | Out-Null
$passwordFile=Join-Path $private 'dm8-model-schema.credential.xml'
if(-not (Test-Path -LiteralPath $passwordFile)){
 $password='M9'+[Guid]::NewGuid().ToString('N').Substring(0,30)
 ConvertTo-SecureString -String $password -AsPlainText -Force | Export-Clixml -LiteralPath $passwordFile
 Remove-Variable password
}
function Read-LocalSecret([string]$path){
 $value=Import-Clixml -LiteralPath (Resolve-Path -LiteralPath $path).Path
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
 try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
try{
 if(-not $env:DEEPSEEK_API_KEY){throw 'Real approved provider credential required; never use a substitute'}
 $env:ADJ_MODEL_COMPONENT_LIVE='true';$env:ADJ_MODEL_COMPONENT_DB_URL='jdbc:dm://127.0.0.1:5301';$env:ADJ_MODEL_COMPONENT_DB_USER='ADJLLM20261003'
 $env:ADJ_MODEL_COMPONENT_DB_PASSWORD=Read-LocalSecret $passwordFile
 $env:ADJ_MODEL_COMPONENT_KEY_DIRECTORY=Join-Path $private 'keys'
 $env:ADJ_MODEL_COMPONENT_EVIDENCE=$evidence;$env:ADJ_DM8_ADMIN_USER='SYSDBA';$env:ADJ_DM8_ADMIN_PASSWORD=Read-LocalSecret $PrimaryCredentialFile
 $testSelector=if($RestoreOnly){'MaterialModelDm8IT#restorePreviousRunWithCurrentCodeAndNoProviderRequest'}else{'MaterialModelDm8IT#approvedActualModelAndPersistentDm8EncryptedRecovery'}
 & mvn -f $BackendPom "-Dmaven.multiModuleProjectDirectory=$workspace" -pl apps/platform-api -am "-Dtest=$testSelector" '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $evidence 'tests.log')
 $code=$LASTEXITCODE;Get-Content -LiteralPath (Join-Path $evidence 'tests.log') -Tail 20
 if($code -ne 0){throw 'Approved model/DM8 component run failed; persistent budget retained, no automatic retry'}
 Write-Output "MODEL_COMPONENT_EXIT=$code EVIDENCE=$evidence"
}finally{
 foreach($name in @('ADJ_MODEL_COMPONENT_LIVE','ADJ_MODEL_COMPONENT_DB_URL','ADJ_MODEL_COMPONENT_DB_USER','ADJ_MODEL_COMPONENT_DB_PASSWORD','ADJ_MODEL_COMPONENT_KEY_DIRECTORY','ADJ_MODEL_COMPONENT_EVIDENCE','ADJ_DM8_ADMIN_USER','ADJ_DM8_ADMIN_PASSWORD')){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
}
