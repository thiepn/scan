param(
    [ValidateSet("Prepare", "Status", "Checkpoint", "FreshInstall", "Upgrade", "Font200", "Record", "Package")]
    [string]$Mode = "Status",
    [string]$Repo = "thiepn/scan",
    [string]$Session,
    [ValidateSet("general", "samsung", "pixel", "constrained")]
    [string]$Profile = "general",
    [string]$Serial,
    [string]$Label,
    [ValidateSet(
        "samsung",
        "pixel",
        "constrained",
        "critical-flow",
        "talkback",
        "font-200",
        "stress-1000",
        "low-storage",
        "process-death",
        "backup-recovery",
        "fresh-install",
        "production-key-upgrade",
        "production-certificate-recorded",
        "apk-aab-verification",
        "zero-p0-p1"
    )]
    [string]$Gate,
    [ValidateSet("pass", "fail", "pending")]
    [string]$Result = "pending",
    [string]$Notes,
    [switch]$AllowIncomplete
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$EvidenceBase = Join-Path $Root "release-evidence\v1"
$CurrentSessionFile = Join-Path $EvidenceBase "current-session.txt"
$Collector = Join-Path $PSScriptRoot "collect-device-evidence.ps1"
$PackageName = "com.thiepn.scan"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Get-SuccessfulRun {
    param([string]$Workflow, [string]$Sha)

    $json = & gh run list --repo $Repo --workflow $Workflow --branch main --limit 30 --json databaseId,headSha,status,conclusion,createdAt
    if ($LASTEXITCODE -ne 0) {
        throw "Could not query workflow '$Workflow'."
    }

    $runs = $json | ConvertFrom-Json
    return $runs |
        Where-Object { $_.headSha -eq $Sha -and $_.status -eq "completed" -and $_.conclusion -eq "success" } |
        Sort-Object createdAt -Descending |
        Select-Object -First 1
}

function Resolve-MainSha {
    $sha = (& gh api --method GET "repos/$Repo/commits/main" --jq '.sha').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sha)) {
        throw "Could not resolve current main SHA."
    }
    return $sha
}

function Resolve-SessionPath {
    if (-not [string]::IsNullOrWhiteSpace($Session)) {
        return (Resolve-Path $Session).Path
    }

    if (-not (Test-Path $CurrentSessionFile)) {
        throw "No current QA session exists. Run with -Mode Prepare first or pass -Session."
    }

    $path = (Get-Content $CurrentSessionFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path $path)) {
        throw "The current QA session pointer is stale. Run -Mode Prepare again or pass -Session."
    }
    return (Resolve-Path $path).Path
}

function Load-Session {
    param([string]$Path)
    $manifest = Join-Path $Path "session.json"
    if (-not (Test-Path $manifest)) {
        throw "Missing session manifest: $manifest"
    }
    return Get-Content $manifest -Raw | ConvertFrom-Json
}

function Save-Session {
    param([string]$Path, [object]$Data)
    $Data.updated_at_utc = [DateTime]::UtcNow.ToString("o")
    $Data | ConvertTo-Json -Depth 10 | Set-Content -Path (Join-Path $Path "session.json") -Encoding UTF8
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)

    Require-Command "adb"
    $prefix = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $prefix = @("-s", $Serial)
    }

    $output = & adb @prefix @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        $message = ($output -join [Environment]::NewLine)
        throw "adb failed ($code): $message"
    }
    return ($output -join [Environment]::NewLine).TrimEnd()
}

function Require-OneDevice {
    Require-Command "adb"

    $lines = & adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "Could not enumerate adb devices."
    }

    $devices = @($lines | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
    })

    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        if ($devices -notcontains $Serial) {
            throw "Device '$Serial' is not connected or authorized. Connected: $($devices -join ', ')"
        }
        return
    }

    if ($devices.Count -eq 0) {
        throw "No authorized adb device is connected."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple adb devices are connected. Re-run with -Serial. Connected: $($devices -join ', ')"
    }

    $script:Serial = $devices[0]
    Write-Host "Using adb device: $script:Serial" -ForegroundColor Cyan
}

function Capture-Evidence {
    param([string]$SessionPath, [string]$Checkpoint)

    Require-OneDevice
    $root = Join-Path $SessionPath "evidence\$Profile"
    New-Item -ItemType Directory -Path $root -Force | Out-Null

    $params = @{
        Label = $Checkpoint
        OutputRoot = $root
        Serial = $Serial
        PackageName = $PackageName
    }

    $captured = & $Collector @params
    if ($LASTEXITCODE -ne 0) {
        throw "Device evidence capture failed."
    }

    Write-Host "Evidence: $captured" -ForegroundColor Green
    return $captured
}

function Verify-Checksums {
    param([string]$KitPath)

    $checksumFile = Join-Path $KitPath "acceptance-checksums.sha256"
    if (-not (Test-Path $checksumFile)) {
        throw "Acceptance kit is missing acceptance-checksums.sha256."
    }

    $expected = @{}
    foreach ($line in Get-Content $checksumFile) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') {
            $name = [System.IO.Path]::GetFileName($Matches[2].Trim())
            $expected[$name] = $Matches[1].ToLowerInvariant()
        }
    }

    $required = @(
        "Scan-v1.0.0-acceptance.apk",
        "Scan-v1.0.0-acceptance.aab",
        "Scan-v1.0.0-pre-v1-baseline.apk"
    )

    $actual = [ordered]@{}
    foreach ($name in $required) {
        $path = Join-Path $KitPath $name
        if (-not (Test-Path $path)) {
            throw "Acceptance kit is missing $name."
        }
        if (-not $expected.ContainsKey($name)) {
            throw "Checksum manifest has no entry for $name."
        }

        $hash = (Get-FileHash -Algorithm SHA256 $path).Hash.ToLowerInvariant()
        if ($hash -ne $expected[$name]) {
            throw "SHA-256 mismatch for $name."
        }
        $actual[$name] = $hash
    }

    return $actual
}

function Get-GateValue {
    param([object]$Data, [string]$Name)
    return $Data.gates.PSObject.Properties[$Name].Value
}

function Set-GateValue {
    param([object]$Data, [string]$Name, [string]$GateResult, [string]$GateNotes)

    $entry = Get-GateValue $Data $Name
    $entry.result = $GateResult
    $entry.notes = $GateNotes
    $entry.recorded_at_utc = [DateTime]::UtcNow.ToString("o")
}

function Show-Status {
    param([string]$SessionPath)

    $data = Load-Session $SessionPath
    Write-Host ""
    Write-Host "Scan v1 physical-device QA" -ForegroundColor Cyan
    Write-Host "Session: $SessionPath"
    Write-Host "Release SHA: $($data.release_sha)"
    Write-Host "Acceptance run: $($data.acceptance_run_id)"
    Write-Host "Signer SHA-256: $($data.signer_sha256)"
    Write-Host ""

    $rows = foreach ($property in $data.gates.PSObject.Properties) {
        $value = $property.Value
        [PSCustomObject]@{
            Gate = $property.Name
            Result = $value.result
            Recorded = $value.recorded_at_utc
            Notes = $value.notes
        }
    }
    $rows | Format-Table -AutoSize
}

Require-Command "gh"

& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' first."
}

New-Item -ItemType Directory -Path $EvidenceBase -Force | Out-Null

if ($Mode -eq "Prepare") {
    $mainSha = Resolve-MainSha

    $ci = Get-SuccessfulRun "android.yml" $mainSha
    if ($null -eq $ci) {
        throw "Exact current main $mainSha has no successful Android CI run."
    }

    $certification = Get-SuccessfulRun "certification.yml" $mainSha
    if ($null -eq $certification) {
        throw "Exact current main $mainSha has no successful v1 Production Certification run."
    }

    $acceptance = Get-SuccessfulRun "production-acceptance.yml" $mainSha
    if ($null -eq $acceptance) {
        throw "Exact current main $mainSha has no successful production-signed acceptance run. Configure production signing and let that workflow pass first."
    }

    $stamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
    $shortSha = $mainSha.Substring(0, 12)
    $sessionPath = Join-Path $EvidenceBase "$shortSha-$stamp"
    $kitPath = Join-Path $sessionPath "acceptance-kit"
    New-Item -ItemType Directory -Path $kitPath -Force | Out-Null

    $artifactName = "scan-v1-production-acceptance-$mainSha"
    Write-Host "Downloading $artifactName from workflow run $($acceptance.databaseId)..." -ForegroundColor Cyan
    & gh run download $acceptance.databaseId --repo $Repo --name $artifactName --dir $kitPath
    if ($LASTEXITCODE -ne 0) {
        throw "Could not download the production acceptance artifact."
    }

    $hashes = Verify-Checksums $kitPath

    $metadataPath = Join-Path $kitPath "production-acceptance-metadata.txt"
    if (-not (Test-Path $metadataPath)) {
        throw "Acceptance kit is missing production-acceptance-metadata.txt."
    }
    $metadata = Get-Content $metadataPath -Raw
    if ($metadata -notmatch "(?m)^release_sha=$([regex]::Escape($mainSha))$") {
        throw "Acceptance metadata does not match exact current main."
    }

    $signingPath = Join-Path $kitPath "acceptance-signing.txt"
    if (-not (Test-Path $signingPath)) {
        throw "Acceptance kit is missing acceptance-signing.txt."
    }
    $signing = Get-Content $signingPath -Raw
    $signer = ""
    if ($signing -match '(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$') {
        $signer = $Matches[1].ToLowerInvariant()
    }
    if ([string]::IsNullOrWhiteSpace($signer)) {
        throw "Could not resolve the production signer SHA-256 digest."
    }

    $gateNames = @(
        "samsung",
        "pixel",
        "constrained",
        "critical-flow",
        "talkback",
        "font-200",
        "stress-1000",
        "low-storage",
        "process-death",
        "backup-recovery",
        "fresh-install",
        "production-key-upgrade",
        "production-certificate-recorded",
        "apk-aab-verification",
        "zero-p0-p1"
    )

    $gates = [ordered]@{}
    foreach ($name in $gateNames) {
        $gates[$name] = [ordered]@{
            result = "pending"
            notes = ""
            recorded_at_utc = ""
        }
    }

    $manifest = [ordered]@{
        schema_version = 1
        created_at_utc = [DateTime]::UtcNow.ToString("o")
        updated_at_utc = [DateTime]::UtcNow.ToString("o")
        release_sha = $mainSha
        android_ci_run_id = [string]$ci.databaseId
        certification_run_id = [string]$certification.databaseId
        acceptance_run_id = [string]$acceptance.databaseId
        signer_sha256 = $signer
        artifact_name = $artifactName
        checksums = $hashes
        gates = $gates
    }

    $manifest | ConvertTo-Json -Depth 10 | Set-Content -Path (Join-Path $sessionPath "session.json") -Encoding UTF8
    Set-Content -Path $CurrentSessionFile -Value (Resolve-Path $sessionPath).Path -Encoding UTF8

    $readme = @(
        "# Scan v1.0.0 physical-device QA session",
        "",
        "Release SHA: $mainSha",
        "Android CI run: $($ci.databaseId)",
        "Production Certification run: $($certification.databaseId)",
        "Production Acceptance run: $($acceptance.databaseId)",
        "Signer SHA-256: $signer",
        "",
        "Use scripts/run-v1-device-qa.ps1 -Mode Status to inspect progress."
    )
    $readme | Set-Content -Path (Join-Path $sessionPath "README.md") -Encoding UTF8

    Write-Host ""
    Write-Host "Prepared physical-device QA session:" -ForegroundColor Green
    Write-Host (Resolve-Path $sessionPath).Path
    Write-Host ""
    Write-Host "Next: connect a test phone, then use Checkpoint, FreshInstall, Upgrade, Font200 and Record modes."
    exit 0
}

$sessionPath = Resolve-SessionPath
$data = Load-Session $sessionPath
$currentMain = Resolve-MainSha
if ($data.release_sha -ne $currentMain) {
    throw "This evidence session targets $($data.release_sha), but current main is $currentMain. Evidence is stale; create a new session."
}

switch ($Mode) {
    "Status" {
        Show-Status $sessionPath
    }

    "Checkpoint" {
        if ([string]::IsNullOrWhiteSpace($Label)) {
            throw "-Label is required for Checkpoint mode."
        }
        Capture-Evidence $sessionPath "$Profile-$Label" | Out-Null
    }

    "FreshInstall" {
        Require-OneDevice
        $apk = Join-Path $sessionPath "acceptance-kit\Scan-v1.0.0-acceptance.apk"

        Invoke-Adb @("uninstall", $PackageName) -AllowFailure | Out-Null
        Invoke-Adb @("install", $apk) | Out-Host
        Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") | Out-Host
        Start-Sleep -Seconds 2

        $pid = Invoke-Adb @("shell", "pidof", $PackageName)
        if ([string]::IsNullOrWhiteSpace($pid)) {
            throw "Scan did not remain running after fresh install."
        }

        Capture-Evidence $sessionPath "$Profile-fresh-install-launch" | Out-Null
        Write-Host ""
        Write-Host "Automated fresh-install and launch smoke passed." -ForegroundColor Green
        Write-Host "Complete the manual critical-flow checks, then record fresh-install explicitly."
    }

    "Upgrade" {
        Require-OneDevice
        $baseline = Join-Path $sessionPath "acceptance-kit\Scan-v1.0.0-pre-v1-baseline.apk"
        $final = Join-Path $sessionPath "acceptance-kit\Scan-v1.0.0-acceptance.apk"

        Invoke-Adb @("uninstall", $PackageName) -AllowFailure | Out-Null
        Invoke-Adb @("install", $baseline) | Out-Host
        Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") | Out-Host

        Write-Host ""
        Write-Host "Create representative documents and state in the pre-v1 baseline now." -ForegroundColor Yellow
        $ready = Read-Host "When ready for the in-place update, type READY"
        if ($ready.Trim().ToUpperInvariant() -ne "READY") {
            throw "Upgrade test cancelled before applying the final APK."
        }

        Capture-Evidence $sessionPath "$Profile-upgrade-baseline-before" | Out-Null
        Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
        Invoke-Adb @("install", "-r", $final) | Out-Host
        Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") | Out-Host
        Start-Sleep -Seconds 2

        Capture-Evidence $sessionPath "$Profile-upgrade-final-after" | Out-Null
        Write-Host ""
        Write-Host "In-place production-key update installed successfully." -ForegroundColor Green
        Write-Host "Verify documents, settings and state manually, then record production-key-upgrade as pass or fail."
    }

    "Font200" {
        Require-OneDevice
        $original = (Invoke-Adb @("shell", "settings", "get", "system", "font_scale")).Trim()
        if ([string]::IsNullOrWhiteSpace($original)) {
            throw "Could not read current Android font scale."
        }

        try {
            Invoke-Adb @("shell", "settings", "put", "system", "font_scale", "2.0") | Out-Null
            Invoke-Adb @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
            Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") | Out-Host
            Start-Sleep -Seconds 2
            Capture-Evidence $sessionPath "$Profile-font-200" | Out-Null

            Write-Host ""
            Write-Host "Inspect the required 200 percent font-scale surfaces from docs/V1_DEVICE_QA.md." -ForegroundColor Yellow
            $answer = Read-Host "Type PASS or FAIL after inspection"
            $normalized = $answer.Trim().ToUpperInvariant()
            if ($normalized -ne "PASS" -and $normalized -ne "FAIL") {
                throw "Expected PASS or FAIL."
            }

            $resultValue = if ($normalized -eq "PASS") { "pass" } else { "fail" }
            Set-GateValue $data "font-200" $resultValue "Recorded by Font200 operator on profile $Profile and serial $Serial."
            Save-Session $sessionPath $data
        }
        finally {
            Invoke-Adb @("shell", "settings", "put", "system", "font_scale", $original) -AllowFailure | Out-Null
            Invoke-Adb @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
            Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") -AllowFailure | Out-Null
            Write-Host "Restored font scale to $original." -ForegroundColor Cyan
        }
    }

    "Record" {
        if ([string]::IsNullOrWhiteSpace($Gate)) {
            throw "-Gate is required for Record mode."
        }

        Set-GateValue $data $Gate $Result $Notes
        Save-Session $sessionPath $data
        Write-Host "Recorded $Gate = $Result" -ForegroundColor Green
        Show-Status $sessionPath
    }

    "Package" {
        $pending = @()
        $failed = @()

        foreach ($property in $data.gates.PSObject.Properties) {
            $value = $property.Value
            if ($value.result -eq "pending") { $pending += $property.Name }
            if ($value.result -eq "fail") { $failed += $property.Name }
        }

        if (($pending.Count -gt 0 -or $failed.Count -gt 0) -and -not $AllowIncomplete) {
            throw "Evidence is incomplete. Pending: $($pending -join ', '). Failed: $($failed -join ', '). Use -AllowIncomplete only for an interim package."
        }

        $summaryPath = Join-Path $sessionPath "EVIDENCE_SUMMARY.md"
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add("# Scan v1.0.0 release evidence")
        $lines.Add("")
        $lines.Add("Release SHA: $($data.release_sha)")
        $lines.Add("Production Acceptance run: $($data.acceptance_run_id)")
        $lines.Add("Signer SHA-256: $($data.signer_sha256)")
        $lines.Add("")
        $lines.Add("| Gate | Result | Notes |")
        $lines.Add("| --- | --- | --- |")

        foreach ($property in $data.gates.PSObject.Properties) {
            $value = $property.Value
            $notesValue = ([string]$value.notes).Replace("|", "/").Replace([char]13, [char]32).Replace([char]10, [char]32)
            $lines.Add("| $($property.Name) | $($value.result) | $notesValue |")
        }

        $lines.Add("")
        $lines.Add("Generated by scripts/run-v1-device-qa.ps1. This package is evidence, not a substitute for the human checks themselves.")
        $lines | Set-Content -Path $summaryPath -Encoding UTF8

        $checksumPath = Join-Path $sessionPath "evidence-checksums.sha256"
        $sessionRoot = (Resolve-Path $sessionPath).Path
        $files = Get-ChildItem -Path $sessionPath -Recurse -File |
            Where-Object { $_.Name -ne "evidence-checksums.sha256" -and $_.Extension -ne ".zip" } |
            Sort-Object FullName

        $checksumLines = foreach ($file in $files) {
            $relative = $file.FullName.Substring($sessionRoot.Length).TrimStart([char]92, [char]47).Replace([char]92, [char]47)
            $hash = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash.ToLowerInvariant()
            "$hash  $relative"
        }
        $checksumLines | Set-Content -Path $checksumPath -Encoding ASCII

        $zipPath = "$sessionPath.zip"
        if (Test-Path $zipPath) {
            Remove-Item -Force $zipPath
        }
        Compress-Archive -Path (Join-Path $sessionPath "*") -DestinationPath $zipPath -CompressionLevel Optimal

        $zipHashPath = "$zipPath.sha256"
        $zipHash = (Get-FileHash -Algorithm SHA256 $zipPath).Hash.ToLowerInvariant()
        "$zipHash  $([System.IO.Path]::GetFileName($zipPath))" | Set-Content -Path $zipHashPath -Encoding ASCII

        Write-Host "Evidence package: $zipPath" -ForegroundColor Green
        Write-Host "Evidence package checksum: $zipHashPath" -ForegroundColor Green
        if ($pending.Count -gt 0 -or $failed.Count -gt 0) {
            Write-Warning "This package is intentionally incomplete."
        }
    }
}
