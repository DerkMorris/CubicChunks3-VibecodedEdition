package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.BoundedCubeLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightSolution;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.LightRange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.Test;

class TestBoundedCubeLightSolver extends BaseTest {
    private static final int DIAMETER = CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int CENTER = DIAMETER / 2;
    private static final int MAX_LIGHT = 15;
    private static final CubeLightBoundary DARK = (pos, direction) -> Optional
            .of(new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), 0, 0));

    @Test
    void opaqueEmitterLightsAllSixDirectionsWithManhattanFalloff() {
        ProtoCube cube = cube(CubePos.ZERO);
        set(cube, CENTER, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
        CubeLightSolution solution = solve(cube, DARK);
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    int distance = Math.abs(x - CENTER) + Math.abs(y - CENTER) + Math.abs(z - CENTER);
                    assertExact(solution, LightLayer.BLOCK, new BlockPos(x, y, z), Math.max(0, MAX_LIGHT - distance));
                }
            }
        }
        assertTrue(solution.isReady(CubePos.ZERO));
    }

    @Test
    void boundaryLightAttenuatesOnEntryOnAllSixFacesExceptDirectDownwardSky() {
        ProtoCube cube = cube(CubePos.ZERO);
        for (Direction incoming : Direction.values()) {
            CubeLightBoundary boundary = (pos, direction) -> Optional.of(new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(),
                    direction == incoming ? MAX_LIGHT : 0, direction == incoming ? MAX_LIGHT : 0));
            CubeLightSolution solution = solve(cube, boundary);
            for (int depth = 0; depth < DIAMETER; depth++) {
                BlockPos pos = fromFace(incoming, depth);
                int expected = Math.max(0, MAX_LIGHT - 1 - depth);
                assertExact(solution, LightLayer.BLOCK, pos, expected);
                assertExact(solution, LightLayer.SKY, pos, incoming == Direction.DOWN ? MAX_LIGHT : expected);
            }
            assertTrue(solution.isReady(CubePos.ZERO));
        }
    }

    @Test
    void capturedStatesAndSolutionsRemainDetachedAfterSourceRemoval() {
        ProtoCube cube = cube(CubePos.ZERO);
        set(cube, CENTER, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
        CubeLightBlockSnapshot before = CubeLightBlockSnapshot.capture(cube);
        CubeLightBounds bounds = new CubeLightBounds(CubePos.ZERO, CubePos.ZERO);
        Map<CubePos, CubeLightBlockSnapshot> mutable = new HashMap<>(Map.of(CubePos.ZERO, before));
        CubeLightSolution old = BoundedCubeLightSolver.solve(bounds, mutable, DARK);
        mutable.clear();
        set(cube, CENTER, CENTER, CENTER, Blocks.AIR.defaultBlockState());
        CubeLightSolution retained = BoundedCubeLightSolver.solve(bounds, Map.of(CubePos.ZERO, before), DARK);
        CubeLightSolution changed = solve(cube, DARK);
        BlockPos source = new BlockPos(CENTER, CENTER, CENTER);
        assertExact(old, LightLayer.BLOCK, source, MAX_LIGHT);
        assertExact(retained, LightLayer.BLOCK, source, MAX_LIGHT);
        assertExact(changed, LightLayer.BLOCK, source, 0);
        assertTrue(old.sample(LightLayer.BLOCK, new BlockPos(-1, CENTER, CENTER)).isEmpty());
    }

    @Test
    void targetDampeningAndDirectionalFacesBlockACorridorWithoutBypass() {
        ProtoCube cube = verticalCorridor();
        set(cube, CENTER, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
        set(cube, CENTER, CENTER + 1, CENTER, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        CubeLightSolution bottom = solve(cube, DARK);
        assertExact(bottom, LightLayer.BLOCK, new BlockPos(CENTER, CENTER + 1, CENTER), 0);
        set(cube, CENTER, CENTER + 1, CENTER, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        CubeLightSolution top = solve(cube, DARK);
        assertExact(top, LightLayer.BLOCK, new BlockPos(CENTER, CENTER + 1, CENTER), MAX_LIGHT - 1);
        assertExact(top, LightLayer.BLOCK, new BlockPos(CENTER, CENTER + 2, CENTER), 0);
        set(cube, CENTER, CENTER + 1, CENTER, Blocks.TINTED_GLASS.defaultBlockState());
        assertExact(solve(cube, DARK), LightLayer.BLOCK, new BlockPos(CENTER, CENTER + 1, CENTER), 0);
    }

    @Test
    void directSkyRemainsFifteenUntilWaterThenContinuesDecayingDownward() {
        ProtoCube cube = verticalCorridor();
        CubeLightBoundary sky = (pos, direction) -> Optional
                .of(new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), 0, direction == Direction.DOWN ? MAX_LIGHT : 0));
        CubeLightSolution clear = solve(cube, sky);
        for (int y = 0; y < DIAMETER; y++) {
            assertExact(clear, LightLayer.SKY, new BlockPos(CENTER, y, CENTER), MAX_LIGHT);
        }
        int waterY = CENTER + 4;
        set(cube, CENTER, waterY, CENTER, Blocks.WATER.defaultBlockState());
        CubeLightSolution wet = solve(cube, sky);
        for (int y = 0; y < DIAMETER; y++) {
            int expected = y > waterY ? MAX_LIGHT : Math.max(0, MAX_LIGHT - 1 - (waterY - y));
            assertExact(wet, LightLayer.SKY, new BlockPos(CENTER, y, CENTER), expected);
        }
        assertTrue(wet.isReady(CubePos.ZERO));
    }

    @Test
    void interiorHolesStayUnknownAndNeverReachTheExternalBoundaryProvider() {
        CubeLightBounds bounds = new CubeLightBounds(CubePos.ZERO, CubePos.of(2, 0, 0));
        CubeLightBoundary boundary = (pos, direction) -> {
            assertFalse(bounds.contains(pos), "An interior hole must not consult an exterior-boundary proof");
            return DARK.sample(pos, direction);
        };
        Map<CubePos, CubeLightBlockSnapshot> known = Map.of(CubePos.ZERO, CubeLightBlockSnapshot.capture(cube(CubePos.ZERO)), CubePos.of(2, 0, 0),
                CubeLightBlockSnapshot.capture(cube(CubePos.of(2, 0, 0))));
        CubeLightSolution solution = BoundedCubeLightSolver.solve(bounds, known, boundary);
        assertEquals(new LightRange(0, MAX_LIGHT - 1), solution.sample(LightLayer.BLOCK, new BlockPos(DIAMETER - 1, CENTER, CENTER)).orElseThrow());
        assertEquals(new LightRange(0, MAX_LIGHT - 1), solution.sample(LightLayer.SKY, new BlockPos(DIAMETER - 1, CENTER, CENTER)).orElseThrow());
        assertExact(solution, LightLayer.BLOCK, new BlockPos(0, CENTER, CENTER), 0);
        assertTrue(solution.sample(LightLayer.BLOCK, new BlockPos(DIAMETER, CENTER, CENTER)).isEmpty());
        assertFalse(solution.isReady(CubePos.of(1, 0, 0)));
        assertFalse(solution.isReady(CubePos.ZERO, LightLayer.BLOCK));
    }

    @Test
    void unknownEnvelopesContainConcreteBoundaryCompletions() {
        ProtoCube cube = cube(CubePos.ZERO);
        set(cube, CENTER, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
        CubeLightSolution unknown = solve(cube, CubeLightBoundary.UNKNOWN);
        for (int completion = 0; completion < 3; completion++) {
            int seed = completion;
            CubeLightBoundary boundary = (pos, direction) -> {
                int block = Math.floorMod(pos.getX() * 3 + pos.getY() * 7 + pos.getZ() * 11 + seed, MAX_LIGHT + 1);
                int sky = Math.floorMod(pos.getX() + pos.getZ() + seed, MAX_LIGHT + 1);
                return Optional.of(new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), block, sky));
            };
            assertEncloses(unknown, solve(cube, boundary));
        }
        assertEquals(new LightRange(MAX_LIGHT, MAX_LIGHT), unknown.sample(LightLayer.BLOCK, new BlockPos(CENTER, CENTER, CENTER)).orElseThrow());
        assertEquals(new LightRange(0, MAX_LIGHT), unknown.sample(LightLayer.SKY, new BlockPos(CENTER, CENTER, CENTER - 1)).orElseThrow());
    }

    @Test
    void sealedDomainIsReadyDespiteUnknownExterior() {
        ProtoCube cube = cube(CubePos.ZERO);
        fill(cube, Blocks.STONE.defaultBlockState());
        for (int y = 4; y < DIAMETER - 4; y++) {
            for (int z = 4; z < DIAMETER - 4; z++) {
                for (int x = 4; x < DIAMETER - 4; x++) {
                    set(cube, x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }
        set(cube, CENTER, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
        CubeLightSolution solution = solve(cube, CubeLightBoundary.UNKNOWN);
        assertTrue(solution.isReady(CubePos.ZERO));
        assertExact(solution, LightLayer.BLOCK, new BlockPos(CENTER, CENTER, CENTER), MAX_LIGHT);
        assertExact(solution, LightLayer.SKY, new BlockPos(CENTER, CENTER, CENTER), 0);
        assertExact(solution, LightLayer.BLOCK, new BlockPos(0, CENTER, CENTER), 0);
    }

    @Test
    void completeCubeHaloMakesCentralBlockLightExactButDoesNotProveOpenSky() {
        CubeLightBounds bounds = new CubeLightBounds(CubePos.of(-1, -1, -1), CubePos.of(1, 1, 1));
        Map<CubePos, CubeLightBlockSnapshot> known = new HashMap<>();
        for (CubePos pos : bounds.positions()) {
            ProtoCube cube = cube(pos);
            if (pos.equals(CubePos.of(-1, 0, 0))) {
                set(cube, DIAMETER - 1, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
            }
            known.put(pos, CubeLightBlockSnapshot.capture(cube));
        }
        CubeLightSolution solution = BoundedCubeLightSolver.solve(bounds, known, CubeLightBoundary.UNKNOWN);
        assertTrue(solution.isReady(CubePos.ZERO, LightLayer.BLOCK));
        assertFalse(solution.isReady(CubePos.ZERO, LightLayer.SKY));
        assertFalse(solution.isReady(CubePos.ZERO));
        assertExact(solution, LightLayer.BLOCK, new BlockPos(0, CENTER, CENTER), MAX_LIGHT - 1);
        assertExact(solution, LightLayer.BLOCK, new BlockPos(DIAMETER - 1, CENTER, CENTER), 0);
        assertEquals(new LightRange(0, MAX_LIGHT), solution.sample(LightLayer.SKY, new BlockPos(CENTER, CENTER, CENTER)).orElseThrow());
    }

    @Test
    void negativeAndExtremeGlobalCoordinatesDoNotAliasPackedBlockPositions() {
        for (int coordinate : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, CubePos.MAX_COORDINATE_VALUE }) {
            CubePos pos = CubePos.of(coordinate, coordinate, coordinate);
            ProtoCube cube = cube(pos);
            set(cube, CENTER, CENTER, CENTER, Blocks.GLOWSTONE.defaultBlockState());
            CubeLightSolution solution = solve(cube, DARK);
            BlockPos source = new BlockPos(pos.minCubeX() + CENTER, pos.minCubeY() + CENTER, pos.minCubeZ() + CENTER);
            assertExact(solution, LightLayer.BLOCK, source, MAX_LIGHT);
            assertExact(solution, LightLayer.BLOCK, source.below(), MAX_LIGHT - 1);
            assertTrue(solution.isReady(pos));
        }
    }

    static ProtoCube cube(CubePos pos) {
        return new ProtoCube(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(pos.minCubeY(), DIAMETER),
                PalettedContainerFactory.create(VanillaRegistriesExtension.shared()), null);
    }

    static void set(ProtoCube cube, int x, int y, int z, BlockState state) {
        cube.getSection(Coords.blockToIndex(x, y, z)).setBlockState(SectionPos.sectionRelative(x), SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(z), state);
    }

    private static CubeLightSolution solve(ProtoCube cube, CubeLightBoundary boundary) {
        CubePos pos = cube.cc_getCubePos();
        return BoundedCubeLightSolver.solve(new CubeLightBounds(pos, pos), Map.of(pos, CubeLightBlockSnapshot.capture(cube)), boundary);
    }

    private static void fill(ProtoCube cube, BlockState state) {
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    set(cube, x, y, z, state);
                }
            }
        }
    }

    private static ProtoCube verticalCorridor() {
        ProtoCube cube = cube(CubePos.ZERO);
        fill(cube, Blocks.STONE.defaultBlockState());
        for (int y = 0; y < DIAMETER; y++) {
            set(cube, CENTER, y, CENTER, Blocks.AIR.defaultBlockState());
        }
        return cube;
    }

    private static BlockPos fromFace(Direction incoming, int depth) {
        int far = DIAMETER - 1 - depth;
        return switch (incoming) {
            case DOWN -> new BlockPos(CENTER, far, CENTER);
            case UP -> new BlockPos(CENTER, depth, CENTER);
            case EAST -> new BlockPos(depth, CENTER, CENTER);
            case WEST -> new BlockPos(far, CENTER, CENTER);
            case NORTH -> new BlockPos(CENTER, CENTER, far);
            case SOUTH -> new BlockPos(CENTER, CENTER, depth);
        };
    }

    private static void assertExact(CubeLightSolution solution, LightLayer layer, BlockPos pos, int expected) {
        assertEquals(new LightRange(expected, expected), solution.sample(layer, pos).orElseThrow(), layer + " at " + pos);
    }

    private static void assertEncloses(CubeLightSolution unknown, CubeLightSolution concrete) {
        for (int y = 0; y < DIAMETER; y++) {
            for (int z = 0; z < DIAMETER; z++) {
                for (int x = 0; x < DIAMETER; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    for (LightLayer layer : LightLayer.values()) {
                        LightRange bounds = unknown.sample(layer, pos).orElseThrow();
                        LightRange actual = concrete.sample(layer, pos).orElseThrow();
                        assertTrue(actual.isReady());
                        assertTrue(bounds.lower() <= actual.lower() && actual.upper() <= bounds.upper(), layer + " at " + pos);
                    }
                }
            }
        }
    }
}
