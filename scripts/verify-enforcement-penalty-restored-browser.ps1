param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$PhysicalRunId,[string]$RuleRunId='ENF15-PRODUCT-20261001-119')
$ErrorActionPreference='Stop'
foreach($id in @($RunId,$PhysicalRunId,$RuleRunId)){if($id -notmatch '^[A-Za-z0-9-]{1,75}$'){throw 'Safe evidence ID required'}}
$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$vault=Join-Path $env:LOCALAPPDATA 'AgentPlatform/enforcement-product'
$physical=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$PhysicalRunId";$restoreRoot=Join-Path $env:TEMP "$PhysicalRunId-physical-product"
$evidence=Join-Path $workspace "docs/evidence/enforcement-followup-final-20260930/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Fresh supplementary evidence required'};New-Item -ItemType Directory -Path $evidence|Out-Null
$before=Get-Content -LiteralPath (Join-Path $physical 'target-before-browser.json') -Raw|ConvertFrom-Json
if(-not $before.sourceTargetExact -or $before.writeCount -ne 0){throw 'Previously actual target byte/ledger reconciliation required'}
$a=Get-Content (Join-Path $physical 'source-physical-db-sha.txt')|Sort-Object;$b=Get-Content (Join-Path $physical 'target-physical-db-sha.txt')|Sort-Object
if(-not $a -or (Compare-Object $a $b)){throw 'Actual cold copy SHA evidence missing/different'}
$container='codex-enf-penalty-'+$PhysicalRunId.ToLowerInvariant();$inspection=& docker inspect $container|ConvertFrom-Json
if(-not $inspection[0].State.Running -or $inspection[0].Mounts.Name -notcontains $container){throw 'Actual independent restored volume unavailable'}
function Secret([string]$name){$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((Import-Clixml (Join-Path $vault $name)));try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}}
function Actual([string]$suffix){
 $test='cn.xinji.agentplatform.enforcement.penalty.PenaltyRecoveryTargetDm8IT'
 & mvn -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dsurefire.reportNameSuffix=$suffix" test *> (Join-Path $evidence "$suffix.log")
 $exit=$LASTEXITCODE;$xml=Join-Path $env:ENF_MAVEN_BUILD_ROOT "backend/apps/platform-api/target/surefire-reports/TEST-$test-$suffix.xml"
 if(Test-Path -LiteralPath $xml){Copy-Item -LiteralPath $xml -Destination $evidence};if($exit -ne 0 -or -not(Test-Path -LiteralPath $xml)){throw 'Actual target reconciliation failed'}
 [xml]$report=Get-Content -LiteralPath $xml -Raw;if([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0){throw 'Fresh actual target 1/0/0/0 required'}
}
try {
 & (Join-Path $workspace 'scripts/prepare-enforcement-isolated-build.ps1') -RunId "$RunId-build"
 $env:ENF_ACCEPTANCE_BUSINESS_URL='jdbc:dm://127.0.0.1:5326';$env:ENF_ACCEPTANCE_BUSINESS_USER='ENFACC_BIZ';$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD=Secret 'business-schema.credential.xml';$env:ENF_ACCEPTANCE_ISSUER='https://127.0.0.1:5444';$env:ENF_PENALTY_SOURCE_URL='jdbc:dm://127.0.0.1:5311';$env:ENF_PENALTY_ORIGINALS_BASE=$vault;$env:ENF_PENALTY_RESTORE_ORIGINALS=Join-Path $restoreRoot 'penalty';$env:ENF_PENALTY_SOURCE_INVENTORY=Join-Path $physical 'source-inventory.json'
 # Preparation snapshots source only. Execute the actual pre-browser target I
 # to compile this exclusive build before trying to launch its product classes.
 $env:ENF_PENALTY_TARGET_INVENTORY=Join-Path $evidence 'target-before-browser.json';Actual "$RunId-before"
 $runtimeId=[int](Get-Content (Join-Path $vault 'server-5452.pid'));$process=Get-CimInstance Win32_Process -Filter "ProcessId=$runtimeId"
 $listener=Get-NetTCPConnection -State Listen -LocalPort 5452 -ErrorAction SilentlyContinue
 if($process){
  if($listener.OwningProcess -ne $runtimeId -or -not $process.CommandLine.Contains("enf-isolated-build-$PhysicalRunId-build") -or -not $process.CommandLine.Contains('--product-enforcement')){throw 'Restored runtime owner mismatch'}
  Stop-Process -Id $runtimeId;Wait-Process -Id $runtimeId -ErrorAction SilentlyContinue
 }elseif($listener){throw 'Unowned service on restore port'}
 $env:ENFORCEMENT_V2_PENALTY_FILE_ROOT=$env:ENF_PENALTY_RESTORE_ORIGINALS;$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_URL=$env:ENF_PENALTY_SOURCE_URL;$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_USER=$env:ENF_ACCEPTANCE_BUSINESS_USER;$env:ENFORCEMENT_V2_PENALTY_CURRENT_DB_PASSWORD=$env:ENF_ACCEPTANCE_BUSINESS_PASSWORD;$env:ENFORCEMENT_V2_PENALTY_CURRENT_FILE_ROOT=Join-Path $vault "penalty-product/$RuleRunId"
 $liveCatalog=Join-Path $workspace 'data/enforcement/legal-reference-v2-product-input-v1/catalog.json';$catalog=Get-Content -LiteralPath $liveCatalog -Raw|ConvertFrom-Json
 $original=if([IO.Path]::IsPathRooted($catalog.provenance.snapshot)){$catalog.provenance.snapshot}else{Join-Path $workspace $catalog.provenance.snapshot}
 if((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant() -ne $catalog.provenance.snapshotSha256){throw 'Current source original changed'}
 $retained=Join-Path $restoreRoot 'retained-legal-source-original.html'
 if(Test-Path -LiteralPath $retained){if((Get-FileHash -LiteralPath $retained -Algorithm SHA256).Hash.ToLowerInvariant() -ne $catalog.provenance.snapshotSha256){throw 'Retained source SHA mismatch; no overwrite'}}else{Copy-Item -LiteralPath $original -Destination $retained}
 & (Join-Path $workspace 'scripts/start-enforcement-acceptance.ps1') -HttpsPort 5452 -BusinessPort 5326 -IdentityIssuer $env:ENF_ACCEPTANCE_ISSUER -BuildRoot $env:ENF_MAVEN_BUILD_ROOT -MaterialFileRoot (Join-Path $restoreRoot 'files') -TemplateFileRoot (Join-Path $restoreRoot 'document-templates') -LegalCatalogFile $liveCatalog
 $ready=$false;for($n=0;$n -lt 60;$n++){$http=& curl.exe --silent --insecure --max-time 2 --output NUL --write-out '%{http_code}' https://127.0.0.1:5452/v2/identity/me;if($http -eq '401'){$ready=$true;break};Start-Sleep -Milliseconds 500};if(-not $ready){throw 'Corrected source-adapter restored runtime unavailable'}
 $env:ENF_PENALTY_RUN_ID=$RunId;$env:ENF_PENALTY_RULE_ID="ENF15-$RuleRunId";$env:ENF_PENALTY_BROWSER_EVIDENCE=Join-Path $evidence 'browser'
 $source=Get-Content -LiteralPath $env:ENF_PENALTY_SOURCE_INVENTORY -Raw|ConvertFrom-Json;$selected=@($source.records|Where-Object {$_.row.RESOURCE_ID -eq $env:ENF_PENALTY_RULE_ID -and $_.row.REVISION -eq 1});if($selected.Count -ne 1){throw 'Actual seed missing'};$env:ENF_PENALTY_RECOVERY_ORIGINAL=Join-Path $env:ENF_PENALTY_RESTORE_ORIGINALS $selected[0].row.ARTIFACT_KEY
 $identity=Get-Content (Join-Path $vault 'functional-a.identity.json') -Raw|ConvertFrom-Json;if($identity.testNature -ne 'FUNCTIONAL_VALIDATION'){throw 'Explicit test identity required'};$env:ENF_FOLLOWUP_LOGIN=$identity.loginName;$env:ENF_FOLLOWUP_PASSWORD=Secret 'functional-a.credential.xml'
 & node (Join-Path $workspace 'frontend/e2e/enforcement-penalty-recovery-product.mjs') *> (Join-Path $evidence 'browser-execution.log');if($LASTEXITCODE -ne 0){throw 'Actual browser retry failed; no success claim'}
 $env:ENF_PENALTY_TARGET_INVENTORY=Join-Path $evidence 'target-after-browser.json';Actual "$RunId-after"
 [ordered]@{runId=$RunId;physicalCopyRun=$PhysicalRunId;scope='ACTUAL_SUPPLEMENTARY_RESTORED_BROWSER_AND_FRESH_TARGET_I_NOT_NEW_PHYSICAL_COPY_NOT_FINAL_ACCEPTANCE';tests=2;failures=0;errors=0;skipped=0;restoredRecords=$before.originalCount;retainedSourceSha=$catalog.provenance.snapshotSha256;currentSourceCatalog=$liveCatalog;browserPassed=$true;missingOriginal=503;corruptSha=503;repairedTrial=200;businessAcceptance='PENDING'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
 Write-Output 'Supplementary actual restored browser and fresh target before/after I passed; original physical-run failure remains preserved'
}catch{[ordered]@{runId=$RunId;status='FAILED';physicalCopyRun=$PhysicalRunId;message=$_.Exception.Message}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'failure.json') -Encoding utf8;foreach($name in @('out','error')){$log=Join-Path $vault "server-5452-$name.log";if(Test-Path -LiteralPath $log){Copy-Item -LiteralPath $log -Destination (Join-Path $evidence "runtime-$name.log")}};throw}
finally{foreach($name in @('ENF_ACCEPTANCE_BUSINESS_PASSWORD','ENF_FOLLOWUP_PASSWORD','ENFORCEMENT_V2_PENALTY_CURRENT_DB_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
