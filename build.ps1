$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $projectRoot 'release'
$compiler = 'D:\VSBuildTools\MSBuild\Current\Bin\Roslyn\csc.exe'
$framework = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
$wpf = Join-Path $framework 'WPF'
$iconPath = Join-Path $projectRoot 'assets\xiangbei-schedule-island.ico'
$displayExeName = (-join [char[]](0x5411, 0x5317, 0x8BFE, 0x8868, 0x5C9B)) + '.exe'
$compiledExePath = Join-Path $outDir 'XiangbeiScheduleIsland.build.exe'
$exePath = Join-Path $outDir $displayExeName
$targetOption = if ($env:LIQUID_ISLAND_CONSOLE -eq '1') { '/target:exe' } else { '/target:winexe' }

if (-not (Test-Path -LiteralPath $compiler)) {
    throw "Roslyn compiler not found: $compiler"
}
if (-not (Test-Path -LiteralPath $iconPath)) {
    throw "Application icon not found: $iconPath"
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$refs = @(
    (Join-Path $framework 'mscorlib.dll'),
    (Join-Path $framework 'System.dll'),
    (Join-Path $framework 'System.Core.dll'),
    (Join-Path $framework 'System.Xml.dll'),
    (Join-Path $wpf 'WindowsBase.dll'),
    (Join-Path $wpf 'PresentationCore.dll'),
    (Join-Path $wpf 'PresentationFramework.dll'),
    (Join-Path $framework 'System.Xaml.dll')
) | ForEach-Object { '/reference:' + $_ }

& $compiler /nologo $targetOption /platform:anycpu /optimize+ /debug- /langversion:latest `
    ('/win32icon:' + $iconPath) `
    ('/out:' + $compiledExePath) `
    $refs `
    (Join-Path $projectRoot 'LiquidIsland.cs')

if ($LASTEXITCODE -ne 0) { throw "Compilation failed with exit code $LASTEXITCODE" }
Move-Item -LiteralPath $compiledExePath -Destination $exePath -Force

$scheduleSource = Join-Path $projectRoot 'schedule.csv'
if (-not (Test-Path -LiteralPath $scheduleSource)) {
    $scheduleSource = Join-Path $projectRoot 'schedule.example.csv'
}
Copy-Item -LiteralPath $scheduleSource -Destination (Join-Path $outDir 'schedule.csv') -Force

Get-FileHash -Algorithm SHA256 -LiteralPath $exePath, (Join-Path $outDir 'schedule.csv') |
    Select-Object Path, Hash
