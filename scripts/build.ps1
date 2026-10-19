# Build all modules (skip tests - M0 acceptance is run-driven).
#   .\scripts\build.ps1
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

Push-Location $RepoRoot
try {
    mvn -B clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "maven build failed" }
    Write-Host ""
    Write-Host "[build] artifacts:"
    Get-ChildItem "$RepoRoot\adkernel-rta\target\*.jar", "$RepoRoot\media-simulator\target\*.jar" |
        Where-Object { $_.Name -notmatch "original" } |
        ForEach-Object { Write-Host ("[build]   " + $_.FullName) }
} finally {
    Pop-Location
}
