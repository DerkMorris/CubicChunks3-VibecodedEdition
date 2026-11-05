package io.github.opencubicchunks.cubicchunks.test.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.MarkableAsCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubeLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicTicketStorage;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.LoadingChunkTracker;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.TicketStorage;
import org.junit.jupiter.api.Test;

class TestCubeLoadingUnload extends BaseTest {
    @Test
    void removingLastTicketReleasesColumnsBeyondTheSmallerCubeGenerationRadius() {
        TicketStorage tickets = new TicketStorage();
        HolderManager manager = new HolderManager(tickets);
        LoadingChunkTracker tracker = new LoadingChunkTracker(manager, tickets);
        ((MarkableAsCubic) tracker).cc_setCubic();
        var storage = (CubicTicketStorage) tickets;
        CloPos center = CloPos.cube(-2, 1000000, 3);
        Ticket ticket = new Ticket(TicketType.FORCED, 33);
        for (int cycle = 0; cycle < 2; cycle++) {
            storage.cc_addTicket(ticket, center);
            tracker.runDistanceUpdates(Integer.MAX_VALUE);
            assertTrue(manager.holders.containsKey(center.asLong()));
            assertTrue(
                    manager.levels.entrySet().stream().anyMatch(entry -> CloPos.isChunk(entry.getKey()) && entry.getValue() > CubeLevel.MAX_LEVEL));
            storage.cc_removeTicket(ticket, center);
            tracker.runDistanceUpdates(Integer.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, tracker.runDistanceUpdates(Integer.MAX_VALUE));
            assertTrue(manager.holders.isEmpty(), "No column holder may strand the server shutdown loop");
        }
    }

    /** Retains holders only while the production scheduling limits consider them loaded. */
    private static final class HolderManager extends DistanceManager {
        private final Map<Long, ChunkHolder> holders = new HashMap<>();
        private final Map<Long, Integer> levels = new HashMap<>();

        HolderManager(TicketStorage tickets) {
            super(tickets, Runnable::run, Runnable::run);
        }

        @Override protected boolean isChunkToRemove(long pos) {
            return false;
        }

        @Override protected ChunkHolder getChunk(long pos) {
            return holders.get(pos);
        }

        @Override protected ChunkHolder updateChunkScheduling(long pos, int level, ChunkHolder holder, int oldLevel) {
            if (level > (CloPos.isCube(pos) ? CubeLevel.MAX_LEVEL : ChunkLevel.MAX_LEVEL)) {
                holders.remove(pos);
                levels.remove(pos);
                return null;
            }
            levels.put(pos, level);
            return holders.computeIfAbsent(pos, key -> {
                ChunkHolder created = mock();
                when(created.getTicketLevel()).thenAnswer(invocation -> levels.getOrDefault(key, ChunkLevel.MAX_LEVEL + 1));
                return created;
            });
        }
    }
}
