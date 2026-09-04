@echo off
setlocal
cd /d "%~dp0"

set "JNA_VERSION=5.19.1"
set "JNA_JAR=%~dp0lib\jna-%JNA_VERSION%.jar"
set "JNA_URL=https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/%JNA_VERSION%/jna-%JNA_VERSION%.jar"

if not exist "%JNA_JAR%" (
    if not exist "%~dp0lib" mkdir "%~dp0lib"
    echo JNA %JNA_VERSION% not found. Downloading...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -UseBasicParsing -Uri '%JNA_URL%' -OutFile '%JNA_JAR%'; exit 0 } catch { exit 1 }"
)

if exist "%JNA_JAR%" (
    start "" javaw -Dfile.encoding=UTF-8 -cp "%~dp0dist\SchoolBell.jar;%JNA_JAR%" com.mystersay.schoolbell.Main
) else (
    echo Warning: JNA could not be downloaded. SchoolBell will start, but Windows sleep prevention via JNA will be unavailable.
    start "" javaw -Dfile.encoding=UTF-8 -jar "%~dp0dist\SchoolBell.jar"
)
