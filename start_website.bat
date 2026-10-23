@echo off
setlocal

where javac >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "JAVA_CMD=java"
    set "JAVAC_CMD=javac"
) else (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" (
        set "JAVA_CMD=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
        set "JAVAC_CMD=C:\Program Files\Android\Android Studio\jbr\bin\javac.exe"
    ) else (
        echo [ERROR] Java compiler not found.
        pause
        exit /b 1
    )
)

echo ==================================================================
echo   Distributed E-Commerce Order Processing System - Web Launcher
echo ==================================================================

echo.
echo [1/3] Compiling Java source files...
if not exist out mkdir out
powershell -NoProfile -Command "Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }" > sources.tmp
"%JAVAC_CMD%" -d out @sources.tmp
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    del sources.tmp
    pause
    exit /b 1
)
del sources.tmp

echo.
echo [2/3] Cleaning up any stale port bindings...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":5000 " ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
timeout /t 1 /nobreak >nul

echo.
echo [3/3] Starting Distributed System and opening Web Dashboard...
start "" http://localhost:8080
"%JAVA_CMD%" -cp out com.ecommerce.DistributedOrderSystem

pause
