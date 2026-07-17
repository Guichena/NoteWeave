[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$fixtureRoot = $PSScriptRoot
$repoRoot = [IO.Path]::GetFullPath((Join-Path $fixtureRoot '..\..'))

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

$powerShellFiles = Get-ChildItem -LiteralPath $fixtureRoot -Filter '*.ps1' -File
foreach ($file in $powerShellFiles) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $file.FullName, [ref] $tokens, [ref] $errors
    ) | Out-Null
    if ($errors.Count -gt 0) {
        $detail = ($errors | ForEach-Object { "$($_.Message) line=$($_.Extent.StartLineNumber)" }) -join '; '
        throw "PowerShell AST failed for $($file.Name): $detail"
    }
}
Write-Output "MA4G_STATIC_POWERSHELL_AST_OK files=$($powerShellFiles.Count)"

$pythonFiles = Get-ChildItem -LiteralPath $fixtureRoot -Filter '*.py' -File
if ($pythonFiles.Count -gt 0) {
    $pythonPath = $env:MA4G_PYTHON
    if ([string]::IsNullOrWhiteSpace($pythonPath)) {
        $pythonPath = (Get-Command python -ErrorAction Stop).Source
    }
    if (-not (Test-Path -LiteralPath $pythonPath)) {
        throw "Python executable does not exist: $pythonPath"
    }
    $script = @'
import ast
import pathlib
import sys
for item in sys.argv[1:]:
    path = pathlib.Path(item)
    ast.parse(path.read_text(encoding='utf-8'), filename=str(path))
'@
    & $pythonPath -c $script @($pythonFiles.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Python AST validation failed' }
}
Write-Output "MA4G_STATIC_PYTHON_AST_OK files=$($pythonFiles.Count)"

$shellFiles = Get-ChildItem -LiteralPath $fixtureRoot -Filter '*.sh' -File
$gitBash = 'C:\Program Files\Git\bin\bash.exe'
$bashPath = if (Test-Path -LiteralPath $gitBash) {
    $gitBash
} else {
    (Get-Command bash -ErrorAction SilentlyContinue).Source
}
if ([string]::IsNullOrWhiteSpace($bashPath)) { throw 'bash is required for MA4G shell syntax validation' }
foreach ($file in $shellFiles) {
    & $bashPath -n $file.FullName
    if ($LASTEXITCODE -ne 0) { throw "Shell syntax failed for $($file.Name)" }
}
Write-Output "MA4G_STATIC_SHELL_SYNTAX_OK files=$($shellFiles.Count)"

$allFiles = Get-ChildItem -LiteralPath $fixtureRoot -File
foreach ($file in $allFiles) {
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadLines($file.FullName)) {
        $lineNumber++
        if ($line -match '[ \t]+$') {
            throw "Trailing whitespace in $($file.Name):$lineNumber"
        }
    }
}

$sourceText = ($allFiles | Where-Object Name -ne 'static-check.ps1' |
    ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n"
Assert-True ($sourceText -notmatch 'noteweave-ma4f') 'MA4G fixture must not reference an MA4F project or image'
Assert-True ($sourceText -notmatch '(?im)^\s*container_name\s*:') 'MA4G Compose must not use global container_name values'
Assert-True ($sourceText -notmatch '(?im)^\s*network_mode\s*:\s*host') 'MA4G Compose must not use the host network'
Assert-True ($sourceText -notmatch '(?im)^\s*external\s*:\s*true') 'MA4G Compose must not attach external networks or volumes'
Assert-True ($sourceText -notmatch '2100-01-01') 'MA4G fixtures must not synthesize a future clock'

$fixtureReferences = [regex]::Matches($sourceText, '/fixture/([A-Za-z0-9_.-]+)') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
foreach ($reference in $fixtureReferences) {
    if (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot $reference))) {
        throw "Missing /fixture reference: $reference"
    }
}
Write-Output "MA4G_STATIC_FIXTURE_REFERENCES_OK count=$($fixtureReferences.Count)"

$base = Join-Path $fixtureRoot 'docker-compose.yml'
$cases = @(
    @{ Name = 'g1'; Files = @($base) },
    @{ Name = 'g2'; Files = @($base, (Join-Path $fixtureRoot 'docker-compose.g2.yml')) },
    @{ Name = 'g3'; Files = @($base, (Join-Path $fixtureRoot 'docker-compose.g3.yml')) },
    @{ Name = 'g4'; Files = @($base, (Join-Path $fixtureRoot 'docker-compose.g4.yml')) },
    @{ Name = 'lockmatrix'; Files = @($base, (Join-Path $fixtureRoot 'docker-compose.lockmatrix.yml')) },
    @{ Name = 'migration'; Files = @($base, (Join-Path $fixtureRoot 'docker-compose.migration.yml')) }
)
foreach ($case in $cases) {
    $project = "noteweave-ma4g-static-$($case.Name)"
    $arguments = @('compose', '--project-name', $project)
    foreach ($file in $case.Files) { $arguments += @('--file', $file) }
    $arguments += @('--profile', '*', 'config', '--format', 'json')
    $raw = (& docker @arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Compose config failed for $($case.Name): $raw" }
    $config = $raw | ConvertFrom-Json
    Assert-True ($config.name -eq $project) "Compose project name drifted for $($case.Name)"
    Assert-True ($config.networks.ma4g.internal -eq $true) "MA4G network is not internal for $($case.Name)"
    foreach ($service in $config.services.PSObject.Properties) {
        $ports = $service.Value.ports
        if ($null -ne $ports -and @($ports).Count -ne 0) {
            throw "Service $($service.Name) publishes a host port in $($case.Name)"
        }
        $networkNames = @($service.Value.networks.PSObject.Properties.Name)
        if ($networkNames -notcontains 'ma4g') {
            throw "Service $($service.Name) leaves the project-scoped ma4g network in $($case.Name)"
        }
    }
    Write-Output "MA4G_STATIC_COMPOSE_CONFIG_OK case=$($case.Name) services=$(@($config.services.PSObject.Properties).Count)"
}

$runner = [IO.File]::ReadAllText((Join-Path $fixtureRoot 'run-round.ps1'))
Assert-True ($runner -match "ValidatePattern\('\^noteweave-ma4g-") 'Runner does not enforce noteweave-ma4g-* project names'
Assert-True ($runner -match "'down', '--volumes', '--remove-orphans'") 'Runner does not use project-scoped volume cleanup'
Assert-True ($runner -match "'LockMatrix'") 'Runner does not expose the isolated LockMatrix round'
Assert-True ($runner -match 'lease_expires_at = current_timestamp - interval 1 second') 'G2 does not use an explicit DB-clock expiry fixture'
Assert-True ($runner -notmatch '(?s)--data-binary.{0,300}internal/research-agent-tasks/expire') 'G2 expire endpoint must not accept a request clock body'
Assert-True ($runner -notmatch '"now"\s*:') 'Runner must not send a caller-controlled expiry clock field'

$lockMatrixTest = Join-Path $repoRoot 'backend\src\test\java\com\noteweave\research\ResearchAgentMySqlLockMatrixIT.java'
Assert-True (Test-Path -LiteralPath $lockMatrixTest) 'LockMatrix MySQL integration test is missing'
$lockMatrixTestText = [IO.File]::ReadAllText($lockMatrixTest)
Assert-True ($lockMatrixTestText -match 'performance_schema\.data_lock_waits') 'LockMatrix IT does not prove the physical wait graph'
Assert-True ($lockMatrixTestText -match 'follower -> leader -> gate-holder') 'LockMatrix IT does not record the required wait chain'
Assert-True ($lockMatrixTestText -match 'expireLeases\(\)') 'LockMatrix IT does not use DB-clock lease expiry'
Assert-True ($lockMatrixTestText -notmatch 'expireLeases\(\s*Instant') 'LockMatrix IT reintroduced a caller-controlled expiry clock'

& git -C $repoRoot diff --check -- scripts/ma4g
if ($LASTEXITCODE -ne 0) { throw 'git diff --check failed for scripts/ma4g' }
Write-Output 'MA4G_STATIC_CHECKS_VERIFIED'
