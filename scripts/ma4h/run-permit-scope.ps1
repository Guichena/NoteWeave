[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4h-h5-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,
    [string] $EvidenceDirectory,
    [switch] $KeepProject
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = $PSScriptRoot
$ma4gRoot = [IO.Path]::GetFullPath((Join-Path $fixtureRoot '..\ma4g'))
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $fixtureRoot ("evidence\$ProjectName-" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$compose = @('compose', '--project-name', $ProjectName,
    '--file', (Join-Path $ma4gRoot 'docker-compose.yml'),
    '--file', (Join-Path $fixtureRoot 'docker-compose.drain.yml'),
    '--profile', 'fixture', '--profile', 'workers')

function Save-Text { param([string] $Name, [AllowEmptyString()][string] $Value) [IO.File]::WriteAllText((Join-Path $EvidenceDirectory $Name), $Value + [Environment]::NewLine, $utf8) }
function Invoke-DockerCaptured {
    param([string[]] $Arguments)
    $prior = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $lines = @(& docker @Arguments 2>&1); $exitCode = $LASTEXITCODE; $output = ($lines | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { [string] $_ } } | Out-String).TrimEnd() }
    finally { $ErrorActionPreference = $prior }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}
function Require-Docker { param([string[]] $Arguments) $r = Invoke-DockerCaptured $Arguments; if ($r.ExitCode -ne 0) { throw "docker failed (exit=$($r.ExitCode)): $($Arguments -join ' ')`n$($r.Output)" }; $r.Output }
function Compose { param([string[]] $Arguments) Require-Docker @($compose + $Arguments) }
function Compose-Log { param([string] $Name, [string[]] $Arguments) Save-Text $Name (Compose $Arguments) }
function Mysql { param([string] $Sql) Compose @('exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql', 'mysql', '-unoteweave', '--batch', '--raw', '--skip-column-names', 'noteweave', '-e', $Sql) }
function Invoke-Permit { param([string] $Payload)
    # Compose's Windows argv bridge can strip JSON double quotes from a
    # direct `--data-binary` argument.  Base64 keeps the host-to-container
    # boundary opaque; curl receives the original UTF-8 request bytes.
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Payload))
    $command = "printf %s $encoded | base64 -d | curl --silent --show-error --write-out '\n%{http_code}' -X POST -H 'Content-Type: application/json' -H 'X-NoteWeave-Internal-Token: ma4g-fixture-token' --data-binary @- http://127.0.0.1:8081/internal/research-agent/permits"
    Compose @('exec', '-T', 'backend', 'sh', '-ec', $command)
}
function Wait-Marker {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $probe = Invoke-DockerCaptured @($compose + @('exec', '-T', 'worker-a', 'test', '-f', '/control/phase-started'))
        if ($probe.ExitCode -eq 0) { return }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw 'permit fixture did not observe the worker search-phase marker'
}

$verified = $false
try {
    Compose-Log 'compose-config.yml' @('config')
    Compose-Log 'build.log' @('build', 'backend', 'worker-a')
    Compose @('up', '--detach', '--wait', '--wait-timeout', '300', 'mysql', 'redis', 'kafka', 'kafka-init', 'backend') | Out-Null
    Compose-Log 'seed-g1.log' @('run', '--rm', 'seed-g1')
    Compose-Log 'coordinator-dispatch.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/dispatch')
    $taskId = (Mysql "select id from research_agent_task where research_run_id='00000000-0000-0000-0000-00000000a002' order by id limit 1").Trim()
    if ($taskId -notmatch '^[0-9a-f-]{36}$') { throw "Expected one task id; actual='$taskId'" }
    Save-Text 'task-id.txt' $taskId
    # Claim through the real Worker client instead of duplicating the client
    # transport in this fixture.  The delayed search marker gives the route
    # tests a stable, still-owned lease window.
    Compose @('up', '--detach', 'worker-a') | Out-Null
    Compose-Log 'coordinator-publish.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/publish?limit=100')
    Wait-Marker
    $lease = (Mysql "select lease_epoch,fencing_token from research_agent_task where id='$taskId'").Trim() -split "`t"
    if ($lease.Count -ne 2 -or $lease[0] -notmatch '^\d+$' -or $lease[1] -notmatch '^\d+$') { throw 'claim did not produce lease identity' }
    $base = [ordered]@{ task_id = $taskId; worker_instance_id = 'ma4h-drain-worker-a'; lease_epoch = [int]$lease[0]; fencing_token = [long]$lease[1]; tool_identity = 'search' }
    foreach ($field in @('workspace_id', 'research_run_id', 'role', 'provider_key')) {
        $spoof = [ordered]@{}; foreach ($entry in $base.GetEnumerator()) { $spoof[$entry.Key] = $entry.Value }; $spoof[$field] = "spoofed-$field"
        $response = Invoke-Permit ($spoof | ConvertTo-Json -Compress)
        Save-Text "permit-spoof-$field.txt" $response
        $parts = $response -split "`r?`n"; $status = $parts[-1].Trim()
        if ($status -ne '400' -or $response -notmatch 'RESEARCH_AGENT_PERMIT_INVALID') { throw "spoof field $field was not rejected as invalid" }
    }
    $forbidden = [ordered]@{}; foreach ($entry in $base.GetEnumerator()) { $forbidden[$entry.Key] = $entry.Value }; $forbidden.tool_identity = 'synthesis'
    $forbiddenResponse = Invoke-Permit ($forbidden | ConvertTo-Json -Compress)
    Save-Text 'permit-forbidden-tool.txt' $forbiddenResponse
    if ((($forbiddenResponse -split "`r?`n")[-1]).Trim() -ne '400' -or $forbiddenResponse -notmatch 'RESEARCH_AGENT_PERMIT_TOOL_FORBIDDEN') { throw 'server-derived role allowlist did not reject synthesis' }
    $validResponse = Invoke-Permit ($base | ConvertTo-Json -Compress)
    Save-Text 'permit-valid.txt' $validResponse
    if ((($validResponse -split "`r?`n")[-1]).Trim() -ne '200' -or $validResponse -notmatch '"status":"GRANTED"' -or $validResponse -notmatch '"tool_identity":"search"') { throw 'exact authoritative permit was not granted' }
    Save-Text 'task-state.tsv' (Mysql "select id,status,worker_instance_id,lease_epoch,fencing_token from research_agent_task where id='$taskId'; select count(*) from research_agent_execution where research_agent_task_id='$taskId'; select count(*) from research_agent_completion where research_agent_task_id='$taskId';")
    $verified = $true
}
finally {
    if (-not $verified) {
        # Preserve the server-side exception before project-scoped cleanup so
        # a failed contract round is actionable rather than opaque.
        $backendLogs = Invoke-DockerCaptured @($compose + @('logs', '--no-color', 'backend'))
        Save-Text 'backend-failure.log' $backendLogs.Output
    }
    if (-not $KeepProject) {
        $cleanup = Invoke-DockerCaptured @($compose + @('down', '--volumes', '--remove-orphans')); Save-Text 'cleanup.log' $cleanup.Output
        if ($cleanup.ExitCode -ne 0) { throw 'permit scope fixture cleanup failed' }
        $containers = Require-Docker @('ps', '--all', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}')
        $volumes = Require-Docker @('volume', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.Name}}')
        $networks = Require-Docker @('network', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}')
        Save-Text 'cleanup-verification.txt' "containers=$containers`nvolumes=$volumes`nnetworks=$networks"
        if ($containers -or $volumes -or $networks) { throw 'project-scoped resources remain after cleanup' }
    }
    Save-Text 'manifest.json' (([ordered]@{ schema_version = 'noteweave-ma4h-permit-scope-evidence.v1'; status = if ($verified) { 'VERIFIED' } else { 'FAILED' }; project_name = $ProjectName; completed_at_utc = (Get-Date).ToUniversalTime().ToString('o'); boundary = 'real MySQL/Redis/Kafka backend permit route; no real provider evidence' }) | ConvertTo-Json)
}
if (-not $verified) { throw 'MA4H permit scope verification did not complete' }
Write-Host "MA4H permit scope VERIFIED: $EvidenceDirectory"
