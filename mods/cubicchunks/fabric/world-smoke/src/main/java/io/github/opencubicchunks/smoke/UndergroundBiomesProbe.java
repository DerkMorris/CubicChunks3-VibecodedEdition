package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** Real saved cubes and client packets for all three classic cave biomes at two depths; no fixture block writes. */
final class UndergroundBiomesProbe {
    private static final List<ResourceKey<Biome>> BIOMES = List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK);
    private static final int[] HEIGHTS = { -320, -32000 };
    private static final DeepCaveSettings SETTINGS = new DeepCaveSettings(true, 64, 65, 35);
    private final boolean reopen;
    private final List<Sample> samples = new ArrayList<>();
    private CompletableFuture<Sample> search;
    private CompletableFuture<Void> movement;
    private long deadline;
    private int stage;
    private int settled;
    private boolean finished;

    UndergroundBiomesProbe(boolean reopen) {
        this.reopen = reopen;
    }

    static void configureCreation(boolean reopen) {
        // Reopening must use the world's saved settings, even if the creation preference now disagrees.
        CubicChunks.config().setDeepCaveSettings(reopen ? DeepCaveSettings.CLASSIC : SETTINGS);
    }

    void tick(Minecraft client) {
        if (finished) return;
        try {
            require(!CubicChunks.IS_IN_TEST, "test bypass enabled");
            if (deadline == 0) deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
            require(System.nanoTime() < deadline, "timeout in stage " + stage);
            var server = client.getSingleplayerServer();
            if (search == null) {
                var level = server.overworld();
                require(SETTINGS.equals(((CubicWorldData) level.getLevelData()).cc_deepCaveSettings()), "saved deep cave settings differ");
                var expectedBiome = BIOMES.get(stage % BIOMES.size());
                int y = HEIGHTS[stage / BIOMES.size()];
                Path manifest = server.getWorldPath(LevelResource.ROOT).resolve("underground-biomes-probe.txt");
                int sampleIndex = stage;
                search = CompletableFuture.supplyAsync(() -> reopen ? readSample(manifest, sampleIndex) : find(level, expectedBiome, y));
                return;
            }
            if (!search.isDone()) return;
            var sample = search.join();
            if (movement == null) {
                movement = CompletableFuture.runAsync(() -> {
                    var level = server.overworld();
                    var key = CubePos.from(sample.pos());
                    var cube = ((CubicLevelReader) level).cc_getCube(key.getX(), key.getY(), key.getZ(), ChunkStatus.FULL, true);
                    require(sample.block().equals(blockId(cube.getBlockState(sample.pos()))), "generated/saved feature missing at " + sample.pos());
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SPECTATOR);
                    player.teleportTo(sample.pos().getX() + .5, sample.pos().getY() + 1, sample.pos().getZ() + .5);
                }, server);
                return;
            }
            if (!movement.isDone()) return;
            movement.join();
            var key = CubePos.from(sample.pos());
            var cube = ((ClientCubeCache) client.level.getChunkSource()).cc_getCube(key.getX(), key.getY(), key.getZ(), false);
            if (cube == null || !client.player.isSpectator() || client.player.blockPosition().distSqr(sample.pos()) > 16) return;
            if (++settled < 20) return;
            var state = cube.getBlockState(sample.pos());
            require(sample.block().equals(blockId(state)), "client feature missing at " + sample.pos() + ": " + state);
            var expectedBiome = BIOMES.get(stage % BIOMES.size());
            int qx = QuartPos.fromBlock(sample.pos().getX()), qy = QuartPos.fromBlock(sample.pos().getY()), qz = QuartPos.fromBlock(sample.pos().getZ());
            require(cube.getNoiseBiome(qx, qy, qz).is(expectedBiome), "client biome mismatch at " + sample.pos());
            if (state.hasBlockEntity() && client.level.getBlockEntity(sample.pos()) == null) return;
            var clientState = state;
            server.submit(() -> {
                var level = server.overworld();
                require(level.getNoiseBiome(qx, qy, qz).is(expectedBiome), "server biome mismatch at " + sample.pos());
                require(((CubicServerLevel) level).cc_nativeBiomeTerrain().biomeResolver().getNoiseBiome(qx, qy, qz).is(expectedBiome),
                        "generated palette differs from resolver");
                require(clientState.equals(level.getBlockState(sample.pos())), "client/server state mismatch at " + sample.pos());
                if (clientState.hasBlockEntity()) require(level.getBlockEntity(sample.pos()) != null, "server cave block entity missing");
                if (clientState.is(Blocks.SCULK_SHRIEKER)) require(clientState.getValue(SculkShriekerBlock.CAN_SUMMON), "generated shrieker cannot summon");
            }).join();
            samples.add(sample);
            System.out.println("CC_UNDERGROUND_BIOME_VERIFIED stage=" + stage + " reopen=" + reopen + " biome=" + expectedBiome
                    + " pos=" + sample.pos() + " block=" + state + " blockEntity=" + state.hasBlockEntity() + " testBypass=false");
            if (++stage == BIOMES.size() * HEIGHTS.length) {
                if (!reopen) writeSamples(server.getWorldPath(LevelResource.ROOT).resolve("underground-biomes-probe.txt"), samples);
                finished = true;
                System.out.println("CC_UNDERGROUND_BIOMES_COMPLETE reopen=" + reopen + " phases=" + stage + " testBypass=false");
                client.stop();
            } else {
                search = null;
                movement = null;
                settled = 0;
            }
        } catch (Throwable failure) {
            finished = true;
            System.out.println("CC_UNDERGROUND_BIOMES_FAILED stage=" + stage + " reopen=" + reopen + " reason=" + failure);
            failure.printStackTrace();
            client.stop();
        }
    }

    private static Sample find(ServerLevel level, ResourceKey<Biome> biome, int y) {
        var terrain = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
        var resolver = terrain.biomeResolver();
        Set<CubePos> visited = new HashSet<>();
        int generated = 0;
        for (int radius = 0; radius < 96; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = dx * 128 + 8, z = dz * 128 + 8;
                    for (int dy : new int[] { 0, -32, 32, -64, 64 }) {
                        var key = CubePos.from(new BlockPos(x, y + dy, z));
                        if (!resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y + dy), QuartPos.fromBlock(z)).is(biome)
                                || !visited.add(key)) continue;
                        require(++generated <= 96, "no decorated cave found among 96 biome candidates for " + biome + " y=" + y);
                        // Preview the immutable production terrain on a detached cube; actual saved generation is checked after teleport.
                        var cube = new ProtoCube(key, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
                        terrain.fill(cube).join();
                        cube.setPersistedStatus(ChunkStatus.BIOMES);
                        for (int ly = 0; ly < CubicConstants.DIAMETER_IN_BLOCKS; ly++) {
                            for (int lz = 0; lz < CubicConstants.DIAMETER_IN_BLOCKS; lz++) {
                                for (int lx = 0; lx < CubicConstants.DIAMETER_IN_BLOCKS; lx++) {
                                    var pos = key.asBlockPos(lx, ly, lz);
                                    var state = cube.getBlockState(pos);
                                    if (feature(state, biome) && cube.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                                            QuartPos.fromBlock(pos.getZ())).is(biome)) return new Sample(pos, blockId(state));
                                }
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No biome sample found for " + biome + " y=" + y);
    }

    private static boolean feature(BlockState state, ResourceKey<Biome> biome) {
        if (biome == Biomes.LUSH_CAVES) return state.is(Blocks.MOSS_BLOCK);
        if (biome == Biomes.DRIPSTONE_CAVES) return state.is(Blocks.DRIPSTONE_BLOCK);
        return state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.SCULK_SHRIEKER);
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static Sample readSample(Path file, int index) {
        try {
            var lines = Files.readAllLines(file);
            require(lines.size() == BIOMES.size() * HEIGHTS.length, "saved sample manifest incomplete");
            String[] values = lines.get(index).split(" ");
            return new Sample(new BlockPos(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2])), values[3]);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read saved underground biome samples", failure);
        }
    }

    private static void writeSamples(Path file, List<Sample> samples) {
        try {
            Files.write(file, samples.stream().map(sample -> sample.pos().getX() + " " + sample.pos().getY() + " " + sample.pos().getZ()
                    + " " + sample.block()).toList());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot save underground biome samples", failure);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private record Sample(BlockPos pos, String block) {}
}
