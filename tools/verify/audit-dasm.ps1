param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string[]]$MinecraftJars,
    [string]$SourceRoot = (Join-Path $PSScriptRoot '../../mods/cubicchunks/common/src/main/java')
)
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$dependencies = Join-Path $PSScriptRoot '../../mods/dasm/fabric/build/verify-deps'
$classpath = @('asm-9.9.1.jar', 'asm-tree-9.9.1.jar', 'gson-2.13.2.jar') | ForEach-Object { Join-Path $dependencies $_ }
$classpath = $classpath -join ';'
& (Join-Path $JavaHome 'bin\javac.exe') -proc:none -cp $classpath -d $output (Join-Path $PSScriptRoot 'AuditDasm.java')
if ($LASTEXITCODE -ne 0) { throw 'Audit tool compilation failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp ($output + ';' + $classpath) AuditDasm $SourceRoot (Join-Path $output 'dasm-audit.json') @MinecraftJars
if ($LASTEXITCODE -ne 0) { throw 'DASM audit found missing vanilla members; see build/dasm-audit.json' }
