[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4f-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,

    [ValidateRange(10, 600)]
    [int] $TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = $PSScriptRoot
$baseCompose = Join-Path $fixtureRoot 'docker-compose.yml'
$replayCompose = Join-Path $fixtureRoot 'docker-compose.replay.yml'
$compose = @(
    'compose',
    '--project-name', $ProjectName,
    '--file', $baseCompose,
    '--file', $replayCompose
)

function Invoke-Ma4fCompose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    & docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed (exit=$LASTEXITCODE): $($Arguments -join ' ')"
    }
}

function Get-Ma4fComposeOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $output = & docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed (exit=$LASTEXITCODE): $($Arguments -join ' ')"
    }
    return ($output | Out-String).Trim()
}

function Invoke-Ma4fDocker {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker failed (exit=$LASTEXITCODE): $($Arguments -join ' ')"
    }
}

$mysqlContainer = Get-Ma4fComposeOutput -Arguments @('ps', '-q', 'mysql')
$kafkaContainer = Get-Ma4fComposeOutput -Arguments @('ps', '-q', 'kafka')
$workerAContainer = Get-Ma4fComposeOutput -Arguments @('ps', '--all', '-q', 'worker-a')
$workerBContainer = Get-Ma4fComposeOutput -Arguments @('ps', '--status', 'running', '-q', 'worker-b')

if ([string]::IsNullOrWhiteSpace($mysqlContainer) -or [string]::IsNullOrWhiteSpace($kafkaContainer)) {
    throw "R4 project '$ProjectName' is not running its project-scoped mysql and kafka services"
}
if ([string]::IsNullOrWhiteSpace($workerAContainer)) {
    throw "R4 project '$ProjectName' does not have a worker-a container"
}
if (-not [string]::IsNullOrWhiteSpace($workerBContainer)) {
    throw "R4 replay is a single-worker fixture; worker-b is unexpectedly running"
}

# Fail before changing consumer state when the stack was not created with the
# replay override (which supplies read-only /fixture mounts).
Invoke-Ma4fCompose -Arguments @('exec', '-T', 'mysql', 'test', '-r', '/fixture/replay-state.sql')
Invoke-Ma4fCompose -Arguments @('exec', '-T', 'kafka', 'test', '-r', '/fixture/reset-replay-offsets.sh')

$workerStopped = $false
try {
    Invoke-Ma4fCompose -Arguments @(
        'exec', '-T', 'mysql',
        'sh', '/fixture/verify-replay-state.sh', 'capture'
    )

    # Use the ID discovered through this compose project.  Direct Docker
    # lifecycle calls never start the already-completed seed dependency.
    Invoke-Ma4fDocker -Arguments @('stop', $workerAContainer)
    $workerStopped = $true

    # reset-replay-offsets.sh waits for Empty/Dead (or Kafka's explicit
    # no-active-member result) before it attempts the dry run and reset.
    Invoke-Ma4fCompose -Arguments @(
        'exec', '-T',
        '--env', "MA4F_REPLAY_TIMEOUT_SECONDS=$TimeoutSeconds",
        'kafka', 'sh', '/fixture/reset-replay-offsets.sh'
    )

    Invoke-Ma4fDocker -Arguments @('start', $workerAContainer)
    $workerStopped = $false

    Invoke-Ma4fCompose -Arguments @(
        'exec', '-T',
        '--env', "MA4F_REPLAY_TIMEOUT_SECONDS=$TimeoutSeconds",
        'kafka', 'sh', '/fixture/verify-replay-kafka.sh'
    )
    Invoke-Ma4fCompose -Arguments @(
        'exec', '-T', 'mysql',
        'sh', '/fixture/verify-replay-state.sh', 'assert'
    )

    Write-Output "MA4F_REPLAY_IDEMPOTENCY_VERIFIED project=$ProjectName"
}
finally {
    if ($workerStopped) {
        Write-Warning 'Replay orchestration failed while worker-a was stopped; attempting to restart it.'
        & docker start $workerAContainer
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "worker-a restart also failed (exit=$LASTEXITCODE)"
        }
    }
}
