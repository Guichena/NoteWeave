param()

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$defaultRepoRoot = Split-Path -Parent $scriptDir
$repoRoot = if ($env:NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_REPO_ROOT) {
    $env:NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_REPO_ROOT
} else {
    $defaultRepoRoot
}
$envPrefix = if ($env:NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_ENV_PREFIX) {
    $env:NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_ENV_PREFIX
} else {
    Join-Path $repoRoot ".conda\bilibili-render-pdf-mcp"
}
$serverScript = if ($env:NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_SERVER_SCRIPT) {
    $env:NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_SERVER_SCRIPT
} else {
    Join-Path $scriptDir "bilibili_render_pdf_server.py"
}

$condaBat = "C:\Users\guichen\miniforge3\condabin\conda.bat"
if (-not (Test-Path -LiteralPath $condaBat)) {
    throw "conda.bat not found at $condaBat"
}
if (-not (Test-Path -LiteralPath $serverScript)) {
    throw "MCP server script not found at $serverScript"
}
if (-not (Test-Path -LiteralPath (Join-Path $envPrefix "conda-meta\history"))) {
    throw "Dedicated conda environment is missing or incomplete at $envPrefix. Run workers/artifact-worker/mcp_servers/setup_bilibili_render_pdf_mcp_env.ps1 first."
}
if (-not (Test-Path -LiteralPath (Join-Path $envPrefix "python.exe"))) {
    throw "Dedicated conda environment is missing python.exe at $envPrefix. Run workers/artifact-worker/mcp_servers/setup_bilibili_render_pdf_mcp_env.ps1 first."
}

& $condaBat run --no-capture-output -p $envPrefix python $serverScript
