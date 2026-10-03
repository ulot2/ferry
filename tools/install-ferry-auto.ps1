# install-ferry-auto.ps1 - installs Ferry Auto on an Android phone from this Windows computer, over Wi-Fi.
#
# Google Play Protect blocks Ferry Auto when it is installed from a phone browser, because it uses
# Android's accessibility permission for automatic sending. Installs from a computer are not blocked.
# Run it again to update Ferry Auto. Your pairing and history stay.
#
# Usage:  powershell -ExecutionPolicy Bypass -File install-ferry-auto.ps1 [-Apk path\to\Ferry-Auto.apk]
param([string]$Apk)
$ErrorActionPreference = 'Stop'

$home_ = Join-Path $env:LOCALAPPDATA 'Ferry'
$adb = Join-Path $home_ 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Host "Downloading adb, Google's Android platform tools (about 7 MB)..."
    $zip = Join-Path $env:TEMP 'platform-tools.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive $zip $home_ -Force
    Remove-Item $zip
}

if (-not $Apk) {
    Write-Host 'Downloading the latest Ferry Auto...'
    $Apk = Join-Path $env:TEMP 'Ferry-Auto.apk'
    Invoke-WebRequest 'https://github.com/ulot2/ferry/releases/latest/download/Ferry-Auto.apk' -OutFile $Apk
}

Write-Host @'

Do these steps on the phone. The phone and this computer must be on the same Wi-Fi.
  1. Open Settings > About phone. Tap "Build number" 7 times.
     On Xiaomi, Redmi or POCO phones, tap "OS version" 7 times instead.
  2. Open Developer options (Settings > System, or Settings > Additional settings).
  3. Turn on "Wireless debugging".
     On Xiaomi, Redmi or POCO phones, also turn on "Install via USB".
  4. Tap "Wireless debugging", then tap "Pair device with pairing code".

'@
$pairAt = Read-Host 'Type the IP address and port under the pairing code (for example 192.168.1.5:37123)'
Write-Host 'Now type the 6-digit pairing code from the phone when adb asks for it.'
& $adb pair $pairAt
if ($LASTEXITCODE -ne 0) { throw 'Pairing failed. Make sure that the phone still shows the pairing code, then run this script again.' }

$connectAt = Read-Host 'Type the IP address and port at the top of the Wireless debugging screen (not the pairing one)'
& $adb connect $connectAt
& $adb -s $connectAt install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'The install failed. On Xiaomi phones, make sure that "Install via USB" is on, then run this script again.' }

& $adb disconnect $connectAt | Out-Null
Write-Host ''
Write-Host 'Ferry Auto is installed. Open Ferry on the phone and turn on automatic sending.'
Write-Host 'You can now turn off Wireless debugging.'
