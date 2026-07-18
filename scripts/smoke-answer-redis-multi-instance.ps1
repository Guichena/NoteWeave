param(
    [int]$TimeoutSeconds = 90,
    [string]$OutputDirectory = "target/answer-redis-smoke"
)

$ErrorActionPreference = "Stop"
$runId = (Get-Date -Format "yyyyMMddHHmmss") + "-" + (Get-Random -Minimum 100 -Maximum 9999)
$peer = "noteweave-v2-backend-peer"
$report = $null

function Invoke-Json([string]$Method, [string]$Uri, $Body = $null) {
    $parameters = @{ Method = $Method; Uri = $Uri; ContentType = "application/json" }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 8) }
    return Invoke-RestMethod @parameters
}

function Invoke-Mysql([string]$Sql) {
    $password = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { "noteweave123" }
    $result = docker exec noteweave-v2-mysql mysql -N -B -unoteweave "-p$password" noteweave -e $Sql
    if ($LASTEXITCODE -ne 0) { throw "MySQL command failed" }
    return ($result -join "`n").Trim()
}

function Test-Container([string]$Name) {
    return @((docker ps -a --format "{{.Names}}") | Where-Object { $_ -eq $Name }).Count -gt 0
}

try {
    if (Test-Container $peer) { docker rm -f $peer | Out-Null }
    docker compose run -d --rm --no-deps --name $peer -p 18082:8081 backend | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $health = docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $peer 2>$null
        if ($health -eq "healthy") { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($health -ne "healthy") {
        docker logs --tail 120 $peer
        throw "Peer backend did not become healthy: $health"
    }

    $workspace = (Invoke-Json "POST" "http://localhost:8081/api/v2/workspaces" @{
        name = "Redis peer smoke $runId"
        description = "multi-instance answer bridge verification"
    }).data.workspace_id
    $conversation = (Invoke-Json "POST" "http://localhost:8081/api/v2/workspaces/$workspace/conversations" @{
        title = "Redis peer smoke"
        conversation_type = "QA"
    }).data.conversation_id
    $message = (Invoke-Json "POST" "http://localhost:8081/api/v2/conversations/$conversation/messages" @{
        content = "Cross instance Redis stream smoke"
        answer_mode = "QA"
        client_request_id = "redis-peer-$runId"
    }).data
    $answerRunId = $message.answer_run_id

    Invoke-Mysql "update answer_run set stream_owner='simulated-owner', stream_lease_until=date_add(current_timestamp, interval 2 minute) where id='$answerRunId' and status='GENERATING'" | Out-Null
    $streamKey = "stream:answer:$answerRunId"
    docker exec noteweave-v2-redis redis-cli DEL $streamKey | Out-Null
    docker exec noteweave-v2-redis redis-cli XADD $streamKey "5-0" sequence 5 type answer.delta data "remote-delta-$runId" occurred_at ((Get-Date).ToUniversalTime().ToString("o")) | Out-Null
    docker exec noteweave-v2-redis redis-cli XADD $streamKey "8-0" sequence 8 type answer.completed data "remote-message" occurred_at ((Get-Date).ToUniversalTime().ToString("o")) | Out-Null
    docker exec noteweave-v2-redis redis-cli EXPIRE $streamKey 600 | Out-Null

    $events = curl.exe -sS -N "http://localhost:18082/api/v2/workspaces/$workspace/answer-runs/$answerRunId/events"
    $eventText = $events -join "`n"
    $passed = $eventText.Contains("remote-delta-$runId") -and $eventText.Contains("event:answer.completed")
    if (-not $passed) { throw "Peer did not receive bridged delta and terminal event" }

    $bridgeMetric = Invoke-RestMethod "http://localhost:18082/actuator/metrics/noteweave.answer.redis.bridge_delivered"
    $delivered = ($bridgeMetric.measurements | Where-Object statistic -eq "COUNT").value
    if ([double]$delivered -lt 2) { throw "Peer bridge metric was not incremented" }

    $conversationStreamKey = "stream:conversation:$conversation"
    $conversationSequenceKey = "seq:conversation:$conversation"
    docker exec noteweave-v2-redis redis-cli DEL $conversationStreamKey $conversationSequenceKey | Out-Null
    docker exec noteweave-v2-redis redis-cli XADD $conversationStreamKey "1-0" sequence 1 run_id $answerRunId run_sequence 5 type answer.delta data "conversation-remote-$runId" occurred_at ((Get-Date).ToUniversalTime().ToString("o")) | Out-Null
    docker exec noteweave-v2-redis redis-cli XADD $conversationStreamKey "2-0" sequence 2 run_id $answerRunId run_sequence 8 type answer.completed data "remote-message" occurred_at ((Get-Date).ToUniversalTime().ToString("o")) | Out-Null
    docker exec noteweave-v2-redis redis-cli SET $conversationSequenceKey 2 EX 600 | Out-Null
    docker exec noteweave-v2-redis redis-cli EXPIRE $conversationStreamKey 600 | Out-Null
    $conversationEvents = curl.exe -sS -N "http://localhost:18082/api/v2/workspaces/$workspace/conversations/$conversation/events?close_on_terminal=true"
    $conversationEventText = $conversationEvents -join "`n"
    $conversationPassed = $conversationEventText.Contains("conversation.snapshot") -and $conversationEventText.Contains("conversation-remote-$runId") -and $conversationEventText.Contains($answerRunId) -and $conversationEventText.Contains("event:answer.completed")
    if (-not $conversationPassed) { throw "Peer did not receive conversation-wide bridged events" }
    $conversationMetric = Invoke-RestMethod "http://localhost:18082/actuator/metrics/noteweave.conversation.events.remote_delivered"
    $conversationDelivered = ($conversationMetric.measurements | Where-Object statistic -eq "COUNT").value
    if ([double]$conversationDelivered -lt 2) { throw "Peer conversation bridge metric was not incremented" }

    Invoke-Json "DELETE" "http://localhost:8081/api/v2/workspaces/$workspace/answer-runs/$answerRunId" | Out-Null
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $true
        owner = "noteweave-v2-backend"
        subscriber = $peer
        answer_run_id = $answerRunId
        redis_stream = $streamKey
        delivered_events = [double]$delivered
        conversation_id = $conversation
        conversation_redis_stream = $conversationStreamKey
        conversation_delivered_events = [double]$conversationDelivered
    }
} catch {
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        run_id = $runId
        passed = $false
        error = $_.Exception.Message
    }
} finally {
    if (Test-Container $peer) { docker stop $peer | Out-Null }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$path = Join-Path $OutputDirectory "answer-redis-peer-$runId.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $path
Write-Output "report=$path"
Write-Output ($report | ConvertTo-Json -Depth 8 -Compress)
if (-not $report.passed) { exit 2 }
