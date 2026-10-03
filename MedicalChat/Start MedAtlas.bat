@echo off
setlocal
title MedAtlas Launcher
cd /d "%~dp0"

echo.
echo  ==========================================
echo          MEDATLAS - ONE CLICK START
echo  ==========================================
echo.

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found.
  echo Install JDK 17 or newer, then try again.
  echo https://www.oracle.com/java/technologies/downloads/
  echo.
  pause
  exit /b 1
)

if not exist "Main.java" (
  echo Main.java was not found.
  echo Keep this launcher in the same folder as Main.java.
  echo.
  pause
  exit /b 1
)

if not exist "web\index.html" (
  echo web\index.html was not found.
  echo Keep your web folder beside Main.java.
  echo.
  pause
  exit /b 1
)

echo Compiling MedAtlas...
javac Main.java
if errorlevel 1 (
  echo.
  echo Compilation failed. Check the error messages above.
  pause
  exit /b 1
)

echo.
echo Starting MedAtlas server...
echo When the server starts, open the URL printed below.
echo Keep this window open while using MedAtlas.
echo To stop the server, close this window or press Ctrl+C.
echo.
java Main
echo.
echo MedAtlas has stopped.
pause
