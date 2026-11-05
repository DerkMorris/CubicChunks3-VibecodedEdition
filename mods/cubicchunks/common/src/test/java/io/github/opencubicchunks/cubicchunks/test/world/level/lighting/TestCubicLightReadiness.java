package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

class TestCubicLightReadiness extends BaseTest {
    @Test
    void unimplementedCubeLightingCannotReportOpenSkyOrFullBrightness() {
        ServerLevel level = level(true);
        LevelLightEngine engine = engine(level);
        BlockAndLightGetter view = mock(BlockAndLightGetter.class, CALLS_REAL_METHODS);
        when(view.getLightEngine()).thenReturn(engine);
        for (int y : new int[] { -1000000, -64, 0, 320, 1000000 }) {
            BlockPos pos = new BlockPos(7, y, -9);
            assertEquals(0, view.getRawBrightness(pos, 0));
            assertFalse(view.canSeeSky(pos));
            for (LightLayer layer : LightLayer.values()) {
                assertEquals(0, view.getBrightness(layer, pos));
                assertNull(engine.getLayerListener(layer).getDataLayerData(SectionPos.of(pos)));
            }
        }
        assertFalse(engine.lightOnInColumn(0L));
    }

    @Test
    void ordinaryWorldsKeepTheirOriginalLayerListenersAndBrightness() {
        ServerLevel level = level(false);
        LevelLightEngine engine = engine(level);
        LayerLightEventListener sky = engine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener block = engine.getLayerListener(LightLayer.BLOCK);
        assertNotSame(LayerLightEventListener.DummyLightLayerEventListener.INSTANCE, sky);
        assertNotSame(LayerLightEventListener.DummyLightLayerEventListener.INSTANCE, block);
        BlockPos pos = new BlockPos(3, 400, -2);
        int darkening = 4;
        assertEquals(Math.max(block.getLightValue(pos), sky.getLightValue(pos) - darkening), engine.getRawBrightness(pos, darkening));

        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        assertSame(LayerLightEventListener.DummyLightLayerEventListener.INSTANCE, engine.getLayerListener(LightLayer.SKY));
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
        assertSame(sky, engine.getLayerListener(LightLayer.SKY));
        assertSame(block, engine.getLayerListener(LightLayer.BLOCK));
    }

    @Test
    void emptyEngineDoesNotRequireACubicWorldAccessor() {
        assertEquals(0, LevelLightEngine.EMPTY.getRawBrightness(BlockPos.ZERO, 0));
        assertSame(LayerLightEventListener.DummyLightLayerEventListener.INSTANCE, LevelLightEngine.EMPTY.getLayerListener(LightLayer.SKY));
    }

    private static ServerLevel level(boolean cubic) {
        ServerLevel level = mock(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
        return level;
    }

    private static LevelLightEngine engine(ServerLevel level) {
        LightChunkGetter getter = mock(LightChunkGetter.class);
        when(getter.getLevel()).thenReturn(level);
        return new LevelLightEngine(getter, true, true);
    }
}
