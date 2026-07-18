param(
    [string]$WorkspaceName = "Stage 5 QA Annotation stage5-real-qa-20260715-r4",
    [string]$DatasetVersion = "stage5-real-qa-r6-recovered-20260716",
    [string]$OutputPath = "backups/stage5-r6-recovered/raw/stage5-r6-recovered-request.json",
    [string]$MySqlContainer = "noteweave-v2-mysql",
    [string]$ElasticsearchUri = "http://localhost:9200",
    [string]$IndexPrefix = "noteweave_chunk"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($WorkspaceName -notmatch '^[A-Za-z0-9 ._-]{1,200}$') {
    throw "Workspace name contains unsupported characters"
}
if ($DatasetVersion -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$') {
    throw "Dataset version must be a safe identifier"
}

function Invoke-MySqlRead {
    param([string]$Sql)

    $output = @($Sql | & docker.exe exec -i $MySqlContainer sh -lc `
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D "$MYSQL_DATABASE" --batch --raw --skip-column-names')
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL recovery read failed"
    }
    return $output
}

$escapedWorkspaceName = $WorkspaceName.Replace("'", "''")
$rows = Invoke-MySqlRead @"
select w.id,
       s.id,
       lower(ss.sha256),
       w.status,
       s.status,
       s.index_status,
       ss.index_status,
       sum(case when c.projection_status = 'PROJECTED' then 1 else 0 end)
from workspace w
join source s on s.workspace_id = w.id
join source_snapshot ss
  on ss.source_id = s.id
 and ss.version_no = (
     select max(latest.version_no)
     from source_snapshot latest
     where latest.source_id = s.id
 )
left join source_chunk c on c.source_snapshot_id = ss.id
where w.name = '$escapedWorkspaceName'
group by w.id, s.id, ss.sha256, w.status, s.status, s.index_status, ss.index_status
order by s.created_at, s.id;
"@

if ($rows.Count -ne 3) {
    throw "Recovery corpus must contain exactly three current Sources"
}

$sources = foreach ($row in $rows) {
    $parts = $row -split "`t"
    if ($parts.Count -ne 8) {
        throw "Recovery corpus row shape is invalid"
    }
    [pscustomobject]@{
        WorkspaceId = $parts[0]
        SourceId = $parts[1]
        SnapshotSha256 = $parts[2]
        WorkspaceStatus = $parts[3]
        SourceStatus = $parts[4]
        SourceIndexStatus = $parts[5]
        SnapshotIndexStatus = $parts[6]
        ProjectedChunkCount = [int]$parts[7]
    }
}

$workspaceIds = @($sources | Select-Object -ExpandProperty WorkspaceId -Unique)
$invalidWorkspaceStates = @($sources | Where-Object { $_.WorkspaceStatus -ne "ACTIVE" }).Count
$invalidSourceStates = @($sources | Where-Object { $_.SourceStatus -ne "READY" }).Count
$invalidSourceIndexStates = @($sources | Where-Object { $_.SourceIndexStatus -ne "INDEXED" }).Count
$invalidSnapshotIndexStates = @($sources | Where-Object { $_.SnapshotIndexStatus -ne "INDEXED" }).Count
$projectedChunkCount = ($sources | Measure-Object ProjectedChunkCount -Sum).Sum
if ($workspaceIds.Count -ne 1 -or
        $invalidWorkspaceStates -ne 0 -or
        $invalidSourceStates -ne 0 -or
        $invalidSourceIndexStates -ne 0 -or
        $invalidSnapshotIndexStates -ne 0 -or
        $projectedChunkCount -ne 16) {
    throw "Recovery corpus does not satisfy the 3 Source / 16 projected chunk invariant"
}

$sourceBySnapshotHash = @{}
foreach ($source in $sources) {
    if ($sourceBySnapshotHash.ContainsKey($source.SnapshotSha256)) {
        throw "Recovery corpus Snapshot hashes must be unique"
    }
    $sourceBySnapshotHash[$source.SnapshotSha256] = $source.SourceId
}

$documents = @(
    [ordered]@{
        id = "project-capabilities"
        path = "README.md"
        query = "What are the main NoteWeave v2 capabilities and module boundaries?"
        topK = 5
    },
    [ordered]@{
        id = "qa-annotation-workflow"
        path = "backend/README.md"
        query = "How do I capture and compile a reviewed QA retrieval annotation draft?"
        topK = 5
    },
    [ordered]@{
        id = "research-capability-coverage"
        path = "docs/ResearchAgent-Capability-Coverage.md"
        query = "Which ResearchAgent capabilities are covered and how are they verified?"
        topK = 6
    }
)

$sourceIdsByDocument = @{}
$cases = New-Object System.Collections.Generic.List[object]
foreach ($document in $documents) {
    $file = Join-Path $repoRoot $document.path
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Recovery corpus source file is missing"
    }
    $fileHash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not $sourceBySnapshotHash.ContainsKey($fileHash)) {
        throw "Recovery corpus source file has drifted from its indexed Snapshot"
    }
    $sourceId = $sourceBySnapshotHash[$fileHash]
    $sourceIdsByDocument[$document.id] = $sourceId
    $cases.Add([ordered]@{
        id = $document.id
        workspaceId = $workspaceIds[0]
        query = $document.query
        allowedSourceIds = @($sourceId)
        topK = $document.topK
    })
}

$additionalCases = @(
    [ordered]@{
        id = "project-capabilities-paraphrase"
        documentId = "project-capabilities"
        query = "Describe NoteWeave v2's main capabilities, module boundaries, and Java/Python responsibility split."
        topK = 5
    },
    [ordered]@{
        id = "qa-workflow-source-scope-refusal"
        documentId = "project-capabilities"
        query = "How do I capture and compile a reviewed QA retrieval annotation draft?"
        topK = 5
    },
    [ordered]@{
        id = "unknown-feature-refusal"
        documentId = "project-capabilities"
        query = "How does NoteWeave implement quantum banana theorem proof generation?"
        topK = 5
    },
    [ordered]@{
        id = "artifact-version-capabilities"
        documentId = "qa-annotation-workflow"
        query = "How are Artifact versions saved, regenerated, compared, rolled back, and exported?"
        topK = 5
    },
    [ordered]@{
        id = "research-safety-verification"
        documentId = "research-capability-coverage"
        query = "How are HTTP fetch, SSRF, prompt injection, and citation support verified?"
        topK = 5
    }
)
foreach ($case in $additionalCases) {
    $cases.Add([ordered]@{
        id = $case.id
        workspaceId = $workspaceIds[0]
        query = $case.query
        allowedSourceIds = @($sourceIdsByDocument[$case.documentId])
        topK = $case.topK
    })
}

$indexName = ($IndexPrefix + "_" + $workspaceIds[0]).ToLowerInvariant()
try {
    $stats = Invoke-RestMethod -Method Get -Uri (
        $ElasticsearchUri.TrimEnd('/') + "/" + $indexName + "/_stats/docs")
    $indexStats = $stats.indices.PSObject.Properties[$indexName].Value
    $liveDocuments = [int]$indexStats.primaries.docs.count
    $deletedDocuments = [int]$indexStats.primaries.docs.deleted
} catch {
    throw "Elasticsearch recovery corpus validation failed"
}
if ($liveDocuments -ne 16 -or $deletedDocuments -ne 0) {
    throw "Elasticsearch recovery corpus must contain 16 live and zero deleted documents"
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
    schema_version = "stage5-r6-recovery-request-summary-v1"
    dataset_version = $DatasetVersion
    workspace_name = $WorkspaceName
    source_count = $sources.Count
    snapshot_count = $sources.Count
    projected_chunk_count = 16
    elasticsearch_live_document_count = $liveDocuments
    elasticsearch_deleted_document_count = $deletedDocuments
    case_count = $cases.Count
    raw_request_is_sensitive = $true
    output_is_git_ignored = $true
} | ConvertTo-Json -Depth 5
