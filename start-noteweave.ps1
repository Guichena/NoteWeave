param(
    [switch]$Build,
    [switch]$Logs
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is not installed or not available on PATH."
}

if ($Build) {
    docker compose up -d --build
} else {
    docker compose up -d
}

if ($Logs) {
    docker compose logs -f app
}
