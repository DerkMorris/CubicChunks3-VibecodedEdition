package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

/** Native creative block actions with server light application held back: inspect each newly opened cell immediately. */
final class WorldLightDigProbe {
    private final int surface = Integer.getInteger("cubicchunks.smoke.lightHeight", 32000);
    private final List<BlockPos> cells = new ArrayList<>();
    private CompletableFuture<Void> setup;
    private CompletableFuture<Void> cleanup;
    private CompletableFuture<Boolean> confirmed;
    private LightProbeScene scene;
    private int broken;
    private int heldTicks;
    private int afterDig;
    private long maxBreakNanos;
    private boolean screenshot;

    WorldLightDigProbe() {
        for (int x = 7; x <= 9; x++) {
            for (int z = 8; z <= 9; z++) {
                for (int depth = 0; depth < 3; depth++) {
                    cells.add(new BlockPos(x, surface - depth, z));
                }
            }
        }
    }

    void tick(Minecraft client) {
        if (cleanup != null) {
            if (!cleanup.isDone()) return;
            cleanup.join();
            System.out.println("CC_LIGHT_DIG_COMPLETE cells=" + broken + " heldTicks=" + heldTicks + " maxBreakMicros=" + maxBreakNanos / 1000
                    + " renderer=" + (FabricLoader.getInstance().isModLoaded("sodium") ? "Sodium" : "vanilla") + " testBypass=false");
            client.stop();
            return;
        }
        if (setup == null) {
            var server = client.getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                var level = server.overworld();
                scene = LightProbeScene.retain(level, new BlockPos(5, surface - 4, 6), new BlockPos(12, surface, 13));
                for (int x = 5; x <= 12; x++) {
                    for (int z = 6; z <= 13; z++) {
                        for (int y = surface - 4; y <= surface; y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            level.setBlockAndUpdate(pos, (y == surface ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState());
                        }
                    }
                }
                server.getPlayerList().getPlayers().getFirst().teleportTo(8.5, surface + 1, 10.5);
            }, server);
            return;
        }
        if (!setup.isDone()) { return; }
        setup.join();
        var cache = (ClientCubeCache) client.level.getChunkSource();
        if (broken == 0 && (cells.stream().anyMatch(pos -> !cache.cc_isCubeLightReady(CubePos.from(pos)) || client.level.getBlockState(pos).isAir())
                || !RenderGeometryChecks.hasVisibleGeometryNear(client, surface, 48))) { return; }
        client.player.setYRot(180);
        client.player.setXRot(55);
        if (broken < cells.size()) {
            WorldLightDelay.arm();
            BlockPos pos = cells.get(broken);
            String before = LightProbeDiagnostics.column(client, pos);
            long start = System.nanoTime();
            if (!client.gameMode.startDestroyBlock(pos, Direction.UP) || !client.level.getBlockState(pos).isAir()) {
                throw new IllegalStateException("Native creative prediction did not break " + pos);
            }
            assertLit(client, pos, before);
            maxBreakNanos = Math.max(maxBreakNanos, System.nanoTime() - start);
            broken++;
        } else if (++afterDig == 10) {
            Screenshot.grab(client.gameDirectory, "light-prediction-dig.png", client.gameRenderer.mainRenderTarget(), 1, message -> {
                screenshot = true;
                System.out.println("CC_LIGHT_DIG_SCREENSHOT " + message.getString());
            });
        }
        for (int i = 0; i < broken; i++) {
            assertLit(client, cells.get(i), null);
        }
        if (WorldLightDelay.active() && !cache.cc_isCubeLightReady(CubePos.from(cells.getFirst()))) { heldTicks++; }
        if (broken != cells.size() || WorldLightDelay.active() || !screenshot) { return; }
        if (confirmed == null) {
            var server = client.getSingleplayerServer();
            confirmed = CompletableFuture.supplyAsync(() -> cells.stream().allMatch(pos -> scene.getBlockState(pos).isAir()
                    && server.overworld().getBrightness(LightLayer.SKY, pos) == 15), server);
        } else if (confirmed.isDone()) {
            if (!confirmed.join()) { confirmed = null; return; }
            if (cells.stream().map(CubePos::from).distinct().anyMatch(pos -> !cache.cc_isCubeLightReady(pos))) { return; }
            if (heldTicks == 0) { throw new IllegalStateException("Did not observe a pending server light interval"); }
            if (CubicChunks.IS_IN_TEST) { throw new IllegalStateException("Light dig probe used test bypass"); }
            cleanup = CompletableFuture.runAsync(scene::close, client.getSingleplayerServer());
        }
    }

    private static void assertLit(Minecraft client, BlockPos pos, @Nullable String before) {
        if (!client.level.getBlockState(pos).isAir() || client.level.getBrightness(LightLayer.SKY, pos) != 15) {
            throw new IllegalStateException("Newly excavated cell went dark or reappeared before server light: " + pos
                    + " block=" + client.level.getBlockState(pos) + " sky=" + client.level.getBrightness(LightLayer.SKY, pos)
                    + " beforeAction=" + before + " current=" + LightProbeDiagnostics.column(client, pos));
        }
    }
}
