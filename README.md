# CubicChunks3 Vibecoded Edition

[English](README.md) | [Русский](README.ru.md)

An experimental fork of OpenCubicChunks/CubicChunks3 for Minecraft Java Edition 26.3.
Fabric is the currently supported platform. Code is organized by mod and loader, with shared components for future ports.

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
    forge/                 # reserved for a future port
    neoforge/legacy/       # original NeoForge adapters preserved for future porting
  dasm/
    mod.properties
    common/src/main/       # integration of DASM transformations with Mixin
    fabric/src/main/       # configuration discovery and bootstrap through Fabric
    fabric/src/test/       # bytecode and configuration tests
libraries/
  cubicchunks-core/        # vendored Core sources with fixes for 26.3
gradle/                     # shared build rules
buildSrc/                   # Mixin/DASM configuration generators and Java headers
tools/                      # additional verification tools
legacy/                     # historical sources excluded from the build
docs/port-history.md        # details of earlier porting milestones
```

Gradle currently includes `common` and `fabric`. The Forge and NeoForge directories describe future work.
To add a loader, provide its `build.gradle`, entrypoints, metadata and service implementations.
The `checkLoaderIndependence` check prevents loader API references in shared production code.

Shared Minecraft code is compiled independently. Loom provides Minecraft and access to its classes during the build.
The `Platform` and `ClientPlatform` services are provided through Java ServiceLoader.
Client handlers are registered only by the client entrypoint.
Game code tests from both directories run through Fabric Loader in the `:cubicchunks:fabric:test` task.

## Building

JDK 25 and access to dependency repositories are required for the first build.
The Gradle Wrapper is included. No Git submodules are required to build the project.

| Windows command | Result |
| --- | --- |
| `.\dev.cmd listMods` | List mods, versions and configured loaders |
| `.\dev.cmd build` | Build and check all configured modules |
| `.\dev.cmd :cubicchunks:fabric:build` | Build CubicChunks for Fabric |
| `.\dev.cmd :dasm:fabric:build` | Build the DASM adapter for Fabric |
| `.\dev.cmd runClient` | Launch a separate development client |
| `.\dev.cmd :cubicchunks:fabric:runServer` | Launch a development server |
| `.\dev.cmd spotlessApply` | Format CubicChunks code |

On Linux/macOS, use `./gradlew` with the same tasks.
Built JARs for all mods are collected in the root `build/libs`; individual mod builds are in `mods/<id>/build/libs`.
Files named `*-sources.jar` contain source code.
The main CubicChunks JAR includes the DASM adapter, Core and configuration libraries.
The game client requires Fabric API for Minecraft 26.3. A normal installation needs the main CubicChunks JAR and Fabric API.

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

## Credits and license

This project is based on [OpenCubicChunks/CubicChunks3](https://github.com/OpenCubicChunks/CubicChunks3).
Core is based on [OpenCubicChunks/CubicChunksCore](https://github.com/OpenCubicChunks/CubicChunksCore) and is vendored with the port's changes.
DASM uses NotStirred's 3.2.0 core and adapted dasm-mod sources. Attribution and license details are in `mods/dasm/NOTICE.txt`.
This project is licensed under MIT; see `LICENSE`.
