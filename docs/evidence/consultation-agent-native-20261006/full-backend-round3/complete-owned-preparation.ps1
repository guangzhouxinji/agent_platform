$ErrorActionPreference='Stop';$out='docs/evidence/consultation-agent-native-20261006/full-backend-round3';$run=(Get-Content "$out/full-run-id.txt" -Raw).Trim();$evidence="docs/evidence/enforcement-document-draft-20261002/$run";$m=Get-Content "$evidence/isolated-resource-manifest.json" -Raw|ConvertFrom-Json;$c=(docker inspect $m.containerId|ConvertFrom-Json)[0]
if($c.Id -ne $m.containerId -or $c.Config.Labels.'agent-platform.run-id' -ne $run -or $m.port -ne 5393 -or -not $c.State.Running){throw 'Exact owned unused fixture required'}
if(Test-Path "$evidence/isolated-schema-preparation.json"){throw 'No repeat initialization'}
$credentialFile=Join-Path $env:LOCALAPPDATA "AgentPlatform/isolated-verification/$run.credential.xml";if(Test-Path $credentialFile){throw 'No credential overwrite'}
try{
$env:SYSDBA_PWD=($c.Config.Env|Where-Object {$_ -like 'SYSDBA_PWD=*'}).Substring(11);$env:JOB_PACK_DM8_URL='jdbc:dm://127.0.0.1:5393';$env:JOB_PACK_DM8_USER=$m.schema;$env:JOB_PACK_DM8_PASSWORD='EnfI9!'+[Guid]::NewGuid().ToString('N');$env:ENF_TEST_RUN_ID=$run
$build=Join-Path $env:TEMP "enf-isolated-build-$run/backend";$cp=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim();$cp="$build/enforcement/target/classes;$build/apps/platform-api/target/classes;$build/apps/platform-api/target/test-classes;$cp"
& 'D:/software/jdk-21/bin/javac.exe' -encoding UTF-8 -cp $cp -d $evidence scripts/java/EnforcementIsolatedSchemaPreparation88.java *> "$evidence/schema-compile-retry.log";if($LASTEXITCODE -ne 0){throw 'Compile failed'}
& 'D:/software/jdk-21/bin/java.exe' -cp "$evidence;$cp" EnforcementIsolatedSchemaPreparation88 "$evidence/isolated-schema-preparation.json" *> "$evidence/schema-initialization-retry.log";if($LASTEXITCODE -ne 0){throw 'Init failed'}
[PSCredential]::new($m.schema,(ConvertTo-SecureString $env:JOB_PACK_DM8_PASSWORD -AsPlainText -Force))|Export-Clixml -LiteralPath $credentialFile
[ordered]@{runId=$run;testExecuted=$false;credentialFile=$credentialFile;scope='OWNED_ISOLATED_PREPARATION_ONLY'}|ConvertTo-Json|Set-Content "$evidence/isolated-prepared.json"
}finally{foreach($n in @('SYSDBA_PWD','JOB_PACK_DM8_URL','JOB_PACK_DM8_USER','JOB_PACK_DM8_PASSWORD','ENF_TEST_RUN_ID')){[Environment]::SetEnvironmentVariable($n,$null,'Process')}}
