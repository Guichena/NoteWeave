[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^noteweave-ma4i-i1-[a-z0-9][a-z0-9_-]*$')]
    [string] $ProjectName,
    [string] $EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $PSScriptRoot ("evidence\$ProjectName-" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
}
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$compose = @('compose', '--project-name', $ProjectName, '--file', (Join-Path $PSScriptRoot 'docker-compose.i1.yml'))
$utf8 = [Text.UTF8Encoding]::new($false)
function Save([string]$name, [string]$value) { [IO.File]::WriteAllText((Join-Path $EvidenceDirectory $name), $value + [Environment]::NewLine, $utf8) }
function Invoke-Docker([string[]]$Arguments) {
    $prior = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $lines = @(& docker @Arguments 2>&1); $code = $LASTEXITCODE; $out = ($lines | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { [string]$_ } } | Out-String).TrimEnd() }
    finally { $ErrorActionPreference = $prior }
    [pscustomobject]@{ Code = $code; Output = $out }
}
function Require([string[]]$Arguments) { $r=Invoke-Docker $Arguments; if($r.Code -ne 0){throw "docker failed ($($r.Code)): $($Arguments -join ' ')`n$($r.Output)"}; $r.Output }
$verified = $false
try {
    Save 'compose-config.yml' (Require ($compose + @('config')))
    Save 'build.log' (Require ($compose + @('build', 'i1-tests')))
    $testContainer = "$ProjectName-i1-results"
    $test = Invoke-Docker ($compose + @('run', '--name', $testContainer, 'i1-tests'))
    Save 'test.log' $test.Output
    if ($test.Code -ne 0) { throw "I1 tests failed with exit code $($test.Code)" }
    try {
        Require @('cp', "$testContainer`:/workspace/target/surefire-reports", $EvidenceDirectory) | Out-Null
    } finally { Invoke-Docker @('rm', '-f', $testContainer) | Out-Null }
    $report = Get-ChildItem (Join-Path $EvidenceDirectory 'surefire-reports') -Filter 'TEST-com.noteweave.research.ResearchAgentWaveBarrierTest.xml' | Select-Object -First 1
    if ($null -eq $report) { throw 'I1 surefire report was not copied' }
    [xml]$xml = Get-Content $report.FullName
    if ($xml.testsuite.tests -ne '4' -or $xml.testsuite.failures -ne '0' -or $xml.testsuite.errors -ne '0') { throw 'I1 report does not show 4 green tests' }
    $verified = $true
} finally {
    $down = Invoke-Docker ($compose + @('down', '--volumes', '--remove-orphans'))
    Save 'cleanup.log' $down.Output
    $containers = Require @('ps','--all','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.ID}}')
    $volumes = Require @('volume','ls','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.Name}}')
    $networks = Require @('network','ls','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.ID}}')
    Save 'cleanup-verification.txt' "containers=$containers`nvolumes=$volumes`nnetworks=$networks"
    Save 'manifest.json' (([ordered]@{schema_version='noteweave-ma4i-i1-evidence.v1';status=if($verified){'VERIFIED'}else{'FAILED'};project_name=$ProjectName;completed_at_utc=(Get-Date).ToUniversalTime().ToString('o');boundary='pure barrier and decision-canonicalization unit contract; no coordinator database or provider evidence'}) | ConvertTo-Json)
}
if(-not $verified){throw 'MA4I I1 verification did not complete'}
Write-Host "MA4I I1 VERIFIED: $EvidenceDirectory"
