@echo off
setlocal
cd /d "%~dp0"

if not exist out mkdir out

javac -d out src\DocgenServer.java
if errorlevel 1 exit /b 1

java -cp out DocgenServer %1
