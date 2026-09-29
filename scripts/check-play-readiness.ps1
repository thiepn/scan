param(
    [string]$Repo = "thiepn/scan",
    [string]$WebsitePrivacyUrl = "https://thiepn.dev/scan/privacy/"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Get-SuccessfulRunCount {
    param(
        [string]$Workflow,
        [string]$Sha
    )
    $endpoint = "repos/$Repo/actions/workflows/$Workflow/runs?branch=main&head_sha=$Sha&status=success"
    $count = & gh api --method GET $endpoint --jq '[.workflow_runs[] | select(.event == "push" or .event == "workflow_dispatch")] | length'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not query workflow '$Workflow'."
    }
    return [int]$count
}

function Test-SecretName {
    param([string]$Name)
    $names = & gh secret list --repo $Repo --json name --jq '.[].name'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not list repository secret names."
    }
    return @($names) -contains $Name
}

Require-Command "gh"
Require-Command "python3"

& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' first."
}

$mainSha = (& gh api --method GET "repos/$Repo/commits/main" --jq '.sha').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($mainSha)) {
    throw "Could not resolve current main SHA."
}

Write-Host ""
Write-Host "Scan Google Play readiness audit" -ForegroundColor Cyan
Write-Host "Current main: $mainSha"
Write-Host ""

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

Write-Host "[1/9] Validating committed Play listing package..."
& python3 scripts/validate-play-listing.py
if ($LASTEXITCODE -ne 0) {
    $failures.Add("Play listing package validation failed.")
}

Write-Host "[2/9] Checking exact-main Google Play Listing Validation..."
if ((Get-SuccessfulRunCount "play-listing.yml" $mainSha) -lt 1) {
    $failures.Add("Exact current main has no successful Google Play Listing Validation run. Dispatch that workflow on current main if it was not triggered automatically.")
}

Write-Host "[3/9] Checking exact-main Android CI..."
if ((Get-SuccessfulRunCount "android.yml" $mainSha) -lt 1) {
    $failures.Add("Exact current main has no successful Android CI run.")
}

Write-Host "[4/9] Checking exact-main v1 production certification..."
if ((Get-SuccessfulRunCount "certification.yml" $mainSha) -lt 1) {
    $failures.Add("Exact current main has no successful v1 Production Certification run.")
}

Write-Host "[5/9] Checking production signing secret names..."
$requiredSecrets = @(
    "SCAN_RELEASE_KEYSTORE_BASE64",
    "SCAN_RELEASE_STORE_PASSWORD",
    "SCAN_RELEASE_KEY_ALIAS",
    "SCAN_RELEASE_KEY_PASSWORD"
)
foreach ($name in $requiredSecrets) {
    if (-not (Test-SecretName $name)) {
        $failures.Add("Missing GitHub Actions secret: $name")
    }
}

Write-Host "[6/9] Checking exact-main production acceptance..."
if ((Get-SuccessfulRunCount "production-acceptance.yml" $mainSha) -lt 1) {
    $failures.Add("Exact current main has no successful production-signed acceptance run.")
}

Write-Host "[7/9] Checking GitHub immutable release protection..."
$immutableResponse = & gh api --method GET -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2026-03-10" "repos/$Repo/immutable-releases" 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($immutableResponse -join ""))) {
    $failures.Add("GitHub immutable releases are not enabled or could not be verified. Enable Settings > General > Releases > Enable release immutability.")
}
else {
    $immutableSettings = ($immutableResponse -join [Environment]::NewLine) | ConvertFrom-Json
    if ($immutableSettings.enabled -ne $true) {
        $failures.Add("GitHub immutable releases are not enabled.")
    }
}

Write-Host "[8/9] Checking public privacy policy..."
try {
    $response = Invoke-WebRequest -Uri $WebsitePrivacyUrl -Method Get -MaximumRedirection 5
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
        $failures.Add("Privacy policy returned HTTP $($response.StatusCode): $WebsitePrivacyUrl")
    }
    elseif ($response.Content -notmatch "ML Kit diagnostics") {
        $failures.Add("Public privacy policy is reachable but expected Scan/ML Kit disclosure text was not found.")
    }
}
catch {
    $failures.Add("Could not fetch public privacy policy: $($_.Exception.Message)")
}

Write-Host "[9/9] Checking remaining human Play Console gates..."
$warnings.Add("Public Play Console support email must still be supplied.")
$warnings.Add("Play App Signing must be configured with the intended Scan app-signing identity before any open/public rollout.")
$warnings.Add("Play app-signing certificate fingerprint must be compared against the production Scan certificate.")
$warnings.Add("Data Safety, content rating, app access, ads, target audience, and other Play declarations require final human review.")
$warnings.Add("Physical-device acceptance in docs/V1_DEVICE_QA.md must be complete before v1.0.0 publication.")
$warnings.Add("Immutable GitHub releases must remain enabled through publication; published assets and the tag will then be locked.")

Write-Host ""
if ($warnings.Count -gt 0) {
    Write-Host "Manual / Play Console items:" -ForegroundColor Yellow
    foreach ($warning in $warnings) {
        Write-Host "  - $warning"
    }
    Write-Host ""
}

if ($failures.Count -gt 0) {
    Write-Host "NOT READY" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "  - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "AUTOMATED PLAY READINESS GATES PASS" -ForegroundColor Green
Write-Host "The repository is technically ready for the remaining human Play Console and physical-device steps."
