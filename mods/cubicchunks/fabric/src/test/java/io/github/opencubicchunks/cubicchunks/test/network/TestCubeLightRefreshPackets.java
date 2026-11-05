package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeLightRefresh;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeLightRefreshPacket;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

class TestCubeLightRefreshPackets extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-17, -9000, 31);

    @Test
    void roundTripUsesOnlyCanonicalAnchorAndObservedEpoch() {
        CCServerboundCubeLightRefreshPacket packet = new CCServerboundCubeLightRefreshPacket(POSITION, 12);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCServerboundCubeLightRefreshPacket.STREAM_CODEC.encode(buffer, packet);
            assertEquals(17, buffer.readableBytes());
            assertEquals(packet, CCServerboundCubeLightRefreshPacket.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsVersionPositionAliasesNegativeEpochAndTruncation() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(2);
            assertThrows(IllegalArgumentException.class, () -> CCServerboundCubeLightRefreshPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            // INVALID_CUBE_POS also round-trips as (-1, -1, MAX_COORDINATE_VALUE); toggle the redundant marker bit for an actual alias.
            buffer.writeVarInt(1).writeLong(POSITION.asLong() ^ Long.MIN_VALUE).writeLong(0);
            assertThrows(IllegalArgumentException.class, () -> CCServerboundCubeLightRefreshPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarInt(1).writeLong(POSITION.asLong()).writeLong(-1);
            assertThrows(IllegalArgumentException.class, () -> CCServerboundCubeLightRefreshPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarInt(1).writeLong(POSITION.asLong());
            assertThrows(IndexOutOfBoundsException.class, () -> CCServerboundCubeLightRefreshPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new CCServerboundCubeLightRefreshPacket(POSITION, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CCServerboundCubeLightRefreshPacket(CubePos.of(CubePos.MAX_COORDINATE_VALUE + 1, 0, 0), 0));
    }

    @Test
    void serverHandlerDelegatesToCurrentCubicLevelValidationOnly() {
        ServerLevel level = mock(ServerLevel.class, withSettings().extraInterfaces(CanBeCubic.class));
        ServerChunkCache chunkCache = mock(ServerChunkCache.class, withSettings().extraInterfaces(ServerCubeCache.class));
        ServerCubeCache cache = (ServerCubeCache) chunkCache;
        ServerPlayer player = mock(ServerPlayer.class);
        ServerPlayNetworking.Context context = mock(ServerPlayNetworking.Context.class);
        when(context.player()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(chunkCache);
        CCServerboundCubeLightRefreshPacket packet = new CCServerboundCubeLightRefreshPacket(POSITION, 12);
        CCServerboundCubeLightRefreshPacket.Handler handler = new CCServerboundCubeLightRefreshPacket.Handler();
        handler.receive(packet, context.player());
        verify(cache, never()).cc_requestCubeLightRefresh(player, POSITION, 12);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        handler.receive(packet, context.player());
        verify(cache).cc_requestCubeLightRefresh(player, POSITION, 12);
    }

    @Test
    void clientTickRequiresConnectionCubicWorldAndNegotiatedChannelBeforePolling() {
        TickFixture fixture = new TickFixture();
        fixture.client.level = null;
        fixture.tick(true);
        fixture.client.level = fixture.level;
        when(fixture.client.getConnection()).thenReturn(null);
        fixture.tick(true);
        fixture.connect();
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(false);
        fixture.tick(true);
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(true);
        fixture.tick(false);
        verify(fixture.cache, never()).cc_pollCubeLightRefresh();
        assertTrue(fixture.sent.isEmpty());
        when(fixture.cache.cc_pollCubeLightRefresh()).thenReturn(fixture.packet);
        fixture.tick(true);
        assertEquals(List.of(fixture.packet), fixture.sent);
    }

    @Test
    void clientTickCannotSendAnOldWorldsRequestAfterWorldChangesDuringPolling() {
        TickFixture fixture = new TickFixture();
        when(fixture.cache.cc_pollCubeLightRefresh()).thenAnswer(invocation -> {
            fixture.client.level = null;
            return fixture.packet;
        });
        fixture.tick(true);
        assertTrue(fixture.sent.isEmpty());
        clearInvocations(fixture.cache);
        fixture.tick(true);
        verify(fixture.cache, never()).cc_pollCubeLightRefresh();
    }

    private static final class TickFixture {
        private final Minecraft client = mock(Minecraft.class);
        private final ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(CanBeCubic.class));
        private final ClientChunkCache chunkCache = mock(ClientChunkCache.class, withSettings().extraInterfaces(ClientCubeCache.class));
        private final ClientCubeCache cache = (ClientCubeCache) this.chunkCache;
        private final CCServerboundCubeLightRefreshPacket packet = new CCServerboundCubeLightRefreshPacket(POSITION, 12);
        private final List<CCServerboundCubeLightRefreshPacket> sent = new ArrayList<>();

        private TickFixture() {
            this.client.level = this.level;
            when(this.level.getChunkSource()).thenReturn(this.chunkCache);
            when(((CanBeCubic) this.level).cc_isCubic()).thenReturn(true);
            connect();
        }

        private void connect() {
            ClientPacketListener connection = mock(ClientPacketListener.class);
            when(this.client.getConnection()).thenReturn(connection);
        }

        private void tick(boolean canSend) {
            ClientCubeLightRefresh.tick(this.client, () -> canSend, this.sent::add);
        }
    }
}
