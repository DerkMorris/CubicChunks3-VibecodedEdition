package io.github.opencubicchunks.cubicchunks.test.world.level.gameevent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipBlockStateContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native placed shrieker event, travel, source filtering and scheduled state transition; summon flow is separate. */
class TestCubeSculkShrieker extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeShriekerReceivesPlayerEventAcrossSeamAndResetsOnScheduledTick(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            ServerPlayer source = mock();
            f.post(GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, source);
            f.tick(2);
            assertNotNull(f.shrieker.getVibrationData().getCurrentVibration());
            assertFalse(f.shrieking());
            f.tick(10);
            assertNull(f.shrieker.getVibrationData().getCurrentVibration());
            assertTrue(f.shrieking());
            verify(f.world.level).scheduleTick(f.receiver, Blocks.SCULK_SHRIEKER, 90);
            verify(f.world.level).levelEvent(3007, f.receiver, 0);
            f.post(GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, source);
            f.tick(12);
            verify(f.world.level, times(1)).scheduleTick(f.receiver, Blocks.SCULK_SHRIEKER, 90);
            f.world.level.getBlockState(f.receiver).tick(f.world.level, f.receiver, f.world.level.getRandom());
            assertFalse(f.shrieking());
            f.world.cube(f.receiver).removeBlockEntity(f.receiver);
            assertTrue(f.world.cube(f.receiver).cc_getListenerRegistry(f.receiver).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeShriekerRejectsUnrelatedEventNonPlayerAndOccludedPlayer(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            ServerPlayer source = mock();
            f.post(GameEvent.STEP, source);
            f.tick(12);
            assertFalse(f.shrieking());
            f.post(GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, null);
            f.tick(12);
            assertFalse(f.shrieking());
            f.world.block(f.receiver.below(2), Blocks.WOOL.white().defaultBlockState());
            f.post(GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, source);
            f.tick(12);
            assertFalse(f.shrieking());
            f.world.block(f.receiver.below(2), Blocks.AIR.defaultBlockState());
            f.post(GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, source);
            f.tick(12);
            assertTrue(f.shrieking());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CubeMobTestWorld world;
        private final BlockPos receiver;
        private final SculkShriekerBlockEntity shrieker;
        private long time;

        private Fixture(int y, Path directory) throws Exception {
            world = new CubeMobTestWorld(directory);
            receiver = new BlockPos(16, y, 16);
            when(world.level.getGameTime()).thenAnswer(call -> time);
            when(world.distance.inBlockTickingRange(anyLong())).thenReturn(true);
            doCallRealMethod().when(world.level).shouldTickBlocksAt(any(BlockPos.class));
            doCallRealMethod().when(world.level).isBlockInLine(any(ClipBlockStateContext.class));
            doCallRealMethod().when(world.level).setBlockAndUpdate(any(), any());
            doAnswer(call -> {
                world.block(call.getArgument(0), call.getArgument(1));
                return true;
            }).when(world.level).setBlock(any(), any(), anyInt());
            var section = SectionPos.of(receiver);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        world.cube(SectionPos.of(section.x() + x, section.y() + dy, section.z() + z).origin());
                    }
                }
            }
            var cube = world.cube(receiver);
            cube.setLoaded(true);
            world.block(receiver, Blocks.SCULK_SHRIEKER.defaultBlockState());
            shrieker = (SculkShriekerBlockEntity) cube.getBlockEntity(receiver);
            assertNotNull(shrieker);
        }

        private boolean shrieking() {
            return world.level.getBlockState(receiver).getValue(SculkShriekerBlock.SHRIEKING);
        }

        private void post(Holder<GameEvent> event, Entity source) {
            new GameEventDispatcher(world.level).post(event, Vec3.atCenterOf(receiver.below(4)), GameEvent.Context.of(source));
        }

        private void tick(int ticks) {
            for (int i = 0; i < ticks; i++) {
                time++;
                VibrationSystem.Ticker.tick(world.level, shrieker.getVibrationData(), shrieker.getVibrationUser());
            }
        }

        @Override public void close() throws Exception {
            world.close();
        }
    }
}
