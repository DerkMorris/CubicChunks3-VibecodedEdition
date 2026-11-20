package io.github.opencubicchunks.cubicchunks.test.world.level.gameevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkCatalystBlock;
import net.minecraft.world.level.block.entity.SculkCatalystBlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native catalyst users consume native animal XP once and create a full-height cursor across a vertical cube seam. */
class TestCubeSculkCatalyst extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nearestNativeCatalystConsumesDeathXpOnceAndBloomsAcrossCubeSeam(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            doCallRealMethod().when(world.level).setBlockAndUpdate(any(), any());
            doAnswer(call -> {
                world.block(call.getArgument(0), call.getArgument(1));
                return true;
            }).when(world.level).setBlock(any(), any(), anyInt());
            BlockPos near = new BlockPos(16, y + 1, 16);
            BlockPos far = new BlockPos(16, y - 3, 16);
            BlockPos origin = new BlockPos(16, y + 3, 16);
            var farCatalyst = catalyst(world, far);
            var nearCatalyst = catalyst(world, near);
            var source = EntityTypes.COW.create(world.level, EntitySpawnReason.LOAD);
            assertNotNull(source);
            source.snapTo(Vec3.atBottomCenterOf(origin));
            source.setHealth(0);
            assertFalse(source.wasExperienceConsumed());
            var dispatcher = new GameEventDispatcher(world.level);
            dispatcher.post(GameEvent.ENTITY_DIE, Vec3.atCenterOf(origin), GameEvent.Context.of(source));
            assertTrue(source.wasExperienceConsumed());
            var nearCursors = nearCatalyst.getListener().getSculkSpreader().getCursors();
            assertEquals(1, nearCursors.size());
            assertEquals(origin.above(), nearCursors.getFirst().getPos());
            int charge = nearCursors.getFirst().getCharge();
            assertTrue(charge >= 1 && charge <= 3, "Native adult cow XP must become sculk charge");
            assertTrue(farCatalyst.getListener().getSculkSpreader().getCursors().isEmpty());
            assertTrue(world.level.getBlockState(near).getValue(SculkCatalystBlock.PULSE));
            assertFalse(world.level.getBlockState(far).getValue(SculkCatalystBlock.PULSE));
            verify(world.level).scheduleTick(near, Blocks.SCULK_CATALYST, 8);
            dispatcher.post(GameEvent.ENTITY_DIE, Vec3.atCenterOf(origin), GameEvent.Context.of(source));
            assertEquals(1, nearCursors.size());
            assertEquals(charge, nearCursors.getFirst().getCharge());
            assertTrue(farCatalyst.getListener().getSculkSpreader().getCursors().isEmpty());
            world.level.getBlockState(near).tick(world.level, near, world.level.getRandom());
            assertFalse(world.level.getBlockState(near).getValue(SculkCatalystBlock.PULSE));
        }
    }

    private static SculkCatalystBlockEntity catalyst(CubeMobTestWorld world, BlockPos position) {
        var cube = world.cube(position);
        cube.setLoaded(true);
        world.block(position, Blocks.SCULK_CATALYST.defaultBlockState());
        var result = (SculkCatalystBlockEntity) cube.getBlockEntity(position);
        assertNotNull(result);
        return result;
    }
}
