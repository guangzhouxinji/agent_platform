$ErrorActionPreference='Stop'
$helper='docs/evidence/single-database-installation-20261009/approved-execution/prepare-domain-references10.ps1'
$code=Get-Content -LiteralPath $helper -Raw;$cut=$code.IndexOf("`nAssert-Window`n`$request=");if($cut -lt 0){throw 'EXACT_DECLARATION_PREFIX_REQUIRED'};. ([scriptblock]::Create($code.Substring(0,$cut)))
Assert-Window
$directory='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation'
$source='C:/Users/60329/AppData/Local/AgentPlatform/consultation-product'
$certificateFile=Join-Path $directory 'handoff-client.cer';$storeFile=Join-Path $directory 'handoff-client.p12'
if((Test-Path -LiteralPath $certificateFile) -or (Test-Path -LiteralPath $storeFile)){throw 'EXISTING_SERVICE_CALLER_KEY_PRESERVED'}
$base=Get-Content -LiteralPath (Join-Path $evidence 'domain-references-consultation10.json') -Raw|ConvertFrom-Json -AsHashtable -DateKind String
$secret=New-ManagedSecret $directory 'SINGLE_DB_CONSULTATION_PRODUCT_CONSULTATION_COMPARISON_START_WORKER_PASSWORD'
$password=Read-FinalManagedSecret $base.sourceSignerCredential
$ca=[Security.Cryptography.X509Certificates.X509Certificate2Collection]::new();$ca.Import($base.sourceSignerStore.path,$password,[Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet);$password=$null
$signer=@($ca|Where-Object {$_.HasPrivateKey -and (Cert-Sha $_) -ceq $base.trustCertificateSha256})
if($signer.Count -ne 1){throw 'EXACT_CONSULTATION_SIGNER_REQUIRED'}
$root=Read-PublicCertificate $base.rootCertificate.path;$rsa=[Security.Cryptography.RSA]::Create(3072)
try{
 $subject='consultation-handoff-service'
 $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN='+$subject,$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
 $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false,$false,0,$true))
 $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature,$true))
 $oids=[Security.Cryptography.OidCollection]::new();$null=$oids.Add([Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.2'));$request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($oids,$false))
 $serial=[Security.Cryptography.RandomNumberGenerator]::GetBytes(16);$serial[0]=$serial[0] -band 0x7f
 $notAfter=[DateTimeOffset]::Parse($base.serverNotAfter)
 $cert=$request.Create($signer[0],[DateTimeOffset]::UtcNow.AddMinutes(-5),$notAfter,$serial)
 $withKey=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::CopyWithPrivateKey($cert,$rsa)
 Assert-CertificateChain $cert $root
 Write-NewBytes $certificateFile $cert.RawData
 $collection=[Security.Cryptography.X509Certificates.X509Certificate2Collection]::new();$null=$collection.Add($withKey);$null=$collection.Add($root)
 $bytes=$collection.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12,$secret.value);try{Write-NewBytes $storeFile $bytes}finally{[Array]::Clear($bytes)}
 $sourcePath=Join-Path $workspace 'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductComparisonStartConfiguration.java'
 $record=@{format='SINGLE_DATABASE_HANDOFF_COMPONENT_TLS_REFERENCE/v1';capturedAt=[DateTimeOffset]::UtcNow.ToString('o');taskId='IMP-SINGLE-DB-FINAL-BASELINE-01';subject=$subject;certificateSubjectDn='CN='+$subject;certificateSha256=(Cert-Sha $cert);notAfter=$notAfter.ToString('o');keyStore=(Ref $storeFile);certificate=(Ref $certificateFile);workerPassword=$secret.reference;trustStore=$base.trust;trustPassword=$base.secrets.SINGLE_DB_CONSULTATION_SERVER_SSL_TRUST_STORE_PASSWORD;componentSource=(Ref $sourcePath);responsibility='Current approved main execution session prepares the actual ProductComparisonStartApplication mTLS self-call binding; not a standalone Worker claim';productOrigin='https://consultation.agent-platform.internal:8443';publicIssuer=$base.issuer;configurationVersion='FINAL-HANDOFF-20261009-V1';leaseMillis=60000;normalSubjectRegistrationRequired=$true;normalTaskDelegationRequired=$true;subjectRegistered=$false;taskGrantCreated=$false;standaloneWorkerClaimed=$false;componentApplicationStarted=$false;modelBudgetOrAttemptCreated=$false;databaseOperations=0;sourcePrivateKeyCopied=$false;professionalIdentityClaimed=$false;sourceApproval=(Ref $approvalPath)}
 Write-NewText (Join-Path $evidence 'consultation-handoff-component-tls12.json') (($record|ConvertTo-Json -Depth 8)+"`n")
 Write-Output 'REAL_COMPONENT_CLIENT_TLS_PREPARED_NO_SUBJECT_OR_TASK_PERMISSION_CREATED'
}finally{if($withKey){$withKey.Dispose()};if($cert){$cert.Dispose()};$rsa.Dispose();$root.Dispose();foreach($c in $ca){$c.Dispose()};$secret.value=$null}
