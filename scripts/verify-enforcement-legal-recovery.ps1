param([string]$RunId='ENF17-RECOVERY-20260930-26')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
$restoreRoot=Join-Path $env:TEMP $RunId
$gate=Join-Path $restoreRoot 'gate'
$volume="codex-enf-legal-cold-$($RunId.ToLowerInvariant())"
$container=$volume
if (Test-Path -LiteralPath $evidence) {throw 'Evidence directory already exists'}
if (Test-Path -LiteralPath $restoreRoot) {throw 'Recovery root already exists'}
if (Get-NetTCPConnection -State Listen -LocalPort 5324,5449 -ErrorAction SilentlyContinue) {throw 'Independent recovery ports occupied'}
$existingVolumes=docker volume ls --format '{{.Name}}'
if ($existingVolumes -contains $volume) {throw 'Refusing to reuse recovery volume'}
New-Item -ItemType Directory -Path $evidence,$restoreRoot | Out-Null
function Read-EnfColdSecret([string]$name) {
    $secure=Import-Clixml (Join-Path $vault $name)
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)} finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
$identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw | ConvertFrom-Json
if ($identity.testNature -ne 'FUNCTIONAL_VALIDATION') {throw 'Explicit technical identity required'}
$baseline=Join-Path $workspace 'docs/evidence/enforcement-followup-final-20260930/ENF17-LEGAL-BROWSER-20260930-24-browser/result.json'
$reference=(Get-Content -LiteralPath $baseline -Raw | ConvertFrom-Json).referenceId
$env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ'
$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Read-EnfColdSecret 'business-schema.credential.xml'
$env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312'
$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC'
$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Read-EnfColdSecret 'authority-service.credential.xml'
$env:ENF_ACCEPTANCE_AUTHORITY_SCHEMA='ENFACC_AUTH'
$env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
$env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId
$env:ENF_LEGAL_PREP_TARGET_TYPE='PROCEDURE_REPORT'
$env:ENF_LEGAL_PREP_REFERENCE_ID=$reference
$env:ENF_LEGAL_PREP_REFERENCE_VERSION='3'
$env:ENF_LEGAL_PREP_OUTPUT=Join-Path $evidence 'grant-input.json'
$env:ENF_LEGAL_FINAL_REFERENCE_ID=$reference
$env:ENF_LEGAL_FINAL_CATALOG=Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/catalog.json'
$env:ENF_LEGAL_FINAL_ORIGINAL=Join-Path $workspace 'data/shared/source-review/cnipa-rules-documents-v1/raw/patent-implementing-rules-2023.html'
$env:ENF_LEGAL_FINAL_OUTPUT=Join-Path $evidence 'source-reconciliation.json'
$sourceCatalog=$env:ENF_LEGAL_FINAL_CATALOG
$sourceOriginal=$env:ENF_LEGAL_FINAL_ORIGINAL
function Invoke-EnfColdTest([string]$test,[string]$suffix) {
    & mvn -q -f (Join-Path $workspace 'backend/pom.xml') -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test
    if ($LASTEXITCODE -ne 0) {throw "Actual execution failed: $suffix"}
    $xmls=Get-ChildItem (Join-Path $workspace 'backend/apps/platform-api/target/surefire-reports') -Filter "*-$suffix.xml"
    if ($xmls.Count -ne 1) {throw 'Execution XML is not unique'}
    foreach($file in $xmls) {
        [xml]$xml=Get-Content -LiteralPath $file.FullName
        if ([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) {throw 'Non-passing or skipped I'}
        Copy-Item -LiteralPath $file.FullName -Destination $evidence
    }
}
function Wait-EnfColdGate([string]$suffix) {
    $deadline=(Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath "$gate$suffix") {return}
        if ($browser.HasExited) {throw "Browser failed before gate $suffix"}
        Start-Sleep -Milliseconds 300
    }
    throw "Gate timeout: $suffix"
}
$sourceStopped=$false
function Start-EnfColdRuntime([hashtable]$settings) {
    $port=if($settings.ContainsKey('HttpsPort')){[int]$settings.HttpsPort}else{5444}
    $prefix=if($port -eq 5444){'server'}else{"server-$port"}
    for($attempt=1;$attempt -le 4;$attempt++) {
        & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') @settings
        $runtimePid=[int](Get-Content (Join-Path $vault "$prefix.pid"))
        $deadline=(Get-Date).AddSeconds(40)
        while((Get-Date) -lt $deadline) {
            try {$r=Invoke-WebRequest "https://127.0.0.1:$port/login" -SkipCertificateCheck; if($r.StatusCode -eq 200){return}}catch{}
            if(-not(Get-Process -Id $runtimePid -ErrorAction SilentlyContinue)){break}
            Start-Sleep -Milliseconds 500
        }
        Copy-Item -LiteralPath (Join-Path $vault "$prefix-out.log") -Destination (Join-Path $evidence "startup-$port-attempt-$attempt.log")
        if(Get-Process -Id $runtimePid -ErrorAction SilentlyContinue){throw 'Runtime alive but health check failed; not stopping unverified service'}
        Start-Sleep -Seconds 1
    }
    throw "HTTPS runtime did not become ready: $port"
}
try {
    Invoke-EnfColdTest 'EnforcementLegalAcceptanceGrantFixtureTest' "$RunId-grants"
    Invoke-EnfColdTest 'LegalReferenceRuntimeDm8Test' "$RunId-source-I"
    $env:ENF_FOLLOWUP_LOGIN=$identity.loginName
    $env:ENF_FOLLOWUP_PASSWORD=Read-EnfColdSecret 'functional-a.credential.xml'
    $env:ENF_LEGAL_RECOVERY_RUN_ID="$RunId-browser"
    $env:ENF_LEGAL_RECOVERY_GATE=$gate
    $env:ENF_LEGAL_BROWSER_BASELINE=$baseline
    $originalRelative='data/shared/source-review/cnipa-rules-documents-v1/raw/patent-implementing-rules-2023.html'
    $env:ENF_LEGAL_RECOVERY_ORIGINAL=Join-Path $restoreRoot $originalRelative
    $browser=Start-Process -FilePath 'D:/software/nodejs/node.exe' -ArgumentList 'e2e/enforcement-legal-reference-recovery-product.mjs' -WorkingDirectory (Join-Path $workspace 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'browser-out.log') -RedirectStandardError (Join-Path $evidence 'browser-error.log')
    Wait-EnfColdGate '.session-ready'
    $servicePid=[int](Get-Content (Join-Path $vault 'server.pid'))
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid"
    $listener=Get-NetTCPConnection -State Listen -LocalPort 5444
    if ($listener.OwningProcess -ne $servicePid -or $process.CommandLine -notmatch 'cn.xinji.agentplatform.AgentPlatformApplication') {throw 'Unverified source runtime owner'}
    Stop-Process -Id $servicePid
    & docker stop codex-enf-i-business | Out-Null
    if ($LASTEXITCODE -ne 0) {throw 'Unable to stop source DB at consistent point'}
    $sourceStopped=$true
    & docker volume create $volume | Out-Null
    if ($LASTEXITCODE -ne 0) {throw 'Recovery volume create failed'}
    $inspection=docker inspect codex-enf-i-business | ConvertFrom-Json
    $image=$inspection[0].Config.Image
    & docker run --rm --entrypoint /bin/sh -v 'codex-enf-i-business-data:/source:ro' -v "${volume}:/target" $image -c 'cp -a /source/. /target/ && sync'
    if ($LASTEXITCODE -ne 0) {throw 'Physical DB copy failed'}
    $originalTarget=Join-Path $restoreRoot $originalRelative
    New-Item -ItemType Directory -Path (Split-Path -Parent $originalTarget) -Force | Out-Null
    Copy-Item -LiteralPath $sourceOriginal -Destination $originalTarget
    Copy-Item -LiteralPath $sourceCatalog -Destination (Join-Path $restoreRoot 'catalog.json')
    Copy-Item -LiteralPath (Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/target-procedure-input.json') -Destination (Join-Path $restoreRoot 'target-procedure-input.json')
    Copy-Item -LiteralPath (Join-Path $vault 'files') -Destination $restoreRoot -Recurse
    Copy-Item -LiteralPath (Join-Path $vault 'document-templates') -Destination $restoreRoot -Recurse
    & docker start codex-enf-i-business | Out-Null
    Start-EnfColdRuntime @{ProcedureSourceFile=(Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/target-procedure-input.json');ProcedureApprovedBasis='PROJECT-METHOD-ENF17|1|TECHNICAL_TEST_ONLY|PROJECT_TEST_NOT_LEGAL|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_REVIEW'}
    $sourceStopped=$false
    $containerArgs=@('create','--name',$container,'--privileged','--shm-size=1g','-p','127.0.0.1:5324:5236','-v',"${volume}:/opt/dmdbms/data")
    foreach($entry in $inspection[0].Config.Env) {
        $pair=$entry -split '=',2
        if ($pair[0] -match '^(SYSDBA_PWD|SYSAUDITOR_PWD|SYSSSO_PWD|INSTANCE_NAME|PAGE_SIZE|EXTENT_SIZE|BLANK_PAD_MODE|LOG_SIZE|CASE_SENSITIVE|UNICODE_FLAG|LENGTH_IN_CHAR|CHARSET)$') {
            [Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process')
            $containerArgs+=@('-e',$pair[0])
        }
    }
    $containerArgs+=$image
    & docker @containerArgs | Out-Null
    if ($LASTEXITCODE -ne 0) {throw 'Recovery DB container create failed'}
    & docker start $container | Out-Null
    $env:ENF_LEGAL_FINAL_CATALOG=Join-Path $restoreRoot 'catalog.json'
    $env:ENF_LEGAL_FINAL_ORIGINAL=$originalTarget
    Start-EnfColdRuntime @{HttpsPort=5449;BusinessPort=5324;IdentityIssuer='https://127.0.0.1:5444';LegalCatalogFile=$env:ENF_LEGAL_FINAL_CATALOG;ProcedureSourceFile=(Join-Path $restoreRoot 'target-procedure-input.json');ProcedureApprovedBasis='PROJECT-METHOD-ENF17|1|TECHNICAL_TEST_ONLY|PROJECT_TEST_NOT_LEGAL|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_REVIEW';TemplateFileRoot=(Join-Path $restoreRoot 'document-templates');MaterialFileRoot=(Join-Path $restoreRoot 'files')}
    $deadline=(Get-Date).AddMinutes(3)
    $ready=$false
    while ((Get-Date) -lt $deadline) {
        try {$response=Invoke-WebRequest 'https://127.0.0.1:5449/login' -SkipCertificateCheck; if($response.StatusCode -eq 200){$ready=$true;break}} catch {}
        Start-Sleep -Milliseconds 500
    }
    if(-not $ready){throw 'Restored HTTPS did not become ready'}
    New-Item -ItemType File -Path "$gate.restore-ready" | Out-Null
    Wait-EnfColdGate '.read-ready'
    $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5324'
    $env:ENF_LEGAL_FINAL_OUTPUT=Join-Path $evidence 'restored-reconciliation.json'
    Invoke-EnfColdTest 'LegalReferenceRuntimeDm8Test' "$RunId-restored-I"
    $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311'
    $env:ENF_LEGAL_PREP_REVOKE_READ='true'
    $env:ENF_LEGAL_PREP_OUTPUT=Join-Path $evidence 'withdrawal.json'
    Invoke-EnfColdTest 'EnforcementLegalAcceptanceGrantFixtureTest' "$RunId-withdrawal"
    New-Item -ItemType File -Path "$gate.withdraw-ready" | Out-Null
    $browser.WaitForExit(60000)
    if (-not $browser.HasExited -or $browser.ExitCode -ne 0) {throw 'Recovery browser/fail-closed verification failed'}
    $source=Get-Content (Join-Path $evidence 'source-reconciliation.json') -Raw | ConvertFrom-Json
    $restored=Get-Content (Join-Path $evidence 'restored-reconciliation.json') -Raw | ConvertFrom-Json
    if ($source.targetSha256 -ne $restored.targetSha256 -or $source.originalSha256 -ne $restored.originalSha256) {throw 'Source/restored SHA mismatch'}
    [ordered]@{runId=$RunId;physicalRestore='PASSED';sourceVolume='codex-enf-i-business-data';targetVolume=$volume;targetDb=5324;targetHttps=5449;independentAuthority='5312_NOT_RESTORED';referenceId=$reference;targetSha256=$source.targetSha256;originalSha256=$source.originalSha256;businessAcceptance='PENDING'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'physical-restore-result.json') -Encoding utf8
} finally {
    if($sourceStopped){docker start codex-enf-i-business | Out-Null}
    foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD','SYSDBA_PWD','SYSAUDITOR_PWD','SYSSSO_PWD','ENF_LEGAL_PREP_REVOKE_READ')){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
}
