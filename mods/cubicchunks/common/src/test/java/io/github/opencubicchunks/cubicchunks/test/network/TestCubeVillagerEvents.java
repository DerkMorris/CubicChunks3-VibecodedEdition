package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.test.network.TestCubeVillagerMetadata.roundTrip;
import static io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEventPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeLevelEventPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import org.junit.jupiter.api.Test;

class TestCubeVillagerEvents extends BaseTest {
    @Test
    void realServerSendConvertsBellAndComposterEventsOnlyInCubicWorld() throws Exception {
        ServerLevel level = mock();
        ServerPlayer player = mock();
        ServerGamePacketListenerImpl listener = mock();
        listener.player = player;
        when(player.level()).thenReturn(level);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        var connection = mock(Connection.class);
        setField(ServerCommonPacketListenerImpl.class, listener, "connection", connection);
        setField(ServerCommonPacketListenerImpl.class, listener, "server", mock(net.minecraft.server.MinecraftServer.class));
        List<Packet<?>> sent = new ArrayList<>();
        doAnswer(i -> sent.add(i.getArgument(0))).when(connection).send(any(), any(), anyBoolean());
        doCallRealMethod().when(listener).send(any());
        doCallRealMethod().when(listener).send(any(), any());
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
            BlockPos pos = new BlockPos(1, y, 2);
            listener.send(new ClientboundBlockEventPacket(pos, Blocks.BELL, 1, Direction.EAST.get3DDataValue()));
            var block = assertInstanceOf(CCClientboundCubeBlockEventPacket.class,
                    assertInstanceOf(ClientboundCustomPayloadPacket.class, sent.getLast()).payload());
            assertEquals(pos, roundTrip(CCClientboundCubeBlockEventPacket.STREAM_CODEC, block).pos());
            listener.send(new ClientboundLevelEventPacket(1500, pos, 1, false));
            var effect = assertInstanceOf(CCClientboundCubeLevelEventPacket.class,
                    assertInstanceOf(ClientboundCustomPayloadPacket.class, sent.getLast()).payload());
            assertEquals(effect, roundTrip(CCClientboundCubeLevelEventPacket.STREAM_CODEC, effect));
        }
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(false);
        var block = new ClientboundBlockEventPacket(BlockPos.ZERO, Blocks.BELL, 1, 2);
        var effect = new ClientboundLevelEventPacket(1500, BlockPos.ZERO, 1, false);
        listener.send(block);
        assertSame(block, sent.getLast());
        listener.send(effect);
        assertSame(effect, sent.getLast());
    }

    @Test
    void clientRingsActualLoadedBellAndDispatchesComposterEffectsAtFullHeight() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            var f = new ClientFixture(new BlockPos(2, y, 2));
            var bell = (BellBlockEntity) f.cube.getBlockEntity(f.pos);
            assertTrue(!bell.shaking);
            f.ring();
            assertTrue(bell.shaking);
            assertEquals(Direction.EAST, bell.clickDirection);
            f.effect(false);
            verify(f.level).levelEvent(1500, f.pos, 1);
            f.effect(true);
            verify(f.level).globalLevelEvent(1500, f.pos, 1);
        }
    }

    @Test
    void staleWorldMissingCubeAndReplacedBellDoNotReceiveBlockEvents() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            var f = new ClientFixture(new BlockPos(2, 32000008, 2));
            switch (scenario) {
                case 0 -> f.cache.cc_drop(CubePos.from(f.pos));
                case 1 -> f.cube.setBlockState(f.pos, Blocks.STONE.defaultBlockState());
                case 2 -> when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
                default -> f.client.level = mock();
            }
            clearInvocations(f.level);
            f.ring();
            verify(f.level, never()).blockEvent(any(), any(), anyInt(), anyInt());
            if (scenario != 1) {
                f.effect(false);
                verify(f.level, never()).levelEvent(anyInt(), any(), anyInt());
            }
        }
    }

    @Test
    void eventCodecsPreserveNativeUnsignedBytesAndRejectBadHeadersAndTruncation() {
        var event = new CCClientboundCubeBlockEventPacket(new BlockPos(1, 32000000, 2), Blocks.BELL, -1, 258);
        var decoded = roundTrip(CCClientboundCubeBlockEventPacket.STREAM_CODEC, event);
        assertEquals(255, decoded.action());
        assertEquals(2, decoded.parameter());
        var buffer = TestCubeVillagerMetadata.buffer();
        try {
            CCClientboundCubeBlockEventPacket.STREAM_CODEC.encode(buffer, event);
            buffer.setByte(0, 2);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundCubeBlockEventPacket.STREAM_CODEC.decode(buffer));
            buffer.setByte(0, 1);
            int length = buffer.writerIndex();
            for (int end = 0; end < length; end++) {
                buffer.readerIndex(0);
                buffer.writerIndex(end);
                assertThrows(RuntimeException.class, () -> CCClientboundCubeBlockEventPacket.STREAM_CODEC.decode(buffer));
            }
            buffer.clear();
            CCClientboundCubeLevelEventPacket.STREAM_CODEC.encode(buffer, new CCClientboundCubeLevelEventPacket(event.pos(), 1500, 1, false));
            buffer.setByte(buffer.writerIndex() - 1, 2);
            assertThrows(IllegalArgumentException.class, () -> CCClientboundCubeLevelEventPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static final class ClientFixture {
        private final BlockPos pos;
        private final ClientLevel level = Misc.mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft client = mock();
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock();
        private final ClientCubeCache cache;
        private final LevelCube cube;

        private ClientFixture(BlockPos pos) {
            this.pos = pos;
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.isClientSide()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            var chunks = new ClientChunkCache(level, 5);
            cache = (ClientCubeCache) chunks;
            when(level.getChunkSource()).thenReturn(chunks);
            client.level = level;
            LocalPlayer player = mock();
            when(player.level()).thenReturn(level);
            when(context.client()).thenReturn(client);
            when(context.player()).thenReturn(player);
            CubePos key = CubePos.from(pos);
            cache.cc_updateViewCenter(key.getX(), key.getY(), key.getZ());
            LevelCube source = new LevelCube(level, key);
            source.setBlockState(pos, Blocks.BELL.defaultBlockState());
            var packet = roundTrip(CCClientboundLevelCubeWithLightPacket.STREAM_CODEC, new CCClientboundLevelCubeWithLightPacket(source));
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, context);
            cube = cache.cc_getCube(key.getX(), key.getY(), key.getZ(), false);
            when(level.getBlockState(any())).thenAnswer(i -> cube.getBlockState(i.getArgument(0)));
            when(level.getBlockEntity(any())).thenAnswer(i -> cube.getBlockEntity(i.getArgument(0)));
            doCallRealMethod().when(level).blockEvent(any(), any(), anyInt(), anyInt());
        }

        private void ring() {
            var packet = new CCClientboundCubeBlockEventPacket(pos, Blocks.BELL, 1, Direction.EAST.get3DDataValue());
            new CCClientboundCubeBlockEventPacket.Handler().receive(roundTrip(CCClientboundCubeBlockEventPacket.STREAM_CODEC, packet), context);
        }

        private void effect(boolean global) {
            var packet = new CCClientboundCubeLevelEventPacket(pos, 1500, 1, global);
            new CCClientboundCubeLevelEventPacket.Handler().receive(roundTrip(CCClientboundCubeLevelEventPacket.STREAM_CODEC, packet), context);
        }
    }
}
