param([ValidateSet('all','enforcement-adjudication')][string]$Stage='all')
$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path
$evidence=Join-Path $workspace 'docs/evidence/single-database-installation-20261009/approved-execution'
. (Join-Path $workspace 'scripts/lib/single-database-final-deployment.ps1')
$approvalPath=Join-Path $evidence 'resource-approval01.json'
$requestPath=Join-Path $evidence 'resource-request-original01.json'
function Assert-Window {
 if((Get-FileHash -LiteralPath $approvalPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne '649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7' -or (Get-FileHash -LiteralPath $requestPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne '8c6c5b46f977732b4dcb1825b54620ae4edac1b700004a3c2f967a70738d3a17'){throw 'ORIGINAL_APPROVAL_CHANGED'}
 $a=Get-Content -LiteralPath $approvalPath -Raw|ConvertFrom-Json -DateKind String
 $now=[DateTimeOffset]::UtcNow
 if(-not $a.approvalGranted -or $now -lt [DateTimeOffset]::Parse($a.operationWindowBeijing.from) -or $now -ge [DateTimeOffset]::Parse($a.operationWindowBeijing.until)){throw 'APPROVAL_WINDOW_CLOSED'}
}
function Write-NewBytes([string]$Path,[byte[]]$Bytes){$s=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$s.Write($Bytes);$s.Flush($true)}finally{$s.Dispose()}}
function Write-NewText([string]$Path,[string]$Text){Write-NewBytes $Path ([Text.UTF8Encoding]::new($false).GetBytes($Text))}
function Read-PublicCertificate([string]$Path){$b=[IO.File]::ReadAllBytes($Path);if([Text.Encoding]::ASCII.GetString($b).Contains('-----BEGIN CERTIFICATE-----')){$matches=[regex]::Matches([Text.Encoding]::ASCII.GetString($b),'-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----');if($matches.Count -ne 1){throw 'EXACT_PUBLIC_CERTIFICATE_REQUIRED'};$b=[Convert]::FromBase64String($matches[0].Groups[1].Value)};return [Security.Cryptography.X509Certificates.X509Certificate2]::new($b)}
function Cert-Sha($Certificate){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Certificate.RawData)).ToLowerInvariant()}
function Ref([string]$Path){return @{path=[IO.Path]::GetFullPath($Path).Replace('\','/');sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}}
function Restrict-NewDirectory([string]$Path){
 $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
 foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){$rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow);$acl.AddAccessRule($rule)}
 Set-Acl -LiteralPath $Path -AclObject $acl
}
function New-ManagedSecret([string]$Directory,[string]$Environment){
 $b=[Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
 try{$v=[Convert]::ToBase64String($b);$secure=ConvertTo-SecureString $v -AsPlainText -Force;$file=Join-Path $Directory ($Environment+'.dpapi');Write-NewText $file ((ConvertFrom-SecureString $secure)+"`n");return @{value=$v;reference=(Ref $file)}}finally{[Array]::Clear($b);$secure=$null}
}
function Assert-CertificateChain($Certificate,$Root){
 $chain=[Security.Cryptography.X509Certificates.X509Chain]::new()
 try{$chain.ChainPolicy.TrustMode=[Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust;$chain.ChainPolicy.CustomTrustStore.Add($Root);$chain.ChainPolicy.RevocationMode=[Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck;$chain.ChainPolicy.DisableCertificateDownloads=$true
 if(-not $chain.Build($Certificate)){throw 'EXACT_OFFLINE_CERTIFICATE_CHAIN_INVALID'}}finally{$chain.Dispose()}
}
Assert-Window
$request=Get-Content -LiteralPath $requestPath -Raw|ConvertFrom-Json -DateKind String
$secretRoot=$request.managedCredentialRootRequested
if($secretRoot -cne 'C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009'){throw 'EXACT_MANAGED_ROOT_REQUIRED'}
$rootItem=Get-Item -LiteralPath $secretRoot; $part=$rootItem
while($part){if($part.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'MANAGED_ROOT_REDIRECT_REFUSED'};$part=$part.Parent}
$domains=@(
 @{domain='consultation';source='C:/Users/60329/AppData/Local/AgentPlatform/consultation-product';credential='certificate-store.credential.xml'},
 @{domain='enforcement';source='C:/Users/60329/AppData/Local/AgentPlatform/enforcement-product';credential='certificate-store.credential.xml'},
 @{domain='adjudication';source='C:/Users/60329/AppData/Local/AgentPlatform/adjudication-model-v1/product-runtime';credential='tls.credential.xml'}
)
if($Stage -ceq 'enforcement-adjudication'){$domains=@($domains|Where-Object domain -CNE 'consultation')}
# Validate all source certificates and collisions before creating any key. Existing private keys are never copied.
$validated=@();$caFingerprints=@()
foreach($d in $domains){
 $target=Join-Path $secretRoot $d.domain;if(Test-Path -LiteralPath $target){throw 'EXISTING_DOMAIN_REFERENCE_DIRECTORY_PRESERVED'}
 $manifestFile=Join-Path $d.source 'identity-bootstrap.json';$manifest=Get-Content -LiteralPath $manifestFile -Raw|ConvertFrom-Json -DateKind String
 $peer=Read-PublicCertificate $manifest.certificatePath;$root=Read-PublicCertificate $manifest.trustCertificatePath
 if((Cert-Sha $peer) -cne $manifest.certificateSha256.ToLowerInvariant() -or $peer.NotBefore.ToUniversalTime() -gt [DateTime]::UtcNow -or $peer.NotAfter.ToUniversalTime() -le [DateTime]::UtcNow -or $root.NotAfter.ToUniversalTime() -le [DateTime]::UtcNow){throw 'CURRENT_PINNED_MAINTAINER_CERTIFICATE_REQUIRED'}
 Assert-CertificateChain $peer $root
 if((Cert-Sha $root) -cin $caFingerprints){throw 'THREE_INDEPENDENT_TRUST_ROOTS_REQUIRED'};$caFingerprints+=(Cert-Sha $root)
 if(@($manifest.roles).Count -ne @($manifest.roles|Select-Object -Unique).Count -or @($manifest.roles|Where-Object {$_ -cnotin @('SUBJECTS','GRANTS','KEYS','SOURCES')}).Count -or [string]::IsNullOrWhiteSpace($manifest.subject) -or [string]::IsNullOrWhiteSpace($manifest.organization)){throw 'EXACT_RECORDED_MAINTAINER_DUTIES_REQUIRED'}
 $validated+=@{domain=$d.domain;source=$d.source;credential=(Ref (Join-Path $d.source $d.credential));sourceManifest=(Ref $manifestFile);manifest=$manifest;peer=$peer;root=$root;directory=$target}
}
$results=@();$oldEnv=[Environment]::GetEnvironmentVariable('SINGLE_FINAL_TRUSTSTORE_PASSWORD','Process')
try{
 foreach($d in $validated){
  Assert-Window
  [IO.Directory]::CreateDirectory($d.directory)|Out-Null;Restrict-NewDirectory $d.directory
  $prefix='SINGLE_DB_'+$d.domain.ToUpperInvariant()
  $serverPassword=New-ManagedSecret $d.directory ($prefix+'_SERVER_SSL_KEY_STORE_PASSWORD')
  $trustPassword=New-ManagedSecret $d.directory ($prefix+'_SERVER_SSL_TRUST_STORE_PASSWORD')
  $checkpoint=New-ManagedSecret $d.directory ($prefix+'_AGENT_PLATFORM_RUNTIME_CHECKPOINT_KEY')
  $master=New-ManagedSecret $d.directory ($prefix+'_PRODUCT_IDENTITY_MASTER_KEY')
  $extra=@{};if($d.domain -ceq 'consultation'){$cursor=New-ManagedSecret $d.directory ($prefix+'_PRODUCT_SOURCE_RESULTS_CURSOR_HMAC_KEY_BASE64');$extra[$prefix+'_PRODUCT_SOURCE_RESULTS_CURSOR_HMAC_KEY_BASE64']=$cursor.reference;$cursor.value=$null}
  $rootFile=Join-Path $d.directory 'root-ca.cer';Write-NewBytes $rootFile $d.root.RawData
  $maintainerFile=Join-Path $d.directory 'maintainer.cer';Write-NewBytes $maintainerFile $d.peer.RawData
  $sourcePassword=Read-FinalManagedSecret $d.credential
  $caCollection=[Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
  $caCollection.Import((Join-Path $d.source 'root-ca.p12'),$sourcePassword,[Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
  $sourcePassword=$null
  $signers=@($caCollection|Where-Object {$_.HasPrivateKey -and (Cert-Sha $_) -ceq (Cert-Sha $d.root)})
  if($signers.Count -ne 1){throw 'EXACT_EXISTING_SIGNER_REQUIRED'}
  $issuer=(@($request.dnsAndTlsRequested|Where-Object domain -CEQ $d.domain)[0]).issuer;$hostName=([uri]$issuer).DnsSafeHost
  $rsa=[Security.Cryptography.RSA]::Create(3072)
  try{
   $subject=[Security.Cryptography.X509Certificates.X500DistinguishedName]::new('CN='+$hostName)
   $csr=[Security.Cryptography.X509Certificates.CertificateRequest]::new($subject,$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
   $csr.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false,$false,0,$true))
   $csr.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([Security.Cryptography.X509Certificates.X509KeyUsageFlags]'DigitalSignature,KeyEncipherment',$true))
   $oids=[Security.Cryptography.OidCollection]::new();$null=$oids.Add([Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1'));$csr.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($oids,$false))
   $san=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new();$san.AddDnsName($hostName);$csr.CertificateExtensions.Add($san.Build())
   $notAfter=[DateTimeOffset]::Parse($request.dataScope.latestRetentionUntil).AddMinutes(-1)
   if($notAfter -gt [DateTimeOffset]$d.root.NotAfter){$notAfter=[DateTimeOffset]$d.root.NotAfter}
   $serial=[Security.Cryptography.RandomNumberGenerator]::GetBytes(16);$serial[0]=$serial[0] -band 0x7f
   $certificate=$csr.Create($signers[0],[DateTimeOffset]::UtcNow.AddMinutes(-5),$notAfter,$serial)
   $withKey=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::CopyWithPrivateKey($certificate,$rsa)
   $serverFile=Join-Path $d.directory 'server.cer';Write-NewBytes $serverFile $certificate.RawData
   Assert-CertificateChain $certificate $d.root
   $pfx=[Security.Cryptography.X509Certificates.X509Certificate2Collection]::new();$pfx.Add($withKey);$pfx.Add($d.root)
   $serverStore=Join-Path $d.directory 'server.p12';$pfxBytes=$pfx.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12,$serverPassword.value)
   try{Write-NewBytes $serverStore $pfxBytes}finally{[Array]::Clear($pfxBytes)}
   $trustStore=Join-Path $d.directory 'trust.p12'
   [Environment]::SetEnvironmentVariable('SINGLE_FINAL_TRUSTSTORE_PASSWORD',$trustPassword.value,'Process')
   # Public root only; small bounded keytool, no JDBC/container/build and no password argument value.
   & 'D:/software/jdk-21/bin/keytool.exe' @('-J-Xmx64m','-J-XX:ActiveProcessorCount=1','-J-Duser.language=en','-J-Duser.country=US','-importcert','-alias','product-root','-file',$rootFile,'-keystore',$trustStore,'-storetype','PKCS12','-storepass:env','SINGLE_FINAL_TRUSTSTORE_PASSWORD','-noprompt') *> (Join-Path $d.directory 'public-truststore-tool.log')
   if($LASTEXITCODE -ne 0){throw 'PUBLIC_TRUSTSTORE_CREATION_FAILED_PRESERVED'}
   $bootstrap=@{issuer=$issuer;subject=$d.manifest.subject;organization=$d.manifest.organization;certificatePath=$maintainerFile.Replace('\','/');trustCertificatePath=$rootFile.Replace('\','/');certificateSubjectDn=$d.manifest.certificateSubjectDn;certificateSha256=$d.manifest.certificateSha256;roles=@($d.manifest.roles)}
   $bootstrapFile=Join-Path $d.directory 'identity-bootstrap.json';Write-NewText $bootstrapFile (($bootstrap|ConvertTo-Json -Depth 4)+"`n")
   $secrets=@{};$secrets[$prefix+'_SERVER_SSL_KEY_STORE_PASSWORD']=$serverPassword.reference;$secrets[$prefix+'_SERVER_SSL_TRUST_STORE_PASSWORD']=$trustPassword.reference;$secrets[$prefix+'_AGENT_PLATFORM_RUNTIME_CHECKPOINT_KEY']=$checkpoint.reference;$secrets[$prefix+'_PRODUCT_IDENTITY_MASTER_KEY']=$master.reference;foreach($k in $extra.Keys){$secrets[$k]=$extra[$k]}
   $r=@{domain=$d.domain;issuer=$issuer;sourceMaintainerManifest=$d.sourceManifest;sourceSignerStore=(Ref (Join-Path $d.source 'root-ca.p12'));sourceSignerCredential=$d.credential;maintainerCertificateSha256=(Cert-Sha $d.peer);trustCertificateSha256=(Cert-Sha $d.root);serverCertificateSha256=(Cert-Sha $certificate);serverNotBefore=$certificate.NotBefore.ToUniversalTime().ToString('o');serverNotAfter=$certificate.NotAfter.ToUniversalTime().ToString('o');subjectAlternativeName=$hostName;server=(Ref $serverStore);trust=(Ref $trustStore);rootCertificate=(Ref $rootFile);maintainerCertificate=(Ref $maintainerFile);identityBootstrapManifest=(Ref $bootstrapFile);secrets=$secrets;sourceIssuerNotReused=$true;sourcePrivateKeysCopied=$false;newProductMemberOrGrantCreated=$false;databaseTouched=$false;normalBootstrapNotExecuted=$true;personalOrProfessionalIdentityClaimed=$false}
   Write-NewText (Join-Path $evidence ('domain-references-'+$d.domain+'10.json')) (($r|ConvertTo-Json -Depth 8)+"`n");$results+=$r
  }finally{if($withKey){$withKey.Dispose()};if($certificate){$certificate.Dispose()};$rsa.Dispose();foreach($cert in $caCollection){$cert.Dispose()};$serverPassword.value=$null;$trustPassword.value=$null;$checkpoint.value=$null;$master.value=$null}
 }
 Assert-Window
 Write-NewText (Join-Path $evidence 'domain-references10.json') ((@{format='SINGLE_DATABASE_REAL_MANAGED_DOMAIN_REFERENCES/v1';taskId='IMP-SINGLE-DB-FINAL-BASELINE-01';capturedAt=[DateTimeOffset]::UtcNow.ToString('o');sourceApproval=(Ref $approvalPath);applications=$results;configurationComplete=$false;actualProductIdentityBootstrap=$false;actualApplicationsStarted=$false;actualDatabaseOperations=0;modelCalls=0;sourceBusinessRightsExtended=$false;privateValuesIncluded=$false}|ConvertTo-Json -Depth 10)+"`n")
 Write-Output 'THREE_REAL_DOMAIN_TLS_AND_DPAPI_REFERENCES_PREPARED_NO_DB_OR_BUSINESS_GRANTS'
}finally{[Environment]::SetEnvironmentVariable('SINGLE_FINAL_TRUSTSTORE_PASSWORD',$oldEnv,'Process');foreach($d in $validated){$d.peer.Dispose();$d.root.Dispose()}}
