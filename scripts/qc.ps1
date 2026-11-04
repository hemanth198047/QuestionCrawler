# Launcher for the QuestionCrawler CLI (Windows PowerShell). Usage: scripts\qc.ps1 <command> [options]
# Looks for the built distribution (.\gradlew.bat :core:installDist) or $env:QUESTIONCRAWLER_HOME.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$home_dir = if ($env:QUESTIONCRAWLER_HOME) { $env:QUESTIONCRAWLER_HOME } else { Join-Path $root 'backend\core\build\install\questioncrawler' }
$bin = Join-Path $home_dir 'bin\questioncrawler.bat'
if (-not (Test-Path $bin)) {
    [Console]::Error.WriteLine("QuestionCrawler is not built yet: $bin not found.")
    [Console]::Error.WriteLine("Build it once:  cd `"$root\backend`"; .\gradlew.bat :core:installDist   (needs JDK 21)")
    exit 1
}
& $bin @args
exit $LASTEXITCODE
