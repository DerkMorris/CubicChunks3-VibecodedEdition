package io.github.opencubicchunks.smoke;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.CompletableFuture;

/** Check real cube bodies and both networked biome palettes at a forest and an ocean. */
final class BiomeProbe {
    private CompletableFuture<BlockPos> target;
    private int phase;
    private BlockPos forest;
    private BlockPos sample;
    private int ticks;

    void tick(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (target == null) {
            target = CompletableFuture.supplyAsync(() -> {
                var level = server.overworld();
                if (phase == 2) {
                    var cave = findCave(level, forest);
                    server.getPlayerList().getPlayers().getFirst().teleportTo(cave.getX() + .5, cave.getY(), cave.getZ() + .5);
                    return cave;
                }
                var source = level.getChunkSource();
                var gen = source.getGenerator();
                var resolver = gen.getBiomeSource().createUncachedResolver(source.randomState());
                var tag = phase == 0 ? BiomeTags.IS_FOREST : BiomeTags.IS_OCEAN;
                for (int r = 0; r < 100; r++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) { continue; }
                            int x = dx * 64 + 8, z = dz * 64 + 8;
                            if (!resolver.getNoiseBiome(QuartPos.fromBlock(x), 16, QuartPos.fromBlock(z)).is(tag)) { continue; }
                            int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState());
                            BlockPos pos = new BlockPos(x, y, z);
                            server.getPlayerList().getPlayers().getFirst().teleportTo(x + .5, y + 3, z + .5);
                            return pos;
                        }
                    }
                }
                throw new IllegalStateException("No climate sample for " + tag);
            }, server);
            return;
        }
        if (!target.isDone()) { return; }
        BlockPos pos = target.join();
        var cache = (ClientCubeCache) client.level.getChunkSource();
        if (++ticks > 1600) { throw new IllegalStateException("Missing biome or terrain in phase " + phase + " at " + pos); }
        if (ticks % 5 != 0) { return; }
        if (phase == 2) {
            var key = CubePos.from(pos);
            if (cache.cc_getCube(key.getX(), key.getY(), key.getZ(), false) == null) { return; }
            if (!client.level.getBlockState(pos).isAir()) { throw new IllegalStateException("Cave missing on client at " + pos); }
            sample = pos;
        }
        if (sample == null) {
            for (int dx = -16; dx <= 16 && sample == null; dx++) {
                for (int dz = -16; dz <= 16 && sample == null; dz++) {
                    for (int dy = -8; dy <= 16; dy++) {
                        var p = pos.offset(dx, dy, dz);
                        var cubePos = CubePos.from(p);
                        var cube = cache.cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), false);
                        if (cube == null) { continue; }
                        var state = cube.getBlockState(p);
                        if (phase == 0 ? state.is(BlockTags.LOGS) : state.is(Blocks.WATER)) { sample = p; break; }
                    }
                }
            }
        }
        if (sample == null) { return; }
        var p = sample;
        var clientBiome = client.level.getNoiseBiome(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
        var state = client.level.getBlockState(p);
        server.submit(() -> {
            var level = server.overworld();
            var serverBiome = level.getNoiseBiome(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
            var source = level.getChunkSource();
            var expected = source.getGenerator().getBiomeSource().createUncachedResolver(source.randomState()).getNoiseBiome(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
            if (!serverBiome.unwrapKey().equals(clientBiome.unwrapKey()) || !expected.unwrapKey().equals(serverBiome.unwrapKey()) || !state.equals(level.getBlockState(p))) {
                throw new IllegalStateException("Client biome or terrain mismatch at " + p);
            }
            System.out.println("CC_BIOME_VERIFIED phase=" + phase + " pos=" + p + " biome=" + serverBiome + " block=" + state);
        }).join();
        if (phase == 0) { forest = pos; }
        if (++phase == 3) { client.stop(); } else { target = null; sample = null; ticks = 0; }
    }
    private static BlockPos findCave(net.minecraft.server.level.ServerLevel level, BlockPos origin) {
        var center = CubePos.from(origin);
        for (int cx = center.getX() - 1; cx <= center.getX() + 1; cx++) {
            for (int cz = center.getZ() - 1; cz <= center.getZ() + 1; cz++) {
                for (int cy = -1; cy <= 0; cy++) {
                    var cube = ((CubicLevelReader) level).cc_getCube(cx, cy, cz, ChunkStatus.FULL, true);
                    for (int x = 1; x < 31; x++) {
                        for (int z = 1; z < 31; z++) {
                            for (int y = 1; y < 29; y++) {
                                var p = cube.cc_getCubePos().asBlockPos(x, y, z);
                                if (cube.getBlockState(p).isAir() && cube.getBlockState(p.above()).isAir()
                                        && cube.getBlockState(p.above(2)).isAir() && !cube.getBlockState(p.below()).isAir()) { return p; }
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No underground cave at sample area");
    }
}



