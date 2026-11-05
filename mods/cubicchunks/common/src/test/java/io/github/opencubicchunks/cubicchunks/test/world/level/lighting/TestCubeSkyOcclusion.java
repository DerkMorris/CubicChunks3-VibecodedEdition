package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.Random;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightIndex;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.ColumnSkyLightResult;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.junit.jupiter.api.Test;

class TestCubeSkyOcclusion extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;

    @Test
    void distinguishesLightDampeningAndDirectedFacesFromHeightmapPredicates() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bottomSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState topSlab = bottomSlab.setValue(SlabBlock.TYPE, SlabType.TOP);
        assertFalse(CubeSkyOcclusion.isEdgeOccluded(air, Blocks.GLASS.defaultBlockState()));
        assertTrue(CubeSkyOcclusion.isEdgeOccluded(air, Blocks.TINTED_GLASS.defaultBlockState()));
        assertTrue(CubeSkyOcclusion.isEdgeOccluded(air, Blocks.WATER.defaultBlockState()));
        assertTrue(CubeSkyOcclusion.isEdgeOccluded(air, Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(CubeSkyOcclusion.isEdgeOccluded(air, bottomSlab));
        assertTrue(CubeSkyOcclusion.isEdgeOccluded(bottomSlab, air));
        assertTrue(CubeSkyOcclusion.isEdgeOccluded(air, topSlab));
        assertFalse(CubeSkyOcclusion.isEdgeOccluded(topSlab, air));
        assertFalse(CubeSkyOcclusion.isEdgeOccluded(topSlab, bottomSlab));
        assertTrue(CubeSkyOcclusion.isEdgeOccluded(bottomSlab, topSlab));
    }

    @Test
    void complementaryPartialFacesCanOccludeAnEdgeTogether() {
        BlockState air = Blocks.AIR.defaultBlockState();
        Direction[] horizontal = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        boolean foundComplementaryFaces = false;
        for (Direction upperFacing : horizontal) {
            BlockState upper = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.FACING, upperFacing);
            for (Direction lowerFacing : horizontal) {
                BlockState lower = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.BOTTOM).setValue(StairBlock.FACING,
                        lowerFacing);
                if (!CubeSkyOcclusion.isEdgeOccluded(upper, air) && !CubeSkyOcclusion.isEdgeOccluded(air, lower)
                        && CubeSkyOcclusion.isEdgeOccluded(upper, lower)) {
                    foundComplementaryFaces = true;
                }
            }
        }
        assertTrue(foundComplementaryFaces, "The union of two partial faces must be evaluated, not only each block in isolation");
    }

    @Test
    void capturesOnlyInternalEdgesAndDetachesBothBoundaryPlanes() {
        CubePos pos = CubePos.of(-3, -7, 2);
        ProtoCube cube = newCube(pos);
        BlockState topSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        set(cube, 0, DIAMETER - 1, 0, topSlab);
        set(cube, 1, 0, 1, Blocks.GLASS.defaultBlockState());
        set(cube, 16, 15, 31, Blocks.STONE.defaultBlockState());
        CubeSkyOcclusion saved = CubeSkyOcclusion.capture(cube);
        assertEquals(pos, saved.pos());
        assertTrue(saved.highestInternalEdgeY(0, 0).isEmpty(), "The edge above a top slab lies outside this cube");
        assertEquals(OptionalInt.of(pos.minCubeY() + 16), saved.highestInternalEdgeY(16, 31));
        assertSame(topSlab, saved.topState(0, 0));
        assertSame(Blocks.GLASS.defaultBlockState(), saved.bottomState(1, 1));
        set(cube, 0, DIAMETER - 1, 0, Blocks.AIR.defaultBlockState());
        set(cube, 1, 0, 1, Blocks.STONE.defaultBlockState());
        set(cube, 16, 15, 31, Blocks.AIR.defaultBlockState());
        CubeSkyOcclusion changed = CubeSkyOcclusion.capture(cube);
        assertSame(topSlab, saved.topState(0, 0));
        assertSame(Blocks.GLASS.defaultBlockState(), saved.bottomState(1, 1));
        assertEquals(OptionalInt.of(pos.minCubeY() + 16), saved.highestInternalEdgeY(16, 31));
        assertTrue(changed.highestInternalEdgeY(16, 31).isEmpty());
        assertEquals(OptionalInt.of(pos.minCubeY() + 1), changed.highestInternalEdgeY(1, 1));
    }

    @Test
    void everyColumnMatchesVanillaSourcesAcrossFourTilesWithAKnownAirUpperCube() {
        ProtoCube cube = newCube(CubePos.of(-2, -5, 3));
        BlockState[] states = states();
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                set(cube, x, (x * 3 + z) % DIAMETER, z, states[(x + z * DIAMETER) % states.length]);
            }
        }
        assertVanillaOracle(cube);
    }

    @Test
    void randomizedStackedFacesAndChangesMatchVanillaSources() {
        ProtoCube cube = newCube(CubePos.of(4, -100000, -2));
        Random random = new Random(0x5C71);
        BlockState[] states = states();
        for (int change = 0; change < 5000; change++) {
            set(cube, random.nextInt(DIAMETER), random.nextInt(DIAMETER), random.nextInt(DIAMETER), states[random.nextInt(states.length)]);
        }
        assertVanillaOracle(cube);
        for (int z = 0; z < DIAMETER; z++) {
            for (int x = 0; x < DIAMETER; x++) {
                for (int y = DIAMETER / 2; y < DIAMETER; y++) {
                    set(cube, x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }
        assertVanillaOracle(cube);
    }

    @Test
    void preservesExtremeCoordinatesAndRejectsLocalAliasing() {
        for (int y : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, CubePos.MAX_COORDINATE_VALUE }) {
            CubePos pos = CubePos.of(CubePos.MAX_COORDINATE_VALUE, y, -CubePos.MAX_COORDINATE_VALUE - 1);
            ProtoCube cube = newCube(pos);
            set(cube, DIAMETER - 1, DIAMETER - 2, DIAMETER - 1, Blocks.STONE.defaultBlockState());
            CubeSkyOcclusion snapshot = CubeSkyOcclusion.capture(cube);
            assertEquals(OptionalInt.of(pos.minCubeY() + DIAMETER - 1), snapshot.highestInternalEdgeY(DIAMETER - 1, DIAMETER - 1));
            for (int invalid : new int[] { -1, DIAMETER, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
                assertThrows(IllegalArgumentException.class, () -> snapshot.highestInternalEdgeY(invalid, 0));
                assertThrows(IllegalArgumentException.class, () -> snapshot.topState(0, invalid));
                assertThrows(IllegalArgumentException.class, () -> snapshot.bottomState(invalid, 0));
            }
        }
        ProtoCube invalid = newCube(CubePos.of(CubePos.MAX_COORDINATE_VALUE + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CubeSkyOcclusion.capture(invalid));
    }

    static ProtoCube newCube(CubePos pos) {
        return new ProtoCube(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(pos.minCubeY(), DIAMETER),
                PalettedContainerFactory.create(VanillaRegistriesExtension.shared()), null);
    }

    static void set(ProtoCube cube, int x, int y, int z, BlockState state) {
        cube.getSection(Coords.blockToIndex(x, y, z)).setBlockState(SectionPos.sectionRelative(x), SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(z), state);
    }

    private static BlockState[] states() {
        return new BlockState[] { Blocks.AIR.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.TINTED_GLASS.defaultBlockState(),
            Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.BOTTOM),
            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP),
            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.BOTTOM).setValue(StairBlock.FACING, Direction.SOUTH),
            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.FACING, Direction.SOUTH) };
    }

    private static void assertVanillaOracle(ProtoCube cube) {
        CubePos pos = cube.cc_getCubePos();
        ColumnSkyLightIndex index = new ColumnSkyLightIndex(pos.getX(), pos.getZ());
        index.put(pos, ChunkStatus.FULL, CubeSkyOcclusion.capture(cube));
        CubePos upper = CubePos.of(pos.getX(), pos.getY() + 1, pos.getZ());
        index.put(upper, ChunkStatus.FULL, CubeSkyOcclusion.capture(newCube(upper)));
        for (int tileZ = 0; tileZ < CubicConstants.DIAMETER_IN_SECTIONS; tileZ++) {
            for (int tileX = 0; tileX < CubicConstants.DIAMETER_IN_SECTIONS; tileX++) {
                ChunkSkyLightSources oracle = vanillaSources(cube, tileX, tileZ);
                for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                    for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                        int globalX = pos.minCubeX() + tileX * SectionPos.SECTION_SIZE + x;
                        int globalZ = pos.minCubeZ() + tileZ * SectionPos.SECTION_SIZE + z;
                        ColumnSkyLightResult result = index.query(globalX, globalZ, pos.getY(), upper.getY());
                        int expected = oracle.getLowestSourceY(x, z);
                        assertEquals(expected == ChunkSkyLightSources.NEGATIVE_INFINITY ? OptionalInt.empty() : OptionalInt.of(expected),
                                result.highestKnownBlockingY(), "Column " + globalX + ", " + globalZ);
                        assertEquals(expected == ChunkSkyLightSources.NEGATIVE_INFINITY ? ColumnSkyLightResult.State.CLEAR_WITHIN_RANGE
                                : ColumnSkyLightResult.State.KNOWN_BLOCKING_EDGE, result.state());
                        assertTrue(result.isComplete());
                    }
                }
            }
        }
    }

    private static ChunkSkyLightSources vanillaSources(ProtoCube cube, int tileX, int tileZ) {
        LevelHeightAccessor height = LevelHeightAccessor.create(cube.cc_getCubePos().minCubeY(), DIAMETER * 2);
        ProtoChunk vanilla = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height,
                PalettedContainerFactory.create(VanillaRegistriesExtension.shared()), null);
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    BlockState state = cube
                            .getSection(Coords.blockToIndex(tileX * SectionPos.SECTION_SIZE + x, y, tileZ * SectionPos.SECTION_SIZE + z))
                            .getBlockState(x, SectionPos.sectionRelative(y), z);
                    vanilla.getSection(y / SectionPos.SECTION_SIZE).setBlockState(x, SectionPos.sectionRelative(y), z, state);
                }
            }
        }
        ChunkSkyLightSources sources = new ChunkSkyLightSources(height);
        sources.fillFrom(vanilla);
        return sources;
    }
}
