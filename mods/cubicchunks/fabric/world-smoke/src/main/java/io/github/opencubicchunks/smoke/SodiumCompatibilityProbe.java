package io.github.opencubicchunks.smoke;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Real Sodium meshes, empty-section edits, light, vertical eviction and renderer recreation. Never packaged. */
final class SodiumCompatibilityProbe {
    private static final int[] HEIGHTS = {96, 32000, -32000, 96};
    private static final Field MANAGER = field(SodiumWorldRenderer.class, "renderSectionManager");
    private static final Method SECTION = sectionMethod();
    private int stage;
    private int step;
    private int settled;
    private int waitTicks;
    private long deadline;
    private CompletableFuture<Void> mutation;
    private RenderSectionManager beforeReload;
    private volatile boolean screenshotDone;
    private boolean screenshotRequested;

    void tick(Minecraft client) {
        try {
            if (deadline == 0) {
                require(!CubicChunks.IS_IN_TEST && ((CanBeCubic) client.level).cc_isCubic(), "Not a real cubic world");
                startStage(client);
            }
            require(System.nanoTime() < deadline, "Timed out at height=" + height() + " step=" + step);
            if (!mutation.isDone()) return;
            mutation.join();
            if (Math.abs(client.player.getY() - (height() + 4.5)) > 0.1 || client.gui.screen() != null) return;
            client.player.setDeltaMovement(0, 0, 0);
            client.player.setYRot(180);
            client.player.setXRot(20);
            var renderer = SodiumWorldRenderer.instance();
            var manager = manager(renderer);
            var target = section(manager, height());
            var floor = section(manager, height() - 1);
            var cache = (ClientCubeCache) client.level.getChunkSource();
            boolean lit = cache.cc_isCubeLightReady(CubePos.from(marker()));
            boolean floorVisible = geometry(floor) && renderer.getVisibleChunkCount() > 0;
            if (++waitTicks % 100 == 0) {
                BlockPos floorPos = new BlockPos(8, height() - 1, 8);
                CubePos floorCube = CubePos.from(floorPos);
                System.out.println("CC_SODIUM_WAIT height=" + height() + " step=" + step + " target=" + flags(target)
                        + " floor=" + flags(floor) + " floorMissing=" + (floor == null)
                        + " floorBuilt=" + (floor != null && floor.isBuilt()) + " floorPending=" + (floor == null ? -1 : floor.getPendingUpdate())
                        + " floorBlock=" + client.level.getBlockState(floorPos)
                        + " floorCubePresent=" + (cache.cc_getCube(floorCube.getX(), floorCube.getY(), floorCube.getZ(), false) != null)
                        + " floorLightReady=" + cache.cc_isCubeLightReady(floorCube)
                        + " lightReady=" + lit + " visible=" + renderer.getVisibleChunkCount()
                        + " totalSections=" + manager.getTotalSections() + " debug=" + manager.getChunksDebugString());
            }
            if (!lit || !floorVisible || target == null || !target.isBuilt()) return;
            if (stage > 0 && section(manager, HEIGHTS[stage - 1]) != null) return;
            switch (step) {
                case 0 -> {
                    if (geometry(target) || !client.level.getBlockState(marker()).isAir()) return;
                    if (++settled < 20) return;
                    System.out.println("CC_SODIUM_EMPTY height=" + height() + " floorGeometry=true visible=" + renderer.getVisibleChunkCount());
                    mutateMarker(client, true);
                    step = 1;
                }
                case 1 -> {
                    if (!geometry(target) || !client.level.getBlockState(marker()).is(Blocks.SEA_LANTERN)
                            || client.level.getBrightness(LightLayer.BLOCK, marker().above()) != 14) return;
                    if (!screenshotRequested) {
                        screenshotRequested = true;
                        Screenshot.grab(client.gameDirectory, "sodium-" + stage + "-" + height() + ".png",
                                client.gameRenderer.mainRenderTarget(), 1, message -> {
                                    screenshotDone = true;
                                    System.out.println("CC_SODIUM_SCREENSHOT " + message.getString());
                                });
                    }
                    if (!screenshotDone) return;
                    System.out.println("CC_SODIUM_ADDED height=" + height() + " geometry=true blockLight=14 visible="
                            + renderer.getVisibleChunkCount());
                    beforeReload = manager;
                    renderer.reload();
                    step = 2;
                }
                case 2 -> {
                    require(manager != beforeReload, "Renderer reload retained the old manager");
                    if (!geometry(target) || client.level.getBrightness(LightLayer.BLOCK, marker().above()) != 14) return;
                    System.out.println("CC_SODIUM_RELOADED height=" + height() + " geometry=true visible=" + renderer.getVisibleChunkCount());
                    mutateMarker(client, false);
                    step = 3;
                }
                case 3 -> {
                    if (geometry(target) || !client.level.getBlockState(marker()).isAir()
                            || client.level.getBrightness(LightLayer.BLOCK, marker().above()) != 0) return;
                    System.out.println("CC_SODIUM_REMOVED height=" + height() + " geometry=false blockLight=0");
                    if (++stage == HEIGHTS.length) {
                        System.out.println("CC_SODIUM_COMPLETE heights=96,32000,-32000,96 meshes=true emptyEdits=true light=true reload=true eviction=true testBypass=false");
                        client.stop();
                    } else {
                        startStage(client);
                    }
                }
                default -> throw new IllegalStateException("Unknown Sodium probe step " + step);
            }
        } catch (Throwable failure) {
            System.out.println("CC_SODIUM_FAILED stage=" + stage + " step=" + step + " reason=" + failure);
            throw new IllegalStateException("Sodium compatibility probe failed", failure);
        }
    }

    private void startStage(Minecraft client) {
        step = 0;
        settled = 0;
        waitTicks = 0;
        screenshotRequested = false;
        screenshotDone = false;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
        int y = height();
        boolean prepare = stage < 3;
        var server = client.getSingleplayerServer();
        mutation = CompletableFuture.runAsync(() -> {
            var level = server.overworld();
            if (prepare) {
                // Clear exactly one section so an empty -> nonempty -> empty rebuild cannot pass on unrelated terrain.
                for (int cubeY = Math.floorDiv(y - 1, 32); cubeY <= Math.floorDiv(y + 15, 32); cubeY++) {
                    ((CubicLevelReader) level).cc_getCube(0, cubeY, 0, ChunkStatus.FULL, true);
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                        for (int dy = 0; dy < 16; dy++) {
                            level.setBlockAndUpdate(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(8.5, y + 4.5, 13.5);
            server.setWeatherParameters(10000, 0, false, false);
        }, server);
        System.out.println("CC_SODIUM_STAGE height=" + y + " revisit=" + !prepare);
    }

    private void mutateMarker(Minecraft client, boolean placed) {
        var server = client.getSingleplayerServer();
        BlockPos pos = marker();
        mutation = CompletableFuture.runAsync(() -> server.overworld().setBlockAndUpdate(pos,
                (placed ? Blocks.SEA_LANTERN : Blocks.AIR).defaultBlockState()), server);
    }

    private int height() { return HEIGHTS[stage]; }

    private BlockPos marker() { return new BlockPos(8, height() + 3, 8); }

    private static int flags(RenderSection section) {
        return section == null ? 0 : section.getRegion().getSectionFlags(section.getSectionIndex());
    }

    private static boolean geometry(RenderSection section) {
        return (flags(section) & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) != 0;
    }

    // Reflection is confined to this development probe; production compatibility does not depend on these test helpers.
    private static RenderSectionManager manager(SodiumWorldRenderer renderer) throws ReflectiveOperationException {
        return (RenderSectionManager) MANAGER.get(renderer);
    }

    private static RenderSection section(RenderSectionManager manager, int blockY) throws ReflectiveOperationException {
        return (RenderSection) SECTION.invoke(manager, 0, Math.floorDiv(blockY, 16), 0);
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method sectionMethod() {
        try {
            Method method = RenderSectionManager.class.getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
