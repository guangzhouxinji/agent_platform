param([int] $Generation = 2026100603)
$ErrorActionPreference = 'Stop'
. './docs/evidence/consultation-agent-window03-20261006/approved-http.ps1'
$resourceCutoff=[DateTimeOffset](Get-Content (Join-Path $out 'authorized-resource-window.json') -Raw|ConvertFrom-Json).taskSourceWorkloadExpiry
Assert-ApprovedTime
$suffix = if ($Generation -eq 1) { '' } else { "-v$Generation" }
$certPath = Join-Path $vault "worker$suffix.p12"
$secretPath = Join-Path $vault "worker-certificate$suffix.credential.xml"
if (Test-Path $certPath) { throw 'Worker certificate already exists' }
$root = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
    (Join-Path $vault 'root-ca.p12'), (Read-ProductSecret 'certificate-store.credential.xml'),
    [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable)
$key = [Security.Cryptography.RSA]::Create(3072)
try {
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
        "CN=consultation-worker-20260929$suffix", $key,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $request.CertificateExtensions.Add(
        [Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new(
            [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature, $true))
    $uses = [Security.Cryptography.OidCollection]::new()
    [void] $uses.Add([Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.2'))
    $request.CertificateExtensions.Add(
        [Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($uses, $true))
    $serial = [Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $signed = $request.Create($root, [DateTimeOffset]::UtcNow,
        $resourceCutoff, $serial)
    $worker = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::CopyWithPrivateKey(
        $signed, $key)
    $password = [Convert]::ToBase64String(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    try {
        [IO.File]::WriteAllBytes($certPath,
            $worker.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12,
                $password))
        (ConvertTo-SecureString $password -AsPlainText -Force) | Export-Clixml $secretPath
        $fingerprint = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($worker.RawData)).ToLowerInvariant()
        $http = New-ProductHttp
        try {
            $registration = Approved-Send $http 'POST' '/v2/identity/workloads' @{
                certificateSha256 = $fingerprint
                kind = 'AGENT'
                expiresAt = $resourceCutoff.ToString('o')
            } 'new-worker-registration'
            $registration | ConvertTo-Json -Compress |
                Set-Content (Join-Path $vault "worker-registration$suffix.json")
            $task=Get-Content (Join-Path $out 'new-task.json') -Raw|ConvertFrom-Json
            $grant=Approved-Send $http POST "/v2/identity/workloads/$($registration.subjectId)/task-grants" @{taskId=$task.taskId;expiresAt=$resourceCutoff.ToString('o');expectedEpoch=0} 'new-worker-task-grant'
            Save-ApprovedTrace 'worker-initialization'
            Write-Output "worker_subject=$($registration.subjectId) newRegistration=true fixedExpiry=$($resourceCutoff.ToString('o'))"
        } finally { $http.Dispose() }
    } finally { $password = $null }
} finally { $key.Dispose(); $root.Dispose() }
