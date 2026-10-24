# Runs the suite in the container; the report is written directly to
# Desktop\Chipyard-UA\Output\run_MM_DD_HH_MM.txt through a bind mount.
$repo = Resolve-Path "$PSScriptRoot\..\.."
$env:EXPORT_HOST_DIR = Join-Path $env:USERPROFILE "Desktop\Chipyard-UA\Output"
New-Item -ItemType Directory $env:EXPORT_HOST_DIR -Force | Out-Null
Push-Location $repo
docker compose -f compose.yaml -f "$PSScriptRoot\compose.export.yaml" run --rm chipyard `
    bash /workspace/student-work/l2-replacement/run-test-suite
Pop-Location
Get-ChildItem "$env:EXPORT_HOST_DIR\run_*.txt" | Sort-Object LastWriteTime |
    Select-Object -Last 1 | ForEach-Object { "Report: $($_.FullName)" }
