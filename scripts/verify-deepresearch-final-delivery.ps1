$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Assert-LastExitCode {
    param(
        [string]$Step
    )

    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

Push-Location $repoRoot
try {
    Write-Host "[FINAL] Verifying Gate 1 smoke harness..."
    powershell -ExecutionPolicy Bypass -File ".\workers\scripts\run-gate1-smoke.ps1"
    Assert-LastExitCode "FINAL Gate 1 smoke harness"

    Write-Host "[FINAL] Verifying P5-A delivery chain..."
    powershell -ExecutionPolicy Bypass -File ".\scripts\verify-p5a-research-delivery.ps1"
    Assert-LastExitCode "FINAL P5-A delivery chain"

    Write-Host "[FINAL] Verifying P5-B demo suite..."
    powershell -ExecutionPolicy Bypass -File ".\scripts\verify-p5b-demo-suite.ps1"
    Assert-LastExitCode "FINAL P5-B demo suite"

    Write-Host "[FINAL] Verifying P5-C delivery pack..."
    powershell -ExecutionPolicy Bypass -File ".\scripts\verify-p5c-delivery-pack.ps1"
    Assert-LastExitCode "FINAL P5-C delivery pack"
} finally {
    Pop-Location
}
