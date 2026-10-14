$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path;$d=Join-Path $workspace 'docs/evidence/single-database-installation-20261009/approved-execution'
. (Join-Path $workspace 'scripts/lib/single-database-final-deployment.ps1')
$results=@();$old=[Environment]::GetEnvironmentVariable('SINGLE_FINAL_CHECK_PASSWORD','Process')
try{
 foreach($domain in @('consultation','enforcement','adjudication')){
  $r=Get-Content -LiteralPath (Join-Path $d ('domain-references-'+$domain+'10.json')) -Raw|ConvertFrom-Json -AsHashtable -DateKind String
  foreach($kind in @('server','trust')){
   Assert-FinalFile $r[$kind]
   $n='SINGLE_DB_'+$domain.ToUpperInvariant()+'_SERVER_SSL_'+$(if($kind -ceq 'server'){'KEY'}else{'TRUST'})+'_STORE_PASSWORD'
   [Environment]::SetEnvironmentVariable('SINGLE_FINAL_CHECK_PASSWORD',(Read-FinalManagedSecret $r.secrets[$n]),'Process')
   $out=& 'D:/software/jdk-21/bin/keytool.exe' @('-J-Xmx64m','-J-XX:ActiveProcessorCount=1','-J-Duser.language=en','-J-Duser.country=US','-list','-v','-keystore',$r[$kind].path,'-storetype','PKCS12','-storepass:env','SINGLE_FINAL_CHECK_PASSWORD') 2>&1
   if($LASTEXITCODE -ne 0){throw 'JAVA_REAL_KEYSTORE_READ_FAILED'}
   $text=$out -join "`n"
   if($kind -ceq 'server' -and ($text -notmatch 'PrivateKeyEntry' -or $text -notmatch 'Certificate chain length: 2' -or $text -notmatch ('DNSName: '+[regex]::Escape(([uri]$r.issuer).DnsSafeHost)))){throw 'REAL_JAVA_SERVER_KEY_CHAIN_OR_SAN_FAILED'}
   if($kind -ceq 'trust' -and ($text -notmatch 'trustedCertEntry' -or $text -match 'PrivateKeyEntry')){throw 'PUBLIC_ONLY_REAL_JAVA_TRUSTSTORE_REQUIRED'}
   $log=Join-Path $d ('domain-'+$domain+'-'+$kind+'-java-validation10.log')
   $s=[IO.File]::Open($log,[IO.FileMode]::CreateNew);try{$b=[Text.UTF8Encoding]::new($false).GetBytes($text+"`n");$s.Write($b);$s.Flush($true)}finally{$s.Dispose()}
   $results+=@{domain=$domain;kind=$kind;actualJdk21Read=$true;sourceSha256=$r[$kind].sha256;publicValidationOutputPath=$log;privateValuesPrinted=$false}
  }
 }
 $applications=@('consultation','enforcement','adjudication')|ForEach-Object {Get-Content -LiteralPath (Join-Path $d ('domain-references-'+$_+'10.json')) -Raw|ConvertFrom-Json -AsHashtable -DateKind String}
 $receipt=@{format='SINGLE_DATABASE_REAL_THREE_DOMAIN_REFERENCES_VALIDATED/v1';taskId='IMP-SINGLE-DB-FINAL-BASELINE-01';capturedAt=[DateTimeOffset]::UtcNow.ToString('o');applications=@($applications);validation=$results;actualJdkReadChecks=$results.Count;allThreeCaDistinct=(@($applications.trustCertificateSha256|Select-Object -Unique).Count -eq 3);databaseOperations=0;productMembersOrGrantsCreated=0;modelCalls=0;privateKeysCopiedFromOldResources=$false;configurationComplete=$false;normalIdentityBootstrapNotExecuted=$true;applicationsNotStarted=$true;professionalIdentityNotClaimed=$true}
 $dest=Join-Path $d 'domain-references-complete10.json';$s=[IO.File]::Open($dest,[IO.FileMode]::CreateNew);try{$b=[Text.UTF8Encoding]::new($false).GetBytes(($receipt|ConvertTo-Json -Depth 12)+"`n");$s.Write($b);$s.Flush($true)}finally{$s.Dispose()}
 Write-Output 'REAL_THREE_DOMAINS_SIX_JDK_PKCS12_CHAIN_AND_SAN_CHECKS_PASSED_NO_PRODUCT_BOOTSTRAP'
}finally{[Environment]::SetEnvironmentVariable('SINGLE_FINAL_CHECK_PASSWORD',$old,'Process')}
