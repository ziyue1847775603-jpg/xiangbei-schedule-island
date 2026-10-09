[CmdletBinding()]
param(
    [string]$ReleaseRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'Liminal\releases'),
    [switch]$Resume
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$workspace = Split-Path -Parent $projectRoot
$seriesRoot = Join-Path $workspace 'Liminal'
$destination = [IO.Path]::GetFullPath($ReleaseRoot)
if (-not $destination.StartsWith($seriesRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw '课表岛发布必须位于 Liminal 目录内。'
}

$packageName = 'Liminal.ScheduleIsland'
$seriesVersion = '1.0.0'
$androidBuildFile = Get-Content -LiteralPath (Join-Path $projectRoot 'android\app\build.gradle') -Raw -Encoding UTF8
$androidVersion = [regex]::Match($androidBuildFile, 'versionName\s+"([^"]+)"').Groups[1].Value
if (-not $androidVersion) { throw '无法读取 Android versionName。' }
$folder = Join-Path $destination $packageName
$zipPath = Join-Path $destination "$packageName-$seriesVersion-multiplatform.zip"
$exeName = "$packageName.exe"
$apkName = "$packageName-Android-$androidVersion.apk"
$exePath = Join-Path $folder $exeName
$apkPath = Join-Path $folder $apkName
$encoding = [Text.UTF8Encoding]::new($false)
$expectedSigner = 'C795B241BAF6BAC7362FA07C38E24AAB53AAC421527F658C2CA8B2C7BC55BEC2'
$expectedFiles = @($exeName, $apkName, 'README.md', 'schedule.example.csv', 'SERIES.json', 'SHA256SUMS.txt')
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Test-PublicPackage {
    $files = @(Get-ChildItem -LiteralPath $folder -Force)
    if ($files.Count -ne $expectedFiles.Count -or @($files | Where-Object {
        $_.PSIsContainer -or $_.Name -notin $expectedFiles
    }).Count) { throw '课表岛发布包包含预期以外的文件。' }
    if (Test-Path -LiteralPath (Join-Path $folder 'schedule.csv')) { throw '私人课表不得进入发布包。' }
    $archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $entries = @($archive.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        if ($entries.Count -ne $expectedFiles.Count) { throw '课表岛 ZIP 文件数量不匹配。' }
        foreach ($file in $files) {
            $entry = @($entries | Where-Object { $_.FullName.Replace('\','/') -eq "$packageName/$($file.Name)" })
            if ($entry.Count -ne 1) { throw "课表岛 ZIP 路径不匹配：$($file.Name)" }
            $stream = $entry[0].Open(); $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','') }
            finally { $stream.Dispose(); $sha.Dispose() }
            if ($hash -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) {
                throw "课表岛 ZIP 哈希不匹配：$($file.Name)"
            }
        }
    } finally { $archive.Dispose() }
}

if ((Test-Path -LiteralPath $folder) -or (Test-Path -LiteralPath $zipPath)) {
    if (-not $Resume) { throw '已有课表岛发布保留，请选择新的发布目录或使用续建核验。' }
    $record = Get-Content -LiteralPath (Join-Path $folder 'SERIES.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($record.modelId -ne 'schedule-island' -or $record.version -ne $seriesVersion -or
        $record.productFamily -ne 'SCHEDULE' -or
        $record.exeSha256 -ne (Get-FileHash -LiteralPath $exePath -Algorithm SHA256).Hash -or
        $record.apkSha256 -ne (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash -or
        (Get-Item -LiteralPath $exePath).VersionInfo.ProductName -ne 'Liminal · 课表岛') {
        throw '课表岛续建记录与文件不一致。'
    }
    Test-PublicPackage
    Write-Output 'RESUME verified Liminal Schedule Island package'
    return
}

$buildRoot = Join-Path $seriesRoot ('artifacts\schedule-island-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
$previousOutput = $env:LIQUID_ISLAND_OUTPUT_DIR
$previousPublic = $env:LIQUID_ISLAND_PUBLIC_PACKAGE
try {
    $env:LIQUID_ISLAND_OUTPUT_DIR = $buildRoot
    $env:LIQUID_ISLAND_PUBLIC_PACKAGE = '1'
    & (Join-Path $projectRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Windows 课表岛构建失败。' }
} finally {
    if ($null -eq $previousOutput) { Remove-Item Env:LIQUID_ISLAND_OUTPUT_DIR -ErrorAction SilentlyContinue }
    else { $env:LIQUID_ISLAND_OUTPUT_DIR = $previousOutput }
    if ($null -eq $previousPublic) { Remove-Item Env:LIQUID_ISLAND_PUBLIC_PACKAGE -ErrorAction SilentlyContinue }
    else { $env:LIQUID_ISLAND_PUBLIC_PACKAGE = $previousPublic }
}

$windowsCandidate = Join-Path $buildRoot 'Liminal.ScheduleIsland.exe'
$sampleCandidate = Join-Path $buildRoot 'schedule.example.csv'
if (-not (Test-Path -LiteralPath $windowsCandidate) -or -not (Test-Path -LiteralPath $sampleCandidate)) {
    throw 'Windows 课表岛公共构建缺少预期文件。'
}
$metadata = (Get-Item -LiteralPath $windowsCandidate).VersionInfo
if ($metadata.ProductName -ne 'Liminal · 课表岛' -or $metadata.FileVersion -ne '1.0.0.0') {
    throw 'Windows 课表岛产品元数据不匹配。'
}
$process = Start-Process -FilePath $windowsCandidate -ArgumentList '--self-test' -PassThru -Wait -WindowStyle Hidden
if ($process.ExitCode -ne 0) { throw "Windows 课表岛自测失败：$($process.ExitCode)" }

& (Join-Path $projectRoot 'build-android.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Android 课表岛构建失败。' }
$androidCandidate = Join-Path $projectRoot "release\Liminal-Schedule-Island-Android-$androidVersion.apk"
if (-not (Test-Path -LiteralPath $androidCandidate)) { throw 'Android APK 缺失。' }
$sdk = Join-Path $workspace 'android-toolchain\sdk'
$aapt = Join-Path $sdk 'build-tools\35.0.0\aapt.exe'
$apksigner = Join-Path $sdk 'build-tools\35.0.0\apksigner.bat'
$badging = (& $aapt dump badging $androidCandidate) -join "`n"
if ($badging -notmatch "package: name='com\.xiangbei\.scheduleisland'.*versionName='$([regex]::Escape($androidVersion))'" -or
    $badging -notmatch "application-label:'Liminal · 课表岛'") {
    throw 'Android APK 包名、版本或品牌标签不匹配。'
}
$signing = (& $apksigner verify --print-certs $androidCandidate) -join "`n"
$signer = [regex]::Match($signing, 'SHA-256 digest:\s*([0-9a-fA-F]{64})').Groups[1].Value.ToUpperInvariant()
if ($signer -ne $expectedSigner) { throw "Android 升级签名不匹配：$signer" }

New-Item -ItemType Directory -Path $folder | Out-Null
Copy-Item -LiteralPath $windowsCandidate -Destination $exePath
Copy-Item -LiteralPath $androidCandidate -Destination $apkPath
Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $folder 'README.md')
Copy-Item -LiteralPath (Join-Path $projectRoot 'schedule.example.csv') -Destination (Join-Path $folder 'schedule.example.csv')
$exeHash = (Get-FileHash -LiteralPath $exePath -Algorithm SHA256).Hash
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$record = [ordered]@{
    series = 'Liminal'; productFamily = 'SCHEDULE'; modelId = 'schedule-island'; model = '课表岛'
    displayName = 'Liminal · 课表岛'; legacyName = '向北课表岛'; version = $seriesVersion
    windowsVersion = '1.0.0'; androidVersion = $androidVersion; brandingDate = '2026-10-09'; protected = $false
    assemblyIdentity = [Reflection.AssemblyName]::GetAssemblyName($exePath).Name
    sourceProject = 'liquid-glass-timetable'; exe = "$packageName/$exeName"; exeSha256 = $exeHash
    apk = "$packageName/$apkName"; apkSha256 = $apkHash; apkSignerSha256 = $expectedSigner
    zip = [IO.Path]::GetFileName($zipPath)
    offlineTestsPassed = @('windows-schedule-self-test','android-testDebugUnitTest','android-lintDebug','android-package-metadata','public-package-hashes')
    deviceBrandingTested = $false; recentsAutoHideAcceptanceTested = $false
    compatibility = [ordered]@{
        androidApplicationId = 'com.xiangbei.scheduleisland'
        androidNamespace = 'com.xiangbei.scheduleisland'
        windowsNamespace = 'Xiangbei.LiquidIsland'
        startupRegistryValue = 'XiangbeiLiquidIsland'
        scheduleDirectory = '向北课表'
        legacyWindowsExecutable = '向北课表岛.exe'
    }
}
[IO.File]::WriteAllText((Join-Path $folder 'SERIES.json'), ($record | ConvertTo-Json -Depth 7), $encoding)
$seriesHash = (Get-FileHash -LiteralPath (Join-Path $folder 'SERIES.json') -Algorithm SHA256).Hash
$readmeHash = (Get-FileHash -LiteralPath (Join-Path $folder 'README.md') -Algorithm SHA256).Hash
$sampleHash = (Get-FileHash -LiteralPath (Join-Path $folder 'schedule.example.csv') -Algorithm SHA256).Hash
$sums = @(
    "$exeHash  $exeName",
    "$apkHash  $apkName",
    "$readmeHash  README.md",
    "$sampleHash  schedule.example.csv",
    "$seriesHash  SERIES.json"
) -join "`r`n"
[IO.File]::WriteAllText((Join-Path $folder 'SHA256SUMS.txt'), $sums + "`r`n", $encoding)
[IO.Compression.ZipFile]::CreateFromDirectory($folder, $zipPath, [IO.Compression.CompressionLevel]::Optimal, $true)
Test-PublicPackage
Write-Output "RELEASE Liminal Schedule Island $seriesVersion EXE=$exeHash APK=$apkHash"
