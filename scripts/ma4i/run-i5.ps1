[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4i-i5-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,
    [string] $EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $PSScriptRoot ("evidence\$ProjectName-" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$compose = @('compose', '--project-name', $ProjectName, '--file', (Join-Path $root 'scripts\ma4h\docker-compose.h2.yml'), '--file', (Join-Path $PSScriptRoot 'docker-compose.i5.yml'))
function Save([string]$name, [AllowEmptyString()][string]$value) { [IO.File]::WriteAllText((Join-Path $EvidenceDirectory $name), $value + [Environment]::NewLine, $utf8) }
function Invoke-Docker([string[]]$Arguments) {
    $prior = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $lines = @(& docker @Arguments 2>&1); $code = $LASTEXITCODE; $out = ($lines | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { [string]$_ } } | Out-String).TrimEnd() }
    finally { $ErrorActionPreference = $prior }
    [pscustomobject]@{ Code = $code; Output = $out }
}
function Require([string[]]$Arguments) { $r=Invoke-Docker $Arguments; if($r.Code -ne 0){throw "docker failed ($($r.Code)): $($Arguments -join ' ')`n$($r.Output)"}; $r.Output }

$verified = $false
$testExitCode = -1
try {
    Save 'compose-config.yml' (Require ($compose + @('config')))
    Save 'build.log' (Require ($compose + @('build', 'h2-tests')))
    Require ($compose + @('up', '-d', '--wait', '--wait-timeout', '180', 'mysql')) | Out-Null
    Save 'mysql-version.txt' (Require ($compose + @('exec', '-T', '--env', 'MYSQL_PWD=root', 'mysql', 'mysql', '-uroot', '--batch', '--raw', '--skip-column-names', '-e', 'select version(), @@transaction_isolation;')))
    $container = "$ProjectName-i5-results"
    $test = Invoke-Docker ($compose + @('run', '--name', $container, 'h2-tests'))
    $testExitCode = $test.Code
    Save 'test.log' $test.Output
    try { Require @('cp', "$container`:/workspace/target/surefire-reports", $EvidenceDirectory) | Out-Null }
    finally { Invoke-Docker @('rm', '-f', $container) | Out-Null }
    if ($testExitCode -ne 0) { throw "I5 tests failed with exit code $testExitCode" }
    $reports = @(
        'TEST-com.noteweave.research.ResearchAgentTaskCoordinatorServiceTest.xml',
        'TEST-com.noteweave.research.ResearchAgentCoordinatorSchedulerTest.xml',
        'TEST-com.noteweave.research.ResearchAgentCommandDispatchSchedulerTest.xml'
    ) | ForEach-Object {
        $path = Join-Path (Join-Path $EvidenceDirectory 'surefire-reports') $_
        if (-not (Test-Path $path)) { throw "I5 surefire report was not copied: $_" }
        [xml](Get-Content $path)
    }
    $totalTests = ($reports | ForEach-Object { [int]$_.testsuite.tests } | Measure-Object -Sum).Sum
    $failures = ($reports | ForEach-Object { [int]$_.testsuite.failures } | Measure-Object -Sum).Sum
    $errors = ($reports | ForEach-Object { [int]$_.testsuite.errors } | Measure-Object -Sum).Sum
    $skipped = ($reports | ForEach-Object { [int]$_.testsuite.skipped } | Measure-Object -Sum).Sum
    if ($totalTests -ne 27 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) { throw "I5 reports are not 27 green tests: tests=$totalTests failures=$failures errors=$errors skipped=$skipped" }
    Save 'test-summary.txt' "Tests run: $totalTests, Failures: $failures, Errors: $errors, Skipped: $skipped"
    $verified = $true
} finally {
    $down = Invoke-Docker ($compose + @('down', '--volumes', '--remove-orphans'))
    Save 'cleanup.log' $down.Output
    $containers = Require @('ps','--all','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.ID}}')
    $volumes = Require @('volume','ls','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.Name}}')
    $networks = Require @('network','ls','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.ID}}')
    Save 'cleanup-verification.txt' "containers=$containers`nvolumes=$volumes`nnetworks=$networks"
    Save 'manifest.json' (([ordered]@{schema_version='noteweave-ma4i-i5-evidence.v1';status=if($verified){'VERIFIED'}else{'FAILED'};project_name=$ProjectName;test_exit_code=$testExitCode;completed_at_utc=(Get-Date).ToUniversalTime().ToString('o');boundary='MySQL 8.4 restart-safe coordinator database contract; no Kafka delivery, worker crash, or real-provider evidence'}) | ConvertTo-Json)
}
if(-not $verified){throw 'MA4I I5 verification did not complete'}
Write-Host "MA4I I5 VERIFIED: $EvidenceDirectory"
