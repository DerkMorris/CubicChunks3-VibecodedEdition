package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeBlockChanges;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubePredictions;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientLevel;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubePlayerActionPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeUseItemOnPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.TickThrottler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TestCubePlayerActionRouting extends BaseTest {
    private MockedStatic<PacketUtils> packetThread;

    @BeforeEach
    void onServerThread() {
        // World services are mocked; Fabric's real play receiver already dispatches to the server thread.
        packetThread = mockStatic(PacketUtils.class);
    }

    @AfterEach
    void closeThreadDispatch() {
        packetThread.close();
    }

    @Test
    void actualClientSendConvertsBlockActionsAndHitResultsBeforeVanillaEncoding() throws ReflectiveOperationException {
        ClientFixture fixture = new ClientFixture();
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            networking.when(() -> ClientPlayNetworking.canSend(any(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type.class)))
                    .thenReturn(true);
            for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
                BlockPos pos = new BlockPos(-17, y, 31);
                for (Action action : List.of(Action.START_DESTROY_BLOCK, Action.CHANGE_DESTROY_DIRECTION, Action.ABORT_DESTROY_BLOCK,
                        Action.STOP_DESTROY_BLOCK)) {
                    fixture.listener.send(new ServerboundPlayerActionPacket(action, pos, Direction.UP, 27));
                    var wrapped = assertInstanceOf(ServerboundCustomPayloadPacket.class, fixture.sent.getLast());
                    var payload = assertInstanceOf(CCServerboundCubePlayerActionPacket.class, wrapped.payload());
                    assertEquals(pos, TestCubePlayerActionPackets.roundTrip(CCServerboundCubePlayerActionPacket.STREAM_CODEC, payload).pos());
                    assertEquals(action, payload.action());
                    assertEquals(27, payload.sequence());
                }
                fixture.listener.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.DOWN, pos, true), 28));
                var wrapped = assertInstanceOf(ServerboundCustomPayloadPacket.class, fixture.sent.getLast());
                var payload = assertInstanceOf(CCServerboundCubeUseItemOnPacket.class, wrapped.payload());
                assertEquals(pos, TestCubePlayerActionPackets.roundTrip(CCServerboundCubeUseItemOnPacket.STREAM_CODEC, payload).pos());
                assertEquals(InteractionHand.OFF_HAND, payload.hand());
            }
        }
    }

    @Test
    void ordinaryActionsAndPositionlessUseStayVanillaAndMissingChannelCannotSilentlyTruncate() throws ReflectiveOperationException {
        ClientFixture fixture = new ClientFixture();
        var use = new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 9, 0, 0);
        fixture.listener.send(use);
        assertSame(use, fixture.sent.getLast());
        var drop = new ServerboundPlayerActionPacket(Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN);
        fixture.listener.send(drop);
        assertSame(drop, fixture.sent.getLast());
        var action = new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, new BlockPos(0, 32000000, 0), Direction.UP, 10);
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            assertThrows(IllegalStateException.class, () -> fixture.listener.send(action));
            assertEquals(2, fixture.sent.size());
            when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(false);
            fixture.listener.send(action);
            assertSame(action, fixture.sent.getLast());
        }
    }

    @Test
    void decodedActionsReachRealServerListenerWithFullCoordinatesCubicLimitAndCumulativeAck() throws ReflectiveOperationException {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
            ServerFixture fixture = new ServerFixture(new BlockPos(1, y, 2));
            for (Action action : List.of(Action.START_DESTROY_BLOCK, Action.CHANGE_DESTROY_DIRECTION, Action.ABORT_DESTROY_BLOCK,
                    Action.STOP_DESTROY_BLOCK)) {
                fixture.action(action, 25);
                verify(fixture.mode).handleBlockBreakAction(fixture.pos, action, Direction.UP, CubicBlockBounds.MAX, 25);
            }
            assertEquals(25, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
            fixture.action(Action.ABORT_DESTROY_BLOCK, 0);
            assertEquals(25, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
        }
    }

    @Test
    void useAtExtremeHeightsRetainsHitAndSendsTwoFullCoordinateCorrectionsBeforeAck() throws ReflectiveOperationException {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX }) {
            ServerFixture fixture = new ServerFixture(new BlockPos(1, y, 2));
            Direction face = y == CubicBlockBounds.MAX ? Direction.DOWN : Direction.UP;
            var packet = fixture.usePacket(face, 31);
            fixture.use(packet);
            var capture = org.mockito.ArgumentCaptor.forClass(BlockHitResult.class);
            verify(fixture.mode).useItemOn(eq(fixture.player), eq(fixture.level), any(), eq(InteractionHand.OFF_HAND), capture.capture());
            assertEquals(fixture.pos, capture.getValue().getBlockPos());
            assertEquals(Vec3.atCenterOf(fixture.pos), capture.getValue().getLocation());
            assertEquals(2, fixture.sent.size());
            assertEquals(fixture.pos, correction(fixture.sent.getFirst()).blockPos(correction(fixture.sent.getFirst()).changes().getFirst()));
            assertEquals(fixture.pos.relative(face),
                    correction(fixture.sent.getLast()).blockPos(correction(fixture.sent.getLast()).changes().getFirst()));
            assertEquals(31, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
        }
    }

    @Test
    void useStillChecksReachSpawnProtectionWorldPermissionsAndPendingTeleport() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(1, 32000000, 2));
        var packet = fixture.usePacket(Direction.UP, 1);
        when(fixture.player.isWithinBlockInteractionRange(any(), anyDouble())).thenReturn(false);
        fixture.use(packet);
        verify(fixture.mode, never()).useItemOn(any(), any(), any(), any(), any());
        assertTrue(fixture.sent.isEmpty());
        when(fixture.player.isWithinBlockInteractionRange(any(), anyDouble())).thenReturn(true);
        when(fixture.server.isUnderSpawnProtection(any(), any(), any())).thenReturn(true);
        fixture.use(packet);
        verify(fixture.player).sendSpawnProtectionMessage(fixture.pos);
        verify(fixture.mode, never()).useItemOn(any(), any(), any(), any(), any());
        when(fixture.server.isUnderSpawnProtection(any(), any(), any())).thenReturn(false);
        when(fixture.level.mayInteract(any(), any())).thenReturn(false);
        fixture.use(packet);
        verify(fixture.mode, never()).useItemOn(any(), any(), any(), any(), any());
        when(fixture.level.mayInteract(any(), any())).thenReturn(true);
        setField(ServerGamePacketListenerImpl.class, fixture.listener, "awaitingPositionFromClient", Vec3.atCenterOf(fixture.pos));
        fixture.use(packet);
        verify(fixture.mode, never()).useItemOn(any(), any(), any(), any(), any());
        assertEquals(1, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
    }

    @Test
    void realDestroyValidationRetainsReachSpawnProtectionAndAdventureRestrictions() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(1, -32000000, 2));
        ServerPlayerGameMode real = spy(new ServerPlayerGameMode(fixture.player));
        setField(ServerPlayer.class, fixture.player, "gameMode", real);
        doReturn(true).when(real).destroyBlock(any());
        when(fixture.player.isWithinBlockInteractionRange(any(), anyDouble())).thenReturn(false);
        fixture.action(Action.START_DESTROY_BLOCK, 1);
        verify(real, never()).destroyBlock(any());
        when(fixture.player.isWithinBlockInteractionRange(any(), anyDouble())).thenReturn(true);
        when(fixture.server.isUnderSpawnProtection(any(), any(), any())).thenReturn(true);
        fixture.action(Action.START_DESTROY_BLOCK, 2);
        verify(real, never()).destroyBlock(any());
        when(fixture.server.isUnderSpawnProtection(any(), any(), any())).thenReturn(false);
        when(fixture.player.blockActionRestricted(any(), any(), any())).thenReturn(true);
        fixture.action(Action.START_DESTROY_BLOCK, 3);
        verify(real, never()).destroyBlock(any());
        assertEquals(fixture.pos, correction(fixture.sent.getLast()).blockPos(correction(fixture.sent.getLast()).changes().getFirst()));
        when(fixture.player.blockActionRestricted(any(), any(), any())).thenReturn(false);
        fixture.player.getAbilities().instabuild = true;
        fixture.action(Action.START_DESTROY_BLOCK, 4);
        verify(real).destroyBlock(fixture.pos);
        assertEquals(4, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
    }

    @Test
    void nativeSuccessfulBreakConfirmsPredictionBeforeAckWithoutWaitingForCubeBroadcast() throws ReflectiveOperationException {
        for (int y : new int[] { -32000000, 32000000 }) {
            ServerFixture fixture = new ServerFixture(new BlockPos(7, y, 8));
            LevelCube serverCube = fixture.loadedCube();
            PredictedClientFixture client = new PredictedClientFixture(serverCube, fixture.pos);
            ServerPlayerGameMode real = spy(new ServerPlayerGameMode(fixture.player));
            setField(ServerPlayer.class, fixture.player, "gameMode", real);
            fixture.player.getAbilities().instabuild = true;
            // Keep native validation, destroyAndAck and packet dispatch; the world mutation deliberately has no ticking-cube broadcast.
            doAnswer(invocation -> serverCube.setBlockState(invocation.getArgument(0), Blocks.AIR.defaultBlockState()) != null).when(real)
                    .destroyBlock(any());
            client.predictBreak(7);
            fixture.action(Action.START_DESTROY_BLOCK, 7);
            verify(real).destroyBlock(fixture.pos);
            assertTrue(serverCube.getBlockState(fixture.pos).isAir());
            assertEquals(1, fixture.sent.size(), "A successful native break needs an explicit correction before its deferred ACK");
            assertTrue(correction(fixture.sent.getFirst()).changes().getFirst().state().isAir());
            fixture.flushAck();
            assertEquals(7, assertInstanceOf(ClientboundBlockChangedAckPacket.class, fixture.sent.getLast()).sequence());
            client.receive(fixture.sent);
            assertTrue(client.cube.getBlockState(fixture.pos).isAir(), "ACK must confirm AIR instead of restoring the original solid block");
            verify(fixture.cubes, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), eq(true));
        }
    }

    @Test
    void nativeRejectedBreakRollsBackPredictionWithCurrentSignDataBeforeAck() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(7, 32000000, 8));
        LevelCube serverCube = fixture.loadedCube();
        serverCube.setBlockState(fixture.pos, Blocks.OAK_SIGN.defaultBlockState());
        PredictedClientFixture client = new PredictedClientFixture(serverCube, fixture.pos);
        SignBlockEntity sign = (SignBlockEntity) serverCube.getBlockEntity(fixture.pos);
        List<Component> text = List.of(Component.literal("authoritative rejection"), Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(text, text, DyeColor.BLACK, false), SignTextSlot.FRONT);
        ServerPlayerGameMode real = spy(new ServerPlayerGameMode(fixture.player));
        setField(ServerPlayer.class, fixture.player, "gameMode", real);
        when(fixture.player.blockActionRestricted(any(), any(), any())).thenReturn(true);
        client.predictBreak(9);
        fixture.action(Action.START_DESTROY_BLOCK, 9);
        verify(real, never()).destroyBlock(any());
        var update = correction(fixture.sent.getLast()).changes().getFirst();
        assertEquals(Blocks.OAK_SIGN.defaultBlockState(), update.state());
        assertNotNull(update.blockEntity());
        fixture.flushAck();
        client.receive(fixture.sent);
        assertEquals(Blocks.OAK_SIGN.defaultBlockState(), client.cube.getBlockState(fixture.pos));
        SignBlockEntity restored = (SignBlockEntity) client.cube.getBlockEntity(fixture.pos);
        assertNotNull(restored);
        assertEquals("authoritative rejection", restored.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString());
    }

    @Test
    void unavailableOrUntrackedTargetsAreAcknowledgedWithoutLoadingOrDispatchAndWrongWorldIsIgnored() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(1, 32000000, 2));
        when(((CCServerPlayer) fixture.player).cc_getCloTrackingView()).thenReturn(CloTrackingView.EMPTY);
        clearInvocations(fixture.cache);
        fixture.action(Action.START_DESTROY_BLOCK, 3);
        fixture.use(fixture.usePacket(Direction.UP, 4));
        verify(fixture.cubes, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean());
        verify(fixture.mode, never()).handleBlockBreakAction(any(), any(), any(), anyInt(), anyInt());
        verify(fixture.mode, never()).useItemOn(any(), any(), any(), any(), any());
        fixture.track();
        when(fixture.cubes.cc_getCube(anyInt(), anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false))).thenReturn(null);
        fixture.action(Action.START_DESTROY_BLOCK, 5);
        verify(fixture.mode, never()).handleBlockBreakAction(any(), any(), any(), anyInt(), anyInt());
        verify(fixture.cubes, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), eq(true));
        assertEquals(5, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
        when(fixture.listener.hasClientLoaded()).thenReturn(false);
        fixture.action(Action.START_DESTROY_BLOCK, 6);
        when(fixture.listener.hasClientLoaded()).thenReturn(true);
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(false);
        fixture.action(Action.START_DESTROY_BLOCK, 7);
        assertEquals(5, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
    }

    @Test
    void unloadedPlacementNeighborAndOutOfRangeCorrectionCannotCauseCoordinateAliasing() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(1, 32000031, 2));
        fixture.use(fixture.usePacket(Direction.UP, 12));
        verify(fixture.mode, never()).useItemOn(any(), any(), any(), any(), any());
        assertEquals(12, getField(ServerGamePacketListenerImpl.class, fixture.listener, "ackBlockChangesUpTo"));
        fixture.listener.send(new ClientboundBlockUpdatePacket(new BlockPos(1, CubicBlockBounds.MAX + 1, 2), Blocks.AIR.defaultBlockState()));
        assertTrue(fixture.sent.isEmpty());
    }

    @Test
    void ordinaryServerCorrectionsAndAcksRemainVanilla() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(1, 64, 2));
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(false);
        var update = new ClientboundBlockUpdatePacket(fixture.pos, Blocks.STONE.defaultBlockState());
        fixture.listener.send(update);
        assertSame(update, fixture.sent.getLast());
        var ack = new ClientboundBlockChangedAckPacket(17);
        fixture.listener.send(ack);
        assertSame(ack, fixture.sent.getLast());
        fixture.listener.handlePlayerAction(new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, fixture.pos, Direction.UP, 17));
        verify(fixture.mode).handleBlockBreakAction(fixture.pos, Action.START_DESTROY_BLOCK, Direction.UP, 319, 17);
    }

    @Test
    void directCorrectionIncludesVisibleBlockEntityDataFromTheLoadedCube() throws ReflectiveOperationException {
        ServerFixture fixture = new ServerFixture(new BlockPos(1, -32000000, 2));
        CubePos pos = CubePos.from(fixture.pos);
        LevelCube cube = (LevelCube) fixture.cubes.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.FULL, false);
        cube.setBlockState(fixture.pos, Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity sign = (SignBlockEntity) cube.getBlockEntity(fixture.pos);
        List<Component> text = List.of(Component.literal("correction text"), Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(text, text, DyeColor.BLACK, false), SignTextSlot.FRONT);
        fixture.listener.send(new ClientboundBlockUpdatePacket(fixture.pos, Blocks.OAK_SIGN.defaultBlockState()));
        var packet = correction(fixture.sent.getLast());
        var update = packet.changes().getFirst().blockEntity();
        assertNotNull(update);
        assertEquals(BlockEntityTypes.SIGN, update.type());
        assertEquals(sign.getUpdateTag(fixture.level.registryAccess()), update.tag());
    }

    private static CCClientboundCubeBlockChangesPacket correction(Packet<?> packet) {
        var payload = assertInstanceOf(CCClientboundCubeBlockChangesPacket.class,
                assertInstanceOf(ClientboundCustomPayloadPacket.class, packet).payload());
        return TestCubePlayerActionPackets.roundTrip(CCClientboundCubeBlockChangesPacket.STREAM_CODEC, payload);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class ClientFixture {
        private final ClientLevel level = mock();
        private final ClientPacketListener listener = mock();
        private final List<Packet<?>> sent = new ArrayList<>();

        private ClientFixture() throws ReflectiveOperationException {
            Minecraft client = mock();
            client.level = level;
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            Connection connection = mock();
            setField(ClientCommonPacketListenerImpl.class, listener, "minecraft", client);
            setField(ClientCommonPacketListenerImpl.class, listener, "connection", connection);
            doAnswer(invocation -> {
                sent.add(invocation.getArgument(0));
                return null;
            }).when(connection).send(any());
            doCallRealMethod().when(listener).send(any());
        }
    }

    private static final class ServerFixture {
        private final BlockPos pos;
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final MinecraftServer server = mock();
        private final ServerChunkCache cache = mock();
        private final CubeSource cubes = (CubeSource) cache;
        private final ServerPlayer player = mock();
        private final ServerPlayerGameMode mode = mock();
        private final ServerGamePacketListenerImpl listener = mock();
        private final List<Packet<?>> sent = new ArrayList<>();
        private final ServerPlayNetworking.Context context = mock();

        private ServerFixture(BlockPos pos) throws ReflectiveOperationException {
            this.pos = pos;
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getServer()).thenReturn(server);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getLightEngine()).thenReturn(mock());
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.mayInteract(any(), any())).thenReturn(true);
            when(player.level()).thenReturn(level);
            when(player.getAbilities()).thenReturn(new Abilities());
            when(player.isWithinBlockInteractionRange(any(), anyDouble())).thenReturn(true);
            when(player.getItemInHand(any())).thenReturn(new ItemStack(Items.STICK));
            when(mode.useItemOn(any(), any(), any(), any(), any())).thenReturn(InteractionResult.PASS);
            setField(ServerPlayer.class, player, "gameMode", mode);
            player.connection = listener;
            listener.player = player;
            track();
            LevelCube cube = new LevelCube(level, CubePos.from(pos));
            cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
            CubePos cubePos = CubePos.from(pos);
            when(cubes.cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ChunkStatus.FULL, false)).thenReturn(cube);
            when(level.getBlockState(any())).thenAnswer(invocation -> cube.getBlockState(invocation.getArgument(0)));
            Connection connection = mock();
            setField(ServerCommonPacketListenerImpl.class, listener, "server", server);
            setField(ServerCommonPacketListenerImpl.class, listener, "connection", connection);
            setField(ServerGamePacketListenerImpl.class, listener, "ackBlockChangesUpTo", -1);
            when(listener.hasClientLoaded()).thenReturn(true);
            doCallRealMethod().when(listener).handlePlayerAction(any());
            doCallRealMethod().when(listener).handleUseItemOn(any());
            doCallRealMethod().when(listener).ackBlockChangesUpTo(anyInt());
            doCallRealMethod().when(listener).send(any());
            doCallRealMethod().when(listener).send(any(), any());
            doAnswer(invocation -> {
                sent.add(invocation.getArgument(0));
                return null;
            }).when(connection).send(any(), any(), anyBoolean());
            when(context.player()).thenReturn(player);
        }

        private void track() {
            when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(CubePos.from(pos)), 5));
        }

        private LevelCube loadedCube() {
            CubePos cubePos = CubePos.from(pos);
            return (LevelCube) cubes.cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ChunkStatus.FULL, false);
        }

        private void flushAck() throws ReflectiveOperationException {
            // Pause unrelated player simulation while executing the real listener tick that emits the accumulated ACK.
            when(server.isPaused()).thenReturn(true);
            for (String field : List.of("chatSpamThrottler", "commandSpamThrottler", "dropSpamThrottler")) {
                setField(ServerGamePacketListenerImpl.class, listener, field, mock(TickThrottler.class));
            }
            setField(ServerGamePacketListenerImpl.class, listener, "commandSuggestionsProvider", mock(ServerCommandSuggestionsProvider.class));
            doCallRealMethod().when(listener).tick();
            listener.tick();
        }

        private void action(Action action, int sequence) {
            var packet = new CCServerboundCubePlayerActionPacket(pos, action, Direction.UP, sequence);
            new CCServerboundCubePlayerActionPacket.Handler()
                    .receive(TestCubePlayerActionPackets.roundTrip(CCServerboundCubePlayerActionPacket.STREAM_CODEC, packet), context.player());
        }

        private CCServerboundCubeUseItemOnPacket usePacket(Direction face, int sequence) {
            return new CCServerboundCubeUseItemOnPacket(pos, face, new Vec3(0.5, 0.5, 0.5), InteractionHand.OFF_HAND, false, false, sequence);
        }

        private void use(CCServerboundCubeUseItemOnPacket packet) {
            new CCServerboundCubeUseItemOnPacket.Handler()
                    .receive(TestCubePlayerActionPackets.roundTrip(CCServerboundCubeUseItemOnPacket.STREAM_CODEC, packet), context.player());
        }
    }

    private static final class PredictedClientFixture {
        private final BlockPos pos;
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final LocalPlayer player = mock();
        private final ClientCubePredictions predictions = new ClientCubePredictions();
        private final LevelCube cube;

        private PredictedClientFixture(LevelCube source, BlockPos pos) {
            this.pos = pos;
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.isClientSide()).thenReturn(true);
            when(player.level()).thenReturn(level);
            when(player.position()).thenReturn(Vec3.atCenterOf(pos));
            Misc.setupLevelHeight(level, -64, 384);
            ClientChunkCache vanilla = new ClientChunkCache(level, 5);
            when(level.getChunkSource()).thenReturn(vanilla);
            when(((CubicClientLevel) level).cc_getCubePredictions()).thenReturn(predictions);
            ClientCubeCache cache = (ClientCubeCache) vanilla;
            CubePos cubePos = CubePos.from(pos);
            cache.cc_updateViewCenter(cubePos.getX(), cubePos.getY(), cubePos.getZ());
            var data = new CCClientboundLevelCubePacketData(source);
            FriendlyByteBuf buffer = data.getReadBuffer();
            try {
                cube = cache.cc_replaceWithPacketData(cubePos.getX(), cubePos.getY(), cubePos.getZ(), buffer, data.getHeightmaps(), ignored -> {});
            } finally {
                buffer.release();
            }
        }

        private void predictBreak(int sequence) {
            var state = cube.getBlockState(pos);
            predictions.retain(level, pos, state, CCClientboundCubeBlockChangesPacket.captureBlockEntity(cube, pos, state), player, sequence);
            cube.setBlockState(pos, Blocks.AIR.defaultBlockState());
        }

        private void receive(List<Packet<?>> packets) {
            for (Packet<?> packet : packets) {
                if (packet instanceof ClientboundBlockChangedAckPacket ack) {
                    predictions.acknowledge(level, player, ack.sequence(), -1);
                } else {
                    var update = correction(packet);
                    for (var change : update.changes()) {
                        ClientCubeBlockChanges.applyServerUpdate(level, cube, update.blockPos(change), change.state(), change.blockEntity());
                    }
                    assertTrue(cube.getBlockState(pos).isAir(), "Corrections update pending authoritative data until ACK");
                }
            }
        }
    }
}
