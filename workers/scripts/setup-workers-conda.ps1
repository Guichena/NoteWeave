param(
    [switch]$Update
)

$ErrorActionPreference = "Stop"

$workersRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$environmentFile = Join-Path $workersRoot "environment.yml"
$environmentName = "noteweave-workers"

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing workers/environment.yml"
}

$condaCommand = Get-Command conda -ErrorAction SilentlyContinue
if (-not $condaCommand) {
    throw "Conda is required. Install Miniforge/Miniconda, then rerun this script."
}

$existingEnvironment = conda env list | Select-String -Pattern "^\s*$environmentName\s+"
if ($existingEnvironment -or $Update) {
    conda env update -n $environmentName -f $environmentFile --prune
} else {
    conda env create -f $environmentFile
}

Write-Host "Worker environment is ready: $environmentName"
