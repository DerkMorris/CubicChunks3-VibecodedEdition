param(
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$RunName = ('world-heights-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$reportPath = Join-Path $projectRoot ('build/reports/world-heights/' + $RunName)
$worldPath = Join-Path $projectRoot ('run/world-smoke/saves/' + $RunName)
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $worldPath)) { throw 'Use a fresh RunName; existing worlds are never replaced.' }
New-Item -ItemType Directory -Path $reportPath | Out-Null
$results = @()
Push-Location $projectRoot
try {
    foreach ($round in @('initial', 'reopen')) {
        $arguments = @('-PworldSmoke', '-PsmokeHeights', ('-PsmokeWorld=' + $RunName), 'runWorldSmoke', '--console=plain')
        if ($round -eq 'reopen') { $arguments += '-PsmokeReopen' }
        $logPath = Join-Path $reportPath ($round + '.log')
        & .\dev.cmd @arguments *> $logPath
        $exitCode = $LASTEXITCODE
        $log = Get-Content -LiteralPath $logPath -Raw
        $marker = 'CC_WORLD_HEIGHTS_COMPLETE reopen=' + ($round -eq 'reopen').ToString().ToLowerInvariant()
        $phases = [regex]::Matches($log, 'CC_WORLD_HEIGHTS_PHASE[^\r\n]+') | ForEach-Object { $_.Value }
        if ($exitCode -ne 0 -or $log -notmatch [regex]::Escape($marker) -or $log -match 'CC_WORLD_HEIGHTS_FAIL' -or $phases.Count -ne 5) {
            throw "World heights $round failed: $logPath"
        }
        $results += [ordered]@{ round = $round; exitCode = $exitCode; passed = $true; phases = @($phases) }
        Write-Output "CC_WORLD_HEIGHTS_ROUND_COMPLETE round=$round"
    }
    [ordered]@{ passed = $true; completed = (Get-Date -Format o); rounds = $results } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $reportPath 'result.json')
    Write-Output "CC_WORLD_HEIGHTS_VERIFIED fresh=true freshProcessReopen=true reports=$reportPath"
} finally {
    Pop-Location
}
