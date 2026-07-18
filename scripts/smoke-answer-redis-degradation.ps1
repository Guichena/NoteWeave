param(
    [int]$TimeoutSeconds = 90,
    [string]$OutputDirectory = "target/answer-redis-smoke"
)

$ErrorActionPreference = "Stop"
$runId = (Get-Date -Format "yyyyMMddHHmmss") + "-" + (Get-Random -Minimum 100 -Maximum 9999)
$redis = "noteweave-v2-redis"
$backend = "noteweave-v2-backend"
$report = $null
$redisStopped = $false

function Invoke-Json([string]$Method, [string]$Uri, $Body = $null) {
    $parameters = @{ Method = $Method; Uri = $Uri; ContentType = "application/json"; TimeoutSec = 30 }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 8) }
    return Invoke-RestMethod @parameters
}

function Wait-Healthy([string]$Container, [int]$Timeout) {
    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        $health = docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $Container 2>$null
        if ($health -eq "healthy") { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Container did not become healthy: $health"
}

try {
    Wait-Healthy $redis $TimeoutSeconds
    docker stop $redis | Out-Null
    $redisStopped = $true

    $workspace = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces" @{
        name = "Redis degradation $runId"
        description = "local mux and MySQL recovery verification"
    }).data.workspace_id
    $conversation = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces/$workspace/conversations" @{
        title = "Redis degradation"
        conversation_type = "QA"
    }).data.conversation_id
    $message = (Invoke-Json "POST" "http://localhost:3000/api/v2/conversations/$conversation/messages" @{
        content = "Complete this answer while Redis is unavailable."
        answer_mode = "QA"
        client_request_id = "redis-down-$runId"
    }).data

    $answerEvents = curl.exe -sS -N --max-time 30 "http://localhost:3000$($message.answer_stream_url)"
    $answerText = $answerEvents -join "`n"
    if (-not ($answerText.Contains("event:answer.delta") -and $answerText.Contains("event:answer.completed"))) {
        throw "Answer did not complete through the local mux while Redis was down"
    }
    $snapshotBeforeRestart = (Invoke-Json "GET" "http://localhost:3000/api/v2/workspaces/$workspace/answer-runs/$($message.answer_run_id)").data
    if ($snapshotBeforeRestart.status -ne "COMPLETED" -or $snapshotBeforeRestart.revision_status -ne "FINAL") {
        throw "MySQL snapshot was not completed while Redis was down"
    }

    $conversationEvents = curl.exe -sS -N --max-time 10 "http://localhost:3000/api/v2/workspaces/$workspace/conversations/$conversation/events?close_on_terminal=true"
    $conversationText = $conversationEvents -join "`n"
    if (-not ($conversationText.Contains("conversation.snapshot") -and $conversationText.Contains($message.answer_run_id) -and $conversationText.Contains("event:answer.completed"))) {
        throw "Conversation local replay failed while Redis was down"
    }

    $answerErrorMetric = Invoke-RestMethod "http://localhost:8081/actuator/metrics/noteweave.answer.redis.error"
    $answerRedisErrors = ($answerErrorMetric.measurements | Where-Object statistic -eq "COUNT").value
    $conversationErrorMetric = Invoke-RestMethod "http://localhost:8081/actuator/metrics/noteweave.conversation.redis.error"
    $conversationRedisErrors = ($conversationErrorMetric.measurements | Where-Object statistic -eq "COUNT").value
    if ([double]$answerRedisErrors -lt 1 -or [double]$conversationRedisErrors -lt 1) {
        throw "Redis degradation counters were not incremented"
    }

    docker start $redis | Out-Null
    $redisStopped = $false
    Wait-Healthy $redis $TimeoutSeconds
    docker restart $backend | Out-Null
    Wait-Healthy $backend $TimeoutSeconds

    $snapshotAfterRestart = (Invoke-Json "GET" "http://localhost:3000/api/v2/workspaces/$workspace/answer-runs/$($message.answer_run_id)").data
    if ($snapshotAfterRestart.status -ne "COMPLETED" -or $snapshotAfterRestart.content -ne $snapshotBeforeRestart.content) {
        throw "Answer snapshot did not survive Redis and Backend restart"
    }
    $recoveredConversation = curl.exe -sS -N --max-time 2 -H "Last-Event-ID: 999999" "http://localhost:3000/api/v2/workspaces/$workspace/conversations/$conversation/events?close_on_terminal=true"
    $recoveredText = $recoveredConversation -join "`n"
    if (-not ($recoveredText.Contains("conversation.snapshot") -and $recoveredText.Contains($message.answer_run_id))) {
        throw "Conversation snapshot did not recover from MySQL after restart"
    }
    if ($recoveredText.Contains("event:answer.completed")) {
        throw "Expired live events were incorrectly replayed after the recovery cursor"
    }

    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $true
        workspace_id = $workspace
        conversation_id = $conversation
        answer_run_id = $message.answer_run_id
        redis_down_answer_status = $snapshotBeforeRestart.status
        redis_down_revision_status = $snapshotBeforeRestart.revision_status
        answer_redis_errors = [double]$answerRedisErrors
        conversation_redis_errors = [double]$conversationRedisErrors
        recovered_after_backend_restart = $true
    }
} catch {
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $false
        error = $_.Exception.Message
    }
} finally {
    if ($redisStopped) {
        docker start $redis | Out-Null
        try { Wait-Healthy $redis $TimeoutSeconds } catch { }
    }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$path = Join-Path $OutputDirectory "answer-redis-degradation-$runId.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $path
Write-Output "report=$path"
Write-Output ($report | ConvertTo-Json -Depth 8 -Compress)
if (-not $report.passed) { exit 2 }
