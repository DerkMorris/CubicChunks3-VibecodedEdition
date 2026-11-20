package io.github.opencubicchunks.cubicchunks.test.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercise the native callers, which previously bypassed the existing cubic lifecycle implementations. */
class TestCubePlayerLifecycleRouting extends BaseTest {
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void nativePlayerRegistrationDispatchesBothEntryAndRemovalToCubicLifecycle(boolean entering) throws Exception {
        Fixture f = new Fixture(true);
        f.status(entering);
        assertEquals(List.of("status:" + entering), f.cubicCalls);
        // The delegation owns player-map and ticket mutation; native code must not run a second time.
        assertTrue(f.players.getAllPlayers().isEmpty());
    }

    @Test
    void nativeMovementDispatchesToFullCubePositionComparison() throws Exception {
        Fixture f = new Fixture(true);
        f.map.move(f.player);
        assertEquals(List.of("move"), f.cubicCalls);
    }

    @Test
    void vanillaRegistrationStillRunsNativePlayerMapAndTrackingLifecycle() throws Exception {
        Fixture f = new Fixture(false);
        f.status(true);
        assertTrue(f.players.getAllPlayers().contains(f.player));
        assertTrue(f.view.get() instanceof ChunkTrackingView.Positioned);
        verify(f.distance).addPlayer(SectionPos.of(f.position), f.player);
        f.status(false);
        assertFalse(f.players.getAllPlayers().contains(f.player));
        assertEquals(ChunkTrackingView.EMPTY, f.view.get());
        verify(f.distance).removePlayer(SectionPos.of(f.position), f.player);
        assertTrue(f.cubicCalls.isEmpty());
    }

    @Test
    void vanillaMovementStillMovesTicketsAndUpdatesTheNativeView() throws Exception {
        Fixture f = new Fixture(false);
        SectionPos previous = SectionPos.of(0, 4, 0);
        f.lastSection.set(previous);
        f.players.addPlayer(f.player, false);
        f.map.move(f.player);
        verify(f.distance).removePlayer(previous, f.player);
        verify(f.distance).addPlayer(SectionPos.of(f.position), f.player);
        assertEquals(SectionPos.of(f.position), f.lastSection.get());
        assertEquals(ChunkTrackingView.of(ChunkPos.containing(f.position), 2), f.view.get());
        assertTrue(f.cubicCalls.isEmpty());
    }

    private static void field(Object object, String name, Object value) throws Exception {
        Field field = ChunkMap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static final class Fixture {
        private final List<String> cubicCalls = new ArrayList<>();
        private final ServerLevel level = mock();
        private final ServerPlayer player = mock();
        private final PlayerMap players = new PlayerMap();
        private final ChunkMap.DistanceManager distance = mock();
        private final BlockPos position = new BlockPos(1600, 96, 512);
        private final AtomicReference<ChunkTrackingView> view = new AtomicReference<>(ChunkTrackingView.EMPTY);
        private final AtomicReference<SectionPos> lastSection = new AtomicReference<>(SectionPos.of(position));
        private final ChunkMap map;

        private Fixture(boolean cubic) throws Exception {
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
            when(player.level()).thenReturn(level);
            when(player.blockPosition()).thenReturn(position);
            when(player.chunkPosition()).thenReturn(ChunkPos.containing(position));
            when(player.requestedViewDistance()).thenReturn(2);
            when(player.getLastSectionPos()).thenAnswer(invocation -> lastSection.get());
            doAnswer(invocation -> {
                lastSection.set(invocation.getArgument(0));
                return null;
            }).when(player).setLastSectionPos(any());
            when(player.getChunkTrackingView()).thenAnswer(invocation -> view.get());
            doAnswer(invocation -> {
                view.set(invocation.getArgument(0));
                return null;
            }).when(player).setChunkTrackingView(any());
            player.connection = mock();
            Field sender = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("chunkSender");
            sender.setAccessible(true);
            sender.set(player.connection, mock(sender.getType()));
            map = mock(ChunkMap.class, invocation -> {
                if (invocation.getMethod().getName().equals("cc_updatePlayerStatus")) {
                    assertEquals(player, invocation.getArgument(0));
                    cubicCalls.add("status:" + invocation.getArgument(1));
                    return null;
                }
                if (invocation.getMethod().getName().equals("cc_move")) {
                    assertEquals(player, invocation.getArgument(0));
                    cubicCalls.add("move");
                    return null;
                }
                return invocation.callRealMethod();
            });
            field(map, "level", level);
            field(map, "playerMap", players);
            field(map, "distanceManager", distance);
            field(map, "entityMap", new Int2ObjectOpenHashMap<>());
            field(map, "visibleChunkMap", new Long2ObjectLinkedOpenHashMap<>());
            field(map, "serverViewDistance", 2);
        }

        private void status(boolean entering) throws Exception {
            Method method = ChunkMap.class.getDeclaredMethod("updatePlayerStatus", ServerPlayer.class, boolean.class);
            method.setAccessible(true);
            method.invoke(map, player, entering);
        }
    }
}
