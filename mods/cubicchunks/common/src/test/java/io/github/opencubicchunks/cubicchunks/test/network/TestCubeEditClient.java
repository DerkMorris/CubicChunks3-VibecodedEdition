package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.test.network.TestCubeEditRouting.setField;
import static io.github.opencubicchunks.cubicchunks.test.network.TestCubePlayerActionPackets.roundTrip;
import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEntityDataPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeOpenSignEditorPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.junit.jupiter.api.Test;

class TestCubeEditClient extends BaseTest {
    @Test
    void opensCorrectSideOfAlreadyLoadedSignAtEveryExtremeHeight() throws ReflectiveOperationException {
        for (int y : TestCubeEditPackets.HEIGHTS) {
            Fixture f = new Fixture(new BlockPos(-1, y, 2), true);
            SignBlockEntity sign = (SignBlockEntity) f.cube.getBlockEntities().get(f.pos);
            assertNotNull(sign);
            for (SignTextSlot slot : SignTextSlot.values()) {
                f.open(f.pos, slot);
                verify(f.player).openTextEdit(sign, slot);
            }
        }
    }

    @Test
    void directedCommandDataLoadsActualEntityAndRefreshesItsOpenEditor() throws ReflectiveOperationException {
        for (int y : TestCubeEditPackets.HEIGHTS) {
            Fixture f = new Fixture(new BlockPos(1, y, 2), false);
            CommandBlockEntity serverEntity = (CommandBlockEntity) f.source.getBlockEntity(f.pos);
            serverEntity.getCommandBlock().setCommand("say Привет");
            CommandBlockEditScreen screen = mock();
            when(f.gui.screen()).thenReturn(screen);
            var packet = CCClientboundCubeBlockEntityDataPacket
                    .from(ClientboundBlockEntityDataPacket.create(serverEntity, BlockEntity::saveCustomOnly));
            f.update(packet);
            CommandBlockEntity clientEntity = (CommandBlockEntity) f.cube.getBlockEntities().get(f.pos);
            assertEquals("say Привет", clientEntity.getCommandBlock().getCommand());
            verify(screen).updateGui();
            verify(f.level).sendBlockUpdated(f.pos, clientEntity.getBlockState(), clientEntity.getBlockState(), 3);
        }
    }

    @Test
    void directedSignDataKeepsBothTextSidesAndNotifiesRenderingWithoutReplacingBlocks() throws ReflectiveOperationException {
        Fixture f = new Fixture(new BlockPos(1, -32000000, 2), true);
        SignBlockEntity sign = (SignBlockEntity) f.source.getBlockEntity(f.pos);
        sign.setText(
                new SignText(List.of(Component.literal("back"), Component.empty(), Component.empty(), Component.empty()),
                        List.of(Component.literal("filtered"), Component.empty(), Component.empty(), Component.empty()), DyeColor.BLUE, true),
                SignTextSlot.BACK);
        var packet = CCClientboundCubeBlockEntityDataPacket.from(sign.getUpdatePacket());
        clearInvocations(f.level);
        f.update(packet);
        SignBlockEntity received = (SignBlockEntity) f.cube.getBlockEntities().get(f.pos);
        assertEquals("back", received.getText(SignTextSlot.BACK).getMessages(false).getFirst().getString());
        assertEquals("filtered", received.getText(SignTextSlot.BACK).getMessages(true).getFirst().getString());
        assertEquals(DyeColor.BLUE, received.getText(SignTextSlot.BACK).getColor());
        verify(f.level).sendBlockUpdated(f.pos, received.getBlockState(), received.getBlockState(), 3);
        verify(f.level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void missingWrongTypeDroppedOrdinaryAndPreviousWorldTargetsAreIgnored() throws ReflectiveOperationException {
        for (int missing = 0; missing < 5; missing++) {
            Fixture f = new Fixture(new BlockPos(1, 32000000, 2), true);
            SignBlockEntity sign = (SignBlockEntity) f.source.getBlockEntity(f.pos);
            var packet = CCClientboundCubeBlockEntityDataPacket.from(sign.getUpdatePacket());
            switch (missing) {
                case 0 -> f.cube.getBlockEntities().clear();
                case 1 -> f.cube.setBlockState(f.pos, Blocks.COMMAND_BLOCK.defaultBlockState());
                case 2 -> f.cache.cc_drop(CubePos.from(f.pos));
                case 3 -> when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
                default -> f.client.level = mock();
            }
            clearInvocations(f.level, f.player);
            f.open(f.pos, SignTextSlot.FRONT);
            f.update(packet);
            verify(f.player, never()).openTextEdit(any(), any());
            verify(f.level, never()).sendBlockUpdated(any(), any(), any(), anyInt());
        }
    }

    private static final class Fixture {
        private final BlockPos pos;
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft client = mock();
        private final Gui gui = mock();
        private final LocalPlayer player = mock();
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock();
        private final ClientCubeCache cache;
        private final LevelCube source;
        private final LevelCube cube;

        private Fixture(BlockPos pos, boolean sign) throws ReflectiveOperationException {
            this.pos = pos;
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.isClientSide()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            ClientChunkCache vanilla = new ClientChunkCache(level, 5);
            cache = (ClientCubeCache) vanilla;
            when(level.getChunkSource()).thenReturn(vanilla);
            client.level = level;
            setField(Minecraft.class, client, "gui", gui);
            when(player.level()).thenReturn(level);
            when(context.client()).thenReturn(client);
            when(context.player()).thenReturn(player);
            CubePos p = CubePos.from(pos);
            cache.cc_updateViewCenter(p.getX(), p.getY(), p.getZ());
            source = new LevelCube(level, p);
            source.setBlockState(pos, sign ? Blocks.OAK_SIGN.defaultBlockState() : Blocks.COMMAND_BLOCK.defaultBlockState());
            var packet = roundTrip(CCClientboundLevelCubeWithLightPacket.STREAM_CODEC, new CCClientboundLevelCubeWithLightPacket(source));
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(packet, context);
            cube = cache.cc_getCube(p.getX(), p.getY(), p.getZ(), false);
        }

        private void open(BlockPos at, SignTextSlot slot) {
            var packet = new CCClientboundCubeOpenSignEditorPacket(at, slot);
            new CCClientboundCubeOpenSignEditorPacket.Handler().receive(roundTrip(CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC, packet),
                    context);
        }

        private void update(CCClientboundCubeBlockEntityDataPacket packet) {
            new CCClientboundCubeBlockEntityDataPacket.Handler().receive(roundTrip(CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC, packet),
                    context);
        }
    }
}
