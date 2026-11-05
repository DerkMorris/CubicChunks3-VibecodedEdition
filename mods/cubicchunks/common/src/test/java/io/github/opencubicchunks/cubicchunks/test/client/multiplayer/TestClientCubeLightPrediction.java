package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

/** Real transformed cache and block setter: predict before returning from a break, with no server lighting callback. */
class TestClientCubeLightPrediction extends BaseTest {
    @Test
    void continuousExcavationGetsImmediateSkyInNewAirAtBothHeightSigns() {
        for (int y : new int[] { 1000000, -1000000 }) {
            Fixture fixture = new Fixture(y);
            CubePos pos = CubePos.of(0, y, 0);
            int surface = pos.minCubeY() + 16;
            LevelCube cube = fixture.install(pos, at -> (at.getY() <= surface ? Blocks.DIRT : Blocks.AIR).defaultBlockState(), at -> 0,
                    at -> at.getY() > surface ? 15 : 0);
            CubeLightView before = fixture.cache.cc_getVisibleCubeLightView(pos);
            for (int x = 8; x < 16; x++) {
                for (int depth = 0; depth < 3; depth++) {
                    BlockPos cell = new BlockPos(x, surface - depth, 8);
                    cube.setBlockState(cell, Blocks.AIR.defaultBlockState());
                    assertEquals(15, fixture.sky(cell), "The former solid cell must light before a server response or next frame");
                    assertEquals(15,
                            fixture.vanilla.getLightEngine().getLayerListener(LightLayer.SKY)
                                    .getDataLayerData(Coords.sectionPosByIndex(pos, Coords.blockToIndex(cell)))
                                    .get(cell.getX() & 15, cell.getY() & 15, cell.getZ() & 15));
                    assertFalse(fixture.cache.cc_isCubeLightReady(pos), "Display prediction must not certify current authoritative light");
                }
            }
            assertEquals(0, before.sample(LightLayer.SKY, new BlockPos(8, surface, 8)).orElseThrow().exactValue().orElseThrow(),
                    "A renderer's previous immutable publication cannot be modified");
        }
    }

    @Test
    void skylightCrossesCubeFacesAndAttenuatesUnderAdjacentRoof() {
        Fixture fixture = new Fixture(1000000);
        CubePos left = CubePos.of(0, 1000000, 0);
        CubePos right = CubePos.of(1, 1000000, 0);
        int roof = left.minCubeY() + 16;
        Function<BlockPos, BlockState> terrain = at -> (at.getY() == roof || at.getY() < roof - 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        LevelCube a = fixture.install(left, terrain, at -> 0, at -> at.getY() > roof ? 15 : 0);
        fixture.install(right, terrain, at -> 0, at -> at.getY() > roof ? 15 : 0);
        a.setBlockState(new BlockPos(31, roof, 8), Blocks.AIR.defaultBlockState());
        assertEquals(15, fixture.sky(new BlockPos(31, roof - 1, 8)));
        assertEquals(14, fixture.sky(new BlockPos(32, roof - 1, 8)));
        assertEquals(13, fixture.sky(new BlockPos(33, roof - 1, 8)));
        assertNull(fixture.cache.cc_getCubeLightView(left));
        assertNull(fixture.cache.cc_getCubeLightView(right));
    }

    @Test
    void verticalBoundaryUsesKnownSkyButAnUnloadedUpperCubeCannotInventIt() {
        Fixture fixture = new Fixture(-1000000);
        CubePos low = CubePos.of(0, -1000000, 0);
        CubePos high = CubePos.of(0, low.getY() + 1, 0);
        LevelCube floor = fixture.install(low, at -> Blocks.STONE.defaultBlockState(), at -> 0, at -> 0);
        BlockPos first = low.asBlockPos(8, 31, 8);
        floor.setBlockState(first, Blocks.AIR.defaultBlockState());
        assertEquals(0, fixture.sky(first));
        fixture.install(high, at -> Blocks.AIR.defaultBlockState(), at -> 0, at -> 15);
        BlockPos second = low.asBlockPos(9, 31, 8);
        floor.setBlockState(second, Blocks.AIR.defaultBlockState());
        assertEquals(15, fixture.sky(second));
        assertEquals(15, fixture.sky(first));
    }

    @Test
    void blockLightEntersOpenedCellWithoutBrighteningAnUnlitCave() {
        Fixture fixture = new Fixture(1000000);
        CubePos pos = CubePos.of(0, 1000000, 0);
        BlockPos lamp = pos.asBlockPos(8, 8, 8);
        BlockPos opened = lamp.east();
        LevelCube cube = fixture.install(pos, at -> at.equals(lamp) ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState(),
                at -> at.equals(lamp) ? 15 : 0, at -> 0);
        cube.setBlockState(opened, Blocks.AIR.defaultBlockState());
        assertEquals(14, fixture.block(opened));
        assertEquals(0, fixture.sky(opened));
        BlockPos dark = pos.asBlockPos(24, 8, 8);
        cube.setBlockState(dark, Blocks.AIR.defaultBlockState());
        assertEquals(0, fixture.block(dark));
        assertEquals(0, fixture.sky(dark));
    }

    @Test
    void serverCorrectionReplacesPredictionAndEvictionCannotLeakIt() {
        Fixture fixture = new Fixture(1000000);
        CubePos pos = CubePos.of(0, 1000000, 0);
        BlockPos opening = pos.asBlockPos(8, 16, 8);
        LevelCube cube = fixture.install(pos, at -> (at.getY() <= opening.getY() ? Blocks.STONE : Blocks.AIR).defaultBlockState(), at -> 0,
                at -> at.getY() > opening.getY() ? 15 : 0);
        long oldRevision = cube.cc_getLightRevision();
        CubeLightView old = fixture.cache.cc_getCubeLightView(pos);
        cube.setBlockState(opening, Blocks.AIR.defaultBlockState());
        assertEquals(15, fixture.sky(opening));
        assertFalse(fixture.cache.cc_applyScopedCubeLight(cube, oldRevision, 1, old, fixture.cache.cc_lightGeneration()));
        assertEquals(15, fixture.sky(opening));
        cube.setBlockState(opening, Blocks.STONE.defaultBlockState()); // rejected block prediction
        assertEquals(0, fixture.sky(opening), "Rollback removes provisional light before the authoritative light packet");
        CubeLightView corrected = light(pos, at -> 0, at -> at.getY() > opening.getY() ? 15 : 0);
        assertTrue(fixture.cache.cc_applyScopedCubeLight(cube, cube.cc_getLightRevision(), 5, corrected, fixture.cache.cc_lightGeneration()));
        assertSame(corrected, fixture.cache.cc_getVisibleCubeLightView(pos));
        assertEquals(0, fixture.sky(opening));
        assertEquals(0, fixture.sky(opening.below()), "Rollback must also remove provisional sky immediately");
        fixture.cache.cc_drop(pos);
        assertNull(fixture.cache.cc_getVisibleCubeLightView(pos));
        fixture.install(pos, at -> Blocks.AIR.defaultBlockState(), at -> 0, at -> 0);
        assertEquals(0, fixture.sky(opening));
    }

    @Test
    void removingTorchClearsItsWholeFieldButPreservesDirectSkyAndOldRenderSnapshot() {
        for (int y : new int[] { 1000000, -1000000 }) {
            Fixture fixture = new Fixture(y);
            CubePos pos = CubePos.of(0, y, 0);
            BlockPos torch = pos.asBlockPos(16, 16, 16);
            LevelCube cube = fixture.install(pos, at -> (at.equals(torch) ? Blocks.TORCH : Blocks.AIR).defaultBlockState(),
                    at -> Math.max(0, 14 - distance(at, torch)), at -> 15);
            CubeLightView before = fixture.cache.cc_getVisibleCubeLightView(pos);
            cube.setBlockState(torch, Blocks.AIR.defaultBlockState());
            for (BlockPos at : BlockPos.betweenClosed(pos.asBlockPos(0, 0, 0), pos.asBlockPos(31, 31, 31))) {
                assertEquals(0, fixture.block(at), () -> "Removed torch still illuminates " + at);
                assertEquals(15, fixture.sky(at), "Torch removal cannot revoke unrelated sky, even at an unloaded top boundary");
            }
            assertEquals(14, before.sample(LightLayer.BLOCK, torch).orElseThrow().exactValue().orElseThrow());
            assertFalse(fixture.cache.cc_isCubeLightReady(pos));
            cube.setBlockState(torch, Blocks.TORCH.defaultBlockState());
            assertEquals(13, fixture.block(torch.east()));
        }
    }

    @Test
    void overlappingTorchLightSurvivesSourceRemovalAcrossCubeBoundary() {
        Fixture fixture = new Fixture(-1000000);
        CubePos left = CubePos.of(0, -1000000, 0);
        CubePos right = CubePos.of(1, -1000000, 0);
        BlockPos first = left.asBlockPos(28, 16, 16);
        BlockPos second = right.asBlockPos(4, 16, 16);
        Function<BlockPos, BlockState> terrain = at -> (at.equals(first) || at.equals(second) ? Blocks.TORCH : Blocks.AIR).defaultBlockState();
        ToIntFunction<BlockPos> brightness = at -> Math.max(0, 14 - Math.min(distance(at, first), distance(at, second)));
        LevelCube a = fixture.install(left, terrain, brightness, at -> 0);
        fixture.install(right, terrain, brightness, at -> 0);
        a.setBlockState(first, Blocks.AIR.defaultBlockState());
        for (BlockPos at : BlockPos.betweenClosed(left.asBlockPos(0, 0, 0), right.asBlockPos(31, 31, 31))) {
            assertEquals(Math.max(0, 14 - distance(at, second)), fixture.block(at), () -> "Remaining source at " + at);
        }
        assertFalse(fixture.cache.cc_isCubeLightReady(right));
    }

    @Test
    void placingWallSeparatesTwoTorchesAndBreakingItRestoresTheirContributions() {
        Fixture fixture = new Fixture(1000000);
        CubePos left = CubePos.of(0, 1000000, 0);
        CubePos right = CubePos.of(1, 1000000, 0);
        BlockPos first = left.asBlockPos(24, 8, 8);
        BlockPos second = right.asBlockPos(8, 8, 8);
        Function<BlockPos, BlockState> corridor = at -> {
            if (at.equals(first) || at.equals(second)) {
                return Blocks.TORCH.defaultBlockState();
            }
            return (at.getY() == first.getY() && at.getZ() == first.getZ() ? Blocks.AIR : Blocks.STONE).defaultBlockState();
        };
        ToIntFunction<BlockPos> brightness = at -> corridor.apply(at).is(Blocks.STONE) ? 0
                : Math.max(0, 14 - Math.min(distance(at, first), distance(at, second)));
        LevelCube a = fixture.install(left, corridor, brightness, at -> 0);
        fixture.install(right, corridor, brightness, at -> 0);
        BlockPos wall = first.offset(7, 0, 0);
        a.setBlockState(wall, Blocks.STONE.defaultBlockState());
        assertEquals(0, fixture.block(wall));
        assertEquals(14 - distance(wall.east(), second), fixture.block(wall.east()));
        assertEquals(14 - distance(wall.west(), first), fixture.block(wall.west()));
        a.setBlockState(first, Blocks.AIR.defaultBlockState());
        assertEquals(0, fixture.block(wall.west()));
        assertEquals(14 - distance(wall.east(), second), fixture.block(wall.east()));
        a.setBlockState(wall, Blocks.AIR.defaultBlockState());
        assertEquals(14 - distance(wall.west(), second), fixture.block(wall.west()));
    }

    @Test
    void closingAndReopeningSkyShaftUpdatesBothVerticalCubesImmediately() {
        for (int y : new int[] { 1000000, -1000000 }) {
            Fixture fixture = new Fixture(y);
            CubePos low = CubePos.of(0, y, 0);
            CubePos high = CubePos.of(0, y + 1, 0);
            Function<BlockPos, BlockState> shaft = at -> (at.getX() == 8 && at.getZ() == 8 ? Blocks.AIR : Blocks.STONE).defaultBlockState();
            ToIntFunction<BlockPos> sky = at -> shaft.apply(at).isAir() ? 15 : 0;
            fixture.install(low, shaft, at -> 0, sky);
            LevelCube top = fixture.install(high, shaft, at -> 0, sky);
            BlockPos lid = high.asBlockPos(8, 1, 8);
            for (int repeat = 0; repeat < 3; repeat++) {
                top.setBlockState(lid, Blocks.STONE.defaultBlockState());
                for (int height = low.minCubeY(); height <= lid.getY(); height++) {
                    assertEquals(0, fixture.sky(new BlockPos(8, height, 8)), "Direct-sky removal must propagate downward without attenuation");
                }
                assertEquals(15, fixture.sky(lid.above()));
                top.setBlockState(lid, Blocks.AIR.defaultBlockState());
                assertEquals(15, fixture.sky(low.asBlockPos(8, 0, 8)));
            }
        }
    }

    private static int distance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    @Test
    void decreasesAndReopeningMatchFreshNativeLightingThroughWaterAndSlabs() {
        Fixture fixture = new Fixture(-1000000);
        CubePos pos = CubePos.of(0, -1000000, 0);
        int base = pos.minCubeY();
        Map<BlockPos, BlockState> edits = new HashMap<>();
        edits.put(new BlockPos(8, 8, 8), Blocks.TORCH.defaultBlockState());
        edits.put(new BlockPos(14, 8, 8), Blocks.TORCH.defaultBlockState());
        Function<BlockPos, BlockState> scene = at -> edits.getOrDefault(at, room(at));
        LevelLightEngine initial = nativeLight(scene);
        LevelCube cube = fixture.install(pos, at -> scene.apply(at.offset(0, -base, 0)),
                at -> initial.getLayerListener(LightLayer.BLOCK).getLightValue(at.offset(0, -base, 0)),
                at -> initial.getLayerListener(LightLayer.SKY).getLightValue(at.offset(0, -base, 0)));
        BlockPos hole = new BlockPos(16, 24, 16);
        List<Map.Entry<BlockPos, BlockState>> changes = List.of(Map.entry(hole, Blocks.STONE.defaultBlockState()),
                Map.entry(new BlockPos(8, 8, 8), Blocks.AIR.defaultBlockState()), Map.entry(new BlockPos(14, 8, 8), Blocks.STONE.defaultBlockState()),
                Map.entry(hole, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)),
                Map.entry(hole, Blocks.AIR.defaultBlockState()), Map.entry(hole, Blocks.WATER.defaultBlockState()),
                Map.entry(hole, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)),
                Map.entry(hole, Blocks.AIR.defaultBlockState()));
        for (var change : changes) {
            edits.put(change.getKey(), change.getValue());
            cube.setBlockState(change.getKey().offset(0, base, 0), change.getValue());
            LevelLightEngine expected = nativeLight(scene);
            for (BlockPos local : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(31, 31, 31))) {
                for (LightLayer layer : LightLayer.values()) {
                    int actual = fixture.vanilla.getLightEngine().getLayerListener(layer).getLightValue(local.offset(0, base, 0));
                    assertEquals(expected.getLayerListener(layer).getLightValue(local), actual, () -> change + ": " + layer + " at " + local);
                }
            }
        }
    }

    @Test
    void budgetExhaustionDoesNotPublishPartiallyClearedSky() {
        Fixture fixture = new Fixture(1000000);
        Map<CubePos, CubeLightView> before = new HashMap<>();
        LevelCube top = null;
        for (int y = 1000000; y < 1000005; y++) {
            CubePos pos = CubePos.of(0, y, 0);
            top = fixture.install(pos, at -> Blocks.AIR.defaultBlockState(), at -> 0,
                    at -> Math.max(0, 15 - Math.abs(at.getX() - 16) - Math.abs(at.getZ() - 16)));
            before.put(pos, fixture.cache.cc_getVisibleCubeLightView(pos));
        }
        top.setBlockState(top.cc_getCubePos().asBlockPos(16, 31, 16), Blocks.STONE.defaultBlockState());
        before.forEach((pos, view) -> assertSame(view, fixture.cache.cc_getVisibleCubeLightView(pos),
                "An oversized transaction must leave the previous immutable display intact until server completion"));
    }

    @Test
    void decreasingRedundantSourcePreservesTheUnchangedRenderPublication() {
        Fixture fixture = new Fixture(1000000);
        CubePos pos = CubePos.of(0, 1000000, 0);
        BlockPos lamp = pos.asBlockPos(16, 16, 16);
        BlockPos torch = lamp.east();
        LevelCube cube = fixture.install(pos,
                at -> (at.equals(lamp) ? Blocks.GLOWSTONE : at.equals(torch) ? Blocks.TORCH : Blocks.AIR).defaultBlockState(),
                at -> Math.max(0, 15 - distance(at, lamp)), at -> 0);
        CubeLightView before = fixture.cache.cc_getVisibleCubeLightView(pos);
        cube.setBlockState(torch, Blocks.SOUL_TORCH.defaultBlockState());
        assertSame(before, fixture.cache.cc_getVisibleCubeLightView(pos), "Temporary removal restored by another source needs no light mesh rebuild");
        assertFalse(fixture.cache.cc_isCubeLightReady(pos));
    }

    @Test
    void outdoorBlockLeavesAttenuatedSideSkyBeneathItInsteadOfFullDarkness() {
        Fixture fixture = new Fixture(1000000);
        CubePos pos = CubePos.of(0, 1000000, 0);
        LevelCube cube = fixture.install(pos, at -> Blocks.AIR.defaultBlockState(), at -> 0, at -> 15);
        BlockPos placed = pos.asBlockPos(16, 16, 16);
        cube.setBlockState(placed, Blocks.STONE.defaultBlockState());
        assertEquals(0, fixture.sky(placed));
        assertEquals(15, fixture.sky(placed.above()));
        for (int depth = 1; depth <= 16; depth++) {
            assertEquals(14, fixture.sky(placed.below(depth)), "Neighboring open sky must immediately refill the shadow");
        }
        cube.setBlockState(placed, Blocks.AIR.defaultBlockState());
        assertEquals(15, fixture.sky(placed.below(16)));
    }

    private static BlockState room(BlockPos at) {
        boolean shell = at.getX() == 0 || at.getX() == 31 || at.getZ() == 0 || at.getZ() == 31 || at.getY() == 0;
        boolean roof = at.getY() == 24 && !(at.getX() == 16 && at.getZ() == 16);
        return (at.getY() <= 24 && shell || roof ? Blocks.STONE : Blocks.AIR).defaultBlockState();
    }

    /** Independent fresh vanilla column engine at ordinary Y; the client prediction runs at extended negative Y. */
    private static LevelLightEngine nativeLight(Function<BlockPos, BlockState> states) {
        Map<Long, ProtoChunk> chunks = new HashMap<>();
        PalettedContainerFactory palettes = PalettedContainerFactory.create(VanillaRegistriesExtension.shared());
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(31, 31, 31))) {
            ChunkPos chunkPos = new ChunkPos(at.getX() >> 4, at.getZ() >> 4);
            ProtoChunk chunk = chunks.computeIfAbsent(chunkPos.pack(),
                    key -> new ProtoChunk(chunkPos, UpgradeData.EMPTY, LevelHeightAccessor.create(0, 32), palettes, null));
            chunk.getSection(at.getY() / 16).setBlockState(at.getX() & 15, at.getY() & 15, at.getZ() & 15, states.apply(at));
        }
        ServerLevel level = mock(ServerLevel.class);
        Misc.setupLevelHeight(level, 0, 32);
        LevelLightEngine engine = new LevelLightEngine(new LightChunkGetter() {
            @Override public LightChunk getChunkForLighting(int x, int z) {
                return chunks.get(ChunkPos.pack(x, z));
            }

            @Override public BlockGetter getLevel() {
                return level;
            }
        }, true, true);
        chunks.values().forEach(chunk -> {
            chunk.initializeLightSources();
            engine.updateSectionStatus(SectionPos.of(chunk.getPos().x(), 0, chunk.getPos().z()), false);
            engine.updateSectionStatus(SectionPos.of(chunk.getPos().x(), 1, chunk.getPos().z()), false);
        });
        drain(engine);
        chunks.values().forEach(chunk -> engine.propagateLightSources(chunk.getPos()));
        drain(engine);
        return engine;
    }

    private static void drain(LevelLightEngine engine) {
        for (int i = 0; i < 100 && engine.hasLightWork(); i++) {
            engine.runLightUpdates();
        }
        assertFalse(engine.hasLightWork());
    }

    private static CubeLightView light(CubePos pos, ToIntFunction<BlockPos> block, ToIntFunction<BlockPos> sky) {
        byte[][] blockLayers = new byte[CubicConstants.SECTION_COUNT][];
        byte[][] skyLayers = new byte[CubicConstants.SECTION_COUNT][];
        for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
            DataLayer b = new DataLayer();
            DataLayer s = new DataLayer();
            BlockPos origin = Coords.sectionPosByIndex(pos, section).origin();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockPos at = origin.offset(x, y, z);
                        b.set(x, y, z, block.applyAsInt(at));
                        s.set(x, y, z, sky.applyAsInt(at));
                    }
                }
            }
            blockLayers[section] = b.getData().clone();
            skyLayers[section] = s.getData().clone();
        }
        return CubeLightView.fromExact(pos, blockLayers, skyLayers);
    }

    private static final class Fixture {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final ClientChunkCache vanilla;
        private final ClientCubeCache cache;

        private Fixture(int y) {
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            when(level.dimensionType().hasSkyLight()).thenReturn(true);
            vanilla = new ClientChunkCache(level, 5);
            cache = (ClientCubeCache) vanilla;
            when(level.getChunkSource()).thenReturn(vanilla);
            cache.cc_updateViewCenter(0, y, 0);
        }

        private LevelCube install(CubePos pos, Function<BlockPos, BlockState> states, ToIntFunction<BlockPos> block, ToIntFunction<BlockPos> sky) {
            LevelCube source = new LevelCube(level, pos);
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        source.getSection(Coords.blockToIndex(x, y, z)).setBlockState(x & 15, y & 15, z & 15, states.apply(pos.asBlockPos(x, y, z)));
                    }
                }
            }
            CCClientboundLevelCubePacketData data = new CCClientboundLevelCubePacketData(source);
            var buffer = data.getReadBuffer();
            LevelCube installed;
            try {
                installed = cache.cc_replaceWithPacketData(pos.getX(), pos.getY(), pos.getZ(), buffer, data.getHeightmaps(), ignored -> {});
            } finally {
                buffer.release();
            }
            assertTrue(
                    cache.cc_applyScopedCubeLight(installed, installed.cc_getLightRevision(), 1, light(pos, block, sky), cache.cc_lightGeneration()));
            return installed;
        }

        private int sky(BlockPos pos) {
            return vanilla.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos);
        }

        private int block(BlockPos pos) {
            return vanilla.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
        }
    }
}
