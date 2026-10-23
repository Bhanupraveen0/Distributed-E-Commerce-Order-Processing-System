# Distributed E-Commerce Order Processing System - PowerShell Demo Runner

$javaPath = "java"
$javacPath = "javac"

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    $jbrJavac = "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe"
    $jbrJava = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
    if (Test-Path $jbrJavac) {
        $javacPath = $jbrJavac
        $javaPath = $jbrJava
    } else {
        Write-Error "Java compiler (javac) not found. Please install JDK or ensure it is on PATH."
        exit 1
    }
}

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "  Distributed E-Commerce Order Processing System (DC Mini Project)" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

Write-Host "`n[1/3] Compiling Java source files..." -ForegroundColor Yellow
if (-not (Test-Path out)) { New-Item -ItemType Directory -Path out | Out-Null }

$javaFiles = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
& $javacPath -d out $javaFiles

if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed."
    exit 1
}
Write-Host "[SUCCESS] Compilation completed successfully." -ForegroundColor Green

Write-Host "`n[2/3] Starting Distributed System in background..." -ForegroundColor Yellow
$serverProcess = Start-Process -FilePath $javaPath -ArgumentList "-cp", "out", "com.ecommerce.DistributedOrderSystem" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "`n[3/3] Running OrderClient scenarios..." -ForegroundColor Yellow
& $javaPath -cp out com.ecommerce.OrderClient $args

Write-Host "`nStopping background server..." -ForegroundColor DarkGray
Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
Write-Host "Done!" -ForegroundColor Green
