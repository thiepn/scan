param(
    [string]$Repo = "thiepn/scan",
    [string]$BackupDirectory = (Join-Path $HOME "ScanSigningBackup"),
    [string]$ExistingKeystorePath = "",
    [string]$Alias = "scan-release"
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

& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' first."
}

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

    $KeystoreBase64 = [Convert]::ToBase64String(
        [IO.File]::ReadAllBytes($KeystorePath)
    )

    Write-Host "Uploading encrypted GitHub Actions secrets..."
    & gh secret set SCAN_RELEASE_KEYSTORE_BASE64 --repo $Repo --body $KeystoreBase64
    if ($LASTEXITCODE -ne 0) { throw "Could not set SCAN_RELEASE_KEYSTORE_BASE64." }

    & gh secret set SCAN_RELEASE_STORE_PASSWORD --repo $Repo --body $StorePassword
    if ($LASTEXITCODE -ne 0) { throw "Could not set SCAN_RELEASE_STORE_PASSWORD." }

    & gh secret set SCAN_RELEASE_KEY_ALIAS --repo $Repo --body $Alias
    if ($LASTEXITCODE -ne 0) { throw "Could not set SCAN_RELEASE_KEY_ALIAS." }

    & gh secret set SCAN_RELEASE_KEY_PASSWORD --repo $Repo --body $KeyPassword
    if ($LASTEXITCODE -ne 0) { throw "Could not set SCAN_RELEASE_KEY_PASSWORD." }

    Write-Host ""
    Write-Host "Production signing secrets configured for $Repo." -ForegroundColor Green
    Write-Host "Permanent keystore backup: $KeystorePath"
    Write-Host "Certificate/fingerprint record: $FingerprintPath"
    Write-Host ""
    Write-Host "Store both passwords in a password manager and make at least two secure offline copies of the keystore."
    Write-Host "Do not commit or upload the keystore anywhere except the encrypted GitHub Actions secret."
    Write-Host ""

    Write-Host "Starting the production acceptance workflow..."
    & gh workflow run production-acceptance.yml --repo $Repo --ref main
    if ($LASTEXITCODE -ne 0) {
        throw "Signing is configured, but the production acceptance workflow could not be started."
    }

    Write-Host ""
    Write-Host "Latest production acceptance run:"
    & gh run list --repo $Repo --workflow production-acceptance.yml --limit 1
}
finally {
    $KeystoreBase64 = $null
    $StorePassword = $null
    $KeyPassword = $null
    $StorePasswordSecure = $null
    $KeyPasswordSecure = $null
}
