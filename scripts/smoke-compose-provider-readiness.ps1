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

function Require-ConfiguredSecret {
    param([hashtable]$Values, [System.Collections.Generic.List[object]]$Checks, [string]$Name)
    $value = Get-EnvValue $Values $Name
    $configured = -not [string]::IsNullOrWhiteSpace($value)
    Add-Check $Checks "$Name configured" $configured "$Name=$($(if ($configured) { 'SET' } else { 'BLANK' }))"
}

function Require-EnvValue {
    param([hashtable]$Values, [System.Collections.Generic.List[object]]$Checks, [string]$Name, [string]$Expected)
    $value = Get-EnvValue $Values $Name
    Add-Check $Checks "$Name value" ($value.ToLowerInvariant() -eq $Expected.ToLowerInvariant()) "$Name=$value"
}

$values = Read-EnvFile $EnvFile
$checks = New-Object System.Collections.Generic.List[object]

try {
    $compose = docker compose --env-file $EnvFile --profile app config --quiet 2>&1
    Add-Check $checks "compose_config" ($LASTEXITCODE -eq 0) (($compose | Out-String).Trim())

    if ($RequireFullDemo) {
        Require-EnabledProvider $values $checks "NOTEWEAVE_LLM" @("ENDPOINT", "MODEL")
        Require-ConfiguredSecret $values $checks "NOTEWEAVE_LLM_API_KEY"
        Require-EnvValue $values $checks "NOTEWEAVE_LLM_TEMPLATE_FALLBACK_ENABLED" "false"
        Require-EnabledProvider $values $checks "NOTEWEAVE_EMBEDDING" @("ENDPOINT", "MODEL", "DIMENSIONS")
        Require-ConfiguredSecret $values $checks "NOTEWEAVE_EMBEDDING_API_KEY"
        Require-EnabledProvider $values $checks "NOTEWEAVE_RERANK" @("ENDPOINT", "MODEL")
        Require-ConfiguredSecret $values $checks "NOTEWEAVE_RERANK_API_KEY"
        Require-EnvValue $values $checks "NOTEWEAVE_ES_ENABLED" "true"
        Require-EnvValue $values $checks "NOTEWEAVE_RESEARCH_AGENT_FAKE_PROVIDER_ENABLED" "false"
        Require-EnvValue $values $checks "NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED" "true"
        Require-EnvValue $values $checks "NOTEWEAVE_RESEARCH_ENABLE_URL_READER" "true"

        foreach ($name in @(
            "NOTEWEAVE_RESEARCH_LLM_BASE_URL", "NOTEWEAVE_RESEARCH_LLM_MODEL",
            "NOTEWEAVE_ARTIFACT_LLM_BASE_URL", "NOTEWEAVE_ARTIFACT_LLM_MODEL"
        )) {
            $value = Get-EnvValue $values $name
            Add-Check $checks "$name configured" (-not [string]::IsNullOrWhiteSpace($value)) "$name=$value"
        }

        foreach ($name in @(
            "NOTEWEAVE_RESEARCH_LLM_API_KEY",
            "NOTEWEAVE_ARTIFACT_LLM_API_KEY",
            "NOTEWEAVE_RESEARCH_JINA_API_KEY"
        )) {
            Require-ConfiguredSecret $values $checks $name
        }

        $searchKey = Get-EnvValue $values "NOTEWEAVE_RESEARCH_SERPER_API_KEY"
        if ([string]::IsNullOrWhiteSpace($searchKey)) {
            $searchKey = Get-EnvValue $values "NOTEWEAVE_RESEARCH_SEARCH_API_KEY"
        }
        $searchConfigured = -not [string]::IsNullOrWhiteSpace($searchKey)
        Add-Check $checks "Research search credential configured" $searchConfigured "Research search credential=$($(if ($searchConfigured) { 'SET' } else { 'BLANK' }))"
    }

    $passed = @($checks | Where-Object { -not $_.passed }).Count -eq 0
    $report = [ordered]@{
        measured_at = (Get-Date).ToString("o")
        env_file = (Resolve-Path -LiteralPath $EnvFile).Path
        require_full_demo = [bool]$RequireFullDemo
        passed = $passed
        checks = $checks
    }
    $report | ConvertTo-Json -Depth 8
    if (-not $passed) { exit 2 }
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
