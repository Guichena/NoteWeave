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
    Write-Host "[P5-C] Checking project narrative and evidence mapping..."
    node ".\scripts\check-p5c-delivery-pack.mjs"
    Assert-LastExitCode "P5-C delivery pack"
} finally {
    Pop-Location
}
