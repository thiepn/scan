param(
    [Parameter(Mandatory = $true)]
    [string]$SessionPath,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedSha,

    [switch]$Json
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

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

function Get-RelativePathNormalized {
    param(
        [string]$Root,
        [string]$Path
    )

    $relative = [System.IO.Path]::GetRelativePath($Root, $Path)
    return $relative.Replace([char]92, [char]47)
}

function Resolve-SafeEvidencePath {
    param(
        [string]$Root,
        [string]$Relative
    )

    $normalized = $Relative.Replace([char]92, [char]47).Trim()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        throw "Evidence checksum manifest contains an empty path."
    }
    if ([System.IO.Path]::IsPathRooted($normalized)) {
        throw "Evidence checksum manifest contains a rooted path: $normalized"
    }

    $segments = $normalized.Split([char]47, [System.StringSplitOptions]::RemoveEmptyEntries)
    if ($segments -contains "..") {
        throw "Evidence checksum manifest contains a parent traversal: $normalized"
    }

    $rootFull = [System.IO.Path]::GetFullPath($Root)
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $rootFull $normalized))
    $prefix = $rootFull.TrimEnd([char]92, [char]47) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Evidence checksum path escapes the session directory: $normalized"
    }

    return $candidate
}

function Normalize-CertificateDigest {
    param([string]$Value)

    return (($Value -replace ':', '').Trim()).ToLowerInvariant()
}

function Get-StreamSha256 {
    param([System.IO.Stream]$Stream)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash($Stream)
        return ([System.BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

$resolvedSession = (Resolve-Path $SessionPath).Path
if ($ExpectedSha -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Expected SHA is not a 40-character Git commit SHA."
}

$sessionJson = Join-Path $resolvedSession "session.json"
$summaryPath = Join-Path $resolvedSession "EVIDENCE_SUMMARY.md"
$checksumsPath = Join-Path $resolvedSession "evidence-checksums.sha256"
$zipPath = "$resolvedSession.zip"
$zipHashPath = "$zipPath.sha256"

foreach ($requiredPath in @($sessionJson, $summaryPath, $checksumsPath, $zipPath, $zipHashPath)) {
    if (-not (Test-Path $requiredPath)) {
        throw "Required evidence artifact is missing: $requiredPath"
    }
}

$session = Get-Content $sessionJson -Raw | ConvertFrom-Json
if ([int]$session.schema_version -ne 1) {
    throw "Unsupported evidence schema version: $($session.schema_version)"
}
if ([string]$session.release_sha -ne $ExpectedSha) {
    throw "Physical-device QA evidence targets $($session.release_sha), not exact current main $ExpectedSha."
}

$acceptanceRunId = [string]$session.acceptance_run_id
if ($acceptanceRunId -notmatch '^[0-9]+$') {
    throw "Evidence has an invalid production acceptance run id."
}

$signerSha256 = ([string]$session.signer_sha256).ToLowerInvariant()
if (
    $signerSha256 -notmatch '^[0-9a-f]{64}$' -and
    $signerSha256 -notmatch '^([0-9a-f]{2}:){31}[0-9a-f]{2}$'
) {
    throw "Evidence has an invalid production signer SHA-256 digest."
}

if ($null -eq $session.gates) {
    throw "Evidence session has no gate collection."
}

$gateNames = @($session.gates.PSObject.Properties.Name)
$missingGates = @($RequiredGates | Where-Object { $gateNames -notcontains $_ })
if ($missingGates.Count -gt 0) {
    throw "Physical-device QA evidence is missing required gates: $($missingGates -join ', ')."
}

$nonPassing = New-Object System.Collections.Generic.List[string]
foreach ($property in $session.gates.PSObject.Properties) {
    $gateName = $property.Name
    $result = [string]$property.Value.result
    $recordedAt = [string]$property.Value.recorded_at_utc

    if ($result -ne "pass") {
        $nonPassing.Add("$gateName=$result")
        continue
    }
    if ([string]::IsNullOrWhiteSpace($recordedAt)) {
        $nonPassing.Add("$gateName=pass-without-recorded-at")
    }
}

if ($nonPassing.Count -gt 0) {
    throw "Physical-device QA evidence contains incomplete/non-passing gates: $($nonPassing -join ', ')."
}

$requiredAcceptanceFiles = @(
    "acceptance-kit/Scan-v1.0.0-acceptance.apk",
    "acceptance-kit/Scan-v1.0.0-acceptance.aab",
    "acceptance-kit/Scan-v1.0.0-pre-v1-baseline.apk",
    "acceptance-kit/acceptance-checksums.sha256",
    "acceptance-kit/acceptance-signing.txt",
    "acceptance-kit/production-acceptance-metadata.txt"
)

foreach ($relative in $requiredAcceptanceFiles) {
    $native = Resolve-SafeEvidencePath -Root $resolvedSession -Relative $relative
    if (-not (Test-Path $native)) {
        throw "Completed evidence is missing required acceptance artifact: $relative"
    }
}

$expectedArtifactName = "scan-v1-production-acceptance-$ExpectedSha"
$artifactName = [string]$session.artifact_name
if ($artifactName -ne $expectedArtifactName) {
    throw "Evidence artifact name '$artifactName' does not match exact current main artifact '$expectedArtifactName'."
}

$acceptanceDir = Join-Path $resolvedSession "acceptance-kit"
$acceptanceChecksumsPath = Join-Path $acceptanceDir "acceptance-checksums.sha256"
$acceptanceSigningPath = Join-Path $acceptanceDir "acceptance-signing.txt"
$acceptanceMetadataPath = Join-Path $acceptanceDir "production-acceptance-metadata.txt"

$requiredAcceptanceBinaries = @(
    "Scan-v1.0.0-acceptance.apk",
    "Scan-v1.0.0-acceptance.aab",
    "Scan-v1.0.0-pre-v1-baseline.apk"
)

$acceptanceManifest = @{}
foreach ($line in (Get-Content $acceptanceChecksumsPath)) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    if ($line -notmatch '^([0-9a-fA-F]{64})\s+\*?(.+)$') {
        throw "Malformed acceptance checksum line: $line"
    }

    $hash = $Matches[1].ToLowerInvariant()
    $name = [System.IO.Path]::GetFileName($Matches[2].Trim())
    if ([string]::IsNullOrWhiteSpace($name)) {
        throw "Acceptance checksum manifest contains an empty file name."
    }
    if ($acceptanceManifest.ContainsKey($name)) {
        throw "Duplicate acceptance checksum entry: $name"
    }

    $acceptanceManifest[$name] = $hash
}

foreach ($name in $requiredAcceptanceBinaries) {
    if (-not $acceptanceManifest.ContainsKey($name)) {
        throw "Acceptance kit checksum manifest is missing required file: $name"
    }

    $binaryPath = Join-Path $acceptanceDir $name
    $actual = (Get-FileHash -Algorithm SHA256 $binaryPath).Hash.ToLowerInvariant()
    if ($actual -ne $acceptanceManifest[$name]) {
        throw "Acceptance kit internal checksum mismatch: $name"
    }
}

$metadata = @{}
$metadataSignerLine = $null
foreach ($line in (Get-Content $acceptanceMetadataPath)) {
    if ($line -match '^([^=]+)=(.*)$') {
        $key = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        if ($metadata.ContainsKey($key)) {
            throw "Duplicate production acceptance metadata key: $key"
        }
        $metadata[$key] = $value
        continue
    }

    if ($line -match '^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$') {
        $metadataSignerLine = $Matches[1]
    }
}

foreach ($requiredKey in @("release_sha", "pre_v1_baseline_sha", "workflow_run_id", "workflow_run_attempt")) {
    if (-not $metadata.ContainsKey($requiredKey) -or [string]::IsNullOrWhiteSpace([string]$metadata[$requiredKey])) {
        throw "Production acceptance metadata is missing required key: $requiredKey"
    }
}

if ([string]$metadata["release_sha"] -ne $ExpectedSha) {
    throw "Acceptance metadata release SHA '$($metadata["release_sha"])' does not match exact current main '$ExpectedSha'."
}
if ([string]$metadata["workflow_run_id"] -ne $acceptanceRunId) {
    throw "Acceptance metadata workflow run id '$($metadata["workflow_run_id"])' does not match evidence session run id '$acceptanceRunId'."
}
if ([string]$metadata["workflow_run_attempt"] -notmatch '^[1-9][0-9]*$') {
    throw "Acceptance metadata has an invalid workflow_run_attempt."
}
if ([string]$metadata["pre_v1_baseline_sha"] -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Acceptance metadata has an invalid pre_v1_baseline_sha."
}
if ($null -eq $metadataSignerLine) {
    throw "Production acceptance metadata is missing signer SHA-256 digest."
}

$signingText = Get-Content $acceptanceSigningPath -Raw
if ($signingText -notmatch '(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$') {
    throw "Acceptance signing report is missing signer SHA-256 digest."
}
$acceptanceSigner = Normalize-CertificateDigest $Matches[1]
$metadataSigner = Normalize-CertificateDigest $metadataSignerLine
$sessionSigner = Normalize-CertificateDigest $signerSha256

if ($acceptanceSigner -notmatch '^[0-9a-f]{64}$') {
    throw "Acceptance signing report has an invalid signer SHA-256 digest."
}
if ($metadataSigner -ne $acceptanceSigner) {
    throw "Production acceptance metadata signer does not match acceptance signing report."
}
if ($sessionSigner -ne $acceptanceSigner) {
    throw "Evidence session signer does not match acceptance signing report."
}

$manifest = @{}
foreach ($line in (Get-Content $checksumsPath)) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    if ($line -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') {
        throw "Malformed evidence checksum line: $line"
    }

    $hash = $Matches[1].ToLowerInvariant()
    $relative = $Matches[2].Trim().Replace([char]92, [char]47)

    if ($manifest.ContainsKey($relative)) {
        throw "Duplicate evidence checksum entry: $relative"
    }

    $filePath = Resolve-SafeEvidencePath -Root $resolvedSession -Relative $relative
    if (-not (Test-Path $filePath)) {
        throw "Evidence checksum manifest references a missing file: $relative"
    }

    $actualHash = (Get-FileHash -Algorithm SHA256 $filePath).Hash.ToLowerInvariant()
    if ($actualHash -ne $hash) {
        throw "Evidence checksum mismatch: $relative"
    }

    $manifest[$relative] = $hash
}

$sessionFiles = @(Get-ChildItem -Path $resolvedSession -Recurse -File |
    Where-Object { $_.FullName -ne $checksumsPath } |
    Sort-Object FullName)

$missingManifestEntries = New-Object System.Collections.Generic.List[string]
foreach ($file in $sessionFiles) {
    $relative = Get-RelativePathNormalized -Root $resolvedSession -Path $file.FullName
    if (-not $manifest.ContainsKey($relative)) {
        $missingManifestEntries.Add($relative)
    }
}

if ($missingManifestEntries.Count -gt 0) {
    throw "Evidence checksum manifest does not cover every evidence file: $($missingManifestEntries -join ', ')."
}

$zipHashLine = (Get-Content $zipHashPath -Raw).Trim()
if ($zipHashLine -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') {
    throw "Malformed evidence ZIP checksum file."
}

$expectedZipHash = $Matches[1].ToLowerInvariant()
$zipHashName = [System.IO.Path]::GetFileName($Matches[2].Trim())
if ($zipHashName -ne [System.IO.Path]::GetFileName($zipPath)) {
    throw "Evidence ZIP checksum references the wrong file: $zipHashName"
}

$actualZipHash = (Get-FileHash -Algorithm SHA256 $zipPath).Hash.ToLowerInvariant()
if ($actualZipHash -ne $expectedZipHash) {
    throw "Evidence ZIP checksum mismatch."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $zipEntries = @($archive.Entries | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Name) })

    $expectedZipFiles = @(Get-ChildItem -Path $resolvedSession -Recurse -File | Sort-Object FullName)
    $expectedZipNames = @($expectedZipFiles | ForEach-Object {
        Get-RelativePathNormalized -Root $resolvedSession -Path $_.FullName
    })

    $actualZipNames = @($zipEntries | ForEach-Object { $_.FullName.Replace([char]92, [char]47) })

    $missingZipEntries = @($expectedZipNames | Where-Object { $actualZipNames -notcontains $_ })
    $extraZipEntries = @($actualZipNames | Where-Object { $expectedZipNames -notcontains $_ })

    if ($missingZipEntries.Count -gt 0) {
        throw "Evidence ZIP is missing files: $($missingZipEntries -join ', ')."
    }
    if ($extraZipEntries.Count -gt 0) {
        throw "Evidence ZIP contains unexpected files: $($extraZipEntries -join ', ')."
    }

    foreach ($entry in $zipEntries) {
        $relative = $entry.FullName.Replace([char]92, [char]47)
        $localPath = Resolve-SafeEvidencePath -Root $resolvedSession -Relative $relative
        $localHash = (Get-FileHash -Algorithm SHA256 $localPath).Hash.ToLowerInvariant()

        $stream = $entry.Open()
        try {
            $entryHash = Get-StreamSha256 -Stream $stream
        }
        finally {
            $stream.Dispose()
        }

        if ($entryHash -ne $localHash) {
            throw "Evidence ZIP content does not match the local evidence file: $relative"
        }
    }
}
finally {
    $archive.Dispose()
}

$result = [PSCustomObject]@{
    release_sha = [string]$session.release_sha
    acceptance_run_id = $acceptanceRunId
    signer_sha256 = $signerSha256
    artifact_name = $artifactName
    acceptance_apk_sha256 = [string]$acceptanceManifest["Scan-v1.0.0-acceptance.apk"]
    acceptance_aab_sha256 = [string]$acceptanceManifest["Scan-v1.0.0-acceptance.aab"]
    baseline_apk_sha256 = [string]$acceptanceManifest["Scan-v1.0.0-pre-v1-baseline.apk"]
    session_path = $resolvedSession
    zip_path = $zipPath
    zip_sha256 = $actualZipHash
    required_gate_count = $RequiredGates.Count
    evidence_file_count = $sessionFiles.Count + 1
}

if ($Json) {
    $result | ConvertTo-Json -Compress
}
else {
    Write-Host "Physical-device QA evidence is complete and integrity-verified for $ExpectedSha." -ForegroundColor Green
    Write-Host "Evidence session: $resolvedSession"
    Write-Host "Evidence package: $zipPath"
    $result
}
