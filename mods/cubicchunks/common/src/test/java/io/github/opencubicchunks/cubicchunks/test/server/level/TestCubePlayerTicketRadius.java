package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executor;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level.ChunkTrackerTestAccess;
import io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level.CubicDistanceManagerTestAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.server.level.CubeLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;

class TestCubePlayerTicketRadius extends BaseTest {
    @Test
    void constructorDistanceUsesCubeUnitsBeforeDistanceManagerIsMarkedCubic() throws Exception {
        var fixture = new Fixture(true);
        fixture.assertCubeRadius(8);
        assertEquals(15, field(fixture.map, "serverViewDistance"), "The user's section render distance remains unchanged");
        // The dispatcher also admits the four native supporting columns beneath each cube-column position.
        assertEquals(17 * 17 * 17 + 34 * 34, ((Map<?, ?>) field(fixture.tracker, "chunks")).size());
    }

    @Test
    void sectionDistanceChangesResizeTheCubeGraphAroundAStationaryPlayer() throws Exception {
        var fixture = new Fixture(true);
        for (int sections : new int[] { 7, 15, 2, 9 }) {
            fixture.setDistance(sections);
            fixture.assertCubeRadius(Coords.sectionToCubeRenderDistance(sections));
        }
    }

    @Test
    void convertedTicketsCoverEveryTrackedCubeAndItsFullNeighborRing() throws Exception {
        var fixture = new Fixture(true);
        int radius = 8;
        fixture.assertCubeRadius(radius);
        var center = CloPos.section(fixture.playerSection);
        var view = CloTrackingView.cc_of(center, radius);
        view.cc_forEach(pos -> {
            if (!pos.isCube()) {
                return;
            }
            int ticketX = Math.clamp(pos.getX(), center.getX() - radius, center.getX() + radius);
            int ticketY = Math.clamp(pos.getY(), center.getY() - radius, center.getY() + radius);
            int ticketZ = Math.clamp(pos.getZ(), center.getZ() - radius, center.getZ() + radius);
            assertTrue(fixture.trackerColumns().get(CloPos.cubeAsLong(ticketX, 0, ticketZ)).contains(ticketY));
            int distance = Math.max(Math.abs(pos.getX() - ticketX), Math.max(Math.abs(pos.getY() - ticketY), Math.abs(pos.getZ() - ticketZ)));
            assertTrue(ChunkLevel.isBlockTicking(31 + distance), "Tracked outer-shell cubes must remain sendable");
            // A ticking cube requires its full one-cube neighbor ring; the farthest corner is distance + 1 from this ticket.
            assertEquals(ChunkStatus.FULL, CubeLevel.cubeGenerationStatus(31 + distance + 1));
        });
    }

    @Test
    void vanillaTicketGraphKeepsSectionUnits() throws Exception {
        var fixture = new Fixture(false);
        fixture.tracker.runAllUpdates();
        assertFalse(((MarkableAsCubic) fixture.manager).cc_isCubic());
        assertEquals(15, field(fixture.tracker, "viewDistance"));
        // Vanilla propagates its graph out to the maximum supported distance; this predicate selects the actual ticket radius.
        var haveTicketFor = DistanceManager.PlayerTicketTracker.class.getDeclaredMethod("haveTicketFor", int.class);
        haveTicketFor.setAccessible(true);
        assertEquals(true, haveTicketFor.invoke(fixture.tracker, 15));
        assertEquals(false, haveTicketFor.invoke(fixture.tracker, 16));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Inherited vanilla fields are not necessarily declared by the runtime mixin target.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class Fixture {
        private final ChunkMap map = mock(ChunkMap.class, CALLS_REAL_METHODS);
        private final DistanceManager manager;
        private final DistanceManager.PlayerTicketTracker tracker;
        private final SectionPos playerSection = SectionPos.of(-2, -10, 4);
        private final Method setView;

        private Fixture(boolean cubic) throws Exception {
            var level = mock(ServerLevel.class);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(cubic);
            setField(ChunkMap.class, map, "level", level);
            setField(ChunkMap.class, map, "playerMap", new PlayerMap());
            // The real inner distance manager reads these collections when synchronous admission adds a loading ticket.
            setField(ChunkMap.class, map, "toDrop", new LongOpenHashSet());
            setField(ChunkMap.class, map, "updatingChunkMap", new Long2ObjectLinkedOpenHashMap<>());
            var constructor = ChunkMap.DistanceManager.class.getDeclaredConstructor(ChunkMap.class, TicketStorage.class, Executor.class, Executor.class);
            constructor.setAccessible(true);
            manager = constructor.newInstance(map, new TicketStorage(), (Executor) Runnable::run, (Executor) Runnable::run);
            setField(ChunkMap.class, map, "distanceManager", manager);
            setView = ChunkMap.class.getDeclaredMethod("setServerViewDistance", int.class);
            setView.setAccessible(true);
            // Match ChunkMap construction order: its initial section-distance update happens before cc_onInit marks the manager cubic.
            setDistance(15);
            if (cubic) {
                ((MarkableAsCubic) manager).cc_setCubic();
            }
            tracker = ((CubicDistanceManagerTestAccess) manager).get_playerTicketManager();
            manager.addPlayer(playerSection, mock(ServerPlayer.class));
        }

        private void setDistance(int sections) throws Exception {
            setView.invoke(map, sections);
        }

        private Long2ObjectMap<IntSet> trackerColumns() {
            return ((ChunkTrackerTestAccess) tracker).get_cc_existingCubesForCubeColumns();
        }

        private void assertCubeRadius(int radius) {
            tracker.runAllUpdates();
            var columns = trackerColumns();
            int diameter = radius * 2 + 1;
            assertEquals(diameter * diameter, columns.size());
            assertEquals(diameter * diameter * diameter, columns.values().stream().mapToInt(IntSet::size).sum());
            var center = CloPos.section(playerSection);
            assertTrue(columns.get(CloPos.cubeAsLong(center.getX() + radius, 0, center.getZ() - radius)).contains(center.getY() + radius));
            assertFalse(columns.containsKey(CloPos.cubeAsLong(center.getX() + radius + 1, 0, center.getZ())));
        }
    }
}
