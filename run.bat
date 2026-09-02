@echo off
setlocal
cd /d "%~dp0"
if not exist dist\SchoolBell.jar call build.bat
if not exist dist\SchoolBell.jar exit /b 1
start "SchoolBell" javaw -jar dist\SchoolBell.jar
