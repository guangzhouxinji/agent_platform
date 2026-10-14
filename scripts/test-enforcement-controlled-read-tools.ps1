param([Parameter(Mandatory=$true)][string]$EvidenceRoot,
      [Parameter(Mandatory=$true)][ValidatePattern('^[A-Z0-9_-]+$')][string]$AttemptId)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSCommandPath))).Path
$evidence=(Resolve-Path -LiteralPath $EvidenceRoot).Path
if(-not $evidence.StartsWith($workspace+'\docs\evidence\',[StringComparison]::OrdinalIgnoreCase)){throw 'Owned evidence root required'}
$resultFile=Join-Path $evidence "tools-component-$AttemptId-result.json"
if(Test-Path -LiteralPath $resultFile){throw 'Prior tool evidence will not be overwritten'}
$runRoot=Join-Path $evidence ('tools-c-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot | Out-Null
. (Join-Path $PSScriptRoot 'Test-EnforcementControlledReadRelease.ps1')
$results=[Collections.Generic.List[object]]::new()
function Check([string]$name,[scriptblock]$body){
    try {& $body;$results.Add([ordered]@{name=$name;passed=$true})}
    catch {$results.Add([ordered]@{name=$name;passed=$false;error=$_.Exception.GetType().FullName})}
}
function Reject([scriptblock]$body){
    $rejected=$false
    try {& $body | Out-Null} catch {$rejected=$true}
    if(-not $rejected){throw 'Expected rejection did not occur'}
}
function Case {
    $case=Join-Path $runRoot ([Guid]::NewGuid().ToString('N'))
    $classes=Join-Path $case 'release/classes';$docs=Join-Path $case 'docs/evidence'
    New-Item -ItemType Directory -Path $classes,$docs | Out-Null
    [IO.File]::WriteAllText((Join-Path $classes 'A.class'),'TEST_ONLY_COMPONENT_BYTES_NOT_PRODUCT')
    [IO.File]::WriteAllText((Join-Path $classes 'B.class'),'TEST_ONLY_SECOND_COMPONENT_FILE')
    $issuance=Join-Path $docs 'fixture-issuance.json'
    @{scope='ENFORCEMENT_PRODUCT_RELEASE';releaseId='TEST_ONLY_RELEASE_RECORD'}|ConvertTo-Json|Set-Content -LiteralPath $issuance -Encoding utf8NoBOM
    $files=@(Get-ChildItem -LiteralPath $classes -File | ForEach-Object {@{path=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
    $manifest=@{schemaVersion=1;releaseId='TEST_ONLY_RELEASE_RECORD';artifactRoot=(Join-Path $case 'release');issuanceEvidence=@{path='docs/evidence/fixture-issuance.json';sha256=(Get-FileHash -LiteralPath $issuance -Algorithm SHA256).Hash.ToLowerInvariant()};entries=@(@{path='classes';type='directory';files=$files})}
    $file=Join-Path $case 'manifest.json';$manifest|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $file -Encoding utf8NoBOM
    return @{root=$case;classes=$classes;file=$file;manifest=$manifest;sha=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
}
function ResolveCase($case){Get-EnforcementControlledReadClasspath -ManifestFile $case.file -ManifestSha256 $case.sha -Workspace $case.root}
Check 'PowerShell files parse' {
    foreach($name in @('start-enforcement-acceptance.ps1','Test-EnforcementControlledReadRelease.ps1')){
        $tokens=$null;$errors=$null
        [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)|Out-Null
        if($errors.Count){throw 'Production deployment tool has parser errors'}
    }
}
Check 'Pinned complete fixture classes pass' {$c=Case;if((ResolveCase $c) -ne $c.classes){throw 'Classpath mismatch'}}
Check 'Wrong release SHA rejected' {$c=Case;$c.sha='0'*64;Reject {ResolveCase $c}}
Check 'Changed artifact rejected' {$c=Case;[IO.File]::WriteAllText((Join-Path $c.classes 'A.class'),'CHANGED_TEST_BYTES');Reject {ResolveCase $c}}
Check 'Additional artifact rejected' {$c=Case;[IO.File]::WriteAllText((Join-Path $c.classes 'UNKNOWN.class'),'UNRECORDED_TEST_BYTES');Reject {ResolveCase $c}}
Check 'Missing issuance evidence rejected' {$c=Case;$c.manifest.issuanceEvidence.path='docs/evidence/missing.json';$c.manifest|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $c.file -Encoding utf8NoBOM;$c.sha=(Get-FileHash -LiteralPath $c.file -Algorithm SHA256).Hash.ToLowerInvariant();Reject {ResolveCase $c}}
Check 'Aliased inventory cannot hide unverified file' {
    $c=Case;$first=$c.manifest.entries[0].files[0]
    $c.manifest.entries[0].files=@($first,@{path='nested/../'+$first.path;sha256=$first.sha256})
    $c.manifest|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $c.file -Encoding utf8NoBOM
    $c.sha=(Get-FileHash -LiteralPath $c.file -Algorithm SHA256).Hash.ToLowerInvariant();Reject {ResolveCase $c}
}
Check 'Release root escape rejected' {
    $c=Case;$c.manifest.entries[0].path='../docs';$c.manifest|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $c.file -Encoding utf8NoBOM
    $c.sha=(Get-FileHash -LiteralPath $c.file -Algorithm SHA256).Hash.ToLowerInvariant();Reject {ResolveCase $c}
}
Check 'Incomplete controlled launcher inputs reject before secret or start' {
    Reject {& (Join-Path $PSScriptRoot 'start-enforcement-acceptance.ps1') -ControlledReadPolicyFile 'TEST_ONLY_MISSING_POLICY'}
}
Check 'Restore/test ports rejected before secret or start' {
    Reject {& (Join-Path $PSScriptRoot 'start-enforcement-acceptance.ps1') -ControlledReadPolicyFile 'TEST_ONLY_MISSING_POLICY' -BusinessPort 5319}
}
Check 'Actual launcher argument expressions flatten controlled flags' {
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'start-enforcement-acceptance.ps1'),[ref]$tokens,[ref]$errors)
    $controlledRead=$true;$workspace='TEST_ONLY_WORKSPACE';$classpath='TEST_ONLY_CLASSES;TEST_ONLY_DEPENDENCY.jar'
    $MaterialFileRoot='TEST_ONLY files';$TemplateFileRoot='TEST_ONLY templates';$LegalCatalogFile='TEST_ONLY catalog'
    $DocumentRuleCatalogFile='TEST_ONLY rules';$ControlledReadPolicyFile='TEST_ONLY policy';$ControlledReadPolicySha256='a'*64
    $assignments=$ast.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left -is [Management.Automation.Language.VariableExpressionAst] -and $n.Left.VariablePath.UserPath -in @('publicCandidates','controlledArguments','arguments')},$true)
    foreach($assignment in $assignments){. ([scriptblock]::Create($assignment.Extent.Text))}
    if($publicCandidates -ne '' -or $arguments -notcontains '"--enforcement.v2.job-pack.public-candidates-path="' -or
       $arguments -notcontains '"--enforcement.v2.cleanup.enabled=false"' -or
       $arguments -notcontains '"--enforcement.deployment.controlled-read.enabled=true"' -or
       @($arguments|Where-Object {$_ -isnot [string] -or $_ -match 'System.Object\[\]'}).Count){throw 'Effective controlled arguments incorrect'}
}
$failed=@($results|Where-Object {-not $_.passed}).Count
[ordered]@{scope='COMPONENT_TOOL_FIXTURES_ONLY_NOT_RELEASE_OR_PRODUCT_I';checkedAt=[DateTimeOffset]::UtcNow.ToString('o');tests=$results.Count;failures=$failed;errors=0;skipped=0;cases=$results;fixtureRoot=$runRoot;resourcesRetained=$true;containerStarts=0;databaseConnections=0;secretReads=0;productStarts=0}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $resultFile -Encoding utf8NoBOM
if($failed){throw "Controlled-read tool components failed: $failed"}
Write-Output "Tool components=$($results.Count), failures=0, errors=0, skipped=0; not product I"
