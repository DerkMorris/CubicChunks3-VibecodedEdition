package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightInvalidatePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLightRevokePacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.network.CubeLightPacketData;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

class TestCubeLightPackets extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-17, -9000, 31);

    @Test
    void scopedPacketsRoundTripAndRejectMalformedRevocationsAndFlags() {
        LevelCube cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        FriendlyByteBuf buffer = buffer();
        try {
            var packet = new CCClientboundLevelCubeWithLightPacket(cube, new CubeLightPacketSnapshot(27, exactLight(), true));
            CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            assertTrue(CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer).scopedLight());
            buffer.readerIndex(0);
            buffer.setByte(buffer.writerIndex() - 1, 2);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            var revoke = new CCClientboundCubeLightRevokePacket(CubePos.of(2, 1000000, -3), 28);
            CCClientboundCubeLightRevokePacket.STREAM_CODEC.encode(buffer, revoke);
            assertEquals(revoke, CCClientboundCubeLightRevokePacket.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            buffer.writeLong(POSITION.asLong()).writeLong(-1);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundCubeLightRevokePacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeLong(POSITION.asLong() ^ Long.MIN_VALUE).writeLong(1);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundCubeLightRevokePacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void scopedHandlerRecordsExpectationBeforeQueueAndUsesScopedPublication() {
        HandlerFixture fixture = new HandlerFixture();
        when(fixture.cache.cc_getVisibleCubeLightView(POSITION)).thenReturn(fixture.packet.light());
        var packet = new CCClientboundLevelCubeWithLightPacket(POSITION, fixture.packet.cubeData(), 12, fixture.packet.light(), true);
        new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, fixture.context);
        verify(fixture.cache).cc_expectScopedCubeLight(fixture.cube, 12);
        verify(fixture.cache, never()).cc_applyScopedCubeLight(any(), anyLong(), anyLong(), any(), anyLong());
        fixture.queue.remove().run();
        verify(fixture.cache).cc_applyScopedCubeLight(fixture.cube, fixture.cube.cc_getLightRevision(), 12, packet.light(), 7);
        new CCClientboundCubeLightRevokePacket.Handler().receive(new CCClientboundCubeLightRevokePacket(POSITION, 13), fixture.context);
        verify(fixture.cache).cc_invalidateScopedCubeLight(POSITION, 13);
    }

    @Test
    void fullPacketRoundTripKeepsBothExactLayersAndDetachedBlocks() {
        LevelCube cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        cube.setBlockState(POSITION.asBlockPos(1, 16, 31), Blocks.STONE.defaultBlockState());
        byte[][] block = randomLayer(45);
        byte[][] sky = randomLayer(98);
        CubeLightView light = CubeLightView.fromExact(POSITION, block, sky);
        CCClientboundLevelCubeWithLightPacket packet = new CCClientboundLevelCubeWithLightPacket(cube, new CubeLightPacketSnapshot(12, light));
        byte expected = block[0][0];
        block[0][0] ^= -1;
        cube.setBlockState(POSITION.asBlockPos(1, 16, 31), Blocks.AIR.defaultBlockState());
        FriendlyByteBuf buffer = buffer();
        try {
            CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            CCClientboundLevelCubeWithLightPacket decoded = CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer);
            assertEquals(POSITION, decoded.pos());
            assertEquals(12, decoded.lightEpoch());
            assertEquals(packet.cubeData(), decoded.cubeData());
            assertTrue(decoded.light().isReady());
            assertEquals(0, buffer.readableBytes());
            for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                assertArrayEquals(light.packedLayer(LightLayer.BLOCK, section).orElseThrow(),
                        decoded.light().packedLayer(LightLayer.BLOCK, section).orElseThrow());
                assertArrayEquals(sky[section], decoded.light().packedLayer(LightLayer.SKY, section).orElseThrow());
            }
            byte[] output = decoded.light().packedLayer(LightLayer.BLOCK, 0).orElseThrow();
            assertEquals(expected, output[0]);
            output[0] ^= -1;
            assertEquals(expected, decoded.light().packedLayer(LightLayer.BLOCK, 0).orElseThrow()[0]);
        } finally {
            buffer.release();
        }
    }

    @Test
    void unavailableLightRemainsAbsentAtZeroOrLaterEpoch() {
        LevelCube cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        for (long epoch : new long[] { 0, 16 }) {
            CCClientboundLevelCubeWithLightPacket packet = new CCClientboundLevelCubeWithLightPacket(cube, new CubeLightPacketSnapshot(epoch, null));
            FriendlyByteBuf buffer = buffer();
            try {
                CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.encode(buffer, packet);
                CCClientboundLevelCubeWithLightPacket decoded = CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer);
                assertNull(decoded.light());
                assertEquals(epoch, decoded.lightEpoch());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsUnsupportedVersionsNegativeEpochsAndPositionAliases() {
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(1);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarInt(4).writeLong(POSITION.asLong()).writeLong(-1);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarInt(4).writeLong(POSITION.asLong() ^ Long.MIN_VALUE);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        LevelCube cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        var blocks = new CCClientboundLevelCubePacketData(cube);
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubeWithLightPacket(POSITION, blocks, -1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CCClientboundLevelCubeWithLightPacket(CubePos.of(CubePos.MAX_COORDINATE_VALUE + 1, 0, 0), blocks, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubeWithLightPacket(CubePos.ZERO, blocks, 1, exactLight()));
    }

    @Test
    void rejectsUncertainLightAndMalformedFixedSectionArrays() {
        LevelCube cube = new LevelCube(mockLevelWithPalettes(Level.class), POSITION);
        var solution = BoundedCubeLightSolver.solve(new CubeLightBounds(POSITION, POSITION), Map.of(POSITION, CubeLightBlockSnapshot.capture(cube)),
                CubeLightBoundary.UNKNOWN);
        CubeLightView uncertain = CubeLightView.fromSolution(solution, POSITION).orElseThrow();
        assertFalse(uncertain.isReady());
        assertThrows(IllegalArgumentException.class,
                () -> new CCClientboundLevelCubeWithLightPacket(POSITION, new CCClientboundLevelCubePacketData(cube), 1, uncertain));
        FriendlyByteBuf buffer = buffer();
        try {
            assertThrows(IllegalArgumentException.class, () -> CubeLightPacketData.write(buffer, uncertain));
            assertEquals(0, buffer.writerIndex());
            for (int flag : new int[] { 2, 255 }) {
                buffer.clear();
                buffer.writeByte(flag);
                assertThrows(IllegalArgumentException.class, () -> CubeLightPacketData.read(buffer, POSITION));
            }
            for (int count : new int[] { -1, 0, 7, 9, Integer.MAX_VALUE }) {
                buffer.clear();
                buffer.writeByte(1);
                buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> CubeLightPacketData.read(buffer, POSITION));
            }
            for (int length : new int[] { -1, 0, 2047, 2049, Integer.MAX_VALUE }) {
                buffer.clear();
                buffer.writeByte(1);
                buffer.writeVarInt(CubicConstants.SECTION_COUNT).writeVarInt(length);
                assertThrows(IllegalArgumentException.class, () -> CubeLightPacketData.read(buffer, POSITION));
            }
            buffer.clear();
            CubeLightPacketData.write(buffer, exactLight());
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(IndexOutOfBoundsException.class, () -> CubeLightPacketData.read(buffer, POSITION));
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidationCodecValidatesVersionAndEpoch() {
        FriendlyByteBuf buffer = buffer();
        try {
            for (long epoch : new long[] { 0, 1, Long.MAX_VALUE }) {
                buffer.clear();
                var packet = new CCClientboundCubeLightInvalidatePacket(epoch);
                CCClientboundCubeLightInvalidatePacket.STREAM_CODEC.encode(buffer, packet);
                assertEquals(packet, CCClientboundCubeLightInvalidatePacket.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            }
            buffer.clear();
            buffer.writeVarInt(2);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundCubeLightInvalidatePacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarInt(1).writeLong(-1);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundCubeLightInvalidatePacket.STREAM_CODEC.decode(buffer));
            assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeLightInvalidatePacket(-1));
        } finally {
            buffer.release();
        }
    }

    @Test
    void fullHandlerReleasesBlockBufferAndAppliesLightOnlyAfterQueueValidation() {
        HandlerFixture fixture = new HandlerFixture();
        when(fixture.cache.cc_applyCubeLight(eq(fixture.cube), anyLong(), eq(12L), any(), eq(7L))).thenReturn(true);
        fixture.receive();
        assertEquals(0, fixture.receivedBuffer.refCnt());
        verify(fixture.cache).cc_expectCubeLight(fixture.cube, 12);
        verify(fixture.cache, never()).cc_applyCubeLight(any(), anyLong(), anyLong(), any(), anyLong());

        fixture.queue.remove().run();

        verify(fixture.cache).cc_applyCubeLight(fixture.cube, fixture.cube.cc_getLightRevision(), 12, fixture.packet.light(), 7);
    }

    @Test
    void unavailableFullPacketDoesNotStartRefreshExpectation() {
        HandlerFixture fixture = new HandlerFixture();
        CCClientboundLevelCubeWithLightPacket packet = new CCClientboundLevelCubeWithLightPacket(POSITION, fixture.packet.cubeData(), 12, null);
        new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, fixture.context);
        assertEquals(0, fixture.receivedBuffer.refCnt());
        verify(fixture.cache, never()).cc_expectCubeLight(any(), anyLong());
        fixture.queue.remove().run();
        verify(fixture.cache).cc_applyCubeLight(fixture.cube, fixture.cube.cc_getLightRevision(), 12, null, 7);
    }

    @Test
    void delayedLightCannotApplyAfterWorldChangeCubeReplacementOrBlockEdit() {
        for (int change = 0; change < 3; change++) {
            HandlerFixture fixture = new HandlerFixture();
            fixture.receive();
            if (change == 0) {
                fixture.client.level = mock(ClientLevel.class);
            } else if (change == 1) {
                LevelCube replacement = new LevelCube(fixture.level, POSITION);
                when(fixture.cache.cc_getCube(POSITION.getX(), POSITION.getY(), POSITION.getZ(), false)).thenReturn(replacement);
            } else {
                fixture.cube.setBlockState(POSITION.asBlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            }
            fixture.queue.remove().run();
            verify(fixture.cache, never()).cc_applyCubeLight(any(), anyLong(), anyLong(), any(), anyLong());
        }
    }

    @Test
    void epochInvalidationIsImmediateAndQueuedLightMustPassTheCacheEpochCheck() {
        HandlerFixture fixture = new HandlerFixture();
        fixture.receive();
        new CCClientboundCubeLightInvalidatePacket.Handler().receive(new CCClientboundCubeLightInvalidatePacket(13), fixture.context);
        verify(fixture.cache).cc_invalidateCubeLight(13);
        fixture.queue.remove().run();
        verify(fixture.cache).cc_applyCubeLight(fixture.cube, fixture.cube.cc_getLightRevision(), 12, fixture.packet.light(), 7);
    }

    @Test
    void rejectedOrFailedBlockReplacementDoesNotQueueLightAndAlwaysReleasesBuffer() {
        for (boolean fails : new boolean[] { false, true }) {
            HandlerFixture fixture = new HandlerFixture();
            when(fixture.cache.cc_replaceWithPacketData(eq(POSITION.getX()), eq(POSITION.getY()), eq(POSITION.getZ()), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        fixture.receivedBuffer = invocation.getArgument(3);
                        if (fails) {
                            throw new IllegalArgumentException("expected bad block packet");
                        }
                        return null;
                    });
            if (fails) {
                assertThrows(IllegalArgumentException.class, fixture::receive);
            } else {
                fixture.receive();
            }
            assertEquals(0, fixture.receivedBuffer.refCnt());
            assertTrue(fixture.queue.isEmpty());
            verify(fixture.cache, never()).cc_applyCubeLight(any(), anyLong(), anyLong(), any(), anyLong());
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static byte[][] randomLayer(long seed) {
        Random random = new Random(seed);
        byte[][] result = new byte[CubicConstants.SECTION_COUNT][2048];
        for (byte[] section : result) {
            random.nextBytes(section);
        }
        return result;
    }

    private static CubeLightView exactLight() {
        return CubeLightView.fromExact(POSITION, randomLayer(12), randomLayer(31));
    }

    private static final class HandlerFixture {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft client = mock(Minecraft.class);
        private final ClientChunkCache chunkCache = mock(ClientChunkCache.class, withSettings().extraInterfaces(ClientCubeCache.class));
        private final ClientCubeCache cache = (ClientCubeCache) this.chunkCache;
        private final LevelCube cube = new LevelCube(this.level, POSITION);
        private final CCClientboundLevelCubeWithLightPacket packet = new CCClientboundLevelCubeWithLightPacket(this.cube,
                new CubeLightPacketSnapshot(12, exactLight()));
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock(io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext.class);
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private FriendlyByteBuf receivedBuffer;

        private HandlerFixture() {
            this.client.level = this.level;
            when(((CanBeCubic) this.level).cc_isCubic()).thenReturn(true);
            when(this.level.getChunkSource()).thenReturn(this.chunkCache);
            when(this.chunkCache.getLightEngine()).thenReturn(LevelLightEngine.EMPTY);
            LocalPlayer player = mock(LocalPlayer.class);
            when(player.level()).thenReturn(this.level);
            when(this.context.player()).thenReturn(player);
            when(this.context.client()).thenReturn(this.client);
            when(this.cache.cc_replaceWithPacketData(eq(POSITION.getX()), eq(POSITION.getY()), eq(POSITION.getZ()), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        this.receivedBuffer = invocation.getArgument(3);
                        return this.cube;
                    });
            when(this.cache.cc_getCube(POSITION.getX(), POSITION.getY(), POSITION.getZ(), false)).thenReturn(this.cube);
            when(this.cache.cc_lightGeneration()).thenReturn(7L);
            doAnswer(invocation -> this.queue.add(invocation.getArgument(0))).when(this.level).queueLightUpdate(any());
        }

        private void receive() {
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(this.packet, this.context);
        }
    }
}
