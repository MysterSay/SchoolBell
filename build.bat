@echo off
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes
mkdir dist
for /r src\main\java %%F in (*.java) do echo %%F>>build\sources.txt
call javac --release 17 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
call jar --create --file dist\SchoolBell.jar --main-class com.mystersay.schoolbell.Main -C build\classes .
if errorlevel 1 exit /b 1
echo.
echo Ready: dist\SchoolBell.jar
pause
