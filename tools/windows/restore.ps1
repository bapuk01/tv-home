# Windows: re-enable everything in a debloat list, plus the stock Google TV launcher and setup app
# that the launcher install disables. Also resets animation speed to normal.
#   powershell -ExecutionPolicy Bypass -File tools\windows\restore.ps1 examples\chromecast-google-tv-2020\debloat.txt
param(
    [Parameter(Mandatory)][string]$List,
    [string]$Adb = "adb",
    [string]$Device = ""
)
$s = if ($Device) { @("-s", $Device) } else { @() }
$pkgs = Get-Content $List | ForEach-Object { ($_ -replace "#.*", "").Trim() } | Where-Object { $_ }
$pkgs += "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith"
foreach ($pkg in $pkgs) {
    & $Adb @s shell pm enable --user 0 $pkg
}
foreach ($k in "window_animation_scale", "transition_animation_scale", "animator_duration_scale") {
    & $Adb @s shell settings put global $k 1.0
}
