# AdKernel build environment (dot-source from other scripts or your shell):
#   . .\scripts\env.ps1
# Pins JDK 21 + Maven 3.9.9 from the self-contained tools/ directory (git-ignored).

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $RepoRoot "tools\jdk-21"
$mvnHome = Join-Path $RepoRoot "tools\apache-maven-3.9.9"
$env:Path = "$env:JAVA_HOME\bin;$mvnHome\bin;" + $env:Path

Write-Host "[env] JAVA_HOME = $env:JAVA_HOME"
Write-Host "[env] mvn        = $mvnHome"
