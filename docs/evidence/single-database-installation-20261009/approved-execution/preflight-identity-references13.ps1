$ErrorActionPreference='Stop'
$workspace='D:/daima/xinji/agent_platform'
$e=Join-Path $workspace 'docs/evidence/single-database-installation-20261009/approved-execution'
$root='C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009'
function Ref([string]$path){$file=Get-Item -LiteralPath $path;if($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'REGULAR_REFERENCE_REQUIRED'};return @{path=$file.FullName.Replace('\','/');sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}}
$original=Get-Content -LiteralPath (Join-Path $e 'primary-grant-config08.json') -Raw|ConvertFrom-Json -AsHashtable
$prepared=@()
foreach($d in $original.deployment.domains){
 $r=Get-Content -LiteralPath (Join-Path $e ('domain-references-'+$d.domain+'10.json')) -Raw|ConvertFrom-Json -AsHashtable
 $m=Get-Content -LiteralPath $r.identityBootstrapManifest.path -Raw|ConvertFrom-Json -AsHashtable
 foreach($f in @($r.identityBootstrapManifest,$r.maintainerCertificate,$r.rootCertificate)){if((Ref $f.path).sha256 -cne $f.sha256){throw 'REFERENCE_DRIFT'}}
 if($m.issuer -cne $d.issuer -or $m.certificatePath -cne $r.maintainerCertificate.path -or $m.trustCertificatePath -cne $r.rootCertificate.path -or $m.certificateSha256 -cne $r.maintainerCertificate.sha256){throw 'MANIFEST_DOMAIN_DRIFT'}
 $cert=[Security.Cryptography.X509Certificates.X509Certificate2]::new([IO.File]::ReadAllBytes($m.certificatePath))
 $ca=[Security.Cryptography.X509Certificates.X509Certificate2]::new([IO.File]::ReadAllBytes($m.trustCertificatePath))
 try {
  $now=[DateTimeOffset]::UtcNow
  if($now -lt $cert.NotBefore.ToUniversalTime() -or $now -ge $cert.NotAfter.ToUniversalTime()){throw 'MAINTAINER_CERTIFICATE_EXPIRED'}
  # Custom public trust root only; no OS trust mutation, no network revocation fetch.
  $chain=[Security.Cryptography.X509Certificates.X509Chain]::new()
  try {$chain.ChainPolicy.TrustMode=[Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust;$null=$chain.ChainPolicy.CustomTrustStore.Add($ca);$chain.ChainPolicy.RevocationMode=[Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck;$chain.ChainPolicy.DisableCertificateDownloads=$true;if(-not $chain.Build($cert)){throw 'MAINTAINER_CHAIN_REFUSED'}}finally{$chain.Dispose()}
  $bindings=@{PRODUCT_IDENTITY_AUTHORITY_TOPOLOGY='shared-instance';PRODUCT_IDENTITY_DB_URL=$d.business.jdbcUrl;PRODUCT_IDENTITY_AUTHORITY_DB_URL=$d.authority.jdbcUrl;PRODUCT_IDENTITY_DB_SCHEMA=$d.business.schema;PRODUCT_IDENTITY_AUTHORITY_SCHEMA=$d.authority.schema;PRODUCT_IDENTITY_DB_DEPLOY_USER=$d.business.ownerUser;PRODUCT_IDENTITY_AUTHORITY_DEPLOY_USER=$d.authority.ownerUser;PRODUCT_IDENTITY_DB_SERVICE_USER=$d.business.runtimeUser;PRODUCT_IDENTITY_AUTHORITY_SERVICE_USER=$d.authority.runtimeUser;PRODUCT_IDENTITY_AUTHORITY_BACKUP_USER=$d.authority.backupUser;PRODUCT_IDENTITY_TRUST_SHA256=$r.trustCertificateSha256}
  $secrets=@{}
  foreach($p in @(@('PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD',$d.business.ownerUser),@('PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD',$d.authority.ownerUser),@('PRODUCT_IDENTITY_DB_SERVICE_PASSWORD',$d.business.runtimeUser),@('PRODUCT_IDENTITY_AUTHORITY_SERVICE_PASSWORD',$d.authority.runtimeUser),@('PRODUCT_IDENTITY_AUTHORITY_BACKUP_PASSWORD',$d.authority.backupUser))){$secrets[$p[0]]=Ref (Join-Path $root ('SINGLE_DB_'+$p[1]+'_PASSWORD.dpapi'))}
  $prepared+=@{domain=$d.domain;manifest=Ref $r.identityBootstrapManifest.path;maintainerCertificate=Ref $m.certificatePath;trustCertificate=Ref $m.trustCertificatePath;certificateChainPassed=$true;certificateRevocationStatusClaimed=$false;certificateNotAfter=$cert.NotAfter.ToUniversalTime().ToString('o');environmentBindings=$bindings;managedSecretReferences=$secrets;secretsDecrypted=$false;mainClass='cn.xinji.agentplatform.security.product.ProductIdentityBootstrapSharedInstance';hostManifestArgumentsOnly=@('--shared-instance',$r.identityBootstrapManifest.path);containerManifestAndCertificatePathMappingPrepared=$false;bootstrapExecuted=$false;memberActivationOrTaskGrantCreated=$false}
 }finally{$cert.Dispose();$ca.Dispose()}
}
$output=Join-Path $e 'formal-start-identity-reference-preflight13.json'
if(Test-Path -LiteralPath $output){throw 'PRESERVE_EXISTING_EVIDENCE'}
@{format='SINGLE_DATABASE_IDENTITY_REFERENCE_PREFLIGHT/v1';capturedAt=[DateTimeOffset]::UtcNow.ToString('o');scope='Actual public certificate/reference and five-identity binding preflight; no JDBC, DCL, bootstrap, member activation or Grant';targetId=$original.targetId;source=Ref (Join-Path $workspace 'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductIdentityBootstrapSharedInstance.java');executionPrerequisites=@('Original 674-step grant process normal exit and full receipt/journal reconciliation','Naturally released maintenance lock and fresh complete actual privilege inventory','Current approval window, exact resource attribution, verified current release JAR/classes','Map the host-only manifest and public certificate paths to exact readonly tool mounts; host paths are not runnable container arguments','Normal managed shared-instance bootstrap with preserved failure and actual postconditions');applications=$prepared;databaseConnections=0;containersCreated=0;existingFilesOverwritten=$false;executionAuthorizedByThisEvidence=$false}|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $output
Write-Output 'ACTUAL_THREE_DOMAIN_REFERENCE_PREFLIGHT_PASSED_NO_DATABASE_EXECUTION'
