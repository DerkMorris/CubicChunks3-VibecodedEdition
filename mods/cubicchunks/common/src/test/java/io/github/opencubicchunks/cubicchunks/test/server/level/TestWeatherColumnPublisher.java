package io.github.opencubicchunks.cubicchunks.test.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientWeatherColumns;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWeatherColumnPacket;
import io.github.opencubicchunks.cubicchunks.server.level.WeatherColumnPublisher;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/** Real publication, capture and client cache; only world services, transport and worker scheduling are controlled. */
class TestWeatherColumnPublisher extends BaseTest {
    @Test
    void completedSurfacesPublishBetweenTicksEvenWhenTheColumnChangesEveryTick() throws Exception {
        Fixture f = new Fixture();
        CubePos column = CubePos.of(0, 0, 0);
        f.columns.put(column, f.surface(0, 0, 96, null));
        long previousEpoch = -1;
        for (int iteration = 0; iteration < 8; iteration++) {
            f.publisher.changed(column);
            if (iteration > 0) {
                assertNull(f.sent.getLast().packet.heights(), "An edit immediately revokes the previous valid surface");
            }
            int sentBeforeCapture = f.sent.size();
            f.tick();
            assertEquals(1, f.work.size());
            f.runAll();
            assertEquals(sentBeforeCapture, f.sent.size(), "The worker must never publish on its own thread");
            assertEquals(1, f.owner.size());
            f.runOwner();
            assertEquals(sentBeforeCapture + 1, f.sent.size(), "A ready result must publish before the next tick can invalidate it");
            CCClientboundWeatherColumnPacket packet = f.sent.getLast().packet;
            assertEquals(97, packet.heights()[0]);
            assertTrue(packet.epoch() > previousEpoch);
            previousEpoch = packet.epoch();
            assertTrue(f.work.isEmpty(), "Owner delivery must not start extra captures outside the tick budget");
            assertEquals(iteration + 1, f.submitted);
        }
    }

    @Test
    void changedEpochStillRejectsCompletedResultsWaitingInTheOwnerQueue() throws Exception {
        Fixture f = new Fixture();
        CubePos column = CubePos.of(0, 0, 0);
        f.columns.put(column, f.surface(0, 0, 96, null));
        f.tick();
        f.runAll();
        assertEquals(1, f.owner.size());
        assertTrue(f.sent.isEmpty());
        f.columns.put(column, f.surface(0, 0, 96, 1_000_096));
        f.publisher.changed(column);
        f.runOwner();
        assertTrue(f.sent.isEmpty(), "Owner scheduling cannot weaken the epoch check after a roof edit");
        f.tick();
        f.runAll();
        f.runOwner();
        assertEquals(1, f.sent.size());
        assertEquals(1_000_097, f.sent.getFirst().packet.heights()[0]);
        assertEquals(2, f.submitted);
    }

    @Test
    void blockChangeRevokesImmediatelyAndRejectsAnOlderPendingCapture() throws Exception {
        Fixture f = new Fixture();
        f.columns.put(CubePos.of(0, 0, 0), f.surface(0, 0, 96, null));
        f.tick();
        assertEquals(1, f.work.size());
        f.columns.put(CubePos.of(0, 0, 0), f.surface(0, 0, 96, 1_000_096));
        f.publisher.changed(CubePos.of(0, Coords.blockToCube(1_000_096), 0));
        f.work.remove().run();
        f.tick();
        assertTrue(f.sent.isEmpty(), "A completed capture from before the block edit must never publish");
        assertEquals(1, f.work.size());
        f.work.remove().run();
        f.tick();
        assertEquals(1, f.sent.size());
        CCClientboundWeatherColumnPacket roof = f.sent.getFirst().packet;
        assertEquals(1_000_097, roof.heights()[0]);
        ClientWeatherColumns client = new ClientWeatherColumns(Level.OVERWORLD.identifier());
        assertTrue(client.apply(roof));
        assertEquals(1_000_097, client.height(0, 0));

        f.columns.put(CubePos.of(0, 0, 0), f.surface(0, 0, 96, null));
        f.publisher.changed(CubePos.of(0, Coords.blockToCube(1_000_096), 0));
        assertEquals(2, f.sent.size(), "Revocation must precede another publisher tick or worker execution");
        CCClientboundWeatherColumnPacket revoked = f.sent.getLast().packet;
        assertNull(revoked.heights());
        assertTrue(revoked.epoch() > roof.epoch());
        assertTrue(client.apply(revoked));
        assertEquals(CCClientboundWeatherColumnPacket.UNKNOWN, client.height(0, 0));
        assertFalse(client.apply(roof), "A delayed pre-revocation packet cannot restore the obsolete roof");
        f.tick();
        f.work.remove().run();
        f.tick();
        assertEquals(3, f.sent.size());
        assertTrue(client.apply(f.sent.getLast().packet));
        assertEquals(97, client.height(0, 0));
    }

    @Test
    void atMostTwoWorkersRunAndDroppedInterestCannotPublishItsCompletedJobs() throws Exception {
        Fixture f = new Fixture();
        f.around(BlockPos.ZERO, 96);
        f.tick();
        f.tick();
        assertEquals(2, f.work.size());
        for (int tick = 0; tick < 10; tick++) {
            f.tick();
        }
        assertEquals(2, f.work.size(), "Two queued captures exhaust the global worker budget");
        f.players.clear();
        f.tick();
        assertTrue(f.sent.stream().allMatch(sent -> sent.packet.heights() == null));
        int revocations = f.sent.size();
        f.runAll();
        f.tick();
        assertEquals(revocations, f.sent.size(), "Jobs for forgotten interest must not resurrect client state");
        assertTrue(f.work.isEmpty());

        f.players.add(f.player);
        f.tick();
        assertEquals(1, f.work.size());
        f.runAll();
        f.tick();
        assertTrue(f.sent.getLast().packet.heights() != null);
        long oldEpoch = f.sent.subList(0, revocations).stream().mapToLong(sent -> sent.packet.epoch()).max().orElseThrow();
        assertTrue(f.sent.getLast().packet.epoch() > oldEpoch, "Returning to an evicted interest creates a newer generation");
    }

    @Test
    void cameraInterestCoversNativeWeatherRadiusAndThirdPersonMarginAwayFromPlayer() throws Exception {
        Fixture f = new Fixture();
        BlockPos cameraPos = new BlockPos(3200, 32000, -6400);
        Entity camera = mock();
        when(camera.blockPosition()).thenReturn(cameraPos);
        when(f.player.getCamera()).thenReturn(camera);
        f.around(cameraPos, 96);
        f.publishAll();
        assertEquals(9, f.sent.size());
        ClientWeatherColumns client = new ClientWeatherColumns(Level.OVERWORLD.identifier());
        for (Sent sent : f.sent) {
            assertEquals(f.player, sent.player);
            assertTrue(client.apply(sent.packet));
        }
        // Native weatherRadius is at most 10; a third-person camera may be four blocks from its entity.
        for (int x = cameraPos.getX() - 14; x <= cameraPos.getX() + 14; x++) {
            for (int z = cameraPos.getZ() - 14; z <= cameraPos.getZ() + 14; z++) {
                assertEquals(97, client.height(x, z));
            }
        }
        assertEquals(CCClientboundWeatherColumnPacket.UNKNOWN, client.height(0, 0));
    }

    @Test
    void unchangedSurfaceIsNotResentAndNewWatcherReceivesTheCachedSurface() throws Exception {
        Fixture f = new Fixture();
        f.columns.put(CubePos.of(0, 0, 0), f.surface(0, 0, 96, null));
        f.publishAll();
        assertEquals(1, f.sent.size());
        int captured = f.submitted;
        for (int tick = 0; tick < 60; tick++) {
            f.tick();
        }
        assertEquals(1, f.sent.size());
        assertEquals(captured, f.submitted);
        ServerPlayer joined = f.player(BlockPos.ZERO);
        f.players.add(joined);
        f.tick();
        assertEquals(2, f.sent.size());
        assertEquals(joined, f.sent.getLast().player);
        assertEquals(f.sent.getFirst().packet.epoch(), f.sent.getLast().packet.epoch());
        assertEquals(captured, f.submitted, "A new watcher uses the completed detached surface");
    }

    @Test
    void unchangedUnknownSurfaceRetriesWithoutRepeatingItsPacket() throws Exception {
        Fixture f = new Fixture();
        f.columns.put(CubePos.of(0, 0, 0), new CubeColumnSnapshot(0, 0, -64, 319, new TreeMap<>(), Set.of()));
        f.tick();
        f.runAll();
        f.tick();
        assertEquals(1, f.sent.size());
        assertEquals(CCClientboundWeatherColumnPacket.UNKNOWN, f.sent.getFirst().packet.heights()[0]);
        for (int tick = 0; tick < 20; tick++) {
            f.tick();
        }
        assertEquals(1, f.work.size());
        f.runAll();
        f.tick();
        assertEquals(1, f.sent.size(), "Retrying unresolved terrain must not flood identical UNKNOWN payloads");
    }

    @Test
    void leavingDimensionOrDisconnectingCannotReceivePendingPublication() throws Exception {
        Fixture f = new Fixture();
        f.columns.put(CubePos.of(0, 0, 0), f.surface(0, 0, 96, null));
        f.tick();
        ServerLevel otherDimension = mock();
        when(f.player.level()).thenReturn(otherDimension);
        f.runAll();
        f.tick();
        assertTrue(f.sent.isEmpty());
        when(f.player.level()).thenReturn(f.level);
        f.player.connection = null;
        f.publisher.changed(CubePos.of(0, 3, 0));
        assertTrue(f.sent.isEmpty());
    }

    private record Sent(ServerPlayer player, CCClientboundWeatherColumnPacket packet) {}

    private static final class Fixture {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final List<ServerPlayer> players = new ArrayList<>();
        final Map<CubePos, CubeColumnSnapshot> columns = new HashMap<>();
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        final ArrayDeque<Runnable> owner = new ArrayDeque<>();
        final List<Sent> sent = new ArrayList<>();
        final ServerPlayer player;
        final WeatherColumnPublisher publisher;
        long time;
        int submitted;

        Fixture() throws Exception {
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.players()).thenReturn(players);
            when(level.getGameTime()).thenAnswer(call -> time);
            var server = level.getServer();
            doAnswer(call -> {
                owner.add(call.getArgument(0));
                return null;
            }).when(server).execute(any(Runnable.class));
            ServerChunkCache source = level.getChunkSource();
            var map = source.chunkMap;
            if (map == null) {
                map = mock();
                CubeMobTestWorld.setField(ServerChunkCache.class, source, "chunkMap", map);
            }
            when(((CubeColumnSource) map).cc_column(anyInt(), anyInt())).thenAnswer(
                    call -> columns.get(CubePos.of(Coords.blockToCube((int) call.getArgument(0)), 0, Coords.blockToCube((int) call.getArgument(1)))));
            player = player(BlockPos.ZERO);
            players.add(player);
            publisher = new WeatherColumnPublisher(level, task -> {
                submitted++;
                work.add(task);
            }, (recipient, packet) -> sent.add(new Sent(recipient, packet)));
        }

        ServerPlayer player(BlockPos pos) {
            ServerPlayer created = mock();
            created.connection = mock();
            when(created.level()).thenReturn(level);
            when(created.blockPosition()).thenReturn(pos);
            when(created.getCamera()).thenReturn(created);
            return created;
        }

        void tick() {
            time++;
            publisher.tick();
        }

        void runAll() {
            while (!work.isEmpty()) {
                work.remove().run();
            }
        }

        void runOwner() {
            while (!owner.isEmpty()) {
                owner.remove().run();
            }
        }

        void publishAll() {
            for (int tick = 0; tick < 12; tick++) {
                tick();
                runAll();
            }
        }

        void around(BlockPos center, int floor) {
            for (int x = Coords.blockToCube(center.getX() - 32); x <= Coords.blockToCube(center.getX() + 32); x++) {
                for (int z = Coords.blockToCube(center.getZ() - 32); z <= Coords.blockToCube(center.getZ() + 32); z++) {
                    columns.put(CubePos.of(x, 0, z), surface(x, z, floor, null));
                }
            }
        }

        CubeColumnSnapshot surface(int x, int z, int floor, Integer roof) {
            TreeMap<Integer, CubeColumnBlocks> cubes = new TreeMap<>();
            plane(cubes, x, z, floor, Blocks.STONE.defaultBlockState());
            if (roof != null) {
                plane(cubes, x, z, roof, Blocks.GLASS.defaultBlockState());
            }
            return new CubeColumnSnapshot(x, z, floor, floor + 31, cubes, Set.of());
        }

        void plane(TreeMap<Integer, CubeColumnBlocks> cubes, int x, int z, int y, BlockState state) {
            var cube = new LevelCube(level, CubePos.of(x, Coords.blockToCube(y), z));
            for (int localX = 0; localX < 32; localX++) {
                for (int localZ = 0; localZ < 32; localZ++) {
                    int globalX = Coords.cubeToMinBlock(x) + localX;
                    int globalZ = Coords.cubeToMinBlock(z) + localZ;
                    cube.getSection(Coords.blockToIndex(globalX, y, globalZ)).setBlockState(localX & 15, y & 15, localZ & 15, state);
                }
            }
            cubes.put(Coords.blockToCube(y), CubeColumnBlocks.capture(cube));
        }
    }
}
