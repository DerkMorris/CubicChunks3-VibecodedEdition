param([Parameter(Mandatory = $true)][string]$JavaHome)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$reportDirectory = Join-Path $projectRoot 'build/reports/port'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$logPath = Join-Path $reportDirectory 'server-init.log'
$previousJavaHome = $env:JAVA_HOME
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    & .\gradlew.bat :cubicchunks:fabric:runServer '--args=--initSettings' --console=plain *> $logPath
    $gradleExitCode = $LASTEXITCODE
    $log = Get-Content -LiteralPath $logPath -Raw
    # Minecraft catches some bootstrap errors and exits zero, so Gradle's status
    # alone does not prove the game initialized. Require the actual success marker.
    if ($gradleExitCode -ne 0 -or $log -match 'Failed to start the minecraft server|Mixin apply .*failed|Mixin prepare .*failed|DASM Failure' -or
        $log -notmatch "Initialized '.*server\.properties' and '.*eula\.txt'") {
        throw "Minecraft initialization failed. Inspect $logPath"
    }
    Write-Output "SERVER_INIT_OK: $logPath"
    Write-Output 'This checks bootstrap and configuration only. No world was created and the EULA was not accepted.'
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
