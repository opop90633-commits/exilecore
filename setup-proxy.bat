@echo off
rem One-time setup for the Velocity proxy (stop the server first). Undo: setup-proxy.bat -Undo
chcp 65001 > nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0proxy\setup-proxy.ps1" %*
pause
