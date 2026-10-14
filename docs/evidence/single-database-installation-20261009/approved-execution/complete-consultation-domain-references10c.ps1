$ErrorActionPreference='Stop'
$p=Join-Path (Get-Location) 'docs/evidence/single-database-installation-20261009/approved-execution/prepare-domain-references10.ps1'
$code=Get-Content -LiteralPath $p -Raw
$cut=$code.IndexOf("`nAssert-Window`n`$request=")
if($cut -lt 0){throw 'EXACT_HELPER_PREFIX_REQUIRED'}
# Only declarations/functions; no original TLS operation is replayed.
. ([scriptblock]::Create($code.Substring(0,$cut)))
Assert-Window
$source='C:/Users/60329/AppData/Local/AgentPlatform/consultation-product'
$directory='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation'
$manifestFile=Join-Path $source 'identity-bootstrap.json';$m=Get-Content -LiteralPath $manifestFile -Raw|ConvertFrom-Json -DateKind String
$request=Get-Content -LiteralPath $requestPath -Raw|ConvertFrom-Json -DateKind String
$root=Read-PublicCertificate (Join-Path $directory 'root-ca.cer');$peer=Read-PublicCertificate (Join-Path $directory 'maintainer.cer');$server=Read-PublicCertificate (Join-Path $directory 'server.cer')
$oldRoot=Read-PublicCertificate $m.trustCertificatePath
$serverStore=Join-Path $directory 'server.p12';$trustStore=Join-Path $directory 'trust.p12';$bootstrapFile=Join-Path $directory 'identity-bootstrap.json'
if(Test-Path -LiteralPath $trustStore){throw 'EXISTING_TRUSTSTORE_REQUIRES_DIFFERENT_EXPLICIT_RECONCILIATION'}
if(Test-Path -LiteralPath $bootstrapFile){throw 'EXISTING_BOOTSTRAP_MANIFEST_PRESERVED'}
if((Cert-Sha $root) -cne (Cert-Sha $oldRoot) -or (Cert-Sha $peer) -cne $m.certificateSha256 -or $server.Subject -cne 'CN=consultation.agent-platform.internal' -or $server.NotAfter.ToUniversalTime() -gt [DateTimeOffset]::Parse($request.dataScope.latestRetentionUntil).UtcDateTime){throw 'EXACT_PARTIAL_TLS_SCENE_REQUIRED'}
Assert-CertificateChain $server $root;Assert-CertificateChain $peer $root
$prefix='SINGLE_DB_CONSULTATION'
$secretNames=@('SERVER_SSL_KEY_STORE_PASSWORD','SERVER_SSL_TRUST_STORE_PASSWORD','AGENT_PLATFORM_RUNTIME_CHECKPOINT_KEY','PRODUCT_IDENTITY_MASTER_KEY','PRODUCT_SOURCE_RESULTS_CURSOR_HMAC_KEY_BASE64')|ForEach-Object {$prefix+'_'+$_}
$secrets=@{};foreach($n in $secretNames){$secrets[$n]=Ref (Join-Path $directory ($n+'.dpapi'))}
$password=Read-FinalManagedSecret $secrets[$prefix+'_SERVER_SSL_KEY_STORE_PASSWORD']
$collection=[Security.Cryptography.X509Certificates.X509Certificate2Collection]::new();$collection.Import($serverStore,$password,[Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet);$password=$null
if(@($collection|Where-Object HasPrivateKey).Count -ne 1 -or @($collection|Where-Object {$_.HasPrivateKey -and (Cert-Sha $_) -ceq (Cert-Sha $server)}).Count -ne 1){throw 'EXACT_EXISTING_SERVER_PRIVATE_KEY_REQUIRED'}
$oldEnv=[Environment]::GetEnvironmentVariable('SINGLE_FINAL_TRUSTSTORE_PASSWORD','Process')
try{
 Assert-Window
 [Environment]::SetEnvironmentVariable('SINGLE_FINAL_TRUSTSTORE_PASSWORD',(Read-FinalManagedSecret $secrets[$prefix+'_SERVER_SSL_TRUST_STORE_PASSWORD']),'Process')
 & 'D:/software/jdk-21/bin/keytool.exe' @('-J-Xmx64m','-J-XX:ActiveProcessorCount=1','-J-Duser.language=en','-J-Duser.country=US','-importcert','-alias','product-root','-file',(Join-Path $directory 'root-ca.cer'),'-keystore',$trustStore,'-storetype','PKCS12','-storepass:env','SINGLE_FINAL_TRUSTSTORE_PASSWORD','-noprompt') *> (Join-Path $directory 'public-truststore-tool-completion10c.log')
 if($LASTEXITCODE -ne 0){throw 'PUBLIC_ONLY_TRUST_COMPLETION_FAILED_PRESERVED'}
 $issuer=(@($request.dnsAndTlsRequested|Where-Object domain -CEQ 'consultation')[0]).issuer
 $manifest=@{issuer=$issuer;subject=$m.subject;organization=$m.organization;certificatePath=(Join-Path $directory 'maintainer.cer').Replace('\','/');trustCertificatePath=(Join-Path $directory 'root-ca.cer').Replace('\','/');certificateSubjectDn=$m.certificateSubjectDn;certificateSha256=$m.certificateSha256;roles=@($m.roles)}
 Write-NewText $bootstrapFile (($manifest|ConvertTo-Json -Depth 4)+"`n")
 $result=@{domain='consultation';issuer=$issuer;sourceMaintainerManifest=(Ref $manifestFile);sourceSignerStore=(Ref (Join-Path $source 'root-ca.p12'));sourceSignerCredential=(Ref (Join-Path $source 'certificate-store.credential.xml'));maintainerCertificateSha256=(Cert-Sha $peer);trustCertificateSha256=(Cert-Sha $root);serverCertificateSha256=(Cert-Sha $server);serverNotBefore=$server.NotBefore.ToUniversalTime().ToString('o');serverNotAfter=$server.NotAfter.ToUniversalTime().ToString('o');subjectAlternativeName='consultation.agent-platform.internal';server=(Ref $serverStore);trust=(Ref $trustStore);rootCertificate=(Ref (Join-Path $directory 'root-ca.cer'));maintainerCertificate=(Ref (Join-Path $directory 'maintainer.cer'));identityBootstrapManifest=(Ref $bootstrapFile);secrets=$secrets;sourceIssuerNotReused=$true;sourcePrivateKeysCopied=$false;newProductMemberOrGrantCreated=$false;databaseTouched=$false;normalBootstrapNotExecuted=$true;personalOrProfessionalIdentityClaimed=$false;explicitPartialTlsCompletion=$true;originalFailurePath='docs/evidence/single-database-installation-20261009/approved-execution/domain-references-execution10b.log';privateKeyRegenerated=$false;existingFilesOverwritten=$false}
 Write-NewText (Join-Path $evidence 'domain-references-consultation10.json') (($result|ConvertTo-Json -Depth 8)+"`n")
 Write-Output 'CONSULTATION_EXISTING_TLS_PREFIX_VERIFIED_PUBLIC_TRUST_ONLY_COMPLETED_NO_KEY_REGENERATION'
}finally{[Environment]::SetEnvironmentVariable('SINGLE_FINAL_TRUSTSTORE_PASSWORD',$oldEnv,'Process');foreach($cert in $collection){$cert.Dispose()};$server.Dispose();$peer.Dispose();$root.Dispose();$oldRoot.Dispose()}
