param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$RunName,
    [ValidateSet('above', 'below')][string]$Variant = 'below',
    [string]$LaunchJson = 'build/diagnostics/world-smoke-launch.json',
    [switch]$Profile,
    [switch]$Diagnostics
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$report = Join-Path $projectRoot ('build/reports/loading-speed/' + $RunName)
$world = Join-Path $projectRoot ('run/world-smoke/saves/' + $RunName)
if ((Test-Path -LiteralPath $report) -or (Test-Path -LiteralPath $world)) { throw 'RunName must be fresh.' }
New-Item -ItemType Directory -Path $report | Out-Null
$launch = Get-Content -LiteralPath (Join-Path $projectRoot $LaunchJson) -Raw | ConvertFrom-Json
$arguments = @($launch.jvmArgs) + @(
    ('-Dcubicchunks.smoke.latency=' + $Variant),
    ('-Dcubicchunks.smoke.world=' + $RunName),
    '-Dcubicchunks.smoke.reopen=false',
    '-Dcubicchunks.smoke.descent=false'
)
if ($Diagnostics) {
    $arguments += @('-Dcubicchunks.smoke.nativeTerrainDiagnostics=true', '-Dcubicchunks.smoke.lightDiagnostics=true')
}
if ($Profile) { $arguments += '-XX:StartFlightRecording=filename=' + (Join-Path $report 'runtime.jfr') + ',settings=profile,dumponexit=true,maxsize=256m' }
$arguments += @('-cp', $launch.classpath, $launch.mainClass) + @($launch.args)
$argFile = Join-Path $report 'launch.args'
$encoded = ($arguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' }) -join [Environment]::NewLine
[IO.File]::WriteAllText($argFile, $encoded, [Text.UTF8Encoding]::new($false))
$stdout = Join-Path $report 'stdout.log'
$stderr = Join-Path $report 'stderr.log'
$process = Start-Process -FilePath $launch.java -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory (Join-Path $projectRoot 'run/world-smoke') -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
@{ processId = $process.Id; variant = $Variant; launchJson = $LaunchJson; started = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $report 'process.json')
$deadline = [DateTime]::UtcNow.AddMinutes(7)
while (-not $process.HasExited) {
    if ([DateTime]::UtcNow -gt $deadline) {
        Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
        throw "Isolated latency probe exceeded seven minutes: $report"
    }
    Start-Sleep -Milliseconds 500
    $process.Refresh()
}
$process.WaitForExit()
$log = Get-Content -LiteralPath $stdout -Raw
$passed = $process.ExitCode -eq 0 -and $log.Contains('CC_LATENCY_COMPLETE variant=' + $Variant) -and -not $log.Contains('CC_LATENCY_FAIL')
@{ variant = $Variant; passed = $passed; exitCode = $process.ExitCode; completed = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $report 'result.json')
Select-String -LiteralPath $stdout -Pattern 'CC_LATENCY_BASELINE|CC_LATENCY_DEADLINE|CC_LATENCY_COMPLETE|CC_LATENCY_FAIL|CC_LATENCY_MILESTONE.*stage=(full|client|collision) percent=95' | ForEach-Object { $_.Line }
if (-not $passed) { throw "Latency probe failed its 30-second requirement: $report" }
