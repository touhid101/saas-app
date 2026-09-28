# Quick Lombok Fix Script for Windows PowerShell
# This script attempts multiple solutions to fix the Lombok compilation issue

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "  Friends Pharma ERP - Lombok Compilation Fix Script" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host ""

# Set Java Home
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host "Step 1: Verifying Java installation..." -ForegroundColor Yellow
java -version
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Java not found. Please install JDK 21." -ForegroundColor Red
    exit 1
}
Write-Host "✓ Java OK" -ForegroundColor Green
Write-Host ""

Write-Host "Step 2: Cleaning previous build artifacts..." -ForegroundColor Yellow
if (Test-Path "target") {
    Remove-Item -Recurse -Force target
    Write-Host "✓ Removed target/" -ForegroundColor Green
}
if (Test-Path "$env:USERPROFILE\.m2\repository\com\friendspharma") {
    Remove-Item -Recurse -Force "$env:USERPROFILE\.m2\repository\com\friendspharma"
    Write-Host "✓ Removed cached artifacts" -ForegroundColor Green
}
Write-Host ""

Write-Host "Step 3: Attempting Maven build with delombok..." -ForegroundColor Yellow
Write-Host "  This may take 1-2 minutes..." -ForegroundColor Gray
mvn clean install -DskipTests -U 2>&1 | Tee-Object -Variable buildOutput | Out-Null

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Green
    Write-Host "  ✓✓✓ SUCCESS! Build completed successfully! ✓✓✓" -ForegroundColor Green
    Write-Host "==================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "  1. Configure database in src/main/resources/application.yml"
    Write-Host "  2. Run application: mvn spring-boot:run"
    Write-Host "  3. Access Swagger UI: http://localhost:8080/swagger-ui.html"
    Write-Host ""
    Write-Host "For more info, see README.md" -ForegroundColor Gray
    exit 0
}

Write-Host ""
Write-Host "Build failed. Analyzing errors..." -ForegroundColor Yellow

# Check if it's Lombok-related
$lombokErrors = $buildOutput | Select-String "cannot find symbol.*get"
if ($lombokErrors) {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Red
    Write-Host "  Lombok annotation processing is not working in Maven CLI" -ForegroundColor Red
    Write-Host "==================================================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "This is a known issue. Please use one of these solutions:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "SOLUTION 1 (RECOMMENDED - 5 minutes):" -ForegroundColor Cyan
    Write-Host "  • Open project in IntelliJ IDEA or Eclipse"
    Write-Host "  • Install Lombok plugin"
    Write-Host "  • Enable annotation processing"
    Write-Host "  • Build → Rebuild Project"
    Write-Host "  • Run PharmaPosApplication"
    Write-Host ""
    Write-Host "SOLUTION 2 (Manual - 60 minutes):" -ForegroundColor Cyan
    Write-Host "  • See LOMBOK_FIX_GUIDE.md → Solution 4"
    Write-Host "  • Add explicit @Getter @Setter to all DTOs"
    Write-Host ""
    Write-Host "For detailed instructions, see:" -ForegroundColor Gray
    Write-Host "  → LOMBOK_FIX_GUIDE.md" -ForegroundColor White
    Write-Host ""
    
    # Save errors to file for analysis
    $buildOutput | Out-File -FilePath "build-errors.log"
    Write-Host "Full error log saved to: build-errors.log" -ForegroundColor Gray
} else {
    Write-Host ""
    Write-Host "Build failed with non-Lombok errors. Check build-errors.log" -ForegroundColor Red
    $buildOutput | Out-File -FilePath "build-errors.log"
}

Write-Host ""
Write-Host "Need help? Check these files:" -ForegroundColor Gray
Write-Host "  • LOMBOK_FIX_GUIDE.md     - Step-by-step Lombok solutions"
Write-Host "  • IMPLEMENTATION_STATUS.md - What's been built"
Write-Host "  • SESSION_SUMMARY.md      - Development session overview"
Write-Host "  • README.md               - Complete setup guide"
Write-Host ""

exit 1
