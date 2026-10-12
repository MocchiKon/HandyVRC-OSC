@echo off
rem Starts HandyVRC-OSC from the folder of this script, so app.properties and logs\ are kept next to the jar
cd /d "%~dp0"
java -jar HandyVRC-OSC.jar %*
if errorlevel 1 pause
