param([Parameter(Mandatory=$true)][string]$RunId,[string]$RuleRunId='ENF15-PRODUCT-20261001-119')
$ErrorActionPreference='Stop'
foreach($id in @($RunId,$RuleRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe execution ID required'}}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
$restoreRoot=Join-Path $env:TEMP "$RunId-physical-product"
$volume='codex-enf-penalty-'+$RunId.ToLowerInvariant();$container=$volume
foreach($path in @($evidence,$restoreRoot)){if(Test-Path -LiteralPath $path){throw 'Fresh evidence and recovery roots required'}}
foreach($port in @(5326,5452,5179)){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Recovery port $port occupied"}}
if((& docker volume ls --format '{{.Name}}') -contains $volume){throw 'Fresh physical recovery volume required'}
New-Item -ItemType Directory -Path $evidence,$restoreRoot | Out-Null
function Secret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
function Actual([string]$test,[string]$suffix){
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence}
 if($exit -ne 0 -or -not(Test-Path -LiteralPath $xml)){throw "Actual phase failed: $suffix"}
 [xml]$report=Get-Content -LiteralPath $xml -Raw
 if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'Actual phase must be 1/0/0/0'}
}
function Wait-Http([string]$url,[string]$expected){for($n=0;$n -lt 90;$n++){$http=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' $url;if($http -eq $expected){return};Start-Sleep -Milliseconds 500};throw "Runtime unavailable: $url"}
$sourceStopped=$false
try {
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml'
 $env:ENF_ACCEPTANCE_AUTHORITY_URL='jdbc:dm://127.0.0.1:5312';$env:ENF_ACCEPTANCE_AUTHORITY_USER='ENFACC_ASVC';$env:ENF_ACCEPTANCE_AUTHORITY_PASSWORD=Secret 'authority-service.credential.xml';$env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444'
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw|ConvertFrom-Json
 if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit controlled technical identity required'}
 $env:ENF_ACCEPTANCE_SUBJECT=$identity.subjectId;$env:ENF_ACCEPTANCE_ORGANIZATION=$identity.organization
 $env:ENF_PENALTY_RULE_ID="ENF15-$RuleRunId";$env:ENF_PENALTY_CLIENT_ID="enforcement-penalty-recovery-$RunId";$env:ENF_PENALTY_FRONTEND_PORT='5179';$env:ENF_PENALTY_PREP_EVIDENCE=Join-Path $evidence 'technical-client-preparation.json'
 Actual 'cn.xinji.agentplatform.security.product.EnforcementPenaltyGrantIT' "$RunId-client"
 $env:ENF_PENALTY_ORIGINALS_BASE=$vault;$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $evidence 'source-inventory.json'
 Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoverySourceDm8IT' "$RunId-source"
 $inventory=Get-Content -LiteralPath $env:ENF_PENALTY_SOURCE_INVENTORY -Raw|ConvertFrom-Json
 $inspection=& docker inspect codex-enf-i-business|ConvertFrom-Json
 if($inspection[0].Mounts.Name -notcontains 'codex-enf-i-business-data'){throw 'Unverified dedicated source volume'};$image=$inspection[0].Config.Image
 & docker stop codex-enf-i-business|Out-Null;if($LASTEXITCODE -ne 0){throw 'Cold source stop failed'};$sourceStopped=$true
 & docker volume create $volume|Out-Null;if($LASTEXITCODE -ne 0){throw 'Fresh target volume creation failed'}
 & docker run --rm --entrypoint /bin/sh -v 'codex-enf-i-business-data:/source:ro' -v "$($volume):/target" $image -c 'cp -a /source/. /target/ && sync'|Out-Null
 if($LASTEXITCODE -ne 0){throw 'Cold physical DM8 copy failed'}
 foreach($pair in @(@('codex-enf-i-business-data','source'),@($volume,'target'))){
  & docker run --rm --entrypoint /bin/sh -v "$($pair[0]):/snapshot:ro" $image -c 'cd /snapshot && find . -type f -exec sha256sum {} +' *> (Join-Path $evidence "$($pair[1])-physical-db-sha.txt")
  if($LASTEXITCODE -ne 0){throw 'Physical DB SHA collection failed'}
 }
 $a=Get-Content (Join-Path $evidence 'source-physical-db-sha.txt')|Sort-Object;$b=Get-Content (Join-Path $evidence 'target-physical-db-sha.txt')|Sort-Object
 if(-not $a -or (Compare-Object $a $b)){throw 'Physical DB bytes mismatch'}
 $penaltyRoot=Join-Path $restoreRoot 'penalty';New-Item -ItemType Directory -Path $penaltyRoot|Out-Null
 foreach($record in $inventory.records){
  if((Get-FileHash -LiteralPath $record.originalPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record.sha256){throw 'Source original changed before checkpoint'}
  $target=Join-Path $penaltyRoot $record.row.ARTIFACT_KEY;Copy-Item -LiteralPath $record.originalPath -Destination $target
  if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record.sha256){throw 'Restored original byte mismatch'}
 }
 foreach($directory in @('files','document-templates')){Copy-Item -LiteralPath (Join-Path $vault $directory) -Destination $restoreRoot -Recurse}
 $liveCatalog=Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/catalog.json'
 Copy-Item -LiteralPath $liveCatalog -Destination (Join-Path $restoreRoot 'legal-catalog.json')
 $provenance=(Get-Content -LiteralPath $liveCatalog -Raw|ConvertFrom-Json).provenance
 $legalOriginal=if([IO.Path]::IsPathRooted($provenance.snapshot)){$provenance.snapshot}else{Join-Path $workspace $provenance.snapshot}
 if((Get-FileHash -LiteralPath $legalOriginal -Algorithm SHA256).Hash.ToLowerInvariant() -ne $provenance.snapshotSha256){throw 'Live legal original SHA mismatch'}
 Copy-Item -LiteralPath $legalOriginal -Destination (Join-Path $restoreRoot 'retained-legal-source-original.html')
 & docker start codex-enf-i-business|Out-Null;if($LASTEXITCODE -ne 0){throw 'Source restart failed'};$sourceStopped=$false
 $arguments=@('create','--name',$container,'--privileged','--shm-size=1g','-p','127.0.0.1:5326:5236','-v',"$($volume):/opt/dmdbms/data")
 foreach($entry in $inspection[0].Config.Env){$pair=$entry -split '=',2;if($pair[0] -match '^(SYSDBA_PWD|SYSAUDITOR_PWD|SYSSSO_PWD|INSTANCE_NAME|PAGE_SIZE|EXTENT_SIZE|BLANK_PAD_MODE|LOG_SIZE|CASE_SENSITIVE|UNICODE_FLAG|LENGTH_IN_CHAR|CHARSET)$'){[Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process');$arguments+=@('-e',$pair[0])}}
 $arguments+=$image;& docker @arguments|Out-Null;if($LASTEXITCODE -ne 0){throw 'Independent restore container creation failed'}
 & docker start $container|Out-Null;if($LASTEXITCODE -ne 0){throw 'Independent restore container start failed'}
 # A listening Docker port is not a working DM8 session. This readiness check is
 # never counted as a product test; the real target reconciliation follows it.
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5326'
 $classRoots=@(Get-ChildItem (Join-Path $env:ENF_MAVEN_BUILD_ROOT 'backend') -Directory -Recurse | Where-Object {$_.FullName -match '\\target\\(test-classes|classes)$'} | ForEach-Object {$_.FullName})
 $classpath=($classRoots+((Get-Content -Raw (Join-Path $env:TEMP 'enf-runtime-classpath.txt')).Trim())) -join ';'
 # Use the exact JDK of the actual product runtime, not a PATH-selected JDK 17.
 & 'D:\software\jdk-21\bin\java.exe' -cp $classpath cn.xinji.agentplatform.enforcement.penalty.PenaltyDm8Readiness *> (Join-Path $evidence 'restore-db-readiness.log')
 if($LASTEXITCODE -ne 0){throw 'Actual restored DM8 JDBC readiness failed; product I NOT_RUN'}
 $env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$penaltyRoot;$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_URL='jdbc:dm://127.0.0.1:5311';$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_USER='ENFACC_BIZ';$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_PASSWORD=$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD;$env:ENFORCEMENT_V2_PENALTY_CURRENT_FILE_ROOT=Join-Path $vault "penalty-product/$RuleRunId"
 # The retained backup stays immutable; current source state must come from
 # the live external catalog, never an old restored catalog presented as current.
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort 5452 -BusinessPort 5326 -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -BuildRoot $env:ENF_MAVEN_BUILD_ROOT -MaterialFileRoot (Join-Path $restoreRoot 'files') -TemplateFileRoot (Join-Path $restoreRoot 'document-templates') -LegalCatalogFile $liveCatalog
 Wait-Http 'https://127.0.0.1:5452/v2/identity/me' '401'
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5326';$env:ENF_PENALTY_SOURCE_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_PENALTY_RESTORE_ORIGINALS=$penaltyRoot
 $env:ENF_PENALTY_TARGET_INVENTORY=Join-Path $evidence 'target-before-browser.json';Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoveryTargetDm8IT' "$RunId-target-before"
 $env:VITE_ENFORCEMENT_PENALTY_CLIENT_ID=$env:ENF_PENALTY_CLIENT_ID;& (Join-Path $workspace 'scripts/start-enforcement-vite.ps1') -Port 5179 -BackendPort 5452
 Wait-Http 'https://127.0.0.1:5179/app/enforcement/penalty' '200'
 $selected=@($inventory.records|Where-Object {$_.row.RESOURCE_ID -eq $env:ENF_PENALTY_RULE_ID -and $_.row.REVISION -eq 1});if($selected.Count -ne 1){throw 'Actual browser seed absent'}
 $env:ENF_PENALTY_RECOVERY_ORIGINAL=Join-Path $penaltyRoot $selected[0].row.ARTIFACT_KEY;$env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser';$env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-a.credential.xml'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-recovery-product.mjs') *> (Join-Path $evidence 'browser-execution.log');if($LASTEXITCODE -ne 0){throw 'Actual restored browser failed'}
 $env:ENF_PENALTY_TARGET_INVENTORY=Join-Path $evidence 'target-after-browser.json';Actual 'cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoveryTargetDm8IT' "$RunId-target-after"
 [ordered]@{runId=$RunId;scope='REAL_FULL_PENALTY_DOMAIN_PHYSICAL_RESTORE_PARTIAL_PRODUCT_NOT_FINAL_ACCEPTANCE';sourceVolume='codex-enf-i-business-data';targetVolume=$volume;sourceDb='jdbc:dm://127.0.0.1:5311';targetDb='jdbc:dm://127.0.0.1:5326';recordCount=$inventory.records.Count;physicalDbShaExact=$true;allDomainOriginalShaExact=$true;browserPassed=$true;success=$true;businessAcceptance='PENDING';remaining='ACTIVE rule and suggestion complete lifecycle, current revocation recovery and final review'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'Actual cold physical DM8 + all penalty originals + restored HTTPS browser + target before/after reconciliation passed; final acceptance pending'
}catch{
 [ordered]@{runId=$RunId;status='FAILED';scope='ACTUAL_PHYSICAL_RECOVERY_ATTEMPT_NOT_ACCEPTED';message=$_.Exception.Message;at=(Get-Date).ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'failure.json') -Encoding utf8
 if(Test-Path -LiteralPath (Join-Path $vault 'server-5452-out.log')){Copy-Item -LiteralPath (Join-Path $vault 'server-5452-out.log') -Destination (Join-Path $evidence 'runtime-startup.log')}
 throw
}finally{
 if($sourceStopped){& docker start codex-enf-i-business|Out-Null}
 foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_ACCEPTANCE_AUTHORITY_PASSWORD','ENF_FOLLOWUP_PASSWORD','ENFORCEMENT_V2_PENALTY_CURRENT_DB_PASSWORD','SYSDBA_PWD','SYSAUDITOR_PWD','SYSSSO_PWD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
}
