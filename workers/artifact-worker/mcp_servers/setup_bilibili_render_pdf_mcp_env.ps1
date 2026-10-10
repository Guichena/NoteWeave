param()

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$envFile = Join-Path $scriptDir "environment.yml"
$envPrefix = Join-Path $repoRoot ".conda\bilibili-render-pdf-mcp"
$condaBat = "C:\Users\guichen\miniforge3\condabin\conda.bat"
$envParent = Split-Path -Parent $envPrefix

if (-not (Test-Path -LiteralPath $condaBat)) {
    throw "conda.bat not found at $condaBat"
}
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "environment.yml not found at $envFile"
}

function Test-ValidCondaPrefix {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PrefixPath
    )

    $historyFile = Join-Path $PrefixPath "conda-meta\history"
    $pythonExecutable = Join-Path $PrefixPath "python.exe"
    return (Test-Path -LiteralPath $historyFile) -and (Test-Path -LiteralPath $pythonExecutable)
}

if ((Test-Path -LiteralPath $envPrefix) -and -not (Test-ValidCondaPrefix -PrefixPath $envPrefix)) {
    $resolvedEnvPrefix = [System.IO.Path]::GetFullPath($envPrefix)
    $resolvedEnvParent = [System.IO.Path]::GetFullPath($envParent)
    if (-not $resolvedEnvPrefix.StartsWith($resolvedEnvParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete invalid conda prefix outside expected directory: $resolvedEnvPrefix"
    }
    Remove-Item -LiteralPath $resolvedEnvPrefix -Recurse -Force
}

if (Test-ValidCondaPrefix -PrefixPath $envPrefix) {
    & $condaBat env update --prefix $envPrefix --file $envFile --prune
} else {
    & $condaBat env create --prefix $envPrefix --file $envFile
}
