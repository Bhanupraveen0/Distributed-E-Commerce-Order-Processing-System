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
        echo [ERROR] Java not found.
        pause
        exit /b 1
    )
)

echo Compiling project...
if not exist out mkdir out
powershell -NoProfile -Command "Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }" > sources.tmp
"%JAVAC_CMD%" -d out @sources.tmp
del sources.tmp

echo Starting Distributed Order System (Ports: 5000, 6001, 6002, 6003, Web Dashboard: 8080)...
"%JAVA_CMD%" -cp out com.ecommerce.DistributedOrderSystem
pause
