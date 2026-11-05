package io.github.opencubicchunks.cubicchunks.test.world.level.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.BoundedCubeLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightSolution;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightManager;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeWorldLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.LightRange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

/** Compares the independent bounded solver with actual 26.3 block and sky engines in explicitly allocated sections. */
class TestCubeLightVanillaParity extends BaseTest {
    @Test
    void blockLightCrossesCubeFacesAndRebuildsAfterSourceRemoval() {
        Scene scene = new Scene(new CubeLightBounds(CubePos.of(-2, -1, 1), CubePos.of(-1, -1, 1)));
        scene.shell(true);
        scene.set(31, 16, 16, Blocks.GLOWSTONE.defaultBlockState());
        scene.set(37, 12, 16, Blocks.SEA_LANTERN.defaultBlockState());
        scene.set(32, 16, 18, Blocks.WATER.defaultBlockState());
        scene.set(33, 16, 17, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        CubeLightSolution first = scene.assertVanilla(false);
        assertEquals(14, first.sample(LightLayer.BLOCK, scene.pos(32, 16, 16)).orElseThrow().exactValue().orElseThrow());

        scene.set(31, 16, 16, Blocks.AIR.defaultBlockState());
        scene.set(37, 12, 16, Blocks.AIR.defaultBlockState());
        CubeLightSolution removed = scene.assertVanilla(false);
        assertEquals(0, removed.sample(LightLayer.BLOCK, scene.pos(32, 16, 16)).orElseThrow().exactValue().orElseThrow());
        assertEquals(14, first.sample(LightLayer.BLOCK, scene.pos(32, 16, 16)).orElseThrow().exactValue().orElseThrow());
    }

    @Test
    void skyLightMatchesVanillaThroughRoofOpeningWaterAndDirectedShapes() {
        Scene scene = new Scene(new CubeLightBounds(CubePos.of(-1, -1, 2), CubePos.of(-1, -1, 2)));
        scene.shell(false);
        for (int z = 1; z < scene.bounds.sizeZ() - 1; z++) {
            for (int x = 1; x < scene.bounds.sizeX() - 1; x++) {
                if (x != 16 || z != 16) {
                    scene.set(x, 24, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        scene.set(17, 22, 16, Blocks.WATER.defaultBlockState());
        scene.set(15, 20, 15, Blocks.OAK_LEAVES.defaultBlockState());
        scene.set(18, 21, 16, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        scene.set(18, 20, 16, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        scene.set(16, 19, 18, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.FACING, Direction.SOUTH));
        scene.set(8, 12, 16, Blocks.GLOWSTONE.defaultBlockState());
        CubeLightSolution open = scene.assertVanilla(true);
        assertEquals(15, open.sample(LightLayer.SKY, scene.pos(16, 23, 16)).orElseThrow().exactValue().orElseThrow());
        assertTrue(open.sample(LightLayer.SKY, scene.pos(17, 23, 16)).orElseThrow().upper() < 15);

        scene.set(16, 24, 16, Blocks.STONE.defaultBlockState());
        CubeLightSolution roofClosed = scene.assertVanilla(true);
        assertEquals(0, roofClosed.sample(LightLayer.SKY, scene.pos(16, 23, 16)).orElseThrow().exactValue().orElseThrow());
    }

    private static final class Scene {
        private final CubeLightBounds bounds;
        private final PalettedContainerFactory palettes = PalettedContainerFactory.create(VanillaRegistriesExtension.shared());
        private final Map<CubePos, ProtoCube> cubes = new HashMap<>();

        private Scene(CubeLightBounds bounds) {
            this.bounds = bounds;
            for (CubePos pos : bounds.positions()) {
                this.cubes.put(pos, new ProtoCube(pos, UpgradeData.EMPTY,
                        LevelHeightAccessor.create(pos.minCubeY(), CubicConstants.DIAMETER_IN_BLOCKS), this.palettes, null));
            }
        }

        private BlockPos pos(int x, int y, int z) {
            return new BlockPos(this.bounds.minBlockX() + x, this.bounds.minBlockY() + y, this.bounds.minBlockZ() + z);
        }

        private void set(int x, int y, int z, BlockState state) {
            BlockPos pos = pos(x, y, z);
            CubePos cubePos = CubePos.of(Coords.blockToCube(pos.getX()), Coords.blockToCube(pos.getY()), Coords.blockToCube(pos.getZ()));
            this.cubes.get(cubePos).setBlockState(pos, state);
        }

        private void shell(boolean roof) {
            for (int y = 0; y < this.bounds.sizeY(); y++) {
                for (int z = 0; z < this.bounds.sizeZ(); z++) {
                    for (int x = 0; x < this.bounds.sizeX(); x++) {
                        if (x == 0 || z == 0 || y == 0 || x == this.bounds.sizeX() - 1 || z == this.bounds.sizeZ() - 1
                                || roof && y == this.bounds.sizeY() - 1) {
                            set(x, y, z, Blocks.STONE.defaultBlockState());
                        }
                    }
                }
            }
        }

        private CubeLightSolution assertVanilla(boolean skyAbove) {
            Map<CubePos, CubeLightBlockSnapshot> snapshots = new HashMap<>();
            this.cubes.forEach((pos, cube) -> snapshots.put(pos, CubeLightBlockSnapshot.capture(cube)));
            int ceiling = this.bounds.minBlockY() + this.bounds.sizeY();
            CubeLightBoundary boundary = (outside, direction) -> Optional
                    .of(new CubeLightBoundary.BoundaryLight(Blocks.AIR.defaultBlockState(), 0, skyAbove && outside.getY() == ceiling ? 15 : 0));
            CubeLightSolution solution = BoundedCubeLightSolver.solve(this.bounds, snapshots, boundary);
            LevelLightEngine vanilla = vanillaEngine();
            Map<CubePos, CubeLightView> automatic = new HashMap<>();
            for (CubePos center : bounds.positions()) {
                Map<CubePos, CubeLightBlockSnapshot> halo = new HashMap<>();
                for (CubePos pos : CubeWorldLightManager.halo(center).positions()) {
                    halo.put(pos, CubeLightBlockSnapshot.create(pos, this::state));
                }
                automatic.put(center, CubeWorldLightSolver.solve(center, halo, this::skyEdge));
            }
            for (CubePos cube : this.cubes.keySet()) {
                assertTrue(solution.isReady(cube));
            }
            for (int y = 0; y < this.bounds.sizeY(); y++) {
                for (int z = 0; z < this.bounds.sizeZ(); z++) {
                    for (int x = 0; x < this.bounds.sizeX(); x++) {
                        BlockPos pos = pos(x, y, z);
                        for (LightLayer layer : LightLayer.values()) {
                            LightRange light = solution.sample(layer, pos).orElseThrow();
                            int expected = vanilla.getLayerListener(layer).getLightValue(pos);
                            assertEquals(expected, light.lower(), () -> layer + " at " + pos);
                            assertEquals(expected, light.upper(), () -> layer + " at " + pos);
                            assertEquals(expected, automatic.get(CubePos.from(pos)).sample(layer, pos).orElseThrow().exactValue().orElseThrow(),
                                    () -> "Automatic " + layer + " at " + pos);
                        }
                    }
                }
            }
            return solution;
        }

        private BlockState state(BlockPos pos) {
            ProtoCube cube = cubes.get(CubePos.from(pos));
            return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
        }

        private int skyEdge(int x, int z) {
            BlockState upper = Blocks.AIR.defaultBlockState();
            for (int y = bounds.minBlockY() + bounds.sizeY() - 1; y >= bounds.minBlockY(); y--) {
                BlockState lower = state(new BlockPos(x, y, z));
                if (CubeSkyOcclusion.isEdgeOccluded(upper, lower)) {
                    return y + 1;
                }
                upper = lower;
            }
            return Integer.MIN_VALUE;
        }

        private LevelLightEngine vanillaEngine() {
            Map<Long, ProtoChunk> chunks = vanillaChunks();
            ServerLevel level = mock(ServerLevel.class);
            Misc.setupLevelHeight(level, this.bounds.minBlockY(), this.bounds.sizeY());
            LightChunkGetter getter = new LightChunkGetter() {
                @Override public LightChunk getChunkForLighting(int x, int z) {
                    return chunks.get(ChunkPos.pack(x, z));
                }

                @Override public BlockGetter getLevel() {
                    return level;
                }
            };
            LevelLightEngine engine = new LevelLightEngine(getter, true, true);
            for (ProtoChunk chunk : chunks.values()) {
                chunk.initializeLightSources();
                for (int section = chunk.getMinSectionY(); section <= chunk.getMaxSectionY(); section++) {
                    engine.updateSectionStatus(SectionPos.of(chunk.getPos().x(), section, chunk.getPos().z()), false);
                }
            }
            drain(engine);
            chunks.values().forEach(chunk -> engine.propagateLightSources(chunk.getPos()));
            drain(engine);
            return engine;
        }

        private Map<Long, ProtoChunk> vanillaChunks() {
            Map<Long, ProtoChunk> chunks = new HashMap<>();
            LevelHeightAccessor height = LevelHeightAccessor.create(this.bounds.minBlockY(), this.bounds.sizeY());
            for (ProtoCube cube : this.cubes.values()) {
                CubePos cubePos = cube.cc_getCubePos();
                for (int z = 0; z < CubicConstants.DIAMETER_IN_BLOCKS; z++) {
                    for (int x = 0; x < CubicConstants.DIAMETER_IN_BLOCKS; x++) {
                        int globalX = cubePos.minCubeX() + x;
                        int globalZ = cubePos.minCubeZ() + z;
                        ChunkPos chunkPos = new ChunkPos(SectionPos.blockToSectionCoord(globalX), SectionPos.blockToSectionCoord(globalZ));
                        ProtoChunk chunk = chunks.computeIfAbsent(chunkPos.pack(),
                                ignored -> new ProtoChunk(chunkPos, UpgradeData.EMPTY, height, this.palettes, null));
                        for (int y = 0; y < CubicConstants.DIAMETER_IN_BLOCKS; y++) {
                            int globalY = cubePos.minCubeY() + y;
                            BlockState state = cube.getSection(Coords.blockToIndex(x, y, z)).getBlockState(SectionPos.sectionRelative(x),
                                    SectionPos.sectionRelative(y), SectionPos.sectionRelative(z));
                            chunk.getSection(chunk.getSectionIndex(globalY)).setBlockState(SectionPos.sectionRelative(globalX),
                                    SectionPos.sectionRelative(globalY), SectionPos.sectionRelative(globalZ), state);
                        }
                    }
                }
            }
            return chunks;
        }

        private static void drain(LevelLightEngine engine) {
            for (int pass = 0; pass < 100 && engine.hasLightWork(); pass++) {
                engine.runLightUpdates();
            }
            assertFalse(engine.hasLightWork(), "The actual vanilla light engine must reach a fixed point");
        }
    }
}
