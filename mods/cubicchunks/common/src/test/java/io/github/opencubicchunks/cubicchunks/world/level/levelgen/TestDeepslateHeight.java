package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.Test;

class TestDeepslateHeight extends BaseTest {
    @Test
    void transitionUsesAbsoluteHeightAndStableSeededMixtureAtBothSigns() {
        for (int height : new int[] { -32000000, -128, 128, 32000000 }) {
            var strata = new DeepslateStrata(37, height);
            var same = new DeepslateStrata(37, height);
            int slate = 0;
            for (int x = -32; x < 32; x++) {
                assertSame(Blocks.STONE, strata.rock(x, height, 17));
                assertSame(Blocks.DEEPSLATE, strata.rock(x, height - 8, 17));
                assertSame(strata.rock(x, height - 4, 17), same.rock(x, height - 4, 17));
                if (strata.rock(x, height - 4, 17) == Blocks.DEEPSLATE) {
                    slate++;
                }
            }
            assertTrue(slate > 0 && slate < 64, "The transition must contain both rock types");
        }
    }

    @Test
    void onlyNaturalRockAndMatchingOreVariantsChange() {
        var strata = new DeepslateStrata(7, -128);
        for (var block : new net.minecraft.world.level.block.Block[] { Blocks.AIR, Blocks.WATER, Blocks.TUFF, Blocks.GRANITE, Blocks.ANDESITE,
                Blocks.CALCITE, Blocks.BEDROCK, Blocks.CHEST, Blocks.DEEPSLATE_BRICKS }) {
            assertSame(block.defaultBlockState(), strata.remap(block.defaultBlockState(), 0, -200, 0));
        }
        for (var ore : OreKind.values()) {
            assertTrue(strata.remap(ore.stone().defaultBlockState(), 0, -200, 0).is(ore.slate()));
            assertTrue(strata.remap(ore.slate().defaultBlockState(), 0, -64, 0).is(ore.stone()));
        }
        var lit = Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        var moved = strata.remap(lit, 0, -64, 0);
        assertTrue(moved.is(Blocks.REDSTONE_ORE));
        assertTrue(moved.getValue(BlockStateProperties.LIT));
    }

    @Test
    void nativeDefaultIsUnchangedButCustomHeightMovesTheNaturalBand() {
        var level = mockLevelWithPalettes(Level.class);
        var chunk = new ProtoChunk(new ChunkPos(-1, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384),
                level.palettedContainerFactory(), null);
        var slate = new BlockPos(-1, -32, 0);
        var stone = new BlockPos(-1, 96, 0);
        var granite = stone.above();
        chunk.setBlockState(slate, Blocks.DEEPSLATE.defaultBlockState(), 0);
        chunk.setBlockState(stone, Blocks.STONE.defaultBlockState(), 0);
        chunk.setBlockState(granite, Blocks.GRANITE.defaultBlockState(), 0);
        new DeepslateStrata(7, 8).applyNative(chunk);
        assertTrue(chunk.getBlockState(slate).is(Blocks.DEEPSLATE));
        assertTrue(chunk.getBlockState(stone).is(Blocks.STONE));
        new DeepslateStrata(7, -128).applyNative(chunk);
        assertTrue(chunk.getBlockState(slate).is(Blocks.STONE));
        new DeepslateStrata(7, 200).applyNative(chunk);
        assertTrue(chunk.getBlockState(stone).is(Blocks.DEEPSLATE));
        assertTrue(chunk.getBlockState(granite).is(Blocks.GRANITE));
        assertTrue(chunk.getBlockState(stone.east()).isAir());
    }

    @Test
    void classicSectionsHonorCustomAndDefaultHeightsWithoutCaves() {
        var palettes = mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var custom = new DeepCaveTerrain(37, settings(false, -128), palettes);
        var standard = new DeepCaveTerrain(37, DeepCaveSettings.CLASSIC, palettes);
        for (int y = 0; y < 16; y++) {
            assertTrue(custom.section(-1, -5, 0).getBlockState(0, y, 0).is(Blocks.STONE));
            assertTrue(custom.section(-1, -10, 0).getBlockState(0, y, 0).is(Blocks.DEEPSLATE));
            assertTrue(standard.section(-1, -5, 0).getBlockState(0, y, 0).is(Blocks.DEEPSLATE));
        }
    }

    @Test
    void movingStrataPreservesCaveGeometryOtherRocksAndOrePositions() {
        var palettes = mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var standard = new DeepCaveTerrain(37, settings(true, 8), palettes);
        var lower = new DeepCaveTerrain(37, settings(true, -256), palettes);
        int changed = 0;
        for (int sx = -1; sx <= 1; sx++) {
            var before = standard.section(sx, -10, 0);
            var after = lower.section(sx, -10, 0);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        var old = before.getBlockState(x, y, z);
                        var current = after.getBlockState(x, y, z);
                        assertEquals(old.isAir(), current.isAir());
                        if (old != current) {
                            changed++;
                            assertTrue(old.is(Blocks.DEEPSLATE) && current.is(Blocks.STONE)
                                    || java.util.Arrays.stream(OreKind.values()).anyMatch(ore -> old.is(ore.slate()) && current.is(ore.stone())));
                        }
                    }
                }
            }
        }
        assertTrue(changed > 0);
    }

    @Test
    void actualClassicCubeAndLightingFallbackAgreeAndOtherDimensionsStayUnchanged() {
        for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            var level = mockLevelWithPalettes(ServerLevel.class);
            when(level.dimension()).thenReturn(dimension);
            when(level.getMinY()).thenReturn(-64);
            when(level.getMaxY()).thenReturn(319);
            var terrain = new NativeBiomeTerrain(level, Runnable::run);
            var pos = CubePos.of(-1, -1000, 0);
            var cube = new ProtoCube(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            terrain.fill(cube).join();
            var expected = dimension == Level.OVERWORLD ? Blocks.DEEPSLATE : Blocks.STONE;
            var at = pos.asBlockPos(15, 0, 31);
            assertTrue(cube.getBlockState(at).is(expected));
            var snapshot = terrain.blocks(pos).join();
            assertTrue(snapshot.sameStates(CubeLightBlockSnapshot.capture(cube)));
        }
    }

    private static DeepCaveSettings settings(boolean caves, int height) {
        return new DeepCaveSettings(caves, 64, 45, 100, Map.of(), Map.of(), new WorldHeightSettings(192, height, 0));
    }
}
