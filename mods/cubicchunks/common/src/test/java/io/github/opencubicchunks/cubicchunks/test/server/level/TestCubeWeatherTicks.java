package io.github.opencubicchunks.cubicchunks.test.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubeWeatherTicks;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.CubeSpawnTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestCubeWeatherTicks extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeSnowAccumulatesAcrossCubeSeamAndRespectsRule(int y) throws Exception {
        try (var f = fixture(y)) {
            var pos = new BlockPos(16, y, 16);
            f.level.getGameRules().set(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, 2, null);
            assertTrue(CubeWeatherTicks.precipitate(f.level, pos));
            assertTrue(f.world.cube(pos).getBlockState(pos).is(Blocks.SNOW));
            assertTrue(CubeWeatherTicks.precipitate(f.level, pos));
            assertTrue(CubeWeatherTicks.precipitate(f.level, pos));
            assertEquals(2, f.world.cube(pos).getBlockState(pos).getValue(SnowLayerBlock.LAYERS));
            assertNull(CubeWeatherTicks.precipitationSurface(f.level));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeWaterFreezesAndKnownBlockLightPreventsIt(int y) throws Exception {
        try (var f = fixture(y)) {
            var water = new BlockPos(16, y - 1, 16);
            f.world.block(water, Blocks.WATER.defaultBlockState());
            when(f.level.isRaining()).thenReturn(false);
            assertTrue(CubeWeatherTicks.precipitate(f.level, water.above()));
            assertTrue(f.world.cube(water).getBlockState(water).is(Blocks.ICE));
            f.platform(y, 10);
            f.world.block(water, Blocks.WATER.defaultBlockState());
            assertTrue(CubeWeatherTicks.precipitate(f.level, water.above()));
            assertTrue(f.world.cube(water).getBlockState(water).is(Blocks.WATER));
        }
    }

    @Test
    void stackedCubesDoNotMultiplyWeatherAttempts() throws Exception {
        try (var f = fixture(96)) {
            var columns = (CubeColumnSource) f.level.getChunkSource().chunkMap;
            when(columns.cc_column(anyInt(), anyInt())).thenAnswer(call -> snapshot(f));
            var random = mock(RandomSource.class);
            when(f.level.getRandom()).thenReturn(random);
            when(f.level.getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt()))
                    .thenAnswer(call -> new BlockPos((int) call.getArgument(0) + 8, 0, (int) call.getArgument(2) + 8));
            var ticks = new CubeWeatherTicks();
            ticks.tick(f.level, f.world.cube(new BlockPos(16, 96, 16)), 3);
            ticks.tick(f.level, f.world.cube(new BlockPos(16, 32000, 16)), 3);
            ticks.tick(f.level, f.world.cube(new BlockPos(16, -32000, 16)), 3);
            verify(random, times(12)).nextInt(48);
            when(f.level.getGameTime()).thenReturn(1L);
            ticks.tick(f.level, f.world.cube(new BlockPos(16, 96, 16)), 3);
            verify(random, times(24)).nextInt(48);
        }
    }

    @Test
    void thunderProbabilityIsIndependentOfRandomTickSpeed() throws Exception {
        try (var f = fixture(96)) {
            var columns = (CubeColumnSource) f.level.getChunkSource().chunkMap;
            when(columns.cc_column(anyInt(), anyInt())).thenAnswer(call -> localSnapshot(f, 96));
            when(f.level.isThundering()).thenReturn(true);
            var random = mock(RandomSource.class);
            when(random.nextInt(100000)).thenReturn(1);
            when(f.level.getRandom()).thenReturn(random);
            var ticks = new CubeWeatherTicks();
            ticks.tick(f.level, f.world.cube(new BlockPos(16, 96, 16)), 0);
            ticks.tick(f.level, f.world.cube(new BlockPos(16, 32000, 16)), 0);
            verify(random, times(4)).nextInt(100000);
            verify(f.level, org.mockito.Mockito.never()).getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void unknownLightOrMissingNeighborsCannotFreezeOrSnow() throws Exception {
        try (var f = fixture(96)) {
            var pos = new BlockPos(16, 96, 16);
            f.lights.clear();
            assertFalse(CubeWeatherTicks.precipitate(f.level, pos));
            assertTrue(f.world.cube(pos).getBlockState(pos).isAir());
            assertFalse(CubeWeatherTicks.precipitate(f.level, new BlockPos(16, CubicBlockBounds.MIN, 16)));
            assertFalse(CubeWeatherTicks.precipitate(f.level, new BlockPos(16, CubicBlockBounds.MAX + 1, 16)));
        }
    }

    @Test
    void glassRoofWinsOverLowerExposedLookingPlatforms() throws Exception {
        try (var f = fixture(96)) {
            BlockPos sample = new BlockPos(16, 96, 16);
            f.world.block(sample.atY(32000), Blocks.GLASS.defaultBlockState());
            CubeColumnSnapshot snapshot = snapshot(f);
            assertEquals(32001, snapshot.firstAvailable(Heightmap.Types.MOTION_BLOCKING, 16, 16).orElseThrow());
            assertEquals(32001, snapshot.firstAvailable(Heightmap.Types.WORLD_SURFACE, 16, 16).orElseThrow());
        }
    }

    @Test
    void nativeTemperatureCacheDoesNotAliasDistantAltitude() throws Exception {
        try (var f = fixture(96)) {
            var biome = f.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value();
            BlockPos near = new BlockPos(0, 80, 0);
            BlockPos high = near.above(4096);
            assertTrue(biome.warmEnoughToRain(near, 63));
            assertFalse(biome.warmEnoughToRain(high, 63));
            assertTrue(biome.warmEnoughToRain(near, 63));
            assertTrue(biome.warmEnoughToRain(near.below(4096), 63));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeRodAtFullHeightAttractsStrikeAndPowersThenResets(int y) throws Exception {
        try (var f = fixture(y)) {
            BlockPos rod = new BlockPos(19, y, 16);
            f.world.block(rod, Blocks.LIGHTNING_ROD.weathering().unaffected().defaultBlockState());
            f.poi.add(rod, f.level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.LIGHTNING_ROD));
            CubeColumnSource source = (x, z) -> localSnapshot(f, y);
            BlockPos selected = CubeWeatherTicks.lightningTarget(f.level, source, new BlockPos(16, y, 16));
            assertEquals(rod.above(), selected);

            var rainy = mock(Biome.class);
            when(rainy.getPrecipitationAt(any(), anyInt())).thenReturn(Biome.Precipitation.RAIN);
            doReturn(Holder.direct(rainy)).when(f.level).getBiome(any());
            f.level.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
            var added = new ArrayList<Entity>();
            doAnswer(call -> added.add(call.getArgument(0))).when(f.level).addFreshEntity(any());
            assertTrue(CubeWeatherTicks.thunder(f.level, source, new BlockPos(16, y, 16)));
            assertEquals(1, added.size());
            assertTrue(added.getFirst() instanceof LightningBolt);
            assertEquals(y + 1, added.getFirst().getY());

            when(f.level.setBlock(any(), any(), anyInt())).thenAnswer(call -> {
                f.world.block(call.getArgument(0), call.getArgument(1));
                return true;
            });
            var state = f.world.cube(rod).getBlockState(rod);
            ((LightningRodBlock) state.getBlock()).onLightningStrike(state, f.level, rod);
            assertTrue(f.world.cube(rod).getBlockState(rod).getValue(LightningRodBlock.POWERED));
            verify(f.level).scheduleTick(rod, Blocks.LIGHTNING_ROD.weathering().unaffected(), 8);
            f.world.cube(rod).getBlockState(rod).tick(f.level, rod, f.level.getRandom());
            assertFalse(f.world.cube(rod).getBlockState(rod).getValue(LightningRodBlock.POWERED));
        }
    }

    @Test
    void coveredLightningRodIsRejected() throws Exception {
        try (var f = fixture(96)) {
            BlockPos rod = new BlockPos(19, 96, 16);
            f.world.block(rod, Blocks.LIGHTNING_ROD.weathering().unaffected().defaultBlockState());
            f.world.block(rod.above(10), Blocks.GLASS.defaultBlockState());
            f.poi.add(rod, f.level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.LIGHTNING_ROD));
            CubeColumnSource source = (x, z) -> localSnapshot(f, 96);
            BlockPos sample = new BlockPos(16, 96, 16);
            assertEquals(sample, CubeWeatherTicks.lightningTarget(f.level, source, sample));
        }
    }

    @Test
    void vanillaPrecipitationStillUsesNativeColumnHeight() throws Exception {
        try (var f = fixture(96)) {
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            CubeMobTestWorld.setField(net.minecraft.world.level.Level.class, f.level, "cc_isCubic", false);
            BlockPos surface = new BlockPos(16, 96, 16);
            when(f.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO)).thenReturn(surface);
            doReturn(0).when(f.level).getBrightness(any(), any());
            doAnswer(call -> {
                BlockPos pos = call.getArgument(0);
                var cube = f.world.cubes.get(CubePos.from(pos));
                return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
            }).when(f.level).getBlockState(any());
            doAnswer(call -> f.level.getBlockState(call.getArgument(0)).getFluidState()).when(f.level).getFluidState(any());
            f.level.tickPrecipitation(BlockPos.ZERO);
            verify(f.level).getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
            assertTrue(f.world.cube(surface).getBlockState(surface).is(Blocks.SNOW));
        }
    }

    private CubeSpawnTestWorld fixture(int y) throws Exception {
        var f = new CubeSpawnTestWorld(directory.resolve("world"));
        f.platform(y, 0);
        var snowy = f.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.SNOWY_PLAINS);
        doReturn(snowy).when(f.level).getBiome(any());
        doReturn(List.of()).when(f.level).getEntitiesOfClass(any(), any(), any());
        when(f.level.isRaining()).thenReturn(true);
        when(f.level.setBlockAndUpdate(any(), any())).thenAnswer(call -> {
            f.world.block(call.getArgument(0), call.getArgument(1));
            return true;
        });
        doCallRealMethod().when(f.level).tickPrecipitation(any());
        return f;
    }

    private static CubeColumnSnapshot snapshot(CubeSpawnTestWorld f) {
        var cubes = new TreeMap<Integer, CubeColumnBlocks>();
        f.world.cubes.forEach((pos, cube) -> {
            if (pos.getX() == 0 && pos.getZ() == 0) {
                cubes.put(pos.getY(), CubeColumnBlocks.capture(cube));
            }
        });
        return new CubeColumnSnapshot(0, 0, -64, 319, cubes, Set.of());
    }

    private static CubeColumnSnapshot localSnapshot(CubeSpawnTestWorld f, int y) {
        var blocks = snapshot(f).cubes();
        return new CubeColumnSnapshot(0, 0, y - 32, y + 31, blocks, Set.of());
    }
}
