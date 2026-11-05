# Cubic Chunks 3

## Experimental Fabric 26.3 port — cubic biome generation

This branch ports the unfinished NeoForge/MC 1.21.6 rewrite to Minecraft Java 26.3 and Fabric. A new cubic Overworld can now be generated, entered, saved, and reopened, with automatic sky and block lighting around players. New cubes use the world preset and seed for biome distribution, terrain, aquifers, surface materials, caves, ores and biome vegetation. Vanilla generates in its normal vertical range. Cubes below it continue as stone and cubes above it as air. In a noise-generated Overworld, deepslate replaces the bottom bedrock layers to keep the deep cubes accessible. Structure starts and references, fluid/block ticking and extreme-height rendering still need work.

For a **new test world**, enable Cubic Chunks in the creation screen, or set `general.generateNewWorldsAsCC=true` in `config/cubicchunks_common.toml` before launching. Creative/Peaceful is the tested setup. Previously saved cubes keep their terrain. Borders between old hills and newly generated areas can be visible. Nether and End remain ordinary dimensions. The world's cubic mode is saved in `level.dat` and sent to clients before entry, so later changes to this preference do not change existing saves. Unmarked existing worlds remain ordinary; there is no conversion of old worlds.

A real Minecraft client and integrated server passed creation, terrain rendering readiness, a server block update received by the client, clean shutdown, and reopening the saved gold block with the new-world preference turned off. The test used no `IS_IN_TEST` bypass. A later storage optimization reduced the measured fresh-world saving phase from about 25 to 12 seconds on this machine; cube writes now use bounded asynchronous queues. Autosave and normal unload do not wait for disk acknowledgement; explicit flush and shutdown still drain pending writes. Snapshot capture remains on the server thread. Initial cube transmission now waits for exact light, which the client installs before its first render.

With the terrain writer deliberately blocked for 1.5 seconds in a loaded test world, autosave returned in 21 ms and the integrated server advanced 30 ticks. A block changed during the pending write survived a fresh-process reopen. This checks asynchronous saving; initial loading/generation and explicit flush/exit can still pause.

Natural biome spawning now selects positions inside loaded ENTITY_TICKING cubes at full Y. It keeps native placement, light, collision, group, and mob rules, uses 3D player distances, and shares category caps across nearby vertical layers. Unknown lighting or terrain defers an attempt. Spawning is limited to areas whose automatic lighting publication is ready. Structure-specific lists now resolve loaded cube references and full 3D structure/piece bounds, including the fortress nether-brick rule. Missing referenced starts defer the attempt. General predicates requiring a global column surface remain unfinished.

Phantoms, patrols, cats, and wandering traders with llamas now use cubic spawning paths. Native cooldowns and game rules remain; trader chance/delay still use native saved data. Patrols and traders search for a nearby loaded floor within 32 blocks vertically instead of a column heightmap. POI records and occupied tickets now persist per cube at full Y. Cats, patrols, and traders use the same POI manager as villagers instead of the earlier block/memory observations.

Beds, workstations, and bells now use cube-backed POI queries, occupation/release, block reconciliation, and village-section distances. Native villager behaviors acquire homes and meeting points, assign professions, and release claims at extended heights; retry caches no longer alias packed block Y. POI files are stored separately in `cube_poi`, with full coordinates and native ticket counts. Component tests cover Y near +/-32,000,000 and cache save/unload/reload. Complete village gameplay, debug networking, and large-world performance are not yet validated.

Villager sleep/wake, workstation restocking, farmer composting, and meetings now have native-behavior tests at both height signs. Sleeping-position metadata, bell block events, and composter effects preserve full coordinates on the wire. Block events and block-entity tickers check the exact cube and its loaded entities. Client tests apply real sleeping metadata and ring a loaded bell; visual multiplayer and complete day/night village simulation remain unverified.

Villager iron-golem summons now search loaded cubes at full Y and require loaded terrain and entity data across the golem's whole bounding box. Native sleep, nearby-villager counts, panic/gossip timing, floor rules, and nearby-golem sensing remain in use. A rejected insertion does not start the villagers' golem cooldown. Tests at both signs of roughly 32 million Y cover repeat summons, cube boundaries, persistence, and player-built iron-golem patterns. These are component checks; complete iron-farm gameplay remains unverified.

Server-side mobs enter Minecraft's native tick list when their cube reaches ENTITY_TICKING. Status changes, movement, and removal stop ticking as needed; the server's distance check uses full cube coordinates. The normal goal, navigation, movement-control, freeze, passenger, and NoAI paths remain in use. Pathfinding, block collisions, and fluid interaction read loaded cubes at full height, and negative Y no longer triggers the ordinary world's void limit. Routes are local, capped at 96 blocks; missing terrain is treated as an obstacle. Tests exercise moving mobs and ground/flying/swimming paths at Y = ±32,000,000. Block ticks and specialized behaviors such as teleport searches still require work.

Client entities now enter the native tick list when their exact cube is resident. This fixes stationary mob models, frozen walking animations, and a red damage overlay that never expired while server-side mobs continued moving. Cube load/drop, movement, cache eviction and view changes update membership using full coordinates; native interpolation, freeze and passenger handling remain in use. A real client/integrated server passed movement and damage checks for pigs, cows, zombies, endermen, bats and cod at Y=96 and Y=32,000, including a player attack and a separate pig using native AI/navigation. Remote multiplayer and every specialized mob behavior remain unverified.

Live entity visibility follows full cube coordinates. Server and client spatial queries check actual bounding boxes without truncating Y, including large entities and movements between heights that alias in vanilla's section index. Client tracking uses distance on all three axes and requires the entity's cube to have been sent in the current dimension. Sending or dropping a cube updates stationary entities too. Spatial queries currently scan a snapshot of all visible entities; large populations need a faster index. Visual multiplayer validation remains unfinished.

Live entities now save and load in separate `cube_entities` files keyed by full cube X/Y/Z. Native NBT preserves UUIDs, names, health, equipment, and passenger trees across restarts. FULL-cube status loads entity data; autosave, save-all, shutdown, and cube unloading use the new store. An unload waits for successful writes before releasing entities and their UUIDs. Corrupt, incompatible, or conflicting saved data stops persistence instead of being replaced by an empty snapshot. Autosave writes detached snapshots in the background, and unload retains entities until acknowledgement. Explicit flush and close wait for completion; multi-cube saves are not an atomic transaction.

Structure templates include resident entities at extended heights: mobs, names, equipment, selected passenger trees, and block-attached entities such as frames and paintings. Each paste creates new UUIDs and registers entities with Minecraft's entity manager. Preparation checks entity positions and loaded coverage before inserting blocks. Players are excluded; `ignoreEntities` still disables entity capture/placement. The template format remains vanilla `.nbt`. Visual gameplay verification remains unfinished.

Templates also capture blocks, air, and block-entity data from loaded cubes. Disk reload and placement preserve inventories, rotation, reflection, waterlogging, and neighbor shape repair across cube boundaries. Capture and placement check the whole area first; missing cubes reject the operation before capture or insertion. Placement requires a loaded one-block border when waterlogging or shape repair is enabled, plus coverage of the placed entities' actual bounding boxes.

Structure corner detection searches an inclusive 80-block radius on all three axes around the SAVE block, including at extreme positive and negative heights. It checks at most 216 already loaded FULL cubes and clips the area at the supported coordinate limits. If any cube is unavailable, detection fails without changing the previous dimensions or loading more terrain. Matching CORNER blocks retain vanilla's one-corner/multiple-corner rules. Ordinary worlds keep their vanilla search.

Sign, command-block, and structure-block editing uses full-coordinate packets. Opening a sign editor and sending directed block-entity data, including a command block's current command, also preserve extended heights. Server handlers retain game-master permissions and sign ownership/wax checks; delayed sign filtering cannot apply to a replaced cube/entity or another world. Command mode changes reinsert their entity into the cube. Structure SAVE/LOAD packets now exercise the actual template manager and placement path in tests at both signs of large Y.

Block destruction and use-on-block actions now travel in custom client-to-server envelopes with full coordinates and vanilla prediction sequences. Server handlers retain their normal interaction checks, with cubic build limits and a tracked/loaded-cube gate. Direct server corrections use the cubic block-change protocol. Client prediction uses immutable full-coordinate keys and defers authoritative states and block-entity tags until acknowledgement. Full relight snapshots preserve pending local actions while updating their authoritative baseline; removing a cube discards its predictions. Dimension section-array sizes remain unchanged.

Full cube loads now include an initial client-visible snapshot of every block entity, including entities whose normal change packet is absent. Pending saved tags are promoted before capture. The client prepares replacement sections and entities before installing them, and clears old objects, tickers, and pending tags on replacement or unload. Positions preserve the full supported height range. Initial data uses Minecraft's `getUpdateTag`; chest contents still synchronize through menus. Full-cube wire format 4 (inner data format 2) requires matching client/server builds; disk format 2 is unchanged.

Server-to-client cube block changes use a full cube position, XYZ section index, and local block positions. They carry bounded normal block-entity update tags with the corresponding states. Large delta tag batches split into packets, and failed sends retain dirty changes. Full snapshots have separate bounded section/entity budgets and fail capture if exceeded. Other specialized packets, debug networking, and complete rendering at extended heights still require work.

The earlier broadcasting changes removed the two missing-member references from the expanded DASM audit. Cube holders handle eight sections independently of dimension height, and send readiness uses 3D player tracking. Vanilla column-light packets are absent from the cube broadcaster; column-based debug subscriptions are registered only for ordinary chunks. Cube debug overlays remain unimplemented.

Normal gameplay now automatically lights tracked FULL cubes, prioritizing positions near players. A detached worker solves each 32-block cube with a 15-block halo using Minecraft 26.3 emission, attenuation and block-face occlusion. A separate vertical index accounts for roofs anywhere above it, including saved unloaded cubes. Live and saved cubes override the decorated generator baseline. Unloaded terrain in the lighting halo and skylight occlusion above the player use the same native sections as new cube generation, including water and tree canopies.

Both layers travel with their complete block snapshot. Per-cube epochs preserve valid light elsewhere while edits revoke affected columns and neighboring columns, including far below a roof. In-flight calculations and queued client callbacks reject stale dependencies. The scheduler admits up to eight concurrent cube calculations, shares unchanged sky indexes, and prioritizes cubes near block edits. Owner-thread capture has a 32-snapshot / 4 ms soft budget; only the local halo needs full block copies. Disk reads and propagation run asynchronously. During relighting, the client keeps the resident cube's last accepted light visible until its replacement arrives. Current readiness and server gameplay still require up-to-date data. Newly sent cubes include exact light immediately; generating new regions and rebuilding their meshes can still take seconds.

Client recovery can republish current ready data after prediction invalidation, with tracking checks and a rate limit. Full-cube wire format **4**, including its per-cube lighting flag, requires matching client/server builds. Disk format 2 is unchanged: light is derived again after loading, and `isLightCorrect` remains false. The earlier explicitly selected bounded solver and its unknown-boundary query APIs remain available separately for diagnostics.

Client light prediction now handles both increases and decreases before the block setter returns: opening terrain, placing opaque blocks, and adding or removing torches. It first removes dependent light, then restores contributions from remaining sources and neighboring sky using native attenuation and face occlusion across cube boundaries. The complete result is published atomically; temporary cleared arrays never reach rendering. Work is capped at 32,768 queue visits per edit. An oversized transaction keeps the previous display until the server completes it, and unavailable terrain is never loaded or assumed bright. Prediction does not certify server readiness; authoritative publications still replace it normally.

Real client/server checks cover open sky, a solid roof, roof removal, source placement/removal and save/reopen at Y near **+32,000,000 and -32,000,000**. These establish brightness calculation and transport, not rendering at those extremes: vanilla's packed render-section coordinates still alias there, leaving geometry unavailable or stale. The same edit checks passed at **+32,000 and -32,000**, with actual visible meshes near every sample and clean renderer shutdown. See [PORTING.md](PORTING.md) for reproduction commands and validation limits.

Direct-skylight obstacle queries across loaded and saved cubes remain available separately. They distinguish a known blocking edge, a clear bounded range, and unknown coverage. A clear range does not prove access to the sky.

Bounded surface queries also combine persisted local heightmaps with a snapshot of live changes. They preserve unloaded upper cubes and distinguish known heights, empty ranges, and missing or unfinished cubes. These APIs do not yet replace vanilla world-height queries.

Local heightmaps cover each cube's 32×32 horizontal footprint. All six Minecraft heightmap types update with block edits, survive conversion from proto to full cubes, and are included in cube packets and disk snapshots.

Experimental persistence also retains all eight sections' block and biome palettes, block entities and inventories, proto entities, scheduled ticks, structures, and postprocessing. New saves use cube format 2; this branch's format 1 files rebuild local heightmaps from their saved blocks. Both require the exact Minecraft data version, and there is no import of older Cubic Chunks worlds. Corrupt or incompatible files fail loading instead of becoming empty cubes. Autosave writes asynchronously through bounded queues; explicit flush and shutdown wait for acknowledgement. Storage still uses one compressed file per cube.

See [PORTING.md](PORTING.md) for build instructions, validation results, and remaining work. [REFERENCE_REUSE.md](REFERENCE_REUSE.md) records the earlier implementations reviewed: the CC2 persistence files already exist unchanged in `src_old`, while legacy heightmap and lighting algorithms remain useful references for subsequent work.

The project's goal is to extend Minecraft height and depth to be nearly infinite (at least a million blocks).

For the most up-to-date information about this mod and its related mods, as well as the newest downloads, please join us on the [**Cubic Chunks Discord**](https://discord.gg/kMfWg9m).

### Cubic Chunks (CC) - Links:

Github - [Cubic Chunks - 1.21.6 and above](https://github.com/OpenCubicChunks/CubicChunks3)  
Github - [Cubic Chunks - 1.12.2 and lower](https://github.com/OpenCubicChunks/CubicChunks)

### Cloning the repository

Note: you need git installed to do the following:
```
git clone --recursive
```
You need a git submodule for the project to compile.
If you don't yet have the submodule but already cloned the repository:
```
git submodule update --init --recursive
```

This port uses the pinned Core revision with a compatibility patch. After initializing the submodule, run `./port-tools/apply-core-patch.ps1`. Do not update Core to an arbitrary remote revision; see [PORTING.md](PORTING.md).

### .git-blame-ignore-revs
Configure commits to be ignored for git blame:

```shell
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

### Contributing

#### PR Guidelines
- All mixin methods and fields _**must**_ have a `cc_` prefix, except for those in test mixins. // todo automate this check in gh workflows.
- If a class is significantly modified with mixins, it _**must**_ have documentation explaining:
	- The original vanilla behaviour, can mention important fields/methods.
		- If the vanilla class has sufficient javadoc (through parchment), this can be skipped. Our javadoc should link to the parchment javadoc.
	- The goal of all mixins targeting the class.
- Any merged mixins _**must**_ have tests associated with them. 
	- If a mixin is "untestable" its test class should have a comment explaining *why* it's untestable. 
		- Optionally a to-do (project task? issue?) suggesting integration tests when possible.
	- All non-trivial mixins _**must**_ have a comment explaining their purpose.
	-  //todo Investigate code coverage for mixin tests ([jacoco?](https://docs.gradle.org/current/userguide/jacoco_plugin.html) [other link maybe it's bad](https://igorski.co/generating-junit-test-coverage-using-gradle-and-jacoco/)) .
- Any non-mixin class _**must**_ have tests associated with it.
	- The tests should reasonably cover all expected usage of the class (its external api).
	- Any method(s) that can be reasonably unit tested _**must**_ be.
- Anything annotated with `@SuppressWarnings` _**must**_ have either a TODO comment, or a comment explaining why the warning is suppressed.
- _**Must**_ pass spotless check and checkstyle. Spotless formats automatically if you run `./gradlew spotlessApply`; checkstyle violations must be fixed manually.
- _**Must**_ build.
- All tests _**must**_ pass (no regressions).
(TODO more contributing docs)
