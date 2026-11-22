param(
    [string]$OutputPath = (Join-Path $HOME "Downloads\shizuku-diagnostics-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt")
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}
Get-Command adb -ErrorAction Stop | Out-Null
$state = (& adb get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
    throw "ADB ist nicht mit genau einem autorisierten Geraet verbunden: $state"
}

$report = [System.Collections.Generic.List[string]]::new()
$report.Add("Shizuku discovery diagnostics v2 - $(Get-Date -Format 'o')")

function Add-AdbSection {
    param([string]$Title, [string[]]$Arguments)
    $report.Add("")
    $report.Add("=== $Title ===")
    $lines = & adb @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    foreach ($line in $lines) { $report.Add([string]$line) }
    if ($exitCode -ne 0) { $report.Add("ADB exit code: $exitCode") }
}

foreach ($property in @(
    'ro.product.manufacturer', 'ro.product.model',
    'ro.build.version.release', 'ro.build.version.sdk',
    'ro.build.version.preview_sdk', 'ro.build.version.codename',
    'ro.build.version.oneui', 'ro.build.version.incremental'
)) {
    Add-AdbSection -Title $property -Arguments @('shell', 'getprop', $property)
}
Add-AdbSection -Title 'Current user' -Arguments @('shell', 'am', 'get-current-user')
Add-AdbSection -Title 'Users' -Arguments @('shell', 'pm', 'list', 'users')
$report.Add("")
$report.Add('=== Shizuku processes ===')
& adb shell ps -A | Select-String 'shizuku' | ForEach-Object { $report.Add($_.Line) }

foreach ($package in @('moe.shizuku.privileged.api', 'in.hridayan.ashell', 'dev.shizzi', 'dev.shizzi.localdns')) {
    $report.Add("")
    $report.Add("=== Package $package ===")
    & adb shell dumpsys package $package |
        Select-String 'versionCode=|versionName=|User \d+:|moe\.shizuku\.manager\.permission\.API_V23' |
        ForEach-Object { $report.Add($_.Line) }
}

Add-AdbSection -Title 'Discovery and Shizuku logs (all retained matching entries)' -Arguments @(
    'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-d', '-v', 'threadtime',
    'ShizukuDiscovery:V', 'Service:V', 'Shizuku:V', 'ShizukuApplication:V',
    'ShizukuProvider:V', 'BinderSender:V', 'ShizukuServer:V', 'AndroidRuntime:E', '*:S'
)

$parentDirectory = Split-Path -Parent $OutputPath
if ($parentDirectory) { New-Item -ItemType Directory -Force -Path $parentDirectory | Out-Null }
[System.IO.File]::WriteAllLines($OutputPath, $report, [System.Text.UTF8Encoding]::new($false))
Write-Host "Diagnose gespeichert: $OutputPath"
if (-not ($report | Select-String 'android17-discovery-v2' -Quiet)) {
    Write-Warning 'Kein neuer Server-Marker gefunden. Shizuku stoppen/starten, App-Verwaltung oeffnen und erneut sammeln.'
}
