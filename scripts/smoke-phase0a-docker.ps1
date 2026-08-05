param(
    [int]$TimeoutSeconds = 90,
    [string]$OutputDirectory = "target/docker-smoke"
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location $workspace
$checks = New-Object System.Collections.Generic.List[object]
$runId = (Get-Date -Format "yyyyMMddHHmmss") + "-" + (Get-Random -Maximum 9999)

function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $checks.Add([ordered]@{ name = $Name; passed = $Passed; detail = $Detail })
    if (-not $Passed) { throw "Smoke check failed: $Name - $Detail" }
}

function Invoke-Json {
    param([string]$Method, [string]$Uri, [object]$Body = $null, [hashtable]$Headers = @{})
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers
    }
    return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 12)
}

function Wait-Healthy {
    param([string]$ContainerName)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = docker inspect --format='{{.State.Health.Status}}' $ContainerName 2>$null
        if ($status -eq "healthy") { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$ContainerName is not healthy: $status"
}

function Invoke-Mysql {
    param([string]$Sql)
    return (docker exec noteweave-v2-mysql mysql -N -s -unoteweave -pnoteweave123 noteweave -e $Sql).Trim()
}

function Test-Contains {
    param([string]$Text, [string]$Needle)
    return $null -ne $Text -and $Text.Contains($Needle)
}

$report = $null
$tempFile = Join-Path $env:TEMP "noteweave-smoke-$runId.md"
try {
    foreach ($container in @(
        "noteweave-v2-mysql", "noteweave-v2-redis", "noteweave-v2-kafka", "noteweave-v2-minio",
        "noteweave-v2-elasticsearch", "noteweave-v2-backend", "noteweave-v2-frontend",
        "noteweave-v2-research-worker-consumer", "noteweave-v2-artifact-worker-api"
    )) {
        Wait-Healthy $container
    }
    Add-Check "container_health" $true "all required containers are healthy"

    $frontendHealth = (Invoke-WebRequest -UseBasicParsing http://localhost:3000/healthz).StatusCode
    Add-Check "frontend_readiness" ($frontendHealth -eq 200) "status=$frontendHealth"
    $backendHealth = (Invoke-RestMethod http://localhost:8081/actuator/health).status
    Add-Check "backend_readiness" ($backendHealth -eq "UP") "status=$backendHealth"

    $flywayVersion = Invoke-Mysql "select version from flyway_schema_history order by installed_rank desc limit 1"
    Add-Check "flyway_latest" ($flywayVersion -eq "047") "version=$flywayVersion"
    $redisPing = (docker exec noteweave-v2-redis redis-cli ping).Trim()
    Add-Check "redis_round_trip" ($redisPing -eq "PONG") "reply=$redisPing"

    $minioKey = "smoke/$runId.txt"
    $minioUser = if ($env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER } else { "minioadmin" }
    $minioPassword = if ($env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD } else { "minioadmin" }
    $minioOutput = docker exec noteweave-v2-minio sh -c "mc alias set smoke http://127.0.0.1:9000 '$minioUser' '$minioPassword' >/dev/null && printf 'noteweave-smoke-$runId' | mc pipe smoke/noteweave-source/$minioKey >/dev/null && mc cat smoke/noteweave-source/$minioKey && mc rm smoke/noteweave-source/$minioKey >/dev/null"
    Add-Check "minio_put_get" (Test-Contains ($minioOutput -join "`n") "noteweave-smoke-$runId") "key=$minioKey"

    $topic = "noteweave.smoke.$($runId.Replace('-', ''))"
    $payload = "smoke-$runId"
    docker exec noteweave-v2-kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic $topic --partitions 1 --replication-factor 1 | Out-Null
    docker exec noteweave-v2-kafka sh -c "printf '%s' '$payload' | /opt/bitnami/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic $topic" | Out-Null
    $kafkaRead = docker exec noteweave-v2-kafka /opt/bitnami/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic $topic --from-beginning --max-messages 1 --timeout-ms 10000
    Add-Check "kafka_round_trip" (Test-Contains ($kafkaRead -join "`n") $payload) "topic=$topic"
    docker exec noteweave-v2-kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic $topic | Out-Null

    $esIndex = "noteweave_smoke_$($runId.Replace('-', '_'))"
    $esDoc = @{ smoke = $runId; created_at = (Get-Date).ToString("o") }
    Invoke-RestMethod -Method Put -Uri "http://localhost:9200/$esIndex/_doc/1?refresh=true" -ContentType "application/json" -Body ($esDoc | ConvertTo-Json) | Out-Null
    $esSearchBody = @{ query = @{ match = @{ smoke = $runId } } } | ConvertTo-Json -Depth 5
    $esSearch = Invoke-RestMethod -Method Post -Uri "http://localhost:9200/$esIndex/_search" -ContentType "application/json" -Body $esSearchBody
    Add-Check "elasticsearch_index_search" ($esSearch.hits.total.value -ge 1) "index=$esIndex"
    Invoke-RestMethod -Method Delete -Uri "http://localhost:9200/$esIndex" | Out-Null
    Add-Check "elasticsearch_delete" $true "index=$esIndex"

    $internalToken = if ($env:NOTEWEAVE_INTERNAL_AUTH_TOKEN) { $env:NOTEWEAVE_INTERNAL_AUTH_TOKEN } else { "noteweave-internal-dev" }
    $internal = Invoke-RestMethod -Uri http://localhost:8081/internal/worker/artifact-outbox/metrics -Headers @{ "X-NoteWeave-Internal-Token" = $internalToken }
    Add-Check "internal_auth" ([bool]$internal.success) "artifact outbox metrics endpoint accepted configured token"

    $workspace = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces" @{ name = "Docker Smoke $runId"; description = "automated phase0a smoke" }).data.workspace_id
    $content = [Text.Encoding]::UTF8.GetBytes("# Docker smoke $runId`n`nThis is a valid NoteWeave smoke document.`n")
    [IO.File]::WriteAllBytes($tempFile, $content)
    $upload = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces/$workspace/uploads" @{
        file_name = "smoke.md"; file_size = $content.Length; mime_type = "text/markdown"; chunk_size = $content.Length; total_chunks = 1
    }).data
    $md5Algorithm = [Security.Cryptography.MD5]::Create()
    try {
        $md5 = [Convert]::ToBase64String($md5Algorithm.ComputeHash($content))
    } finally {
        $md5Algorithm.Dispose()
    }
    $chunkResult = curl.exe -sS -X PUT -H "Content-Type: application/octet-stream" -H "Content-MD5: $md5" --data-binary "@$tempFile" "http://localhost:3000/api/v2/uploads/$($upload.upload_id)/chunks/0"
    Add-Check "upload_chunk" (Test-Contains ($chunkResult -join "`n") '"accepted":true') "upload_id=$($upload.upload_id)"
    $complete = (Invoke-Json "POST" "http://localhost:3000/api/v2/uploads/$($upload.upload_id)/complete").data
    Add-Check "upload_complete" ([string]::IsNullOrWhiteSpace($complete.source_id) -eq $false) "source_id=$($complete.source_id)"

    $taskId = $complete.task_id
    $terminal = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $terminal = (Invoke-Json "GET" "http://localhost:3000/api/v2/tasks/$taskId").data
        if ($terminal.task_status -in @("COMPLETED", "FAILED", "CANCELLED")) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    Add-Check "task_terminal" ($terminal.task_status -eq "COMPLETED") "status=$($terminal.task_status)"
    $source = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $sources = (Invoke-Json "GET" "http://localhost:3000/api/v2/workspaces/$workspace/sources").data
        $source = $sources | Where-Object source_id -eq $complete.source_id | Select-Object -First 1
        if ($null -ne $source -and $source.status -eq "READY") { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Add-Check "source_ready" ($null -ne $source -and $source.status -eq "READY") "status=$($source.status)"

    $conversation = (Invoke-Json "POST" "http://localhost:3000/api/v2/workspaces/$workspace/conversations" @{ title = "Docker smoke"; conversation_type = "QA" }).data.conversation_id
    $message = (Invoke-Json "POST" "http://localhost:3000/api/v2/conversations/$conversation/messages" @{
        answer_mode = "QA"
        client_request_id = "docker-smoke-$runId"
        content = "Summarize the Docker smoke document."
    }).data
    $runBefore = (Invoke-Json "GET" "http://localhost:3000/api/v2/workspaces/$workspace/answer-runs/$($message.answer_run_id)").data
    $runCreatedStateValid = $runBefore.status -in @("GENERATING", "FINALIZING", "COMPLETED") -and $runBefore.revision_status -in @("STREAMING", "FINAL")
    Add-Check "answer_run_created" $runCreatedStateValid "run_id=$($message.answer_run_id) status=$($runBefore.status) revision=$($runBefore.revision_status)"
    $stream = curl.exe -sS -N "http://localhost:3000$($message.answer_stream_url)"
    $streamText = $stream -join "`n"
    Add-Check "answer_sse_content" ((Test-Contains $streamText "answer.delta") -or (Test-Contains $streamText "answer.snapshot")) "answer_run_id=$($message.answer_run_id)"
    Add-Check "answer_sse_completed" (Test-Contains $streamText "answer.completed") "answer_run_id=$($message.answer_run_id)"
    $runAfter = (Invoke-Json "GET" "http://localhost:3000/api/v2/workspaces/$workspace/answer-runs/$($message.answer_run_id)").data
    Add-Check "answer_run_completed" ($runAfter.status -eq "COMPLETED" -and $runAfter.revision_status -eq "FINAL") "status=$($runAfter.status) revision=$($runAfter.revision_status)"
    $answerEventCount = Invoke-Mysql "select count(*) from answer_event where answer_run_id = '$($message.answer_run_id)'"
    Add-Check "answer_event_snapshot" ([int]$answerEventCount -ge 6) "events=$answerEventCount"
    $answerCursor = Invoke-Mysql "select event_seq from answer_run where id = '$($message.answer_run_id)'"
    $resumedStream = curl.exe -sS -N -H "Last-Event-ID: $answerCursor" "http://localhost:3000$($message.answer_stream_url)"
    $resumedText = $resumedStream -join "`n"
    Add-Check "answer_cursor_resume" ((Test-Contains $resumedText "answer.snapshot") -eq $false -and (Test-Contains $resumedText "answer.completed") -eq $false) "cursor=$answerCursor produced_no_duplicate_terminal=true"
    $deltaBatchCount = ([regex]::Matches($streamText, "event:answer.delta")).Count
    Add-Check "answer_delta_batching" ($deltaBatchCount -le 6) "delta_batches=$deltaBatchCount snapshot_replay=$([bool](Test-Contains $streamText 'answer.snapshot'))"
    $muxMetric = Invoke-RestMethod "http://localhost:8081/actuator/metrics/noteweave.answer.events.published"
    $muxPublished = ($muxMetric.measurements | Where-Object statistic -eq "COUNT").value
    Add-Check "answer_event_mux_metrics" ([double]$muxPublished -ge 1) "published=$muxPublished"
    $redisAnswerLength = (docker exec noteweave-v2-redis redis-cli XLEN "stream:answer:$($message.answer_run_id)").Trim()
    $redisAnswerTtl = (docker exec noteweave-v2-redis redis-cli TTL "stream:answer:$($message.answer_run_id)").Trim()
    Add-Check "answer_redis_stream" ([int]$redisAnswerLength -ge 3 -and [int]$redisAnswerTtl -gt 0) "length=$redisAnswerLength ttl=$redisAnswerTtl"
    $conversationStreamUrl = "http://localhost:3000/api/v2/workspaces/$workspace/conversations/$conversation/events?close_on_terminal=true"
    $conversationStream = curl.exe -sS -N --max-time 10 $conversationStreamUrl
    $conversationStreamText = $conversationStream -join "`n"
    Add-Check "conversation_sse_snapshot" (Test-Contains $conversationStreamText "conversation.snapshot") "conversation_id=$conversation"
    Add-Check "conversation_sse_aggregates_run" ((Test-Contains $conversationStreamText "answer.delta") -and (Test-Contains $conversationStreamText $message.answer_run_id) -and (Test-Contains $conversationStreamText "run_sequence")) "answer_run_id=$($message.answer_run_id)"
    Add-Check "conversation_sse_completed" (Test-Contains $conversationStreamText "answer.completed") "conversation_id=$conversation"
    $redisConversationLength = (docker exec noteweave-v2-redis redis-cli XLEN "stream:conversation:$conversation").Trim()
    $redisConversationTtl = (docker exec noteweave-v2-redis redis-cli TTL "stream:conversation:$conversation").Trim()
    Add-Check "conversation_redis_stream" ([int]$redisConversationLength -ge 3 -and [int]$redisConversationTtl -gt 0) "length=$redisConversationLength ttl=$redisConversationTtl"
    $conversationCursor = (docker exec noteweave-v2-redis redis-cli GET "seq:conversation:$conversation").Trim()
    $conversationResumed = curl.exe -sS -N --max-time 2 -H "Last-Event-ID: $conversationCursor" $conversationStreamUrl
    $conversationResumedText = $conversationResumed -join "`n"
    Add-Check "conversation_cursor_resume" ((Test-Contains $conversationResumedText "conversation.snapshot") -and (Test-Contains $conversationResumedText "answer.completed") -eq $false) "cursor=$conversationCursor produced_no_duplicate_terminal=true"
    $legacyStream = curl.exe -sS -N "http://localhost:3000$($message.stream_url)"
    $legacyStreamText = $legacyStream -join "`n"
    Add-Check "legacy_sse_compatibility" ((Test-Contains $legacyStreamText "chat.delta") -and (Test-Contains $legacyStreamText "chat.completed")) "assistant_request_id=$($message.assistant_request_id)"

    $cancelMessage = (Invoke-Json "POST" "http://localhost:3000/api/v2/conversations/$conversation/messages" @{
        answer_mode = "QA"
        client_request_id = "docker-cancel-$runId"
        content = "Cancel this answer before streaming."
    }).data
    $cancelledRun = $null
    try {
        $cancelledRun = (Invoke-Json "DELETE" "http://localhost:3000/api/v2/workspaces/$workspace/answer-runs/$($cancelMessage.answer_run_id)").data
    } catch {
        $cancelledRun = (Invoke-Json "GET" "http://localhost:3000/api/v2/workspaces/$workspace/answer-runs/$($cancelMessage.answer_run_id)").data
    }
    $cancelTerminalValid = ($cancelledRun.status -eq "CANCELLED" -and $cancelledRun.revision_status -eq "PARTIAL") -or ($cancelledRun.status -eq "COMPLETED" -and $cancelledRun.revision_status -eq "FINAL")
    Add-Check "answer_cancel_terminal" $cancelTerminalValid "status=$($cancelledRun.status) revision=$($cancelledRun.revision_status)"
    if ($cancelledRun.status -eq "CANCELLED") {
        $redisCancel = (docker exec noteweave-v2-redis redis-cli GET "cancel:answer:$($cancelMessage.answer_run_id)").Trim()
        Add-Check "answer_redis_cancel_signal" ($redisCancel -eq "1") "signal=$redisCancel"
    }

    $report = [ordered]@{ measured_at = (Get-Date).ToString("o"); run_id = $runId; passed = $true; checks = $checks }
} catch {
    $report = [ordered]@{ measured_at = (Get-Date).ToString("o"); run_id = $runId; passed = $false; error = $_.Exception.Message; checks = $checks }
} finally {
    if (Test-Path $tempFile) { Remove-Item -LiteralPath $tempFile -Force }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$jsonPath = Join-Path $OutputDirectory "phase0a-smoke-$runId.json"
$report | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 $jsonPath
Write-Output "report=$jsonPath"
Write-Output ($report | ConvertTo-Json -Depth 12 -Compress)
if (-not $report.passed) { exit 2 }
