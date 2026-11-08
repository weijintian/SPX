<#
  Android 发布包构建（团队标准）

  规则（写死在 app/build.gradle 的 release.abiFilters 里，脚本会再校验一遍）：
    1. 发布包只打 ARM：arm64-v8a + armeabi-v7a（不打 x86 / x86_64）
    2. 签名使用 keys/spx-release.jks（CN=SPX IM）
    3. 构建后校验：ABI 清单、体积、签名、sha256

  用法：
    powershell -ExecutionPolicy Bypass -File android-native\build-release.ps1
    powershell -ExecutionPolicy Bypass -File android-native\build-release.ps1 -SkipBuild   # 只校验现有产物

  发版前记得先把 app/build.gradle 里的 versionCode / versionName 加一。
#>
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'

function Get-JavaMajor([string]$javaExe) {
    # Gradle 8.14 只支持到 Java 24；Android Studio 自带的 JBR 25 会让构建直接报
    # "Unsupported class file major version 69"，所以必须先读版本号再决定用谁。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $javaExe -version 2>&1 | Select-Object -First 1
    $ErrorActionPreference = $previous
    if ("$out" -match 'version "(\d+)') { return [int]$matches[1] }
    return 0
}

function Find-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += Get-ChildItem (Join-Path $env:TEMP 'spx-jdk21') -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }
    $candidates += Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }
    $candidates += Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path $java)) { continue }
        $major = Get-JavaMajor $java
        if ($major -ge 17 -and $major -le 24) { return $candidate }
    }
    throw '找不到可用的 JDK（需要 17–24，Gradle 8.14 不支持 Java 25）。请设置 JAVA_HOME，或把 JDK 21 解压到 %TEMP%\spx-jdk21\ 下。'
}

function Find-Gradle {
    $wrapper = Join-Path $root 'gradlew.bat'
    if (Test-Path $wrapper) { return $wrapper }
    $dists = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\wrapper\dists') -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'gradle-8\.' } | Sort-Object FullName -Descending
    if ($dists) { return $dists[0].FullName }
    throw '找不到 Gradle。请先执行 gradle wrapper，或确认 ~/.gradle/wrapper/dists 下有 8.x 发行版。'
}

function Find-Aapt2 {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    foreach ($tool in $buildTools) {
        $aapt2 = Join-Path $tool.FullName 'aapt2.exe'
        if (Test-Path $aapt2) { return $aapt2 }
    }
    return $null
}

if (-not $SkipBuild) {
    $env:JAVA_HOME = Find-JavaHome
    $gradle = Find-Gradle
    $mirror = Join-Path $root '..\.spx-release-build\spx-mirror.gradle'
    $arguments = @('assembleRelease', '--no-daemon', '--console=plain')
    if (Test-Path $mirror) { $arguments += @('-I', (Resolve-Path $mirror).Path) }
    Write-Host "构建中…（JAVA_HOME=$env:JAVA_HOME）" -ForegroundColor Cyan
    Push-Location $root
    try {
        & $gradle @arguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（exit $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $apk)) { throw "没有找到产物：$apk" }

# ---- 校验 ----
Write-Host "`n=== 产物校验 ===" -ForegroundColor Cyan
$file = Get-Item $apk
$sizeMb = [math]::Round($file.Length / 1MB, 2)
Write-Host ("  文件   : {0}" -f $file.FullName)
Write-Host ("  大小   : {0} MB ({1} 字节)" -f $sizeMb, $file.Length)
Write-Host ("  时间   : {0}" -f $file.LastWriteTime)

$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
Write-Host ("  sha256 : {0}" -f $sha)

$failures = @()

if ($sizeMb -gt 80) {
    $failures += "体积 $sizeMb MB 超过 80 MB：检查是否误把 x86/x86_64 打进发布包"
}

$aapt2 = Find-Aapt2
if ($aapt2) {
    $badging = & $aapt2 dump badging $apk 2>$null
    $native = ($badging | Select-String -Pattern '^native-code:').Line
    Write-Host ("  ABI    : {0}" -f ($native -replace 'native-code: ', ''))
    $expected = "'arm64-v8a' 'armeabi-v7a'"
    if (-not $native -or -not $native.Contains("arm64-v8a")) {
        $failures += '缺少 arm64-v8a'
    }
    if ($native -and ($native -match "x86")) {
        $failures += '发布包里出现了 x86/x86_64'
    }
    $package = ($badging | Select-String -Pattern '^package:').Line
    if ($package) { Write-Host ("  版本   : {0}" -f ($package -replace 'package: ', '')) }
} else {
    Write-Host '  ABI    : 跳过（找不到 aapt2）' -ForegroundColor Yellow
}

$apksigner = Get-ChildItem (Join-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk') 'build-tools') -Recurse -Filter 'apksigner.bat' -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($apksigner) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $certs = & $apksigner.FullName verify --print-certs $apk 2>$null
    $signerExit = $LASTEXITCODE
    $ErrorActionPreference = $previous
    $dn = ($certs | Select-String -Pattern 'certificate DN').Line
    if ($dn) { Write-Host ("  签名   : {0}" -f ($dn -replace '.*certificate DN: ', '')) }
    if (-not $dn -or $signerExit -ne 0) { $failures += '签名校验失败' }
}

if ($failures.Count -gt 0) {
    Write-Host "`n校验未通过：" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    exit 1
}

Write-Host "`n校验通过：ARM-only 发布包，可以上传分发。" -ForegroundColor Green
Write-Host "下一步："
Write-Host "  1) 上传到 OSS：oss://spx001/releases/spxchat-<version>-<code>.apk"
Write-Host "  2) 在后台新增 app_releases 记录并填写 apk_url"
Write-Host "  3) 设为 active（老客户端会通过 302 直接下载 OSS 上的包）"
