package io.github.opencubicchunks.smoke;

import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CubeTerrainGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Real client/server lighting, live source/roof changes and extended-coordinate teleport/reopen checks. Never shipped. */
final class WorldLightProbe {
    private static final int HIGH = Integer.getInteger("cubicchunks.smoke.lightHeight", 32000000);
    private static final int LOW = -HIGH;
    private final boolean reopen;
    private int phase = -1;
    private CompletableFuture<?> setup;
    private CompletableFuture<Sample> pending;
    private BlockPos sample;
    private int expectedBlock;
    private int expectedSky;
    private int pendingDisplayChecks;
    private volatile long changedAt;
    private long serverLightMs;
    private long clientLightMs;

    WorldLightProbe(boolean reopen) { this.reopen = reopen; }

    void tick(Minecraft client, int ticks) {
        if (phase == -1) {
            advance(client, 0);
        }
        checkDisplayContinuity(client);
        if (!setup.isDone()) { return; }
        setup.join();
        if (pending != null && pending.isDone()) {
            Sample server = pending.join();
            pending = null;
            ClientCubeCache cache = (ClientCubeCache) client.level.getChunkSource();
            int clientBlock = client.level.getBrightness(LightLayer.BLOCK, sample);
            int clientSky = client.level.getBrightness(LightLayer.SKY, sample);
            long localSections = client.levelRenderer.visibleSections().stream()
                    .filter(section -> Math.abs((long) section.getRenderOrigin().getY() - sample.getY()) <= 64)
                    .filter(section -> section.getSectionMesh().hasRenderableLayers()).count();
            boolean rendered = !Boolean.getBoolean("cubicchunks.smoke.lightRender") || localSections > 0;
            if (serverLightMs < 0 && server.ready && server.block == expectedBlock && server.sky == expectedSky) {
                serverLightMs = (System.nanoTime() - changedAt) / 1_000_000;
            }
            if (clientLightMs < 0 && cache.cc_isCubeLightReady(CubePos.from(sample)) && clientBlock == expectedBlock && clientSky == expectedSky) {
                clientLightMs = (System.nanoTime() - changedAt) / 1_000_000;
            }
            if (server.ready && cache.cc_isCubeLightReady(CubePos.from(sample)) && server.block == expectedBlock && server.sky == expectedSky
                    && clientBlock == expectedBlock && clientSky == expectedSky && rendered && server.neighborsReady
                    && (phase != 7 || client.level.getBlockState(sample).is(Blocks.STONE))) {
                System.out.println("CC_WORLD_LIGHT_OK phase=" + phase + " sample=" + sample + " block=" + clientBlock + " sky=" + clientSky
                        + " localRender=" + localSections + " reopen=" + reopen + " latencyMs=" + (System.nanoTime() - changedAt) / 1_000_000
                        + " serverLightMs=" + serverLightMs + " clientLightMs=" + clientLightMs);
                if (phase == 9 || reopen && phase == 5) {
                    if (Boolean.getBoolean("cubicchunks.smoke.lightContinuity") && pendingDisplayChecks == 0) {
                        throw new IllegalStateException("Continuity test did not observe pending lighting");
                    }
                    System.out.println("CC_WORLD_LIGHT_DISPLAY checksWhilePending=" + pendingDisplayChecks);
                    if (WorldLightPacketChecks.initialPackets == 0) {
                        throw new IllegalStateException("Initial-light check did not inspect any cube packets");
                    }
                    System.out.println("CC_WORLD_LIGHT_INITIAL packetsWithImmediateLight=" + WorldLightPacketChecks.initialPackets);
                    System.out.println("CC_WORLD_LIGHT_COMPLETE reopen=" + reopen);
                    client.stop();
                    return;
                }
                advance(client, reopen && phase == 1 ? 5 : phase + 1);
                return;
            }
            if (ticks % 100 < 20) {
                System.out.println("CC_WORLD_LIGHT_WAIT phase=" + phase + " server=" + server + " client=" + clientBlock + "," + clientSky);
            }
        }
        if (pending == null) {
            var server = client.getSingleplayerServer();
            BlockPos point = sample;
            pending = CompletableFuture.supplyAsync(() -> {
                var level = server.overworld();
                var cache = (io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider) level.getChunkSource();
                var view = cache.cc_getCubeLightView(CubePos.from(point));
                return new Sample(view != null && view.isReady(), level.getBrightness(LightLayer.BLOCK, point),
                        level.getBrightness(LightLayer.SKY, point), neighborsReady(level, point),
                        ((CubicServerLevel) level).cc_getCubeLightStatus().join().toString());
            }, server);
        }
    }

    private static boolean neighborsReady(ServerLevel level, BlockPos point) {
        CubePos center = CubePos.from(point);
        var map = (io.github.opencubicchunks.cubicchunks.server.level.CubeHolder.PlayerProvider) level.getChunkSource().chunkMap;
        var light = (io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider) level.getChunkSource();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -2; y <= 2; y++) {
                    CubePos pos = CubePos.of(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!map.cc_getPlayers(pos, false).isEmpty() && light.cc_getCubeLightView(pos) == null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void checkDisplayContinuity(Minecraft client) {
        if (!Boolean.getBoolean("cubicchunks.smoke.lightContinuity") || phase != 2) {
            return;
        }
        ClientCubeCache cache = (ClientCubeCache) client.level.getChunkSource();
        CubePos pos = CubePos.from(sample);
        if (cache.cc_getVisibleCubeLightView(pos) == null || client.level.getBrightness(LightLayer.BLOCK, sample) != 14) {
            throw new IllegalStateException("Unchanged lamp went dark while roof lighting was being recalculated");
        }
        for (int section = 0; section < io.github.opencubicchunks.cc_core.api.CubicConstants.SECTION_COUNT; section++) {
            var at = io.github.opencubicchunks.cc_core.utils.Coords.sectionPosByIndex(pos, section);
            if (client.level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(at) == null
                    || client.level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(at) == null) {
                throw new IllegalStateException("Section lighting disappeared during relighting");
            }
        }
        if (!cache.cc_isCubeLightReady(pos)) {
            pendingDisplayChecks++;
        }
    }

    private void advance(Minecraft client, int next) {
        phase = next;
        serverLightMs = -1;
        clientLightMs = -1;
        if (next == 2 && Boolean.getBoolean("cubicchunks.smoke.lightContinuity")) {
            WorldLightDelay.arm();
        }
        var server = client.getSingleplayerServer();
        if (next == 0) {

            // The saved 7x7 platform far above this point shades it after reopening, even while its cube is unloaded.
            expectedBlock = 0; expectedSky = reopen ? 11 : 15;
        } else if (next < 5) {
            sample = new BlockPos(9, HIGH + 1, 8);
            expectedBlock = next <= 2 && !reopen ? 14 : 0;
            expectedSky = next == 2 || next == 3 ? 0 : 15;
        } else {
            sample = new BlockPos(9, LOW + 1, 8);
            expectedBlock = (next == 5 && !reopen || next == 8) ? 14 : 0; expectedSky = 0;
        }
        setup = CompletableFuture.runAsync(() -> {
            ServerLevel level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            if (next == 0) {
                sample = new BlockPos(8, level.getMaxY() + 1, 8);
                player.teleportTo(10.5, sample.getY(), 10.5);
            } else if (next == 1) {
                ensure(level, new BlockPos(8, HIGH, 8));
                if (!reopen) {
                    fill(level, 5, 11, HIGH, HIGH, 5, 11, false);
                    level.setBlockAndUpdate(new BlockPos(8, HIGH + 1, 8), Blocks.GLOWSTONE.defaultBlockState());
                }
                player.teleportTo(10.5, HIGH + 1, 10.5);
            } else if (next == 2 || next == 4) {
                fill(level, -8, 24, HIGH + 5, HIGH + 5, -8, 24, next == 4);
            } else if (next == 3) {
                level.setBlockAndUpdate(new BlockPos(8, HIGH + 1, 8), Blocks.AIR.defaultBlockState());
            } else if (next == 5) {
                ensure(level, new BlockPos(8, LOW, 8));
                if (!reopen) {
                    fill(level, 5, 12, LOW + 1, LOW + 4, 5, 12, true);
                    level.setBlockAndUpdate(new BlockPos(8, LOW + 1, 8), Blocks.GLOWSTONE.defaultBlockState());
                }
                player.teleportTo(10.5, LOW + 1, 10.5);
            } else if (next == 6 || next == 9) {
                level.setBlockAndUpdate(new BlockPos(8, LOW + 1, 8), Blocks.AIR.defaultBlockState());
            } else if (next == 7) {
                level.setBlockAndUpdate(new BlockPos(8, LOW + 1, 8), Blocks.GLOWSTONE.defaultBlockState());
                level.setBlockAndUpdate(sample, Blocks.STONE.defaultBlockState());
            } else {
                level.setBlockAndUpdate(sample, Blocks.AIR.defaultBlockState());
            }
            changedAt = System.nanoTime();
        }, server);
    }

    private static void ensure(ServerLevel level, BlockPos pos) {
        CubePos cube = CubePos.from(pos);
        ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
    }

    private static void fill(ServerLevel level, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, boolean air) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    ensure(level, pos);
                    level.setBlockAndUpdate(pos, (air ? Blocks.AIR : Blocks.STONE).defaultBlockState());
                }
            }
        }
    }

    private record Sample(boolean ready, int block, int sky, boolean neighborsReady, String status) {}
}

