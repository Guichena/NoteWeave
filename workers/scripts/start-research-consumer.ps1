param(
    [string]$EnvironmentName = "noteweave-workers"
)

$ErrorActionPreference = "Stop"

$workersRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$researchWorker = Join-Path $workersRoot "research-worker"

$condaCommand = Get-Command conda -ErrorAction SilentlyContinue
if (-not $condaCommand) {
    throw "Conda is required. Run workers/scripts/setup-workers-conda.ps1 first."
}

Push-Location $researchWorker
try {
    conda run -n $EnvironmentName python -m app.kafka_consumer
} finally {
    Pop-Location
}
