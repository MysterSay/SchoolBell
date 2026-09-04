@echo off
setlocal
cd /d "%~dp0"
set "JNA_JAR=%~dp0lib\jna-5.19.1.jar"
if exist "%JNA_JAR%" (
  java -Dfile.encoding=UTF-8 -cp "%~dp0dist\SchoolBell.jar;%JNA_JAR%" com.mystersay.schoolbell.Main
) else (
  java -Dfile.encoding=UTF-8 -jar "%~dp0dist\SchoolBell.jar"
)
