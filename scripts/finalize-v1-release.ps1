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

$Attestation = Read-Host "Type the exact main SHA to attest that ALL manual v1.0.0 gates above passed"
if ($Attestation.Trim() -ne $MainSha) {
    throw "Manual acceptance was not attested for the exact current main SHA."
}

& gh variable set SCAN_V1_MANUAL_ACCEPTANCE_SHA --repo $Repo --body $MainSha
if ($LASTEXITCODE -ne 0) {
    throw "Could not set SCAN_V1_MANUAL_ACCEPTANCE_SHA."
}

$Recorded = (& gh variable get SCAN_V1_MANUAL_ACCEPTANCE_SHA --repo $Repo).Trim()
if ($LASTEXITCODE -ne 0 -or $Recorded -ne $MainSha) {
    throw "Manual acceptance variable did not round-trip to the expected SHA."
}

Write-Host "Manual acceptance attestation recorded." -ForegroundColor Green

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
    & gh issue close $ReleaseBlockerIssue --repo $Repo --comment "v1.0.0 published successfully from exact accepted main SHA $MainSha. Release: $ReleaseUrl"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Release succeeded, but release-blocker issue #$ReleaseBlockerIssue could not be closed automatically."
    }
}

Write-Host ""
Write-Host "Scan v1.0.0 is published." -ForegroundColor Green
Write-Host $ReleaseUrl
