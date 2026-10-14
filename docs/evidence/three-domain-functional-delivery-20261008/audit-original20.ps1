param([Parameter(Mandatory)][int]$Rbc)
$ErrorActionPreference='Stop'
$root="docs/evidence/three-domain-functional-delivery-20261008/full-regression-rbc$Rbc"
$old=Get-Content 'docs/evidence/grant-recovery-admission-20261008/full-regression-rbc13/original20-current-full-audit.json' -Raw|ConvertFrom-Json
$rows=@()
foreach($case in $old.cases){
 $files=@(Get-ChildItem "$root/full-reports" -Recurse -File -Filter ("TEST-"+$case.suite+"-20261008-FUNCTIONAL-DELIVERY-FULL$Rbc.xml"))
 if($files.Count -ne 1){throw 'Missing exact original20 report'}
 [xml]$xml=Get-Content $files[0].FullName -Raw
 $tests=@($xml.testsuite.SelectNodes('testcase')|Where-Object {$_.name -eq $case.test})
 if($tests.Count -ne 1){throw 'Missing or duplicate original20 testcase'}
 $test=$tests[0]
 $state=if($null -ne $test.SelectSingleNode('failure')){'FAILED'}elseif($null -ne $test.SelectSingleNode('error')){'ERROR'}elseif($null -ne $test.SelectSingleNode('skipped')){'NOT_EXECUTED'}else{'PASSED'}
 $rel=$files[0].FullName.Substring((Resolve-Path $root).Path.Length+1).Replace('\','/')
 $rows+=@{suite=$case.suite;test=$case.test;status=$state;report=$rel;xmlSha256=(Get-FileHash $files[0].FullName).Hash.ToLowerInvariant()}
}
@{source="Prior original20 identities only; outcomes independently recomputed from RBC$Rbc actual XML";passed=@($rows|Where-Object status -eq 'PASSED').Count;cases=$rows}|ConvertTo-Json -Depth 8|Set-Content -Encoding utf8 "$root/original20-current-full-audit.json"
