$ErrorActionPreference='Stop'
. (Join-Path (Get-Location) 'scripts/lib/single-database-final-deployment.ps1')
$e=Split-Path -Parent $PSScriptRoot
$ready=(Read-FinalDeploymentJson "$e/formal-binding17-resume-inputs.json").readyApplicationFields
$null=Assert-FinalApproval $ready
$c=@((Read-FinalDeploymentJson "$e/final-entry-client23/public-client-and-binding-inputs.json").clients|Where-Object domain -CEQ 'consultation')[0]
Assert-FinalFile $c.maintenance.trustCertificate
Add-Type -TypeDefinition @"
using System; using System.Net.Http; using System.Net.Security; using System.Security.Cryptography; using System.Security.Cryptography.X509Certificates;
public static class ConsultationPublicTrust45 {
 public static X509Certificate2 Root; public static string Peer; public static bool Accepted;
 public static readonly Func<HttpRequestMessage,X509Certificate2,X509Chain,SslPolicyErrors,bool> Callback=(r,c,x,e)=>{
 Accepted=false; if(c==null || (e & (SslPolicyErrors.RemoteCertificateNameMismatch|SslPolicyErrors.RemoteCertificateNotAvailable))!=0)return false;
 Peer=Convert.ToHexString(SHA256.HashData(c.RawData)).ToLowerInvariant();
 if(Peer!="de4f652f8f99db36e7b7049fab104de0c0918a0e68dcc2dbd26fad6451374f06")return false;
 using var chain=new X509Chain();chain.ChainPolicy.TrustMode=X509ChainTrustMode.CustomRootTrust;chain.ChainPolicy.CustomTrustStore.Add(Root);chain.ChainPolicy.RevocationMode=X509RevocationMode.NoCheck;chain.ChainPolicy.VerificationFlags=X509VerificationFlags.NoFlag;chain.ChainPolicy.ApplicationPolicy.Add(new Oid("1.3.6.1.5.5.7.3.1"));
 Accepted=chain.Build(c);return Accepted;
 };
}
"@
$ca=[Security.Cryptography.X509Certificates.X509Certificate2]::new($c.maintenance.trustCertificate.path)
$handler=$null;$http=$null
try{
 [ConsultationPublicTrust45]::Root=$ca
 $dns=@([Net.Dns]::GetHostAddresses('consultation.agent-platform.internal')|ForEach-Object IPAddressToString)
 if($dns.Count -ne 1 -or $dns[0] -cne '127.0.0.1'){throw 'EXACT_CURRENT_LOCAL_DNS_REQUIRED'}
 $handler=[Net.Http.HttpClientHandler]::new();$handler.UseProxy=$false;$handler.AllowAutoRedirect=$false;$handler.ClientCertificateOptions=[Net.Http.ClientCertificateOption]::Manual;$handler.SslProtocols=[Security.Authentication.SslProtocols]::Tls12;$handler.ServerCertificateCustomValidationCallback=[ConsultationPublicTrust45]::Callback
 if($handler.ClientCertificates.Count -ne 0){throw 'NO_CLIENT_CERT_REQUIRED'}
 $http=[Net.Http.HttpClient]::new($handler);$http.Timeout=[TimeSpan]::FromSeconds(20)
 $results=@()
 foreach($path in @('/app/consultation','/.well-known/openid-configuration','/login')){
  $null=Assert-FinalApproval $ready
  $response=$http.GetAsync('https://consultation.agent-platform.internal:5446'+$path).GetAwaiter().GetResult()
  try{$bytes=$response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();$text=[Text.Encoding]::UTF8.GetString($bytes)
   $r=@{path=$path;status=[int]$response.StatusCode;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();bytes=$bytes.Length;strictTlsAccepted=[ConsultationPublicTrust45]::Accepted;serverSha256=[ConsultationPublicTrust45]::Peer;clientCertificateCount=0;rawBodySaved=$false;cookieAndCsrfSaved=$false}
   if($path -eq '/login'){$r.normalFormLoginFieldsPresent=($text -match 'name="username"' -and $text -match 'name="password"')}
   if($path -eq '/.well-known/openid-configuration'){$r.exactIssuer=($text.Contains('"issuer":"https://consultation.agent-platform.internal:5446"'))}
   $results+=$r
   if($r.status -ne 200 -or -not $r.strictTlsAccepted){throw 'PUBLIC_NO_CERT_TLS_GET_FAILED'}
  }finally{$bytes=$null;$text=$null;$response.Dispose()}
 }
 @{format='ACTUAL_CURRENT_CONSULTATION_PUBLIC_TLS_NO_CLIENT_CERT/v1';capturedAt=[DateTimeOffset]::Now.ToString('o');requests=$results;maintenancePrivateKeyRead=$false;identityRegistered=$false;loginSubmitted=$false;ordinaryBusinessVerified=$false;modelCalls=0;scope='read-only GET admission; not browser UI or human confirmation evidence'}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $PSScriptRoot 'consultation-public-tls45-result.json')
}finally{if($http){$http.Dispose()};if($handler){$handler.Dispose()};$ca.Dispose();[ConsultationPublicTrust45]::Root=$null}