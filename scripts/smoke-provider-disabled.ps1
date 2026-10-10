param(
    [string]$BaseUrl = "http://localhost:3000",
    [string]$BackendUrl = "http://localhost:8081",
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$runId = (Get-Date -Format "yyyyMMddHHmmss") + "-" + (Get-Random -Maximum 9999)
# 同时用于 Windows 本机和 Linux CI：临时目录与 curl 可执行文件名按平台区分
$tempFile = Join-Path ([System.IO.Path]::GetTempPath()) "noteweave-provider-disabled-$runId.md"
$curl = if ($IsWindows -or $env:OS -eq "Windows_NT") { "curl.exe" } else { "curl" }

function Invoke-Json {
    param([string]$Method, [string]$Uri, [object]$Body = $null)
    if ($null -eq $Body) { return Invoke-RestMethod -Method $Method -Uri $Uri }
    return Invoke-RestMethod -Method $Method -Uri $Uri -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 12)
}

function Wait-Until {
    param([scriptblock]$Probe, [string]$Description)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try { $value = & $Probe; if ($null -ne $value) { return $value } } catch { }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description"
}

try {
    # Compose 默认不把 Backend 端口映射到宿主机；直连不可达时改为经前端同源 /api 代理确认 Backend 可用。
    $health = Wait-Until {
        try {
            $response = Invoke-RestMethod "$BackendUrl/actuator/health" -TimeoutSec 3
            if ($response.status -eq "UP") { return $response }
        } catch { }
        $workspaces = Invoke-RestMethod "$BaseUrl/api/v2/workspaces" -TimeoutSec 5
        if ($workspaces.success -eq $true) { [pscustomobject]@{ status = "UP" } }
    } "backend health"
    if ($health.status -ne "UP") { throw "Backend health is not UP" }

    $workspace = (Invoke-Json "POST" "$BaseUrl/api/v2/workspaces" @{ name = "Provider disabled smoke $runId"; description = "fail-closed vertical smoke" }).data.workspace_id
    $content = [Text.Encoding]::UTF8.GetBytes("# Provider disabled smoke $runId`n`nThis source must reach READY before the answer is attempted.")
    [IO.File]::WriteAllBytes($tempFile, $content)
    $upload = (Invoke-Json "POST" "$BaseUrl/api/v2/workspaces/$workspace/uploads" @{ file_name = "smoke.md"; file_size = $content.Length; mime_type = "text/markdown"; chunk_size = $content.Length; total_chunks = 1 }).data
    $md5 = [Convert]::ToBase64String(([Security.Cryptography.MD5]::Create().ComputeHash($content)))
    $chunk = & $curl -sS -X PUT -H "Content-Type: application/octet-stream" -H "Content-MD5: $md5" --data-binary "@$tempFile" "$BaseUrl/api/v2/uploads/$($upload.upload_id)/chunks/0"
    $chunkBody = ($chunk -join "`n") | ConvertFrom-Json
    if ($chunkBody.data.accepted -ne $true) { throw "Upload chunk was not accepted: $($chunk -join ' ')" }
    $complete = (Invoke-Json "POST" "$BaseUrl/api/v2/uploads/$($upload.upload_id)/complete").data
    $task = Wait-Until {
        $state = (Invoke-Json "GET" "$BaseUrl/api/v2/tasks/$($complete.task_id)").data
        if ($state.task_status -in @("COMPLETED", "FAILED", "CANCELLED")) { $state }
    } "source parse task terminal state"
    if ($task.task_status -ne "COMPLETED") { throw "Source parse did not complete: $($task.task_status)" }
    $source = Wait-Until {
        $sources = (Invoke-Json "GET" "$BaseUrl/api/v2/workspaces/$workspace/sources").data
        $item = $sources | Where-Object source_id -eq $complete.source_id | Select-Object -First 1
        if ($null -ne $item -and $item.status -in @("READY", "FAILED")) { $item }
    } "source projection terminal state"
    if ($source.status -ne "READY") { throw "Source projection did not reach READY: $($source.status)" }

    $conversation = (Invoke-Json "POST" "$BaseUrl/api/v2/workspaces/$workspace/conversations" @{ title = "Provider disabled smoke"; conversation_type = "QA" }).data.conversation_id
    $message = $null
    try {
        $message = (Invoke-Json "POST" "$BaseUrl/api/v2/conversations/$conversation/messages" @{ answer_mode = "QA"; client_request_id = "provider-disabled-$runId"; content = "Summarize the uploaded source." }).data
    } catch {
        $message = $null
    }
    if ($null -ne $message -and $message.answer_run_id) {
        $run = Wait-Until {
            $state = (Invoke-Json "GET" "$BaseUrl/api/v2/workspaces/$workspace/answer-runs/$($message.answer_run_id)").data
            if ($state.status -in @("COMPLETED", "FAILED", "CANCELLED")) { $state }
        } "answer run terminal state"
        if ($run.status -eq "COMPLETED") { throw "Provider-disabled answer unexpectedly completed" }
    }
    Write-Output ([ordered]@{ passed = $true; boundary = "source READY followed by explicit provider-disabled answer failure"; workspace_id = $workspace; run_id = $runId } | ConvertTo-Json -Depth 8)
} finally {
    if (Test-Path -LiteralPath $tempFile) { Remove-Item -LiteralPath $tempFile -Force }
}
