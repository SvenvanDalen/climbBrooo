#requires -version 5
<#
  Gated build: nothing is built unless preflight passes first.
  Usage:  pwsh -File scripts/build.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '== Preflight gate ==' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'preflight.ps1')
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Build aborted: preflight did not pass.' -ForegroundColor Red
    exit 1
}

Write-Host '== Building Android APK ==' -ForegroundColor Cyan
& (Join-Path $root 'android\gradlew.bat') assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host 'Android build failed' -ForegroundColor Red; exit 1 }

Write-Host '== Building Connect IQ apps ==' -ForegroundColor Cyan
if ($null -eq (Get-Command monkeyc -ErrorAction SilentlyContinue)) {
    Write-Host 'monkeyc not on PATH - skipping .prg build' -ForegroundColor Yellow
} else {
    $key  = Join-Path $root 'developer_key'
    $dist = Join-Path $root 'builds'
    if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Path $dist | Out-Null }
    $apps = @(
        @{ dir = 'garmin';         out = 'ClimbPro.prg' },
        @{ dir = 'garmin-widget';  out = 'ClimbWidget.prg' },
        @{ dir = 'garmin-surface'; out = 'SurfaceField.prg' }
    )
    foreach ($a in $apps) {
        & monkeyc -f (Join-Path $root (Join-Path $a.dir 'monkey.jungle')) `
                  -o (Join-Path $dist $a.out) -y $key -d fr255m
        if ($LASTEXITCODE -ne 0) { Write-Host ("Build failed: " + $a.dir) -ForegroundColor Red; exit 1 }
    }
}
Write-Host 'BUILD COMPLETE' -ForegroundColor Green
