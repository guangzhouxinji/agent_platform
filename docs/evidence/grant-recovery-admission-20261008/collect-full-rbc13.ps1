$ErrorActionPreference='Stop'
$workspace='D:\daima\xinji\agent_platform'
$root=Join-Path $workspace 'docs/evidence/grant-recovery-admission-20261008/full-regression-rbc13'
$exit=Get-Content (Join-Path $root 'full-backend-exit.json') -Raw|ConvertFrom-Json
$stability=Get-Content (Join-Path $root 'source-stability.json') -Raw|ConvertFrom-Json
if(-not $stability.unchanged){throw 'Source drift prevents current freeze closure'}
$destination=Join-Path $root 'full-reports'
if(Test-Path $destination){throw 'Refuse report overwrite'}
$reports=@();$skips=@();$totals=@{tests=0;failures=0;errors=0;skipped=0}
$xmls=@(Get-ChildItem (Join-Path $workspace 'backend') -Recurse -File -Filter 'TEST-*-20261008-GRANT-RECOVERY-FULL13.xml'|Where-Object {$_.FullName -match '[\\/]target[\\/]surefire-reports[\\/]'}|Sort-Object FullName)
if($xmls.Count -eq 0){throw 'No actual XML reports'}
foreach($file in $xmls){
 $rel=$file.FullName.Substring((Join-Path $workspace 'backend').Length+1).Replace('\','/')
 $target=Join-Path $destination $rel
 New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
 Copy-Item -LiteralPath $file.FullName -Destination $target
 [xml]$xml=[IO.File]::ReadAllText($target)
 $suite=$xml.testsuite
 $row=@{file=('full-reports/'+$rel);name=[string]$suite.name;sha256=(Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant()}
 foreach($key in @('tests','failures','errors','skipped')){$row[$key]=[int]$suite.GetAttribute($key);$totals[$key]+=$row[$key]}
 $reports+=$row
 foreach($test in $suite.SelectNodes('testcase[skipped]')){
  $skip=$test.SelectSingleNode('skipped')
  $skips+=@{suite=[string]$suite.name;test=[string]$test.name;class=[string]$test.classname;message=$skip.GetAttribute('message');type=$skip.GetAttribute('type');actualReason=$skip.InnerText;report=$row.file;status='CONDITIONALLY_SKIPPED_NOT_EXECUTED_NOT_PASSED'}
 }
}
if($skips.Count -ne $totals.skipped){throw 'Skip reason count mismatch'}
@{scope=$exit.scope;reportCount=$reports.Count;tests=$totals.tests;failures=$totals.failures;errors=$totals.errors;skipped=$totals.skipped;conditionalSkippedAreNotPassed=$true;reports=$reports;totals=$totals;exitReceipt='full-backend-exit.json';sourceStability='source-stability.json'}|ConvertTo-Json -Depth 7|Set-Content -Encoding utf8 (Join-Path $root 'full-backend-result.json')
@{recordedAt=(Get-Date -Format o);scope='ACTUAL_XML_SKIP_REASONS_NOT_EXECUTED';count=$skips.Count;cases=$skips}|ConvertTo-Json -Depth 7|Set-Content -Encoding utf8 (Join-Path $root 'conditional-skips.json')
@{reports=$reports.Count;totals=$totals;exit=$exit.exit;sourceUnchanged=$stability.unchanged}|ConvertTo-Json -Depth 4