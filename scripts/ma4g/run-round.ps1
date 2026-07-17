[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Migration', 'LockMatrix', 'G1', 'G2', 'G3', 'G4')]
    [string] $Round,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4g-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,

    [ValidateRange(60, 900)]
    [int] $TimeoutSeconds = 300,

    [string] $EvidenceDirectory,

    [switch] $KeepProject
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = $PSScriptRoot
$repoRoot = [IO.Path]::GetFullPath((Join-Path $fixtureRoot '..\..'))
$roundKey = $Round.ToLowerInvariant()
if ($ProjectName -notmatch "^noteweave-ma4g-$roundKey-") {
    throw "Project '$ProjectName' must start with noteweave-ma4g-$roundKey- for round $Round"
}

if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $EvidenceDirectory = Join-Path $fixtureRoot "evidence\$ProjectName-$stamp"
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)

$baseCompose = Join-Path $fixtureRoot 'docker-compose.yml'
$override = switch ($Round) {
    'Migration' { Join-Path $fixtureRoot 'docker-compose.migration.yml' }
    'LockMatrix' { Join-Path $fixtureRoot 'docker-compose.lockmatrix.yml' }
    'G2' { Join-Path $fixtureRoot 'docker-compose.g2.yml' }
    'G3' { Join-Path $fixtureRoot 'docker-compose.g3.yml' }
    'G4' { Join-Path $fixtureRoot 'docker-compose.g4.yml' }
    default { $null }
}
$script:ComposePrefix = @('compose', '--project-name', $ProjectName, '--file', $baseCompose)
if ($null -ne $override) {
    $script:ComposePrefix += @('--file', $override)
}

function Save-Text {
    param([Parameter(Mandatory = $true)][string] $Name, [AllowEmptyString()][string] $Value)
    [IO.File]::WriteAllText((Join-Path $EvidenceDirectory $Name), $Value + [Environment]::NewLine, $utf8)
}

function Invoke-DockerCaptured {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)

    # Docker/Compose writes ordinary progress (for example, BuildKit's
    # "Image ... Building") to stderr.  Windows PowerShell turns that native
    # stderr into ErrorRecord objects and, under the script-wide Stop policy,
    # can abort before LASTEXITCODE is inspected.  Capture both streams under
    # a local Continue policy; the native exit code remains the sole authority.
    $previousErrorActionPreference = $ErrorActionPreference
    $hasNativePreference = Test-Path variable:PSNativeCommandUseErrorActionPreference
    if ($hasNativePreference) { $previousNativePreference = $PSNativeCommandUseErrorActionPreference }
    try {
        $ErrorActionPreference = 'Continue'
        if ($hasNativePreference) { $PSNativeCommandUseErrorActionPreference = $false }
        $nativeOutput = @(& docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output = ($nativeOutput | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) {
                $_.Exception.Message
            }
            else {
                [string] $_
            }
        } | Out-String).TrimEnd()
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        if ($hasNativePreference) { $PSNativeCommandUseErrorActionPreference = $previousNativePreference }
    }
    return [pscustomobject]@{ Output = $output; ExitCode = $exitCode }
}

function Get-DockerOutput {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $result = Invoke-DockerCaptured -Arguments $Arguments
    if ($result.ExitCode -ne 0) {
        throw "docker failed (exit=$($result.ExitCode)): $($Arguments -join ' ')`n$($result.Output)"
    }
    return $result.Output
}

function Get-DockerInspectProjectOwner {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('container', 'volume', 'network')]
        [string] $ResourceType,

        [Parameter(Mandatory = $true)]
        [string] $Identifier
    )

    $inspectArguments = switch ($ResourceType) {
        'container' { @('inspect', $Identifier) }
        'volume' { @('volume', 'inspect', $Identifier) }
        'network' { @('network', 'inspect', $Identifier) }
    }
    $inspectDocuments = @(
        (Get-DockerOutput -Arguments $inspectArguments) | ConvertFrom-Json
    )
    if ($inspectDocuments.Count -ne 1) {
        throw "Expected exactly one inspect document for $ResourceType '$Identifier'; actual=$($inspectDocuments.Count)"
    }

    $labels = if ($ResourceType -eq 'container') {
        $inspectDocuments[0].Config.Labels
    }
    else {
        $inspectDocuments[0].Labels
    }
    if ($null -eq $labels) { return '' }
    $ownerProperty = $labels.PSObject.Properties['com.docker.compose.project']
    if ($null -eq $ownerProperty) { return '' }
    return [string] $ownerProperty.Value
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $output = Get-DockerOutput -Arguments $Arguments
    if ($output) { Write-Output $output }
}

function Get-ComposeOutput {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    return Get-DockerOutput -Arguments @($script:ComposePrefix + $Arguments)
}

function Invoke-Compose {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $output = Get-ComposeOutput -Arguments $Arguments
    if ($output) { Write-Output $output }
}

function Invoke-ComposeCapture {
    param(
        [Parameter(Mandatory = $true)][string] $EvidenceName,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
    $output = Get-ComposeOutput -Arguments $Arguments
    Save-Text -Name $EvidenceName -Value $output
    if ($output) { Write-Host $output }
    return $output
}

function Get-MysqlValue {
    param([Parameter(Mandatory = $true)][string] $Sql)
    return (Get-ComposeOutput -Arguments @(
        'exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql',
        'mysql', '-unoteweave', '--batch', '--raw', '--skip-column-names', 'noteweave', '-e', $Sql
    )).Trim()
}

function Get-LegacyMysqlValue {
    param([Parameter(Mandatory = $true)][string] $Sql)
    return (Get-ComposeOutput -Arguments @(
        'exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql-legacy',
        'mysql', '-unoteweave', '--batch', '--raw', '--skip-column-names', 'noteweave_legacy', '-e', $Sql
    )).Trim()
}

function Wait-MysqlValue {
    param(
        [Parameter(Mandatory = $true)][string] $Sql,
        [Parameter(Mandatory = $true)][string] $Expected,
        [string] $Label = 'database condition'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $actual = Get-MysqlValue -Sql $Sql
        if ($actual -eq $Expected) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$Label did not become '$Expected' before timeout; actual='$actual'"
}

function Wait-LegacyMysqlValue {
    param(
        [Parameter(Mandatory = $true)][string] $Sql,
        [Parameter(Mandatory = $true)][string] $Expected,
        [string] $Label = 'legacy database condition'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try { $actual = Get-LegacyMysqlValue -Sql $Sql }
        catch { $actual = 'UNAVAILABLE' }
        if ($actual -eq $Expected) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$Label did not become '$Expected' before timeout; actual='$actual'"
}

function Wait-ControlMarker {
    param(
        [Parameter(Mandatory = $true)][string] $Service,
        [Parameter(Mandatory = $true)][string] $Path,
        [string] $Label = 'control marker'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $probe = Invoke-DockerCaptured -Arguments @($script:ComposePrefix + @(
            'exec', '-T', $Service, 'test', '-f', $Path
        ))
        if ($probe.ExitCode -eq 0) { return }

        $containerId = (Get-ComposeOutput -Arguments @('ps', '--all', '--quiet', $Service)).Trim()
        if ($containerId -match '^[0-9a-f]{12,64}$') {
            $inspectDocuments = @(
                (Get-DockerOutput -Arguments @('inspect', $containerId)) | ConvertFrom-Json
            )
            if ($inspectDocuments.Count -eq 1 -and -not [bool] $inspectDocuments[0].State.Running) {
                $logs = Get-ComposeOutput -Arguments @('logs', '--no-color', $Service)
                throw "$Label '$Path' could not be observed because service '$Service' exited with code $($inspectDocuments[0].State.ExitCode): $logs"
            }
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "$Label '$Path' was not observed in service '$Service' before timeout"
}

function Invoke-CoordinatorDispatch {
    $output = Invoke-ComposeCapture -EvidenceName 'coordinator-dispatch.json' -Arguments @(
        'exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST',
        '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token',
        'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/dispatch'
    )
    if ($output -notmatch '"code"\s*:\s*"?0"?' -and $output -notmatch '"success"\s*:\s*true') {
        throw "Coordinator dispatch did not return an obvious success envelope: $output"
    }
}

function Invoke-CoordinatorPublish {
    $output = Invoke-ComposeCapture -EvidenceName 'coordinator-publish.json' -Arguments @(
        'exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST',
        '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token',
        'http://127.0.0.1:8081/internal/research-agent/coordinator/runs/00000000-0000-0000-0000-00000000a002/publish?limit=100'
    )
    if ($output -notmatch '"code"\s*:\s*"?0"?' -and $output -notmatch '"success"\s*:\s*true') {
        throw "Coordinator publish did not return an obvious success envelope: $output"
    }
}

function Invoke-Verifier {
    param(
        [Parameter(Mandatory = $true)][string] $EvidenceName,
        [Parameter(Mandatory = $true)][string[]] $Command,
        [string[]] $Environment = @()
    )
    $arguments = @('--profile', 'fixture', 'run', '--rm')
    foreach ($item in $Environment) { $arguments += @('--env', $item) }
    $arguments += @('verifier') + $Command
    Invoke-ComposeCapture -EvidenceName $EvidenceName -Arguments $arguments | Out-Null
}

function Invoke-KafkaTool {
    param(
        [Parameter(Mandatory = $true)][string] $EvidenceName,
        [Parameter(Mandatory = $true)][string[]] $Command,
        [string[]] $Environment = @()
    )
    $arguments = @('--profile', 'fixture', 'run', '--rm')
    foreach ($item in $Environment) { $arguments += @('--env', $item) }
    $arguments += @('kafka-tools') + $Command
    Invoke-ComposeCapture -EvidenceName $EvidenceName -Arguments $arguments | Out-Null
}

function Export-And-VerifyContracts {
    param([Parameter(Mandatory = $true)][int] $ExpectedChildRows)
    Invoke-Verifier -EvidenceName 'contract-export.log' -Command @('sh', '/fixture/export-contracts.sh')
    Invoke-ComposeCapture -EvidenceName 'contract-verification.log' -Arguments @(
        '--profile', 'fixture', 'run', '--rm', 'contract-verifier'
    ) | Out-Null
    Invoke-Verifier -EvidenceName 'contracts.tsv' -Command @('sh', '-ec', 'cat /control/contracts.tsv')
    Invoke-Verifier -EvidenceName 'row-digest-export.log' -Command @('sh', '/fixture/export-row-digests.sh')
    Invoke-ComposeCapture -EvidenceName 'row-digest-verification.log' -Arguments @(
        '--profile', 'fixture', 'run', '--rm',
        '--env', "MA4G_EXPECTED_CHILD_ROWS=$ExpectedChildRows",
        'contract-verifier', 'python', '/fixture/verify_persisted_row_digests.py'
    ) | Out-Null
    Invoke-Verifier -EvidenceName 'row-digests.tsv' -Command @('sh', '-ec', 'cat /control/row-digests.tsv')
}

function Invoke-StateVerification {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('capture', 'assert')][string] $Mode,
        [Parameter(Mandatory = $true)][string] $Label,
        [Parameter(Mandatory = $true)][string] $EvidenceName
    )
    Invoke-Verifier -EvidenceName $EvidenceName -Command @('sh', '/fixture/verify-state.sh', $Mode, $Label)
}

function Invoke-AtomicVerification {
    param([int] $Tasks, [int] $Cells, [int] $Workers)
    Invoke-Verifier -EvidenceName 'atomic-verification.log' -Command @(
        'sh', '/fixture/verify-atomic.sh', "$Tasks", "$Cells", "$Workers"
    )
}

function Invoke-KafkaVerification {
    param([string] $EvidenceName = 'kafka-verification.log')
    $parameters = @{
        EvidenceName = $EvidenceName
        Command = @('sh', '/fixture/verify-kafka.sh')
        Environment = @("MA4G_KAFKA_TIMEOUT_SECONDS=$TimeoutSeconds")
    }
    Invoke-KafkaTool @parameters
}

function Collect-DatabaseEvidence {
    Invoke-Verifier -EvidenceName 'database-evidence.log' -Command @('sh', '/fixture/collect-db-evidence.sh')
}

function Complete-AtomicRound {
    param([int] $Tasks, [int] $Cells, [int] $Workers)
    Invoke-AtomicVerification -Tasks $Tasks -Cells $Cells -Workers $Workers
    Export-And-VerifyContracts -ExpectedChildRows $Cells
    Invoke-KafkaVerification
    Collect-DatabaseEvidence
}

function Start-BaseStack {
    Invoke-Compose -Arguments @(
        'up', '--detach', '--wait', '--wait-timeout', "$TimeoutSeconds",
        'mysql', 'redis', 'kafka', 'kafka-init', 'backend'
    )
    Invoke-ComposeCapture -EvidenceName 'compose-ps-started.log' -Arguments @('ps', '--all') | Out-Null
}

function Seed-G1 {
    Invoke-ComposeCapture -EvidenceName 'seed-g1.log' -Arguments @(
        '--profile', 'fixture', 'run', '--rm', 'seed-g1'
    ) | Out-Null
}

function Run-MigrationRound {
    Start-BaseStack
    Invoke-Verifier -EvidenceName 'migration-fresh.log' -Command @(
        'sh', '/fixture/verify-migration.sh', 'fresh'
    )

    Invoke-Compose -Arguments @(
        '--profile', 'migration-v044', 'up', '--detach', '--wait', '--wait-timeout', "$TimeoutSeconds",
        'mysql-legacy', 'backend-v044'
    )
    $waitForV044 = @{
        Sql = 'select max(cast(version as unsigned)) from flyway_schema_history where success=1'
        Expected = '44'
        Label = 'V044 Flyway migration'
    }
    Wait-LegacyMysqlValue @waitForV044
    $v044 = Get-LegacyMysqlValue -Sql 'select max(cast(version as unsigned)) from flyway_schema_history where success=1'
    if ($v044 -ne '44') { throw "Legacy migration did not stop at V044; actual=$v044" }
    $premature = Get-LegacyMysqlValue -Sql "select count(*) from information_schema.tables where table_schema=database() and table_name='research_agent_completion'"
    if ($premature -ne '0') { throw 'V044 legacy database already contains the V045 completion table' }
    Invoke-ComposeCapture -EvidenceName 'migration-v044-history.log' -Arguments @(
        'exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql-legacy',
        'mysql', '-unoteweave', 'noteweave_legacy', '-e',
        'select version,description,type,installed_on,success from flyway_schema_history order by installed_rank'
    ) | Out-Null
    Invoke-ComposeCapture -EvidenceName 'migration-v044-seed.log' -Arguments @(
        '--profile', 'migration-v044', 'run', '--rm', 'legacy-seed-v044'
    ) | Out-Null
    Invoke-ComposeCapture -EvidenceName 'migration-v044-legacy-values.log' -Arguments @(
        'exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql-legacy',
        'mysql', '-unoteweave', 'noteweave_legacy', '-e',
        "select id,support_score,conflict_score from source_evidence; select id,confidence_score from research_agent_candidate; select id,confidence_score from research_cell"
    ) | Out-Null
    Invoke-Compose -Arguments @('--profile', 'migration-v044', 'stop', '--timeout', '20', 'backend-v044')
    Invoke-Compose -Arguments @(
        '--profile', 'migration-v045', 'up', '--detach', '--wait', '--wait-timeout', "$TimeoutSeconds",
        'backend-v045'
    )
    $waitForV045 = @{
        Sql = 'select max(cast(version as unsigned)) from flyway_schema_history where success=1'
        Expected = '45'
        Label = 'V045 Flyway migration'
    }
    Wait-LegacyMysqlValue @waitForV045
    $legacyVerification = @{
        EvidenceName = 'migration-legacy-v045.log'
        Environment = @('MA4G_MYSQL_HOST=mysql-legacy', 'MA4G_MYSQL_DATABASE=noteweave_legacy')
        Command = @('sh', '/fixture/verify-migration.sh', 'legacy')
    }
    Invoke-Verifier @legacyVerification
}

function Export-LockMatrixEvidence {
    try {
        Invoke-Verifier -EvidenceName 'lock-matrix-evidence.jsonl' -Command @(
            'sh', '-ec', 'find /control/lock-matrix -maxdepth 1 -type f -name "*.json" -print -exec cat {} \;'
        )
    }
    catch {
        Save-Text -Name 'lock-matrix-evidence-export-failure.log' -Value $_.Exception.Message
    }
}

function Run-LockMatrixRound {
    Invoke-Compose -Arguments @(
        'up', '--detach', '--wait', '--wait-timeout', "$TimeoutSeconds", 'mysql'
    )
    Invoke-ComposeCapture -EvidenceName 'compose-ps-started.log' -Arguments @('ps', '--all') | Out-Null

    $arguments = @($script:ComposePrefix + @(
        '--profile', 'lockmatrix', 'run', '-T', '--no-deps', 'lock-matrix-test'
    ))
    $testResult = Invoke-DockerCaptured -Arguments $arguments
    $output = $testResult.Output
    $testExitCode = $testResult.ExitCode
    Save-Text -Name 'lock-matrix-test.log' -Value $output
    if ($output) { Write-Output $output }

    Export-LockMatrixEvidence
    if ($testExitCode -ne 0) {
        throw "LockMatrix MySQL integration test failed (exit=$testExitCode)"
    }
    Invoke-Verifier -EvidenceName 'lock-matrix-verification.log' -Command @(
        'sh', '/fixture/verify-lock-matrix.sh'
    )
    Collect-DatabaseEvidence
}

function Run-G1Round {
    Start-BaseStack
    Seed-G1
    Invoke-CoordinatorDispatch
    Invoke-Compose -Arguments @('--profile', 'workers', 'up', '--detach', 'worker-a')
    Invoke-CoordinatorPublish
    Complete-AtomicRound -Tasks 1 -Cells 2 -Workers 1
    Invoke-StateVerification -Mode capture -Label 'g1-final' -EvidenceName 'state-final.log'
}

function Run-G2Round {
    Start-BaseStack
    Seed-G1
    Invoke-CoordinatorDispatch
    $taskId = Get-MysqlValue -Sql "select id from research_agent_task where research_run_id='00000000-0000-0000-0000-00000000a002'"
    if ($taskId -notmatch '^[0-9a-f-]{36}$') { throw "G2 did not discover one canonical task id: '$taskId'" }
    Save-Text -Name 'g2-task-id.txt' -Value $taskId
    Invoke-ComposeCapture -EvidenceName 'g2-trigger-install.log' -Arguments @(
        'exec', '-T', 'mysql', 'sh', '-ec',
        'MYSQL_PWD=root mysql -uroot noteweave < /fixture/install-g2-trigger.sql'
    ) | Out-Null

    $previousTaskId = $env:MA4G_TASK_ID
    try {
        $env:MA4G_TASK_ID = $taskId
        Invoke-Compose -Arguments @('--profile', 'g2', 'up', '--detach', 'g2-fault-client')
    }
    finally {
        if ($null -eq $previousTaskId) { Remove-Item Env:MA4G_TASK_ID -ErrorAction SilentlyContinue }
        else { $env:MA4G_TASK_ID = $previousTaskId }
    }
    Wait-ControlMarker -Service 'g2-fault-client' -Path '/control/g2-ready.json' -Label 'G2 prepared completion'
    Invoke-Verifier -EvidenceName 'g2-prekill-verification.log' -Command @('sh', '/fixture/verify-g2.sh', 'prekill')
    Invoke-StateVerification -Mode capture -Label 'g2-prekill' -EvidenceName 'g2-state-before-kill.log'

    $faultContainer = (Get-ComposeOutput -Arguments @('ps', '--all', '--quiet', 'g2-fault-client')).Trim()
    if ($faultContainer -notmatch '^[0-9a-f]{12,64}$') { throw 'G2 fault client container was not found' }
    $owner = Get-DockerInspectProjectOwner -ResourceType container -Identifier $faultContainer
    if (-not [string]::Equals($owner, $ProjectName, [StringComparison]::Ordinal)) {
        throw "Refusing to control container outside project '$ProjectName'"
    }
    Invoke-Compose -Arguments @('exec', '-T', 'g2-fault-client', 'sh', '-ec', 'touch /control/g2-go')
    $killTimeoutSeconds = [Math]::Min($TimeoutSeconds, 90)
    Invoke-ComposeCapture -EvidenceName 'g2-kill-connection.log' -Arguments @(
        'exec', '-T', '--env', "MA4G_G2_KILL_TIMEOUT_SECONDS=$killTimeoutSeconds", 'mysql',
        'sh', '/fixture/kill-g2-transaction.sh'
    ) | Out-Null
    $faultExit = (Get-DockerOutput -Arguments @('wait', $faultContainer)).Trim()
    if ($faultExit -ne '0') { throw "G2 fault client exited unexpectedly: $faultExit" }
    Invoke-ComposeCapture -EvidenceName 'g2-fault-client.log' -Arguments @('logs', '--no-color', 'g2-fault-client') | Out-Null
    Invoke-Verifier -EvidenceName 'g2-control-proof.log' -Command @(
        'sh', '-ec', 'test -s /control/g2-ready.json && test -s /control/g2-envelope.json && test -s /control/g2-failure.json && cat /control/g2-ready.json && cat /control/g2-failure.json'
    )
    Invoke-Verifier -EvidenceName 'g2-postkill-verification.log' -Command @('sh', '/fixture/verify-g2.sh', 'postkill')
    Invoke-StateVerification -Mode assert -Label 'g2-prekill' -EvidenceName 'g2-state-after-kill.log'

    Invoke-ComposeCapture -EvidenceName 'g2-trigger-drop.log' -Arguments @(
        'exec', '-T', 'mysql', 'sh', '-ec',
        'MYSQL_PWD=root mysql -uroot noteweave < /fixture/drop-g2-trigger.sql'
    ) | Out-Null
    Invoke-ComposeCapture -EvidenceName 'g2-expire-lease-db-clock.log' -Arguments @(
        'exec', '-T', '--env', 'MYSQL_PWD=noteweave123', 'mysql',
        'mysql', '-unoteweave', '--batch', '--raw', 'noteweave', '-e',
        "update research_agent_task set lease_expires_at = current_timestamp - interval 1 second where id = '$taskId' and status in ('CLAIMED','RUNNING'); select id,status,lease_expires_at,current_timestamp as database_now from research_agent_task where id = '$taskId';"
    ) | Out-Null
    Invoke-ComposeCapture -EvidenceName 'g2-expire-lease.json' -Arguments @(
        'exec', '-T', 'backend', 'curl', '--fail', '--silent', '--show-error', '-X', 'POST',
        '-H', 'X-NoteWeave-Internal-Token: ma4g-fixture-token',
        'http://127.0.0.1:8081/internal/research-agent-tasks/expire'
    ) | Out-Null
    $retryWait = @{
        Sql = "select count(*) from research_agent_task where research_run_id='00000000-0000-0000-0000-00000000a002' and status='EXPIRED'"
        Expected = '1'
        Label = 'G2 lifecycle-expired task'
    }
    Wait-MysqlValue @retryWait
    Invoke-Compose -Arguments @('--profile', 'workers', 'up', '--detach', 'worker-b')
    Invoke-CoordinatorPublish
    Complete-AtomicRound -Tasks 1 -Cells 2 -Workers 1
    Invoke-Verifier -EvidenceName 'g2-recovery-verification.log' -Command @('sh', '/fixture/verify-g2.sh', 'recovered')
    Invoke-StateVerification -Mode capture -Label 'g2-final' -EvidenceName 'g2-state-final.log'
}

function Run-G3Round {
    Start-BaseStack
    Seed-G1
    Invoke-CoordinatorDispatch
    Invoke-Compose -Arguments @('--profile', 'workers', 'up', '--detach', 'response-loss-proxy', 'worker-a')
    Invoke-CoordinatorPublish
    Wait-ControlMarker -Service 'response-loss-proxy' -Path '/control/g3-replay-waiting.json' -Label 'G3 exact replay barrier'

    Invoke-StateVerification -Mode capture -Label 'g3-before-replay' -EvidenceName 'g3-state-before-replay.log'
    Invoke-Compose -Arguments @('exec', '-T', 'response-loss-proxy', 'sh', '-ec', 'touch /control/g3-release-replay')
    Wait-ControlMarker -Service 'response-loss-proxy' -Path '/control/g3-proof.json' -Label 'G3 replay proof'
    Invoke-ComposeCapture -EvidenceName 'g3-proxy-proof-verification.log' -Arguments @(
        '--profile', 'fixture', 'run', '--rm', 'contract-verifier',
        'python', '/fixture/verify_g3_proxy.py'
    ) | Out-Null
    Invoke-StateVerification -Mode assert -Label 'g3-before-replay' -EvidenceName 'g3-state-after-replay.log'
    Complete-AtomicRound -Tasks 1 -Cells 2 -Workers 1
    Invoke-Verifier -EvidenceName 'g3-control-proof.json' -Command @('sh', '-ec', 'cat /control/g3-first-commit.json; cat /control/g3-replay-waiting.json; cat /control/g3-proof.json')
    Invoke-ComposeCapture -EvidenceName 'g3-proxy.log' -Arguments @('logs', '--no-color', 'response-loss-proxy') | Out-Null
}

function Run-G4Round {
    Start-BaseStack
    Seed-G1
    Invoke-ComposeCapture -EvidenceName 'seed-g4-extra.log' -Arguments @(
        '--profile', 'fixture', 'run', '--rm', 'seed-g4-extra'
    ) | Out-Null
    Invoke-CoordinatorDispatch
    Invoke-Compose -Arguments @('--profile', 'workers', 'up', '--detach', 'worker-a', 'worker-b')
    Invoke-KafkaTool -EvidenceName 'g4-active-members-before-publish.log' -Command @(
        'sh', '/fixture/capture-kafka-members.sh'
    ) -Environment @("MA4G_MEMBER_TIMEOUT_SECONDS=$TimeoutSeconds")
    Invoke-CoordinatorPublish
    Invoke-KafkaTool -EvidenceName 'g4-active-members-after-publish.log' -Command @(
        'sh', '/fixture/capture-kafka-members.sh'
    ) -Environment @("MA4G_MEMBER_TIMEOUT_SECONDS=$TimeoutSeconds")

    Complete-AtomicRound -Tasks 16 -Cells 32 -Workers 2
    Invoke-KafkaTool -EvidenceName 'g4-partitions.log' -Command @('sh', '/fixture/verify-partition-use.sh')
    Invoke-StateVerification -Mode capture -Label 'g4-before-replay' -EvidenceName 'g4-state-before-replay.log'
    Invoke-Compose -Arguments @('--profile', 'workers', 'stop', '--timeout', '20', 'worker-a', 'worker-b')
    Invoke-KafkaTool -EvidenceName 'g4-offset-reset.log' -Command @(
        'sh', '/fixture/reset-replay-offsets.sh'
    ) -Environment @("MA4G_REPLAY_TIMEOUT_SECONDS=$TimeoutSeconds")
    Invoke-Compose -Arguments @('--profile', 'workers', 'start', 'worker-a', 'worker-b')
    Invoke-KafkaVerification -EvidenceName 'g4-kafka-after-replay.log'
    Invoke-StateVerification -Mode assert -Label 'g4-before-replay' -EvidenceName 'g4-state-after-replay.log'
    Invoke-Verifier -EvidenceName 'g4-atomic-after-replay.log' -Command @(
        'sh', '/fixture/verify-atomic.sh', '16', '32', '2'
    )
}

function Collect-FinalEvidence {
    foreach ($entry in @(
        @{ Name = 'compose-ps-final.log'; Args = @('ps', '--all') },
        @{ Name = 'compose-images-final.log'; Args = @('images') },
        @{ Name = 'compose-logs.log'; Args = @('logs', '--no-color', '--timestamps') }
    )) {
        try {
            $value = Get-ComposeOutput -Arguments $entry.Args
            Save-Text -Name $entry.Name -Value $value
        }
        catch {
            Save-Text -Name $entry.Name -Value "EVIDENCE_COLLECTION_FAILED $($_.Exception.Message)"
        }
    }
    try {
        $ids = (Get-DockerOutput -Arguments @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName")).Trim()
        if ($ids) {
            $inspect = Get-DockerOutput -Arguments (@('inspect') + ($ids -split '\s+'))
            Save-Text -Name 'containers-inspect.json' -Value $inspect
        }
    }
    catch {
        Save-Text -Name 'containers-inspect.json' -Value "EVIDENCE_COLLECTION_FAILED $($_.Exception.Message)"
    }
}

function Remove-RemainingProjectResources {
    $messages = [System.Collections.Generic.List[string]]::new()

    $containerIds = (Get-DockerOutput -Arguments @(
        'ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
    )).Trim()
    foreach ($containerId in @($containerIds -split '\s+' | Where-Object { $_ })) {
        $owner = Get-DockerInspectProjectOwner -ResourceType container -Identifier $containerId
        if (-not [string]::Equals($owner, $ProjectName, [StringComparison]::Ordinal)) {
            throw "Refusing to remove container '$containerId' owned by '$owner'"
        }
        $messages.Add((Get-DockerOutput -Arguments @('rm', '--force', $containerId)))
    }

    $volumeNames = (Get-DockerOutput -Arguments @(
        'volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
    )).Trim()
    foreach ($volumeName in @($volumeNames -split '\s+' | Where-Object { $_ })) {
        $owner = Get-DockerInspectProjectOwner -ResourceType volume -Identifier $volumeName
        if (-not [string]::Equals($owner, $ProjectName, [StringComparison]::Ordinal)) {
            throw "Refusing to remove volume '$volumeName' owned by '$owner'"
        }
        $messages.Add((Get-DockerOutput -Arguments @('volume', 'rm', $volumeName)))
    }

    $networkIds = (Get-DockerOutput -Arguments @(
        'network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
    )).Trim()
    foreach ($networkId in @($networkIds -split '\s+' | Where-Object { $_ })) {
        $owner = Get-DockerInspectProjectOwner -ResourceType network -Identifier $networkId
        if (-not [string]::Equals($owner, $ProjectName, [StringComparison]::Ordinal)) {
            throw "Refusing to remove network '$networkId' owned by '$owner'"
        }
        $messages.Add((Get-DockerOutput -Arguments @('network', 'rm', $networkId)))
    }

    $remainingContainers = (Get-DockerOutput -Arguments @(
        'ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
    )).Trim()
    $remainingVolumes = (Get-DockerOutput -Arguments @(
        'volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
    )).Trim()
    $remainingNetworks = (Get-DockerOutput -Arguments @(
        'network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
    )).Trim()
    if ($remainingContainers -or $remainingVolumes -or $remainingNetworks) {
        throw "Project-scoped cleanup left resources: containers='$remainingContainers' volumes='$remainingVolumes' networks='$remainingNetworks'"
    }
    $messages.Add('PROJECT_SCOPED_RESOURCES_REMOVED_AND_VERIFIED')
    return ($messages -join [Environment]::NewLine)
}

$existingContainers = (Get-DockerOutput -Arguments @(
    'ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
)).Trim()
$existingVolumes = (Get-DockerOutput -Arguments @(
    'volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
)).Trim()
$existingNetworks = (Get-DockerOutput -Arguments @(
    'network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName"
)).Trim()
if ($existingContainers -or $existingVolumes -or $existingNetworks) {
    throw "Project '$ProjectName' already owns containers, volumes, or networks; choose a new project name"
}

$startedAt = (Get-Date).ToUniversalTime()
$verified = $false
$failure = ''
$cleanupFailure = ''
try {
    Save-Text -Name 'docker-version.log' -Value (Get-DockerOutput -Arguments @('version'))
    Save-Text -Name 'git-head.txt' -Value ((& git -C $repoRoot rev-parse HEAD 2>&1 | Out-String).Trim())
    Save-Text -Name 'git-status.txt' -Value ((& git -C $repoRoot status --short 2>&1 | Out-String).TrimEnd())
    Invoke-ComposeCapture -EvidenceName 'compose-config.yml' -Arguments @('--profile', '*', 'config') | Out-Null

    if ($Round -eq 'Migration') {
        Invoke-Compose -Arguments @('build', 'backend')
    }
    elseif ($Round -eq 'LockMatrix') {
        Invoke-Compose -Arguments @('--profile', 'lockmatrix', 'build', 'lock-matrix-test')
    }
    else {
        Invoke-Compose -Arguments @('build', 'backend', 'worker-a')
    }
    if ($Round -eq 'LockMatrix') {
        Save-Text -Name 'lock-matrix-image-digest.txt' -Value (Get-DockerOutput -Arguments @(
            'image', 'inspect', '--format', '{{.Id}}', 'noteweave-ma4g-lock-matrix:local'
        ))
    }
    else {
        Save-Text -Name 'backend-image-digest.txt' -Value (Get-DockerOutput -Arguments @(
            'image', 'inspect', '--format', '{{.Id}}', 'noteweave-ma4g-backend:local'
        ))
    }
    if ($Round -notin @('Migration', 'LockMatrix')) {
        Save-Text -Name 'worker-image-digest.txt' -Value (Get-DockerOutput -Arguments @(
            'image', 'inspect', '--format', '{{.Id}}', 'noteweave-ma4g-research-worker:local'
        ))
    }

    switch ($Round) {
        'Migration' { Run-MigrationRound }
        'LockMatrix' { Run-LockMatrixRound }
        'G1' { Run-G1Round }
        'G2' { Run-G2Round }
        'G3' { Run-G3Round }
        'G4' { Run-G4Round }
    }
    $verified = $true
}
catch {
    $failure = $_.Exception.Message
    throw
}
finally {
    Collect-FinalEvidence
    if (-not $KeepProject) {
        try {
            $cleanup = Get-ComposeOutput -Arguments @('down', '--volumes', '--remove-orphans', '--timeout', '20')
            $residualCleanup = Remove-RemainingProjectResources
            if ($residualCleanup) {
                $cleanup = $cleanup + [Environment]::NewLine + $residualCleanup
            }
            Save-Text -Name 'cleanup.log' -Value $cleanup
        }
        catch {
            $cleanupFailure = $_.Exception.Message
            $verified = $false
            Save-Text -Name 'cleanup.log' -Value "CLEANUP_FAILED $cleanupFailure"
            Write-Warning "Isolated project cleanup failed: $cleanupFailure"
        }
    }
    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if ($verified) { 'VERIFIED' } else { 'FAILED' }
    $manifestFailure = if ($failure) { $failure } else { $cleanupFailure }
    $proofBoundary = if ($Round -eq 'LockMatrix') {
        'test-only real-MySQL lock-order proof; no provider, Kafka, Run advancement, or production claim'
    } else {
        'deterministic fake provider; no heartbeat, Run advancement, real-provider benchmark, or production claim'
    }
    $manifest = [ordered]@{
        schema_version = 'noteweave-ma4g-evidence.v1'
        project_name = $ProjectName
        round = $Round
        status = $status
        started_at = $startedAt.ToString('o')
        finished_at = $finishedAt.ToString('o')
        evidence_directory = $EvidenceDirectory
        failure = $manifestFailure
        proof_boundary = $proofBoundary
    } | ConvertTo-Json -Depth 4
    Save-Text -Name 'manifest.json' -Value $manifest
}

if ($cleanupFailure) { throw "Isolated project cleanup failed: $cleanupFailure" }
Write-Output "MA4G_ROUND_VERIFIED round=$Round project=$ProjectName evidence=$EvidenceDirectory"
