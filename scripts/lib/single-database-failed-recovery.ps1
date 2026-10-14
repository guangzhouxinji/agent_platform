# Reversible recovery of the exact retained, non-running failed consultation25; never restart it.
function Assert-FinalRecoveryNoWriter($Config,$Domain) {
    $active=@(Invoke-FinalDocker @('ps','--filter','label=task=IMP-SINGLE-DB-FINAL-BASELINE-01','--filter',"label=agent-platform.domain=$Domain",'--no-trunc','--format','{{.ID}}'))
    if(@($active|Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_)}).Count){throw 'FINAL_RECOVERY_ACTIVE_WRITER_REFUSED'}
}
function Invoke-FinalFailedApplicationRecovery($Config,$Recovery) {
    if($Recovery.format -cne 'SINGLE_DATABASE_FAILED_APPLICATION_RECOVERY/v1' -or
       $Recovery.retainedContainerId -cnotmatch '^[a-f0-9]{64}$' -or
       $Recovery.legacyLauncher.sha256 -cne '273e6b036d107ec721281e8c86eb7107715f8172571877042f7898263a0e2dfb'){throw 'FINAL_FAILED_RECOVERY_EVIDENCE_REQUIRED'}
    $plan=Get-FinalDeploymentPlan $Config
    if(@($plan.applications).Count -ne 1 -or $plan.applications[0].input.domain -cne 'consultation'){throw 'FINAL_SINGLE_CONSULTATION_RECOVERY_REQUIRED'}
    $app=$plan.applications[0];$a=$app.input;$id=$Recovery.retainedContainerId
    if($a.jar.sha256 -cne '69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d' -or $Recovery.retainedContainerName -cne $a.name){throw 'FINAL_FIXED25_RECOVERY_REQUIRED'}
    Assert-FinalFile $Recovery.previousConfiguration;Assert-FinalFile $Recovery.legacyLauncher;Assert-FinalFile $Recovery.userScope
    $previous=Read-FinalDeploymentJson $Recovery.previousConfiguration.path
    if($previous.manifestSha256 -cne $Config.manifestSha256 -or
       ($previous.applications|ConvertTo-Json -Depth 30 -Compress) -cne ($Config.applications|ConvertTo-Json -Depth 30 -Compress)){throw 'FINAL_RECOVERY_ORIGINAL_CONFIGURATION_DRIFT'}
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Recovery.legacyLauncher.path,[ref]$tokens,[ref]$errors)
    $factory=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-FinalApplicationLaunchCommand'},$true)
    if($errors.Count -or -not $factory){throw 'FINAL_RETAINED_FACTORY_REQUIRED'}
    $app.launchCommand=& $factory.Body.GetScriptBlock()
    $preserved=$a.name+'-failed-'+$id.Substring(0,12);$renamed=$false;$locked=$false
    $mutex=[Threading.Mutex]::new($false,('Local\AgentPlatform-Final-Recovery-'+$Config.manifestSha256+'-consultation'))
    try {
        if(-not $mutex.WaitOne(0)){throw 'FINAL_RECOVERY_ALREADY_OWNED'};$locked=$true
        Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan
        $actual=@(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable)[0]
        if($actual.Image -cne $Config.image){throw 'FINAL_RETAINED_IMAGE_DRIFT'}
        Assert-FinalCreatedApplication $id $app $Config -RetainedFailedInstance
        $expected=@{}+$app.values
        try {
            foreach($key in $app.secretRefs.Keys){$expected[$key]=Read-FinalManagedSecret $app.secretRefs[$key]}
            $observed=@{};foreach($entry in $actual.Config.Env){$pair=$entry.Split('=',2);if($pair.Count -eq 2){if($observed.Contains($pair[0])){throw 'FINAL_RETAINED_ENVIRONMENT_DRIFT'};$observed[$pair[0]]=$pair[1]}}
            foreach($key in $expected.Keys){if(-not $observed.Contains($key) -or $observed[$key] -cne [string]$expected[$key]){throw 'FINAL_RETAINED_ENVIRONMENT_DRIFT'}}
            foreach($key in $observed.Keys){if($key -clike 'SINGLE_DB_*' -and -not $expected.Contains($key)){throw 'FINAL_RETAINED_ENVIRONMENT_DRIFT'}}
        }finally{$expected.Clear();$observed=$null}
        $names=@(Invoke-FinalDocker @('ps','-a','--format','{{.Names}}'))
        if($preserved -cin $names -or $a.name -cnotin $names){throw 'FINAL_RECOVERY_NAME_DRIFT'}
        Assert-FinalRecoveryNoWriter $Config $a.domain
        Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan
        Assert-FinalCreatedApplication $id $app $Config -RetainedFailedInstance
        $null=Assert-FinalApproval $Config
        Invoke-FinalDocker @('rename',$id,$preserved)|Out-Null;$renamed=$true
        Assert-FinalRecoveryNoWriter $Config $a.domain
        $result=Invoke-FinalApplications $Config
        return @{scope='MANAGED_FAILED_REPLACEMENT_START_ONLY_NOT_HTTP';retainedContainerId=$id;retainedName=$preserved;originalName=$a.name;replacement=$result;oldInstanceRestarted=$false;bootstrapOrGrantReplayed=$false}
    }catch{
        # Reverse only the rename when no replacement exists; never delete a partial/new instance.
        if($renamed){
            try {
                $names=@(Invoke-FinalDocker @('ps','-a','--format','{{.Names}}'))
                $old=@(Invoke-FinalDocker @('inspect',$id)|ConvertFrom-Json -AsHashtable)[0]
                if($a.name -cnotin $names -and $old.Id -ceq $id -and $old.Name -ceq ('/'+$preserved) -and
                   $old.State.Status -ceq 'exited' -and $old.State.Running -eq $false -and $old.State.Pid -eq 0){Invoke-FinalDocker @('rename',$id,$a.name)|Out-Null}
            }catch{}
        }
        throw 'FINAL_FAILED_RECOVERY_REFUSED_OR_PARTIAL_PRESERVED'
    }finally{if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
function Initialize-FinalConsultationEvidenceDirectory($Config) {
    $plan=Get-FinalDeploymentPlan $Config
    if(@($plan.applications).Count -ne 1){throw 'FINAL_SINGLE_FILES_PREPARATION_REQUIRED'}
    $app=$plan.applications[0];$a=$app.input
    if($a.domain -cne 'consultation' -or $a.files.volume -cne 'agent-platform-single-final-primary-data-20261009' -or
       $a.files.subpath -cne 'files/consultation' -or $a.files.target -cne '/product/files/consultation' -or
       $app.values.SINGLE_DB_CONSULTATION_PRODUCT_SOURCE_EVIDENCE_ROOT -cne '/product/files/consultation/source-evidence'){throw 'FINAL_EXACT_EVIDENCE_DIRECTORY_REQUIRED'}
    Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan;Assert-FinalRecoveryNoWriter $Config $a.domain
    $init=Read-FinalDeploymentJson $Config.initializationReceipt.path
    $proof=Read-FinalDeploymentJson $init.storage.files.proof.path;$live=Read-FinalDeploymentJson $proof.probeOutput.path
    $root='/mnt/docker-desktop-disk/data/docker/volumes/agent-platform-single-final-primary-data-20261009/_data/files/consultation'
    $parent=& wsl -d docker-desktop --exec sh -c "if test -L $root; then exit 78; fi; stat -c %d:%u:%g:%a:%F $root" 2>&1
    if($LASTEXITCODE -ne 0 -or ($parent -join '') -cne "$($live.mountedFilesystemDevice):10001:10001:700:directory"){throw 'FINAL_PRIVATE_FILES_PARENT_DRIFT'}
    $target=$root+'/source-evidence';& wsl -d docker-desktop --exec test -e $target;$exists=$LASTEXITCODE -eq 0
    if(-not $exists){
        $null=Assert-FinalApproval $Config
        $output=& wsl -d docker-desktop --exec sh -c "cd $root && exec setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all --inh-caps=-all --ambient-caps=-all mkdir -m 700 -- source-evidence" 2>&1
        if($LASTEXITCODE -ne 0){throw 'FINAL_NORMAL_UID_EMPTY_DIRECTORY_CREATION_FAILED'}
    }
    $child=& wsl -d docker-desktop --exec sh -c "if test -L $target; then exit 78; fi; stat -c %d:%u:%g:%a:%F $target" 2>&1
    if($LASTEXITCODE -ne 0 -or ($child -join '') -cne "$($live.mountedFilesystemDevice):10001:10001:700:directory"){throw 'FINAL_PRIVATE_EVIDENCE_DIRECTORY_DRIFT'}
    $entries=@(& wsl -d docker-desktop --exec find $target -mindepth 1 -maxdepth 1 -print 2>&1)
    if($LASTEXITCODE -ne 0){throw 'FINAL_PRIVATE_EVIDENCE_DIRECTORY_UNAVAILABLE'}
    return @{scope='EXACT_APPROVED_EMPTY_FILES_SUBDIRECTORY_NOT_SOURCE_AUTHORIZATION';created=(-not $exists);path='/product/files/consultation/source-evidence';uid=10001;gid=10001;mode='0700';filesystemDevice=$live.mountedFilesystemDevice;entries=$entries.Count;sourceOrGrantCreated=$false;existingPermissionsChanged=$false}
}
function Assert-FinalCurrentFailedState($Actual,$Config) {
    if($Actual.State.Status -cne 'exited' -or $Actual.State.Running -ne $false -or $Actual.State.Pid -ne 0 -or
       $Actual.State.ExitCode -eq 0 -or $Actual.Image -cne $Config.image){throw 'FINAL_ONLY_CURRENT_FAILED_INSTANCE_REQUIRED'}
}
function Resume-FinalFailedApplication($Config,[string]$Id) {
    if($Id -cnotmatch '^[a-f0-9]{64}$'){throw 'FINAL_EXACT_FAILED_CONTAINER_REQUIRED'}
    $plan=Get-FinalDeploymentPlan $Config
    if(@($plan.applications).Count -ne 1 -or $plan.applications[0].input.domain -cne 'consultation' -or
       $plan.applications[0].input.jar.sha256 -cne '69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d'){throw 'FINAL_SINGLE_FIXED25_FAILED_RECOVERY_REQUIRED'}
    $app=$plan.applications[0];$app.launchCommand=Get-FinalApplicationLaunchCommand $app;$locked=$false
    $mutex=[Threading.Mutex]::new($false,('Local\AgentPlatform-Final-Recovery-'+$Config.manifestSha256+'-consultation'))
    $expected=@{}+$app.values;$observed=@{}
    try {
        if(-not $mutex.WaitOne(0)){throw 'FINAL_RECOVERY_ALREADY_OWNED'};$locked=$true
        Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan;Assert-FinalRecoveryNoWriter $Config 'consultation'
        $actual=@(Invoke-FinalDocker @('inspect',$Id)|ConvertFrom-Json -AsHashtable)[0];Assert-FinalCurrentFailedState $actual $Config
        Assert-FinalCreatedApplication $Id $app $Config
        foreach($key in $app.secretRefs.Keys){$expected[$key]=Read-FinalManagedSecret $app.secretRefs[$key]}
        foreach($entry in $actual.Config.Env){$pair=$entry.Split('=',2);if($pair.Count -eq 2){if($observed.Contains($pair[0])){throw 'FINAL_CURRENT_FAILED_ENVIRONMENT_DRIFT'};$observed[$pair[0]]=$pair[1]}}
        foreach($key in $expected.Keys){if(-not $observed.Contains($key) -or $observed[$key] -cne [string]$expected[$key]){throw 'FINAL_CURRENT_FAILED_ENVIRONMENT_DRIFT'}}
        foreach($key in $observed.Keys){if($key -clike 'SINGLE_DB_*' -and -not $expected.Contains($key)){throw 'FINAL_CURRENT_FAILED_ENVIRONMENT_DRIFT'}}
        $null=Assert-FinalApproval $Config;Assert-FinalExecutionInputs $Config;Assert-FinalOwnedRuntime $Config $plan;Assert-FinalRecoveryNoWriter $Config 'consultation'
        $actual=@(Invoke-FinalDocker @('inspect',$Id)|ConvertFrom-Json -AsHashtable)[0];Assert-FinalCurrentFailedState $actual $Config
        Assert-FinalCreatedApplication $Id $app $Config
        Invoke-FinalDocker @('start',$Id)|Out-Null;Assert-FinalCreatedApplication $Id $app $Config
        return @{scope='SAME_CURRENT_FAILED_INSTANCE_START_ONLY_NOT_HTTP';containerIds=@($Id);newContainersCreated=0;businessVerified=$false;modelAdmissionGranted=$false}
    }finally{$expected.Clear();$observed.Clear();$actual=$null;if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
}