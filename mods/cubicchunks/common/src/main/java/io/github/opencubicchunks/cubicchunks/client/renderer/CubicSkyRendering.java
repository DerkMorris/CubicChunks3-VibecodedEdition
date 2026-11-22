package io.github.opencubicchunks.cubicchunks.client.renderer;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Applies visual depth settings to detached frame state, without changing world lighting. */
public final class CubicSkyRendering {
    private static final double SMOOTHSTEP_CUBIC = 3.0;

    private CubicSkyRendering() {
    }

    public static boolean applies(ClientLevel level) {
        return level != null && ((CanBeCubic) level).cc_isCubic() && level.dimension().equals(Level.OVERWORLD);
    }

    public static float visibility(double cameraY, int startY) {
        double amount = Math.clamp((cameraY - (double) startY + WorldHeightSettings.SKY_FADE_DISTANCE)
                / WorldHeightSettings.SKY_FADE_DISTANCE, 0.0, 1.0);
        return (float) (amount * amount * (SMOOTHSTEP_CUBIC - 2.0 * amount));
    }

    public static void apply(LevelRenderState state, WorldHeightSettings settings, double cameraY) {
        float visibility = visibility(cameraY, settings.skyFadeStartY());
        state.cloudHeight = settings.cloudHeight();
        state.cloudColor = ARGB.color(Math.round(ARGB.alpha(state.cloudColor) * visibility), state.cloudColor);
        var sky = state.skyRenderState;
        if (sky.skybox != DimensionType.Skybox.OVERWORLD || visibility == 1.0F) {
            return;
        }
        if (visibility == 0.0F) {
            sky.skybox = DimensionType.Skybox.NONE;
            return;
        }
        // Attribute probes may share their vectors. Never mutate the originals.
        sky.skyColor = new Vector3f(sky.skyColor).mul(visibility);
        sky.rainBrightness *= visibility;
        sky.starBrightness *= visibility;
        sky.sunriseAndSunsetColor = new Vector4f(sky.sunriseAndSunsetColor).mul(1.0F, 1.0F, 1.0F, visibility);
    }

    public static void fadeBackground(Vector4f color, double cameraY, int startY) {
        float visibility = visibility(cameraY, startY);
        color.mul(visibility, visibility, visibility, 1.0F);
    }
}
