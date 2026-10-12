<#
    build.ps1 - one-command build of the built-in dictionary mod

    Run from the repository root:

        .\build.ps1 -BaseApk "D:\path\to\stock.apk"

    Optional:

        -VersionCode 909005
        -VersionName "9.9-mrdict4"
        -KeysDir     "D:\keys"      holds mrdict.keystore + keystore.pass
        -SkipSign                   stop after merged.apk, do not sign
        -MinApi      21             must be <= the target minSdkVersion
        -JavaHome    "C:\jdk-17"    java to use, else JAVA_HOME or PATH

    The manual step-by-step equivalent is in docs/BUILD.md.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseApk,
    [int]$VersionCode = 909005,
    [string]$VersionName = '9.9-mrdict4',
    [string]$KeysDir = '',
    [string]$JavaHome = '',
    [int]$MinApi = 21,
    [switch]$SkipSign
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = Join-Path $root 'tools'
$work = Join-Path $root 'build'
$base = Join-Path $work 'base'
$dexDir = Join-Path $work 'dex'
$classes = Join-Path $work 'classes'
$outDir = Join-Path $work 'out'

function Step([string]$m) { Write-Host ''; Write-Host "==> $m" -ForegroundColor Cyan }
function Need([string]$p, [string]$hint) {
    if (-not (Test-Path -LiteralPath $p)) { throw "missing $p - $hint" }
}

# ------------------------------------------------------------------ toolchain
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' }
elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' }
else { 'java' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) {
    throw "java not runnable ($java); pass -JavaHome or set JAVA_HOME"
}
# java -version writes to stderr, which $ErrorActionPreference='Stop' turns
# into a terminating error, so relax it for this probe; check the text, not $LASTEXITCODE
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = (& $java -version 2>&1 | Out-String)
$ErrorActionPreference = $previousEap
if ($javaVersion -notmatch 'version') {
    throw "java not runnable ($java); pass -JavaHome or set JAVA_HOME"
}
Write-Host "java: $(($javaVersion -split "`n")[0].Trim())"

$apktool = Join-Path $tools 'apktool.jar'
$ecj = Join-Path $tools 'ecj.jar'
$r8 = Join-Path $tools 'r8.jar'
$signer = Join-Path $tools 'uber-apk-signer.jar'
$androidJar = Join-Path $tools 'android.jar'
foreach ($t in @($apktool, $ecj, $r8, $androidJar)) { Need $t 'run scripts\fetch-tools.py first' }
Need $BaseApk 'point -BaseApk at your own copy of the stock apk'

New-Item -ItemType Directory -Force -Path $work, $dexDir, $classes, $outDir | Out-Null
$jobs = [Math]::Max(1, [Environment]::ProcessorCount)

# ------------------------------------------------- 1. decompile the stock apk
Step '1/7 decompile stock apk'
if (Test-Path -LiteralPath $base) { Remove-Item -LiteralPath $base -Recurse -Force }
& $java -jar $apktool d -f -j $jobs -o $base $BaseApk
if ($LASTEXITCODE -ne 0) { throw "apktool d failed ($LASTEXITCODE)" }

# ------------------------------------- 2. patch the tree (manifest, smali, ver)
Step '2/7 apply mod patches'
$dictSrc = Join-Path $root 'assets'
$patchArgs = @((Join-Path $root 'scripts\apply_mod.py'), '--tree', $base,
    '--version-code', $VersionCode, '--version-name', $VersionName)
$wordlists = @()
if (Test-Path -LiteralPath $dictSrc) {
    $wordlists = @(Get-ChildItem -LiteralPath $dictSrc -Filter *.txt -ErrorAction SilentlyContinue)
}
if ($wordlists.Count) {
    $patchArgs += @('--dict-src', $dictSrc)
    Write-Host ("embedding {0} wordlist(s): {1}" -f $wordlists.Count, (($wordlists | ForEach-Object { $_.Name }) -join ', '))
} else {
    Write-Host 'no wordlists in assets/ - it still builds, but the card only offers the Baike tab' -ForegroundColor Yellow
}
python @patchArgs
if ($LASTEXITCODE -ne 0) { throw "apply_mod.py failed ($LASTEXITCODE)" }

# ------------------------------------------------- 3. compile the mod sources
Step '3/7 compile mod java sources'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
# java/ already contains java/stubs/, so scan the parent once
$argfile = Join-Path $work 'ecj.args'
$argLines = @('-1.8', '-nowarn', '-encoding', 'UTF-8', '-d', $classes, '-classpath', "$androidJar;$classes")
$argLines += (Get-ChildItem -LiteralPath (Join-Path $root 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
# no BOM: ecj would treat it as part of the first option
[System.IO.File]::WriteAllLines($argfile, $argLines, (New-Object System.Text.UTF8Encoding($false)))
& $java -jar $ecj "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "ecj failed ($LASTEXITCODE)" }

# ------------------------------------------------------- 4. dex the mod classes
Step '4/7 dex (d8)'
$classJar = Join-Path $work 'classes.jar'
python (Join-Path $root 'scripts\pack_jar.py') --classes $classes --out $classJar
if ($LASTEXITCODE -ne 0) { throw "pack_jar.py failed ($LASTEXITCODE)" }

$dexOut = Join-Path $dexDir 'classes.dex'
if (Test-Path -LiteralPath $dexOut) { Remove-Item -LiteralPath $dexOut -Force }
# d8 takes files, not directories, so pack a jar first
& $java -cp $r8 com.android.tools.r8.D8 --min-api $MinApi --release --lib $androidJar --output $dexDir $classJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }
Need $dexOut 'd8 produced no classes.dex'

# ------------------------------------------- 5. rebuild the apk with apktool
Step '5/7 rebuild apk (apktool b)'
# -f matters: without it apktool only repacks what it thinks changed,
# so files added after the decompile (the copied wordlists) are dropped
& $java -jar $apktool b -f -j $jobs $base -o (Join-Path $work 'rebuilt.apk')
if ($LASTEXITCODE -ne 0) { throw "apktool b failed ($LASTEXITCODE)" }

# ------------------------------------ 6. splice classes8.dex into the archive
Step '6/7 add classes8.dex'
python (Join-Path $root 'scripts\inject_dex.py') `
    --apk (Join-Path $work 'rebuilt.apk') `
    --dex $dexOut `
    --out (Join-Path $work 'merged.apk')
if ($LASTEXITCODE -ne 0) { throw "inject_dex.py failed ($LASTEXITCODE)" }

if ($SkipSign) { Write-Host "`nmerged apk: $work\merged.apk (not signed)"; return }

# ----------------------------------------------------------- 7. align + sign
Step '7/7 zipalign + sign'
Need $signer 'run scripts\fetch-tools.py first'
if (-not $KeysDir) { $KeysDir = Join-Path $root '.keys' }
$ks = Join-Path $KeysDir 'mrdict.keystore'
$passFile = Join-Path $KeysDir 'keystore.pass'
if (-not (Test-Path -LiteralPath $ks)) { throw "keystore not found: $ks (run scripts\gen-keystore.ps1)" }
$alias = 'mrdict'
$pass = if (Test-Path -LiteralPath $passFile) { (Get-Content -LiteralPath $passFile -Raw).Trim() } else { '' }
$signArgs = @('-jar', $signer, '-a', (Join-Path $work 'merged.apk'), '-o', $outDir,
    '--ks', $ks, '--ksAlias', $alias)
if ($pass) { $signArgs += @('--ksPass', $pass, '--ksKeyPass', $pass) }
# -o and --overwrite are mutually exclusive in uber-apk-signer
& $java @signArgs
if ($LASTEXITCODE -ne 0) { throw "signing failed ($LASTEXITCODE)" }

Step 'done'
Get-ChildItem -LiteralPath $outDir | ForEach-Object { Write-Host ("  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
Write-Host ''
Write-Host 'check the result with: python scripts\verify_apk.py --apk <path>' -ForegroundColor Green
