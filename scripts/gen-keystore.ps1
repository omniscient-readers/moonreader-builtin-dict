# Creates the signing keystore that build.ps1 uses.
#
#   .\scripts\gen-keystore.ps1                       -> .keys\ (gitignored)
#   .\scripts\gen-keystore.ps1 -OutDir D:\my\keys
#   .\scripts\gen-keystore.ps1 -Reuse D:\my\keys     # just check what is there
#
# This is a private key and it is gitignored for that reason. Android only
# installs an update when the signature matches what is already on the device,
# so keep using the same keystore for every build of the same app. When the
# stock apk is already installed, uninstall it first, or change the
# applicationId so both can coexist.

[CmdletBinding()]
param(
    [string]$OutDir = '',
    [string]$Alias = 'mrdict',
    [string]$DName = 'CN=MoonReader Builtin Dict Mod, OU=personal, O=personal, C=CN',
    [int]$ValidityDays = 10950,
    [switch]$Reuse,
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $OutDir) { $OutDir = Join-Path $root '.keys' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$ks = Join-Path $OutDir 'mrdict.keystore'
$passFile = Join-Path $OutDir 'keystore.pass'

$keytool = if ($JavaHome) { Join-Path $JavaHome 'bin\keytool.exe' }
elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
else { 'keytool' }

if (Test-Path -LiteralPath $ks) {
    Write-Host "keystore already exists: $ks" -ForegroundColor Yellow
    Write-Host 'Reusing it (this is what you normally want for repeat builds).'
    if (Test-Path -LiteralPath $passFile) {
        Write-Host 'password file: present'
    } else {
        Write-Host "password file missing - build.ps1 will prompt for the password" -ForegroundColor Yellow
    }
    return
}
if ($Reuse) { throw "no keystore at $ks" }

# 24 random characters, drawn with a cryptographic RNG
$alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$bytes = New-Object 'byte[]' 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pass = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })

Write-Host "generating $ks"
& $keytool -genkeypair -v `
    -keystore $ks `
    -alias $Alias `
    -keyalg RSA -keysize 2048 -validity $ValidityDays `
    -storetype PKCS12 `
    -storepass $pass -keypass $pass `
    -dname $DName
if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }

Set-Content -LiteralPath $passFile -Value $pass -NoNewline -Encoding ASCII

# make sure git can never see either file, whatever the ignore rules say
$exclude = Join-Path (Join-Path $root '.git') 'info\exclude'
if (Test-Path -LiteralPath (Join-Path $root '.git')) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $exclude) | Out-Null
    $lines = if (Test-Path -LiteralPath $exclude) { Get-Content -LiteralPath $exclude } else { @() }
    if ($lines -notcontains '.keys/') {
        Add-Content -LiteralPath $exclude -Value '.keys/'
        Add-Content -LiteralPath $exclude -Value '*.keystore'
    }
}

Write-Host ''
Write-Host "alias       : $Alias"
Write-Host "keystore    : $ks"
Write-Host "password    : $passFile  (gitignored - do not commit)"
Write-Host "fingerprint : running keytool -list for your records:"
& $keytool -list -v -keystore $ks -storepass $pass -alias $Alias | Select-String -Pattern 'SHA256:|Valid from:|Alias name:'
Write-Host ''
Write-Host 'Back this keystore up somewhere private; losing it means you cannot ship updates.' -ForegroundColor Green
