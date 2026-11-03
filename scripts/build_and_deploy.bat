@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_and_deploy.ps1" %*
exit /b %ERRORLEVEL%
