@echo off
rem Start Xinghai behind the Velocity proxy: limbo + Velocity in their own windows, then the Paper server here.
rem First time: run setup-proxy.bat (see proxy\README.md).
chcp 65001 > nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0proxy\start-all.ps1" %*
