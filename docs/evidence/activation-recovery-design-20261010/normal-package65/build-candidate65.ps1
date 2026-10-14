$ErrorActionPreference='Stop'
Set-Location 'D:\daima\xinji\agent_platform'
$worker=Get-Process -Id 37908 -ErrorAction SilentlyContinue
if($worker){Wait-Process -Id 37908 -Timeout 1200}
$other=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^(java|node)\.exe$' -and $_.CommandLine -match 'maven|mvn|vite build|tsc -b|stage-consultation-product-assets|verify-all\.mjs'})
if($other.Count){throw 'OTHER_BUILD_OR_GOVERNANCE_WORKER_PRESENT'}
$env:PYTHONIOENCODING='utf-8'
$e='docs/evidence/activation-recovery-design-20261010/normal-package65'
$env:JAVA_HOME='D:\software\jdk-21';$env:MAVEN_OPTS='-Xmx768m'
if(Test-Path -LiteralPath 'backend/apps/platform-api/target/agent-platform-consultation-activation-recovery-20261011-65.jar'){throw 'NEW_OUTPUT_ALREADY_EXISTS'}
& 'D:\software\apache-maven-3.9.16\bin\mvn.cmd' -o -q -pl backend/apps/platform-api -am -Pbundle-consultation '-Dmaven.test.skip=true' '-Dplatform.api.finalName=agent-platform-consultation-activation-recovery-20261011-65' package *> "$e/maven-package65.log"
$buildCode=$LASTEXITCODE
@{at=[DateTimeOffset]::UtcNow.ToString('o');exitCode=$buildCode;testsExecuted=$false;testCompileSkipped=$true;databaseWrites=0;deployments=0}|ConvertTo-Json|Set-Content -LiteralPath "$e/maven-exit65.json" -Encoding utf8
Write-Output "MAVEN_EXIT=$buildCode"
Get-Content "$e/maven-package65.log" -Tail 18
if($buildCode -ne 0){exit $buildCode}
python "$e/verify-candidate65.py" *> "$e/verify-candidate65.log"
$checkCode=$LASTEXITCODE
Write-Output "CANDIDATE_CHECK_EXIT=$checkCode"
Get-Content "$e/verify-candidate65.log" -Tail 15
exit $checkCode
