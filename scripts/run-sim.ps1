# Run the media simulator.
#   .\scripts\run-sim.ps1 -Profile smoke          # 5 min @ 3000 qps
#   .\scripts\run-sim.ps1 -Profile acceptance     # 3 rounds, 10k + 15k spike
#   .\scripts\run-sim.ps1 -Command authcheck     # signature contract probes (k04)
#   .\scripts\run-sim.ps1 -Command recon -Events <file> -Bills <dir>
param(
    [string]$Command = "run",
    [string]$Profile = "smoke",
    [string]$Events = "",
    [string]$Bills = "",
    [int]$HeapMB = 2048
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

$jar = "$RepoRoot\media-simulator\target\media-simulator.jar"
if (-not (Test-Path $jar)) { throw "simulator jar not found - run .\scripts\build.ps1 first" }

Push-Location $RepoRoot
try {
    $simArgs = @("-Xms256m", "-Xmx${HeapMB}m", "-jar", $jar)
    switch ($Command) {
        "run"       { $simArgs += @("run", $Profile) }
        "authcheck" { $simArgs += "authcheck" }
        "recon"     { $simArgs += @("recon", "--events", $Events, "--bills", $Bills) }
        default     { throw "unknown command: $Command" }
    }
    $simArgs += @("--config", "$RepoRoot\config")
    & "$env:JAVA_HOME\bin\java.exe" @simArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
