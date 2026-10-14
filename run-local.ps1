$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = Join-Path $PSScriptRoot '.tools\android-sdk'
$env:ANDROID_HOME = $sdk
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.tools\android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools\gradle-cache'
if (Test-Path 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe') {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
} elseif (Test-Path 'C:\Program Files\Java\jdk-22\bin\java.exe') {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-22'
}
& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
& $adb start-server
$devices = & $adb devices
if (-not ($devices -match '^emulator-5554\s+')) {
    Start-Process (Join-Path $sdk 'emulator\emulator.exe') -ArgumentList '-avd RaceLinkTablet -port 5554 -no-snapshot-load' -WindowStyle Hidden
}
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 2
    $ErrorActionPreference = 'Continue'
    $booted = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null
    $ErrorActionPreference = 'Stop'
    if ((Get-Date) -gt $deadline) { throw 'Emulator did not boot within five minutes.' }
} until ($booted -match '^1$')
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $adb -s emulator-5554 shell am start -n se.racelink.studio.demo/se.racelink.studio.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
