param([Parameter(Mandatory = $true)][string]$JavaHome)

$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$smokeRoot = Join-Path $moduleRoot ('build\knot-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))
$adapter = Join-Path $smokeRoot 'adapter'
$fixture = Join-Path $smokeRoot 'fixture'
$launcher = Join-Path $smokeRoot 'launcher'
New-Item -ItemType Directory -Path $adapter, $fixture, $launcher -Force | Out-Null
$jars = @(Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'build\verify-deps') -Filter '*.jar' | Where-Object Name -NotMatch 'sources|junit' | ForEach-Object FullName)
if ($jars.Count -lt 10) { throw 'Run verify.ps1 first to obtain dependencies.' }
$classpath = $jars -join ';'
$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'src\main\java'), (Join-Path $moduleRoot '../common/src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') --release 25 -encoding UTF-8 -proc:none -cp $classpath -d $adapter @mainSources
if ($LASTEXITCODE -ne 0) { throw 'Adapter compilation failed' }
Copy-Item -Path (Join-Path $moduleRoot 'src\main\resources\*') -Destination $adapter -Recurse
$metadataFile = Join-Path $adapter 'fabric.mod.json'
[IO.File]::WriteAllText($metadataFile, [IO.File]::ReadAllText($metadataFile).Replace('${version}', '3.2.0+fabric.1'))
$fixtureSources = @(Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'src\smoke\java\smoke\game') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') --release 25 -encoding UTF-8 -proc:none -cp $classpath -d $fixture @fixtureSources
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
Copy-Item -Path (Join-Path $moduleRoot 'src\smoke\resources\*') -Destination $fixture -Recurse
& (Join-Path $JavaHome 'bin\javac.exe') --release 25 -encoding UTF-8 -proc:none -cp $classpath -d $launcher (Join-Path $moduleRoot 'src\smoke\java\smoke\launch\SmokeGameProvider.java')
if ($LASTEXITCODE -ne 0) { throw 'Smoke launcher compilation failed' }
$services = Join-Path $launcher 'META-INF\services'
New-Item -ItemType Directory -Path $services -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $services 'net.fabricmc.loader.impl.game.GameProvider'), 'smoke.launch.SmokeGameProvider')
$runtimeClasspath = (@($launcher, $adapter, $fixture) + $jars) -join ';'
Push-Location $smokeRoot
try {
    & (Join-Path $JavaHome 'bin\java.exe') '-Dfabric.development=true' '-Dfabric.skipMcProvider=true' '-Dfabric.debug.throwDirectly=true' '-Dfabric.noGui=true' -cp $runtimeClasspath net.fabricmc.loader.impl.launch.knot.KnotServer
    if ($LASTEXITCODE -ne 0) { throw 'Fabric Knot smoke test failed' }
} finally {
    Pop-Location
}
Write-Output ('Smoke test output: ' + $smokeRoot)
