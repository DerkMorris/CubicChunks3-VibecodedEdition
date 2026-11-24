package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.NativeBiomeTerrain;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.StrongholdCaveBaseline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
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

/** Maximal caves become smaller natural caves near strongholds/trial chambers while native interiors remain open. */
final class StrongholdCavesProbe {
    private static final DeepCaveSettings SETTINGS = new DeepCaveSettings(true, DeepCaveSettings.MAX_SCALE, 100, 35);
    private static final int[] HEIGHTS = { -320, -32000 };
    private static final List<ResourceKey<Structure>> TYPES = List.of(BuiltinStructures.STRONGHOLD, BuiltinStructures.TRIAL_CHAMBERS);
    private static final int[] DISTANCES = { 1, 2, 4, 6, 8, 12, 16, 24, 32, 48, 64, 80, 96, 112, 128, 136, 144, 152, 160 };
    private static final List<String> SPAWNER_KEYS = List.of("id", "SpawnData", "SpawnPotentials", "MinSpawnDelay", "MaxSpawnDelay",
            "SpawnCount", "MaxNearbyEntities", "RequiredPlayerRange", "SpawnRange", "normal_config", "ominous_config", "config");
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

    StrongholdCavesProbe(boolean reopen) {
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
                Path file = manifest(level);
                search = CompletableFuture.supplyAsync(() -> reopen ? readSample(file, index) : find(level, index));
                return;
            }
            if (!search.isDone()) return;
            var sample = search.join();
            var target = visits(sample).get(point);
            if (movement == null) {
                movement = CompletableFuture.runAsync(() -> {
                    if (point == 0) verifyServer(level, sample);
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SPECTATOR);
                    player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 12000, 0));
                    player.teleportTo(target.pos().getX() + .5, target.pos().getY() + .05, target.pos().getZ() + .5);
                }, server);
                return;
            }
            if (!movement.isDone()) return;
            movement.join();
            var key = CubePos.from(target.pos());
            var cube = ((ClientCubeCache) client.level.getChunkSource()).cc_getCube(key.getX(), key.getY(), key.getZ(), false);
            if (cube == null || !client.player.isSpectator() || client.player.blockPosition().distSqr(target.pos()) > 16) return;
            if (++settled < 20) return;
            var state = cube.getBlockState(target.pos());
            require(target.state().equals(state.toString()), "client sample differs at " + target.pos() + ": " + state);
            if (!target.config().isEmpty() && client.level.getBlockEntity(target.pos()) == null) return;
            if (target.role().endsWith("interior") || target.role().equals("near_natural_air")) {
                client.player.setXRot(0);
                String name = target.role().equals("near_natural_air") ? "structure-caves-" + stage + "-near" : "stronghold-caves-" + stage;
                Screenshot.grab(client.gameDirectory, name + (reopen ? "-reopen" : "") + ".png",
                        client.gameRenderer.mainRenderTarget(), 1, message -> System.out.println("CC_STRONGHOLD_CAVES_SCREENSHOT " + message));
            }
            System.out.println("CC_STRONGHOLD_CAVES_CLIENT stage=" + stage + " role=" + target.role() + " pos=" + target.pos()
                    + " block=" + state + " reopen=" + reopen);
            if (++point < visits(sample).size()) {
                movement = null;
                settled = 0;
                return;
            }
            completed.add(sample);
            System.out.println("CC_STRONGHOLD_CAVES_VERIFIED stage=" + stage + " owner=" + sample.owner() + " samples=" + sample.proofs().size()
                    + " metrics=" + sample.metrics() + " reopen=" + reopen + " testBypass=false");
            if (++stage == HEIGHTS.length * TYPES.size()) {
                if (!reopen) writeSamples(manifest(level), completed);
                finished = true;
                System.out.println("CC_STRONGHOLD_CAVES_COMPLETE reopen=" + reopen + " phases=" + stage + " millis="
                        + Duration.ofNanos(System.nanoTime() - started).toMillis() + " testBypass=false");
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
            System.out.println("CC_STRONGHOLD_CAVES_FAILED stage=" + stage + " point=" + point + " reopen=" + reopen + " reason=" + failure);
            failure.printStackTrace();
            client.stop();
        }
    }

    private static Sample find(ServerLevel level, int stage) {
        var terrain = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
        var layout = terrain.deepStructures();
        require(layout != null, "deep structure layout absent");
        int kind = stage % TYPES.size();
        var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(TYPES.get(kind));
        int y = HEIGHTS[stage / TYPES.size()];
        var start = layout.findNearestStart(HolderSet.direct(holder), new BlockPos(0, y, 0), 200,
                value -> Math.abs((long) value.bounds().getCenter().getY() - y) <= 512);
        require(start != null, "no natural " + TYPES.get(kind) + " near Y=" + y);
        System.out.println("CC_STRONGHOLD_CAVES_LOCATED stage=" + stage + " type=" + TYPES.get(kind) + " owner=" + start.owner() + " bounds=" + start.bounds());
        var pieces = start.copy(level).getPieces();
        var preview = new Preview(level, terrain);
        var baseline = new StrongholdCaveBaseline(level);
        List<Proof> proofs = new ArrayList<>();
        var structureMetrics = kind == 0 ? stronghold(preview, proofs, pieces) : trial(preview, baseline, proofs, pieces);
        var metrics = profiles(preview, baseline, proofs, pieces, start.bounds());
        metrics.put("structure", structureMetrics);
        return new Sample(start.owner(), start.tag(), List.copyOf(proofs), metrics);
    }

    private static CompoundTag stronghold(Preview preview, List<Proof> proofs, List<StructurePiece> pieces) {
        var corridors = pieces.stream().filter(piece -> piece instanceof StrongholdPieces.Straight).limit(12).toList();
        require(corridors.size() >= 3, "natural graph has insufficient straight corridors");
        int shells = 0;
        for (var piece : corridors) {
            for (int x = 1; x <= 3; x++) {
                for (int z = 1; z <= 5; z++) {
                    shell(preview, proofs, "floor", world(piece, x, 0, z));
                    shell(preview, proofs, "ceiling", world(piece, x, 4, z));
                    shells += 2;
                }
            }
            // Native optional side doors occupy z=2..4; z=1/5 must retain their complete walls.
            for (int height = 1; height <= 3; height++) {
                for (int z : new int[] { 1, 5 }) {
                    shell(preview, proofs, "wall", world(piece, 0, height, z));
                    shell(preview, proofs, "wall", world(piece, 4, height, z));
                    shells += 2;
                }
            }
            opening(preview, proofs, "interior", world(piece, 2, 2, 3));
            opening(preview, proofs, "exit", world(piece, 2, 2, 6));
        }
        var portal = pieces.stream().filter(piece -> piece instanceof StrongholdPieces.PortalRoom).findFirst().orElseThrow();
        int frames = 0;
        int spawners = 0;
        var box = portal.getBoundingBox();
        for (var at : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            var state = preview.state(at);
            if (state.is(Blocks.END_PORTAL_FRAME)) {
                proofs.add(proof(preview, "portal_frame", at));
                frames++;
            } else if (state.is(Blocks.SPAWNER)) {
                var proof = proof(preview, "silverfish_spawner", at);
                require(proof.config().getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getString("id").orElse("")
                        .equals("minecraft:silverfish"), "portal room spawner configuration missing");
                proofs.add(proof);
                spawners++;
            }
        }
        require(frames == 12 && spawners == 1, "portal room incomplete: frames=" + frames + " spawners=" + spawners);
        var metrics = new CompoundTag();
        metrics.putInt("corridors", corridors.size());
        metrics.putInt("shell_samples", shells);
        metrics.putInt("portal_frames", frames);
        return metrics;
    }

    private static CompoundTag trial(Preview preview, StrongholdCaveBaseline baseline, List<Proof> proofs, List<StructurePiece> pieces) {
        boolean spawner = false, vault = false, masonry = false;
        int carvedAir = 0, inspected = 0;
        for (var piece : pieces.stream().sorted(Comparator.comparingLong((StructurePiece value) -> volume(value.getBoundingBox())).reversed()).toList()) {
            var box = piece.getBoundingBox();
            for (var at : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                var state = preview.state(at);
                if (!spawner && state.is(Blocks.TRIAL_SPAWNER)) {
                    var proof = proof(preview, "trial_spawner", at);
                    require(proof.config().contains("normal_config") && proof.config().contains("ominous_config"), "trial spawner has no native configuration");
                    proofs.add(proof);
                    spawner = true;
                } else if (!vault && state.is(Blocks.VAULT)) {
                    var proof = proof(preview, "vault", at);
                    require(proof.config().contains("config"), "trial vault has no native configuration");
                    proofs.add(proof);
                    vault = true;
                } else if (!masonry && state.is(Blocks.TUFF_BRICKS)) {
                    proofs.add(proof(preview, "trial_wall", at));
                    masonry = true;
                }
                if (carvedAir < 16 && (at.getX() & 1) == 0 && (at.getY() & 1) == 0 && (at.getZ() & 1) == 0
                        && at.getX() > box.minX() && at.getX() < box.maxX() && at.getY() > box.minY() && at.getY() < box.maxY() - 1
                        && at.getZ() > box.minZ() && at.getZ() < box.maxZ()) {
                    inspected++;
                    // Native templates must carve usable interior air even where the pre-placement cave terrain was solid.
                    if (state.isAir() && preview.state(at.above()).isAir() && !baseline.production(at).isAir()) {
                        proofs.add(proof(preview, "trial_interior", at));
                        carvedAir++;
                    }
                }
            }
            if (spawner && vault && masonry && carvedAir == 16) break;
        }
        require(spawner && vault && masonry && carvedAir == 16, "trial chamber incomplete or interiors filled: spawner=" + spawner
                + " vault=" + vault + " masonry=" + masonry + " native_air=" + carvedAir);
        var metrics = new CompoundTag();
        metrics.putInt("native_interior_air", carvedAir);
        metrics.putInt("interior_candidates", inspected);
        return metrics;
    }

    private static long volume(BoundingBox box) {
        return (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
    }

    private static CompoundTag profiles(Preview preview, StrongholdCaveBaseline baseline, List<Proof> proofs,
            List<StructurePiece> pieces, BoundingBox bounds) {
        var boxes = pieces.stream().map(StructurePiece::getBoundingBox).toList();
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = bounds.minY() - 8; y <= bounds.maxY() + 8; y += 4) {
            for (int x = bounds.minX() - 8; x <= bounds.maxX() + 8; x += 4) {
                for (int z = bounds.minZ() - 8; z <= bounds.maxZ() + 8; z += 4) {
                    var at = new BlockPos(x, y, z);
                    double distance = boxes.stream().mapToDouble(box -> distanceSquared(at, box)).min().orElseThrow();
                    if (distance > 0 && distance <= 64) candidates.add(at);
                }
            }
        }
        int sampled = Math.min(4096, candidates.size());
        int nearAir = 0, baselineAir = 0, nearRock = 0, liveAir = 0, liveRock = 0;
        for (int index = 0; index < sampled; index++) {
            var at = candidates.get((int) ((long) index * candidates.size() / sampled));
            require(baseline.weight(at) == 1, "near geometry sample escaped parameter core");
            var original = baseline.block(at);
            var actual = baseline.production(at);
            if (original.isAir()) baselineAir++;
            if (actual.isAir()) {
                nearAir++;
                if (liveAir < 16 && preview.state(at).isAir() && preview.state(at.above()).isAir()) {
                    proofs.add(proof(preview, "near_natural_air", at));
                    liveAir++;
                }
            } else if (actual.isSolidRender()) {
                nearRock++;
                if (liveRock < 16 && naturalRock(preview.state(at))) {
                    proofs.add(proof(preview, "near_natural_rock", at));
                    liveRock++;
                }
            }
        }
        require(sampled >= 128 && nearAir > 0 && nearAir < baselineAir && nearRock > 0,
                "near structures must retain smaller natural caves: samples=" + sampled + " air=" + nearAir + " baselineAir=" + baselineAir + " rock=" + nearRock);
        require(liveAir > 0 && liveRock > 0, "near raw caves must have real usable cave air and natural rock samples");
        int middleRock = 0, middleAir = 0, outerEqual = 0;
        Map<Integer, Integer> outerDistances = new HashMap<>();
        for (var direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            var edge = pieces.stream().max(Comparator.comparingInt(piece -> extent(piece.getBoundingBox(), direction))).orElseThrow().getBoundingBox();
            var face = face(edge, direction);
            for (int distance : DISTANCES) {
                var at = face.relative(direction, distance);
                var original = baseline.block(at);
                var actual = baseline.production(at);
                String role = "profile";
                if (distance > 8 && distance < 136) {
                    if (actual.isAir()) {
                        middleAir++;
                        role = "transition_air";
                    } else if (actual.isSolidRender()) {
                        middleRock++;
                        role = "transition_rock";
                    }
                } else if (distance > 140 && baseline.unaffected(at)) {
                    require(actual.equals(original), "cave baseline changed outside attenuation radius at " + at + ": " + original + " -> " + actual);
                    outerEqual++;
                    outerDistances.merge(distance, 1, Integer::sum);
                    role = "outer_raw_unchanged";
                }
                proofs.add(proof(preview, role, at));
                System.out.println("CC_STRONGHOLD_CAVES_PROFILE dir=" + direction + " distance=" + distance + " pos=" + at
                        + " baseline=" + original + " generated=" + actual);
            }
        }
        require(outerDistances.keySet().containsAll(List.of(144, 152, 160)), "missing independent outside-radius raw comparisons: " + outerDistances);
        var result = new CompoundTag();
        result.putInt("near_samples", sampled);
        result.putInt("near_natural_air", nearAir);
        result.putInt("near_baseline_air", baselineAir);
        result.putInt("near_natural_rock", nearRock);
        result.putInt("near_live_air", liveAir);
        result.putInt("near_live_rock", liveRock);
        result.putInt("transition_rock", middleRock);
        result.putInt("transition_air", middleAir);
        result.putInt("outer_unchanged", outerEqual);
        return result;
    }

    private static double distanceSquared(BlockPos pos, BoundingBox box) {
        double x = Math.max(0, Math.max(box.minX() - pos.getX(), pos.getX() - box.maxX()));
        double y = Math.max(0, Math.max(box.minY() - pos.getY(), pos.getY() - box.maxY()));
        double z = Math.max(0, Math.max(box.minZ() - pos.getZ(), pos.getZ() - box.maxZ()));
        return x * x + y * y + z * z;
    }

    private static boolean naturalRock(BlockState state) {
        return state.is(Blocks.DEEPSLATE) || state.is(Blocks.STONE) || state.is(Blocks.TUFF) || state.is(Blocks.ANDESITE) || state.is(Blocks.CALCITE);
    }

    private static int extent(BoundingBox box, Direction direction) {
        return switch (direction) {
            case NORTH -> -box.minZ();
            case SOUTH -> box.maxZ();
            case WEST -> -box.minX();
            case EAST -> box.maxX();
            default -> throw new IllegalArgumentException();
        };
    }

    private static BlockPos face(BoundingBox box, Direction direction) {
        var center = box.getCenter();
        return switch (direction) {
            case NORTH -> new BlockPos(center.getX(), center.getY(), box.minZ());
            case SOUTH -> new BlockPos(center.getX(), center.getY(), box.maxZ());
            case WEST -> new BlockPos(box.minX(), center.getY(), center.getZ());
            case EAST -> new BlockPos(box.maxX(), center.getY(), center.getZ());
            default -> throw new IllegalArgumentException();
        };
    }

    private static BlockPos world(StructurePiece piece, int x, int y, int z) {
        var box = piece.getBoundingBox();
        var direction = piece.getOrientation();
        require(direction != null, "native corridor has no orientation");
        return switch (direction) {
            case NORTH -> new BlockPos(box.minX() + x, box.minY() + y, box.maxZ() - z);
            case SOUTH -> new BlockPos(box.minX() + x, box.minY() + y, box.minZ() + z);
            case WEST -> new BlockPos(box.maxX() - z, box.minY() + y, box.minZ() + x);
            case EAST -> new BlockPos(box.minX() + z, box.minY() + y, box.minZ() + x);
            default -> throw new IllegalArgumentException("Nonhorizontal native corridor");
        };
    }

    private static void shell(Preview preview, List<Proof> proofs, String role, BlockPos at) {
        var state = preview.state(at);
        require(state.is(Blocks.STONE_BRICKS) || state.is(Blocks.MOSSY_STONE_BRICKS) || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.INFESTED_STONE_BRICKS), "missing stronghold " + role + " at " + at + ": " + state);
        proofs.add(proof(preview, role, at));
    }

    private static void opening(Preview preview, List<Proof> proofs, String role, BlockPos at) {
        require(preview.state(at).isAir(), "stronghold " + role + " was sealed at " + at + ": " + preview.state(at));
        proofs.add(proof(preview, role, at));
    }

    private static Proof proof(Preview preview, String role, BlockPos at) {
        var state = preview.state(at);
        var tag = state.hasBlockEntity() ? preview.cube(at).getBlockEntityNbtForSaving(at, preview.level.registryAccess()) : null;
        return new Proof(role, at.immutable(), state.toString(), tag == null ? new CompoundTag() : configuration(tag));
    }

    private static CompoundTag configuration(CompoundTag tag) {
        var result = new CompoundTag();
        for (var key : SPAWNER_KEYS) if (tag.contains(key)) result.put(key, tag.get(key).copy());
        return result;
    }

    private static void verifyServer(ServerLevel level, Sample sample) {
        var identifier = sample.start().getString("id").orElseThrow();
        var typeKey = TYPES.stream().filter(type -> type.identifier().toString().equals(identifier)).findFirst().orElseThrow();
        var type = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(typeKey);
        var owner = load(level, sample.owner());
        var start = owner.getStartForStructure(type);
        require(start != null && start.isValid(), "saved stronghold owner missing");
        var actual = start.createTag(StructurePieceSerializationContext.fromLevel(level), start.getChunkPos());
        var expected = sample.start().copy();
        actual.remove("references");
        expected.remove("references");
        require(actual.equals(expected), "saved stronghold piece graph changed");
        Map<CubePos, CubeAccess> cubes = new HashMap<>();
        for (var proof : sample.proofs()) {
            var cube = cubes.computeIfAbsent(CubePos.from(proof.pos()), key -> load(level, key));
            var state = cube.getBlockState(proof.pos());
            require(proof.state().equals(state.toString()), "server generated/persisted sample differs at " + proof.pos() + ": " + state);
            if (!proof.role().contains("rock") && !proof.role().contains("air") && !proof.role().startsWith("outer") && !proof.role().equals("profile")) {
                require(cube.getReferencesForStructure(type).contains(sample.owner().asLong()), "full-Y stronghold reference missing at " + proof.pos());
            }
            if (!proof.config().isEmpty()) {
                var entity = level.getBlockEntity(proof.pos());
                require(entity != null && proof.config().equals(configuration(entity.saveWithFullMetadata(level.registryAccess()))),
                        "native block-entity configuration changed at " + proof.pos());
            }
        }
    }

    private static CubeAccess load(ServerLevel level, CubePos key) {
        return ((CubicLevelReader) level).cc_getCube(key.getX(), key.getY(), key.getZ(), ChunkStatus.FULL, true);
    }

    private static List<Proof> visits(Sample sample) {
        Map<String, Proof> roles = new LinkedHashMap<>();
        sample.proofs().forEach(proof -> {
            if (!proof.role().equals("profile")) roles.putIfAbsent(proof.role(), proof);
        });
        return List.copyOf(roles.values());
    }

    private static Path manifest(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("stronghold-caves-probe.nbt");
    }

    private static void writeSamples(Path file, List<Sample> samples) {
        var root = new CompoundTag();
        var entries = new ListTag();
        for (var sample : samples) {
            var entry = new CompoundTag();
            entry.putIntArray("owner", new int[] { sample.owner().getX(), sample.owner().getY(), sample.owner().getZ() });
            entry.put("start", sample.start());
            entry.put("metrics", sample.metrics());
            var proofs = new ListTag();
            for (var proof : sample.proofs()) {
                var value = new CompoundTag();
                value.putString("role", proof.role());
                value.putIntArray("pos", new int[] { proof.pos().getX(), proof.pos().getY(), proof.pos().getZ() });
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
            throw new IllegalStateException("Cannot save stronghold cave manifest", failure);
        }
    }

    private static Sample readSample(Path file, int index) {
        try {
            var entries = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getListOrEmpty("samples");
            require(entries.size() == HEIGHTS.length * TYPES.size(), "incomplete structure cave manifest");
            var entry = (CompoundTag) entries.get(index);
            int[] owner = entry.getIntArray("owner").orElseThrow();
            List<Proof> proofs = new ArrayList<>();
            for (var element : entry.getListOrEmpty("proofs")) {
                var value = (CompoundTag) element;
                int[] pos = value.getIntArray("pos").orElseThrow();
                proofs.add(new Proof(value.getString("role").orElseThrow(), new BlockPos(pos[0], pos[1], pos[2]),
                        value.getString("state").orElseThrow(), value.getCompoundOrEmpty("config")));
            }
            return new Sample(CubePos.of(owner[0], owner[1], owner[2]), entry.getCompoundOrEmpty("start"), List.copyOf(proofs),
                    entry.getCompoundOrEmpty("metrics"));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read stronghold cave manifest", failure);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private static final class Preview {
        private final ServerLevel level;
        private final NativeBiomeTerrain terrain;
        private final Map<CubePos, ProtoCube> cubes = new HashMap<>();

        private Preview(ServerLevel level, NativeBiomeTerrain terrain) {
            this.level = level;
            this.terrain = terrain;
        }

        private ProtoCube cube(BlockPos pos) {
            return cubes.computeIfAbsent(CubePos.from(pos), key -> {
                require(cubes.size() < 192, "preview exceeded bounded cube budget");
                var cube = new ProtoCube(key, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
                terrain.fill(cube).join();
                return cube;
            });
        }

        private BlockState state(BlockPos pos) {
            return cube(pos).getBlockState(pos);
        }
    }

    private record Proof(String role, BlockPos pos, String state, CompoundTag config) {}

    private record Sample(CubePos owner, CompoundTag start, List<Proof> proofs, CompoundTag metrics) {}
}
