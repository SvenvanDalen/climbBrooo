<#
.SYNOPSIS
    Build and run the Monkey C unit-test suites in the Connect IQ simulator.

.DESCRIPTION
    For each watch module (garmin, garmin-widget, garmin-surface) this script
    compiles the module's `monkey-test.jungle` with `--unit-test` and runs the
    resulting .prg in the Connect IQ simulator via `monkeydo /t`. It parses the
    `passed=/failed=/errors=` summary each module prints and exits non-zero if
    any test fails, errors, or a build fails.

    The Connect IQ simulator is a GUI process; this script will launch it
    (connectiq.bat) if `simulator.exe` is not already running, then push each
    test build to it. Requires the Connect IQ SDK + a developer key already
    installed under %APPDATA%\Garmin\ConnectIQ (SDK Manager does this).

.PARAMETER Device
    Device profile to simulate. Defaults to fr255m (Forerunner 255 Music).

.PARAMETER Modules
    Watch module directories to test. Defaults to all three.

.EXAMPLE
    pwsh -File tools/run-monkeyc-tests.ps1
    pwsh -File tools/run-monkeyc-tests.ps1 -Modules garmin
#>
[CmdletBinding()]
param(
    [string]   $Device  = "fr255m",
    [string[]] $Modules = @("garmin", "garmin-widget", "garmin-surface", "garmin-onboard")
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$ciqRoot  = Join-Path $env:APPDATA "Garmin\ConnectIQ"

# --- Locate the SDK (current-sdk.cfg holds the active SDK path) ---
$sdkCfg = Join-Path $ciqRoot "current-sdk.cfg"
if (-not (Test-Path $sdkCfg)) {
    Write-Error "No Connect IQ SDK found. Install one via the Connect IQ SDK Manager (expected: $sdkCfg)."
}
$sdkPath = (Get-Content $sdkCfg -Raw).Trim()
$bin = Join-Path $sdkPath "bin"
$monkeyc  = Join-Path $bin "monkeyc.bat"
$monkeydo = Join-Path $bin "monkeydo.bat"
$connectiq = Join-Path $bin "connectiq.bat"
foreach ($t in @($monkeyc, $monkeydo, $connectiq)) {
    if (-not (Test-Path $t)) { Write-Error "Missing SDK tool: $t" }
}

$key = Join-Path $ciqRoot "developer_key.der"
if (-not (Test-Path $key)) {
    Write-Error "No developer key at $key. Generate one in the SDK Manager (Settings > Generate a key)."
}

$buildDir = Join-Path $repoRoot "build"
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

# --- Ensure the simulator is running (it is a GUI process) ---
if (-not (Get-Process -Name "simulator" -ErrorAction SilentlyContinue)) {
    Write-Host "Starting Connect IQ simulator..."
    Start-Process -FilePath $connectiq -WindowStyle Minimized | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        if (Get-Process -Name "simulator" -ErrorAction SilentlyContinue) { break }
    }
    Start-Sleep -Seconds 3   # give it a moment to open its control port
}

$totalPass = 0; $totalFail = 0; $totalErr = 0; $hadBuildError = $false

foreach ($m in $Modules) {
    $jungle = Join-Path $repoRoot "$m\monkey-test.jungle"
    if (-not (Test-Path $jungle)) {
        Write-Warning "Skipping $m (no monkey-test.jungle)"
        continue
    }
    $prg = Join-Path $buildDir "$m-test.prg"
    Write-Host "`n=== Building $m (unit-test) ===" -ForegroundColor Cyan
    & $monkeyc -f $jungle -o $prg -y $key -d $Device --unit-test
    if ($LASTEXITCODE -ne 0) {
        Write-Host "BUILD FAILED: $m" -ForegroundColor Red
        $hadBuildError = $true
        continue
    }

    Write-Host "=== Running $m tests ===" -ForegroundColor Cyan
    $log = & $monkeydo $prg $Device /t 2>&1 | Out-String
    Write-Host $log

    if ($log -match "passed=(\d+),\s*failed=(\d+),\s*errors=(\d+)") {
        $totalPass += [int]$Matches[1]
        $totalFail += [int]$Matches[2]
        $totalErr  += [int]$Matches[3]
    } else {
        Write-Host "No RESULTS summary parsed for $m - treating as error." -ForegroundColor Red
        $totalErr += 1
    }
}

Write-Host "`n================ Monkey C TOTAL ================" -ForegroundColor Yellow
Write-Host ("passed={0}, failed={1}, errors={2}" -f $totalPass, $totalFail, $totalErr)

if ($hadBuildError -or $totalFail -gt 0 -or $totalErr -gt 0) {
    Write-Host "MONKEY C TESTS FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "ALL MONKEY C TESTS PASSED" -ForegroundColor Green
exit 0
