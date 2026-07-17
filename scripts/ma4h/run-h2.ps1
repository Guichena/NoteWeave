[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4h-h2-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,

    [string] $EvidenceDirectory,

    [switch] $KeepProject
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = $PSScriptRoot
$repoRoot = [IO.Path]::GetFullPath((Join-Path $fixtureRoot '..\..'))
$composeFile = Join-Path $fixtureRoot 'docker-compose.h2.yml'
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $EvidenceDirectory = Join-Path $fixtureRoot "evidence\$ProjectName-$stamp"
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$compose = @('compose', '--project-name', $ProjectName, '--file', $composeFile)

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
    finally {
        $ErrorActionPreference = $previous
    }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

function Require-Docker {
    param([string[]] $Arguments)
    $result = Invoke-DockerCaptured $Arguments
    if ($result.ExitCode -ne 0) {
        throw "docker failed (exit=$($result.ExitCode)): $($Arguments -join ' ')`n$($result.Output)"
    }
    $result.Output
}

function Compose {
    param([string[]] $Arguments)
    Require-Docker @($compose + $Arguments)
}

$verified = $false
$testExitCode = -1
try {
    Save-Text 'docker-version.txt' (Require-Docker @('version', '--format', '{{json .}}'))
    Save-Text 'compose-config.yml' (Compose @('config'))
    Save-Text 'build.log' (Compose @('build', 'h2-tests'))
    Compose @('up', '-d', '--wait', 'mysql') | Out-Null
    Save-Text 'mysql-version.txt' (Compose @(
        'exec', '-T', '--env', 'MYSQL_PWD=root', 'mysql',
        'mysql', '-uroot', '--batch', '--raw', '--skip-column-names', '-e',
        'select version(), @@transaction_isolation, @@character_set_server, @@collation_server;'
    ))
    Save-Text 'image-inspect.json' (Require-Docker @(
        'image', 'inspect', 'noteweave-ma4h-h2-tests:local'
    ))

    $testResult = Invoke-DockerCaptured @($compose + @(
        'up', '--no-color', '--abort-on-container-exit', '--exit-code-from', 'h2-tests', 'h2-tests'
    ))
    $testExitCode = $testResult.ExitCode
    Save-Text 'test.log' $testResult.Output

    $containerId = (Compose @('ps', '--all', '--quiet', 'h2-tests')).Trim()
    if ($containerId -match '^[0-9a-f]{12,64}$') {
        $reportDirectory = Join-Path $EvidenceDirectory 'surefire-reports'
        New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
        $copyResult = Invoke-DockerCaptured @('cp', "${containerId}:/workspace/target/surefire-reports/.", $reportDirectory)
        Save-Text 'report-copy.log' $copyResult.Output
        if ($copyResult.ExitCode -ne 0 -and $testExitCode -eq 0) {
            throw 'Unable to copy Surefire reports from successful H2 test container'
        }
    }
    if ($testExitCode -ne 0) { throw "MA4H H2 tests failed with exit code $testExitCode" }

    $expectedReports = @(
        'com.noteweave.research.ResearchAgentRateLimitServiceTest.txt',
        'com.noteweave.research.ResearchAgentTaskServiceTest.txt',
        'com.noteweave.research.ResearchAgentTrustedPermitServiceTest.txt'
    )
    $summaryLines = foreach ($reportName in $expectedReports) {
        $reportPath = Join-Path (Join-Path $EvidenceDirectory 'surefire-reports') $reportName
        if (-not (Test-Path $reportPath)) { throw "Missing expected Surefire report: $reportName" }
        (Select-String -Path $reportPath -Pattern '^Tests run:').Line
    }
    $summary = $summaryLines -join [Environment]::NewLine
    $totalTests = 0
    foreach ($line in $summaryLines) {
        if ($line -notmatch '^Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0,') {
            throw "Surefire report is not clean: $line"
        }
        $totalTests += [int] $Matches[1]
    }
    if ($totalTests -ne 19) { throw "Expected exactly 19 H2 tests; actual=$totalTests" }
    Save-Text 'test-summary.txt' $summary
    $verified = $true
}
finally {
    if (-not $KeepProject) {
        $cleanup = Invoke-DockerCaptured @($compose + @('down', '--volumes', '--remove-orphans'))
        Save-Text 'cleanup.log' $cleanup.Output
        if ($cleanup.ExitCode -ne 0) { throw 'MA4H H2 cleanup failed' }
        $remaining = Require-Docker @(
            'ps', '--all', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}'
        )
        $remainingVolumes = Require-Docker @(
            'volume', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.Name}}'
        )
        $remainingNetworks = Require-Docker @(
            'network', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName", '--format', '{{.ID}}'
        )
        Save-Text 'cleanup-verification.txt' "containers=$remaining`nvolumes=$remainingVolumes`nnetworks=$remainingNetworks"
        if ($remaining -or $remainingVolumes -or $remainingNetworks) {
            throw 'MA4H H2 project-scoped resources remain after cleanup'
        }
    }
    $manifest = [ordered]@{
        schema_version = 'noteweave-ma4h-h2-evidence.v1'
        status = if ($verified) { 'VERIFIED' } else { 'FAILED' }
        project_name = $ProjectName
        test_exit_code = $testExitCode
        completed_at_utc = (Get-Date).ToUniversalTime().ToString('o')
        boundary = 'MySQL 8.4 + mocked rate-limit transport; no real provider evidence'
    } | ConvertTo-Json -Depth 4
    Save-Text 'manifest.json' $manifest
}

if (-not $verified) { throw 'MA4H H2 verification did not complete' }
Write-Host "MA4H H2 VERIFIED: $EvidenceDirectory"
