$ErrorActionPreference = "Stop"

$workersRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$environmentName = "noteweave-workers"

$condaCommand = Get-Command conda -ErrorAction SilentlyContinue
if (-not $condaCommand) {
    throw "Conda is required. Run workers/scripts/setup-workers-conda.ps1 first."
}

$researchWorker = Join-Path $workersRoot "research-worker"
$artifactWorker = Join-Path $workersRoot "artifact-worker"

Push-Location $researchWorker
try {
    conda run -n $environmentName python -m pytest tests
} finally {
    Pop-Location
}

Push-Location $artifactWorker
try {
    conda run -n $environmentName python -m pytest tests
} finally {
    Pop-Location
}
