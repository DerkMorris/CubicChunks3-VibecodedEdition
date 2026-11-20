package io.github.opencubicchunks.cubicchunks.test.world.level.gameevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestCubeGameEvents extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeDispatcherVisitsEveryXyzRegistryExactlyOnceAndSortsByDistance(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var heard = new ArrayList<String>();
            var cube = world.cube(new BlockPos(16, y + 16, 16));
            var registries = new ArrayList<GameEventListenerRegistry>();
            for (int x : new int[] { 15, 16 }) {
                for (int z : new int[] { 15, 16 }) {
                    for (int dy : new int[] { 15, 16 }) {
                        var at = new BlockPos(x, y + dy, z);
                        var registry = cube.cc_getListenerRegistry(at);
                        for (var other : registries) {
                            assertNotSame(other, registry);
                        }
                        registries.add(registry);
                        registry.register(new Listener(at, at.toShortString(), heard, GameEventListener.DeliveryMode.UNSPECIFIED));
                    }
                }
            }
            var source = new BlockPos(16, y + 16, 16);
            cube.cc_getListenerRegistry(source)
                    .register(new Listener(source.offset(4, 0, 0), "far", heard, GameEventListener.DeliveryMode.BY_DISTANCE));
            cube.cc_getListenerRegistry(source)
                    .register(new Listener(source.offset(2, 0, 0), "near", heard, GameEventListener.DeliveryMode.BY_DISTANCE));
            new GameEventDispatcher(world.level).post(GameEvent.STEP, Vec3.atCenterOf(source),
                    GameEvent.Context.of((net.minecraft.world.entity.Entity) null));
            assertEquals(10, heard.size());
            assertEquals(10, heard.stream().distinct().count());
            assertEquals(List.of("near", "far"), heard.subList(8, 10));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeDynamicListenerMovesAcrossAllAxesRetriesMissingAndDetachesFromReplacedCube(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var heard = new ArrayList<String>();
            var listener = new Listener(new BlockPos(15, y + 15, 15), "moving", heard, GameEventListener.DeliveryMode.UNSPECIFIED);
            var dynamic = new DynamicGameEventListener<>(listener);
            var first = world.cube(listener.position);
            dynamic.add(world.level);
            var previous = first.cc_getListenerRegistry(listener.position);
            for (var position : List.of(new BlockPos(16, y + 15, 15), new BlockPos(16, y + 16, 15), new BlockPos(16, y + 16, 16),
                    new BlockPos(32, y + 32, 32))) {
                listener.position = position;
                var cube = world.cube(position);
                dynamic.move(world.level);
                assertTrue(previous.isEmpty());
                previous = cube.cc_getListenerRegistry(position);
                assertFalse(previous.isEmpty());
                post(world.level, position);
            }
            assertEquals(4, heard.size());
            var removed = world.cubes.remove(first.cc_getCubePos());
            listener.position = new BlockPos(15, y + 15, 15);
            dynamic.move(world.level);
            assertTrue(previous.isEmpty());
            world.cubes.put(removed.cc_getCubePos(), removed);
            dynamic.move(world.level);
            assertFalse(removed.cc_getListenerRegistry(listener.position).isEmpty(), "The same unloaded position must be retried after loading");
            var replacement = new LevelCube(world.level, removed.cc_getCubePos());
            replacement.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
            world.cubes.put(replacement.cc_getCubePos(), replacement);
            dynamic.move(world.level);
            assertTrue(removed.cc_getListenerRegistry(listener.position).isEmpty());
            assertFalse(replacement.cc_getListenerRegistry(listener.position).isEmpty());
            dynamic.remove(world.level);
            assertTrue(replacement.cc_getListenerRegistry(listener.position).isEmpty());
        }
    }

    @Test
    void nativeDynamicListenerChangingLevelsUnregistersTheOriginalRegistry() throws Exception {
        try (var first = new CubeMobTestWorld(directory.resolve("first")); var second = new CubeMobTestWorld(directory.resolve("second"))) {
            var listener = new Listener(new BlockPos(1, -32000, 1), "moving", new ArrayList<>(), GameEventListener.DeliveryMode.UNSPECIFIED);
            var oldCube = first.cube(listener.position);
            var nextCube = second.cube(listener.position);
            var dynamic = new DynamicGameEventListener<>(listener);
            dynamic.add(first.level);
            dynamic.move(second.level);
            assertTrue(oldCube.cc_getListenerRegistry(listener.position).isEmpty());
            assertFalse(nextCube.cc_getListenerRegistry(listener.position).isEmpty());
            dynamic.remove(second.level);
            assertTrue(nextCube.cc_getListenerRegistry(listener.position).isEmpty());
        }
    }

    @Test
    void nativeVanillaDispatcherStillReadsVanillaChunkAndGlobalSectionY() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
            LevelChunk column = mock();
            GameEventListenerRegistry registry = mock();
            when(column.getListenerRegistry(anyInt())).thenReturn(registry);
            when(world.level.getChunkSource().getChunkNow(anyInt(), anyInt())).thenReturn(column);
            post(world.level, new BlockPos(8, 96, 8));
            verify(column, org.mockito.Mockito.atLeastOnce()).getListenerRegistry(6);
            verify(registry, org.mockito.Mockito.atLeastOnce()).visitInRangeListeners(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    void boundsAndPackedYAliasesCannotDeliverToAnotherCube() throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var heard = new ArrayList<String>();
            var position = new BlockPos(1, CubicBlockBounds.MAX - 1, 1);
            world.cube(position).cc_getListenerRegistry(position)
                    .register(new Listener(position, "boundary", heard, GameEventListener.DeliveryMode.UNSPECIFIED));
            post(world.level, position);
            assertEquals(1, heard.size());
            post(world.level, position.below(4096));
            post(world.level, new BlockPos(1, CubicBlockBounds.MAX + 1, 1));
            assertEquals(1, heard.size());
        }
    }

    private static void post(ServerLevel level, BlockPos position) {
        new GameEventDispatcher(level).post(GameEvent.STEP, Vec3.atCenterOf(position),
                GameEvent.Context.of((net.minecraft.world.entity.Entity) null));
    }

    private static final class Listener implements GameEventListener {
        private BlockPos position;
        private final String name;
        private final List<String> heard;
        private final DeliveryMode mode;

        private Listener(BlockPos position, String name, List<String> heard, DeliveryMode mode) {
            this.position = position;
            this.name = name;
            this.heard = heard;
            this.mode = mode;
        }

        @Override public PositionSource getListenerSource() {
            return new BlockPositionSource(position);
        }

        @Override public int getListenerRadius() {
            return 16;
        }

        @Override public DeliveryMode getDeliveryMode() {
            return mode;
        }

        @Override public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 source) {
            heard.add(name);
            return true;
        }
    }
}
