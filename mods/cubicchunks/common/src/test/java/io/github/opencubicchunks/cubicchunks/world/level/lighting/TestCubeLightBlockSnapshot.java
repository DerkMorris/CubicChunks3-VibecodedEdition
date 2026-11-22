package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class TestCubeLightBlockSnapshot extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-17, -9000, -3);

    @Test
    void packedCaptureDetachesAllSectionsAtNegativeCoordinatesAndMatchesFlatStates() {
        LevelCube cube = patternedCube();
        CubeLightBlockSnapshot packed = CubeLightBlockSnapshot.capture(cube);
        CubeLightBlockSnapshot flat = CubeLightBlockSnapshot.create(POSITION, cube::getBlockState);
        for (var section : cube.getSections()) {
            section.setBlockState(0, 0, 0, Blocks.BEDROCK.defaultBlockState(), false);
            // More than sixteen distinct states forces the original four-bit palette to grow after capture.
            for (int state = 0; state < 40; state++) {
                section.setBlockState(state & 15, state >> 4, 1, Block.BLOCK_STATE_REGISTRY.byId(state), false);
            }
        }
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertSame(pattern(x, y, z), packed.state(x, y, z));
                }
            }
        }
        assertTrue(packed.sameStates(flat));
        assertTrue(flat.sameStates(packed));
        assertFalse(packed.sameStates(CubeLightBlockSnapshot.capture(cube)));
        assertThrows(IllegalArgumentException.class, () -> packed.state(-1, 0, 0));
    }

    @Test
    void packedRowsCrossTheSixteenBlockBoundaryWithoutTouchingAdjacentOutput() {
        CubeLightBlockSnapshot packed = CubeLightBlockSnapshot.capture(patternedCube());
        for (int y : new int[] { 0, 15, 16, 31 }) {
            for (int z : new int[] { 0, 15, 16, 31 }) {
                for (int x : new int[] { 0, 13, 15, 16, 30, 31 }) {
                    BlockState[] row = new BlockState[40];
                    Arrays.fill(row, Blocks.BEDROCK.defaultBlockState());
                    packed.copyRow(x, y, z, row, 3, 32 - x);
                    for (int index = 0; index < row.length; index++) {
                        BlockState expected = index >= 3 && index < 35 - x ? pattern(x + index - 3, y, z) : Blocks.BEDROCK.defaultBlockState();
                        assertSame(expected, row[index]);
                    }
                }
            }
        }
    }

    @Test
    void uniformCaptureAndUniformFactoryMatchFlatInputAndRemainDetached() {
        var cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        var packed = CubeLightBlockSnapshot.capture(cube);
        var uniform = CubeLightBlockSnapshot.uniform(POSITION, Blocks.AIR.defaultBlockState());
        var flat = CubeLightBlockSnapshot.create(POSITION, ignored -> Blocks.AIR.defaultBlockState());
        assertTrue(packed.sameStates(uniform));
        assertTrue(uniform.sameStates(flat));
        assertTrue(flat.sameStates(packed));
        cube.getSection(0).setBlockState(0, 0, 0, Blocks.GLOWSTONE.defaultBlockState(), false);
        assertSame(Blocks.AIR.defaultBlockState(), packed.state(0, 0, 0));
        BlockState[] row = new BlockState[32];
        packed.copyRow(0, 31, 31, row, 0, 32);
        assertTrue(Arrays.stream(row).allMatch(state -> state == Blocks.AIR.defaultBlockState()));
        uniform.copyRow(0, 0, 0, row, 0, 32);
        assertTrue(Arrays.stream(row).allMatch(state -> state == Blocks.AIR.defaultBlockState()));
        assertFalse(packed.sameStates(CubeLightBlockSnapshot.uniform(CubePos.ZERO, Blocks.AIR.defaultBlockState())));
    }

    @Test
    void packedAndFlatSnapshotsProduceIdenticalPackedSolverOutput() {
        LevelCube cube = patternedCube();
        Map<CubePos, CubeLightBlockSnapshot> inputs = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    CubePos pos = CubePos.of(POSITION.getX() + dx, POSITION.getY() + dy, POSITION.getZ() + dz);
                    inputs.put(pos, CubeLightBlockSnapshot.uniform(pos, Blocks.AIR.defaultBlockState()));
                }
            }
        }
        inputs.put(POSITION, CubeLightBlockSnapshot.create(POSITION, cube::getBlockState));
        CubeLightView flat = CubeWorldLightSolver.solve(POSITION, inputs, (x, z) -> POSITION.minCubeY() + 20);
        inputs.put(POSITION, CubeLightBlockSnapshot.capture(cube));
        CubeLightView packed = CubeWorldLightSolver.solve(POSITION, inputs, (x, z) -> POSITION.minCubeY() + 20);
        assertTrue(flat.isReady());
        assertTrue(packed.isReady());
        for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
            for (LightLayer layer : LightLayer.values()) {
                assertArrayEquals(flat.sectionData(layer, section).orElseThrow().getData(),
                        packed.sectionData(layer, section).orElseThrow().getData());
            }
        }
    }

    private static LevelCube patternedCube() {
        var cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    cube.getSection(Coords.blockToIndex(x, y, z)).setBlockState(x & 15, y & 15, z & 15, pattern(x, y, z), false);
                }
            }
        }
        return cube;
    }

    private static BlockState pattern(int x, int y, int z) {
        int index = (Coords.blockToIndex(x, y, z) + ((x & 15) < 8 ? 0 : 1)) % 8;
        return switch (index) {
            case 0 -> Blocks.AIR.defaultBlockState();
            case 1 -> Blocks.STONE.defaultBlockState();
            case 2 -> Blocks.DIRT.defaultBlockState();
            case 3 -> Blocks.GLASS.defaultBlockState();
            case 4 -> Blocks.OAK_SLAB.defaultBlockState();
            case 5 -> Blocks.GLOWSTONE.defaultBlockState();
            case 6 -> Blocks.WATER.defaultBlockState();
            default -> Blocks.DEEPSLATE.defaultBlockState();
        };
    }
}
