# Run the feature service (foreground).
#   .\scripts\run-feature.ps1                # 500k profiles, port 9200
param(
    [int]$Port = 9200,
    [int]$HeapMB = 2048
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

$jar = Get-ChildItem "$RepoRoot\adkernel-feature\target\adkernel-feature-*.jar" |
    Where-Object { $_.Name -notmatch "original" } | Select-Object -First 1
if (-not $jar) { throw "feature jar not found - run .\scripts\build.ps1 first" }

Push-Location $RepoRoot
try {
    $jvmArgs = @("-Xms512m", "-Xmx${HeapMB}m", "-jar", $jar.FullName, "--server.port=$Port")
    Write-Host "[feature] starting (port=$Port)"
    & "$env:JAVA_HOME\bin\java.exe" @jvmArgs
} finally {
    Pop-Location
}
