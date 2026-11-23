# Builds the TapDrop test app without Gradle and installs it on the watch.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 [-NoInstall] [-Debuggable]
#   -Debuggable  a build the tests in tests\ can drive (they read and write its
#                settings); it is not copied into skill\
param([switch]$NoInstall, [switch]$Debuggable)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root       = $PSScriptRoot
$sdk        = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$bt         = Join-Path $sdk 'build-tools\35.0.0'
$platform   = Join-Path $sdk 'platforms\android-35'
$androidJar = Join-Path $platform 'android.jar'
$adb        = Join-Path $sdk 'platform-tools\adb.exe'

# d8 and apksigner need Java 11 or newer, and the java on PATH is often version 8.
# Use JAVA_HOME when it is new enough, else the newest JDK in the usual install folders.
function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($pattern in 'C:\Program Files\Eclipse Adoptium\jdk-*', 'C:\Program Files\Java\jdk-*',
                         'C:\Program Files\Microsoft\jdk-*', 'C:\Program Files\Zulu\zulu-*',
                         'C:\Program Files\Android\Android Studio\jbr') {
        $candidates += Get-Item $pattern -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName }
    }
    foreach ($dir in $candidates) {
        $release = Join-Path $dir 'release'
        if (-not (Test-Path (Join-Path $dir 'bin\javac.exe')) -or -not (Test-Path $release)) { continue }
        $line = Get-Content $release | Select-String 'JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($line -and [int]$line.Matches[0].Groups[1].Value -ge 11) { return $dir }
    }
    return $null
}
$jdk = Find-Jdk
if (-not $jdk) { throw 'No JDK 11 or newer found. Install one, for example Eclipse Temurin 17, or set JAVA_HOME.' }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

function Assert-Ok($step) { if ($LASTEXITCODE -ne 0) { throw "$step failed with exit code $LASTEXITCODE" } }

# Fetches one SDK package from Google and checks it against the SHA-1 in Google's index.
function Get-SdkPackage($file, $sha1, $target) {
    if (Test-Path $target) { return }
    $dl = Join-Path $env:TEMP ("sdk-dl-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $dl | Out-Null
    $zip = Join-Path $dl $file
    Write-Host "Downloading $file from dl.google.com ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/$file" -OutFile $zip -UseBasicParsing
    $got = (Get-FileHash $zip -Algorithm SHA1).Hash.ToLower()
    if ($got -ne $sha1) { throw "Checksum mismatch for $file (got $got)" }
    $x = Join-Path $dl 'x'
    Expand-Archive -Path $zip -DestinationPath $x
    $top = Get-ChildItem $x -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    Move-Item $top.FullName $target
    Remove-Item $dl -Recurse -Force
    Write-Host "Installed $target" -ForegroundColor Green
}

Get-SdkPackage 'build-tools_r35_windows.zip' 'af059bb67cf7786f45ee0db85e2d24985df1b4b6' $bt
Get-SdkPackage 'platform-35_r02.zip'         '0bb560a90a7a2cbd0dd8348224d518b638fe7949' $platform

# Fetches the libraries in libraries.txt into .libs, checked against their SHA-256,
# and keeps the class jar of each (an .aar holds it as classes.jar).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$libDir = Join-Path $root '.libs'
$libs = @()
foreach ($line in Get-Content (Join-Path $root 'libraries.txt')) {
    if ($line -match '^\s*(#|$)') { continue }
    $url, $sha256 = -split $line
    $name = Split-Path $url -Leaf
    $jar = Join-Path $libDir ([IO.Path]::GetFileNameWithoutExtension($name) + '.jar')
    if (-not (Test-Path $jar)) {
        New-Item -ItemType Directory -Force -Path $libDir | Out-Null
        $file = Join-Path $env:TEMP ("lib-" + [guid]::NewGuid().ToString('N').Substring(0, 8) + "-$name")
        Write-Host "Downloading $name ..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
        $got = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
        if ($got -ne $sha256) { Remove-Item $file; throw "Checksum mismatch for $name (got $got)" }
        if ($name.EndsWith('.aar')) {
            $zip = [IO.Compression.ZipFile]::OpenRead($file)
            try {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), "$jar.part", $true)
            } finally { $zip.Dispose() }
        } else {
            Copy-Item $file "$jar.part"
        }
        Move-Item "$jar.part" $jar -Force
        Remove-Item $file
    }
    $libs += $jar
}
$classpath = (@($androidJar) + $libs) -join ';'

$b = Join-Path $root 'build'
if (Test-Path $b) { Remove-Item $b -Recurse -Force }
foreach ($d in 'gen', 'classes', 'dex') { New-Item -ItemType Directory -Force -Path (Join-Path $b $d) | Out-Null }

Write-Host "Compiling resources..." -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$b\res.zip"; Assert-Ok 'aapt2 compile'
$debugMode = @(); if ($Debuggable) { $debugMode = @('--debug-mode') }
& "$bt\aapt2.exe" link -o "$b\base.apk" -I $androidJar --manifest "$root\AndroidManifest.xml" @debugMode `
    --java "$b\gen" --proguard "$b\aapt-rules.pro" --min-sdk-version 30 --target-sdk-version 34 "$b\res.zip"; Assert-Ok 'aapt2 link'

Write-Host "Compiling Java..." -ForegroundColor Cyan
$sources = @(Get-ChildItem "$root\src", "$b\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" --release 11 -encoding UTF-8 -classpath $classpath -d "$b\classes" $sources; Assert-Ok 'javac'

Write-Host "Shrinking and converting to dex..." -ForegroundColor Cyan
& "$jdk\bin\jar.exe" cf "$b\classes.jar" -C "$b\classes" .; Assert-Ok 'jar'
# R8 keeps only the code reachable from the manifest's components (the rules aapt2
# wrote) and proguard.pro. Its warnings about Kotlin metadata are expected, so its
# output goes to build\r8.log and is shown only if it fails.
$ErrorActionPreference = 'Continue'     # else Windows PowerShell stops on R8's first warning
& "$jdk\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.R8 --release --min-api 30 --lib $androidJar `
    --pg-conf "$b\aapt-rules.pro" --pg-conf "$root\proguard.pro" --output "$b\dex" `
    "$b\classes.jar" $libs *> "$b\r8.log"
$r8Exit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($r8Exit -ne 0) { Get-Content "$b\r8.log" | Write-Host; throw "R8 failed with exit code $r8Exit" }

Write-Host "Packaging and signing..." -ForegroundColor Cyan
Copy-Item "$b\base.apk" "$b\unsigned.apk"
Push-Location "$b\dex"
try { & "$jdk\bin\jar.exe" uf "$b\unsigned.apk" classes.dex; Assert-Ok 'add dex' } finally { Pop-Location }
& "$bt\zipalign.exe" -f -p 4 "$b\unsigned.apk" "$b\aligned.apk"; Assert-Ok 'zipalign'

# A throwaway debug key, kept beside the source so reinstalls keep the same signature.
$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'; Assert-Ok 'keytool'
}
$apk = Join-Path $b 'tapdrop.apk'
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $apk "$b\aligned.apk"; Assert-Ok 'apksigner'
Write-Host "Built $apk" -ForegroundColor Green

# Keep the copies bundled with the shareable skill in step with the source.
$repo = Split-Path $root
$skill = Join-Path $repo 'skill\tapdrop-watch'
if ((Test-Path $skill) -and -not $Debuggable) {
    $assets = Join-Path $skill 'assets'
    New-Item -ItemType Directory -Force -Path $assets | Out-Null
    Copy-Item $apk (Join-Path $assets 'tapdrop.apk') -Force
    Copy-Item (Join-Path $repo 'web\pay.html') (Join-Path $assets 'pay.html') -Force
    Write-Host "Updated skill assets" -ForegroundColor Green
}

if ($NoInstall) { return }

# Pick the watch by hardware type, so a phone connected at the same time is never the target.
$dev = $null
foreach ($l in (& $adb devices | Select-String "`tdevice$")) {
    $serial = ($l.Line -split "`t")[0]
    $kind = (& $adb -s $serial shell getprop ro.build.characteristics) -join ''
    if ($kind -match 'watch') { $dev = $serial; break }
}
if (-not $dev) { throw 'No watch connected over adb. Run tools\reconnect-watch.ps1, then run this again.' }
Write-Host "Installing on $dev ..." -ForegroundColor Cyan
& $adb -s $dev install -r $apk; Assert-Ok 'adb install'
& $adb -s $dev shell am start -n dev.tapdrop/.MainActivity; Assert-Ok 'launch'
Write-Host "TapDrop is open on the watch." -ForegroundColor Green
