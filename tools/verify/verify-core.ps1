param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME,
    [string]$ModJar,
    [string]$CoreJar,
    [string]$MinecraftJar,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$GradleUserHome) { $GradleUserHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
$GradleUserHome = [IO.Path]::GetFullPath($GradleUserHome)
$cache = Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
$properties = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') -Raw
$minecraftVersion = [regex]::Match($properties, '(?m)^minecraft_version\s*=\s*(.+)$').Groups[1].Value.Trim()
$modProperties = Get-Content -LiteralPath (Join-Path $projectRoot 'mods/cubicchunks/mod.properties') -Raw
$modVersion = [regex]::Match($modProperties, '(?m)^version\s*=\s*(.+)$').Groups[1].Value.Trim()
if (!$minecraftVersion -or !$modVersion) { throw 'Declare minecraft_version in gradle.properties and version in mods/cubicchunks/mod.properties.' }
if (!$ModJar) { $ModJar = Join-Path $projectRoot "build/libs/cubicchunks-fabric-$modVersion.jar" }
if (!$CoreJar) { $CoreJar = Join-Path $projectRoot "libraries/cubicchunks-core/build/libs/CubicChunksCore-$modVersion.jar" }
if (!$MinecraftJar) { $MinecraftJar = Join-Path $GradleUserHome "caches/fabric-loom/$minecraftVersion/minecraft-merged.jar" }
$gameInfoPath = Join-Path $GradleUserHome "caches/fabric-loom/$minecraftVersion/mojang_minecraft_info.json"
foreach ($path in @($ModJar, $CoreJar, $MinecraftJar, $gameInfoPath)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing $path. Build the mod with ./gradlew build before running this verifier."
    }
}
$ModJar = (Resolve-Path -LiteralPath $ModJar).Path
$CoreJar = (Resolve-Path -LiteralPath $CoreJar).Path
$MinecraftJar = (Resolve-Path -LiteralPath $MinecraftJar).Path
$verifyRoot = Join-Path $PSScriptRoot ('build/core-headers-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$testClasses = Join-Path $verifyRoot 'test-classes'
$linkerClasses = Join-Path $verifyRoot 'linker-classes'
$dependencyRoot = Join-Path $PSScriptRoot 'build/dependencies'
New-Item -ItemType Directory -Path $testClasses, $linkerClasses, $dependencyRoot -Force | Out-Null

function Find-CachedArtifact([string]$Group, [string]$Artifact, [string]$Version) {
    $directory = Join-Path $cache "$Group/$Artifact/$Version"
    if (Test-Path -LiteralPath $directory) {
        $file = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter "$Artifact-$Version.jar" |
            Sort-Object FullName | Select-Object -First 1
        if ($file) { return $file.FullName }
    }
    throw "Missing cached dependency ${Group}:${Artifact}:${Version}. Run ./gradlew jar with this Gradle user home first."
}

function Invoke-JdkTool([string]$Tool, [string[]]$Arguments, [string]$Name) {
    $extension = if ([IO.Path]::DirectorySeparatorChar -eq '\') { '.exe' } else { '' }
    $executable = Join-Path $JavaHome "bin/$Tool$extension"
    if (!(Test-Path -LiteralPath $executable -PathType Leaf)) { throw "JDK executable not found: $executable" }
    $argumentFile = Join-Path $verifyRoot "$Name.args"
    # Java argument files avoid shell quoting and Windows command-length limits, including paths with spaces.
    $escaped = @($Arguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' })
    [IO.File]::WriteAllLines($argumentFile, $escaped, [Text.UTF8Encoding]::new($false))
    & $executable ('@' + $argumentFile)
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE. See $verifyRoot." }
}

$consoleName = 'junit-platform-console-standalone-1.13.4.jar'
$console = Join-Path $dependencyRoot $consoleName
if (!(Test-Path -LiteralPath $console)) {
    $existingConsole = Join-Path $projectRoot "mods/dasm/fabric/build/verify-deps/$consoleName"
    if (Test-Path -LiteralPath $existingConsole) {
        Copy-Item -LiteralPath $existingConsole -Destination $console
    } elseif ($Offline) {
        throw "Offline mode: missing $console. Run once without -Offline to cache the pinned JUnit console."
    } else {
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/$consoleName" -OutFile $console -UseBasicParsing
    }
}

$gameInfo = Get-Content -LiteralPath $gameInfoPath -Raw | ConvertFrom-Json
$gameLibraries = @($gameInfo.libraries | ForEach-Object {
    $coordinate = $_.name.Split(':')
    # These tests do not load native graphics or sound libraries.
    if ($coordinate.Count -eq 3) { Find-CachedArtifact $coordinate[0] $coordinate[1] $coordinate[2] }
})
$linkerDependencies = @(
    (Find-CachedArtifact 'io.github.opencubicchunks' 'javaheaders' '1.2.8'),
    (Find-CachedArtifact 'com.github.OpenCubicChunks' 'dasm' '9709b98'),
    (Find-CachedArtifact 'com.github.OpenCubicChunks' 'javaheaders-api' '3e193a8')
)
foreach ($artifact in @('asm', 'asm-tree', 'asm-commons', 'asm-util', 'asm-analysis')) {
    # Loom 1.18.2 resolves all five modules during the normal jar build.
    $linkerDependencies += Find-CachedArtifact 'org.ow2.asm' $artifact '9.10.1'
}

# JavaHeaders also loads its Gradle plugin and logger. Use the checked-in wrapper's runtime libraries.
$wrapper = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw
if ($wrapper -notmatch 'gradle-([0-9.]+)-(?:bin|all)\.zip') { throw 'Cannot determine the Gradle wrapper version.' }
$gradleVersion = $Matches[1]
$distributionRoot = Join-Path $GradleUserHome 'wrapper/dists'
$gradleLib = Get-ChildItem -LiteralPath $distributionRoot -Recurse -Directory -Filter "gradle-$gradleVersion" |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'lib') } |
    Sort-Object FullName | Select-Object -First 1
if (!$gradleLib) { throw "Gradle $gradleVersion wrapper distribution is absent. Run ./gradlew jar first." }
$gradleApi = @((Get-ChildItem -LiteralPath (Join-Path $gradleLib.FullName 'lib') -File -Filter 'gradle-*.jar').FullName)
$separator = [IO.Path]::PathSeparator
$linkerClasspath = ($linkerDependencies + $gameLibraries + $gradleApi) -join $separator
$testClasspath = @($CoreJar, $console) -join $separator
$sources = @(
    (Join-Path $projectRoot 'libraries/cubicchunks-core/src/test/java/io/github/opencubicchunks/cc_core/world/level/CloPosTest.java'),
    (Join-Path $projectRoot 'libraries/cubicchunks-core/src/test/java/io/github/opencubicchunks/cc_core/minecraft/MinecraftHeaderLinkageTest.java')
)
Invoke-JdkTool 'javac' (@('--release', '25', '-encoding', 'UTF-8', '-proc:none', '-cp', $testClasspath, '-d', $testClasses) + $sources) 'compile-tests'
$testInput = Join-Path $verifyRoot 'tests-input.jar'
Invoke-JdkTool 'jar' @('--create', '--file', $testInput, '-C', $testClasses, '.') 'package-tests'
Invoke-JdkTool 'javac' @('--release', '25', '-encoding', 'UTF-8', '-proc:none', '-cp', $linkerClasspath, '-d', $linkerClasses, (Join-Path $PSScriptRoot 'LinkHeaders.java')) 'compile-linker'
$linkedTests = Join-Path $verifyRoot 'tests-linked.jar'
Invoke-JdkTool 'java' @('-cp', ($linkerClasses + $separator + $linkerClasspath), 'LinkHeaders', (Join-Path $projectRoot 'mods/cubicchunks/javaHeaders.json'), $CoreJar, $testInput, $linkedTests) 'link-tests'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($ModJar)
try {
    $entries = @($archive.Entries | Where-Object FullName -Match '^META-INF/jars/CubicChunksCore[^/]*\.jar$')
    if ($entries.Count -ne 1) { throw 'Expected exactly one linked CubicChunksCore jar in the packaged mod.' }
    $linkedCore = Join-Path $verifyRoot 'packaged-core.jar'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], $linkedCore, $false)
} finally { $archive.Dispose() }

$runtimeClasspath = (@($linkedTests, $linkedCore, $MinecraftJar, $console) + $gameLibraries) -join $separator
Invoke-JdkTool 'java' @('-ea', '-cp', $runtimeClasspath, 'org.junit.platform.console.ConsoleLauncher', 'execute',
    '--select-class', 'io.github.opencubicchunks.cc_core.world.level.CloPosTest',
    '--select-class', 'io.github.opencubicchunks.cc_core.minecraft.MinecraftHeaderLinkageTest',
    '--fail-if-no-tests', '--reports-dir', (Join-Path $verifyRoot 'reports')) 'run-tests'
Write-Output "Core header verification passed. Reports: $verifyRoot/reports"
