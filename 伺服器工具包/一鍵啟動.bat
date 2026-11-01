@echo off
rem ManaCore test server - one click start (Windows)
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-server.ps1"
if errorlevel 1 pause
