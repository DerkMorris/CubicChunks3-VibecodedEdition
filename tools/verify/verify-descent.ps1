param(
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$RunName = ('descent-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidatePattern('^[A-Za-z0-9_-]*$')]
    [string]$ReopenWorld = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$reportPath = Join-Path $projectRoot ('build/reports/descent/' + $RunName)
$worldName = if ($ReopenWorld) { $ReopenWorld } else { $RunName }
$worldPath = Join-Path $projectRoot ('run/world-smoke/saves/' + $worldName)
if (Test-Path -LiteralPath $reportPath) { throw 'RunName must name a fresh report directory.' }
if ($ReopenWorld) {
    if (-not (Test-Path -LiteralPath $worldPath)) { throw 'The isolated world to reopen does not exist.' }
} elseif (Test-Path -LiteralPath $worldPath) {
    throw 'RunName must name a fresh world; existing saves will not be replaced.'
}
New-Item -ItemType Directory -Path $reportPath | Out-Null
Push-Location $projectRoot
try {
    $rounds = if ($ReopenWorld) { @('reopen') } else { @('initial', 'reopen') }
    foreach ($round in $rounds) {
        $arguments = @('-PworldSmoke', '-PsmokeDescent', ('-PsmokeWorld=' + $worldName), 'runWorldSmoke', '--console=plain')
        if ($round -eq 'reopen') { $arguments += '-PsmokeReopen' }
        $logPath = Join-Path $reportPath ($round + '.log')
        & .\dev.cmd @arguments *> $logPath
        $exitCode = $LASTEXITCODE
        $log = Get-Content -LiteralPath $logPath -Raw
        $marker = 'CC_DESCENT_COMPLETE reopen=' + ($round -eq 'reopen').ToString().ToLowerInvariant() + ' failures=0'
        if ($exitCode -ne 0 -or $log -notmatch [regex]::Escape($marker) -or $log -match 'CC_DESCENT_FAIL') {
            throw "Descent $round failed or did not complete: $logPath"
        }
        if ($log -notmatch 'CC_DESCENT_ANCHORED') { throw "Missing acknowledged starting position: $logPath" }
        foreach ($height in @(-62, -160, -256)) {
            $near = 'CC_DESCENT_NEAR_CHECKED reopen=' + ($round -eq 'reopen').ToString().ToLowerInvariant() + ' y=' + $height + ' failures=0 radiusBlocks=32 holdSeconds=5'
            if ($log -notmatch [regex]::Escape($near)) { throw "Missing five-second nearby check at Y=$height in $logPath" }
            $hold = 'CC_DESCENT_HOLD_COMPLETE reopen=' + ($round -eq 'reopen').ToString().ToLowerInvariant() + ' y=' + $height + ' failures=0 radiusBlocks=192 holdSeconds=30'
            if ($log -notmatch [regex]::Escape($hold)) { throw "Missing hold at Y=$height in $logPath" }
        }
        foreach ($check in @(@{ Name = 'NEAR_CHECKED'; Minimum = 5000 }, @{ Name = 'HOLD_COMPLETE'; Minimum = 30000 })) {
            $elapsed = [regex]::Matches($log, ('CC_DESCENT_' + $check.Name + '[^\r\n]*elapsedMilliseconds=(\d+)'))
            if ($elapsed.Count -ne 3) { throw "Missing measured $($check.Name) durations in $logPath" }
            foreach ($sample in $elapsed) {
                if ([long]$sample.Groups[1].Value -lt $check.Minimum) { throw "Premature $($check.Name) in $logPath" }
            }
        }
        Write-Output "CC_DESCENT_ROUND_COMPLETE round=$round reports=$reportPath"
    }
    $freshWorld = (-not [bool]$ReopenWorld).ToString().ToLowerInvariant()
    Write-Output "CC_DESCENT_VERIFIED freshWorld=$freshWorld freshProcessReopen=true productionTracking=true nearSeconds=5 farSeconds=30 reports=$reportPath"
} finally {
    Pop-Location
}
