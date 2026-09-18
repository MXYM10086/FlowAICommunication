$ErrorActionPreference = 'Stop'
$candidates = @(
    $env:JAVA_HOME,
    'E:\code_app\IntelliJ IDEA 2024.2.4\jbr',
    "$env:ProgramFiles\Android\Android Studio\jbr"
)
$selectedJdk = $null
foreach ($candidate in $candidates) {
    if (!$candidate) { continue }
    $javaExe = Join-Path $candidate 'bin\java.exe'
    $releaseFile = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $javaExe) -and (Test-Path -LiteralPath $releaseFile)) {
        $versionText = Get-Content -LiteralPath $releaseFile -Raw
        if ($versionText -match 'JAVA_VERSION="(17|21)\.') { $selectedJdk = $candidate; break }
    }
}
if (!$selectedJdk) { throw 'Set JAVA_HOME to a JDK 17 or 21 installation and rerun.' }
$oldJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $selectedJdk
    & "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { $env:JAVA_HOME = $oldJavaHome }
