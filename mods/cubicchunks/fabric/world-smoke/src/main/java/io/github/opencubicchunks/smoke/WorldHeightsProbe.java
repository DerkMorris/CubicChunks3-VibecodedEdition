package io.github.opencubicchunks.smoke;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientWorldSettings;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.attribute.EnvironmentAttributes;

/** Checks the actual extracted frame after normal login/teleports, including the mixed fog renderer. */
final class WorldHeightsProbe {
    private static final WorldHeightSettings HEIGHTS = new WorldHeightSettings(32000, -96, -48);
    private static final int[] CAMERA_Y = { -32, -48, -80, -112, -144 };
    private static final float[] VISIBILITY = { 1, 1, 0.5F, 0, 0 };
    private CompletableFuture<Void> movement;
    private long deadline;
    private int stage;
    private int settledTicks;
    private boolean finished;

    static void configureCreation(boolean reopen) {
        CubicChunks.config().setDeepCaveSettings(reopen ? DeepCaveSettings.CLASSIC
                : new DeepCaveSettings(false, 64, 45, 35, Map.of(), Map.of(), HEIGHTS));
    }

    void tick(Minecraft client, boolean reopen) {
        if (finished) return;
        try {
            if (deadline == 0) deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
            require(System.nanoTime() < deadline, "timeout stage=" + stage);
            if (movement == null) {
                move(client);
                return;
            }
            if (!movement.isDone()) return;
            movement.join();
            if (!client.player.isSpectator() || client.gui.screen() != null) return;
            var frame = client.gameRenderer.gameRenderState().levelRenderState;
            double cameraY = frame.cameraRenderState.pos.y;
            if (Math.abs(cameraY - CAMERA_Y[stage]) > 0.02) {
                settledTicks = 0;
                return;
            }
            if (++settledTicks < 20) return;
            verify(client, cameraY);
            System.out.println("CC_WORLD_HEIGHTS_PHASE reopen=" + reopen + " cameraY=" + cameraY + " cloudHeight=" + frame.cloudHeight
                    + " visibility=" + VISIBILITY[stage] + " skybox=" + frame.skyRenderState.skybox
                    + " cloudAlpha=" + ARGB.alpha(frame.cloudColor) + " fog=" + frame.cameraRenderState.fogData.color);
            if (++stage == CAMERA_Y.length) {
                finished = true;
                System.out.println("CC_WORLD_HEIGHTS_COMPLETE reopen=" + reopen
                        + " phases=5 savedSettings=true serverSync=true actualFrame=true actualFog=true testBypass=false");
                client.stop();
            } else {
                movement = null;
                settledTicks = 0;
            }
        } catch (Throwable failure) {
            finished = true;
            System.out.println("CC_WORLD_HEIGHTS_FAIL reopen=" + reopen + " stage=" + stage + " reason=" + failure);
            failure.printStackTrace();
            client.stop();
        }
    }

    private void move(Minecraft client) {
        require(!CubicChunks.IS_IN_TEST, "test bypass enabled");
        // Deliberately disagree with the saved server settings. Rendering must use the configuration payload.
        CubicChunks.config().setDeepCaveSettings(DeepCaveSettings.CLASSIC);
        var server = client.getSingleplayerServer();
        int target = CAMERA_Y[stage];
        movement = CompletableFuture.runAsync(() -> {
            var level = server.overworld();
            require(HEIGHTS.equals(((CubicWorldData) level.getLevelData()).cc_deepCaveSettings().heights()), "saved server settings differ");
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SPECTATOR);
            server.setWeatherParameters(10000, 0, false, false);
            player.teleportTo(0.5, target - player.getEyeHeight(), 0.5);
        }, server);
    }

    private void verify(Minecraft client, double cameraY) {
        require(HEIGHTS.equals(CubicClientWorldSettings.heights(client.level)), "client settings differ from server");
        var frame = client.gameRenderer.gameRenderState().levelRenderState;
        var sky = frame.skyRenderState;
        float visibility = VISIBILITY[stage];
        require(frame.cloudHeight == HEIGHTS.cloudHeight(), "cloud altitude did not reach extracted frame");
        var cloud = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, frame.worldPartialTicks);
        int expectedAlpha = Math.round(ARGB.alpha(ARGB.colorFromVector4f(cloud)) * visibility);
        require(Math.abs(ARGB.alpha(frame.cloudColor) - expectedAlpha) <= 1, "cloud alpha did not fade");
        if (visibility == 0) {
            require(sky.skybox == DimensionType.Skybox.NONE, "sky pass still enabled underground");
            var fog = frame.cameraRenderState.fogData.color;
            require(fog.x == 0 && fog.y == 0 && fog.z == 0, "clear background not black underground");
        } else {
            require(sky.skybox == DimensionType.Skybox.OVERWORLD, "sky disabled before fade ended");
            require(Math.abs(sky.rainBrightness - visibility) < 0.02, "sun/moon visibility incorrect");
            var color = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, frame.worldPartialTicks);
            require(Math.abs(sky.skyColor.x() - color.x() * visibility) < 0.02
                    && Math.abs(sky.skyColor.y() - color.y() * visibility) < 0.02
                    && Math.abs(sky.skyColor.z() - color.z() * visibility) < 0.02, "sky color did not fade");
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
