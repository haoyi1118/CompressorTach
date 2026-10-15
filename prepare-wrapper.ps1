# Prepare official Gradle 8.11.1 Wrapper for a clean project ZIP.
# Downloads from the official Gradle distribution host, then verifies SHA-256.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$wrapperDir = Join-Path $root 'gradle\wrapper'
$jar = Join-Path $wrapperDir 'gradle-wrapper.jar'
$base = 'https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar'
New-Item -ItemType Directory -Path $wrapperDir -Force | Out-Null
Write-Host 'Getting official Gradle Wrapper 8.11.1...' -ForegroundColor Cyan
try {
    $expected = [string](Invoke-RestMethod -Uri ($base + '.sha256') -TimeoutSec 60)
    $expected = $expected.Trim().Split(' ')[0].ToLowerInvariant()
    if ($expected -notmatch '^[a-f0-9]{64}$') { throw 'Invalid remote SHA-256 checksum' }
    if (-not (Test-Path $jar)) {
        Invoke-WebRequest -Uri $base -OutFile $jar -TimeoutSec 60
    }
    $actual = (Get-FileHash -Path $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        Remove-Item $jar -Force
        throw "Checksum mismatch: expected $expected but received $actual"
    }
    Write-Host 'Gradle Wrapper SHA-256 OK.' -ForegroundColor Green
} catch {
    Write-Error "Failed to prepare wrapper. Check access to services.gradle.org. $_"
    exit 1
}
