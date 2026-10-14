param([Parameter(Mandatory=$true)][ValidateSet('preflight-source','preflight-target','initialize','initialize-continued','verify-restart','verify-restored','protected-source-final','protected-target-final')][string]$Phase)
$ErrorActionPreference='Stop';$evidence=Split-Path -Parent $PSCommandPath;$repo=Split-Path -Parent(Split-Path -Parent(Split-Path -Parent $evidence));$root=Join-Path $env:LOCALAPPDATA 'AgentPlatform/single-db-enforcement-persistent-20261007-01';$build=Join-Path $evidence 'isolated-build-03'
foreach($i in (Get-Content (Join-Path $build 'frozen-successful-source-sha.json') -Raw|ConvertFrom-Json)){if((Get-FileHash -LiteralPath $i.file).Hash.ToLowerInvariant() -ne $i.sha256){throw 'Current source changed since successful compile; stop rather than run stale code'}}
$source=$Phase -in @('preflight-source','initialize','initialize-continued','verify-restart','protected-source-final');$id=if($source){'a2df7cece3db3f81dbaeae5d72564092a0f24b975a7afdfba3fb2751035f3233'}else{'e318769a540a04b8406eeecf93f47615f1133ef8b035d30cd00bf984635e3b45'};$port=if($source){5493}else{5491}
$i=(& docker inspect $id|ConvertFrom-Json)[0];if(-not $i.State.Running -or $i.Id -ne $id -or $i.HostConfig.Privileged -or $i.HostConfig.PortBindings.'5236/tcp'[0].HostPort -ne [string]$port -or $i.HostConfig.PortBindings.'5236/tcp'[0].HostIp -ne '127.0.0.1'){throw 'Exact running user-authorized resource required'}
$parent=Import-Clixml (Join-Path $repo 'docs/evidence/single-database-persistent-20261007/.private-vault/managed-secrets-round2.dpapi.xml')
$secureAdmin=if($source){$parent['SYSDBA']}else{Import-Clixml (Join-Path $env:LOCALAPPDATA 'AgentPlatform/single-db-engineering-20261007-01/SYSDBA_PWD.credential.xml')}
function Plain($secure){if($secure -is [string]){$secure=ConvertTo-SecureString $secure};$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}}
$values=@{ENFP_URL="jdbc:dm://127.0.0.1:$port";ENFP_ADMIN_PASSWORD=(Plain $secureAdmin);ENFP_WRAPPING_KEY=(Plain (Import-Clixml (Join-Path $root 'vault/WRAPPING_KEY.credential.xml')))}
foreach($u in @('ENFPERS20261007_BIZ','ENFPERS20261007_AUTH','ENFPERS20261007_BIZ_SVC','ENFPERS20261007_AUTH_SVC')){$values['ENFP_'+$u+'_PASSWORD']=Plain (Import-Clixml (Join-Path $root "vault/$u.credential.xml"))}
$restore=@{};foreach($k in $values.Keys){$restore[$k]=[Environment]::GetEnvironmentVariable($k,'Process')};$files=if($Phase -eq 'verify-restored'){Join-Path $root 'restored-files-owner02'}else{Join-Path $root 'source-files'}
try{
 foreach($k in $values.Keys){[Environment]::SetEnvironmentVariable($k,$values[$k],'Process')};$cp=(Get-Content (Join-Path $build 'classpath.txt') -Raw).Trim()
 $readyArgs=Join-Path $evidence ($Phase+'-ready.args');@('-Xmx128m','-cp',('"'+$cp.Replace('\','/')+'"'),'EnfOwnedReady')|Set-Content $readyArgs -Encoding utf8
 $readyOutput=& D:/software/jdk-21/bin/java.exe ('@'+$readyArgs) 2>&1;$readyCode=$LASTEXITCODE;if($readyCode -ne 0){throw 'Actual managed JDBC readiness failed'}
 $argsFile=Join-Path $evidence ($Phase+'-java.args');@('-Xmx256m','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',('"'+$cp.Replace('\','/')+'"'),'cn.xinji.agentplatform.enforcement.documenttemplate.EnfTemplatePersistentHarness',$Phase,('"'+$repo.Replace('\','/')+'"'),('"'+$evidence.Replace('\','/')+'"'),('"'+$files.Replace('\','/')+'"'))|Set-Content $argsFile -Encoding utf8
 $output=& D:/software/jdk-21/bin/java.exe ('@'+$argsFile) 2>&1;$code=$LASTEXITCODE;$text=($output|ForEach-Object{[string]$_})-join [Environment]::NewLine
 foreach($k in $values.Keys){if($k -match 'PASSWORD|KEY'){$text=$text.Replace($values[$k],'[REDACTED]')}}
 $text|Set-Content (Join-Path $evidence ($Phase+'-java.log')) -Encoding utf8
 [ordered]@{phase=$Phase;exitCode=$code;command=@('D:/software/jdk-21/bin/java.exe','@'+$argsFile);endpoint=$values.ENFP_URL;containerId=$id;sharedTargetWrites=0;no5492Operations=$true;credentialsRecorded=$false}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $evidence ($Phase+'-execution.json'))
 Write-Output $text;if($code -ne 0){throw ('Actual phase failed: '+$Phase+'; preserve new schema and failure original, no cleanup')}
}finally{foreach($k in $restore.Keys){[Environment]::SetEnvironmentVariable($k,$restore[$k],'Process')}}


