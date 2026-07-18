param(
    [Parameter(Mandatory = $true)]
    [string]$AnnotationRequestPath,

    [Parameter(Mandatory = $true)]
    [string]$RunMapPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendPom = Join-Path $repoRoot "backend\pom.xml"
$mavenWrapper = Join-Path $repoRoot "mvnw.cmd"
$classpathFile = Join-Path $repoRoot "backend\target\qa-answer-run-shadow-runtime-classpath.txt"

function Resolve-InputFile {
    param([string]$Path, [string]$Label)
    $candidate = if ([IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $repoRoot $Path }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Label does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

if ([string]::IsNullOrWhiteSpace($env:NOTEWEAVE_RETRIEVAL_EXPORT_SALT) -or
        $env:NOTEWEAVE_RETRIEVAL_EXPORT_SALT.Length -lt 16) {
    throw "NOTEWEAVE_RETRIEVAL_EXPORT_SALT must contain at least 16 characters"
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) {
    throw "SPRING_DATASOURCE_URL is required; use a read-only database account"
}

$annotationRequest = Resolve-InputFile $AnnotationRequestPath "Annotation request"
$runMap = Resolve-InputFile $RunMapPath "AnswerRun map"
$shadowOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $repoRoot $OutputPath
}

& $mavenWrapper -q -f $backendPom `
    '-Dmaven.test.skip=true' `
    compile `
    dependency:build-classpath `
    '-Dmdep.includeScope=runtime' `
    '-Dmdep.outputAbsoluteArtifactFilename=true' `
    "-Dmdep.outputFile=$classpathFile"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$dependencyClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($dependencyClasspath)) {
    throw "Unable to resolve the Backend runtime classpath"
}
$classes = Join-Path $repoRoot "backend\target\classes"

& java -cp "$classes;$dependencyClasspath" `
    com.noteweave.retrieval.eval.QaAnswerRunShadowExportCli `
    export-answer-runs `
    $annotationRequest `
    $runMap `
    $shadowOutput
exit $LASTEXITCODE
