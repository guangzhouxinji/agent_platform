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
try {
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
    $report=[ordered]@{observedAt=[DateTimeOffset]::Now.ToString('o');scope='REAL_HTTPS_PRODUCT_PKCE_READONLY_NOT_TASK_E2E';login=$LoginName;clientId='consultation-workspace-product';pkceSuccess=$true;browserE2e=$false;browserBlock='IAB untrusted private CA; Chrome extension authentication unavailable';routes=@()}
    foreach($path in @('/v2/identity/me','/app/consultation')) {
      $r=$http.GetAsync($base+$path).GetAwaiter().GetResult();$bytes=$r.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();if([int]$r.StatusCode -ne 200){throw "Read-only smoke failed $path HTTP $([int]$r.StatusCode)"}
      $report.routes+=@{path=$path;status=[int]$r.StatusCode;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
      if($path -eq '/v2/identity/me'){$me=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json;$report.subjectId=$me.subject;if($me.subject -ne '7b389c47-9f5b-4004-ad95-084600b351ef'){throw 'Wrong real product member'};$report.currentGrantCount=@($me.grants).Count}
      else{
       $html=[Text.Encoding]::UTF8.GetString($bytes);$assets=@([regex]::Matches($html,'(?:src|href)="(/app/assets/[^"?#]+)"')|ForEach-Object {$_.Groups[1].Value}|Select-Object -Unique)
       if($assets.Count -lt 1){throw 'Built product assets absent'}
       foreach($asset in $assets){$ar=$http.GetAsync($base+$asset).GetAwaiter().GetResult();$ab=$ar.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();if([int]$ar.StatusCode -ne 200){throw 'Asset failed'};$relative=$asset.Substring('/app/'.Length);$file=Join-Path (Get-Location).Path "frontend/dist-consultation/$relative";if(-not(Test-Path $file)){throw "Current built asset not found: $relative"};$sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($ab)).ToLowerInvariant();if($sha -ne (Get-FileHash $file).Hash.ToLowerInvariant()){throw 'Resident asset differs from current product build'};$report.routes+=@{path=$asset;status=200;sha256=$sha;currentDistMatch=$true}}
      }
    }
    $expiredTask=(Get-Content (Join-Path $vault 'task-receipt-v752.json') -Raw|ConvertFrom-Json).taskId
    foreach($probe in @(@{path="/v2/consultation/tasks/$expiredTask/retrieval-workspace";status=403},@{path='/v2/consultation/tasks/00000000-0000-4000-8000-000000000001/retrieval-workspace';status=404})) {
      $r=$http.GetAsync($base+$probe.path).GetAwaiter().GetResult();if([int]$r.StatusCode -ne $probe.status){throw "Expected denial mismatch $($probe.path): $([int]$r.StatusCode)"};$report.routes+=@{path=$probe.path;status=[int]$r.StatusCode;expectedDenial=$true}
    }
    $report|ConvertTo-Json -Depth 12|Set-Content 'docs/evidence/consultation-agent-native-20261006/https-pkce-admission-probes.json'
    Write-Output 'REAL_TRUSTED_TLS_PRODUCT_LOGIN_PKCE_AND_RESIDENT_ASSET_VERIFIED_NO_NEW_TASK_OR_SOURCE'
}finally{$http.Dispose();$password=$null;$token=$null}
