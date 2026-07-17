[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4h-h3-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,

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
$compose = @('compose', '--project-name', $ProjectName,
    '--file', (Join-Path $ma4gRoot 'docker-compose.yml'),
    '--file', (Join-Path $fixtureRoot 'docker-compose.drain.yml'),
    '--profile', 'workers')

function Save-Text {
    param([string] $Name, [AllowEmptyString()][string] $Value)
    [IO.File]::WriteAllText((Join-Path $EvidenceDirectory $Name), $Value + [Environment]::NewLine, $utf8)
}
function Invoke-DockerCaptured {
    param([string[]] $Arguments)
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output = ($lines | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message }
            else { [string] $_ }
        } | Out-String).TrimEnd()
    }
    finally { $ErrorActionPreference = $previous }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}
function Require-Docker {
    param([string[]] $Arguments)
    $result = Invoke-DockerCaptured $Arguments
    if ($result.ExitCode -ne 0) { throw "docker failed (exit=$($result.ExitCode)): $($Arguments -join ' ')`n$($result.Output)" }
    $result.Output
}
function Compose { param([string[]] $Arguments) Require-Docker @($compose + $Arguments) }
function Compose-Log { param([string] $Name, [string[]] $Arguments) Save-Text $Name (Compose $Arguments) }
function Mysql { param([string] $Sql) Compose @('exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql', 'mysql', '-unoteweave', '--batch', '--raw', '--skip-column-names', 'noteweave', '-e', $Sql) }
function Wait-Mysql {
    param([string] $Sql, [string] $Expected, [string] $Label, [int] $TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $actual = (Mysql $Sql).Trim()
        if ($actual -eq $Expected) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "$Label did not become '$Expected'; actual='$actual'"
}
function Wait-Marker {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $probe = Invoke-DockerCaptured @($compose + @('exec', '-T', 'worker-a', 'test', '-f', '/control/phase-started'))
        if ($probe.ExitCode -eq 0) { return }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw 'MA4H drain phase marker was not observed'
}
function Wait-WorkerExit {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $id = (Compose @('ps', '--all', '--quiet', 'worker-a')).Trim()
        if ($id -match '^[0-9a-f]{12,64}$') {
            $inspect = Require-Docker @('inspect', $id) | ConvertFrom-Json
            if (-not [bool] $inspect[0].State.Running) {
                Save-Text 'worker-exit.json' (Require-Docker @('inspect', $id))
                if ([int] $inspect[0].State.ExitCode -ne 75) {
                    throw "worker-a exit code must be 75 after drain grace; actual=$($inspect[0].State.ExitCode)"
                }
                return
            }
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw 'worker-a did not exit after drain grace'
}

$verified = $false
try {
    Compose-Log 'compose-config.yml' @('config')
    Compose-Log 'build.log' @('build', 'backend', 'worker-a')
    Compose @('up', '--detach', '--wait', '--wait-timeout', '300', 'mysql', 'redis', 'kafka', 'kafka-init', 'backend') | Out-Null
    Compose-Log 'seed-g1.log' @('--profile', 'fixture', 'run', '--rm', 'seed-g1')
    Compose-Log 'coordinator-dispatch.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/dispatch')
    $taskId = (Mysql "select id from research_agent_task where research_run_id='00000000-0000-0000-0000-00000000a002' order by id limit 1").Trim()
    if ($taskId -notmatch '^[0-9a-f-]{36}$') { throw "Expected exactly one task id after dispatch; actual='$taskId'" }
    Save-Text 'task-id.txt' $taskId

    Compose @('--profile', 'workers', 'up', '--detach', 'worker-a') | Out-Null
    Compose-Log 'coordinator-publish.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/publish?limit=100')
    Wait-Marker
    Save-Text 'before-sigterm.tsv' (Mysql "select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where id='$taskId'; select count(*) execution_count from research_agent_execution where research_agent_task_id='$taskId'; select count(*) completion_count from research_agent_completion where research_agent_task_id='$taskId';")
    Compose @('kill', '-s', 'SIGTERM', 'worker-a') | Out-Null
    Wait-WorkerExit
    Compose-Log 'worker-a-after-sigterm.log' @('logs', '--no-color', 'worker-a')
    Save-Text 'after-grace.tsv' (Mysql "select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where id='$taskId'; select count(*) execution_count from research_agent_execution where research_agent_task_id='$taskId'; select count(*) completion_count from research_agent_completion where research_agent_task_id='$taskId'; select count(*) evidence_count from source_evidence where research_run_id='00000000-0000-0000-0000-00000000a002';")
    if ((Mysql "select count(*) from research_agent_execution where research_agent_task_id='$taskId'").Trim() -ne '0') { throw 'grace-expired worker wrote execution' }
    if ((Mysql "select count(*) from research_agent_completion where research_agent_task_id='$taskId'").Trim() -ne '0') { throw 'grace-expired worker wrote completion' }

    Start-Sleep -Seconds 5
    Compose-Log 'expire.json' @('exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST', '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token', 'http://127.0.0.1:8081/internal/research-agent-tasks/expire')
    Wait-Mysql "select status from research_agent_task where id='$taskId'" 'EXPIRED' 'DB-clock lease reaper'
    Save-Text 'after-reaper.tsv' (Mysql "select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where id='$taskId'")

    Compose @('--profile', 'workers', 'up', '--detach', 'worker-a') | Out-Null
    $deadline = (Get-Date).AddSeconds(180)
    do {
        $submitted = (Mysql "select count(*) from research_agent_task where id='$taskId' and status='SUBMITTED'").Trim()
        if ($submitted -eq '1') { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if ($submitted -ne '1') { throw 'recovered worker did not atomically complete task' }
    Save-Text 'after-recovery.tsv' (Mysql "select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where id='$taskId'; select count(*) execution_count from research_agent_execution where research_agent_task_id='$taskId'; select count(*) completion_count from research_agent_completion where research_agent_task_id='$taskId';")
    if ((Mysql "select lease_epoch from research_agent_task where id='$taskId'").Trim() -ne '2') { throw 'recovery did not use a new lease epoch' }
    if ((Mysql "select count(*) from research_agent_execution where research_agent_task_id='$taskId'").Trim() -ne '1') { throw 'recovery did not produce exactly one execution' }
    if ((Mysql "select count(*) from research_agent_completion where research_agent_task_id='$taskId'").Trim() -ne '1') { throw 'recovery did not produce exactly one completion' }
    Compose-Log 'worker-a-after-recovery.log' @('logs', '--no-color', 'worker-a')
    Compose-Log 'kafka-verification.log' @('--profile', 'fixture', 'run', '--rm', '--env', 'MA4G_KAFKA_GROUP_ID=noteweave-ma4h-drain-workers', 'kafka-tools', 'sh', '/fixture/verify-kafka.sh')
    $verified = $true
}
finally {
    if (-not $KeepProject) {
        $cleanup = Invoke-DockerCaptured @($compose + @('down', '--volumes', '--remove-orphans'))
        Save-Text 'cleanup.log' $cleanup.Output
        if ($cleanup.ExitCode -ne 0) { throw 'drain fixture cleanup failed' }
        $containers = Require-Docker @('ps', '--all', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}')
        $volumes = Require-Docker @('volume', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.Name}}')
        $networks = Require-Docker @('network', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}')
        Save-Text 'cleanup-verification.txt' "containers=$containers`nvolumes=$volumes`nnetworks=$networks"
        if ($containers -or $volumes -or $networks) { throw 'project-scoped resources remain after drain fixture cleanup' }
    }
    Save-Text 'manifest.json' (([ordered]@{
        schema_version = 'noteweave-ma4h-drain-evidence.v1'
        status = if ($verified) { 'VERIFIED' } else { 'FAILED' }
        project_name = $ProjectName
        completed_at_utc = (Get-Date).ToUniversalTime().ToString('o')
        boundary = 'deterministic fake-provider SIGTERM/drain/recovery; no real provider cancellation evidence'
    }) | ConvertTo-Json)
}
if (-not $verified) { throw 'MA4H drain verification did not complete' }
Write-Host "MA4H DRAIN VERIFIED: $EvidenceDirectory"
