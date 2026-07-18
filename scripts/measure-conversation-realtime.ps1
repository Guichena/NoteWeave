param(
    [int]$Iterations = 5,
    [int]$TimeoutSeconds = 30,
    [string]$OutputDirectory = "target/conversation-realtime-baseline"
)

$ErrorActionPreference = "Stop"
$runId = (Get-Date -Format "yyyyMMddHHmmss") + "-" + (Get-Random -Minimum 100 -Maximum 9999)
$streamProcess = $null
$streamFile = Join-Path $env:TEMP "noteweave-conversation-$runId.sse"

function Invoke-Json([string]$Method, [string]$Uri, $Body = $null) {
    $parameters = @{ Method = $Method; Uri = $Uri; ContentType = "application/json"; TimeoutSec = 30 }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 8) }
    return Invoke-RestMethod @parameters
}

function Read-Stream([string]$Path) {
    if (-not (Test-Path $Path)) { return "" }
    $content = Get-Content -Raw -LiteralPath $Path -ErrorAction SilentlyContinue
    if ($null -eq $content) { return "" }
    return $content.Replace("`r`n", "`n")
}

function Wait-ForText([string]$Path, [scriptblock]$Predicate, [int]$Timeout) {
    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        $text = Read-Stream $Path
        if (& $Predicate $text) { return $text }
        Start-Sleep -Milliseconds 20
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for conversation event stream"
}

function Percentile([double[]]$Values, [double]$P) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $index = [Math]::Ceiling($P * $sorted.Count) - 1
    return [Math]::Round([double]$sorted[[Math]::Max(0, $index)], 2)
}

try {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $workspace = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces" @{
        name = "Conversation realtime baseline $runId"
        description = "persistent session stream request and latency baseline"
    }).data.workspace_id
    $conversation = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces/$workspace/conversations" @{
        title = "Conversation realtime baseline"
        conversation_type = "QA"
    }).data.conversation_id
    $streamUrl = "http://localhost:3000/api/v2/workspaces/$workspace/conversations/$conversation/events"
    $streamProcess = Start-Process curl.exe -ArgumentList @("-sS", "-N", "--max-time", "120", $streamUrl) -RedirectStandardOutput $streamFile -PassThru -WindowStyle Hidden
    Wait-ForText $streamFile { param($text) $text.Contains("event:conversation.snapshot") } $TimeoutSeconds | Out-Null
    Start-Sleep -Milliseconds 100

    $deliveryMs = New-Object System.Collections.Generic.List[double]
    $sendApiMs = New-Object System.Collections.Generic.List[double]
    $answerRunIds = New-Object System.Collections.Generic.List[string]
    for ($index = 1; $index -le $Iterations; $index++) {
        $totalWatch = [Diagnostics.Stopwatch]::StartNew()
        $apiWatch = [Diagnostics.Stopwatch]::StartNew()
        $message = (Invoke-Json "POST" "http://localhost:3000/api/v2/conversations/$conversation/messages" @{
            content = "Persistent conversation stream baseline iteration $index"
            answer_mode = "QA"
            client_request_id = "conversation-baseline-$runId-$index"
        }).data
        $apiWatch.Stop()
        $sendApiMs.Add($apiWatch.Elapsed.TotalMilliseconds)
        $answerRunIds.Add($message.answer_run_id)
        Wait-ForText $streamFile {
            param($text)
            $blocks = $text -split "`n`n"
            return @($blocks | Where-Object {
                $_.Contains("event:answer.completed") -and $_.Contains($message.answer_run_id)
            }).Count -gt 0
        } $TimeoutSeconds | Out-Null
        $totalWatch.Stop()
        $deliveryMs.Add($totalWatch.Elapsed.TotalMilliseconds)
    }

    if ($null -ne $streamProcess -and -not $streamProcess.HasExited) {
        Stop-Process -Id $streamProcess.Id -Force
        $streamProcess.WaitForExit()
    }
    $streamProcess = $null
    $conversationCursor = (docker exec noteweave-v2-redis redis-cli GET "seq:conversation:$conversation").Trim()
    $resumeFile = Join-Path $env:TEMP "noteweave-conversation-resume-$runId.sse"
    $resumeWatch = [Diagnostics.Stopwatch]::StartNew()
    $resumeProcess = Start-Process curl.exe -ArgumentList @(
        "-sS", "-N", "--max-time", "3", "-H", "`"Last-Event-ID: $conversationCursor`"", $streamUrl
    ) -RedirectStandardOutput $resumeFile -PassThru -WindowStyle Hidden
    $resumeText = Wait-ForText $resumeFile { param($text) $text.Contains("event:conversation.snapshot") } $TimeoutSeconds
    $resumeWatch.Stop()
    if (-not $resumeProcess.HasExited) { Stop-Process -Id $resumeProcess.Id -Force }
    Remove-Item -LiteralPath $resumeFile -Force -ErrorAction SilentlyContinue
    $resumeWithoutDuplicateTerminal = -not $resumeText.Contains("event:answer.completed")

    $legacyRuntimeRequests = 2 * $Iterations
    $sessionRuntimeRequests = $Iterations + 1
    $requestReductionPercent = [Math]::Round(
            (($legacyRuntimeRequests - $sessionRuntimeRequests) * 100.0) / $legacyRuntimeRequests, 2)
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $true
        llm_enabled = $false
        workspace_id = $workspace
        conversation_id = $conversation
        iterations = $Iterations
        runtime_requests = [ordered]@{
            per_answer_stream_contract = $legacyRuntimeRequests
            persistent_conversation_contract = $sessionRuntimeRequests
            reduction_percent = $requestReductionPercent
        }
        send_api_ms = [ordered]@{
            p50 = Percentile $sendApiMs.ToArray() 0.50
            p95 = Percentile $sendApiMs.ToArray() 0.95
            samples = $sendApiMs.ToArray()
        }
        post_to_completed_event_ms = [ordered]@{
            p50 = Percentile $deliveryMs.ToArray() 0.50
            p95 = Percentile $deliveryMs.ToArray() 0.95
            samples = $deliveryMs.ToArray()
        }
        reconnect_snapshot_ms = [Math]::Round($resumeWatch.Elapsed.TotalMilliseconds, 2)
        reconnect_without_duplicate_terminal = $resumeWithoutDuplicateTerminal
        conversation_cursor = [long]$conversationCursor
        answer_run_ids = $answerRunIds.ToArray()
    }
    if (-not $resumeWithoutDuplicateTerminal) {
        $report.passed = $false
        $report.error = "Reconnect replayed an already-consumed terminal event"
    }
} catch {
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $false
        error = $_.Exception.Message
        error_at = $_.InvocationInfo.PositionMessage
        stack = $_.ScriptStackTrace
    }
} finally {
    if ($null -ne $streamProcess -and -not $streamProcess.HasExited) {
        Stop-Process -Id $streamProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $streamFile -Force -ErrorAction SilentlyContinue
}

$jsonPath = Join-Path $OutputDirectory "conversation-realtime-$runId.json"
$report | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 $jsonPath
$markdownPath = Join-Path $OutputDirectory "conversation-realtime-$runId.md"
$markdown = @"
# Conversation realtime baseline

- Measured at: $($report.measured_at)
- Passed: $($report.passed)
- Iterations: $($report.iterations)
- Runtime requests: per-answer=$($report.runtime_requests.per_answer_stream_contract), persistent-session=$($report.runtime_requests.persistent_conversation_contract), reduction=$($report.runtime_requests.reduction_percent)%
- Send API: p50=$($report.send_api_ms.p50)ms, p95=$($report.send_api_ms.p95)ms
- POST to completed event: p50=$($report.post_to_completed_event_ms.p50)ms, p95=$($report.post_to_completed_event_ms.p95)ms
- Reconnect snapshot: $($report.reconnect_snapshot_ms)ms
- Duplicate terminal after reconnect: $(-not $report.reconnect_without_duplicate_terminal)
- LLM enabled: $($report.llm_enabled) (model latency is intentionally not fabricated)
"@
$markdown | Set-Content -Encoding utf8 $markdownPath
Write-Output "report=$jsonPath"
Write-Output "markdown=$markdownPath"
Write-Output ($report | ConvertTo-Json -Depth 12 -Compress)
if (-not $report.passed) { exit 2 }
