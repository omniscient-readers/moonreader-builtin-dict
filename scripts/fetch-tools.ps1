# Downloads the jar toolchain into tools/ so build.ps1 has everything it needs.
#
#   .\scripts\fetch-tools.ps1
#   .\scripts\fetch-tools.ps1 -EcjVersion 3.42.0 -ApktoolVersion 2.11.1
#   .\scripts\fetch-tools.ps1 -Mirror "https://maven.aliyun.com/repository/central"
#
# Everything is placed in tools/ and is gitignored - no binaries live in the repo.
# If a download is blocked on your network, fetch the same file in a browser and
# drop it into tools/ with the exact name listed below; the script skips whatever
# is already present.

[CmdletBinding()]
param(
    [string]$ApktoolVersion = '2.11.1',
    [string]$EcjVersion = '3.42.0',
    [string]$SignerVersion = '1.3.0',
    [string]$R8Version = '8.11.18',
    [string]$AndroidPlatform = 'android-34',
    [string]$Mirror = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$tools = Join-Path (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)) 'tools'
New-Item -ItemType Directory -Force -Path $tools | Out-Null

function Get-Mirrored([string]$centralPath, [string]$googlePath) {
    $urls = @()
    if ($Mirror) {
        if ($centralPath) { $urls += ($Mirror.TrimEnd('/') + '/' + $centralPath) }
        if ($googlePath) { $urls += ($Mirror.TrimEnd('/') + '/' + $googlePath) }
    }
    if ($centralPath) { $urls += ('https://repo1.maven.org/maven2/' + $centralPath) }
    if ($googlePath) { $urls += ('https://maven.google.com/' + $googlePath) }
    if ($googlePath) { $urls += ('https://maven.aliyun.com/repository/google/' + $googlePath) }
    return $urls
}

# name -> candidate urls, in priority order
$jobs = [ordered]@{}

$jobs['apktool.jar'] = @(
    "https://github.com/iBotPeaches/Apktool/releases/download/v$ApktoolVersion/apktool_$ApktoolVersion.jar",
    "https://ghproxy.net/https://github.com/iBotPeaches/Apktool/releases/download/v$ApktoolVersion/apktool_$ApktoolVersion.jar"
)
$jobs['ecj.jar'] = Get-Mirrored "org/eclipse/jdt/ecj/$EcjVersion/ecj-$EcjVersion.jar" ''
$jobs['r8.jar'] = Get-Mirrored '' "com/android/tools/r8/$R8Version/r8-$R8Version.jar"
$jobs['uber-apk-signer.jar'] = @(
    "https://github.com/patrickfav/uber-apk-signer/releases/download/v$SignerVersion/uber-apk-signer-$SignerVersion.jar"
)
$jobs['android.jar'] = @(
    "https://raw.githubusercontent.com/Sable/android-platforms/master/$AndroidPlatform/android.jar",
    "https://ghproxy.net/https://raw.githubusercontent.com/Sable/android-platforms/master/$AndroidPlatform/android.jar"
)

$failed = @()
foreach ($name in $jobs.Keys) {
    $dest = Join-Path $tools $name
    if ((Test-Path -LiteralPath $dest) -and -not $Force) {
        Write-Host ("skip  {0}  (already present, {1:N1} MB)" -f $name, ((Get-Item -LiteralPath $dest).Length / 1MB))
        continue
    }
    $ok = $false
    foreach ($url in $jobs[$name]) {
        try {
            Write-Host ("get   {0}  <- {1}" -f $name, $url)
            Invoke-WebRequest -Uri $url -OutFile $dest -TimeoutSec 300 -MaximumRedirection 10
            $size = (Get-Item -LiteralPath $dest).Length
            if ($size -lt 1024) { throw "suspiciously small ($size bytes)" }
            Write-Host ("ok    {0}  {1:N1} MB" -f $name, ($size / 1MB)) -ForegroundColor Green
            $ok = $true
            break
        } catch {
            Write-Host ("      failed: {0}" -f $_.Exception.Message) -ForegroundColor DarkYellow
            if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force -ErrorAction SilentlyContinue }
        }
    }
    if (-not $ok) { $failed += $name }
}

Write-Host ''
if ($failed.Count) {
    Write-Host "Could not download: $($failed -join ', ')" -ForegroundColor Yellow
    Write-Host "Download these by hand into $tools :" -ForegroundColor Yellow
    foreach ($name in $failed) { Write-Host ("  {0,-22} one of:" -f $name); foreach ($u in $jobs[$name]) { Write-Host "      $u" } }
    Write-Host ''
    Write-Host 'apktool/uber-apk-signer may also be installed system-wide instead:' -ForegroundColor Yellow
    Write-Host '  apktool:          https://apktool.org  (then pass -Apktool to apktool, or unzip its jars into tools/)'
    Write-Host '  uber-apk-signer:  https://github.com/patrickfav/uber-apk-signer/releases'
    exit 1
}
Write-Host 'toolchain complete.' -ForegroundColor Green
Write-Host 'next: .\scripts\gen-keystore.ps1   then   .\build.ps1 -BaseApk <your stock apk>'
