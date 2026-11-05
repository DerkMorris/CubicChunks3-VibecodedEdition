package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLayerLightListener;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

class TestCubeLayerLightListener extends BaseTest {
    @Test
    void actualEngineReadsCurrentPublishedViewsAndDropsInvalidatedValues() throws Exception {
        Source source = new Source(true);
        source.views = Map.of(CubePos.ZERO, exact(CubePos.ZERO, 8, 15));
        LevelLightEngine engine = new LevelLightEngine(source, true, true);
        BlockAndLightGetter level = mock(BlockAndLightGetter.class, CALLS_REAL_METHODS);
        when(level.getLightEngine()).thenReturn(engine);
        assertEquals(8, level.getBrightness(LightLayer.BLOCK, BlockPos.ZERO));
        assertEquals(15, level.getBrightness(LightLayer.SKY, BlockPos.ZERO));
        assertEquals(15, level.getRawBrightness(BlockPos.ZERO, 0));
        assertEquals(8, level.getRawBrightness(BlockPos.ZERO, 10));
        assertTrue(level.canSeeSky(BlockPos.ZERO));
        assertFalse(engine.lightOnInColumn(0L), "A ready bounded cube does not certify an entire vertical column");
        assertEquals(8,
                CompletableFuture.supplyAsync(() -> engine.getLayerListener(LightLayer.BLOCK).getLightValue(BlockPos.ZERO)).get(5, TimeUnit.SECONDS));
        source.views = Map.of();
        assertEquals(0, level.getBrightness(LightLayer.BLOCK, BlockPos.ZERO));
        assertEquals(0, level.getRawBrightness(BlockPos.ZERO, 0));
        assertFalse(level.canSeeSky(BlockPos.ZERO));
        assertNull(engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(0, 0, 0)));
    }

    @Test
    void actualRawReadCombinesRangesBeforeApplyingTheDarkFallback() {
        ProtoCube cube = TestBoundedCubeLightSolver.cube(CubePos.ZERO);
        TestBoundedCubeLightSolver.set(cube, 16, 16, 16, Blocks.GLOWSTONE.defaultBlockState());
        Source source = new Source(true);
        source.views = Map.of(CubePos.ZERO, TestCubeLightView.fromCube(cube, CubeLightBoundary.UNKNOWN));
        LevelLightEngine engine = new LevelLightEngine(source, true, true);
        BlockPos pos = new BlockPos(16, 16, 15);
        assertEquals(14, engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos));
        assertEquals(0, engine.getLayerListener(LightLayer.SKY).getLightValue(pos));
        assertEquals(0, engine.getRawBrightness(pos, 0), "The unresolved combined range must not be presented as exact block light");
        assertEquals(14, engine.getRawBrightness(pos, 1), "Darkening makes the known block light dominate the entire sky range");
        assertNull(engine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(pos)));
    }

    @Test
    void disabledLayersRemainZeroAndOrdinaryWorldsRetainVanillaListeners() {
        Source source = new Source(true);
        source.views = Map.of(CubePos.ZERO, exact(CubePos.ZERO, 6, 13));
        for (boolean block : new boolean[] { false, true }) {
            for (boolean sky : new boolean[] { false, true }) {
                LevelLightEngine engine = new LevelLightEngine(source, block, sky);
                assertEquals(block ? 6 : 0, engine.getLayerListener(LightLayer.BLOCK).getLightValue(BlockPos.ZERO));
                assertEquals(sky ? 13 : 0, engine.getLayerListener(LightLayer.SKY).getLightValue(BlockPos.ZERO));
                assertEquals(Math.max(block ? 6 : 0, sky ? 9 : 0), engine.getRawBrightness(BlockPos.ZERO, 4));
                assertEquals(Math.max(block ? 6 : 0, sky ? 14 : 0), engine.getRawBrightness(BlockPos.ZERO, -1));
            }
        }
        Source ordinary = new Source(false);
        ordinary.views = source.views;
        LevelLightEngine vanilla = new LevelLightEngine(ordinary, true, true);
        LayerLightEventListener original = vanilla.getLayerListener(LightLayer.SKY);
        assertFalse(original instanceof CubeLayerLightListener);
        when(((CanBeCubic) ordinary.level).cc_isCubic()).thenReturn(true);
        assertEquals(13, vanilla.getLayerListener(LightLayer.SKY).getLightValue(BlockPos.ZERO));
        when(((CanBeCubic) ordinary.level).cc_isCubic()).thenReturn(false);
        assertSame(original, vanilla.getLayerListener(LightLayer.SKY));
        assertSame(LayerLightEventListener.DummyLightLayerEventListener.INSTANCE, LevelLightEngine.EMPTY.getLayerListener(LightLayer.BLOCK));
        assertEquals(0, LevelLightEngine.EMPTY.getRawBrightness(BlockPos.ZERO, 0));
    }

    @Test
    void sectionAndBlockReadsKeepExtremeYAndVanillaPackedAliasesDistinct() {
        Source source = new Source(true);
        Map<CubePos, CubeLightView> views = new HashMap<>();
        CubePos[] positions = { CubePos.ZERO, CubePos.of(0, 1 << 19, 0),
            CubePos.of(-CubePos.MAX_COORDINATE_VALUE - 1, -CubePos.MAX_COORDINATE_VALUE - 1, -CubePos.MAX_COORDINATE_VALUE - 1),
            CubePos.of(CubePos.MAX_COORDINATE_VALUE, CubePos.MAX_COORDINATE_VALUE, CubePos.MAX_COORDINATE_VALUE) };
        for (int index = 0; index < positions.length; index++) {
            views.put(positions[index], exact(positions[index], index + 3, index + 9));
        }
        source.views = Map.copyOf(views);
        LevelLightEngine engine = new LevelLightEngine(source, true, true);
        for (int index = 0; index < positions.length; index++) {
            CubePos pos = positions[index];
            assertEquals(index + 3, engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos.asBlockPos()));
            for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                SectionPos sectionPos = SectionPos.of(Coords.cubeToSection(pos.getX(), Coords.indexToX(section)),
                        Coords.cubeToSection(pos.getY(), Coords.indexToY(section)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(section)));
                assertEquals(index + 3, engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos).get(0, 0, 0));
                assertEquals(index + 9, engine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos).get(15, 15, 15));
            }
        }
    }

    @Test
    void listenerMutationMethodsCannotAlterTheReadOnlyPublication() {
        CubeLightView view = exact(CubePos.ZERO, 4, 9);
        CubeLayerLightListener listener = new CubeLayerLightListener(pos -> pos.equals(CubePos.ZERO) ? view : null, LightLayer.BLOCK);
        SectionPos section = SectionPos.of(0, 0, 0);
        DataLayer exported = listener.getDataLayerData(section);
        exported.set(0, 0, 0, 15);
        listener.checkBlock(BlockPos.ZERO);
        listener.updateSectionStatus(section, true);
        listener.updateSectionStatus(BlockPos.ZERO, false);
        listener.setLightEnabled(new ChunkPos(0, 0), false);
        listener.propagateLightSources(new ChunkPos(0, 0));
        assertFalse(listener.hasLightWork());
        assertEquals(0, listener.runLightUpdates());
        assertEquals(4, listener.getLightValue(BlockPos.ZERO));
        assertEquals(4, listener.getDataLayerData(section).get(0, 0, 0));
        CubeLayerLightListener foreign = new CubeLayerLightListener(pos -> exact(CubePos.of(1, 0, 0), 15, 15), LightLayer.BLOCK);
        assertEquals(0, foreign.getLightValue(BlockPos.ZERO));
        assertNull(foreign.getDataLayerData(section));
    }

    private static CubeLightView exact(CubePos pos, int block, int sky) {
        return CubeLightView.fromExact(pos, TestCubeLightView.filled(block), TestCubeLightView.filled(sky));
    }

    private static final class Source implements LightChunkGetter, CubeLightProvider {
        private final ServerLevel level;
        private volatile Map<CubePos, CubeLightView> views = Map.of();

        private Source(boolean cubic) {
            this.level = mock(ServerLevel.class);
            Misc.setupLevelHeight(this.level, -64, 384);
            when(((CanBeCubic) this.level).cc_isCubic()).thenReturn(cubic);
        }

        @Override public LightChunk getChunkForLighting(int x, int z) {
            return null;
        }

        @Override public BlockGetter getLevel() {
            return this.level;
        }

        @Override public CubeLightView cc_getCubeLightView(CubePos pos) {
            return this.views.get(pos);
        }
    }
}
