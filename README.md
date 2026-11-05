# CubicChunks3 Vibecoded Edition

[English](README.md) | [Русский](README.ru.md)

An experimental fork of OpenCubicChunks/CubicChunks3 for Minecraft Java Edition 26.3.
Supports Fabric, Forge and NeoForge. Each loader has its own JAR, network registration and bootstrap; game logic is shared.

## Project structure

```text
mods/
  cubicchunks/
    mod.properties         # mod ID, version and group
    common/src/main/       # worlds, cubes, worldgen, lighting, entities, GUI, packets and mixins
    common/src/test/       # shared test scenarios
    fabric/src/main/       # entrypoints, network registration, Fabric services and metadata
    fabric/src/test/       # Fabric integration tests
    fabric/world-smoke/    # separate test mod for checks in the actual game client
    forge/src/main/        # Forge entrypoints, networking, services and metadata
    neoforge/src/main/     # NeoForge entrypoints, networking, services and metadata
    neoforge/src/test/     # native FML bootstrap and cube integration test
    loader-smoke/          # opt-in Forge/NeoForge real-client probe
    neoforge/legacy/       # archived adapters, excluded from the build
  dasm/
    mod.properties
    common/src/main/       # integration of DASM transformations with Mixin
    fabric/src/main/       # configuration discovery and bootstrap through Fabric
    fabric/src/test/       # bytecode and configuration tests
    forge/src/main/        # Forge DASM configuration discovery
    neoforge/src/main/     # NeoForge DASM configuration discovery
libraries/
  cubicchunks-core/        # vendored Core sources with fixes for 26.3
gradle/                     # shared build rules
buildSrc/                   # Mixin/DASM configuration generators and Java headers
tools/                      # additional verification tools
legacy/                     # historical sources excluded from the build
docs/port-history.md        # details of earlier porting milestones
```

Gradle includes `common`, `fabric`, `forge` and `neoforge` for both mods.
The `checkLoaderIndependence` check prevents loader API references in shared production code.

Shared Minecraft code is compiled separately against each loader's Minecraft distribution using Loom, ForgeGradle or ModDevGradle.
The `Platform` and `ClientPlatform` services are provided through Java ServiceLoader.
Client handlers are registered only by the client entrypoint.
Game code tests from both directories run through Fabric Loader in the `:cubicchunks:fabric:test` task.
NeoForge also runs `:cubicchunks:neoforge:test` through its native loader. `checkLoaderJar` validates metadata, services and bundled dependencies in each installable JAR.

## Building

JDK 25 and access to dependency repositories are required for the first build.
The Gradle Wrapper is included. No Git submodules are required to build the project.

| Windows command | Result |
| --- | --- |
| `.\dev.cmd listMods` | List mods, versions and configured loaders |
| `.\dev.cmd build` | Build and check all configured modules |
| `.\dev.cmd :cubicchunks:fabric:build` | Build CubicChunks for Fabric |
| `.\dev.cmd :cubicchunks:forge:build` | Build CubicChunks for Forge |
| `.\dev.cmd :cubicchunks:neoforge:build` | Build CubicChunks for NeoForge |
| `.\dev.cmd :dasm:fabric:build` | Build the DASM adapter for Fabric |
| `.\dev.cmd runClient` | Launch a separate development client |
| `.\dev.cmd :cubicchunks:fabric:runServer` | Launch a development server |
| `.\dev.cmd spotlessApply` | Format CubicChunks code |

On Linux/macOS, use `./gradlew` with the same tasks.
Built JARs for all mods are collected in the root `build/libs`; individual mod builds are in `mods/<id>/build/libs`.
Files named `*-sources.jar` contain source code.
Install exactly one `cubicchunks-<loader>-*.jar` matching the installed loader. DASM and Core are bundled; do not install the standalone DASM JAR alongside it.
Fabric additionally requires Fabric API for Minecraft 26.3. Forge and NeoForge supply their configuration libraries and need no Fabric dependencies.
The configured baseline versions are Fabric Loader 0.20.1, Forge 66.1.5 and NeoForge 26.3.0.101, on Minecraft 26.3 / JDK 25.
Forge's bundled Mixin requires Java 21 mod bytecode; its game process still requires JDK 25.
Use `:cubicchunks:forge:runClient` / `runServer` or `:cubicchunks:neoforge:runClient` / `runServer` for native development runs. Their files are isolated under `run/<loader>/<client|server>`.

Mod versions are defined in `mods/<id>/mod.properties`; tool and Minecraft versions are defined in the root `gradle.properties`.
The `cubicchunks` mod ID, save format and network packet IDs have been preserved.

## In-game checks

```powershell
.\dev.cmd -PworldSmoke -PsmokeDeepCaves -PsmokeCaveCurves -PsmokeOreProfiles -PsmokeWorld=layout-check runWorldSmoke
.\dev.cmd -PworldSmoke -PsmokeDeepCaves -PsmokeCaveCurves -PsmokeOreProfiles -PsmokeWorld=layout-check -PsmokeReopen runWorldSmoke
```

The first run creates a separate world through the game menus and checks world generation, ores and synchronization.
The second checks the saved world. Files are stored in `run/world-smoke`.
The test mod is excluded from distributed JARs.
Other scenarios are described in `tools/verify/README.md`.

For the Forge and NeoForge world-entry probes, run `:cubicchunks:forge:runClient` or `:cubicchunks:neoforge:runClient` with `-PloaderSmoke`.
The probe creates a fresh cubic world, checks rendered terrain and a server-to-client block update, prints `CC_LOADER_SMOKE_PASSED` and exits.
Rebuild without `-PloaderSmoke` before distributing; the artifact checks reject a JAR containing the probe.

## Credits and license

This project is based on [OpenCubicChunks/CubicChunks3](https://github.com/OpenCubicChunks/CubicChunks3).
Core is based on [OpenCubicChunks/CubicChunksCore](https://github.com/OpenCubicChunks/CubicChunksCore) and is vendored with the port's changes.
DASM uses NotStirred's 3.2.0 core and adapted dasm-mod sources. Attribution and license details are in `mods/dasm/NOTICE.txt`.
This project is licensed under MIT; see `LICENSE`.
