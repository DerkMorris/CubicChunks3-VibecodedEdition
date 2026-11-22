package io.github.opencubicchunks.cubicchunks.test.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.opencubicchunks.cubicchunks.client.renderer.CubicSkyRendering;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.WorldHeightSettings;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class TestCubicSkyRendering extends BaseTest {
    @Test
    void smoothFadeUsesFullHeightWithoutIntegerOverflow() {
        for (int start : new int[] { -30_000_000, -64, 0, 32000, 30_000_000, Integer.MIN_VALUE }) {
            assertEquals(1.0F, CubicSkyRendering.visibility(start, start));
            assertEquals(1.0F, CubicSkyRendering.visibility((double) start + 1000, start));
            assertEquals(0.5F, CubicSkyRendering.visibility((double) start - 32, start));
            assertEquals(0.0F, CubicSkyRendering.visibility((double) start - 64, start));
            assertEquals(0.0F, CubicSkyRendering.visibility((double) start - 1000, start));
            float previous = 0;
            for (int offset = -64; offset <= 0; offset++) {
                float current = CubicSkyRendering.visibility((double) start + offset, start);
                assertTrue(current >= previous);
                previous = current;
            }
        }
    }

    @Test
    void fadesEverySkyElementWithoutMutatingSharedAttributeVectors() {
        var state = frame();
        var originalSky = state.skyRenderState.skyColor;
        var originalSunset = state.skyRenderState.sunriseAndSunsetColor;
        CubicSkyRendering.apply(state, new WorldHeightSettings(32000, 8, -48), -80);
        assertEquals(32000, state.cloudHeight);
        assertEquals(100, ARGB.alpha(state.cloudColor));
        assertEquals(0.5F, state.skyRenderState.rainBrightness);
        assertEquals(0.25F, state.skyRenderState.starBrightness);
        assertEquals(new Vector3f(0.1F, 0.2F, 0.3F), state.skyRenderState.skyColor);
        assertEquals(new Vector4f(1, 0.5F, 0, 0.4F), state.skyRenderState.sunriseAndSunsetColor);
        assertNotSame(originalSky, state.skyRenderState.skyColor);
        assertNotSame(originalSunset, state.skyRenderState.sunriseAndSunsetColor);
        assertEquals(new Vector3f(0.2F, 0.4F, 0.6F), originalSky);
        assertEquals(0.8F, originalSunset.w());
    }

    @Test
    void depthLimitRemovesSkyPassAndCloudsAndBlackensBackground() {
        var state = frame();
        CubicSkyRendering.apply(state, WorldHeightSettings.DEFAULT, -100);
        assertEquals(DimensionType.Skybox.NONE, state.skyRenderState.skybox);
        assertEquals(0, ARGB.alpha(state.cloudColor));
        var fog = new Vector4f(0.5F, 0.7F, 1, 1);
        CubicSkyRendering.fadeBackground(fog, -100, 0);
        assertEquals(new Vector4f(0, 0, 0, 1), fog);
    }

    @Test
    void skyAtAndAboveThresholdKeepsOriginalValues() {
        var state = frame();
        var original = state.skyRenderState.skyColor;
        CubicSkyRendering.apply(state, new WorldHeightSettings(-32000, 8, -48), -48);
        assertEquals(-32000, state.cloudHeight);
        assertSame(original, state.skyRenderState.skyColor);
        assertEquals(200, ARGB.alpha(state.cloudColor));
        assertEquals(1.0F, state.skyRenderState.rainBrightness);
        assertEquals(0.5F, state.skyRenderState.starBrightness);
    }

    @Test
    void handlesEmptyFirstFrameSkyWithoutReadingUninitializedVectors() {
        var state = new LevelRenderState();
        CubicSkyRendering.apply(state, WorldHeightSettings.DEFAULT, -32);
        assertEquals(DimensionType.Skybox.NONE, state.skyRenderState.skybox);
    }

    private static LevelRenderState frame() {
        var state = new LevelRenderState();
        state.cloudColor = ARGB.color(200, -1);
        var sky = state.skyRenderState;
        sky.skybox = DimensionType.Skybox.OVERWORLD;
        sky.skyColor = new Vector3f(0.2F, 0.4F, 0.6F);
        sky.sunriseAndSunsetColor = new Vector4f(1, 0.5F, 0, 0.8F);
        sky.rainBrightness = 1;
        sky.starBrightness = 0.5F;
        return state;
    }
}
