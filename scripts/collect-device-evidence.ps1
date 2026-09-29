param(
    [Parameter(Mandatory = $true)]
    [string]$Label,
    [string]$OutputRoot = $(if ($env:SCAN_DEVICE_EVIDENCE_DIR) { $env:SCAN_DEVICE_EVIDENCE_DIR } else { "device-evidence" }),
    [string]$Serial,
    [string]$PackageName = "com.thiepn.scan"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Get-AdbPrefix {
    if ([string]::IsNullOrWhiteSpace($Serial)) { return @() }
    return @("-s", $Serial)
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $prefix = Get-AdbPrefix
    $output = & adb @prefix @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        $message = ($output -join [Environment]::NewLine)
        throw "adb failed ($code): $message"
    }
    return ($output -join [Environment]::NewLine).TrimEnd()
}

function Invoke-AdbBinaryToFile {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $allArgs = @()
    $allArgs += Get-AdbPrefix
    $allArgs += $Arguments

    $quoted = foreach ($arg in $allArgs) {
        if ($arg -match '[\s"]') {
            '"' + ($arg -replace '"', '\"') + '"'
        } else {
            $arg
        }
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Get-Command adb).Source
    $psi.Arguments = ($quoted -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi

    if (-not $process.Start()) {
        throw "Could not start adb."
    }

    $stream = [System.IO.File]::Create($Path)
    try {
        $process.StandardOutput.BaseStream.CopyTo($stream)
    }
    finally {
        $stream.Dispose()
    }

    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        Remove-Item -Force -ErrorAction SilentlyContinue $Path
        throw "adb binary capture failed ($($process.ExitCode)): $stderr"
    }
}

function Safe-Text {
    param([string]$Value)
    return ($Value -replace [char]13, "").Trim()
}

Require-Command "adb"

$stamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$safeLabel = ($Label -replace '[^A-Za-z0-9._-]+', '-').Trim("-")
if ([string]::IsNullOrWhiteSpace($safeLabel)) { $safeLabel = "checkpoint" }

$outputDir = Join-Path $OutputRoot "$safeLabel-$stamp"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

$prefix = Get-AdbPrefix
& adb @prefix wait-for-device
if ($LASTEXITCODE -ne 0) {
    throw "Could not reach the selected Android device."
}

$manufacturer = Safe-Text (Invoke-AdbText @("shell", "getprop", "ro.product.manufacturer"))
$model = Safe-Text (Invoke-AdbText @("shell", "getprop", "ro.product.model"))
$device = Safe-Text (Invoke-AdbText @("shell", "getprop", "ro.product.device"))
$androidRelease = Safe-Text (Invoke-AdbText @("shell", "getprop", "ro.build.version.release"))
$sdk = Safe-Text (Invoke-AdbText @("shell", "getprop", "ro.build.version.sdk"))
$fingerprint = Safe-Text (Invoke-AdbText @("shell", "getprop", "ro.build.fingerprint"))
$fontScale = Safe-Text (Invoke-AdbText @("shell", "settings", "get", "system", "font_scale") -AllowFailure)
$accessibilityEnabled = Safe-Text (Invoke-AdbText @("shell", "settings", "get", "secure", "accessibility_enabled") -AllowFailure)
$accessibilityServices = Safe-Text (Invoke-AdbText @("shell", "settings", "get", "secure", "enabled_accessibility_services") -AllowFailure)

$displaySize = Invoke-AdbText @("shell", "wm", "size") -AllowFailure
$displayDensity = Invoke-AdbText @("shell", "wm", "density") -AllowFailure
$memoryLines = (Invoke-AdbText @("shell", "cat", "/proc/meminfo") -AllowFailure) -split [Environment]::NewLine | Select-Object -First 8
$storage = Invoke-AdbText @("shell", "df", "-h", "/data") -AllowFailure
$packageDump = Invoke-AdbText @("shell", "dumpsys", "package", $PackageName) -AllowFailure
$packageSummary = (($packageDump -split [Environment]::NewLine) | Where-Object {
    $_ -match 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime='
}) -join [Environment]::NewLine

$deviceText = @(
    "captured_at_utc=$stamp",
    "label=$Label",
    "serial=$Serial",
    "manufacturer=$manufacturer",
    "model=$model",
    "device=$device",
    "android_release=$androidRelease",
    "sdk=$sdk",
    "build_fingerprint=$fingerprint",
    "font_scale=$fontScale",
    "accessibility_enabled=$accessibilityEnabled",
    "enabled_accessibility_services=$accessibilityServices",
    "",
    "=== display ===",
    $displaySize,
    $displayDensity,
    "",
    "=== memory ===",
    ($memoryLines -join [Environment]::NewLine),
    "",
    "=== storage ===",
    $storage,
    "",
    "=== package ===",
    $packageSummary
) -join [Environment]::NewLine

Set-Content -Path (Join-Path $outputDir "device.txt") -Value $deviceText -Encoding UTF8

$deviceJson = [ordered]@{
    captured_at_utc = $stamp
    label = $Label
    serial = $Serial
    manufacturer = $manufacturer
    model = $model
    device = $device
    android_release = $androidRelease
    sdk = $sdk
    build_fingerprint = $fingerprint
    font_scale = $fontScale
    accessibility_enabled = $accessibilityEnabled
    enabled_accessibility_services = $accessibilityServices
}
$deviceJson | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $outputDir "device.json") -Encoding UTF8

try {
    Invoke-AdbBinaryToFile -Arguments @("exec-out", "screencap", "-p") -Path (Join-Path $outputDir "screen.png")
}
catch {
    Write-Warning "Screenshot capture failed: $($_.Exception.Message)"
}

$uiDump = "/sdcard/scan-v1-ui.xml"
$dumpResult = Invoke-AdbText @("shell", "uiautomator", "dump", $uiDump) -AllowFailure
if ($dumpResult -notmatch "ERROR|Error") {
    $uiXml = Invoke-AdbText @("exec-out", "cat", $uiDump) -AllowFailure
    if (-not [string]::IsNullOrWhiteSpace($uiXml)) {
        Set-Content -Path (Join-Path $outputDir "ui.xml") -Value $uiXml -Encoding UTF8
    }
    Invoke-AdbText @("shell", "rm", "-f", $uiDump) -AllowFailure | Out-Null
}

$activity = Invoke-AdbText @("shell", "dumpsys", "activity", "activities") -AllowFailure
Set-Content -Path (Join-Path $outputDir "activity.txt") -Value $activity -Encoding UTF8

Write-Output (Resolve-Path $outputDir).Path
