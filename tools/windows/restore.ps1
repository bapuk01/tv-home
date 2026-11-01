# Windows: undo everything — re-enable the packages in a debloat list, bring back the stock Google TV
# launcher (and the setup app the launcher install disables), make it the home screen again, remove
# the tv-home launcher and reset animation speed.
#   powershell -ExecutionPolicy Bypass -File tools\windows\restore.ps1 examples\chromecast-google-tv-2020\debloat.txt
#   -KeepLauncher  leave tv-home installed (it just stops being the home screen)
param(
    [Parameter(Mandatory)][string]$List,
    [string]$Adb = "adb",
    [string]$Device = "",
    [switch]$KeepLauncher
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
& $Adb @s shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
if (-not $KeepLauncher) { & $Adb @s uninstall com.mihir.tvhome }
& $Adb @s shell input keyevent KEYCODE_HOME
