param(
    [string]$RunName = ('dedicated-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('network', 'survival', 'mechanics', 'events', 'persistence', 'all')][string]$Scenario = 'all',
    [int]$Port = 25570,
    [int]$TimeoutSeconds = 1500,
    [switch]$AcceptEula
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $AcceptEula) { throw 'Minecraft EULA must be accepted by the user before using -AcceptEula: https://aka.ms/MinecraftEULA' }
if ($RunName -notmatch '^[A-Za-z0-9_-]+$') { throw 'Use a simple fresh RunName.' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Use an unprivileged TCP port.' }
$reportPath = Join-Path $projectRoot ('build/reports/dedicated/' + $RunName)
$runPath = Join-Path $projectRoot ('run/dedicated/' + $RunName)
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $runPath)) { throw 'RunName must be fresh; no existing worlds or reports will be replaced.' }
$serverDirectory = Join-Path $runPath 'server'
$sharedPath = Join-Path $reportPath 'coordination'
New-Item -ItemType Directory -Path $serverDirectory, $sharedPath -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $serverDirectory 'eula.txt'), "# Explicitly accepted by the user for isolated test servers.`neula=true`n", [Text.UTF8Encoding]::new($false))
$properties = @(
    'server-ip=127.0.0.1', ('server-port=' + $Port), 'online-mode=false', 'enforce-secure-profile=false',
    'white-list=false', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=5', 'max-players=2',
    'gamemode=survival', 'difficulty=normal', 'allow-flight=true', 'pause-when-empty-seconds=0',
    'level-name=world', 'level-seed=246813579', 'generate-structures=true', 'enable-rcon=false',
    'enable-query=false', 'max-tick-time=180000', 'sync-chunk-writes=true'
)
[IO.File]::WriteAllLines((Join-Path $serverDirectory 'server.properties'), $properties, [Text.UTF8Encoding]::new($false))
$processes = @()
Push-Location $projectRoot
try {
    & .\dev.cmd -PworldSmoke :cubicchunks:fabric:writeWorldSmokeLaunch :cubicchunks:fabric:writeDedicatedSmokeLaunch --console=plain *> (Join-Path $reportPath 'prepare.log')
    if ($LASTEXITCODE -ne 0) { throw "Dedicated preparation failed: $reportPath/prepare.log" }
    $clientLaunch = Get-Content -LiteralPath (Join-Path $projectRoot 'build/diagnostics/world-smoke-launch.json') -Raw | ConvertFrom-Json
    $serverLaunch = Get-Content -LiteralPath (Join-Path $projectRoot 'build/diagnostics/dedicated-smoke-launch.json') -Raw | ConvertFrom-Json
    foreach ($round in @('initial', 'reopen')) {
        $processes = @()
        foreach ($role in @('server', 'one', 'two')) {
            $isServer = $role -eq 'server'
            $launch = if ($isServer) { $serverLaunch } else { $clientLaunch }
            $clientDirectory = if ($isServer) { $serverDirectory } else { Join-Path $runPath $role }
            New-Item -ItemType Directory -Path $clientDirectory -Force | Out-Null
            if (-not $isServer -and $round -eq 'initial') {
                [IO.File]::WriteAllLines((Join-Path $clientDirectory 'options.txt'), @(
                    'onboardAccessibility:false', 'skipMultiplayerWarning:true', 'tutorialStep:none',
                    'pauseOnLostFocus:false', 'renderDistance:2', 'simulationDistance:5', 'maxFps:45',
                    'enableVsync:false', 'fullscreen:false', 'autoJump:false'
                ), [Text.UTF8Encoding]::new($false))
            }
            $arguments = @($launch.jvmArgs | Where-Object { $_ -notmatch '^-Xm[sx]' })
            $arguments += $(if ($isServer) { '-Xmx3G' } else { '-Xmx2G' })
            $arguments += @(
                '-Dcubicchunks.smoke.networkDirectory=' + $sharedPath
                '-Dcubicchunks.smoke.networkPort=' + $Port
                '-Dcubicchunks.smoke.dedicatedScenario=' + $Scenario
                '-Dcubicchunks.smoke.dedicatedReopen=' + ($round -eq 'reopen').ToString().ToLowerInvariant()
            )
            $arguments += $(if ($isServer) { '-Dcubicchunks.smoke.dedicatedServer=true' } else { '-Dcubicchunks.smoke.dedicatedClient=' + $role })
            $arguments += @('-cp', $launch.classpath, $launch.mainClass)
            $programArguments = @($launch.args)
            for ($index = 0; $index -lt $programArguments.Count; $index++) {
                if (-not $isServer -and $programArguments[$index] -in @('--username', '--uuid', '--gameDir', '--width', '--height')) { $index++; continue }
                $arguments += $programArguments[$index]
            }
            if (-not $isServer) {
                $arguments += @('--username', $(if ($role -eq 'one') { 'CCSurvivalOne' } else { 'CCSurvivalTwo' }),
                    '--uuid', $(if ($role -eq 'one') { '00000000-0000-4000-8000-000000000011' } else { '00000000-0000-4000-8000-000000000012' }),
                    '--gameDir', $clientDirectory, '--width', '854', '--height', '480')
            }
            $prefix = Join-Path $reportPath ($round + '-' + $role)
            $argumentFile = $prefix + '.args'
            $encoded = @($arguments | ForEach-Object { '"' + ([string]$_).Replace('\', '\\').Replace('"', '\"') + '"' })
            [IO.File]::WriteAllLines($argumentFile, $encoded, [Text.UTF8Encoding]::new($false))
            $process = Start-Process -FilePath $launch.java -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $clientDirectory `
                -RedirectStandardOutput ($prefix + '.log') -RedirectStandardError ($prefix + '.stderr.log') -PassThru -WindowStyle Hidden
            $processes += [pscustomobject]@{ Role = $role; Process = $process; Log = ($prefix + '.log') }
        }
        $processes | ForEach-Object { [pscustomobject]@{ role = $_.Role; processId = $_.Process.Id; log = $_.Log } } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportPath ($round + '-processes.json'))
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while (@($processes | Where-Object { -not $_.Process.HasExited }).Count) {
            foreach ($entry in $processes) {
                if ($entry.Process.HasExited -and $entry.Process.ExitCode -ne 0) { throw "$round $($entry.Role) failed: $($entry.Log)" }
                if ($entry.Process.HasExited) {
                    $completedLog = Get-Content -LiteralPath $entry.Log -Raw
                    $completedMarker = if ($entry.Role -ne 'server') { 'CC_DEDICATED_CLIENT_COMPLETE role=' + $entry.Role }
                                       elseif ($round -eq 'initial') { 'CC_DEDICATED_COMPLETE restartReady=true' }
                                       else { 'CC_DEDICATED_REOPEN_COMPLETE' }
                    if ($completedLog -notmatch [regex]::Escape($completedMarker)) { throw "Incomplete $round $($entry.Role) even though its process exited: $($entry.Log)" }
                }
            }
            if ([DateTime]::UtcNow -gt $deadline) { throw "Dedicated probe timed out: $reportPath" }
            Start-Sleep -Milliseconds 250
        }
        foreach ($entry in $processes) {
            $entry.Process.WaitForExit()
            $log = Get-Content -LiteralPath $entry.Log -Raw
            $marker = if ($entry.Role -ne 'server') { 'CC_DEDICATED_CLIENT_COMPLETE role=' + $entry.Role + ' reopen=' + ($round -eq 'reopen').ToString().ToLowerInvariant() }
                      elseif ($round -eq 'initial') { 'CC_DEDICATED_COMPLETE restartReady=true' }
                      else { 'CC_DEDICATED_REOPEN_COMPLETE' }
            if ($entry.Process.ExitCode -ne 0 -or $log -notmatch [regex]::Escape($marker)) { throw "Missing successful $round $($entry.Role) completion: $($entry.Log)" }
        }
        Write-Output "CC_DEDICATED_ROUND_COMPLETE round=$round processes=3 scenario=$Scenario"
    }
    Write-Output "CC_DEDICATED_PROCESSES_COMPLETE clients=2 dedicated=true freshServerReopen=true scenario=$Scenario reports=$reportPath"
} finally {
    foreach ($entry in $processes) {
        if (-not $entry.Process.HasExited) { Stop-Process -InputObject $entry.Process -Force -ErrorAction SilentlyContinue }
    }
    Pop-Location
}
