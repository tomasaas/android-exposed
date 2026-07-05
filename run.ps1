$ErrorActionPreference = "Stop"

$AppId = "com.example.androidexposed"
$Activity = "$AppId/.MainActivity"
$Port = 8765
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

Set-Location $Root

function Find-Adb {
    $paths = @(
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    )

    foreach ($path in $paths) {
        if ($path -and (Test-Path $path)) {
            return $path
        }
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "adb.exe was not found. Install Android SDK platform-tools or open this from Android Studio's terminal."
}

function Ensure-Java {
    if (Get-Command java -ErrorAction SilentlyContinue) {
        return
    }

    $studioJava = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path "$studioJava\bin\java.exe") {
        $env:JAVA_HOME = $studioJava
        $env:Path = "$studioJava\bin;$env:Path"
        return
    }

    throw "Java was not found. Set JAVA_HOME or install Android Studio."
}

$adb = Find-Adb
Ensure-Java

Write-Host "Checking connected Android device..."
$devices = & $adb devices | Select-String "`tdevice$"

if ($devices.Count -eq 0) {
    throw "No Android device found. Connect the phone with USB debugging enabled."
}

if ($devices.Count -gt 1 -and -not $env:ANDROID_SERIAL) {
    Write-Host "Multiple devices found:"
    & $adb devices
    throw "Set ANDROID_SERIAL to choose one device, then run this script again."
}

Write-Host "Building and installing debug APK..."
& "$Root\gradlew.bat" installDebug

Write-Host "Forwarding host port $Port to phone port $Port..."
& $adb forward "tcp:$Port" "tcp:$Port"

Write-Host "Starting $AppId..."
& $adb shell am start -n $Activity

Write-Host ""
Write-Host "App started."
Write-Host "On the phone: choose sources/rates, then tap 'Start sensor exposure'."
Write-Host "Test from this computer using ./tools-scripts..."

