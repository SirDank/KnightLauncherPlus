[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Dev,
    [switch]$Run,
    [switch]$DryRun,
    [string]$SkDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
Set-Location $repoRoot

function Find-SpiralKnightsDir {
    if ($SkDir) {
        if (Test-Path (Join-Path $SkDir 'code\projectx-pcode.jar')) {
            return (Resolve-Path $SkDir).Path
        }
        throw "Specified -SkDir '$SkDir' does not contain code\projectx-pcode.jar"
    }

    # 1. Environment variables
    foreach ($var in @('SK_GAME_DIR', 'SK_DIR', 'SPIRAL_KNIGHTS_DIR')) {
        $val = [Environment]::GetEnvironmentVariable($var)
        if ($val -and (Test-Path (Join-Path $val 'code\projectx-pcode.jar'))) {
            return (Resolve-Path $val).Path
        }
    }

    # 2. Windows Steam Registry & libraryfolders.vdf
    $steamPath = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -Name 'SteamPath' -ErrorAction SilentlyContinue).SteamPath
    if (-not $steamPath) {
        $steamPath = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam' -Name 'InstallPath' -ErrorAction SilentlyContinue).InstallPath
    }
    if ($steamPath) {
        $direct = Join-Path $steamPath 'steamapps\common\Spiral Knights'
        if (Test-Path (Join-Path $direct 'code\projectx-pcode.jar')) {
            return (Resolve-Path $direct).Path
        }
        $vdf = Join-Path $steamPath 'steamapps\libraryfolders.vdf'
        if (Test-Path $vdf) {
            $content = Get-Content $vdf -Raw
            $regex = [regex]'\"path\"\s+\"([^\"]+)\"'
            foreach ($m in $regex.Matches($content)) {
                $libPath = $m.Groups[1].Value -replace '\\\\', '\'
                $cand = Join-Path $libPath 'steamapps\common\Spiral Knights'
                if (Test-Path (Join-Path $cand 'code\projectx-pcode.jar')) {
                    return (Resolve-Path $cand).Path
                }
            }
        }
    }

    # 3. Common drive candidate paths
    $candidates = @(
        'D:\SteamLibrary\steamapps\common\Spiral Knights',
        'C:\Program Files (x86)\Steam\steamapps\common\Spiral Knights',
        'C:\Steam\steamapps\common\Spiral Knights',
        'C:\SteamLibrary\steamapps\common\Spiral Knights',
        'E:\SteamLibrary\steamapps\common\Spiral Knights',
        (Join-Path $env:LOCALAPPDATA 'Spiral Knights\app')
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'code\projectx-pcode.jar'))) {
            return (Resolve-Path $c).Path
        }
    }

    throw 'Could not automatically detect Spiral Knights directory. Set $env:SK_GAME_DIR or pass -SkDir <path>.'
}

$targetSkDir = Find-SpiralKnightsDir
Write-Host "[*] Detected Spiral Knights Directory: $targetSkDir" -ForegroundColor Cyan

if ($DryRun) {
    Write-Host "[*] Dry run completed successfully." -ForegroundColor Green
    return
}

# 1. Build via Maven Wrapper
$mvnw = Join-Path $repoRoot 'mvnw.cmd'
if (-not (Test-Path $mvnw)) {
    throw "Maven wrapper '$mvnw' not found."
}

$envMode = if ($Dev) { 'dev' } else { 'prod' }

# The build bakes deploy.properties into the jar; a fresh clone only has the template.
if (-not (Test-Path 'deploy.properties')) {
    Copy-Item 'deploy.properties.template' 'deploy.properties'
}

# Compiling needs the game's own jar, which can't be committed: take it from the game install
# (and refresh it when the game updated).
$gamePcode = Join-Path $targetSkDir 'code\projectx-pcode.jar'
$libPcode = Join-Path $repoRoot 'lib\projectx-pcode.jar'
if (-not $SkipBuild) {
    if (-not (Test-Path $libPcode) -or (Get-FileHash $gamePcode).Hash -ne (Get-FileHash $libPcode).Hash) {
        Write-Host "[*] Copying projectx-pcode.jar from the game -> lib\" -ForegroundColor Yellow
        Copy-Item -Path $gamePcode -Destination $libPcode -Force
    }
}

if (-not $SkipBuild) {
    Write-Host "[*] Building KnightLauncherPlus (env=$envMode) using Maven Wrapper..." -ForegroundColor Yellow
    & $mvnw "-Denv=$envMode" '-DskipTests' clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }
}

$targetJar = Join-Path $repoRoot 'target\KnightLauncher.jar'
if (-not (Test-Path $targetJar)) {
    throw "Expected artifact '$targetJar' was not found."
}

# 2. Deploy to game directory
$destJar = Join-Path $targetSkDir 'KnightLauncher.jar'
if (Test-Path $destJar) {
    Write-Host "[*] Backing up the installed launcher -> $destJar.bak (close the launcher before deploying)" -ForegroundColor Yellow
    Copy-Item -Path $destJar -Destination "$destJar.bak" -Force
}
Write-Host "[*] Deploying KnightLauncher.jar -> $destJar..." -ForegroundColor Yellow
Copy-Item -Path $targetJar -Destination $destJar -Force

foreach ($script in @('KnightLauncher_windows.bat', 'KnightLauncher_mac_linux.sh')) {
    $srcScript = Join-Path $repoRoot "scripts\$script"
    $destScript = Join-Path $targetSkDir $script
    if (Test-Path $srcScript) {
        Copy-Item -Path $srcScript -Destination $destScript -Force
    }
}

Write-Host "[+] Deployment complete! Installed to: $destJar" -ForegroundColor Green

# 3. Optional Launch
if ($Run) {
    Write-Host "[*] Launching KnightLauncher..." -ForegroundColor Cyan
    Start-Process -FilePath 'cmd.exe' -ArgumentList "/c start java -jar KnightLauncher.jar" -WorkingDirectory $targetSkDir
}

