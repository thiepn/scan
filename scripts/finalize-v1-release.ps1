param(
    [string]$Repo = "thiepn/scan",
    [int]$ReleaseBlockerIssue = 20
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Get-RunCount {
    param(
        [string]$Workflow,
        [string]$Sha
    )
    $endpoint = "repos/$Repo/actions/workflows/$Workflow/runs?branch=main&head_sha=$Sha&status=success"
    $count = & gh api --method GET $endpoint --jq '.workflow_runs | map(select(.event == "push" or .event == "workflow_dispatch")) | length'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not query workflow '$Workflow'."
    }
    return [int]$count
}


function Resolve-CompletedEvidenceSession {
    param([string]$ExpectedSha)

    $root = Split-Path -Parent $PSScriptRoot
    $evidenceBase = Join-Path $root "release-evidence\v1"
    $pointer = Join-Path $evidenceBase "current-session.txt"
    $verifier = Join-Path $PSScriptRoot "verify-v1-evidence.ps1"

    if (-not (Test-Path $pointer)) {
        throw "No current physical-device QA session found. Run scripts/run-v1-device-qa.ps1 -Mode Prepare first."
    }
    if (-not (Test-Path $verifier)) {
        throw "Missing strict evidence verifier: $verifier"
    }

    $sessionPath = (Get-Content $pointer -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($sessionPath) -or -not (Test-Path $sessionPath)) {
        throw "The physical-device QA session pointer is missing or stale."
    }

    $verificationJson = & $verifier -SessionPath $sessionPath -ExpectedSha $ExpectedSha -Json
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($verificationJson -join ""))) {
        throw "Strict physical-device evidence verification failed."
    }

    $verified = ($verificationJson -join [Environment]::NewLine) | ConvertFrom-Json

    $runJson = & gh api --method GET "repos/$Repo/actions/runs/$($verified.acceptance_run_id)"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($runJson -join ""))) {
        throw "Could not resolve evidence production-acceptance run $($verified.acceptance_run_id)."
    }

    $run = ($runJson -join [Environment]::NewLine) | ConvertFrom-Json
    if ([string]$run.head_sha -ne $ExpectedSha) {
        throw "Evidence acceptance run targets $($run.head_sha), not exact current main $ExpectedSha."
    }
    if ([string]$run.status -ne "completed" -or [string]$run.conclusion -ne "success") {
        throw "Evidence acceptance run is not a successful completed run."
    }
    if ([string]$run.path -ne ".github/workflows/production-acceptance.yml") {
        throw "Evidence acceptance run came from an unexpected workflow: $($run.path)"
    }
    if ([string]$run.event -ne "push" -and [string]$run.event -ne "workflow_dispatch") {
        throw "Evidence acceptance run has an unexpected trigger event: $($run.event)"
    }

    $expectedArtifactName = "scan-v1-production-acceptance-$ExpectedSha"
    if ([string]$verified.artifact_name -ne $expectedArtifactName) {
        throw "Verified evidence references unexpected acceptance artifact '$($verified.artifact_name)'."
    }

    $remoteRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("scan-v1-remote-acceptance-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $remoteRoot -Force | Out-Null

    try {
        & gh run download $verified.acceptance_run_id --repo $Repo --name $expectedArtifactName --dir $remoteRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Could not download the original production acceptance artifact from run $($verified.acceptance_run_id)."
        }

        $expectedFiles = @(
            "Scan-v1.0.0-acceptance.apk",
            "Scan-v1.0.0-acceptance.aab",
            "Scan-v1.0.0-pre-v1-baseline.apk",
            "acceptance-checksums.sha256",
            "acceptance-signing.txt",
            "production-acceptance-metadata.txt"
        )

        $remoteFiles = @(Get-ChildItem -Path $remoteRoot -File | Sort-Object Name)
        $remoteNames = @($remoteFiles | ForEach-Object { $_.Name })

        $missingRemoteFiles = @($expectedFiles | Where-Object { $remoteNames -notcontains $_ })
        $unexpectedRemoteFiles = @($remoteNames | Where-Object { $expectedFiles -notcontains $_ })
        if ($missingRemoteFiles.Count -gt 0) {
            throw "Downloaded production acceptance artifact is missing files: $($missingRemoteFiles -join ', ')."
        }
        if ($unexpectedRemoteFiles.Count -gt 0) {
            throw "Downloaded production acceptance artifact contains unexpected files: $($unexpectedRemoteFiles -join ', ')."
        }

        $localAcceptanceDir = Join-Path ([string]$verified.session_path) "acceptance-kit"
        foreach ($name in $expectedFiles) {
            $localPath = Join-Path $localAcceptanceDir $name
            $remotePath = Join-Path $remoteRoot $name
            if (-not (Test-Path $localPath)) {
                throw "Local evidence acceptance kit is missing file: $name"
            }

            $localHash = (Get-FileHash -Algorithm SHA256 $localPath).Hash.ToLowerInvariant()
            $remoteHash = (Get-FileHash -Algorithm SHA256 $remotePath).Hash.ToLowerInvariant()
            if ($localHash -ne $remoteHash) {
                throw "Remote acceptance artifact mismatch for $name."
            }
        }
    }
    finally {
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $remoteRoot
    }

    Write-Host "Physical-device QA evidence is complete, internally verified, ZIP-verified, and byte-bound to production acceptance run $($verified.acceptance_run_id)." -ForegroundColor Green
    Write-Host "Evidence session: $($verified.session_path)"
    Write-Host "Evidence package: $($verified.zip_path)"
    Write-Host "Evidence ZIP SHA-256: $($verified.zip_sha256)"

    return [PSCustomObject]@{
        Path = [string]$verified.session_path
        ZipPath = [string]$verified.zip_path
        ZipSha256 = [string]$verified.zip_sha256
        SignerSha256 = [string]$verified.signer_sha256
        AcceptanceRunId = [string]$verified.acceptance_run_id
    }
}

Require-Command "gh"

& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' first."
}

$MainSha = (& gh api --method GET "repos/$Repo/commits/main" --jq '.sha').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($MainSha)) {
    throw "Could not resolve the current main SHA."
}

Write-Host "Current main SHA: $MainSha"

$CertificationRuns = Get-RunCount "certification.yml" $MainSha
if ($CertificationRuns -lt 1) {
    throw "The exact main SHA has no successful v1 Production Certification run."
}

$AcceptanceRuns = Get-RunCount "production-acceptance.yml" $MainSha
if ($AcceptanceRuns -lt 1) {
    throw "The exact main SHA has no successful production-signed acceptance run."
}

$TagRef = & gh api --method GET "repos/$Repo/git/ref/tags/v1.0.0" --jq '.object.sha' 2>$null
if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($TagRef)) {
    throw "Tag v1.0.0 already exists at $($TagRef.Trim())."
}

Write-Host ""
Write-Host "Automated release gates are green for the exact current main SHA." -ForegroundColor Green
Write-Host ""

$EvidenceSession = Resolve-CompletedEvidenceSession $MainSha

Write-Host ""
Write-Host "Before continuing, every manual gate in docs/V1_DEVICE_QA.md must have been completed with evidence:"
Write-Host "  - Samsung physical-device pass"
Write-Host "  - Pixel physical-device pass"
Write-Host "  - constrained/mid-range device pass"
Write-Host "  - TalkBack and 200% font-scale passes"
Write-Host "  - real 1,000-page stress case"
Write-Host "  - low-storage and process-death recovery"
Write-Host "  - encrypted backup disaster recovery"
Write-Host "  - production-signed fresh install and same-key upgrade"
Write-Host "  - production certificate fingerprint recorded outside the repository"
Write-Host "  - independent APK/AAB verification"
Write-Host ""

Write-Host "Verified evidence package: $($EvidenceSession.ZipPath)"
Write-Host ""
$Attestation = Read-Host "Type the exact main SHA to attest that the verified evidence package represents ALL manual v1.0.0 gates"
if ($Attestation.Trim() -ne $MainSha) {
    throw "Manual acceptance was not attested for the exact current main SHA."
}


$MainBeforeAttestation = (& gh api --method GET "repos/$Repo/commits/main" --jq '.sha').Trim()
if ($LASTEXITCODE -ne 0 -or $MainBeforeAttestation -ne $MainSha) {
    throw "Current main changed after evidence verification. Do not publish stale evidence; prepare a new acceptance/evidence session."
}

$AcceptanceRunId = [string]$EvidenceSession.AcceptanceRunId
if ($AcceptanceRunId -notmatch '^[1-9][0-9]*$') {
    throw "Verified evidence returned an invalid production acceptance run id."
}

& gh variable set SCAN_V1_MANUAL_ACCEPTANCE_SHA --repo $Repo --body $MainSha
if ($LASTEXITCODE -ne 0) {
    throw "Could not set SCAN_V1_MANUAL_ACCEPTANCE_SHA."
}

& gh variable set SCAN_V1_ACCEPTANCE_RUN_ID --repo $Repo --body $AcceptanceRunId
if ($LASTEXITCODE -ne 0) {
    throw "Could not set SCAN_V1_ACCEPTANCE_RUN_ID."
}

$RecordedSha = (& gh variable get SCAN_V1_MANUAL_ACCEPTANCE_SHA --repo $Repo).Trim()
if ($LASTEXITCODE -ne 0 -or $RecordedSha -ne $MainSha) {
    throw "Manual acceptance SHA variable did not round-trip to the expected value."
}

$RecordedRunId = (& gh variable get SCAN_V1_ACCEPTANCE_RUN_ID --repo $Repo).Trim()
if ($LASTEXITCODE -ne 0 -or $RecordedRunId -ne $AcceptanceRunId) {
    throw "Acceptance run ID variable did not round-trip to the evidence-bound run."
}

$MainBeforeTag = (& gh api --method GET "repos/$Repo/commits/main" --jq '.sha').Trim()
if ($LASTEXITCODE -ne 0 -or $MainBeforeTag -ne $MainSha) {
    throw "Current main changed before tag creation. The acceptance attestation is stale; do not create v1.0.0."
}

Write-Host "Manual acceptance attestation recorded for SHA $MainSha and acceptance run $AcceptanceRunId." -ForegroundColor Green

$refPayload = @{
    ref = "refs/tags/v1.0.0"
    sha = $MainSha
} | ConvertTo-Json -Compress

$refPayload | & gh api --method POST "repos/$Repo/git/refs" --input - *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Could not create v1.0.0 tag."
}

Write-Host "Created v1.0.0 at $MainSha." -ForegroundColor Green
Write-Host "Waiting for the release workflow to appear..."

$ReleaseRunId = $null
for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep -Seconds 5
    $runsJson = & gh run list --repo $Repo --workflow release.yml --limit 5 --json databaseId,headSha,status,conclusion,event
    if ($LASTEXITCODE -ne 0) {
        continue
    }

    $runs = $runsJson | ConvertFrom-Json
    $match = $runs | Where-Object { $_.headSha -eq $MainSha } | Select-Object -First 1
    if ($null -ne $match) {
        $ReleaseRunId = [string]$match.databaseId
        break
    }
}

if ([string]::IsNullOrWhiteSpace($ReleaseRunId)) {
    throw "v1.0.0 tag was created, but the Publish v1 Release workflow did not appear. Inspect GitHub Actions before retrying anything."
}

Write-Host "Watching Publish v1 Release run $ReleaseRunId..."
& gh run watch $ReleaseRunId --repo $Repo --exit-status
if ($LASTEXITCODE -ne 0) {
    throw "The v1.0.0 publish workflow failed. Do not recreate or move the tag; inspect the failed run."
}

$ReleaseUrl = (& gh release view v1.0.0 --repo $Repo --json url --jq '.url').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($ReleaseUrl)) {
    throw "Publish workflow succeeded but the GitHub Release could not be resolved."
}

if ($ReleaseBlockerIssue -gt 0) {
    & gh issue close $ReleaseBlockerIssue --repo $Repo --comment "v1.0.0 published successfully from exact accepted main SHA $MainSha using the exact APK/AAB bytes from production acceptance run $AcceptanceRunId. Release: $ReleaseUrl"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Release succeeded, but release-blocker issue #$ReleaseBlockerIssue could not be closed automatically."
    }
}

Write-Host ""
Write-Host "Scan v1.0.0 is published." -ForegroundColor Green
Write-Host $ReleaseUrl
