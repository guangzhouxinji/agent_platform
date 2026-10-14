param([switch]$ResumeIncompleteTrust)
$ErrorActionPreference='Stop'
$root=Join-Path $env:LOCALAPPDATA 'AgentPlatform/adjudication-model-v1/product-runtime'
$exists=Test-Path -LiteralPath $root
if($exists -and (-not $ResumeIncompleteTrust -or (Test-Path -LiteralPath (Join-Path $root 'root-ca.p12')))){throw 'Preserve existing trust; resume only before root certificate generation'}
if(-not $exists){New-Item -ItemType Directory -Path $root|Out-Null}
$owner=[Security.Principal.WindowsIdentity]::GetCurrent().Name; & icacls.exe $root '/inheritance:r' '/grant:r' "$($owner):(OI)(CI)F" *> (Join-Path $root 'directory-acl.log');if($LASTEXITCODE -ne 0){throw 'Private trust directory permissions failed'}
function Save-Secret([string]$name,[string]$value){ConvertTo-SecureString -String $value -AsPlainText -Force|Export-Clixml -LiteralPath (Join-Path $root "$name.credential.xml")}
if($exists){$secure=Import-Clixml -LiteralPath (Join-Path $root 'tls.credential.xml');$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);try{$pass=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}}else{$pass='T9'+[Guid]::NewGuid().ToString('N');Save-Secret 'tls' $pass}
foreach($name in @('authority-admin','authority-owner','authority-service','authority-backup','operator')){if(-not (Test-Path -LiteralPath (Join-Path $root "$name.credential.xml"))){Save-Secret $name ('P9'+[Guid]::NewGuid().ToString('N'))}}
if(-not (Test-Path -LiteralPath (Join-Path $root 'identity-master.credential.xml'))){Save-Secret 'identity-master' ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))}
$env:ADJ_TLS_PASSWORD=$pass
function Keytool([string[]]$a){& keytool.exe @a *> (Join-Path $root 'certificate-creation.log');if($LASTEXITCODE -ne 0){throw 'Independent certificate creation failed; preserve existing files'}}
try{
 Keytool @('-genkeypair','-alias','ca','-dname','CN=Adjudication Product Root 20261004','-keyalg','RSA','-keysize','3072','-validity','28','-ext','BC=ca:true','-ext','KU=keyCertSign,cRLSign','-keystore',"$root/root-ca.p12",'-storetype','PKCS12','-storepass:env','ADJ_TLS_PASSWORD','-keypass:env','ADJ_TLS_PASSWORD','-noprompt')
 Keytool @('-exportcert','-alias','ca','-keystore',"$root/root-ca.p12",'-storepass:env','ADJ_TLS_PASSWORD','-rfc','-file',"$root/root-ca.cer")
 foreach($kind in @('server','maintainer')){
  $dn=if($kind -eq 'server'){'CN=localhost'}else{'CN=adj-deployment-maintainer-20261004'};$eku=if($kind -eq 'server'){'EKU=serverAuth'}else{'EKU=clientAuth'}
  Keytool @('-genkeypair','-alias',$kind,'-dname',$dn,'-keyalg','RSA','-keysize','3072','-validity','28','-keystore',"$root/$kind.p12",'-storetype','PKCS12','-storepass:env','ADJ_TLS_PASSWORD','-keypass:env','ADJ_TLS_PASSWORD','-noprompt')
  Keytool @('-certreq','-alias',$kind,'-keystore',"$root/$kind.p12",'-storepass:env','ADJ_TLS_PASSWORD','-file',"$root/$kind.csr")
  $a=@('-gencert','-alias','ca','-keystore',"$root/root-ca.p12",'-storepass:env','ADJ_TLS_PASSWORD','-infile',"$root/$kind.csr",'-outfile',"$root/$kind.cer",'-rfc','-validity','28','-ext','KU=digitalSignature,keyEncipherment','-ext',$eku)
  if($kind -eq 'server'){$a+=@('-ext','SAN=dns:localhost,ip:127.0.0.1')};Keytool $a
  Keytool @('-importcert','-alias','ca','-keystore',"$root/$kind.p12",'-storepass:env','ADJ_TLS_PASSWORD','-file',"$root/root-ca.cer",'-noprompt')
  Keytool @('-importcert','-alias',$kind,'-keystore',"$root/$kind.p12",'-storepass:env','ADJ_TLS_PASSWORD','-file',"$root/$kind.cer",'-noprompt')
 }
 Keytool @('-importcert','-alias','ca','-keystore',"$root/trust.p12",'-storetype','PKCS12','-storepass:env','ADJ_TLS_PASSWORD','-file',"$root/root-ca.cer",'-noprompt')
 $ca=[Security.Cryptography.X509Certificates.X509Certificate2]::new("$root/root-ca.cer");$peer=[Security.Cryptography.X509Certificates.X509Certificate2]::new("$root/maintainer.cer")
 $manifest=[ordered]@{issuer='https://localhost:5445';subject='adj-deployment-maintainer-20261004';organization='project-adjudication';certificatePath="$root/maintainer.cer";trustCertificatePath="$root/root-ca.cer";certificateSubjectDn='CN=adj-deployment-maintainer-20261004';certificateSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($peer.GetRawCertData())).ToLower();roles=@('SUBJECTS','GRANTS','KEYS','SOURCES')}
 $manifest|ConvertTo-Json|Set-Content "$root/identity-bootstrap.json" -Encoding utf8
 [ordered]@{sourceApproval='ADJ-SUBJECT-SELECTION-USER-20261004-01';organization='project-adjudication';issuer=$manifest.issuer;maintainer=$manifest.subject;certificateSha256=$manifest.certificateSha256;trustCaSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($ca.GetRawCertData())).ToLower();roles=$manifest.roles;certificateNotAfterUtc=$peer.NotAfter.ToUniversalTime().ToString('o');realPersonIdentityVerified=$false;professionalReviewPermission=$false}|ConvertTo-Json|Set-Content 'docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01/selected-trust.json' -Encoding utf8
 Write-Output 'INDEPENDENT_ADJ_TRUST_CREATED_NO_DATABASE_OR_BUSINESS_GRANT_WRITES'
}finally{Remove-Item Env:ADJ_TLS_PASSWORD -ErrorAction SilentlyContinue}
