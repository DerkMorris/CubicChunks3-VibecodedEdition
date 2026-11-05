param([Parameter(Mandatory = $true)][string]$JavaHome)

$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$verifyRoot = Join-Path $moduleRoot ('build\verify-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))
$dependencyRoot = Join-Path $moduleRoot 'build\verify-deps'
$classes = Join-Path $verifyRoot 'classes'
New-Item -ItemType Directory -Path $dependencyRoot, $classes -Force | Out-Null
$artifacts = @(
    @('https://repo.maven.apache.org/maven2', 'io/github/notstirred/dasm/3.2.0/dasm-3.2.0.jar'),
    @('https://maven.fabricmc.net', 'net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar'),
    @('https://maven.fabricmc.net', 'net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/ow2/asm/asm/9.9.1/asm-9.9.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/ow2/asm/asm-commons/9.9.1/asm-commons-9.9.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/ow2/asm/asm-util/9.9.1/asm-util-9.9.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/ow2/asm/asm-analysis/9.9.1/asm-analysis-9.9.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/apache/logging/log4j/log4j-api/2.25.1/log4j-api-2.25.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/apache/logging/log4j/log4j-core/2.25.1/log4j-core-2.25.1.jar'),
    @('https://repo.maven.apache.org/maven2', 'com/google/guava/guava/31.1-jre/guava-31.1-jre.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar'),
    @('https://repo.maven.apache.org/maven2', 'org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar')
)
$jars = foreach ($artifact in $artifacts) {
    $file = Join-Path $dependencyRoot ($artifact[1].Split('/')[-1])
    if (-not (Test-Path -LiteralPath $file)) {
        Invoke-WebRequest -Uri ($artifact[0] + '/' + $artifact[1]) -OutFile $file -UseBasicParsing
    }
    $file
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'src\main\java'), (Join-Path $moduleRoot '../common/src/main/java'), (Join-Path $moduleRoot 'src\test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$classpath = $jars -join ';'
& (Join-Path $JavaHome 'bin\javac.exe') --release 25 -encoding UTF-8 -proc:none -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'DASM adapter compilation failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp ($classes + ';' + $classpath) org.junit.platform.console.ConsoleLauncher execute --scan-class-path=$classes --fail-if-no-tests --reports-dir (Join-Path $verifyRoot 'reports')
if ($LASTEXITCODE -ne 0) { throw 'DASM adapter tests failed' }
Write-Output ('Verification output: ' + $verifyRoot)
