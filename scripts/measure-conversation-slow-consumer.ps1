param(
    [int]$EventCount = 600,
    [int]$PayloadBytes = 8192,
    [string]$OutputDirectory = "target/conversation-realtime-baseline"
)

$ErrorActionPreference = "Stop"
$runId = (Get-Date -Format "yyyyMMddHHmmss") + "-" + (Get-Random -Minimum 100 -Maximum 9999)
$streamFile = Join-Path $env:TEMP "noteweave-slow-consumer-$runId.sse"
$streamProcess = $null

function Invoke-Json([string]$Method, [string]$Uri, $Body = $null) {
    $parameters = @{ Method = $Method; Uri = $Uri; ContentType = "application/json"; TimeoutSec = 30 }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 8) }
    return Invoke-RestMethod @parameters
}

function Metric-Count([string]$Name) {
    try {
        $metric = Invoke-RestMethod "http://localhost:8081/actuator/metrics/$Name"
        $value = ($metric.measurements | Where-Object statistic -eq "COUNT").value
        if ($null -eq $value) { return 0.0 }
        return [double]$value
    } catch {
        return 0.0
    }
}

function Percentile([double[]]$Values, [double]$P) {
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($P * $sorted.Count) - 1)
    return [Math]::Round([double]$sorted[$index], 2)
}

function Add-RespCommand([Text.StringBuilder]$Builder, [string[]]$Arguments) {
    [void]$Builder.Append("*$($Arguments.Count)`r`n")
    foreach ($argument in $Arguments) {
        $bytes = [Text.Encoding]::UTF8.GetByteCount($argument)
        [void]$Builder.Append("`$$bytes`r`n$argument`r`n")
    }
}

function Invoke-RedisPipe([Text.StringBuilder]$Commands) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = "docker.exe"
    $start.Arguments = "exec -i noteweave-v2-redis redis-cli --pipe"
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    [void]$process.Start()
    $process.StandardInput.Write($Commands.ToString())
    $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or $output -notmatch "errors: 0") {
        throw "Redis pipeline failed: $output $errorOutput"
    }
}

try {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $workspace = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces" @{
        name = "Slow conversation consumer $runId"
        description = "bounded subscriber queue verification"
    }).data.workspace_id
    $conversation = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces/$workspace/conversations" @{
        title = "Slow consumer"
        conversation_type = "QA"
    }).data.conversation_id
    $streamKey = "stream:conversation:$conversation"
    $sequenceKey = "seq:conversation:$conversation"
    docker exec noteweave-v2-redis redis-cli DEL $streamKey $sequenceKey | Out-Null

    $coalescedBefore = Metric-Count "noteweave.conversation.events.delta_coalesced"
    $overflowBefore = Metric-Count "noteweave.conversation.events.subscriber_overflow"
    $streamUrl = "http://localhost:3000/api/v2/workspaces/$workspace/conversations/$conversation/events"
    $streamProcess = Start-Process curl.exe -ArgumentList @(
        "-sS", "-N", "--limit-rate", "1k", "--max-time", "30", $streamUrl
    ) -RedirectStandardOutput $streamFile -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(15)
    do {
        $activeMetric = Invoke-RestMethod "http://localhost:8081/actuator/metrics/noteweave.conversation.events.active_subscribers"
        $active = ($activeMetric.measurements | Where-Object statistic -eq "VALUE").value
        if ([double]$active -ge 1) { break }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    if ([double]$active -lt 1) { throw "Slow subscriber did not connect" }

    $payload = "x" * $PayloadBytes
    $commands = New-Object Text.StringBuilder
    for ($sequence = 1; $sequence -le $EventCount; $sequence++) {
        Add-RespCommand $commands @(
            "XADD", $streamKey, "$sequence-0",
            "sequence", "$sequence", "run_id", "slow-run", "run_sequence", "$sequence",
            "type", "answer.delta", "data", $payload, "occurred_at", "2026-07-13T00:00:00Z"
        )
    }
    $terminalSequence = $EventCount + 1
    Add-RespCommand $commands @(
        "XADD", $streamKey, "$terminalSequence-0",
        "sequence", "$terminalSequence", "run_id", "slow-run", "run_sequence", "$terminalSequence",
        "type", "citation.upsert", "data", "terminal-pressure", "occurred_at", "2026-07-13T00:00:00Z"
    )
    Add-RespCommand $commands @("SET", $sequenceKey, "$terminalSequence", "EX", "600")
    Add-RespCommand $commands @("EXPIRE", $streamKey, "600")
    Invoke-RedisPipe $commands
    $streamLength = [int](docker exec noteweave-v2-redis redis-cli XLEN $streamKey)
    if ($streamLength -ne ($EventCount + 1)) {
        throw "Redis pressure injection length mismatch: expected=$($EventCount + 1), actual=$streamLength"
    }

    $apiLatencies = New-Object System.Collections.Generic.List[double]
    for ($index = 0; $index -lt 20; $index++) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        Invoke-RestMethod "http://localhost:8081/actuator/health" | Out-Null
        $watch.Stop()
        $apiLatencies.Add($watch.Elapsed.TotalMilliseconds)
    }

    $deadline = (Get-Date).AddSeconds(15)
    do {
        $coalescedAfter = Metric-Count "noteweave.conversation.events.delta_coalesced"
        $overflowAfter = Metric-Count "noteweave.conversation.events.subscriber_overflow"
        if ($coalescedAfter -gt $coalescedBefore -or $overflowAfter -gt $overflowBefore) { break }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    $coalescedDelta = $coalescedAfter - $coalescedBefore
    $overflowDelta = $overflowAfter - $overflowBefore
    if ($coalescedDelta -lt 1 -and $overflowDelta -lt 1) {
        throw "The slow consumer did not activate bounded-queue backpressure"
    }

    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $true
        workspace_id = $workspace
        conversation_id = $conversation
        injected_delta_events = $EventCount
        payload_bytes = $PayloadBytes
        redis_stream_length = $streamLength
        subscriber_rate_limit = "1KB/s"
        delta_coalesced = $coalescedDelta
        subscriber_overflow = $overflowDelta
        health_api_ms = [ordered]@{
            p50 = Percentile $apiLatencies.ToArray() 0.50
            p95 = Percentile $apiLatencies.ToArray() 0.95
            samples = $apiLatencies.ToArray()
        }
    }
} catch {
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $false
        error = $_.Exception.Message
        error_at = $_.InvocationInfo.PositionMessage
    }
} finally {
    if ($null -ne $streamProcess -and -not $streamProcess.HasExited) {
        Stop-Process -Id $streamProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $streamFile -Force -ErrorAction SilentlyContinue
}

$path = Join-Path $OutputDirectory "conversation-slow-consumer-$runId.json"
$report | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 $path
Write-Output "report=$path"
Write-Output ($report | ConvertTo-Json -Depth 10 -Compress)
if (-not $report.passed) { exit 2 }
