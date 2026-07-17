[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4h-h4-(stale|unavailable)-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,

    [Parameter(Mandatory = $true)]
    [ValidateSet('stale', 'unavailable')]
    [string] $FaultMode,

    [string] $EvidenceDirectory,
    [switch] $KeepProject
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = $PSScriptRoot
$ma4gRoot = [IO.Path]::GetFullPath((Join-Path $fixtureRoot '..\ma4g'))
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $EvidenceDirectory = Join-Path $fixtureRoot "evidence\$ProjectName-$stamp"
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$previousFaultMode = $env:MA4H_HEARTBEAT_FAULT_MODE
$env:MA4H_HEARTBEAT_FAULT_MODE = $FaultMode
$compose = @('compose', '--project-name', $ProjectName,
    '--file', (Join-Path $ma4gRoot 'docker-compose.yml'),
    '--file', (Join-Path $fixtureRoot 'docker-compose.heartbeat-fault.yml'),
    '--profile', 'workers')

function Save-Text { param([string] $Name, [AllowEmptyString()][string] $Value) [IO.File]::WriteAllText((Join-Path $EvidenceDirectory $Name), $Value + [Environment]::NewLine, $utf8) }
function Invoke-DockerCaptured {
    param([string[]] $Arguments)
    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& docker @Arguments 2>&1); $exitCode = $LASTEXITCODE
        $output = ($lines | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { [string] $_ } } | Out-String).TrimEnd()
    } finally { $ErrorActionPreference = $prior }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}
function Require-Docker { param([string[]] $Arguments) $r = Invoke-DockerCaptured $Arguments; if ($r.ExitCode -ne 0) { throw "docker failed (exit=$($r.ExitCode)): $($Arguments -join ' ')`n$($r.Output)" }; $r.Output }
function Compose { param([string[]] $Arguments) Require-Docker @($compose + $Arguments) }
function Compose-Log { param([string] $Name, [string[]] $Arguments) Save-Text $Name (Compose $Arguments) }
function Mysql { param([string] $Sql) Compose @('exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql', 'mysql', '-unoteweave', '--batch', '--raw', '--skip-column-names', 'noteweave', '-e', $Sql) }
function Wait-Marker {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $probe = Invoke-DockerCaptured @($compose + @('exec', '-T', 'worker-a', 'test', '-f', '/control/heartbeat-phases'))
        if ($probe.ExitCode -eq 0) { return }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw 'heartbeat fault phase marker was not observed'
}
function Wait-WorkerFailure {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $id = (Compose @('ps', '--all', '--quiet', 'worker-a')).Trim()
        if ($id -match '^[0-9a-f]{12,64}$') {
            $inspect = Require-Docker @('inspect', $id) | ConvertFrom-Json
            if (-not [bool] $inspect[0].State.Running) {
                Save-Text 'worker-a-exit.json' (Require-Docker @('inspect', $id))
                if ([int] $inspect[0].State.ExitCode -eq 0) { throw 'faulted worker unexpectedly exited successfully' }
                return
            }
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw 'faulted worker did not exit'
}
function Wait-Mysql { param([string] $Sql, [string] $Expected, [string] $Label, [int] $TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do { $actual = (Mysql $Sql).Trim(); if ($actual -eq $Expected) { return }; Start-Sleep -Milliseconds 250 } while ((Get-Date) -lt $deadline)
    throw "$Label did not become '$Expected'; actual='$actual'"
}

$verified = $false
try {
    Compose-Log 'compose-config.yml' @('config')
    Compose-Log 'build.log' @('build', 'backend', 'worker-a')
    Compose @('up', '--detach', '--wait', '--wait-timeout', '300', 'mysql', 'redis', 'kafka', 'kafka-init', 'backend') | Out-Null
    Compose-Log 'seed-g1.log' @('--profile', 'fixture', 'run', '--rm', 'seed-g1')
    Compose-Log 'coordinator-dispatch.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/dispatch')
    $taskId = (Mysql "select id from research_agent_task where research_run_id='00000000-0000-0000-0000-00000000a002' order by id limit 1").Trim()
    if ($taskId -notmatch '^[0-9a-f-]{36}$') { throw "Expected one task id; actual='$taskId'" }
    Save-Text 'task-id.txt' $taskId
    Compose @('--profile', 'workers', 'up', '--detach', 'worker-a') | Out-Null
    Compose-Log 'coordinator-publish.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/publish?limit=100')
    Wait-Marker
    Wait-WorkerFailure
    Compose-Log 'worker-a.log' @('logs', '--no-color', 'worker-a')
    # worker-a has already exited by design.  Use a short one-off container
    # to read the named control volume instead of `compose exec`.
    $phaseRead = Compose @('run', '--rm', '--no-deps', '--entrypoint', 'sh', 'worker-a', '-ec', 'cat /control/heartbeat-phases 2>/dev/null || true')
    # Compose can prefix `run` output with container lifecycle lines; retain
    # only the closed set emitted by the fixture itself.
    $phases = (($phaseRead -split "`r?`n" | Where-Object { $_ -in @('search', 'fetch', 'read', 'extract') }) -join "`n").Trim()
    Save-Text 'phase-log.txt' $phases
    if ($phases -ne 'search') { throw "heartbeat fault reached unexpected phases: '$phases'" }
    Save-Text 'after-fault.tsv' (Mysql "select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where id='$taskId'; select count(*) from research_agent_execution where research_agent_task_id='$taskId'; select count(*) from research_agent_completion where research_agent_task_id='$taskId'; select count(*) from source_evidence where research_run_id='00000000-0000-0000-0000-00000000a002';")
    if ((Mysql "select count(*) from research_agent_execution where research_agent_task_id='$taskId'").Trim() -ne '0') { throw 'faulted worker wrote execution' }
    if ((Mysql "select count(*) from research_agent_completion where research_agent_task_id='$taskId'").Trim() -ne '0') { throw 'faulted worker wrote completion' }
    Compose-Log 'expire.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent-tasks/expire')
    Wait-Mysql "select status from research_agent_task where id='$taskId'" 'EXPIRED' 'DB-clock lease reaper'
    Compose @('--profile', 'workers', 'up', '--detach', 'worker-b') | Out-Null
    Wait-Mysql "select status from research_agent_task where id='$taskId'" 'SUBMITTED' 'recovery completion' 180
    Save-Text 'after-recovery.tsv' (Mysql "select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where id='$taskId'; select count(*) from research_agent_execution where research_agent_task_id='$taskId'; select count(*) from research_agent_completion where research_agent_task_id='$taskId';")
    if ((Mysql "select lease_epoch from research_agent_task where id='$taskId'").Trim() -ne '2') { throw 'recovery did not use epoch 2' }
    if ((Mysql "select count(*) from research_agent_execution where research_agent_task_id='$taskId'").Trim() -ne '1') { throw 'recovery did not write exactly one execution' }
    if ((Mysql "select count(*) from research_agent_completion where research_agent_task_id='$taskId'").Trim() -ne '1') { throw 'recovery did not write exactly one completion' }
    Compose-Log 'worker-b.log' @('logs', '--no-color', 'worker-b')
    Compose-Log 'kafka-recovery-group.log' @('--profile', 'fixture', 'run', '--rm', '--env', 'MA4G_KAFKA_GROUP_ID=noteweave-ma4g-workers', 'kafka-tools', 'sh', '/fixture/verify-kafka.sh')
    $verified = $true
}
finally {
    if ($null -eq $previousFaultMode) { Remove-Item Env:MA4H_HEARTBEAT_FAULT_MODE -ErrorAction SilentlyContinue } else { $env:MA4H_HEARTBEAT_FAULT_MODE = $previousFaultMode }
    if (-not $KeepProject) {
        $cleanup = Invoke-DockerCaptured @($compose + @('down', '--volumes', '--remove-orphans')); Save-Text 'cleanup.log' $cleanup.Output
        if ($cleanup.ExitCode -ne 0) { throw 'heartbeat fault fixture cleanup failed' }
        $containers = Require-Docker @('ps', '--all', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}')
        $volumes = Require-Docker @('volume', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.Name}}')
        $networks = Require-Docker @('network', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}')
        Save-Text 'cleanup-verification.txt' "containers=$containers`nvolumes=$volumes`nnetworks=$networks"
        if ($containers -or $volumes -or $networks) { throw 'project-scoped resources remain after cleanup' }
    }
    Save-Text 'manifest.json' (([ordered]@{ schema_version = 'noteweave-ma4h-heartbeat-fault-evidence.v1'; status = if ($verified) { 'VERIFIED' } else { 'FAILED' }; project_name = $ProjectName; fault_mode = $FaultMode; completed_at_utc = (Get-Date).ToUniversalTime().ToString('o'); boundary = 'deterministic fake-provider heartbeat fault and DB-clock recovery; no real provider cancellation evidence' }) | ConvertTo-Json)
}
if (-not $verified) { throw 'MA4H heartbeat fault verification did not complete' }
Write-Host "MA4H heartbeat fault VERIFIED: $EvidenceDirectory"
