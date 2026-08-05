param(
    [string]$EnvFile = ".env",
    [switch]$RequireFullDemo
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location $workspace

function Read-EnvFile {
    param([string]$Path)
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Environment file not found: $Path"
    }
    Get-Content -Encoding UTF8 -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#") -or -not $line.Contains("=")) { return }
        $name, $value = $line.Split("=", 2)
        $values[$name.Trim()] = $value.Trim().Trim('"').Trim("'")
    }
    return $values
}

function Get-EnvValue {
    param([hashtable]$Values, [string]$Name)
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
    if ($Values.ContainsKey($Name)) { return [string]$Values[$Name] }
    return ""
}

function Add-Check {
    param([System.Collections.Generic.List[object]]$Checks, [string]$Name, [bool]$Passed, [string]$Detail)
    $Checks.Add([ordered]@{ name = $Name; passed = $Passed; detail = $Detail }) | Out-Null
    if (-not $Passed) { throw "Provider readiness failed: $Name - $Detail" }
}

function Require-EnabledProvider {
    param([hashtable]$Values, [System.Collections.Generic.List[object]]$Checks, [string]$Prefix, [string[]]$RequiredFields)
    $enabled = (Get-EnvValue $Values "$($Prefix)_ENABLED").ToLowerInvariant()
    Add-Check $Checks "$Prefix enabled" ($enabled -eq "true") "$Prefix`_ENABLED=$enabled"
    foreach ($field in $RequiredFields) {
        $name = "$($Prefix)_$field"
        $value = Get-EnvValue $Values $name
        Add-Check $Checks "$name configured" (-not [string]::IsNullOrWhiteSpace($value)) "$name=$value"
    }
}

$values = Read-EnvFile $EnvFile
$checks = New-Object System.Collections.Generic.List[object]

try {
    $compose = docker compose --env-file $EnvFile --profile app config --quiet 2>&1
    Add-Check $checks "compose_config" ($LASTEXITCODE -eq 0) (($compose | Out-String).Trim())

    if ($RequireFullDemo) {
        Require-EnabledProvider $values $checks "NOTEWEAVE_LLM" @("ENDPOINT", "MODEL")
        Require-EnabledProvider $values $checks "NOTEWEAVE_EMBEDDING" @("ENDPOINT", "MODEL", "DIMENSIONS")
        Require-EnabledProvider $values $checks "NOTEWEAVE_RERANK" @("ENDPOINT", "MODEL")

        foreach ($name in @(
            "NOTEWEAVE_RESEARCH_LLM_BASE_URL", "NOTEWEAVE_RESEARCH_LLM_MODEL",
            "NOTEWEAVE_ARTIFACT_LLM_BASE_URL", "NOTEWEAVE_ARTIFACT_LLM_MODEL"
        )) {
            $value = Get-EnvValue $values $name
            Add-Check $checks "$name configured" (-not [string]::IsNullOrWhiteSpace($value)) "$name=$value"
        }
    }

    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        env_file = (Resolve-Path -LiteralPath $EnvFile).Path
        require_full_demo = [bool]$RequireFullDemo
        passed = $true
        checks = $checks
    }
    $report | ConvertTo-Json -Depth 8
} catch {
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        env_file = $EnvFile
        require_full_demo = [bool]$RequireFullDemo
        passed = $false
        error = $_.Exception.Message
        checks = $checks
    }
    $report | ConvertTo-Json -Depth 8
    exit 2
}
