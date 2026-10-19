# CompressorTach clean installation helper, Windows + Android Platform Tools (ADB).
# Only runs after user explicitly starts this script. Uninstall removes app data.
param(
    [string]$Apk = "",
    [switch]$Yes
)
$ErrorActionPreference = "Stop"
$packageName = "com.compwave.tach"

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    $paths = @()
    if ($env:ANDROID_HOME) { $paths += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $paths += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    if ($env:LOCALAPPDATA) { $paths += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    foreach ($path in $paths) { if (Test-Path -LiteralPath $path) { return $path } }
    throw "找不到 adb.exe。请安装 Android Platform Tools，并将 adb 添加到 PATH，或安装 Android Studio。"
}

function Find-Apk {
    if ($Apk) {
        if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "未找到指定 APK：$Apk" }
        return (Resolve-Path -LiteralPath $Apk).Path
    }
    $candidates = @(
        (Join-Path $PSScriptRoot 'app-debug.apk'),
        (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'),
        (Join-Path $PSScriptRoot 'CompressorTach-debug-apk\app-debug.apk')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw '未找到 app-debug.apk。请先在 GitHub Actions 下载并解压 APK，或运行 .\install-clean.ps1 -Apk "C:\path\app-debug.apk"。'
}

try {
    $apkPath = Find-Apk
    $adb = Find-Adb
    $devicesOutput = @(& $adb devices)
    $devices = @($devicesOutput | Where-Object { $_ -match '^\S+\s+device\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) {
        throw "请仅连接一台已授权 USB 调试的 Android 手机（当前找到 $($devices.Count) 台）。请先检查 adb devices。"
    }
    $serial = $devices[0]
    Write-Host "设备: $serial"
    Write-Host "APK:  $apkPath"

    $installedPaths = @(& $adb -s $serial shell pm path $packageName 2>$null)
    $alreadyInstalled = (($installedPaths -join "`n") -match 'package:')
    if ($alreadyInstalled) {
        Write-Warning "检测到旧版 $packageName。先卸载旧版会永久清除其应用私有数据、保存的设置等！"
        if (-not $Yes) {
            $answer = Read-Host '输入 UNINSTALL 确认清除旧版数据后安装新版（其它输入取消）'
            if ($answer -cne 'UNINSTALL') { throw '用户取消安装，未卸载任何应用。' }
        }
        $removeOutput = @(& $adb -s $serial uninstall $packageName)
        if ($LASTEXITCODE -ne 0 -or (($removeOutput -join "`n") -notmatch 'Success')) {
            throw "旧版卸载失败：$($removeOutput -join ' ')。已中止，不会继续安装。"
        }
        Write-Host '旧版已卸载。'
    } else {
        Write-Host '未检测到旧版应用，直接安装。'
    }

    $installOutput = @(& $adb -s $serial install $apkPath)
    if ($LASTEXITCODE -ne 0 -or (($installOutput -join "`n") -notmatch 'Success')) {
        throw "安装失败：$($installOutput -join ' ')"
    }
    Write-Host 'CompressorTach 新版安装成功。' -ForegroundColor Green
} catch {
    Write-Host "操作失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
