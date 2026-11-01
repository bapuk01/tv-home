# Windows-версия сборки из build-and-install.sh (только сборка APK, без установки).
#   powershell -ExecutionPolicy Bypass -File launcher\build.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$BT  = "$SDK\build-tools\34.0.0"
$JAR = "$SDK\platforms\android-34\android.jar"
$B   = "build"
$JDK = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Java\jdk-17" }
$env:PATH = "$JDK\bin;$env:PATH"

function Run($exe) { & $exe @args; if ($LASTEXITCODE) { throw "$exe failed ($LASTEXITCODE)" } }

if (Test-Path $B) { Remove-Item $B -Recurse -Force }
New-Item -ItemType Directory "$B\gen", "$B\classes", "$B\dex" | Out-Null
Run "$BT\aapt2.exe" compile --dir res -o "$B\res.zip"
# Без -A: aapt2 на Windows пишет пути ассетов с "\", Android их не находит. Ассеты добавляются ниже.
Run "$BT\aapt2.exe" link -o "$B\base.apk" -I $JAR --manifest AndroidManifest.xml --java "$B\gen" `
    --min-sdk-version 30 --target-sdk-version 30 "$B\res.zip"
$srcs = Get-ChildItem src, "$B\gen" -Recurse -Filter *.java | ForEach-Object FullName
Run javac -nowarn "-Xlint:-options" -encoding UTF-8 --release 11 -classpath $JAR -d "$B\classes" @srcs
$classes = Get-ChildItem "$B\classes" -Recurse -Filter *.class | ForEach-Object FullName
Run "$BT\d8.bat" --min-api 30 --lib $JAR --output "$B\dex" @classes

Copy-Item "$B\base.apk" "$B\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Resolve-Path "$B\unsigned.apk"), "Update")
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path "$B\dex\classes.dex"), "classes.dex") | Out-Null
$root = (Resolve-Path .).Path + "\"
foreach ($f in Get-ChildItem assets -Recurse -File) {
    $name = $f.FullName.Substring($root.Length).Replace("\", "/")
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $name, [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
}
$zip.Dispose()

Run "$BT\zipalign.exe" -f 4 "$B\unsigned.apk" "$B\aligned.apk"
if (-not (Test-Path debug.keystore)) {
    Run keytool -genkeypair -keystore debug.keystore -storepass android -keypass android `
        -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome"
}
Run "$BT\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --out "$B\tvhome.apk" "$B\aligned.apk"
"built $B\tvhome.apk ($((Get-Item "$B\tvhome.apk").Length) bytes)"
