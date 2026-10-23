@echo off
setlocal enabledelayedexpansion

echo ==================================================================
echo   Distributed E-Commerce Order Processing System (DC Mini Project)
echo ==================================================================

:: Check if javac is on PATH
where javac >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "JAVAC_CMD=javac"
    set "JAVA_CMD=java"
) else (
    :: Fallback to installed Android Studio JBR
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" (
        set "JAVAC_CMD=C:\Program Files\Android\Android Studio\jbr\bin\javac.exe"
        set "JAVA_CMD=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
    ) else (
        echo [ERROR] Java compiler ^(javac^) not found. Please install JDK or add to PATH.
        pause
        exit /b 1
    )
)

echo [1/3] Compiling Java source files...
if not exist out mkdir out

:: Collect java source files
powershell -NoProfile -Command "Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }" > sources.tmp
"%JAVAC_CMD%" -d out @sources.tmp
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    del sources.tmp
    pause
    exit /b 1
)
del sources.tmp
echo [SUCCESS] Compilation complete.

echo.
echo [2/3] Starting Distributed Services in background...
:: Stop any previous server holding port 5000
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":5000 " ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
timeout /t 1 /nobreak >nul

start /b "" "%JAVA_CMD%" -cp out com.ecommerce.DistributedOrderSystem > server.log 2>&1
timeout /t 2 /nobreak >nul

echo.
echo [3/3] Running OrderClient test scenarios...
echo.
"%JAVA_CMD%" -cp out com.ecommerce.OrderClient %*

echo.
echo Stopping background server...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":5000 " ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
echo Demonstration complete. Review server.log for coordinator & rollback trace.
endlocal
