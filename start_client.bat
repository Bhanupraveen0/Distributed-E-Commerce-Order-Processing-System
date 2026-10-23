@echo off
setlocal

where java >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "JAVA_CMD=java"
) else (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_CMD=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
    ) else (
        echo [ERROR] Java not found.
        pause
        exit /b 1
    )
)

echo Running OrderClient...
"%JAVA_CMD%" -cp out com.ecommerce.OrderClient %*
pause
