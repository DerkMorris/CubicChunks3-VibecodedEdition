package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual vanilla pattern matching and boss construction; combat and boss phases are deliberately outside this test. */
class TestCubeWitherSummoning extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "wither structure at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void completeSkullPatternSummonsBossAcrossCubeSeamsAndConsumesOnlyTheStructure(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y - 2);
            when(world.level.getDifficulty()).thenReturn(Difficulty.NORMAL);
            List<Entity> created = new ArrayList<>();
            when(world.level.addFreshEntity(any())).thenAnswer(call -> created.add(call.getArgument(0)));
            when(world.level.setBlock(any(BlockPos.class), any(BlockState.class), anyInt())).thenAnswer(call -> {
                world.block(call.getArgument(0), call.getArgument(1));
                return true;
            });
            BlockPos skull = new BlockPos(31, y + 1, 2);
            world.block(skull.below(2), Blocks.SOUL_SAND.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++) {
                world.block(skull.offset(dx, -1, 0), Blocks.SOUL_SAND.defaultBlockState());
            }
            assertTrue(WitherSkullBlock.canSpawnMob(world.level, skull, new ItemStack(Items.WITHER_SKELETON_SKULL)));
            assertFalse(WitherSkullBlock.canSpawnMob(world.level, skull, new ItemStack(Items.SKELETON_SKULL)));
            for (int dx = -1; dx <= 1; dx++) {
                world.block(skull.offset(dx, 0, 0), Blocks.WITHER_SKELETON_SKULL.defaultBlockState());
            }
            SkullBlockEntity placed = new SkullBlockEntity(skull, Blocks.WITHER_SKELETON_SKULL.defaultBlockState());
            when(world.level.getDifficulty()).thenReturn(Difficulty.PEACEFUL);
            WitherSkullBlock.checkSpawn(world.level, skull, placed);
            assertTrue(created.isEmpty(), "Peaceful must still reject the complete pattern");
            when(world.level.getDifficulty()).thenReturn(Difficulty.NORMAL);
            WitherSkullBlock.checkSpawn(world.level, skull, placed);
            assertEquals(1, created.size());
            assertEquals(EntityTypes.WITHER, created.getFirst().getType());
            assertEquals(y - 1 + 0.55, created.getFirst().getY(), 1.0E-7);
            for (int dx = -1; dx <= 1; dx++) {
                assertTrue(world.level.getBlockState(skull.offset(dx, 0, 0)).isAir());
                assertTrue(world.level.getBlockState(skull.offset(dx, -1, 0)).isAir());
            }
            assertTrue(world.level.getBlockState(skull.below(2)).isAir());
            assertTrue(world.level.getBlockState(skull.below(4)).is(Blocks.STONE), "The room floor must remain intact");
        }
    }
}
