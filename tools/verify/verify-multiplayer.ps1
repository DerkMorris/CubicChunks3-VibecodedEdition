param(
    [string]$RunName = ('network-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$Port = 25569,
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($RunName -notmatch '^[A-Za-z0-9_-]+$') { throw 'RunName must contain only letters, digits, underscore or hyphen.' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Use an unprivileged TCP port.' }
$reportPath = Join-Path $projectRoot ('build/reports/multiplayer/' + $RunName)
if (Test-Path -LiteralPath $reportPath) { throw "Use a new RunName; reports already exist: $reportPath" }
New-Item -ItemType Directory -Path $reportPath -Force | Out-Null
$sharedPath = Join-Path $reportPath 'coordination'
New-Item -ItemType Directory -Path $sharedPath -Force | Out-Null
$buildLog = Join-Path $reportPath 'prepare.log'
Push-Location $projectRoot
$processes = @()
try {
    & .\dev.cmd -PworldSmoke :cubicchunks:fabric:writeWorldSmokeLaunch --console=plain *> $buildLog
    if ($LASTEXITCODE -ne 0) { throw "Multiplayer client preparation failed. Inspect $buildLog" }
    $launch = Get-Content -LiteralPath (Join-Path $projectRoot 'build/diagnostics/world-smoke-launch.json') -Raw | ConvertFrom-Json
    foreach ($role in @('host', 'guest')) {
        $clientDirectory = Join-Path $projectRoot ('run/multiplayer/' + $RunName + '/' + $role)
        New-Item -ItemType Directory -Path $clientDirectory -Force | Out-Null
        # Fresh game directories must reach TitleScreen without first-launch onboarding.
        $clientOptions = @(
            'onboardAccessibility:false'
            'skipMultiplayerWarning:true'
            'tutorialStep:none'
            'pauseOnLostFocus:false'
            'renderDistance:2'
            'simulationDistance:5'
            'maxFps:60'
            'enableVsync:false'
            'fullscreen:false'
        )
        [IO.File]::WriteAllLines((Join-Path $clientDirectory 'options.txt'), $clientOptions, [Text.UTF8Encoding]::new($false))
        $arguments = @($launch.jvmArgs | Where-Object { $_ -notmatch '^-Xm[sx]' -and $_ -notmatch '^-Dcubicchunks\.smoke\.world=' })
        $arguments += $(if ($role -eq 'host') { '-Xmx3G' } else { '-Xmx2G' })
        $arguments += @(
            '-Dcubicchunks.smoke.multiplayer=' + $role
            '-Dcubicchunks.smoke.networkDirectory=' + $sharedPath
            '-Dcubicchunks.smoke.networkPort=' + $Port
            '-Dcubicchunks.smoke.world=' + $RunName
            '-cp', $launch.classpath, $launch.mainClass
        )
        $programArguments = @($launch.args)
        for ($index = 0; $index -lt $programArguments.Count; $index++) {
            if ($programArguments[$index] -in @('--username', '--uuid', '--gameDir', '--width', '--height')) { $index++; continue }
            $arguments += $programArguments[$index]
        }
        $arguments += @('--username', $(if ($role -eq 'host') { 'CCProbeHost' } else { 'CCProbeGuest' }),
            '--uuid', $(if ($role -eq 'host') { '00000000-0000-4000-8000-000000000001' } else { '00000000-0000-4000-8000-000000000002' }),
            '--gameDir', $clientDirectory, '--width', '854', '--height', '480')
        # Java argument files avoid Windows' command-line length limit and shell interpolation.
        $argumentFile = Join-Path $reportPath ($role + '.args')
        $encoded = @($arguments | ForEach-Object { '"' + ([string]$_).Replace('\', '\\').Replace('"', '\"') + '"' })
        [IO.File]::WriteAllLines($argumentFile, $encoded, [Text.UTF8Encoding]::new($false))
        $outputLog = Join-Path $reportPath ($role + '.log')
        $errorLog = Join-Path $reportPath ($role + '.stderr.log')
        $process = Start-Process -FilePath $launch.java -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $clientDirectory `
            -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog -PassThru -WindowStyle Hidden
        $processes += [pscustomobject]@{ Role = $role; Process = $process; Log = $outputLog; ErrorLog = $errorLog }
    }
    $processes | ForEach-Object { [pscustomobject]@{ role = $_.Role; processId = $_.Process.Id; log = $_.Log } } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportPath 'processes.json')
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (@($processes | Where-Object { -not $_.Process.HasExited }).Count) {
        foreach ($entry in $processes) {
            if ($entry.Process.HasExited -and $entry.Process.ExitCode -ne 0) {
                throw "Multiplayer $($entry.Role) failed (exit $($entry.Process.ExitCode)). Inspect $($entry.Log) and $($entry.ErrorLog)"
            }
        }
        if ([DateTime]::UtcNow -gt $deadline) { throw "Multiplayer probe timed out. Reports: $reportPath" }
        Start-Sleep -Milliseconds 250
    }
    foreach ($entry in $processes) {
        $entry.Process.WaitForExit()
        $log = Get-Content -LiteralPath $entry.Log -Raw
        if ($entry.Process.ExitCode -ne 0 -or $log -notmatch ('CC_MULTIPLAYER_COMPLETE role=' + $entry.Role)) {
            throw "Missing successful multiplayer completion for $($entry.Role). Inspect $($entry.Log)"
        }
        if ($entry.Role -eq 'host' -and $log -notmatch 'CC_MULTIPLAYER_SERVER_COMPLETE') {
            throw "Missing server-side multiplayer completion. Inspect $($entry.Log)"
        }
    }
    Write-Output "CC_MULTIPLAYER_PROCESSES_COMPLETE clients=2 transport=TCP reports=$reportPath"
} finally {
    # Only processes created by this invocation are eligible for cleanup on failure/timeout.
    foreach ($entry in $processes) {
        if (-not $entry.Process.HasExited) { Stop-Process -InputObject $entry.Process -Force -ErrorAction SilentlyContinue }
    }
    Pop-Location
}
