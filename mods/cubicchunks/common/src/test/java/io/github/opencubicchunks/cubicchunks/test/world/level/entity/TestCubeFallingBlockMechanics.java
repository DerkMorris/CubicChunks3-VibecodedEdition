package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native scheduled block callbacks must create falling entities even below the dimension's ordinary minimum. */
class TestCubeFallingBlockMechanics extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "falling source blocks at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void unsupportedGravityAndBrushableBlocksBecomeEntitiesWithoutDuplicatingTheirSource(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            world.floor(y);
            List<Entity> created = new ArrayList<>();
            when(world.level.addFreshEntity(any())).thenAnswer(call -> created.add(call.getArgument(0)));
            when(world.level.setBlockAndUpdate(any(BlockPos.class), any(BlockState.class))).thenAnswer(call -> {
                world.block(call.getArgument(0), call.getArgument(1));
                return true;
            });
            BlockPos pos = new BlockPos(2, y + 2, 2);
            for (Block block : List.of(Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
                    Blocks.CONCRETE_POWDER.white(), Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL)) {
                created.clear();
                BlockState state = block.defaultBlockState();
                world.block(pos.below(), Blocks.STONE.defaultBlockState());
                world.block(pos, state);
                state.tick(world.level, pos, RandomSource.create(1));
                assertTrue(created.isEmpty(), block + " must stay supported");
                world.block(pos.below(), Blocks.AIR.defaultBlockState());
                state.tick(world.level, pos, RandomSource.create(1));
                assertEquals(1, created.size(), block + " must begin falling at Y=" + y);
                FallingBlockEntity falling = assertInstanceOf(FallingBlockEntity.class, created.getFirst());
                assertEquals(state, falling.getBlockState(), block.toString());
                assertEquals(pos, falling.blockPosition(), block.toString());
                assertTrue(world.level.getBlockState(pos).isAir(), block + " source must be removed exactly once");
            }
            if (y < -64) {
                created.clear();
                when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
                world.block(pos, Blocks.SAND.defaultBlockState());
                Blocks.SAND.defaultBlockState().tick(world.level, pos, RandomSource.create(1));
                assertTrue(created.isEmpty(), "Ordinary worlds keep the native lower bound");
            }
        }
    }
}
