param(
    [string]$Repo = "thiepn/scan",
    [string]$BackupDirectory = (Join-Path $HOME "ScanSigningBackup"),
    [string]$ExistingKeystorePath = "",
    [string]$Alias = "scan-release",
    [switch]$NoWait,
    [switch]$SkipQaPrepare
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function ConvertFrom-SecureValue {
    param([Security.SecureString]$SecureValue)
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

function Set-GitHubSecretFromValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$Value,
        [Parameter(Mandatory = $true)]
        [string]$Repository
    )

    $ghPath = (Get-Command "gh").Source
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $ghPath
    $psi.Arguments = "secret set $Name --repo $Repository"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi

    if (-not $process.Start()) {
        throw "Could not start GitHub CLI while setting $Name."
    }

    try {
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        if ($process.ExitCode -ne 0) {
            throw "Could not set GitHub Actions secret $Name. gh exited with $($process.ExitCode). $stderr"
        }
    }
    finally {
        if (-not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
        $process.Dispose()
    }
}

function Read-ConfirmedSecret {
    param([string]$Prompt)
    while ($true) {
        $first = Read-Host $Prompt -AsSecureString
        $second = Read-Host "Confirm $Prompt" -AsSecureString
        $firstPlain = ConvertFrom-SecureValue $first
        $secondPlain = ConvertFrom-SecureValue $second
        try {
            if ($firstPlain.Length -lt 12) {
                Write-Host "Use at least 12 characters." -ForegroundColor Yellow
                continue
            }
            if ($firstPlain -ne $secondPlain) {
                Write-Host "Values did not match. Try again." -ForegroundColor Yellow
                continue
            }
            return $first
        }
        finally {
            $firstPlain = $null
            $secondPlain = $null
        }
    }
}

Require-Command "keytool"
Require-Command "gh"
Require-Command "git"

& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' first."
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LocalHead = (& git -C $RepoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($LocalHead)) {
    throw "Could not resolve the local Git HEAD."
}

$RemoteMain = (& gh api --method GET "repos/$Repo/commits/main" --jq '.sha').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($RemoteMain)) {
    throw "Could not resolve remote main for $Repo."
}

if ($LocalHead -ne $RemoteMain) {
    throw "This checkout is not exact current main. local=$LocalHead remote=$RemoteMain. Run 'git switch main' and 'git pull --ff-only', then retry."
}

Write-Host "Release candidate SHA pinned to exact current main: $RemoteMain" -ForegroundColor Cyan

New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
$BackupDirectory = (Resolve-Path $BackupDirectory).Path

if ([string]::IsNullOrWhiteSpace($ExistingKeystorePath)) {
    $KeystorePath = Join-Path $BackupDirectory "scan-production.jks"
    if (Test-Path $KeystorePath) {
        throw "A keystore already exists at '$KeystorePath'. Refusing to overwrite a production identity. Pass -ExistingKeystorePath to reuse it."
    }

    Write-Host ""
    Write-Host "No existing Scan production keystore was supplied." -ForegroundColor Yellow
    Write-Host "A new permanent Android signing identity will be created at:" -ForegroundColor Yellow
    Write-Host "  $KeystorePath"
    Write-Host ""
    $confirmation = Read-Host "Type CREATE to generate this permanent production signing identity"
    if ($confirmation -cne "CREATE") {
        throw "Cancelled. No signing key was created."
    }

    $StorePasswordSecure = Read-ConfirmedSecret "Production keystore password"
    $KeyPasswordSecure = Read-ConfirmedSecret "Production key password"
    $StorePassword = ConvertFrom-SecureValue $StorePasswordSecure
    $KeyPassword = ConvertFrom-SecureValue $KeyPasswordSecure

    try {
        $keytoolArgs = @(
            "-genkeypair", "-v",
            "-keystore", $KeystorePath,
            "-storetype", "JKS",
            "-storepass", $StorePassword,
            "-alias", $Alias,
            "-keypass", $KeyPassword,
            "-keyalg", "RSA",
            "-keysize", "3072",
            "-validity", "10000",
            "-dname", "CN=Scan Production, O=THIEPN, C=DE"
        )
        & keytool @keytoolArgs

        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $KeystorePath)) {
            throw "keytool failed to create the production keystore."
        }
    }
    catch {
        Remove-Item -Force -ErrorAction SilentlyContinue $KeystorePath
        throw
    }
}
else {
    $KeystorePath = (Resolve-Path $ExistingKeystorePath).Path
    Write-Host "Reusing existing production keystore: $KeystorePath"
    $StorePasswordSecure = Read-ConfirmedSecret "Production keystore password"
    $KeyPasswordSecure = Read-ConfirmedSecret "Production key password"
    $StorePassword = ConvertFrom-SecureValue $StorePasswordSecure
    $KeyPassword = ConvertFrom-SecureValue $KeyPasswordSecure
}

try {
    $listArgs = @(
        "-list",
        "-keystore", $KeystorePath,
        "-storepass", $StorePassword,
        "-alias", $Alias
    )
    & keytool @listArgs *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "The keystore/password/alias combination could not be opened."
    }

    $FingerprintPath = Join-Path $BackupDirectory "scan-production-certificate.txt"
    $verboseArgs = @(
        "-list", "-v",
        "-keystore", $KeystorePath,
        "-storepass", $StorePassword,
        "-alias", $Alias
    )
    & keytool @verboseArgs | Out-File -Encoding utf8 $FingerprintPath

    $CertificatePemPath = Join-Path $BackupDirectory "scan-production-certificate.pem"
    $exportArgs = @(
        "-exportcert", "-rfc",
        "-keystore", $KeystorePath,
        "-storepass", $StorePassword,
        "-alias", $Alias,
        "-file", $CertificatePemPath
    )
    & keytool @exportArgs
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $CertificatePemPath)) {
        throw "Could not export the public production certificate."
    }

    $KeystoreBase64 = [Convert]::ToBase64String(
        [IO.File]::ReadAllBytes($KeystorePath)
    )

    Write-Host "Uploading encrypted GitHub Actions secrets without placing secret values on the process command line..."
    Set-GitHubSecretFromValue -Name "SCAN_RELEASE_KEYSTORE_BASE64" -Value $KeystoreBase64 -Repository $Repo
    Set-GitHubSecretFromValue -Name "SCAN_RELEASE_STORE_PASSWORD" -Value $StorePassword -Repository $Repo
    Set-GitHubSecretFromValue -Name "SCAN_RELEASE_KEY_ALIAS" -Value $Alias -Repository $Repo
    Set-GitHubSecretFromValue -Name "SCAN_RELEASE_KEY_PASSWORD" -Value $KeyPassword -Repository $Repo

    Write-Host ""
    Write-Host "Production signing secrets configured for $Repo." -ForegroundColor Green
    Write-Host "Permanent keystore backup: $KeystorePath"
    Write-Host "Certificate/fingerprint record: $FingerprintPath"
    Write-Host "Public certificate (PEM): $CertificatePemPath"
    Write-Host ""
    Write-Host "Store both passwords in a password manager and make at least two secure offline copies of the keystore."
    Write-Host "Do not commit or upload the keystore anywhere except the encrypted GitHub Actions secret."
    Write-Host ""

    Write-Host "Starting the production acceptance workflow for exact main $RemoteMain..."

    $existingRunsJson = & gh run list --repo $Repo --workflow production-acceptance.yml --branch main --limit 30 --json databaseId
    if ($LASTEXITCODE -ne 0) {
        throw "Could not snapshot existing production-acceptance runs before dispatch."
    }
    $existingRunIds = @{}
    foreach ($run in ($existingRunsJson | ConvertFrom-Json)) {
        $existingRunIds[[string]$run.databaseId] = $true
    }

    & gh workflow run production-acceptance.yml --repo $Repo --ref main
    if ($LASTEXITCODE -ne 0) {
        throw "Signing is configured, but the production acceptance workflow could not be started."
    }

    if ($NoWait) {
        Write-Host ""
        Write-Host "Production acceptance was dispatched. -NoWait was supplied, so the bootstrap is stopping here." -ForegroundColor Yellow
        Write-Host "After the exact-main acceptance run passes, prepare QA with:"
        Write-Host "  powershell -ExecutionPolicy Bypass -File .\scripts\run-v1-device-qa.ps1 -Mode Prepare"
    }
    else {
        Write-Host "Resolving the newly dispatched exact-main acceptance run..." -ForegroundColor Cyan
        $acceptanceRun = $null

        for ($attempt = 1; $attempt -le 60; $attempt++) {
            $runsJson = & gh run list --repo $Repo --workflow production-acceptance.yml --branch main --limit 30 --json databaseId,headSha,event,status,conclusion,createdAt
            if ($LASTEXITCODE -ne 0) {
                throw "Could not query production-acceptance runs after dispatch."
            }

            $candidates = @($runsJson | ConvertFrom-Json) | Where-Object {
                $_.headSha -eq $RemoteMain -and
                $_.event -eq "workflow_dispatch" -and
                -not $existingRunIds.ContainsKey([string]$_.databaseId)
            } | Sort-Object createdAt -Descending

            if ($candidates.Count -gt 0) {
                $acceptanceRun = $candidates[0]
                break
            }

            Start-Sleep -Seconds 2
        }

        if ($null -eq $acceptanceRun) {
            throw "The production acceptance workflow was dispatched, but the new exact-main workflow_dispatch run could not be resolved."
        }

        Write-Host "Watching production acceptance run $($acceptanceRun.databaseId)..." -ForegroundColor Cyan
        & gh run watch $acceptanceRun.databaseId --repo $Repo --exit-status
        if ($LASTEXITCODE -ne 0) {
            throw "Production acceptance run $($acceptanceRun.databaseId) did not pass. Inspect the workflow before continuing physical-device QA."
        }

        $runJson = & gh run view $acceptanceRun.databaseId --repo $Repo --json headSha,status,conclusion,event
        if ($LASTEXITCODE -ne 0) {
            throw "Could not re-read the completed production acceptance run."
        }
        $completedRun = $runJson | ConvertFrom-Json
        if ($completedRun.headSha -ne $RemoteMain -or $completedRun.status -ne "completed" -or $completedRun.conclusion -ne "success") {
            throw "Completed acceptance run does not certify the pinned main SHA."
        }

        Write-Host ""
        Write-Host "Exact-main production acceptance passed." -ForegroundColor Green
        Write-Host "Run ID: $($acceptanceRun.databaseId)"

        if ($SkipQaPrepare) {
            Write-Host "-SkipQaPrepare was supplied. Prepare the physical-device QA session later with:" -ForegroundColor Yellow
            Write-Host "  powershell -ExecutionPolicy Bypass -File .\scripts\run-v1-device-qa.ps1 -Mode Prepare"
        }
        else {
            $QaOperator = Join-Path $PSScriptRoot "run-v1-device-qa.ps1"
            if (-not (Test-Path $QaOperator)) {
                throw "Production acceptance passed, but the device-QA operator was not found at $QaOperator."
            }

            Write-Host ""
            Write-Host "Preparing the SHA-pinned physical-device QA session..." -ForegroundColor Cyan
            & powershell -ExecutionPolicy Bypass -File $QaOperator -Mode Prepare -Repo $Repo
            if ($LASTEXITCODE -ne 0) {
                throw "Production acceptance passed, but automatic physical-device QA preparation failed."
            }

            Write-Host ""
            Write-Host "Signing bootstrap and acceptance handoff complete." -ForegroundColor Green
            Write-Host "Connect the first physical device and inspect progress with:"
            Write-Host "  .\scripts\run-v1-device-qa.ps1 -Mode Status"
        }
    }
}
finally {
    $KeystoreBase64 = $null
    $StorePassword = $null
    $KeyPassword = $null
    $StorePasswordSecure = $null
    $KeyPasswordSecure = $null
}
