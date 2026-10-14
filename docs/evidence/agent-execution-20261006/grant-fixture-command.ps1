$ErrorActionPreference='Stop'
$root=(Resolve-Path '.').Path
$evidence=Join-Path $root 'docs/evidence/agent-execution-20261006'
$containers=@();$settings=@{}
foreach($pair in @(@('rbc-run-id.txt','BUSINESS',5381),@('jobpack-run-id.txt','AUTHORITY',5382))) {
 $run=(Get-Content -LiteralPath (Join-Path $evidence $pair[0]) -Raw).Trim()
 $manifest=Get-Content -LiteralPath (Join-Path $root "docs/evidence/enforcement-document-draft-20261002/$run/isolated-resource-manifest.json") -Raw|ConvertFrom-Json
 $container=(docker inspect $manifest.container|ConvertFrom-Json)[0]
 if($LASTEXITCODE -ne 0 -or $container.Id -ne $manifest.containerId -or $container.Config.Labels.'agent-platform.run-id' -ne $run -or $container.Config.Labels.'agent-platform.role' -ne 'enforcement-test' -or -not $container.State.Running){throw 'Owned running container mismatch'}
 $binding=@($container.HostConfig.PortBindings.'5236/tcp')
 if($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or [int]$binding[0].HostPort -ne $pair[2]){throw 'Exact isolated port mismatch'}
 $vol=docker volume inspect $manifest.volume|ConvertFrom-Json
 if($vol[0].Labels.'agent-platform.run-id' -ne $run -or @($container.Mounts|Where-Object {$_.Name -eq $manifest.volume}).Count -ne 1){throw 'Owned independent volume mismatch'}
 $prefix='ENF_ACCEPTANCE_'+$pair[1]
 $settings[$prefix+'_URL']='jdbc:dm://127.0.0.1:'+$pair[2]
 $settings[$prefix+'_USER']='ENFFIX_'+[Guid]::NewGuid().ToString('N').Substring(0,12).ToUpperInvariant()
 $settings[$prefix+'_PASSWORD']='Fixture9!'+[Guid]::NewGuid().ToString('N')
 $settings[$prefix+'_ADMIN']=($container.Config.Env|Where-Object {$_ -like 'SYSDBA_PWD=*'}).Substring(11)
 $containers += [ordered]@{containerId=$container.Id;volume=$manifest.volume;port=$pair[2];schema=$settings[$prefix+'_USER'];role=$pair[1]}
}
$settings.ENF_ACCEPTANCE_AUTHORITY_SCHEMA=$settings.ENF_ACCEPTANCE_AUTHORITY_USER
$settings.ENF_ACCEPTANCE_ISSUER='https://isolated-fixture.invalid'
$settings.ENF_ACCEPTANCE_SUBJECT='isolated-fixture-member'
$settings.ENF_ACCEPTANCE_ORGANIZATION='ISOLATED-FUNCTIONAL-VALIDATION'
$settings.ENF_ACCEPTANCE_TEMPLATE_ID='isolated-template'
$settings.ENF_ACCEPTANCE_REVOKE_TEMPLATE_DOWNLOAD='false'
if(Test-Path -LiteralPath (Join-Path $evidence 'grant-fixture-ownership.json')){throw 'Never overwrite a prior run'}
$containers|ConvertTo-Json|Set-Content (Join-Path $evidence 'grant-fixture-ownership.json')
$prior=@{};foreach($name in $settings.Keys){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
$oldJava=$env:JAVA_HOME;$oldPath=$env:PATH
try {
 $env:JAVA_HOME='D:/software/jdk-21';$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $cp=(Get-Content (Join-Path $env:TEMP 'enf-runtime-classpath.txt') -Raw).Trim()
 & "$env:JAVA_HOME/bin/javac.exe" -cp $cp -d $evidence (Join-Path $evidence 'IsolatedGrantFixturePreparation.java') *> (Join-Path $evidence 'grant-fixture-compile.log')
 if($LASTEXITCODE -ne 0){throw 'Compile failed'}
 & "$env:JAVA_HOME/bin/java.exe" -cp "$evidence;$cp" IsolatedGrantFixturePreparation *> (Join-Path $evidence 'grant-fixture-prepare.log')
 if($LASTEXITCODE -ne 0){throw 'Preparation failed; preserve evidence and schemas'}
 & mvn -f backend/pom.xml -pl apps/platform-api -am '-Dtest=EnforcementFollowupAcceptanceGrantFixtureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsurefire.reportNameSuffix=AGENT_GRANT_ISOLATED_20261006' test *> (Join-Path $evidence 'grant-fixture-test.log')
 $code=$LASTEXITCODE
 [ordered]@{mavenExit=$code;finishedAt=[DateTimeOffset]::UtcNow.ToString('o');scope='Two fresh isolated schemas, fixture grants only, not product authorization or acceptance';originalBusinessOrAuthorityAccess=$false}|ConvertTo-Json|Set-Content (Join-Path $evidence 'grant-fixture-result.json')
 Write-Output "GRANT_FIXTURE=$code"
}finally {foreach($name in $settings.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')};$env:JAVA_HOME=$oldJava;$env:PATH=$oldPath}
