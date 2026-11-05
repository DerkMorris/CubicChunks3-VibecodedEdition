package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.mixin.test.client.multiplayer.ClientChunkCacheTestAccess;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightInvalidatePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightRevokePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.BoundedCubeLightSolver;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBoundary;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBounds;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

/** Uses the actual Fabric-transformed client cache and its light engine without creating a window. */
class TestClientCubeLighting extends BaseTest {
    @Test
    void scopedRelightingKeepsScalarAndSectionDisplayThroughRealCacheAndPacketsAtBothHeightSigns() {
        for (int y : new int[] { 1000000, -1000000 }) {
            Fixture fixture = new Fixture();
            CubePos pos = CubePos.of(0, y, 0);
            fixture.cache.cc_updateViewCenter(0, y, 0);
            LevelCube source = new LevelCube(fixture.level, pos);
            CubeLightView lit = filledLight(pos, 7, 15);
            fixture.receive(roundTrip(new CCClientboundLevelCubeWithLightPacket(source, new CubeLightPacketSnapshot(2, lit, true))));
            assertTrue(fixture.queuedLight.isEmpty(), "Initial exact light must be installed before rendering can sample the new cube");
            assertTrue(fixture.cache.cc_isCubeLightReady(pos));
            assertRetainedLight(fixture, pos);
            LevelCube installed = fixture.cache.cc_getCube(0, y, 0, false);
            installed.setBlockState(pos.asBlockPos(1, 1, 1), Blocks.STONE.defaultBlockState());
            new CCClientboundCubeLightRevokePacket.Handler().receive(new CCClientboundCubeLightRevokePacket(pos, 3), fixture.context);
            fixture.receive(roundTrip(new CCClientboundLevelCubeWithLightPacket(source, new CubeLightPacketSnapshot(3, null, true))));
            fixture.queuedLight.remove().run();
            assertFalse(fixture.cache.cc_isCubeLightReady(pos));
            assertNull(fixture.cache.cc_getCubeLightView(pos));
            assertRetainedLight(fixture, pos);
            CubeLightView dark = filledLight(pos, 0, 0);
            fixture.receive(roundTrip(new CCClientboundLevelCubeWithLightPacket(source, new CubeLightPacketSnapshot(4, dark, true))));
            assertRetainedLight(fixture, pos);
            fixture.queuedLight.remove().run();
            assertTrue(fixture.cache.cc_isCubeLightReady(pos));
            assertEquals(0, fixture.vanilla.getLightEngine().getRawBrightness(pos.asBlockPos(), 0));
            fixture.cache.cc_drop(pos);
            assertNull(fixture.cache.cc_getVisibleCubeLightView(pos));
        }
    }

    private static void assertRetainedLight(Fixture fixture, CubePos pos) {
        var engine = fixture.vanilla.getLightEngine();
        assertEquals(15, engine.getRawBrightness(pos.asBlockPos(), 0));
        assertEquals(7, engine.getRawBrightness(pos.asBlockPos(), 10));
        assertEquals(7, engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos.asBlockPos()));
        assertEquals(15, engine.getLayerListener(LightLayer.SKY).getLightValue(pos.asBlockPos()));
        for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
            assertEquals(7, engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(Coords.sectionPosByIndex(pos, section)).get(4, 8, 12));
            assertEquals(15, engine.getLayerListener(LightLayer.SKY).getDataLayerData(Coords.sectionPosByIndex(pos, section)).get(4, 8, 12));
        }
    }

    private static CubeLightView filledLight(CubePos pos, int block, int sky) {
        byte[][] blocks = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
        byte[][] skies = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
        for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
            Arrays.fill(blocks[section], (byte) (block | block << 4));
            Arrays.fill(skies[section], (byte) (sky | sky << 4));
        }
        return CubeLightView.fromExact(pos, blocks, skies);
    }

    @Test
    void calculatedLightSurvivesWireCodecRealHandlerAndClientEngine() {
        LevelCube source = new LevelCube(mockLevelWithPalettes(Level.class), CubePos.ZERO);
        int diameter = CubicConstants.DIAMETER_IN_BLOCKS;
        for (int y = 0; y < diameter; y++) {
            for (int z = 0; z < diameter; z++) {
                for (int x = 0; x < diameter; x++) {
                    if (x == 0 || y == 0 || z == 0 || x == diameter - 1 || y == diameter - 1 || z == diameter - 1) {
                        source.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        BlockPos lamp = new BlockPos(16, 16, 16);
        source.setBlockState(lamp, Blocks.GLOWSTONE.defaultBlockState());
        var solution = BoundedCubeLightSolver.solve(new CubeLightBounds(CubePos.ZERO, CubePos.ZERO),
                Map.of(CubePos.ZERO, CubeLightBlockSnapshot.capture(source)), CubeLightBoundary.UNKNOWN);
        CubeLightView view = CubeLightView.fromSolution(solution, CubePos.ZERO).orElseThrow();
        assertTrue(view.isReady());
        CCClientboundLevelCubeWithLightPacket packet = roundTrip(
                new CCClientboundLevelCubeWithLightPacket(source, new CubeLightPacketSnapshot(2, view)));
        Fixture fixture = new Fixture();
        fixture.receive(packet);
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        fixture.queuedLight.remove().run();
        assertTrue(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        assertEquals(Blocks.GLOWSTONE.defaultBlockState(), fixture.cache.cc_getCube(0, 0, 0, false).getBlockState(lamp));
        assertEquals(15, fixture.vanilla.getLightEngine().getRawBrightness(lamp, 0));
        assertEquals(14, fixture.vanilla.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(lamp.east()));

        fixture.receive(packet);
        new CCClientboundCubeLightInvalidatePacket.Handler().receive(new CCClientboundCubeLightInvalidatePacket(3), fixture.context);
        fixture.queuedLight.remove().run();
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        assertNull(fixture.cache.cc_getCubeLightView(CubePos.ZERO));
        assertEquals(15, fixture.vanilla.getLightEngine().getRawBrightness(lamp, 0));
    }

    private static CCClientboundLevelCubeWithLightPacket roundTrip(CCClientboundLevelCubeWithLightPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            return CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void fullPacketLightReachesActualEngineAndBlockEditsRevokeAllNeighbors() {
        Fixture fixture = new Fixture();
        LevelCube first = fixture.install(CubePos.ZERO);
        LevelCube second = fixture.install(CubePos.of(1, 0, 0));
        fixture.cache.cc_invalidateCubeLight(2);
        assertTrue(fixture.apply(first, 2));
        assertTrue(fixture.apply(second, 2));
        assertEquals(7, fixture.vanilla.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(BlockPos.ZERO));
        assertEquals(7, fixture.vanilla.getLightEngine().getRawBrightness(BlockPos.ZERO, 0));
        assertFalse(first.isLightCorrect());

        first.setBlockState(new BlockPos(1, 1, 1), Blocks.STONE.defaultBlockState());
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        assertFalse(fixture.cache.cc_isCubeLightReady(second.cc_getCubePos()));
        assertNull(fixture.cache.cc_getCubeLightView(CubePos.ZERO));
        assertEquals(7, fixture.vanilla.getLightEngine().getRawBrightness(BlockPos.ZERO, 0));
        assertFalse(fixture.apply(first, 2));
        fixture.cache.cc_invalidateCubeLight(3);
        assertTrue(fixture.apply(first, 3));
    }

    @Test
    void sameObjectFullReplacementClearsItsViewAndRejectsAnEarlierCallback() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.install(CubePos.ZERO);
        assertTrue(fixture.apply(cube, 2));
        long revision = cube.cc_getLightRevision();
        long generation = fixture.cache.cc_lightGeneration();
        assertSame(cube, fixture.install(CubePos.ZERO));
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        assertFalse(fixture.cache.cc_applyCubeLight(cube, revision, 2, exact(CubePos.ZERO), generation));
        assertTrue(fixture.apply(cube, 2));
    }

    @Test
    void rejectedPredictionRequestsBoundedRecoveryAndAcceptsAuthoritativeNewEpoch() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.install(CubePos.ZERO);
        assertTrue(fixture.apply(cube, 2));
        BlockPos prediction = new BlockPos(1, 1, 1);
        cube.setBlockState(prediction, Blocks.STONE.defaultBlockState());
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        for (int tick = 0; tick < 19; tick++) {
            assertNull(fixture.cache.cc_pollCubeLightRefresh());
        }
        var request = fixture.cache.cc_pollCubeLightRefresh();
        assertEquals(CubePos.ZERO, request.pos());
        assertEquals(2, request.observedEpoch());

        LevelCube authoritative = new LevelCube(mockLevelWithPalettes(Level.class), CubePos.ZERO);
        new CCClientboundCubeLightInvalidatePacket.Handler().receive(new CCClientboundCubeLightInvalidatePacket(3), fixture.context);
        fixture.receive(roundTrip(new CCClientboundLevelCubeWithLightPacket(authoritative, new CubeLightPacketSnapshot(3, exact(CubePos.ZERO)))));
        fixture.queuedLight.remove().run();
        assertSame(cube, fixture.cache.cc_getCube(0, 0, 0, false));
        assertEquals(Blocks.AIR.defaultBlockState(), cube.getBlockState(prediction));
        assertTrue(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        assertEquals(7, fixture.vanilla.getLightEngine().getRawBrightness(prediction, 0));
        cube.setBlockState(prediction, Blocks.AIR.defaultBlockState());
        assertTrue(fixture.cache.cc_isCubeLightReady(CubePos.ZERO), "A duplicate block delta must not revoke the matching full snapshot");
        for (int tick = 0; tick < 200; tick++) {
            assertNull(fixture.cache.cc_pollCubeLightRefresh());
        }
    }

    @Test
    void editBeforeFirstQueuedLightApplicationStillHasARecoveryAnchor() {
        Fixture fixture = new Fixture();
        LevelCube authoritative = new LevelCube(mockLevelWithPalettes(Level.class), CubePos.ZERO);
        var packet = new CCClientboundLevelCubeWithLightPacket(authoritative, new CubeLightPacketSnapshot(2, exact(CubePos.ZERO)));
        fixture.receive(roundTrip(packet));
        LevelCube cube = fixture.cache.cc_getCube(0, 0, 0, false);
        cube.setBlockState(new BlockPos(1, 1, 1), Blocks.STONE.defaultBlockState());
        fixture.queuedLight.remove().run();
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        for (int tick = 0; tick < 19; tick++) {
            assertNull(fixture.cache.cc_pollCubeLightRefresh());
        }
        assertEquals(CubePos.ZERO, fixture.cache.cc_pollCubeLightRefresh().pos());
        fixture.receive(roundTrip(new CCClientboundLevelCubeWithLightPacket(authoritative, new CubeLightPacketSnapshot(3, exact(CubePos.ZERO)))));
        fixture.queuedLight.remove().run();
        assertTrue(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));
        assertNull(fixture.cache.cc_pollCubeLightRefresh());
    }

    @Test
    void cacheDropEvictionAndRadiusLossRevokeLightAndDelayedCallbacks() {
        Fixture fixture = new Fixture();
        LevelCube first = fixture.install(CubePos.ZERO);
        assertTrue(fixture.apply(first, 2));
        long generation = fixture.cache.cc_lightGeneration();
        fixture.cache.cc_drop(CubePos.ZERO);
        assertNull(fixture.cache.cc_getCubeLightView(CubePos.ZERO));
        assertFalse(fixture.cache.cc_applyCubeLight(first, first.cc_getLightRevision(), 2, exact(CubePos.ZERO), generation));

        LevelCube replacement = fixture.install(CubePos.ZERO);
        assertTrue(fixture.apply(replacement, 2));
        var storage = ((ClientChunkCacheTestAccess) fixture.vanilla).cubeStorage();
        storage.replace(storage.getIndex(0, 0, 0), new LevelCube(fixture.level, CubePos.of(100, 0, 0)));
        assertFalse(fixture.cache.cc_isCubeLightReady(CubePos.ZERO));

        LevelCube edge = fixture.install(CubePos.of(storage.cubeRadius, 0, 0));
        assertTrue(fixture.apply(edge, 2));
        fixture.cache.cc_updateViewRadius(0);
        assertFalse(fixture.cache.cc_isCubeLightReady(edge.cc_getCubePos()));
    }

    @Test
    void fullPacketEvictionCanInstallFreshLightFromTheSameServerEpoch() {
        Fixture fixture = new Fixture();
        LevelCube cube = fixture.install(CubePos.ZERO);
        assertTrue(fixture.apply(cube, 2));
        long oldGeneration = fixture.cache.cc_lightGeneration();
        int range = ((ClientChunkCacheTestAccess) fixture.vanilla).cubeStorage().cubeRadius * 2 + 1;
        CubePos next = CubePos.of(range, 0, 0);
        fixture.cache.cc_updateViewCenter(range, 0, 0);
        LevelCube replacement = fixture.install(next);
        assertTrue(fixture.cache.cc_lightGeneration() > oldGeneration);
        assertFalse(fixture.cache.cc_applyCubeLight(cube, cube.cc_getLightRevision(), 2, exact(CubePos.ZERO), oldGeneration));
        assertTrue(fixture.apply(replacement, 2));
        assertTrue(fixture.cache.cc_isCubeLightReady(next));
    }

    private static CubeLightView exact(CubePos pos) {
        byte[][] block = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
        for (byte[] section : block) {
            Arrays.fill(section, (byte) 0x77);
        }
        return CubeLightView.fromExact(pos, block, new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE]);
    }

    private static final class Fixture {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final ClientChunkCache vanilla;
        private final ClientCubeCache cache;
        private final Queue<Runnable> queuedLight = new ArrayDeque<>();
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock(io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext.class);

        private Fixture() {
            when(((CanBeCubic) this.level).cc_isCubic()).thenReturn(true);
            Misc.setupLevelHeight(this.level, -64, 384);
            when(this.level.dimensionType().hasSkyLight()).thenReturn(true);
            this.vanilla = new ClientChunkCache(this.level, 5);
            this.cache = (ClientCubeCache) this.vanilla;
            when(this.level.getChunkSource()).thenReturn(this.vanilla);
            Minecraft client = mock(Minecraft.class);
            client.level = this.level;
            LocalPlayer player = mock(LocalPlayer.class);
            when(player.level()).thenReturn(this.level);
            when(this.context.client()).thenReturn(client);
            when(this.context.player()).thenReturn(player);
            doAnswer(invocation -> this.queuedLight.add(invocation.getArgument(0))).when(this.level).queueLightUpdate(any());
        }

        private void receive(CCClientboundLevelCubeWithLightPacket packet) {
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, this.context);
        }

        private LevelCube install(CubePos pos) {
            LevelCube source = new LevelCube(this.level, pos);
            CCClientboundLevelCubePacketData data = new CCClientboundLevelCubePacketData(source);
            FriendlyByteBuf buffer = data.getReadBuffer();
            try {
                return this.cache.cc_replaceWithPacketData(pos.getX(), pos.getY(), pos.getZ(), buffer, data.getHeightmaps(), ignored -> {});
            } finally {
                buffer.release();
            }
        }

        private boolean apply(LevelCube cube, long epoch) {
            return this.cache.cc_applyCubeLight(cube, cube.cc_getLightRevision(), epoch, exact(cube.cc_getCubePos()),
                    this.cache.cc_lightGeneration());
        }
    }
}
