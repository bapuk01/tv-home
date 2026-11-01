# Windows: build the launcher, install it and make it the home screen (Google TV).
# Disables the stock launcher and the setup app, which otherwise takes over the Home button.
#   powershell -ExecutionPolicy Bypass -File tools\windows\install-launcher.ps1
param(
    [string]$Adb = "adb",
    [string]$Device = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
& powershell -ExecutionPolicy Bypass -File "$root\launcher\build.ps1"
if ($LASTEXITCODE) { throw "build failed" }
$s = if ($Device) { @("-s", $Device) } else { @() }
& $Adb @s install -r "$root\launcher\build\tvhome.apk"
foreach ($cmd in @(
    "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION",
    "pm grant com.mihir.tvhome android.permission.ACCESS_COARSE_LOCATION",
    "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity",
    "pm disable-user --user 0 com.google.android.apps.tv.launcherx",
    "pm disable-user --user 0 com.google.android.tungsten.setupwraith",
    "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity",
    "input keyevent KEYCODE_HOME")) {
    & $Adb @s shell $cmd
}
"Installed. Press Home on the remote."
