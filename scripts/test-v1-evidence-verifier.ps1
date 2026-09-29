param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$Verifier = Join-Path $PSScriptRoot "verify-v1-evidence.ps1"
$ExpectedSha = "1111111111111111111111111111111111111111"

$RequiredGates = @(
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

function New-TestPackage {
    param([string]$Base)

    if (Test-Path $Base) {
        Remove-Item -Recurse -Force $Base
    }
    if (Test-Path "$Base.zip") {
        Remove-Item -Force "$Base.zip"
    }
    if (Test-Path "$Base.zip.sha256") {
        Remove-Item -Force "$Base.zip.sha256"
    }

    New-Item -ItemType Directory -Path $Base -Force | Out-Null
    $kit = Join-Path $Base "acceptance-kit"
    $evidence = Join-Path $Base "evidence\general"
    New-Item -ItemType Directory -Path $kit -Force | Out-Null
    New-Item -ItemType Directory -Path $evidence -Force | Out-Null

    Set-Content -Path (Join-Path $kit "Scan-v1.0.0-acceptance.apk") -Value "apk" -Encoding ASCII
    Set-Content -Path (Join-Path $kit "Scan-v1.0.0-acceptance.aab") -Value "aab" -Encoding ASCII
    Set-Content -Path (Join-Path $kit "Scan-v1.0.0-pre-v1-baseline.apk") -Value "baseline" -Encoding ASCII
    Set-Content -Path (Join-Path $kit "acceptance-checksums.sha256") -Value ("0" * 64 + "  placeholder") -Encoding ASCII
    Set-Content -Path (Join-Path $kit "acceptance-signing.txt") -Value "Signer #1 certificate SHA-256 digest: 22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22:22" -Encoding ASCII
    Set-Content -Path (Join-Path $kit "production-acceptance-metadata.txt") -Value "release_sha=$ExpectedSha" -Encoding ASCII
    Set-Content -Path (Join-Path $evidence "device.txt") -Value "device=test" -Encoding ASCII
    Set-Content -Path (Join-Path $evidence "screen.png") -Value "fake-image-bytes" -Encoding ASCII
    Set-Content -Path (Join-Path $Base "README.md") -Value "test session" -Encoding UTF8
    Set-Content -Path (Join-Path $Base "EVIDENCE_SUMMARY.md") -Value "test evidence summary" -Encoding UTF8

    $gates = [ordered]@{}
    foreach ($name in $RequiredGates) {
        $gates[$name] = [ordered]@{
            result = "pass"
            notes = "self-test"
            recorded_at_utc = "2026-09-29T00:00:00Z"
        }
    }

    $session = [ordered]@{
        schema_version = 1
        created_at_utc = "2026-09-29T00:00:00Z"
        updated_at_utc = "2026-09-29T00:00:00Z"
        release_sha = $ExpectedSha
        android_ci_run_id = "1"
        certification_run_id = "2"
        acceptance_run_id = "3"
        signer_sha256 = ("22:" * 31) + "22"
        artifact_name = "test"
        checksums = [ordered]@{}
        gates = $gates
    }
    $session | ConvertTo-Json -Depth 10 | Set-Content -Path (Join-Path $Base "session.json") -Encoding UTF8

    $checksumPath = Join-Path $Base "evidence-checksums.sha256"
    $baseFull = (Resolve-Path $Base).Path
    $files = Get-ChildItem -Path $Base -Recurse -File |
        Where-Object { $_.Name -ne "evidence-checksums.sha256" } |
        Sort-Object FullName

    $lines = foreach ($file in $files) {
        $relative = [System.IO.Path]::GetRelativePath($baseFull, $file.FullName).Replace([char]92, [char]47)
        $hash = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
    $lines | Set-Content -Path $checksumPath -Encoding ASCII

    Compress-Archive -Path (Join-Path $Base "*") -DestinationPath "$Base.zip" -CompressionLevel Optimal
    $zipHash = (Get-FileHash -Algorithm SHA256 "$Base.zip").Hash.ToLowerInvariant()
    "$zipHash  $([System.IO.Path]::GetFileName("$Base.zip"))" |
        Set-Content -Path "$Base.zip.sha256" -Encoding ASCII
}

function Assert-Pass {
    param([string]$Base, [string]$Name)

    try {
        $null = & $Verifier -SessionPath $Base -ExpectedSha $ExpectedSha -Json
        Write-Host "PASS: $Name" -ForegroundColor Green
    }
    catch {
        throw "Expected pass for '$Name', but verifier failed: $($_.Exception.Message)"
    }
}

function Assert-Fail {
    param([string]$Base, [string]$Name)

    $failed = $false
    try {
        $null = & $Verifier -SessionPath $Base -ExpectedSha $ExpectedSha -Json
    }
    catch {
        $failed = $true
        Write-Host "PASS: rejected $Name" -ForegroundColor Green
    }

    if (-not $failed) {
        throw "Expected verifier to reject '$Name', but it passed."
    }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("scan-v1-evidence-selftest-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    $case = Join-Path $tempRoot "valid"
    New-TestPackage $case
    Assert-Pass $case "valid evidence package"

    $case = Join-Path $tempRoot "missing-gate"
    New-TestPackage $case
    $sessionPath = Join-Path $case "session.json"
    $session = Get-Content $sessionPath -Raw | ConvertFrom-Json
    $session.gates.PSObject.Properties.Remove("talkback")
    $session | ConvertTo-Json -Depth 10 | Set-Content -Path $sessionPath -Encoding UTF8
    Assert-Fail $case "missing required gate"

    $case = Join-Path $tempRoot "failed-gate"
    New-TestPackage $case
    $sessionPath = Join-Path $case "session.json"
    $session = Get-Content $sessionPath -Raw | ConvertFrom-Json
    $session.gates.talkback.result = "fail"
    $session | ConvertTo-Json -Depth 10 | Set-Content -Path $sessionPath -Encoding UTF8
    Assert-Fail $case "non-passing required gate"

    $case = Join-Path $tempRoot "checksum-omission"
    New-TestPackage $case
    $checksumPath = Join-Path $case "evidence-checksums.sha256"
    $filtered = Get-Content $checksumPath | Where-Object { $_ -notmatch '\sREADME\.md$' }
    $filtered | Set-Content -Path $checksumPath -Encoding ASCII
    Assert-Fail $case "checksum manifest omission"

    $case = Join-Path $tempRoot "tampered-file"
    New-TestPackage $case
    Add-Content -Path (Join-Path $case "README.md") -Value "tampered"
    Assert-Fail $case "tampered evidence file"

    $case = Join-Path $tempRoot "stale-sha"
    New-TestPackage $case
    try {
        $null = & $Verifier -SessionPath $case -ExpectedSha "3333333333333333333333333333333333333333" -Json
        throw "Expected stale SHA verification to fail."
    }
    catch {
        if ($_.Exception.Message -eq "Expected stale SHA verification to fail.") {
            throw
        }
        Write-Host "PASS: rejected stale source SHA" -ForegroundColor Green
    }

    $case = Join-Path $tempRoot "zip-hash"
    New-TestPackage $case
    Set-Content -Path "$case.zip.sha256" -Value (("f" * 64) + "  " + [System.IO.Path]::GetFileName("$case.zip")) -Encoding ASCII
    Assert-Fail $case "corrupt ZIP checksum"

    Write-Host ""
    Write-Host "All v1 evidence verifier self-tests passed." -ForegroundColor Green
}
finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tempRoot
}
