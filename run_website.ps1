# Distributed E-Commerce Order Processing System - Website Launcher (PowerShell)

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
Write-Host "  Distributed E-Commerce Order Processing System - Web Dashboard  " -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# Terminate any old server holding port 5000 or 8080
$stalePorts = 5000, 8080
foreach ($p in $stalePorts) {
    $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($conns) {
        foreach ($c in $conns) {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "`n[1/3] Compiling Java source files..." -ForegroundColor Yellow
if (-not (Test-Path out)) { New-Item -ItemType Directory -Path out | Out-Null }

$javaFiles = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
& $javacPath -d out $javaFiles

if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed."
    exit 1
}
Write-Host "[SUCCESS] Compilation completed successfully." -ForegroundColor Green

Write-Host "`n[2/3] Launching Distributed Order System & Web Dashboard..." -ForegroundColor Yellow
$serverProcess = Start-Process -FilePath $javaPath -ArgumentList "-cp", "out", "com.ecommerce.DistributedOrderSystem" -PassThru

Start-Sleep -Seconds 2

Write-Host "`n[3/3] Opening Web Dashboard in default browser: http://localhost:8080" -ForegroundColor Cyan
Start-Process "http://localhost:8080"

Write-Host "`n==================================================================" -ForegroundColor Green
Write-Host "  Web Dashboard is LIVE at: http://localhost:8080                 " -ForegroundColor Green
Write-Host "  Coordinator: Port 5000 | Inventory: 6001 | Payment: 6002 | Ship: 6003" -ForegroundColor Gray
Write-Host "  Press Ctrl+C in this terminal or close window to stop server.   " -ForegroundColor Yellow
Write-Host "==================================================================" -ForegroundColor Green

try {
    Wait-Process -Id $serverProcess.Id
} finally {
    Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
}
