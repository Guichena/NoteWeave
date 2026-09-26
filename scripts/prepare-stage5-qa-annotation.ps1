param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$OutputPath = "backend/target/retrieval-eval/stage5-real-qa-request.json",
    [string]$DatasetVersion = "",
    [string]$BearerToken = "",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($DatasetVersion)) {
    $DatasetVersion = "stage5-real-qa-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

function Get-ApiHeaders {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $headers["Authorization"] = "Bearer $BearerToken"
    }
    return $headers
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = Get-ApiHeaders
    }
    if ($null -ne $Body) {
        $parameters["ContentType"] = "application/json"
        $parameters["Body"] = $Body | ConvertTo-Json -Depth 12
    }
    $response = Invoke-RestMethod @parameters
    if (-not $response.success) {
        throw "API request failed: $Method $Uri code=$($response.code) message=$($response.message)"
    }
    return $response.data
}

function Send-UploadChunk {
    param(
        [string]$UploadId,
        [string]$FilePath,
        [string]$ContentMd5
    )
    $arguments = @(
        "-sS",
        "-X", "PUT",
        "-H", "Content-Type: application/octet-stream",
        "-H", "Content-MD5: $ContentMd5"
    )
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $arguments += @("-H", "Authorization: Bearer $BearerToken")
    }
    $arguments += @(
        "--data-binary", "@$FilePath",
        "$BaseUrl/api/v2/uploads/$UploadId/chunks/0"
    )
    $raw = & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Upload chunk failed for upload $UploadId"
    }
    $response = ($raw -join "`n") | ConvertFrom-Json
    if (-not $response.success -or -not $response.data.accepted) {
        throw "Upload chunk was not accepted for upload $UploadId"
    }
}

function Wait-TaskCompleted {
    param([string]$TaskId)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $task = Invoke-Json "GET" "$BaseUrl/api/v2/tasks/$TaskId"
        if ($task.task_status -eq "COMPLETED") {
            return
        }
        if ($task.task_status -in @("FAILED", "CANCELLED")) {
            throw "Source parse task $TaskId ended as $($task.task_status)"
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Source parse task $TaskId did not complete within $TimeoutSeconds seconds"
}

function Wait-SourceReady {
    param(
        [string]$WorkspaceId,
        [string]$SourceId
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $sources = Invoke-Json "GET" "$BaseUrl/api/v2/workspaces/$WorkspaceId/sources"
        $source = $sources | Where-Object source_id -eq $SourceId | Select-Object -First 1
        if ($null -ne $source -and $source.status -eq "READY" -and $source.index_status -eq "INDEXED") {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Source $SourceId did not become READY/INDEXED within $TimeoutSeconds seconds"
}

function Upload-Document {
    param(
        [string]$WorkspaceId,
        [string]$RelativePath
    )
    $filePath = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        throw "QA annotation document does not exist: $filePath"
    }
    $file = Get-Item -LiteralPath $filePath
    $upload = Invoke-Json "POST" "$BaseUrl/api/v2/workspaces/$WorkspaceId/uploads" @{
        file_name = $file.Name
        file_size = $file.Length
        mime_type = "text/markdown"
        chunk_size = $file.Length
        total_chunks = 1
    }
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $contentMd5 = [Convert]::ToBase64String($md5.ComputeHash([IO.File]::ReadAllBytes($file.FullName)))
    } finally {
        $md5.Dispose()
    }
    Send-UploadChunk $upload.upload_id $file.FullName $contentMd5
    $completed = Invoke-Json "POST" "$BaseUrl/api/v2/uploads/$($upload.upload_id)/complete"
    Wait-TaskCompleted $completed.task_id
    Wait-SourceReady $WorkspaceId $completed.source_id
    return $completed.source_id
}

$documents = @(
    [ordered]@{
        id = "project-capabilities"
        relative_path = "README.md"
        query = "What are the main NoteWeave v2 capabilities and module boundaries?"
        top_k = 5
    },
    [ordered]@{
        id = "qa-annotation-workflow"
        relative_path = "backend/README.md"
        query = "How do I capture and compile a reviewed QA retrieval annotation draft?"
        top_k = 5
    },
    [ordered]@{
        id = "research-capability-coverage"
        relative_path = "scripts/fixtures/research/ResearchAgent-Capability-Coverage.md"
        query = "Which ResearchAgent capabilities are covered and how are they verified?"
        top_k = 6
    }
)

$workspace = Invoke-Json "POST" "$BaseUrl/api/v2/workspaces" @{
    name = "Stage 5 QA Annotation $DatasetVersion"
    description = "Real repository design documents for a PENDING QA annotation draft; not reviewed gold."
}
$cases = New-Object System.Collections.Generic.List[object]
$sourceIdsByDocument = @{}
foreach ($document in $documents) {
    $sourceId = Upload-Document $workspace.workspace_id $document.relative_path
    $sourceIdsByDocument[$document.id] = $sourceId
    $cases.Add([ordered]@{
        id = $document.id
        workspaceId = $workspace.workspace_id
        query = $document.query
        allowedSourceIds = @($sourceId)
        topK = $document.top_k
    })
}

$additionalCases = @(
    [ordered]@{
        id = "project-capabilities-paraphrase"
        document_id = "project-capabilities"
        query = "Describe NoteWeave v2's main capabilities, module boundaries, and Java/Python responsibility split."
        top_k = 5
    },
    [ordered]@{
        id = "qa-workflow-source-scope-refusal"
        document_id = "project-capabilities"
        query = "How do I capture and compile a reviewed QA retrieval annotation draft?"
        top_k = 5
    },
    [ordered]@{
        id = "unknown-feature-refusal"
        document_id = "project-capabilities"
        query = "How does NoteWeave implement quantum banana theorem proof generation?"
        top_k = 5
    },
    [ordered]@{
        id = "artifact-version-capabilities"
        document_id = "qa-annotation-workflow"
        query = "How are Artifact versions saved, regenerated, compared, rolled back, and exported?"
        top_k = 5
    },
    [ordered]@{
        id = "research-safety-verification"
        document_id = "research-capability-coverage"
        query = "How are HTTP fetch, SSRF, prompt injection, and citation support verified?"
        top_k = 5
    }
)
foreach ($case in $additionalCases) {
    $cases.Add([ordered]@{
        id = $case.id
        workspaceId = $workspace.workspace_id
        query = $case.query
        allowedSourceIds = @($sourceIdsByDocument[$case.document_id])
        topK = $case.top_k
    })
}

$request = [ordered]@{
    schemaVersion = "retrieval-qa-annotation-request-v1"
    datasetVersion = $DatasetVersion
    candidatePoolSize = 12
    cases = $cases
}
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $repoRoot $OutputPath
}
$outputDirectory = Split-Path -Parent $resolvedOutput
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}
$request | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resolvedOutput -Encoding utf8

[ordered]@{
    dataset_version = $DatasetVersion
    workspace_id = $workspace.workspace_id
    source_count = $documents.Count
    case_count = $cases.Count
    annotation_status = "NOT_CAPTURED"
    request_path = $resolvedOutput
} | ConvertTo-Json -Depth 5
