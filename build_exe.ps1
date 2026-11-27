$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Output "Checking PyInstaller..."
$hasPyInstaller = $true
try {
    python -m PyInstaller --version | Out-Null
} catch {
    $hasPyInstaller = $false
}

if (-not $hasPyInstaller) {
    Write-Output "Installing PyInstaller..."
    python -m pip install --upgrade pyinstaller
}

$ffmpegSrc = "D:\bilibilijj2\OrderEXE\ffmpeg.exe"
if (-not (Test-Path -LiteralPath $ffmpegSrc)) {
    throw "ffmpeg.exe not found at $ffmpegSrc"
}
$iconSrc = Join-Path $PSScriptRoot "assets\app.ico"
$assetsDir = Join-Path $PSScriptRoot "assets"

Write-Output "Building fully standalone BiliMusicDownloader.exe (with bundled ffmpeg.exe + custom icon + modern UI)..."
python -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --icon "$iconSrc" `
    --add-binary "${ffmpegSrc};." `
    --add-data "${assetsDir};assets" `
    --collect-data customtkinter `
    --name "BiliMusicDownloader" `
    app.py

$exePath = Join-Path $PSScriptRoot "dist\BiliMusicDownloader.exe"
if (-not (Test-Path -LiteralPath $exePath)) {
    throw "Build failed: $exePath not found"
}

Write-Output "Built EXE: $exePath ($((Get-Item -LiteralPath $exePath).Length) bytes)"
