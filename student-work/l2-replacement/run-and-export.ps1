# Runs the suite in a persistent container and writes the report to
# Desktop\Chipyard-UA\Output\run_MM_DD_HH_MM.txt through a bind mount.
#
# The container "l2suite" is kept between runs so the (slow) simulator builds
# are cached. Use -Reset to delete it and start from scratch.
#
# Examples:
#   .\run-and-export.ps1
#   .\run-and-export.ps1 -Policies L2RoundRobinDemoReplacementConfig -Benchmarks reuse
param(
    [string]$Baselines,
    [string]$Policies,
    [string]$Benchmarks,
    [switch]$Reset
)

$ErrorActionPreference = "Stop"
$name = "l2suite"
$repo = Resolve-Path "$PSScriptRoot\..\.."
$env:EXPORT_HOST_DIR = Join-Path $env:USERPROFILE "Desktop\Chipyard-UA\Output"
New-Item -ItemType Directory $env:EXPORT_HOST_DIR -Force | Out-Null

Push-Location $repo
try {
    if ($Reset) { docker rm -f $name 2>$null | Out-Null }

    $existing = docker ps -a --filter "name=^$name$" --format "{{.Status}}"
    if (-not $existing) {
        # The course entrypoint ignores its arguments, so bypass it and keep the container alive.
        docker compose -f compose.yaml -f "$PSScriptRoot\compose.export.yaml" run -d --name $name `
            --entrypoint sleep chipyard infinity | Out-Null
    } elseif ($existing -notmatch "^Up") {
        docker start $name | Out-Null
    }

    docker exec $name bash /workspace/course-scripts/install-course-configs.sh | Out-Null

    $envArgs = @()
    if ($Baselines)  { $envArgs += @("-e", "BASELINES=$Baselines") }
    if ($Policies)   { $envArgs += @("-e", "POLICIES=$Policies") }
    if ($Benchmarks) { $envArgs += @("-e", "BENCHMARKS=$Benchmarks") }

    docker exec @envArgs $name bash /workspace/student-work/l2-replacement/run-test-suite
} finally {
    Pop-Location
}

Get-ChildItem "$env:EXPORT_HOST_DIR\run_*.txt" | Sort-Object LastWriteTime |
    Select-Object -Last 1 | ForEach-Object { "Report: $($_.FullName)" }
