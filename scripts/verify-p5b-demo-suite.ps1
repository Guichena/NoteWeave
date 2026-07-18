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
    Write-Host "[P5-B] Checking demo suite manifest..."
    node ".\scripts\check-p5b-demo-suite.mjs"
    Assert-LastExitCode "P5-B demo suite manifest"

    Write-Host "[P5-B] Running Gate 1 smoke suite..."
    powershell -ExecutionPolicy Bypass -File ".\workers\scripts\run-gate1-smoke.ps1"
    Assert-LastExitCode "P5-B Gate 1 smoke suite"

    Write-Host "[P5-B] Running research worker regression suite..."
    conda run -n noteweave-workers python -m pytest workers/research-worker/tests/test_harness.py
    Assert-LastExitCode "P5-B research worker regression suite"

    Write-Host "[P5-B] Running backend research artifact contract..."
    & ".\mvnw.cmd" -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest" test
    Assert-LastExitCode "P5-B backend research artifact contract"

    Write-Host "[P5-B] Running frontend build..."
    npm --prefix frontend run build
    Assert-LastExitCode "P5-B frontend build"

    Write-Host "[P5-B] Running frontend UI contract..."
    npm --prefix frontend run ui:check
    Assert-LastExitCode "P5-B frontend UI contract"
} finally {
    Pop-Location
}
