$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir

$env:SERVER_PORT = "18083"
$env:NOTEWEAVE_STUDIO_MCP_REMOTE_SERVER_ENABLED = "true"

& "$repoRoot\\mvnw.cmd" spring-boot:run `
  "-Dspring-boot.run.mainClass=com.noteweave_remote.BilibiliMcpRemoteApplication"
