# Windows: disable every package in a debloat list over ADB (one pm call per package).
# Packages are disabled for user 0, never uninstalled. Undo with tools\windows\restore.ps1 <same list>.
#   powershell -ExecutionPolicy Bypass -File tools\windows\disable.ps1 examples\chromecast-google-tv-2020\debloat.txt
param(
    [Parameter(Mandatory)][string]$List,
    [string]$Adb = "adb",     # path to adb.exe if it isn't on PATH
    [string]$Device = ""      # adb serial, when more than one device is connected
)
$s = if ($Device) { @("-s", $Device) } else { @() }
foreach ($line in Get-Content $List) {
    $pkg = ($line -replace "#.*", "").Trim()
    if (-not $pkg) { continue }
    $out = & $Adb @s shell pm disable-user --user 0 $pkg 2>&1 | Out-String
    if ($out -match "disabled-user") { "OK    $pkg" } else { "SKIP  $pkg : $($out.Trim())" }
}
