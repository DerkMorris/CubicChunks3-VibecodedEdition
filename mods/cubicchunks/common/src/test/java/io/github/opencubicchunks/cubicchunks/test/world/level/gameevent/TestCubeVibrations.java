package io.github.opencubicchunks.cubicchunks.test.world.level.gameevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.stream.IntStream;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.ClipBlockStateContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Native listeners, ray occlusion, selector, travel and receiving users; unrelated server services remain controlled. */
class TestCubeVibrations extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("verticalEdges")
    void nativeSensorAtWorldEdgeIgnoresOnlyImpossibleNeighborSections(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            var sensor = f.sensor();
            BlockPos source = y == CubicBlockBounds.MIN ? f.receiver.above(4) : f.receiver.below(4);
            f.post(GameEvent.STEP, source, null);
            var neighborKey = CubePos.from(f.receiver.offset(16, 0, 0));
            var neighbor = f.world.cubes.remove(neighborKey);
            assertNotNull(neighbor);
            f.tick(sensor, 12);
            assertNotNull(sensor.getVibrationData().getCurrentVibration());
            assertEquals(0, sensor.getLastVibrationFrequency());
            f.world.cubes.put(neighborKey, neighbor);
            f.tick(sensor, 1);
            assertNull(sensor.getVibrationData().getCurrentVibration());
            assertEquals(1, sensor.getLastVibrationFrequency());
            assertEquals(SculkSensorPhase.ACTIVE, f.world.level.getBlockState(f.receiver).getValue(SculkSensorBlock.PHASE));
        }
    }

    private static IntStream verticalEdges() {
        return IntStream.of(CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeSensorRegistersTravelsPowersCoolsAndUnregistersAcrossCubeSeam(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            var sensor = f.sensor();
            f.post(GameEvent.STEP, f.receiver.below(4), null);
            assertEquals(0, sensor.getLastVibrationFrequency());
            f.tick(sensor, 2);
            assertNotNull(sensor.getVibrationData().getCurrentVibration());
            assertTrue(sensor.getVibrationData().getTravelTimeInTicks() > 0);
            f.tick(sensor, 10);
            assertNull(sensor.getVibrationData().getCurrentVibration());
            assertEquals(1, sensor.getLastVibrationFrequency());
            var state = f.world.level.getBlockState(f.receiver);
            assertEquals(SculkSensorPhase.ACTIVE, state.getValue(SculkSensorBlock.PHASE));
            assertTrue(state.getValue(SculkSensorBlock.POWER) > 0);
            verify(f.world.level).scheduleTick(f.receiver, Blocks.SCULK_SENSOR, SculkSensorBlock.ACTIVE_TICKS);
            state.tick(f.world.level, f.receiver, f.world.level.getRandom());
            assertEquals(SculkSensorPhase.COOLDOWN, f.world.level.getBlockState(f.receiver).getValue(SculkSensorBlock.PHASE));
            f.world.level.getBlockState(f.receiver).tick(f.world.level, f.receiver, f.world.level.getRandom());
            assertEquals(SculkSensorPhase.INACTIVE, f.world.level.getBlockState(f.receiver).getValue(SculkSensorBlock.PHASE));
            f.world.cube(f.receiver).removeBlockEntity(f.receiver);
            assertTrue(f.world.cube(f.receiver).cc_getListenerRegistry(f.receiver).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void missingVerticalTickingNeighborPausesNativeVibrationAndResumeDeliversOnce(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            var sensor = f.sensor();
            f.post(GameEvent.STEP, f.receiver.below(4), null);
            var missingKey = CubePos.from(f.receiver.below(16));
            var lower = f.world.cubes.remove(missingKey);
            f.tick(sensor, 12);
            assertNotNull(sensor.getVibrationData().getCurrentVibration());
            assertEquals(0, sensor.getLastVibrationFrequency());
            f.world.cubes.put(missingKey, lower);
            lower.setFullStatus(() -> FullChunkStatus.FULL);
            f.tick(sensor, 2);
            assertEquals(0, sensor.getLastVibrationFrequency());
            lower.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
            f.tick(sensor, 1);
            assertNull(sensor.getVibrationData().getCurrentVibration());
            assertEquals(1, sensor.getLastVibrationFrequency());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeWoolOcclusionAndSpectatorSneakingFiltersRemainEffective(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            var sensor = f.sensor();
            var origin = f.receiver.below(4);
            var wall = f.receiver.below(2);
            f.world.block(wall, Blocks.WOOL.white().defaultBlockState());
            f.post(GameEvent.STEP, origin, null);
            f.tick(sensor, 10);
            assertEquals(0, sensor.getLastVibrationFrequency());
            f.world.block(wall, Blocks.AIR.defaultBlockState());
            ServerPlayer player = mock();
            net.minecraft.server.PlayerAdvancements advancements = mock();
            when(player.getAdvancements()).thenReturn(advancements);
            doReturn(java.util.Map.of()).when(advancements).getTriggerMapForType(any());
            when(player.isSpectator()).thenReturn(true);
            f.post(GameEvent.STEP, origin, player);
            f.tick(sensor, 10);
            assertEquals(0, sensor.getLastVibrationFrequency());
            when(player.isSpectator()).thenReturn(false);
            when(player.isSteppingCarefully()).thenReturn(true);
            f.post(GameEvent.STEP, origin, player);
            f.tick(sensor, 10);
            assertEquals(0, sensor.getLastVibrationFrequency());
            when(player.isSteppingCarefully()).thenReturn(false);
            f.post(GameEvent.STEP, origin, null);
            f.tick(sensor, 10);
            assertEquals(1, sensor.getLastVibrationFrequency());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeAllayHearsNoteBlockAndStoresFullHeightMemory(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            Allay allay = EntityTypes.ALLAY.create(f.world.level, EntitySpawnReason.LOAD);
            assertNotNull(allay);
            allay.snapTo(Vec3.atBottomCenterOf(f.receiver));
            allay.updateDynamicGameEventListener((listener, level) -> listener.add(level));
            BlockPos note = f.receiver.below(4);
            f.world.block(note, Blocks.NOTE_BLOCK.defaultBlockState());
            f.post(GameEvent.NOTE_BLOCK_PLAY, note, null);
            f.tick(allay, 12);
            assertEquals(GlobalPos.of(Level.OVERWORLD, note), allay.getBrain().getMemory(MemoryModuleType.LIKED_NOTEBLOCK_POSITION).orElseThrow());
            allay.updateDynamicGameEventListener((listener, level) -> listener.remove(level));
            assertTrue(f.world.cube(f.receiver).cc_getListenerRegistry(f.receiver).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeWardenReceivesDisturbanceAndRejectsNewVibrationDuringCooldown(int y) throws Exception {
        try (var f = new Fixture(y, directory)) {
            Warden warden = EntityTypes.WARDEN.create(f.world.level, EntitySpawnReason.LOAD);
            assertNotNull(warden);
            warden.snapTo(Vec3.atBottomCenterOf(f.receiver));
            warden.updateDynamicGameEventListener((listener, level) -> listener.add(level));
            BlockPos origin = f.receiver.below(4);
            f.post(GameEvent.STEP, origin, null);
            f.tick(warden, 12);
            assertTrue(warden.getBrain().hasMemoryValue(MemoryModuleType.VIBRATION_COOLDOWN));
            assertEquals(origin, warden.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION).orElseThrow());
            f.post(GameEvent.STEP, f.receiver.below(3), null);
            f.tick(warden, 12);
            assertEquals(origin, warden.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION).orElseThrow());
            assertNull(warden.getVibrationData().getCurrentVibration());
        }
    }

    @Test
    void vanillaAdjacentChunkRuleIsPreserved() throws Exception {
        try (var f = new Fixture(96, directory)) {
            var sensor = f.sensor();
            f.post(GameEvent.STEP, f.receiver.below(4), null);
            when(((CanBeCubic) f.world.level).cc_isCubic()).thenReturn(false);
            when(f.world.level.shouldTickBlocksAt(anyLong())).thenReturn(false);
            f.tick(sensor, 12);
            assertNotNull(sensor.getVibrationData().getCurrentVibration());
            verify(f.world.level, org.mockito.Mockito.atLeastOnce()).shouldTickBlocksAt(anyLong());
            assertEquals(0, sensor.getLastVibrationFrequency());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CubeMobTestWorld world;
        private final BlockPos receiver;
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
                        BlockPos origin = SectionPos.of(section.x() + x, section.y() + dy, section.z() + z).origin();
                        if (CubicBlockBounds.contains(origin)) {
                            world.cube(origin);
                        }
                    }
                }
            }
        }

        private SculkSensorBlockEntity sensor() {
            var cube = world.cube(receiver);
            cube.setLoaded(true);
            world.block(receiver, Blocks.SCULK_SENSOR.defaultBlockState());
            return (SculkSensorBlockEntity) cube.getBlockEntity(receiver);
        }

        private void post(Holder<GameEvent> event, BlockPos origin, Entity source) {
            new GameEventDispatcher(world.level).post(event, Vec3.atCenterOf(origin), GameEvent.Context.of(source));
        }

        private void tick(VibrationSystem system, int ticks) {
            for (int i = 0; i < ticks; i++) {
                time++;
                VibrationSystem.Ticker.tick(world.level, system.getVibrationData(), system.getVibrationUser());
            }
        }

        @Override public void close() throws Exception {
            world.close();
        }
    }
}
