$ErrorActionPreference='Stop'
. (Join-Path $env:LOCALAPPDATA 'AgentPlatform/consultation-product/product-http.ps1')
$LoginName='project-consultation-member-20260929-v5'
$CredentialName='member-v5.credential.xml'
$HttpProtocolMajor=1
$password = Read-ProductSecret $CredentialName
$handler = [Net.Http.HttpClientHandler]::new()
$handler.CookieContainer = [Net.CookieContainer]::new()
$handler.UseCookies = $true
$handler.AllowAutoRedirect = $false
$handler.SslProtocols = [Security.Authentication.SslProtocols]::Tls12
[ProductDeploymentTrust]::Root = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
    (Join-Path $vault 'root-ca.cer'))
$handler.ServerCertificateCustomValidationCallback = [ProductDeploymentTrust]::Callback
$http = [Net.Http.HttpClient]::new($handler)
if($HttpProtocolMajor -eq 2) {
    $http.DefaultRequestVersion = [Version]::new(2,0)
    $http.DefaultVersionPolicy = [Net.Http.HttpVersionPolicy]::RequestVersionExact
}
$base = if ($env:PRODUCT_DEPLOYMENT_BASE_URL) { $env:PRODUCT_DEPLOYMENT_BASE_URL } else { 'https://localhost:5443' }
function Login-ApprovedMember {
    $loginPage = $http.GetAsync("$base/login").GetAwaiter().GetResult()
    if ([int]$loginPage.StatusCode -ne 200) { throw "Login page HTTP $([int]$loginPage.StatusCode)" }
    Write-Output "member_http_protocol=$($loginPage.Version)"
    $html = $loginPage.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $csrfField = [regex]::Match($html, '<input[^>]*name="_csrf"[^>]*>')
    $csrf = [regex]::Match($csrfField.Value, 'value="([^"]+)"')
    if (-not $csrfField.Success -or -not $csrf.Success) { throw 'Login CSRF field unavailable' }
    $loginFields = [Collections.Generic.Dictionary[string,string]]::new()
    $loginFields.Add('username', $LoginName)
    $loginFields.Add('password', $password)
    $loginFields.Add('_csrf', [Net.WebUtility]::HtmlDecode($csrf.Groups[1].Value))
    $loginForm = [Net.Http.FormUrlEncodedContent]::new($loginFields)
    $login = $http.PostAsync("$base/login", $loginForm).GetAwaiter().GetResult()
    if ([int]$login.StatusCode -ne 302 -or $login.Headers.Location.ToString() -match 'error') {
        throw "Member login HTTP $([int]$login.StatusCode)"
    }
    $verifier = ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
    $verifier = $verifier.TrimEnd('=').Replace('+','-').Replace('/','_')
    $digest = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($verifier))
    $challenge = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+','-').Replace('/','_')
    $callback = 'https://localhost:5443/app/consultation'
    $authorize = "$base/oauth2/authorize?response_type=code&client_id=consultation-workspace-product" +
        "&redirect_uri=$([Uri]::EscapeDataString($callback))&scope=openid&state=product-task-read" +
        "&code_challenge=$challenge&code_challenge_method=S256"
    $approval = $http.GetAsync($authorize).GetAwaiter().GetResult()
    if ([int]$approval.StatusCode -ne 302) { throw "Authorization HTTP $([int]$approval.StatusCode)" }
    $location = $approval.Headers.Location.ToString()
    if (-not $location.StartsWith($callback + '?')) { throw 'Unexpected authorization redirect' }
    $codeMatch = [regex]::Match($location, '[?&]code=([^&]+)')
    if (-not $codeMatch.Success) { throw 'Authorization code unavailable' }
    $tokenFields = [Collections.Generic.Dictionary[string,string]]::new()
    $tokenFields.Add('grant_type', 'authorization_code')
    $tokenFields.Add('client_id', 'consultation-workspace-product')
    $tokenFields.Add('redirect_uri', $callback)
    $tokenFields.Add('code', [Uri]::UnescapeDataString($codeMatch.Groups[1].Value))
    $tokenFields.Add('code_verifier', $verifier)
    $tokenForm = [Net.Http.FormUrlEncodedContent]::new($tokenFields)
    $issued = $http.PostAsync("$base/oauth2/token", $tokenForm).GetAwaiter().GetResult()
    if ([int]$issued.StatusCode -ne 200) { throw "Token HTTP $([int]$issued.StatusCode)" }
    $token = ($issued.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).access_token
    if (-not $token) { throw 'Access token unavailable' }
    $http.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
    $script:approvedToken=$token
    return $token
}
$cutoff=[DateTimeOffset]::Parse('2026-10-06T18:00:00+08:00')
$out=Join-Path (Get-Location).Path 'docs/evidence/consultation-agent-native-20261006/positive-run'
$trace=[Collections.Generic.List[object]]::new()
function Assert-ApprovedTime {if([DateTimeOffset]::Now -ge $cutoff){throw 'Approved cutoff elapsed; never extend'}}
function Approved-Send($client,[string]$method,[string]$path,$body,[string]$name,[int]$expected=200) {
 Assert-ApprovedTime
 $file=Join-Path $out ($name+'.json');if(Test-Path -LiteralPath $file){throw 'Preserve previous receipt; do not create different request silently'}
 $req=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method),$base+$path)
 if($method -ne 'GET'){$req.Headers.Add('Idempotency-Key',('con-positive-20261006-'+$name))}
 if($null -ne $body){$req.Content=[Net.Http.StringContent]::new(($body|ConvertTo-Json -Compress -Depth 35),[Text.Encoding]::UTF8,'application/json')}
 try{$response=$client.SendAsync($req).GetAwaiter().GetResult();$text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult();$value=$null;if($text){try{$value=$text|ConvertFrom-Json -Depth 50}catch{$value=$text}}
 $trace.Add([ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');method=$method;path=$path;status=[int]$response.StatusCode;requestId=if($method -ne 'GET'){'con-positive-20261006-'+$name}else{$null};request=$body})
 if([int]$response.StatusCode -ne $expected){$trace|ConvertTo-Json -Depth 35|Set-Content (Join-Path $out ($name+'-failed-http.json'));throw "Formal request $name HTTP $([int]$response.StatusCode), expected $expected; no secret/raw error body logged"}
 if($text){[IO.File]::WriteAllText($file,$text,[Text.UTF8Encoding]::new($false))}else{[IO.File]::WriteAllText($file,'null',[Text.UTF8Encoding]::new($false))}
 return $value
 }finally{$req.Dispose()}
}
function Save-ApprovedTrace([string]$name){$trace|ConvertTo-Json -Depth 40|Set-Content (Join-Path $out ($name+'-http.json'))}
