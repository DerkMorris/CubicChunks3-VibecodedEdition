package io.github.opencubicchunks.cubicchunks.test.server.level;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level.ChunkHolderBroadcastTestAccess;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CubeHolder;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TestCubeBroadcasting extends BaseTest {
    private static final CubePos POS = CubePos.of(-5, -1000000, 9);
    private MockedStatic<ServerPlayNetworking> network;

    @BeforeEach
    void captureTransport() {
        network = Mockito.mockStatic(ServerPlayNetworking.class);
    }

    @AfterEach
    void closeTransport() {
        network.close();
    }

    @Test
    void cubeConstructorUsesEightSectionsEvenInAShortDimensionAndColumnsKeepTheirOwnHeight() throws Exception {
        for (int height : new int[] { 16, 384 }) {
            Fixture fixture = new Fixture(height);
            assertEquals(CubicConstants.SECTION_COUNT, fixture.access.cc_testChangedSections().length);
            ChunkHolder column = new ChunkHolder(new ChunkPos(-10, 18), 31, LevelHeightAccessor.create(-64, height), LevelLightEngine.EMPTY,
                    (pos, queue, ticket, setQueue) -> {}, (pos, boundary) -> List.of());
            assertEquals(height / 16, ((ChunkHolderBroadcastTestAccess) column).cc_testChangedSections().length);
        }
    }

    @Test
    void singleChangesAcrossEverySectionSendCurrentBlocksAtTheCorrectPositions() throws Exception {
        Fixture fixture = new Fixture(16);
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
            BlockPos pos = Coords.sectionPosByIndex(POS, index).origin().offset(1, 2, 3);
            fixture.change(pos, Blocks.STONE.defaultBlockState());
            fixture.change(pos, Blocks.GLOWSTONE.defaultBlockState());
            expected.put(pos, Blocks.GLOWSTONE.defaultBlockState());
        }
        fixture.access.cc_testBroadcast(fixture.cube);
        assertEquals(CubicConstants.SECTION_COUNT, fixture.sent.size());
        for (CCClientboundCubeBlockChangesPacket update : fixture.sent) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                CCClientboundCubeBlockChangesPacket.STREAM_CODEC.encode(buffer, update);
                var decoded = CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(buffer);
                assertEquals(1, decoded.changes().size());
                var change = decoded.changes().getFirst();
                assertEquals(expected.remove(decoded.blockPos(change)), change.state());
            } finally {
                buffer.release();
            }
        }
        assertTrue(expected.isEmpty());
        verifyNoInteractions(fixture.players.getFirst().connection);
        assertFalse(fixture.holder.hasChangesToBroadcast());
        fixture.access.cc_testBroadcast(fixture.cube);
        assertEquals(CubicConstants.SECTION_COUNT, fixture.sent.size());
    }

    @Test
    void batchedSectionUpdatesCarryDetachedClientBlockEntityDataWithBlockStates() throws Exception {
        Fixture fixture = new Fixture(384);
        BlockPos chestPos = POS.asBlockPos(17, 18, 19);
        BlockPos stonePos = chestPos.offset(1, 0, 0);
        fixture.change(chestPos, Blocks.CHEST.defaultBlockState());
        fixture.change(stonePos, Blocks.STONE.defaultBlockState());
        BlockEntity blockEntity = spy(fixture.cube.getBlockEntity(chestPos));
        ClientboundBlockEntityDataPacket entityPacket = mock();
        CompoundTag tag = new CompoundTag();
        tag.putString("test", "snapshot");
        when(entityPacket.getPos()).thenReturn(chestPos);
        doReturn(BlockEntityTypes.CHEST).when(entityPacket).getType();
        when(entityPacket.getTag()).thenReturn(tag);
        doReturn(entityPacket).when(blockEntity).getUpdatePacket();
        fixture.cube.setBlockEntity(blockEntity);
        fixture.access.cc_testBroadcast(fixture.cube);
        tag.putString("test", "changed later");
        assertEquals(1, fixture.sent.size());
        var update = fixture.sent.getFirst();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundCubeBlockChangesPacket.STREAM_CODEC.encode(buffer, update);
            var packet = CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(buffer);
            Map<BlockPos, BlockState> decoded = new HashMap<>();
            for (var change : packet.changes()) {
                decoded.put(packet.blockPos(change), change.state());
                if (packet.blockPos(change).equals(chestPos)) {
                    assertEquals("snapshot", change.blockEntity().tag().getStringOr("test", ""));
                }
            }
            assertEquals(Map.of(chestPos, Blocks.CHEST.defaultBlockState(), stonePos, Blocks.STONE.defaultBlockState()), decoded);
        } finally {
            buffer.release();
        }
        assertFalse(fixture.holder.hasChangesToBroadcast());
    }

    @Test
    void emptyRecipientListDrainsChangesWithoutLeavingOldUpdatesForLaterPlayers() throws Exception {
        Fixture fixture = new Fixture(384);
        ServerPlayer player = fixture.players.removeFirst();
        fixture.change(POS.asBlockPos(31, 31, 31), Blocks.STONE.defaultBlockState());
        fixture.access.cc_testBroadcast(fixture.cube);
        assertFalse(fixture.holder.hasChangesToBroadcast());
        fixture.players.add(player);
        fixture.access.cc_testBroadcast(fixture.cube);
        assertTrue(fixture.sent.isEmpty());
    }

    @Test
    void failedTransportRetainsTheUnsentSectionForRetry() throws Exception {
        Fixture fixture = new Fixture(384);
        AtomicBoolean fail = new AtomicBoolean(true);
        network.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CCClientboundCubeBlockChangesPacket.class)))
                .thenAnswer(invocation -> {
                    if (fail.getAndSet(false)) {
                        throw new IllegalStateException("send failed");
                    }
                    fixture.sent.add(invocation.getArgument(1));
                    return null;
                });
        fixture.change(POS.asBlockPos(31, 31, 31), Blocks.STONE.defaultBlockState());
        assertThrows(IllegalStateException.class, () -> fixture.access.cc_testBroadcast(fixture.cube));
        assertTrue(fixture.holder.hasChangesToBroadcast());
        fixture.access.cc_testBroadcast(fixture.cube);
        assertEquals(1, fixture.sent.size());
        assertFalse(fixture.holder.hasChangesToBroadcast());
    }

    @Test
    void cubeLightingNeverProducesVanillaColumnMasks() throws Exception {
        Fixture fixture = new Fixture(384);
        assertFalse(fixture.holder.sectionLightChanged(LightLayer.SKY, -1000));
        assertFalse(fixture.holder.sectionLightChanged(LightLayer.BLOCK, 1000));
        fixture.access.cc_testSkyMask().set(5);
        fixture.access.cc_testBlockMask().set(7);
        fixture.access.cc_testBroadcast(fixture.cube);
        assertTrue(fixture.sent.isEmpty());
        assertFalse(fixture.holder.hasChangesToBroadcast());
    }

    @Test
    void aSectionWithManyEntityTagsSplitsIntoBoundedPacketsAndRetriesAfterAPartialSend() throws Exception {
        Fixture fixture = new Fixture(16);
        for (int index = 0; index < 5; index++) {
            BlockPos pos = POS.asBlockPos(index, 1, 1);
            fixture.change(pos, Blocks.CHEST.defaultBlockState());
            BlockEntity entity = spy(fixture.cube.getBlockEntity(pos));
            CompoundTag tag = new CompoundTag();
            tag.putByteArray("large", new byte[60000]);
            ClientboundBlockEntityDataPacket packet = mock();
            when(packet.getPos()).thenReturn(pos);
            doReturn(BlockEntityTypes.CHEST).when(packet).getType();
            when(packet.getTag()).thenReturn(tag);
            doReturn(packet).when(entity).getUpdatePacket();
            fixture.cube.setBlockEntity(entity);
        }
        AtomicBoolean fail = new AtomicBoolean(true);
        network.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CCClientboundCubeBlockChangesPacket.class)))
                .thenAnswer(invocation -> {
                    if (fixture.sent.size() == 1 && fail.getAndSet(false)) {
                        throw new IllegalStateException("second batch failed");
                    }
                    fixture.sent.add(invocation.getArgument(1));
                    return null;
                });
        assertThrows(IllegalStateException.class, () -> fixture.access.cc_testBroadcast(fixture.cube));
        assertTrue(fixture.holder.hasChangesToBroadcast());
        fixture.sent.clear();
        fixture.access.cc_testBroadcast(fixture.cube);
        assertFalse(fixture.holder.hasChangesToBroadcast());
        assertEquals(2, fixture.sent.size());
        assertEquals(5, fixture.sent.stream().mapToInt(packet -> packet.changes().size()).sum());
        for (var packet : fixture.sent) {
            assertTrue(packet.changes().stream().mapToInt(change -> change.blockEntity().size())
                    .sum() <= CCClientboundCubeBlockChangesPacket.MAX_TAG_BYTES);
        }
    }

    @Test
    void holderDoesNotAccumulateChangesUntilItsTickingCubeIsReady() throws Exception {
        Fixture fixture = new Fixture(16);
        CompletableFuture<ChunkResult<LevelCube>> dependency = new CompletableFuture<>();
        fixture.setTickingFuture(dependency);
        assertFalse(fixture.holder.blockChanged(POS.asBlockPos(31, 31, 31)));
        assertFalse(fixture.holder.hasChangesToBroadcast());
        dependency.complete(ChunkResult.of(fixture.cube));
        assertTrue(fixture.holder.blockChanged(POS.asBlockPos(31, 31, 31)));
    }

    private final class Fixture {
        private final Level level = mockLevelWithPalettes(Level.class);
        private final LevelCube cube = new LevelCube(level, POS);
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<CCClientboundCubeBlockChangesPacket> sent = new ArrayList<>();
        private final ChunkHolder holder;
        private final ChunkHolderBroadcastTestAccess access;

        private Fixture(int height) throws Exception {
            ServerPlayer player = mock();
            player.connection = mock(ServerGamePacketListenerImpl.class);
            network.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(CCClientboundCubeBlockChangesPacket.class)))
                    .thenAnswer(invocation -> {
                        sent.add(invocation.getArgument(1));
                        return null;
                    });
            players.add(player);
            CubeHolder.PlayerProvider provider = (pos, boundary) -> {
                assertEquals(POS, pos);
                assertFalse(boundary);
                return players;
            };
            holder = ChunkHolder.class.getConstructor(CubePos.class, int.class, LevelHeightAccessor.class, LevelLightEngine.class,
                    CubeHolder.LevelChangeListener.class, CubeHolder.PlayerProvider.class).newInstance(POS, 31,
                            LevelHeightAccessor.create(-64, height), LevelLightEngine.EMPTY,
                            (CubeHolder.LevelChangeListener) (pos, queue, ticket, setQueue) -> {}, provider);
            setTickingFuture(CompletableFuture.completedFuture(ChunkResult.of(cube)));
            access = (ChunkHolderBroadcastTestAccess) holder;
        }

        private void setTickingFuture(CompletableFuture<ChunkResult<LevelCube>> future) throws ReflectiveOperationException {
            var ticking = ChunkHolder.class.getDeclaredField("tickingChunkFuture");
            ticking.setAccessible(true);
            ticking.set(holder, future);
        }

        private void change(BlockPos pos, BlockState state) {
            cube.setBlockState(pos, state);
            holder.blockChanged(pos);
        }
    }
}
