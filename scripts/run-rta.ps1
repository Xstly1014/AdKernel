# Run the RTA echo server (foreground).
#   .\scripts\run-rta.ps1                 # normal mode
#   .\scripts\run-rta.ps1 -Fault          # fault injection: skip blacklist check (k07 demo)
param(
    [switch]$Fault,
    [int]$Port = 9100
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

$jar = Get-ChildItem "$RepoRoot\adkernel-rta\target\adkernel-rta-*.jar" |
    Where-Object { $_.Name -notmatch "original" } | Select-Object -First 1
if (-not $jar) { throw "rta jar not found - run .\scripts\build.ps1 first" }

Push-Location $RepoRoot
try {
    $jvmArgs = @("-Xms512m", "-Xmx1536m", "-jar", $jar.FullName, "--server.port=$Port")
    if ($Fault) { $jvmArgs += "--rta.fault-blacklist-bypass=true" }
    Write-Host "[rta] starting (fault=$Fault, port=$Port)"
    & "$env:JAVA_HOME\bin\java.exe" @jvmArgs
} finally {
    Pop-Location
}
