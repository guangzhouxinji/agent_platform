[CmdletBinding()]
param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [string]$Dm8Url = "jdbc:dm://127.0.0.1:5237",
    [string]$Dm8User = "ADMIN",
    [SecureString]$Dm8Password,
    [bool]$ModelRuntimeEnabled = $true,
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$jar = Join-Path $workspace "backend\apps\platform-api\target\agent-platform-api-0.0.1-SNAPSHOT.jar"
$runtimeDirectory = Join-Path $workspace "target\runtime"

function Get-Listener([int]$Port) {
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Wait-ForHttp([string]$Url, [System.Diagnostics.Process]$Process, [int]$TimeoutSeconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            throw "进程启动失败，退出码 $($Process.ExitCode)。请检查 target/runtime 中的日志。"
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -SkipHttpErrorCheck -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch { }
        Start-Sleep -Milliseconds 350
    }
    throw "等待服务超时：$Url。请检查 target/runtime 中的日志。"
}

function Get-EnvironmentSetting([string]$Name) {
    foreach ($scope in @("Process", "User", "Machine")) {
        $value = [Environment]::GetEnvironmentVariable($Name, $scope)
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    return $null
}

function Resolve-JavaExecutable {
    $candidates = @()
    $javaHome = Get-EnvironmentSetting "JAVA_HOME"
    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        $candidates += Join-Path $javaHome "bin\java.exe"
    }
    $pathJava = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($pathJava) { $candidates += $pathJava.Source }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path $candidate)) { continue }
        $versionOutput = (& $candidate -version 2>&1 | Select-Object -First 1) -join ""
        if ($versionOutput -match 'version "(?<major>\d+)') {
            if ([int]$Matches.major -ge 21) { return $candidate }
        }
    }
    throw "未找到 Java 21 或更高版本。请将 JAVA_HOME 指向项目使用的 JDK 21。"
}

if (-not (Test-Path $runtimeDirectory)) {
    New-Item -ItemType Directory -Path $runtimeDirectory | Out-Null
}

if ($Build -or -not (Test-Path $jar)) {
    Push-Location $workspace
    try {
        & mvn.cmd -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "后端构建失败。" }
        & npm.cmd --prefix frontend run build
        if ($LASTEXITCODE -ne 0) { throw "前端构建失败。" }
    } finally {
        Pop-Location
    }
}

$masterKey = Get-EnvironmentSetting "MODEL_CONFIG_MASTER_KEY"
if ([string]::IsNullOrWhiteSpace($masterKey)) {
    throw "未配置 MODEL_CONFIG_MASTER_KEY。请先在 Windows 用户环境中配置与现有模型密钥一致的主密钥。"
}
$javaExecutable = Resolve-JavaExecutable

$backendListener = Get-Listener $BackendPort
if (-not $backendListener) {
    $plainPassword = Get-EnvironmentSetting "DM8_JDBC_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        if (-not $Dm8Password) { $Dm8Password = Read-Host "请输入本地 DM8 密码" -AsSecureString }
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Dm8Password)
        try { $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
    $env:SERVER_PORT = [string]$BackendPort
    $env:SPRING_DATASOURCE_URL = $Dm8Url
    $env:SPRING_DATASOURCE_USERNAME = $Dm8User
    $env:SPRING_DATASOURCE_PASSWORD = $plainPassword
    $env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = "dm.jdbc.driver.DmDriver"
    $env:CONSULTATION_STORAGE = "dm8"
    $env:PATENT_SEARCH_STORAGE = "dm8"
    $env:TRADEMARK_SEARCH_STORAGE = "dm8"
    $env:KNOWLEDGE_BASE_PROVIDER = "dm8"
    $env:KNOWLEDGE_BASE_IMPORT_ENABLED = "false"
    $env:CONSULTATION_SEARCH_PROVIDER = "task"
    $env:CONSULTATION_MODEL_RUNTIME_ENABLED = $ModelRuntimeEnabled.ToString().ToLowerInvariant()
    $env:MODEL_CONFIG_MASTER_KEY = $masterKey

    $backend = Start-Process -FilePath $javaExecutable -ArgumentList @("-jar", $jar) -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimeDirectory "consultation-backend.out.log") `
        -RedirectStandardError (Join-Path $runtimeDirectory "consultation-backend.err.log")
    Wait-ForHttp "http://127.0.0.1:$BackendPort/" $backend
    $plainPassword = $null
}

$frontendListener = Get-Listener $FrontendPort
if (-not $frontendListener) {
    $frontend = Start-Process -FilePath "npm.cmd" -ArgumentList @("--prefix", "frontend", "run", "dev", "--", "--host", "127.0.0.1", "--port", [string]$FrontendPort) `
        -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimeDirectory "consultation-frontend.out.log") `
        -RedirectStandardError (Join-Path $runtimeDirectory "consultation-frontend.err.log")
    Wait-ForHttp "http://127.0.0.1:$FrontendPort/app/consultation" $frontend
}

Write-Host "咨询智能体已就绪：http://127.0.0.1:$FrontendPort/app/consultation"
Write-Host "模型配置：http://127.0.0.1:$FrontendPort/app/consultation/agent-configuration"
