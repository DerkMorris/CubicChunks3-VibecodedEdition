param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MinecraftJar,
    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$auditRoot = Join-Path $PSScriptRoot 'build/body-audit'
$dependencyRoot = Join-Path $projectRoot 'mods/dasm/fabric/build/verify-deps'
if (!$GradleUserHome) {
    $GradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
}
$moduleCache = Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
$gameManifest = Join-Path (Split-Path $MinecraftJar -Parent) 'mojang_minecraft_info.json'
$gameLibraries = @()
if (Test-Path $gameManifest) {
    foreach ($library in (Get-Content -LiteralPath $gameManifest -Raw | ConvertFrom-Json).libraries) {
        $coordinate = $library.name.Split(':')
        if ($coordinate.Length -ne 3) { continue }
        $libraryCache = Join-Path $moduleCache ($coordinate -join '/')
        if (Test-Path $libraryCache) {
            $gameLibraries += Get-ChildItem $libraryCache -Recurse -Filter ($coordinate[1] + '-' + $coordinate[2] + '.jar') |
                Select-Object -First 1 -ExpandProperty FullName
        }
    }
}
$modJar = Get-ChildItem (Join-Path $projectRoot 'build/libs') -Filter 'cubicchunks-fabric-*.jar' |
    Where-Object Name -NotLike '*-sources.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$modJar) { throw 'Build the main jar first: ./gradlew.bat build' }
if (!(Test-Path $dependencyRoot)) { throw 'Run mods/dasm/fabric/verify.ps1 first to prepare audit dependencies.' }
New-Item -ItemType Directory -Path $auditRoot -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($modJar.FullName)
try {
    $coreEntry = $archive.Entries | Where-Object FullName -Match '^META-INF/jars/CubicChunksCore.*\.jar$' | Select-Object -First 1
    if (!$coreEntry) { throw 'The packaged mod is missing its linked CubicChunksCore jar.' }
    $coreJar = Join-Path $auditRoot 'core-linked.jar'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($coreEntry, $coreJar, $true)
} finally { $archive.Dispose() }

$classpath = (@(
    (Join-Path $projectRoot 'mods/dasm/fabric/build/classes/java/main'),
    (Join-Path $projectRoot 'mods/cubicchunks/fabric/build/classes/java/main'),
    $coreJar,
    $MinecraftJar
) + @((Get-ChildItem (Join-Path $dependencyRoot '*.jar')).FullName) + $gameLibraries) -join ';'
& (Join-Path $JavaHome 'bin/javac.exe') -proc:none -cp $classpath -d $auditRoot (Join-Path $PSScriptRoot 'AuditDasmBodies.java')
if ($LASTEXITCODE -ne 0) { throw "Audit compilation failed: $LASTEXITCODE" }
& (Join-Path $JavaHome 'bin/java.exe') -cp "$auditRoot;$classpath" io.github.notstirred.dasm.mod.AuditDasmBodies `
    (Join-Path $projectRoot 'build/resources/main/cubicchunks.dasm.json') (Join-Path $auditRoot 'emitted') (Join-Path $projectRoot 'mods/cubicchunks/fabric/build/classes/java/main') |
    Tee-Object -FilePath (Join-Path $auditRoot 'report.txt')
if ($LASTEXITCODE -ne 0) { throw "Generated DASM bodies contain unresolved members; see $auditRoot/report.txt" }
