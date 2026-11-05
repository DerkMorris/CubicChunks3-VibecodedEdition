package io.github.opencubicchunks.smoke;

import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Measure cube receipt and mesh readiness on a repeatable route through previously unrequested terrain. */
final class StreamingProbe {
    private static final int START_X = 264;
    private static final int ROUTE_Z = -247;
    private static final int LENGTH = 192;
    private static final int LOOK_AHEAD = 16;
    private final int[] heights = new int[LENGTH + LOOK_AHEAD + 1];
    private final Visibility here = new Visibility();
    private final Visibility ahead = new Visibility();
    private CompletableFuture<Void> setup;
    private int samples;
    private double x = START_X;
    private long started;

    void tick(Minecraft client) {
        if (setup == null) {
            var server = client.getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                var level = server.overworld();
                var chunks = level.getChunkSource();
                for (int i = 0; i < heights.length; i++) {
                    heights[i] = chunks.getGenerator().getBaseHeight(START_X + i, ROUTE_Z,
                            Heightmap.Types.WORLD_SURFACE_WG, level, chunks.randomState()) - 1;
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(START_X + .5, heights[0] + 6, ROUTE_Z + .5);
            }, server);
            return;
        }
        if (!setup.isDone()) {
            return;
        }
        setup.join();
        client.player.setYRot(-90);
        client.player.setXRot(30);
        if (samples == 0) {
            if (Math.abs(client.player.getX() - START_X) >= 2 || Math.abs(client.player.getZ() - ROUTE_Z) >= 2) {
                return;
            }
            started = System.nanoTime();
        }
        int offset = (int) x - START_X;
        here.sample(client, surface(offset));
        ahead.sample(client, surface(offset + LOOK_AHEAD));
        samples++;
        if (offset >= LENGTH) {
            System.out.println("CC_STREAM_RESULT samples=" + samples + " missing=" + here.missing + " residentInvisible=" + here.uncompiled
                    + " aheadMissing=" + ahead.missing + " aheadInvisible=" + ahead.uncompiled + " ms=" + (System.nanoTime() - started) / 1_000_000);
            if (here.missing + here.uncompiled == samples) {
                throw new IllegalStateException("No visible terrain along the streaming route");
            }
            client.stop();
            return;
        }
        x += .25;
        client.player.getAbilities().flying = true;
        client.player.setDeltaMovement(0, 0, 0);
        client.player.setPos(x + .5, heights[(int) x - START_X] + 6, ROUTE_Z + .5);
    }

    private BlockPos surface(int offset) {
        return new BlockPos(START_X + offset, heights[offset], ROUTE_Z);
    }

    private static final class Visibility {
        private int missing;
        private int uncompiled;

        private void sample(Minecraft client, BlockPos pos) {
            var cube = CubePos.from(pos);
            var cache = (ClientCubeCache) client.level.getChunkSource();
            if (cache.cc_getCube(cube.getX(), cube.getY(), cube.getZ(), false) == null) {
                missing++;
            } else if (!client.levelRenderer.isSectionCompiledAndVisible(pos, 0)) {
                uncompiled++;
            }
        }
    }
}
