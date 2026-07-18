param(
    [int]$WarmSamples = 30,
    [int]$EsSamples = 20,
    [int]$StartupTimeoutSeconds = 120,
    [switch]$SkipColdStart,
    [switch]$FailOnThreshold,
    [string]$OutputDirectory = "target/performance-baseline"
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location $workspace

function Measure-Request {
    param(
        [string]$Uri,
        [string]$Method = "GET",
        [string]$Body = "",
        [string]$ContentType = "application/json"
    )
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    if ($Method -eq "POST") {
        Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Uri -ContentType $ContentType -Body $Body | Out-Null
    } else {
        Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Uri | Out-Null
    }
    $watch.Stop()
    return [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $index = [math]::Max(0, [math]::Ceiling($Percentile * $sorted.Count) - 1)
    return [math]::Round($sorted[$index], 2)
}

function Summarize-Samples {
    param([double[]]$Values)
    return [ordered]@{
        samples = $Values.Count
        min_ms = [math]::Round(($Values | Measure-Object -Minimum).Minimum, 2)
        p50_ms = Get-Percentile $Values 0.50
        p95_ms = Get-Percentile $Values 0.95
        max_ms = [math]::Round(($Values | Measure-Object -Maximum).Maximum, 2)
    }
}

function Wait-ContainerHealthy {
    param([string]$ContainerName, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $health = docker inspect --format='{{.State.Health.Status}}' $ContainerName 2>$null
        if ($health -eq "healthy") { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$ContainerName did not become healthy within $TimeoutSeconds seconds; last status=$health"
}

$thresholds = [ordered]@{
    backend_restart_healthy_ms = 90000
    first_api_ms = 1000
    warm_api_p95_ms = 500
    first_es_query_ms = 5000
    warm_es_p95_ms = 500
}

$coldStartMs = $null
if (-not $SkipColdStart) {
    $startupWatch = [System.Diagnostics.Stopwatch]::StartNew()
    docker compose --profile app restart elasticsearch | Out-Null
    Wait-ContainerHealthy "noteweave-v2-elasticsearch" $StartupTimeoutSeconds
    docker compose --profile app restart backend | Out-Null
    Wait-ContainerHealthy "noteweave-v2-backend" $StartupTimeoutSeconds
    $startupWatch.Stop()
    $coldStartMs = [math]::Round($startupWatch.Elapsed.TotalMilliseconds, 2)
} else {
    Wait-ContainerHealthy "noteweave-v2-elasticsearch" $StartupTimeoutSeconds
    Wait-ContainerHealthy "noteweave-v2-backend" $StartupTimeoutSeconds
}

$apiUri = "http://localhost:3000/api/v2/skills"
$firstApiMs = Measure-Request $apiUri
$warmApiSamples = @()
for ($i = 0; $i -lt $WarmSamples; $i++) {
    $warmApiSamples += Measure-Request $apiUri
}

$esUri = "http://localhost:9200/noteweave_chunk_*/_search?size=0"
$esBody = '{"query":{"match_all":{}},"size":0}'
$firstEsMs = Measure-Request -Uri $esUri -Method POST -Body $esBody
$warmEsSamples = @()
for ($i = 0; $i -lt $EsSamples; $i++) {
    $warmEsSamples += Measure-Request -Uri $esUri -Method POST -Body $esBody
}

$modelResult = [ordered]@{
    status = "skipped"
    reason = "NOTEWEAVE_LLM_ENABLED is not true; no fake model latency is recorded"
}
if ($env:NOTEWEAVE_LLM_ENABLED -eq "true") {
    $modelResult = [ordered]@{
        status = "not_measured"
        reason = "LLM is enabled, but a stable benchmark prompt and provider budget must be configured explicitly"
    }
}

$apiSummary = Summarize-Samples $warmApiSamples
$esSummary = Summarize-Samples $warmEsSamples
$checks = [ordered]@{
    backend_restart_healthy = $SkipColdStart -or $coldStartMs -le $thresholds.backend_restart_healthy_ms
    first_api = $firstApiMs -le $thresholds.first_api_ms
    warm_api_p95 = $apiSummary.p95_ms -le $thresholds.warm_api_p95_ms
    first_es_query = $firstEsMs -le $thresholds.first_es_query_ms
    warm_es_p95 = $esSummary.p95_ms -le $thresholds.warm_es_p95_ms
}

$result = [ordered]@{
    measured_at = (Get-Date).ToString("o")
    machine = $env:COMPUTERNAME
    docker_desktop = (docker version --format '{{.Server.Version}}')
    cold_start = [ordered]@{ backend_restart_to_healthy_ms = $coldStartMs; skipped = [bool]$SkipColdStart }
    first_api = [ordered]@{ uri = $apiUri; latency_ms = $firstApiMs }
    warm_api = $apiSummary
    first_es_query = [ordered]@{ uri = $esUri; latency_ms = $firstEsMs }
    warm_es_query = $esSummary
    model_request = $modelResult
    thresholds = $thresholds
    checks = $checks
    passed = -not ($checks.Values -contains $false)
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jsonPath = Join-Path $OutputDirectory "phase0-performance-$stamp.json"
$markdownPath = Join-Path $OutputDirectory "phase0-performance-$stamp.md"
$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $jsonPath

$coldDisplay = if ($null -eq $coldStartMs) { "skipped" } else { "$coldStartMs ms" }
$markdown = @(
    "# Phase 0 Docker cold/warm performance baseline",
    "",
    "- measured_at: $($result.measured_at)",
    "- docker_server: $($result.docker_desktop)",
    "- backend_restart_to_healthy: $coldDisplay",
    "- first_api_ms: $firstApiMs",
    "- warm_api: p50=$($apiSummary.p50_ms) ms, p95=$($apiSummary.p95_ms) ms, samples=$($apiSummary.samples)",
    "- first_es_query_ms: $firstEsMs",
    "- warm_es: p50=$($esSummary.p50_ms) ms, p95=$($esSummary.p95_ms) ms, samples=$($esSummary.samples)",
    "- model_request: $($modelResult.status) ($($modelResult.reason))",
    "- threshold_passed: $($result.passed)",
    "",
    "This result is valid only for the current machine, Docker resources, data volume, and concurrency. CI should rerun it with fixed hardware and fixtures."
) -join [Environment]::NewLine
$markdown | Set-Content -Encoding utf8 $markdownPath

Write-Output "json=$jsonPath"
Write-Output "markdown=$markdownPath"
Write-Output ($result | ConvertTo-Json -Depth 8 -Compress)

if ($FailOnThreshold -and -not $result.passed) {
    exit 2
}
