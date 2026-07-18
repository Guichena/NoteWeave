$ErrorActionPreference = "Stop"

$workersRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$researchWorker = Join-Path $workersRoot "research-worker"
$environmentName = "noteweave-workers"

$condaCommand = Get-Command conda -ErrorAction SilentlyContinue
if (-not $condaCommand) {
    throw "Conda is required. Run workers/scripts/setup-workers-conda.ps1 first."
}

Push-Location $researchWorker
try {
    conda run -n $environmentName python -m app.gate1_smoke @args
} finally {
    Pop-Location
}
