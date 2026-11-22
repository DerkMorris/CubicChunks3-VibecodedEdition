package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.SimulationChunkTracker;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.TicketStorage;
import org.junit.jupiter.api.Test;

class TestCubeSimulationDistance extends BaseTest {
    @Test
    void initialSectionDistanceBecomesCubeRadiusBeforeTheFirstPlayerTicket() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.cache.setSimulationDistance(5);
        fixture.addPlayer();
        fixture.assertRadius(3);
        long surface = CloPos.cube(fixture.center.getX(), 0, fixture.center.getZ()).toLong();
        assertFalse(fixture.manager.inEntityTickingRange(surface), "A player at cube Y=-5 must not randomly tick the surface at section distance 5");
        assertFalse(fixture.manager.inBlockTickingRange(surface));
        assertFalse(TicketType.PLAYER_SIMULATION.doesLoad());
        assertEquals(ChunkLevel.MAX_LEVEL + 1, fixture.storage.getTicketLevelAt(fixture.center.toLong(), false),
                "Simulation distance must not create or widen loading tickets");
        fixture.manager.removePlayer(fixture.position, fixture.player);
        fixture.tracker.runAllUpdates();
        assertFalse(fixture.manager.inEntityTickingRange(fixture.center.toLong()));
        assertEquals(ChunkLevel.MAX_LEVEL + 1, fixture.storage.getTicketLevelAt(fixture.center.toLong(), true));
    }

    @Test
    void stationaryPlayerSimulationResizesWithoutTheRenderDistanceMinimumOrDoubleConversion() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.cache.setSimulationDistance(5);
        fixture.addPlayer();
        for (int sections : new int[] { 10, 5, 2, 0, 7, 5 }) {
            fixture.cache.setSimulationDistance(sections);
            fixture.assertRadius(Coords.sectionToCubeCeil(sections));
            fixture.cache.setSimulationDistance(sections);
            fixture.assertRadius(Coords.sectionToCubeCeil(sections));
        }
    }

    @Test
    void vanillaSimulationRetainsSectionRadiusAndItsBlockTickingShell() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.cache.setSimulationDistance(5);
        fixture.addPlayer();
        for (int sections : new int[] { 5, 10, 2, 0 }) {
            fixture.cache.setSimulationDistance(sections);
            fixture.assertRadius(sections);
        }
    }

    private static final class Fixture {
        private final TicketStorage storage = new TicketStorage();
        private final DistanceManager manager = new TestCubicDistanceManager.TestDistanceManager(storage, Runnable::run, Runnable::run);
        private final ServerChunkCache cache = mock(ServerChunkCache.class, CALLS_REAL_METHODS);
        private final SectionPos position = SectionPos.of(-3, -10, 3);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final SimulationChunkTracker tracker;
        private final CloPos center;
        private final boolean cubic;

        private Fixture(boolean cubic) throws Exception {
            this.cubic = cubic;
            center = cubic ? CloPos.section(position) : CloPos.chunk(position.x(), position.z());
            if (cubic) {
                // Match constructor order: ChunkSource is marked at CTOR_HEAD, then ChunkMap marks its distance manager.
                ((MarkableAsCubic) cache).cc_setCubic();
                ((MarkableAsCubic) manager).cc_setCubic();
            }
            setField(ServerChunkCache.class, cache, "distanceManager", manager);
            var field = DistanceManager.class.getDeclaredField("simulationChunkTracker");
            field.setAccessible(true);
            tracker = (SimulationChunkTracker) field.get(manager);
        }

        private void addPlayer() {
            manager.addPlayer(position, player);
        }

        private void assertRadius(int radius) {
            tracker.runAllUpdates();
            assertEquals(31 - radius, storage.getTicketLevelAt(center.toLong(), true));
            AtomicInteger entityTicking = new AtomicInteger();
            manager.forEachEntityTickingChunk(key -> {
                if (!cubic || CloPos.fromLong(key).isCube()) {
                    entityTicking.incrementAndGet();
                }
            });
            int diameter = radius * 2 + 1;
            assertEquals(cubic ? diameter * diameter * diameter : diameter * diameter, entityTicking.get());
            for (int axis : cubic ? new int[] { 0, 1, 2 } : new int[] { 0, 2 }) {
                for (int sign : new int[] { -1, 1 }) {
                    assertTrue(manager.inEntityTickingRange(offset(axis, sign * radius)));
                    assertFalse(manager.inEntityTickingRange(offset(axis, sign * (radius + 1))));
                    assertTrue(manager.inBlockTickingRange(offset(axis, sign * (radius + 1))));
                    assertFalse(manager.inBlockTickingRange(offset(axis, sign * (radius + 2))));
                }
            }
        }

        private long offset(int axis, int distance) {
            int x = center.getX() + (axis == 0 ? distance : 0);
            int z = center.getZ() + (axis == 2 ? distance : 0);
            return cubic ? CloPos.cube(x, center.getY() + (axis == 1 ? distance : 0), z).toLong() : CloPos.chunk(x, z).toLong();
        }
    }
}
