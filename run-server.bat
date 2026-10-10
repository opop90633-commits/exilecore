@echo off
rem Start the test server with the console switched to UTF-8 (code page 65001),
rem so Chinese text in the server log is not garbled. Usage: run-server.bat (PowerShell: .\run-server.bat)
chcp 65001 > nul
call "%~dp0gradlew.bat" runServer %*
