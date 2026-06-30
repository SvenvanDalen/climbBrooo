#requires -version 5
<#
  Preflight pipeline: runs every check that must pass BEFORE anything is built.
  Hard gates (failure -> exit 1):
    1. Android JVM tests (unit + ProtocolRoundTripTest + ProtocolLockstepGuardTest + MonkeyCSourceGuardTest).
    2. Monkey C compilation of all three Connect IQ apps (only if `monkeyc` is on PATH).
  Soft gates (warn, do not fail):
    - Monkey C unit tests (need the simulator; skipped when unavailable).
  Usage:  pwsh -File scripts/preflight.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fail = @()
$warn = @()

Write-Host '== [1/3] Android JVM tests ==' -ForegroundColor Cyan
Push-Location (Join-Path $root 'android')
try {
    & (Join-Path $root 'android\gradlew.bat') test --console=plain
    if ($LASTEXITCODE -ne 0) { $fail += 'Android JVM tests failed' }
} finally { Pop-Location }

Write-Host '== [2/3] Monkey C compilation ==' -ForegroundColor Cyan
$monkeyc = (Get-Command monkeyc -ErrorAction SilentlyContinue)
if ($null -eq $monkeyc) {
    $warn += 'monkeyc not on PATH - Connect IQ compile skipped'
} else {
    $key = Join-Path $root 'developer_key'
    $apps = @(
        @{ dir = 'garmin';         out = 'ClimbPro.prg' },
        @{ dir = 'garmin-widget';  out = 'ClimbWidget.prg' },
        @{ dir = 'garmin-surface'; out = 'SurfaceField.prg' }
    )
    foreach ($a in $apps) {
        $jungle = Join-Path $root (Join-Path $a.dir 'monkey.jungle')
        $out    = Join-Path $env:TEMP $a.out
        & monkeyc -f $jungle -o $out -y $key -d fr255m
        if ($LASTEXITCODE -ne 0) { $fail += ("Monkey C compile failed: " + $a.dir) }
    }
}

Write-Host '== [3/3] Monkey C unit tests ==' -ForegroundColor Cyan
if ($null -eq (Get-Command monkeydo -ErrorAction SilentlyContinue)) {
    $warn += 'monkeydo/simulator unavailable - Monkey C unit tests skipped (review-only)'
} else {
    $key = Join-Path $root 'developer_key'
    $testOut = Join-Path $env:TEMP 'ClimbProTest.prg'
    & monkeyc -f (Join-Path $root 'garmin\monkey-test.jungle') -o $testOut -y $key -d fr255m --test
    if ($LASTEXITCODE -ne 0) { $fail += 'Monkey C test build failed' }
    else { & monkeydo $testOut fr255m -t; if ($LASTEXITCODE -ne 0) { $fail += 'Monkey C unit tests failed' } }
}

Write-Host ''
foreach ($w in $warn) { Write-Host ("WARN: " + $w) -ForegroundColor Yellow }
if ($fail.Count -gt 0) {
    foreach ($f in $fail) { Write-Host ("FAIL: " + $f) -ForegroundColor Red }
    Write-Host 'PREFLIGHT FAILED' -ForegroundColor Red
    exit 1
}
Write-Host 'PREFLIGHT PASSED' -ForegroundColor Green
exit 0
