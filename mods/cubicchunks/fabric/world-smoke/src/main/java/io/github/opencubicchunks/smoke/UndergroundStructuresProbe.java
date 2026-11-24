package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepStructureLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.storage.LevelResource;

/** Natural native layouts, configured block entities, full-height references and fresh-process persistence. */
final class UndergroundStructuresProbe {
    private static final List<ResourceKey<Structure>> TYPES = List.of(BuiltinStructures.MINESHAFT, BuiltinStructures.STRONGHOLD,
            BuiltinStructures.ANCIENT_CITY, BuiltinStructures.TRIAL_CHAMBERS, BuiltinStructures.RUINED_PORTAL_STANDARD);
    private static final int[] HEIGHTS = { -320, -32000 };
    private static final DeepCaveSettings SETTINGS = new DeepCaveSettings(true, 64, 45, 35);
    private static final List<String> CONFIG_KEYS = List.of("id", "LootTable", "LootTableSeed", "SpawnData", "SpawnPotentials",
            "MinSpawnDelay", "MaxSpawnDelay", "SpawnCount", "MaxNearbyEntities", "RequiredPlayerRange", "SpawnRange",
            "normal_config", "ominous_config", "config");
    private final boolean reopen;
    private final List<Sample> completed = new ArrayList<>();
    private CompletableFuture<Sample> search;
    private CompletableFuture<Void> movement;
    private long deadline;
    private long started;
    private int stage;
    private int point;
    private int settled;
    private boolean finished;

    UndergroundStructuresProbe(boolean reopen) {
        this.reopen = reopen;
    }

    static void configureCreation(boolean reopen) {
        CubicChunks.config().setDeepCaveSettings(reopen ? DeepCaveSettings.CLASSIC : SETTINGS);
    }

    void tick(Minecraft client) {
        if (finished) return;
        try {
            require(!CubicChunks.IS_IN_TEST, "test bypass enabled");
            if (started == 0) started = System.nanoTime();
            if (deadline == 0) deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
            require(System.nanoTime() < deadline, "stage deadline exceeded");
            var server = client.getSingleplayerServer();
            var level = server.overworld();
            if (search == null) {
                require(SETTINGS.equals(((CubicWorldData) level.getLevelData()).cc_deepCaveSettings()), "saved cave settings differ");
                require(level.structureManager().shouldGenerateStructures(), "structure generation disabled");
                int index = stage;
                var file = manifest(level);
                search = CompletableFuture.supplyAsync(() -> reopen ? readSample(file, index) : find(level, index));
                return;
            }
            if (!search.isDone()) return;
            var sample = search.join();
            var target = visits(sample).get(point);
            if (movement == null) {
                movement = CompletableFuture.runAsync(() -> {
                    if (point == 0) {
                        verifyServer(level, sample);
                        if (stage == 0) {
                            if (reopen) require(level.getBlockState(sample.edit()).is(Blocks.EMERALD_BLOCK), "saved player edit was regenerated");
                            else level.setBlockAndUpdate(sample.edit(), Blocks.EMERALD_BLOCK.defaultBlockState());
                        }
                    }
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SPECTATOR);
                    player.teleportTo(target.pos().getX() + .5, target.pos().getY() + 1, target.pos().getZ() + .5);
                }, server);
                return;
            }
            if (!movement.isDone()) return;
            movement.join();
            var key = CubePos.from(target.pos());
            var cube = ((ClientCubeCache) client.level.getChunkSource()).cc_getCube(key.getX(), key.getY(), key.getZ(), false);
            if (cube == null || !client.player.isSpectator() || client.player.blockPosition().distSqr(target.pos()) > 16) return;
            if (++settled < 15) return;
            var state = cube.getBlockState(target.pos());
            require(target.block().equals(blockId(state)), "client structure block mismatch at " + target.pos() + ": " + state);
            if (state.hasBlockEntity() && client.level.getBlockEntity(target.pos()) == null) return;
            if (target.role().equals("portal_frame")) require(target.state().equals(state.toString()), "client portal frame orientation/eye differs");
            System.out.println("CC_UNDERGROUND_STRUCTURE_CLIENT stage=" + stage + " role=" + target.role() + " pos=" + target.pos()
                    + " block=" + state + " reopen=" + reopen);
            if (++point < visits(sample).size()) {
                movement = null;
                settled = 0;
                return;
            }
            completed.add(sample);
            System.out.println("CC_UNDERGROUND_STRUCTURE_VERIFIED stage=" + stage + " type=" + TYPES.get(stage % TYPES.size())
                    + " owner=" + sample.owner() + " points=" + sample.proofs().size() + " reopen=" + reopen + " testBypass=false");
            if (++stage == TYPES.size() * HEIGHTS.length) {
                if (!reopen) writeSamples(manifest(level), completed);
                finished = true;
                System.out.println("CC_UNDERGROUND_STRUCTURES_COMPLETE reopen=" + reopen + " phases=" + stage
                        + " millis=" + Duration.ofNanos(System.nanoTime() - started).toMillis() + " testBypass=false");
                client.stop();
            } else {
                search = null;
                movement = null;
                point = 0;
                settled = 0;
                deadline = 0;
            }
        } catch (Throwable failure) {
            finished = true;
            System.out.println("CC_UNDERGROUND_STRUCTURES_FAILED stage=" + stage + " point=" + point + " reopen=" + reopen + " reason=" + failure);
            failure.printStackTrace();
            client.stop();
        }
    }

    private static Sample find(ServerLevel level, int stage) {
        var terrain = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
        var layout = terrain.deepStructures();
        require(layout != null, "deep layout absent");
        int kind = stage % TYPES.size();
        int y = HEIGHTS[stage / TYPES.size()];
        var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(TYPES.get(kind));
        Set<CubePos> rejected = new HashSet<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            var start = layout.findNearestStart(HolderSet.direct(holder), new BlockPos(0, y, 0), 200,
                    value -> Math.abs((long) value.bounds().getCenter().getY() - y) <= 512 && !rejected.contains(value.owner()));
            require(start != null, "no natural start for " + TYPES.get(kind) + " around Y=" + y);
            System.out.println("CC_UNDERGROUND_STRUCTURE_LOCATED stage=" + stage + " owner=" + start.owner() + " bounds=" + start.bounds());
            var proofs = preview(level, start, kind);
            if (complete(proofs, kind)) {
                return new Sample(start.owner(), start.tag(), List.copyOf(proofs), proofs.getFirst().pos().above(5));
            }
            rejected.add(start.owner());
            System.out.println("CC_UNDERGROUND_STRUCTURE_RETRY stage=" + stage + " owner=" + start.owner() + " signatures="
                    + proofs.stream().map(Proof::role).distinct().toList());
        }
        throw new IllegalStateException("Natural layouts missing required signatures for " + TYPES.get(kind));
    }

    private static List<Proof> preview(ServerLevel level, DeepStructureLayout.Start start, int kind) {
        var nativeStart = start.copy(level);
        var pieces = new ArrayList<>(nativeStart.getPieces());
        pieces.sort(Comparator.comparingInt(piece -> piece instanceof StrongholdPieces.PortalRoom ? 0 : 1));
        Set<CubePos> candidates = new LinkedHashSet<>();
        for (var piece : pieces) {
            if (kind == 1 && !(piece instanceof StrongholdPieces.PortalRoom)) continue;
            addCubes(candidates, piece.getBoundingBox());
        }
        var terrain = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
        List<Proof> proofs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        int visited = 0;
        for (var key : candidates) {
            require(++visited <= 192, "structure preview exceeded bounded cube scan");
            var cube = new ProtoCube(key, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            terrain.fill(cube).join();
            var area = start.bounds();
            for (var at : BlockPos.betweenClosed(Math.max(key.minCubeX(), area.minX()), Math.max(key.minCubeY(), area.minY()),
                    Math.max(key.minCubeZ(), area.minZ()), Math.min(key.maxCubeX(), area.maxX()), Math.min(key.maxCubeY(), area.maxY()),
                    Math.min(key.maxCubeZ(), area.maxZ()))) {
                var state = cube.getBlockState(at);
                String role = role(state, kind);
                if (role == null || !seen.add(at.immutable())) continue;
                if (!role.equals("portal_frame") && proofs.stream().anyMatch(value -> value.role().equals(role))) continue;
                var tag = state.hasBlockEntity() ? cube.getBlockEntityNbtForSaving(at, level.registryAccess()) : null;
                var config = tag == null ? new CompoundTag() : configuration(tag);
                if (!validConfiguration(role, config)) continue;
                proofs.add(new Proof(role, at.immutable(), blockId(state), state.toString(), config));
            }
            if (complete(proofs, kind)) return proofs;
        }
        return proofs;
    }

    private static void addCubes(Set<CubePos> target, BoundingBox bounds) {
        target.add(CubePos.from(bounds.getCenter()));
        var min = CubePos.from(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()));
        var max = CubePos.from(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) target.add(CubePos.of(x, y, z));
            }
        }
    }

    private static String role(BlockState state, int kind) {
        return switch (kind) {
            case 0 -> state.is(Blocks.SPAWNER) ? "cave_spawner" : state.is(BlockTags.RAILS) ? "rail" : null;
            case 1 -> state.is(Blocks.END_PORTAL_FRAME) ? "portal_frame" : state.is(Blocks.SPAWNER) ? "silverfish_spawner" : null;
            case 2 -> state.is(Blocks.CHEST) ? "city_chest" : state.is(Blocks.REINFORCED_DEEPSLATE) ? "city_center" : null;
            case 3 -> state.is(Blocks.TRIAL_SPAWNER) ? "trial_spawner" : state.is(Blocks.VAULT) ? "vault" : null;
            default -> state.is(Blocks.CHEST) ? "portal_chest" : state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) ? "obsidian" : null;
        };
    }

    private static boolean validConfiguration(String role, CompoundTag tag) {
        return switch (role) {
            case "cave_spawner" -> tag.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getString("id").orElse("").equals("minecraft:cave_spider");
            case "silverfish_spawner" -> tag.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getString("id").orElse("").equals("minecraft:silverfish");
            case "city_chest" -> tag.getString("LootTable").orElse("").startsWith("minecraft:chests/ancient_city");
            case "portal_chest" -> tag.getString("LootTable").orElse("").equals("minecraft:chests/ruined_portal");
            case "trial_spawner" -> tag.contains("normal_config") && tag.contains("ominous_config");
            case "vault" -> tag.contains("config");
            default -> true;
        };
    }

    private static boolean complete(List<Proof> proofs, int kind) {
        var roles = proofs.stream().map(Proof::role).collect(java.util.stream.Collectors.toSet());
        return switch (kind) {
            case 0 -> roles.containsAll(Set.of("cave_spawner", "rail"));
            case 1 -> roles.contains("silverfish_spawner") && proofs.stream().filter(value -> value.role().equals("portal_frame")).count() == 12;
            case 2 -> roles.containsAll(Set.of("city_chest", "city_center"));
            case 3 -> roles.containsAll(Set.of("trial_spawner", "vault"));
            default -> roles.containsAll(Set.of("portal_chest", "obsidian"));
        };
    }

    private static void verifyServer(ServerLevel level, Sample sample) {
        var requested = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(TYPES.get(currentKind(sample)));
        var type = requested.value();
        var owner = cube(level, sample.owner());
        var savedStart = owner.getStartForStructure(type);
        require(savedStart != null && savedStart.isValid(), "missing start in owner cube " + sample.owner());
        var metadata = ((CubicServerLevel) level).cc_nativeBiomeTerrain().deepStructures().startForOwner(sample.owner(), type);
        require(metadata != null && metadata.copy(level).getBoundingBox().equals(savedStart.getBoundingBox()),
                "metadata-only owner query differs for " + sample.owner());
        var center = savedStart.getBoundingBox().getCenter();
        var located = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(requested), center, 8, false);
        require(located != null && located.getSecond().value() == type && located.getFirst().equals(center),
                "native locate did not return the full-Y structure center for " + sample.owner() + ": " + located);
        var savedTag = savedStart.createTag(StructurePieceSerializationContext.fromLevel(level), savedStart.getChunkPos());
        var expected = sample.start().copy();
        savedTag.remove("references");
        expected.remove("references");
        require(expected.equals(savedTag), "saved native piece graph differs for " + sample.owner());
        for (var proof : sample.proofs()) {
            var cube = cube(level, CubePos.from(proof.pos()));
            require(cube.getReferencesForStructure(type).contains(sample.owner().asLong()), "missing full-Y structure reference at " + proof.pos());
            var state = cube.getBlockState(proof.pos());
            require(proof.block().equals(blockId(state)), "generated/persisted block differs at " + proof.pos());
            if (proof.role().equals("portal_frame")) require(proof.state().equals(state.toString()), "portal frame orientation/eye differs");
            if (!proof.config().isEmpty()) {
                var entity = level.getBlockEntity(proof.pos());
                require(entity != null, "missing generated block entity at " + proof.pos());
                require(proof.config().equals(configuration(entity.saveWithFullMetadata(level.registryAccess()))),
                        "configured block entity data differs at " + proof.pos());
                if (proof.role().endsWith("chest")) require(entity instanceof RandomizableContainerBlockEntity chest && chest.getLootTable() != null,
                        "unopened generated chest lost its loot table");
            }
        }
    }

    private static int currentKind(Sample sample) {
        var id = sample.start().getString("id").orElseThrow();
        for (int i = 0; i < TYPES.size(); i++) if (TYPES.get(i).identifier().toString().equals(id)) return i;
        throw new IllegalStateException("Unsupported saved structure " + id);
    }

    private static CubeAccess cube(ServerLevel level, CubePos pos) {
        return ((CubicLevelReader) level).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, true);
    }

    private static List<Proof> visits(Sample sample) {
        Map<String, Proof> roles = new LinkedHashMap<>();
        sample.proofs().forEach(value -> roles.putIfAbsent(value.role(), value));
        return List.copyOf(roles.values());
    }

    private static CompoundTag configuration(CompoundTag tag) {
        var result = new CompoundTag();
        for (String key : CONFIG_KEYS) if (tag.contains(key)) result.put(key, tag.get(key).copy());
        return result;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static Path manifest(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("underground-structures-probe.nbt");
    }

    private static void writeSamples(Path file, List<Sample> samples) {
        var root = new CompoundTag();
        var entries = new ListTag();
        for (var sample : samples) {
            var entry = new CompoundTag();
            entry.putIntArray("owner", new int[] { sample.owner().getX(), sample.owner().getY(), sample.owner().getZ() });
            entry.put("start", sample.start());
            entry.putIntArray("edit", xyz(sample.edit()));
            var proofs = new ListTag();
            for (var proof : sample.proofs()) {
                var value = new CompoundTag();
                value.putString("role", proof.role());
                value.putIntArray("pos", xyz(proof.pos()));
                value.putString("block", proof.block());
                value.putString("state", proof.state());
                value.put("config", proof.config());
                proofs.add(value);
            }
            entry.put("proofs", proofs);
            entries.add(entry);
        }
        root.put("samples", entries);
        try {
            NbtIo.writeCompressed(root, file);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot save structure smoke manifest", failure);
        }
    }

    private static Sample readSample(Path file, int index) {
        try {
            var root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            var samples = root.getListOrEmpty("samples");
            require(samples.size() == TYPES.size() * HEIGHTS.length, "incomplete saved structure samples");
            var entry = (CompoundTag) samples.get(index);
            int[] owner = entry.getIntArray("owner").orElseThrow();
            var proofs = new ArrayList<Proof>();
            for (var tag : entry.getListOrEmpty("proofs")) {
                var value = (CompoundTag) tag;
                proofs.add(new Proof(value.getString("role").orElseThrow(), pos(value.getIntArray("pos").orElseThrow()),
                        value.getString("block").orElseThrow(), value.getString("state").orElseThrow(), value.getCompoundOrEmpty("config")));
            }
            return new Sample(CubePos.of(owner[0], owner[1], owner[2]), entry.getCompoundOrEmpty("start"), List.copyOf(proofs),
                    pos(entry.getIntArray("edit").orElseThrow()));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read structure smoke manifest", failure);
        }
    }

    private static int[] xyz(BlockPos pos) {
        return new int[] { pos.getX(), pos.getY(), pos.getZ() };
    }

    private static BlockPos pos(int[] values) {
        return new BlockPos(values[0], values[1], values[2]);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private record Proof(String role, BlockPos pos, String block, String state, CompoundTag config) {}

    private record Sample(CubePos owner, CompoundTag start, List<Proof> proofs, BlockPos edit) {}
}
