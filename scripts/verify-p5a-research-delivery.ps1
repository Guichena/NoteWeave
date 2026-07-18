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

Write-Host "[P5-A] Running frontend export contract..."
Push-Location (Join-Path $repoRoot "frontend")
try {
    npm run test -- src/researchReportDelivery.test.ts
    Assert-LastExitCode "P5-A frontend export contract"
} finally {
    Pop-Location
}

Write-Host "[P5-A] Running backend save-as-source and reuse contract..."
Push-Location $repoRoot
try {
    & ".\mvnw.cmd" -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest#completedResearchReportShouldBeSavedAsWorkspaceSource+savedResearchReportShouldRemainIdentifiableInChatCitations" test
    Assert-LastExitCode "P5-A backend save-as-source and reuse contract"
} finally {
    Pop-Location
}
