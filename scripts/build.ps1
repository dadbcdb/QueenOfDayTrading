$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME) {
    $bundledJdk = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
    if (Test-Path -LiteralPath $bundledJdk) { $env:JAVA_HOME = $bundledJdk }
}
if (-not (Test-Path -LiteralPath 'local.properties')) {
    $sdkPath = Join-Path $projectRoot '.tools/android-sdk'
    if (Test-Path -LiteralPath $sdkPath) { ('sdk.dir=' + $sdkPath.Replace('\','/')) | Set-Content -Encoding ascii local.properties }
}
& ./gradlew.bat testDebugUnitTest lintDebug assembleDebug
if ($LASTEXITCODE -ne 0) { throw '빌드 또는 검증 실패' }
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination release/stock-diary-debug.apk -Force
Write-Output '완료: release/stock-diary-debug.apk'
