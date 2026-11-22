package io.github.opencubicchunks.cubicchunks.test.client;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.EmptyLevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Opt-in with -PsodiumCompat: exercises actual Sodium snapshots after both mods' transformations. */
class TestSodiumSnapshots extends BaseTest {
    private ClassLoader previousContextLoader;

    @BeforeEach
    void useTransformedLoaderForSodiumServices() {
        // Sodium uses ServiceLoader's context loader, whereas Gradle's test worker retains its untransformed loader.
        previousContextLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(TestSodiumSnapshots.class.getClassLoader());
    }

    @AfterEach
    void restoreContextLoader() {
        Thread.currentThread().setContextClassLoader(previousContextLoader);
    }

    @Test
    void copiesEveryCubeSectionWithBiomesLightAndOnlyItsOwnEntitiesAtBothHeightSigns() {
        for (int height : new int[] { -32000, 32000 }) {
            Fixture fixture = new Fixture(true);
            CubePos cubePos = CubePos.from(new BlockPos(-1, height, 47));
            LevelCube cube = fixture.cube(cubePos);
            var desert = fixture.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT);
            var forest = fixture.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.FOREST);
            var chests = new ChestBlockEntity[CubicConstants.SECTION_COUNT];
            byte[][] blockLight = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
            byte[][] skyLight = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
            for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
                SectionPos sectionPos = Coords.sectionPosByIndex(cubePos, index);
                BlockPos chestPos = sectionPos.origin().offset(1, 2, 3);
                cube.setBlockState(chestPos, Blocks.CHEST.defaultBlockState());
                chests[index] = new ChestBlockEntity(chestPos, Blocks.CHEST.defaultBlockState());
                cube.setBlockEntity(chests[index]);
                cube.setBlockState(chestPos.east(), Blocks.STONE.defaultBlockState());
                var biome = (index & 1) == 0 ? desert : forest;
                cube.getSection(index).fillBiomesFromNoise((x, y, z) -> biome, 0, 0, 0);
                Arrays.fill(blockLight[index], (byte) ((index + 1) * 17));
                Arrays.fill(skyLight[index], (byte) ((15 - index) * 17));
            }
            fixture.lights.put(cubePos, CubeLightView.fromExact(cubePos, blockLight, skyLight));

            for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
                SectionPos pos = Coords.sectionPosByIndex(cubePos, index);
                ClonedChunkSection snapshot = fixture.prepare(pos);
                assertSame(Blocks.STONE.defaultBlockState(), snapshot.getBlockData().get(2, 2, 3));
                assertSame((index & 1) == 0 ? desert : forest, snapshot.getBiomeData().get(0, 0, 0));
                assertEquals(index + 1, snapshot.getLightArray(LightLayer.BLOCK).get(2, 2, 3));
                assertEquals(15 - index, snapshot.getLightArray(LightLayer.SKY).get(2, 2, 3));
                assertEquals(1, snapshot.getBlockEntityMap().size());
                assertSame(chests[index], snapshot.getBlockEntityMap().get(LevelSlice.getLocalBlockIndex(1, 2, 3)));

                cube.setBlockState(pos.origin().offset(2, 2, 3), Blocks.GOLD_BLOCK.defaultBlockState());
                fixture.cache.invalidate(pos.asLong());
                assertSame(Blocks.STONE.defaultBlockState(), snapshot.getBlockData().get(2, 2, 3), "An in-flight mesh retains its snapshot");
                assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), fixture.prepare(pos).getBlockData().get(2, 2, 3));
            }
        }
    }

    @Test
    void absentNeighborsAreEmptyThenBecomeVisibleAfterCacheInvalidation() {
        Fixture fixture = new Fixture(true);
        SectionPos origin = SectionPos.of(-1, -2000, 3);
        fixture.cube(CubePos.from(origin.origin())).setBlockState(origin.origin(), Blocks.STONE.defaultBlockState());
        SectionPos neighbor = origin.offset(1, 0, 0);
        ClonedChunkSection absent = fixture.cache.acquire(neighbor.x(), neighbor.y(), neighbor.z());
        assertNull(absent.getBlockData());
        assertNull(LevelSlice.prepare(fixture.level, neighbor, fixture.cache));

        fixture.cube(CubePos.from(neighbor.origin())).setBlockState(neighbor.origin(), Blocks.GLASS.defaultBlockState());
        fixture.cache.invalidate(neighbor.asLong());
        assertSame(Blocks.GLASS.defaultBlockState(), fixture.prepare(neighbor).getBlockData().get(0, 0, 0));
        assertNull(absent.getBlockData());
        fixture.cubes.remove(CubePos.from(neighbor.origin()));
        fixture.cache.invalidate(neighbor.asLong());
        assertNull(fixture.cache.acquire(neighbor.x(), neighbor.y(), neighbor.z()).getBlockData());
        assertNotNull(fixture.prepare(origin));
    }

    @Test
    void ordinaryWorldsKeepSodiumColumnSnapshots() {
        Fixture fixture = new Fixture(false);
        SectionPos pos = SectionPos.of(0, 4, 0);
        LevelChunkSection[] sections = new LevelChunkSection[fixture.level.getSectionsCount()];
        Arrays.setAll(sections, ignored -> new LevelChunkSection(fixture.level.palettedContainerFactory()));
        sections[fixture.level.getSectionIndexFromSectionY(pos.y())].setBlockState(2, 3, 4, Blocks.DIAMOND_BLOCK.defaultBlockState());
        when(fixture.column.getSections()).thenReturn(sections);
        BlockPos chestPos = pos.origin().offset(1, 2, 3);
        var chest = new ChestBlockEntity(chestPos, Blocks.CHEST.defaultBlockState());
        when(fixture.column.getBlockEntities()).thenReturn(Map.of(chestPos, chest));

        ClonedChunkSection snapshot = fixture.prepare(pos);
        assertSame(Blocks.DIAMOND_BLOCK.defaultBlockState(), snapshot.getBlockData().get(2, 3, 4));
        assertSame(chest, snapshot.getBlockEntityMap().get(LevelSlice.getLocalBlockIndex(1, 2, 3)));
        verify((CubicLevel) fixture.level, never()).cc_getCube(anyInt(), anyInt(), anyInt());
        verify((CubicLevel) fixture.level, never()).cc_getCube(anyInt(), anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false));
    }

    private static final class Fixture implements LightChunkGetter, CubeLightProvider {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final Map<CubePos, LevelCube> cubes = new HashMap<>();
        private final Map<CubePos, CubeLightView> lights = new HashMap<>();
        private final LevelChunk column = mock(LevelChunk.class);
        private final ClonedChunkSectionCache cache;

        private Fixture(boolean cubic) {
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
            Misc.setupLevelHeight(level, -64, 384);
            when(level.dimensionType().hasSkyLight()).thenReturn(true);
            when(level.getChunk(anyInt(), anyInt())).thenReturn(column);
            var empty = new EmptyLevelCube(level, CubePos.ZERO, level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
            when(((CubicLevel) level).cc_getCube(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(call -> cubes.getOrDefault(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2)), empty));
            when(((CubicLevel) level).cc_getCube(anyInt(), anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false)))
                    .thenAnswer(call -> cubes.get(CubePos.of(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
            var engine = new LevelLightEngine(this, true, true);
            when(level.getLightEngine()).thenReturn(engine);
            cache = new ClonedChunkSectionCache(level);
        }

        private LevelCube cube(CubePos pos) {
            return cubes.computeIfAbsent(pos, key -> new LevelCube(level, key));
        }

        private ClonedChunkSection prepare(SectionPos pos) {
            var context = LevelSlice.prepare(level, pos, cache);
            assertNotNull(context);
            return context.getSections()[LevelSlice.getLocalSectionIndex(1, 1, 1)];
        }

        @Override public LightChunk getChunkForLighting(int x, int z) {
            return null;
        }

        @Override public BlockGetter getLevel() {
            return level;
        }

        @Override public CubeLightView cc_getCubeLightView(CubePos pos) {
            return lights.get(pos);
        }
    }
}
