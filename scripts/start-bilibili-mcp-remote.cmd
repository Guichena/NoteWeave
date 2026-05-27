@echo off
set SCRIPT_DIR=%~dp0
for %%I in ("%SCRIPT_DIR%..") do set REPO_ROOT=%%~fI
set SERVER_PORT=18083
set NOTEWEAVE_STUDIO_MCP_REMOTE_SERVER_ENABLED=true

call "%REPO_ROOT%\mvnw.cmd" spring-boot:run -Dspring-boot.run.mainClass=com.noteweave_remote.BilibiliMcpRemoteApplication
