$ErrorActionPreference = 'Stop'

$repoRoot = $PSScriptRoot
$workspaceRoot = Split-Path -Parent $repoRoot
$toolRoot = Join-Path $workspaceRoot 'android-toolchain'
$androidRoot = Join-Path $repoRoot 'android'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $toolRoot 'sdk' }
$jdkHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME
} elseif (Test-Path -LiteralPath (Join-Path $toolRoot 'jdk')) {
    (Get-ChildItem -LiteralPath (Join-Path $toolRoot 'jdk') -Directory | Select-Object -First 1).FullName
}
$gradle = Join-Path $androidRoot 'gradlew.bat'

if (-not $jdkHome -or -not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe'))) {
    throw 'Portable JDK 17 was not found.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-35'))) {
    throw 'Android SDK 35 was not found.'
}
if (-not (Test-Path -LiteralPath $gradle)) { throw 'Gradle wrapper was not found.' }

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home'
$env:PATH = "$jdkHome\bin;$(Join-Path $sdkRoot 'platform-tools');$env:PATH"
$preservedDebugKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (Test-Path -LiteralPath $preservedDebugKey) {
    $env:XIANGBEI_DEBUG_KEYSTORE = $preservedDebugKey
}

Push-Location $androidRoot
try {
    & $gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}

$apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'The build completed without producing an APK.' }
$buildFile = Get-Content -LiteralPath (Join-Path $androidRoot 'app\build.gradle') -Raw
$version = [regex]::Match($buildFile, 'versionName\s+"([^"]+)"').Groups[1].Value
if (-not $version) { throw 'Could not read versionName from app/build.gradle.' }
$releaseDir = Join-Path $repoRoot 'release'
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
$releaseApk = Join-Path $releaseDir "Xiangbei-Schedule-Island-Android-$version-debug.apk"
Copy-Item -LiteralPath $apk -Destination $releaseApk -Force
Write-Output "APK=$releaseApk"
Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256
