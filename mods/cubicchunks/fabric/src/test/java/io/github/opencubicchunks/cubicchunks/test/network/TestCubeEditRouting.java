package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.test.network.TestCubePlayerActionPackets.roundTrip;
import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEntityDataPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeOpenSignEditorPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetCommandBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSignUpdatePacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.TextFilter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TestCubeEditRouting extends BaseTest {
    private MockedStatic<PacketUtils> packetThread;

    @BeforeEach
    void threadDispatch() {
        // Fabric owns dispatch in production; no running server thread exists in these fixtures.
        packetThread = mockStatic(PacketUtils.class);
    }

    @AfterEach
    void closeDispatch() {
        packetThread.close();
    }

    @Test
    void actualClientSendWrapsAllThreeEditsAndOrdinaryWorldsKeepVanilla() throws ReflectiveOperationException {
        ClientFixture f = new ClientFixture();
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            networking.when(() -> ClientPlayNetworking.canSend(any(CustomPacketPayload.Type.class))).thenReturn(true);
            for (int y : TestCubeEditPackets.HEIGHTS) {
                BlockPos pos = new BlockPos(-1, y, 2);
                var sign = new ServerboundSignUpdatePacket(pos, List.of("a", "b", "c", "d"), SignTextSlot.BACK);
                f.listener.send(sign);
                assertEquals(pos, roundTrip(CCServerboundCubeSignUpdatePacket.STREAM_CODEC,
                        assertInstanceOf(CCServerboundCubeSignUpdatePacket.class, f.payload())).pos());
                f.listener.send(TestCubeEditPackets.command(pos, "say test").vanilla());
                assertEquals(pos, roundTrip(CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC,
                        assertInstanceOf(CCServerboundCubeSetCommandBlockPacket.class, f.payload())).pos());
                f.listener.send(TestCubeEditPackets.structure(pos, BlockPos.ZERO, Vec3i.ZERO, 1).vanilla());
                assertEquals(pos, roundTrip(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC,
                        assertInstanceOf(CCServerboundCubeSetStructureBlockPacket.class, f.payload())).pos());
            }
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            for (Packet<?> packet : edits(new BlockPos(1, 32000000, 2))) {
                f.listener.send(packet);
                assertSame(packet, f.sent.getLast());
            }
        }
    }

    @Test
    void absentChannelsRejectEveryEditInsteadOfSendingPackedCoordinates() throws ReflectiveOperationException {
        ClientFixture f = new ClientFixture();
        try (var networking = mockStatic(ClientPlayNetworking.class)) {
            for (Packet<?> packet : edits(new BlockPos(1, -32000000, 2))) {
                assertThrows(IllegalStateException.class, () -> f.listener.send(packet));
            }
            assertTrue(f.sent.isEmpty());
        }
    }

    private static List<Packet<?>> edits(BlockPos pos) {
        return List.of(new ServerboundSignUpdatePacket(pos, List.of("", "", "", ""), SignTextSlot.FRONT),
                TestCubeEditPackets.command(pos, "").vanilla(), TestCubeEditPackets.structure(pos, BlockPos.ZERO, Vec3i.ZERO, 1).vanilla());
    }

    @Test
    void signFilteringAndBothTextSidesUpdateRealSignsOnlyAfterServerCallback() throws ReflectiveOperationException {
        for (int y : TestCubeEditPackets.HEIGHTS) {
            for (SignTextSlot slot : SignTextSlot.values()) {
                ServerFixture f = new ServerFixture(new BlockPos(-1, y, 2));
                SignBlockEntity sign = f.sign();
                f.signEdit(slot);
                verify(f.filter).processMessageBundle(List.of("hello", "second", "", "fourth"));
                assertEquals("", text(sign, slot));
                f.filtered.complete(List.of(FilteredText.passThrough("hello"), FilteredText.fullyFiltered("second"), FilteredText.EMPTY,
                        FilteredText.passThrough("fourth")));
                assertEquals("", text(sign, slot));
                f.drain();
                assertEquals("hello", text(sign, slot));
                assertEquals("", sign.getText(slot).getMessages(true).get(1).getString());
                assertEquals("second", sign.getText(slot).getMessages(false).get(1).getString());
                assertNull(sign.getPlayerWhoMayEdit());
                verify(f.level, times(2)).sendBlockUpdated(eq(f.pos), any(), any(), eq(3));
                verify(f.level, never()).hasChunkAt(any());
            }
        }
    }

    @Test
    void signWaxAndEditorOwnershipAndFilteringPreferenceRemainEnforced() throws ReflectiveOperationException {
        for (int condition = 0; condition < 3; condition++) {
            ServerFixture f = new ServerFixture(new BlockPos(1, 32000000, 2));
            SignBlockEntity sign = f.sign();
            if (condition == 0) {
                sign.setWaxed(true);
            } else if (condition == 1) {
                sign.setAllowedPlayerEditor(UUID.randomUUID());
            } else {
                when(f.player.isTextFilteringEnabled()).thenReturn(true);
            }
            clearInvocations(f.level);
            f.signEdit(SignTextSlot.FRONT);
            f.filtered.complete(List.of(FilteredText.fullyFiltered("hello"), FilteredText.EMPTY, FilteredText.EMPTY, FilteredText.EMPTY));
            f.drain();
            assertEquals("", text(sign, SignTextSlot.FRONT));
            if (condition < 2) {
                verify(f.level, never()).sendBlockUpdated(any(), any(), any(), anyInt());
            }
        }
    }

    @Test
    void deferredSignResultsCannotCrossWorldConnectionTrackingCubeOrEntityReplacement() throws ReflectiveOperationException {
        for (int change = 0; change < 7; change++) {
            ServerFixture f = new ServerFixture(new BlockPos(1, -32000000, 2));
            SignBlockEntity original = f.sign();
            f.signEdit(SignTextSlot.FRONT);
            invalidateSignTarget(f, change);
            f.finishSign();
            assertEquals("", text(original, SignTextSlot.FRONT));
            verify(f.level, never()).sendBlockUpdated(any(), any(), any(), anyInt());
            verify(f.cubes, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), eq(true));
        }
    }

    private static void invalidateSignTarget(ServerFixture f, int change) {
        switch (change) {
            case 0 -> when(f.player.level()).thenReturn(mock());
            case 1 -> f.player.connection = mock();
            case 2 -> when(f.listener.isAcceptingMessages()).thenReturn(false);
            case 3 -> when(((CCServerPlayer) f.player).cc_getCloTrackingView()).thenReturn(CloTrackingView.EMPTY);
            case 4 -> f.available(null);
            case 5 -> f.available(new LevelCube(f.level, CubePos.from(f.pos)));
            default -> f.cube.setBlockEntity(new SignBlockEntity(f.pos, Blocks.OAK_SIGN.defaultBlockState()));
        }
    }

    @Test
    void commandsUseRealVanillaPermissionsAndModeChangesReinsertIntoCubeWithoutColumnLookup() throws ReflectiveOperationException {
        for (int y : TestCubeEditPackets.HEIGHTS) {
            ServerFixture f = new ServerFixture(new BlockPos(1, y, 2));
            CommandBlockEntity command = f.command();
            var packet = new CCServerboundCubeSetCommandBlockPacket(f.pos, "say Привет", CommandBlockEntity.Mode.SEQUENCE, false, true, false);
            when(f.player.canUseGameMasterBlocks()).thenReturn(false);
            f.commandEdit(packet);
            assertEquals("", command.getCommandBlock().getCommand());
            when(f.player.canUseGameMasterBlocks()).thenReturn(true);
            f.commandEdit(packet);
            assertEquals("say Привет", command.getCommandBlock().getCommand());
            assertEquals(CommandBlockEntity.Mode.SEQUENCE, command.getMode());
            assertFalse(command.getCommandBlock().isTrackOutput());
            assertTrue(command.isConditional());
            assertSame(command, f.cube.getBlockEntities().get(f.pos));
            assertTrue(f.cube.getBlockState(f.pos).is(Blocks.CHAIN_COMMAND_BLOCK));
            verify(f.level, never()).getChunkAt(any());
            verify(f.cubes, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), eq(true));
        }
    }

    @Test
    void structureFieldsReachRealHandlerAtExtremeHeightsAndRespectGameMasterPermission() throws ReflectiveOperationException {
        for (int y : TestCubeEditPackets.HEIGHTS) {
            ServerFixture f = new ServerFixture(new BlockPos(1, y, 2));
            StructureBlockEntity entity = f.structure();
            var packet = new CCServerboundCubeSetStructureBlockPacket(f.pos, StructureBlockEntity.UpdateType.UPDATE_DATA, StructureMode.SAVE,
                    "minecraft:example", new BlockPos(-48, 48, -2), new Vec3i(48, 3, 2), Mirror.FRONT_BACK, Rotation.CLOCKWISE_90, "metadata", false,
                    true, true, false, 0.25F, Long.MIN_VALUE);
            when(f.player.canUseGameMasterBlocks()).thenReturn(false);
            f.structureEdit(packet);
            assertEquals("", entity.getStructureName());
            when(f.player.canUseGameMasterBlocks()).thenReturn(true);
            f.structureEdit(packet);
            assertEquals(packet.name(), entity.getStructureName());
            assertEquals(packet.offset(), entity.getStructurePos());
            assertEquals(packet.size(), entity.getStructureSize());
            assertEquals(packet.mode(), entity.getMode());
            assertEquals(packet.mirror(), entity.getMirror());
            assertEquals(packet.rotation(), entity.getRotation());
            assertEquals(packet.data(), entity.getMetaData());
            assertEquals(packet.seed(), entity.getSeed());
            assertEquals(packet.integrity(), entity.getIntegrity());
            assertTrue(entity.isStrict());
            assertTrue(entity.getShowAir());
            assertFalse(entity.getShowBoundingBox());
            assertFalse(entity.isIgnoreEntities());
        }
    }

    @Test
    void structureSaveLoadAndScanDispatchKeepTheirVanillaOperationSelection() throws ReflectiveOperationException {
        ServerFixture f = new ServerFixture(new BlockPos(1, -32000000, 2));
        StructureBlockEntity entity = spy(f.structure());
        f.cube.setBlockEntity(entity);
        // Test operation selection only; the real corner search has separate integration tests.
        doReturn(true).when(entity).saveStructure();
        doReturn(true).when(entity).isStructureLoadable();
        doReturn(true).when(entity).placeStructureIfSameSize(any());
        doReturn(true).when(entity).detectSize();
        for (StructureBlockEntity.UpdateType action : StructureBlockEntity.UpdateType.values()) {
            f.structureEdit(new CCServerboundCubeSetStructureBlockPacket(f.pos, action, StructureMode.SAVE, "minecraft:example", BlockPos.ZERO,
                    Vec3i.ZERO, Mirror.NONE, Rotation.NONE, "", true, false, false, true, 1, 0));
        }
        verify(entity).saveStructure();
        verify(entity).placeStructureIfSameSize(f.level);
        verify(entity).detectSize();
    }

    @Test
    void unavailableUntrackedNotLoadedAndOrdinaryWorldTargetsNeverReachEditingHandlers() throws ReflectiveOperationException {
        for (int unavailable = 0; unavailable < 4; unavailable++) {
            ServerFixture f = new ServerFixture(new BlockPos(1, 32000000, 2));
            f.sign();
            switch (unavailable) {
                case 0 -> f.available(null);
                case 1 -> when(((CCServerPlayer) f.player).cc_getCloTrackingView()).thenReturn(CloTrackingView.EMPTY);
                case 2 -> when(f.listener.hasClientLoaded()).thenReturn(false);
                default -> when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            }
            clearInvocations(f.cubes);
            f.signEdit(SignTextSlot.FRONT);
            f.commandEdit(TestCubeEditPackets.command(f.pos, "say should not run"));
            f.structureEdit(TestCubeEditPackets.structure(f.pos, BlockPos.ZERO, Vec3i.ZERO, 1));
            verify(f.listener, never()).handleSetCommandBlock(any());
            verify(f.listener, never()).handleSetStructureBlock(any());
            verify(f.filter, never()).processMessageBundle(any());
            verify(f.cubes, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), eq(true));
            if (unavailable != 0) {
                verifyNoInteractions(f.cubes);
            }
        }
    }

    @Test
    void actualServerSendWrapsOpenSignAndPreservesDirectedCommandEditorTag() throws ReflectiveOperationException {
        for (int y : TestCubeEditPackets.HEIGHTS) {
            ServerFixture f = new ServerFixture(new BlockPos(1, y, 2));
            var open = new ClientboundOpenSignEditorPacket(f.pos, SignTextSlot.BACK);
            f.listener.send(open);
            var payload = assertInstanceOf(CCClientboundCubeOpenSignEditorPacket.class,
                    assertInstanceOf(ClientboundCustomPayloadPacket.class, f.sent.getLast()).payload());
            assertEquals(f.pos, roundTrip(CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC, payload).pos());
            assertEquals(SignTextSlot.BACK, payload.slot());
            CommandBlockEntity command = f.command();
            command.getCommandBlock().setCommand("say private editor data");
            doCallRealMethod().when(f.player).openCommandBlock(any());
            f.player.openCommandBlock(command);
            var data = assertInstanceOf(CCClientboundCubeBlockEntityDataPacket.class,
                    assertInstanceOf(ClientboundCustomPayloadPacket.class, f.sent.getLast()).payload());
            assertEquals(f.pos, roundTrip(CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC, data).pos());
            assertEquals("say private editor data", data.data().tag().getStringOr("Command", ""));
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            var vanilla = ClientboundBlockEntityDataPacket.create(command, BlockEntity::saveCustomOnly);
            f.listener.send(vanilla);
            assertSame(vanilla, f.sent.getLast());
            f.listener.send(open);
            assertSame(open, f.sent.getLast());
        }
    }

    private static String text(SignBlockEntity sign, SignTextSlot slot) {
        return sign.getText(slot).getMessages(false).getFirst().getString();
    }

    static void setField(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
            doAnswer(i -> sent.add(i.getArgument(0))).when(connection).send(any());
            doCallRealMethod().when(listener).send(any());
        }

        private CustomPacketPayload payload() {
            return assertInstanceOf(ServerboundCustomPayloadPacket.class, sent.getLast()).payload();
        }
    }

    private static final class ServerFixture {
        private final BlockPos pos;
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final MinecraftServer server = mock();
        private final ServerChunkCache cache = mock();
        private final CubeSource cubes = (CubeSource) cache;
        private final ServerPlayer player = mock();
        private final ServerGamePacketListenerImpl listener = mock();
        private final List<Packet<?>> sent = new ArrayList<>();
        private final ServerPlayNetworking.Context context = mock();
        private final LevelCube cube;
        private final TextFilter filter = mock();
        private final CompletableFuture<List<FilteredText>> filtered = new CompletableFuture<>();
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        private ServerFixture(BlockPos pos) throws ReflectiveOperationException {
            this.pos = pos;
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getServer()).thenReturn(server);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getLightEngine()).thenReturn(mock());
            when(player.level()).thenReturn(level);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.canUseGameMasterBlocks()).thenReturn(true);
            when(player.getTextFilter()).thenReturn(filter);
            when(filter.processMessageBundle(any())).thenReturn(filtered);
            doAnswer(i -> tasks.add(i.getArgument(0))).when(server).execute(any(Runnable.class));
            player.connection = listener;
            listener.player = player;
            when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(CubePos.from(pos)), 5));
            cube = new LevelCube(level, CubePos.from(pos));
            available(cube);
            when(level.getBlockState(any())).thenAnswer(i -> cube.getBlockState(i.getArgument(0)));
            when(level.getBlockEntity(any(BlockPos.class))).thenAnswer(i -> cube.getBlockEntity(i.getArgument(0)));
            when(level.setBlock(any(), any(), anyInt())).thenAnswer(i -> cube.setBlockState(i.getArgument(0), i.getArgument(1)) != null);
            Connection connection = mock();
            setField(ServerCommonPacketListenerImpl.class, listener, "server", server);
            setField(ServerCommonPacketListenerImpl.class, listener, "connection", connection);
            when(listener.hasClientLoaded()).thenReturn(true);
            when(listener.isAcceptingMessages()).thenReturn(true);
            doCallRealMethod().when(listener).handleSetCommandBlock(any());
            doCallRealMethod().when(listener).handleSetStructureBlock(any());
            doCallRealMethod().when(listener).send(any());
            doCallRealMethod().when(listener).send(any(), any());
            doAnswer(i -> sent.add(i.getArgument(0))).when(connection).send(any(), any(), anyBoolean());
            when(context.player()).thenReturn(player);
        }

        private void available(LevelCube value) {
            CubePos p = CubePos.from(pos);
            when(cubes.cc_getCube(p.getX(), p.getY(), p.getZ(), ChunkStatus.FULL, false)).thenReturn(value);
        }

        private SignBlockEntity sign() {
            cube.setBlockState(pos, Blocks.OAK_SIGN.defaultBlockState());
            SignBlockEntity sign = (SignBlockEntity) cube.getBlockEntity(pos);
            sign.setAllowedPlayerEditor(player.getUUID());
            return sign;
        }

        private CommandBlockEntity command() {
            cube.setBlockState(pos, Blocks.COMMAND_BLOCK.defaultBlockState());
            return (CommandBlockEntity) cube.getBlockEntity(pos);
        }

        private StructureBlockEntity structure() {
            cube.setBlockState(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
            return (StructureBlockEntity) cube.getBlockEntity(pos);
        }

        private void signEdit(SignTextSlot slot) {
            var packet = new CCServerboundCubeSignUpdatePacket(pos, List.of("§ahello", "second", "", "fourth"), slot);
            new CCServerboundCubeSignUpdatePacket.Handler().receive(roundTrip(CCServerboundCubeSignUpdatePacket.STREAM_CODEC, packet),
                    context.player());
        }

        private void finishSign() {
            filtered.complete(List.of(FilteredText.passThrough("hello"), FilteredText.EMPTY, FilteredText.EMPTY, FilteredText.EMPTY));
            drain();
        }

        private void drain() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }

        private void commandEdit(CCServerboundCubeSetCommandBlockPacket packet) {
            new CCServerboundCubeSetCommandBlockPacket.Handler().receive(roundTrip(CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC, packet),
                    context.player());
        }

        private void structureEdit(CCServerboundCubeSetStructureBlockPacket packet) {
            new CCServerboundCubeSetStructureBlockPacket.Handler().receive(roundTrip(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC, packet),
                    context.player());
        }
    }
}
