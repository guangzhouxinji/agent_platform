$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot;$a=$PSScriptRoot
$r=Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json";$null=Assert-FinalApproval $r.readyApplicationFields
if(Test-Path -LiteralPath "$a/fresh-post-bootstrap44.json"){throw 'EXISTING_INVENTORY_RECONCILE_REQUIRED'}
$old=[Environment]::GetEnvironmentVariable('SINGLE_DB_ADMIN_PASSWORD','Process');$code=$null;$started=[DateTimeOffset]::Now
try{
 $ref=@{path='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/SINGLE_DB_ADMIN_PASSWORD.dpapi';sha256='ef0e6ed0881f2d5efb2a6af1446b248155d93b84060a406c4e483453e3421eb4'}
 [Environment]::SetEnvironmentVariable('SINGLE_DB_ADMIN_PASSWORD',(Read-FinalManagedSecret $ref),'Process')
 & node scripts/inventory-single-database-lifecycle.mjs "$e/primary-migration-config07.json" "$a/fresh-post-bootstrap44.json" *> "$a/fresh-post-bootstrap44.log";$code=$LASTEXITCODE
 if($code -ne 0){throw 'INVENTORY_FAILED'}
 $before=Read-FinalDeploymentJson "$a/fresh-resource44.json";$protected=@()
 foreach($p in $before.roles){$v=@(& docker inspect $p.id|ConvertFrom-Json -AsHashtable)[0];if($LASTEXITCODE -ne 0 -or -not $v.State.Running -or $v.State.Pid -ne $p.state.Pid -or $v.State.StartedAt -cne $p.state.StartedAt){throw 'PROTECTED_ROLE_DRIFT'};$protected+=@{id=$v.Id;pid=$v.State.Pid;startedAt=$v.State.StartedAt}}
 @{capturedAt=[DateTimeOffset]::Now.ToString('o');protected=$protected;unchanged=$true;modelCalls=0}|ConvertTo-Json -Depth 8|Set-Content "$a/protected-after-bootstrap44.json"
}finally{
 [Environment]::SetEnvironmentVariable('SINGLE_DB_ADMIN_PASSWORD',$old,'Process')
 @{startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');exitCode=$code;readOnly=$true}|ConvertTo-Json|Set-Content "$a/fresh-post-bootstrap44-result.json"
}
