package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubeWithLightPacket;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntitySnapshot;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntitySnapshot.Entry;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightPacketSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class TestClientCubeEntitySnapshots extends BaseTest {
    private static final CubePos POSITION = CubePos.of(-3, 1000000, 7);

    @Test
    void fullWirePacketCreatesEntitiesInEverySectionAtPositiveAndNegativeRangeEndpoints() {
        for (int y : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, -1000000, 1000000, CubePos.MAX_COORDINATE_VALUE }) {
            CubePos pos = CubePos.of(-3, y, 7);
            LevelCube source = source(pos);
            for (int section = 0; section < CubicConstants.SECTION_COUNT; section++) {
                for (int local : new int[] { 0, 4095 }) {
                    BlockPos block = Coords.sectionPosByIndex(pos, section).relativeToBlockPos((short) local);
                    sign(source, block, "section " + section + " local " + local);
                }
            }
            Fixture fixture = new Fixture(pos);
            LevelCube installed = fixture.receive(new CCClientboundLevelCubeWithLightPacket(source));
            assertNotNull(installed);
            assertEquals(16, installed.getBlockEntities().size());
            for (BlockEntity entity : source.getBlockEntities().values()) {
                SignBlockEntity received = (SignBlockEntity) installed.getBlockEntities().get(entity.getBlockPos());
                assertNotNull(received, "Snapshot must create the entity without a lazy lookup");
                assertEquals(text((SignBlockEntity) entity), text(received));
                assertEquals(entity.getBlockPos(), received.getBlockPos());
                assertSame(fixture.level, received.getLevel());
            }
        }
    }

    @Test
    void snapshotIsDetachedAndIncludesEmptyTagEntitiesWithoutSendingChestInventory() {
        LevelCube source = source(POSITION);
        BlockPos signPos = POSITION.asBlockPos(1, 2, 3);
        BlockPos chestPos = POSITION.asBlockPos(20, 21, 22);
        SignBlockEntity original = sign(source, signPos, "captured");
        ChestBlockEntity chest = chest(source, chestPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 32));
        assertNull(chest.getUpdatePacket());
        var packet = new CCClientboundLevelCubeWithLightPacket(source);
        assertEquals(2, packet.cubeData().getBlockEntities().entries().size());
        setText(original, "changed after capture");
        for (Entry entry : packet.cubeData().getBlockEntities().entries()) {
            entry.data().tag().putString("mutation", "ignored");
            if (entry.blockPos(POSITION).equals(chestPos)) {
                assertTrue(entry.data().tag().isEmpty());
            }
        }
        FriendlyByteBuf sections = packet.cubeData().getReadBuffer();
        try {
            assertThrows(java.nio.ReadOnlyBufferException.class, () -> sections.setByte(0, 0));
        } finally {
            sections.release();
        }
        var decoded = roundTrip(packet);
        assertEquals(packet.cubeData(), decoded.cubeData());
        assertEquals(packet.cubeData().hashCode(), decoded.cubeData().hashCode());
        Fixture fixture = new Fixture(POSITION);
        LevelCube installed = fixture.receive(decoded);
        assertEquals("captured", text((SignBlockEntity) installed.getBlockEntities().get(signPos)));
        ChestBlockEntity clientChest = (ChestBlockEntity) installed.getBlockEntities().get(chestPos);
        assertNotNull(clientChest);
        assertTrue(clientChest.getItem(0).isEmpty());
        assertEquals(32, chest.getItem(0).getCount());
    }

    @Test
    void capturePromotesPendingDiskEntityTagsAndCreatesMissingRuntimeInstances() {
        LevelCube source = source(POSITION);
        BlockPos pos = POSITION.asBlockPos(31, 31, 31);
        SignBlockEntity original = sign(source, pos, "from disk");
        CompoundTag saved = original.saveWithFullMetadata(source.getLevel().registryAccess());
        source.getBlockEntities().remove(pos);
        source.setBlockEntityNbt(saved);
        assertTrue(source.getBlockEntities().isEmpty());
        var packet = new CCClientboundLevelCubeWithLightPacket(source);
        assertNotNull(source.getBlockEntities().get(pos));
        assertNull(source.getBlockEntityNbt(pos));
        Fixture fixture = new Fixture(POSITION);
        assertEquals("from disk", text((SignBlockEntity) fixture.receive(packet).getBlockEntities().get(pos)));
        source.getBlockEntities().remove(pos);
        var recreated = new CCClientboundLevelCubeWithLightPacket(source);
        assertEquals(1, recreated.cubeData().getBlockEntities().entries().size());
        assertNotNull(source.getBlockEntities().get(pos));
    }

    @Test
    void repeatedFullSnapshotsReplaceTypeAndDataAndRemoveStaleObjectsPendingTagsAndTickers() {
        BlockPos first = POSITION.asBlockPos(1, 2, 3);
        BlockPos second = POSITION.asBlockPos(20, 21, 22);
        LevelCube source = source(POSITION);
        sign(source, first, "old text");
        chest(source, second);
        Fixture fixture = new Fixture(POSITION);
        LevelCube installed = fixture.receive(new CCClientboundLevelCubeWithLightPacket(source));
        BlockEntity oldSign = installed.getBlockEntities().get(first);
        BlockEntity oldChest = installed.getBlockEntities().get(second);
        List<TickingBlockEntity> oldTickers = List.copyOf(fixture.tickers);
        assertFalse(oldTickers.isEmpty());
        installed.setBlockEntityNbt(oldSign.saveWithFullMetadata(fixture.level.registryAccess()));
        LevelCube replacement = source(POSITION);
        chest(replacement, first);
        assertSame(installed, fixture.receive(new CCClientboundLevelCubeWithLightPacket(replacement)));
        assertTrue(oldSign.isRemoved());
        assertTrue(oldChest.isRemoved());
        assertTrue(oldTickers.stream().allMatch(TickingBlockEntity::isRemoved));
        assertEquals(1, installed.getBlockEntitiesPos().size());
        assertEquals(BlockEntityTypes.CHEST, installed.getBlockEntities().get(first).getType());
        assertNull(installed.getBlockEntityNbt(first));
        BlockEntity current = installed.getBlockEntities().get(first);
        assertSame(installed, fixture.receive(new CCClientboundLevelCubeWithLightPacket(replacement)));
        assertTrue(current.isRemoved());
        assertNotSame(current, installed.getBlockEntities().get(first));
        fixture.receive(new CCClientboundLevelCubeWithLightPacket(source(POSITION)));
        assertTrue(installed.getBlockEntities().isEmpty());
        assertTrue(installed.getBlockEntitiesPos().isEmpty());
    }

    @Test
    void unloadRingEvictionAndRadiusLossRemoveEntityObjectsAndStopTheirTickers() {
        for (int mode = 0; mode < 3; mode++) {
            LevelCube source = source(POSITION);
            BlockPos pos = POSITION.asBlockPos(1, 2, 3);
            chest(source, pos);
            Fixture fixture = new Fixture(POSITION);
            LevelCube installed = fixture.receive(new CCClientboundLevelCubeWithLightPacket(source));
            BlockEntity entity = installed.getBlockEntities().get(pos);
            assertFalse(fixture.tickers.isEmpty());
            if (mode == 0) {
                fixture.cache.cc_drop(POSITION);
            } else if (mode == 1) {
                CubePos other = CubePos.of(POSITION.getX() + 17, POSITION.getY(), POSITION.getZ());
                fixture.center(other);
                fixture.receive(new CCClientboundLevelCubeWithLightPacket(source(other)));
            } else {
                fixture.center(CubePos.of(POSITION.getX() + 8, POSITION.getY(), POSITION.getZ()));
                fixture.cache.cc_updateViewRadius(1);
            }
            assertTrue(entity.isRemoved());
            assertTrue(installed.getBlockEntities().isEmpty());
            assertTrue(fixture.tickers.stream().allMatch(TickingBlockEntity::isRemoved));
        }
    }

    @Test
    void mismatchedEntityAfterAValidEntryDoesNotReplaceExistingBlocksOrEntities() {
        BlockPos first = POSITION.asBlockPos(1, 2, 3);
        BlockPos second = POSITION.asBlockPos(4, 5, 6);
        LevelCube source = source(POSITION);
        sign(source, first, "original");
        Fixture fixture = new Fixture(POSITION);
        LevelCube installed = fixture.receive(new CCClientboundLevelCubeWithLightPacket(source));
        BlockEntity old = installed.getBlockEntities().get(first);
        setText((SignBlockEntity) source.getBlockEntities().get(first), "new data");
        source.setBlockState(second, Blocks.STONE.defaultBlockState());
        var good = new CCClientboundLevelCubePacketData(source);
        var entries = new ArrayList<>(good.getBlockEntities().entries());
        entries.add(new Entry(local(second), CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, new CompoundTag())));
        var bad = data(good, sectionBytes(good), new CubeBlockEntitySnapshot(entries));
        assertThrows(IllegalArgumentException.class, () -> fixture.receive(new CCClientboundLevelCubeWithLightPacket(POSITION, bad, 0, null)));
        assertSame(old, installed.getBlockEntities().get(first));
        assertFalse(old.isRemoved());
        assertEquals("original", text((SignBlockEntity) old));
        assertTrue(installed.getBlockState(second).isAir());
    }

    @Test
    void truncatedAndTrailingSectionDataLeaveThePreviousSnapshotIntact() {
        BlockPos pos = POSITION.asBlockPos(1, 2, 3);
        LevelCube source = source(POSITION);
        sign(source, pos, "original");
        Fixture fixture = new Fixture(POSITION);
        LevelCube installed = fixture.receive(new CCClientboundLevelCubeWithLightPacket(source));
        BlockEntity old = installed.getBlockEntities().get(pos);
        var good = new CCClientboundLevelCubePacketData(source);
        byte[] sections = sectionBytes(good);
        for (byte[] badBytes : List.of(new byte[0], Arrays.copyOf(sections, sections.length - 1), Arrays.copyOf(sections, sections.length + 1))) {
            var bad = data(good, badBytes, good.getBlockEntities());
            assertThrows(RuntimeException.class, () -> fixture.receive(new CCClientboundLevelCubeWithLightPacket(POSITION, bad, 0, null)));
            assertSame(old, installed.getBlockEntities().get(pos));
            assertFalse(old.isRemoved());
            assertEquals(Blocks.OAK_SIGN.defaultBlockState(), installed.getBlockState(pos));
        }
    }

    @Test
    void fullEntityInstallationPrecedesLightAndEarlierQueuedLightCannotUndoReplacement() {
        BlockPos pos = POSITION.asBlockPos(1, 2, 3);
        LevelCube source = source(POSITION);
        SignBlockEntity sign = sign(source, pos, "first");
        CubeLightView light = CubeLightView.fromExact(POSITION, new byte[8][DataLayer.SIZE], new byte[8][DataLayer.SIZE]);
        Fixture fixture = new Fixture(POSITION);
        LevelCube installed = fixture.receive(new CCClientboundLevelCubeWithLightPacket(source, new CubeLightPacketSnapshot(1, light)));
        assertEquals("first", text((SignBlockEntity) installed.getBlockEntities().get(pos)));
        assertFalse(fixture.cache.cc_isCubeLightReady(POSITION));
        setText(sign, "second");
        assertSame(installed, fixture.receive(new CCClientboundLevelCubeWithLightPacket(source, new CubeLightPacketSnapshot(2, light))));
        fixture.lightQueue.remove().run();
        assertFalse(fixture.cache.cc_isCubeLightReady(POSITION));
        fixture.lightQueue.remove().run();
        assertTrue(fixture.cache.cc_isCubeLightReady(POSITION));
        assertEquals("second", text((SignBlockEntity) installed.getBlockEntities().get(pos)));
    }

    @Test
    void packetsForOrdinaryPreviousOrOutOfViewWorldsDoNotInstallEntitiesOrQueueLight() {
        LevelCube source = source(POSITION);
        sign(source, POSITION.asBlockPos(), "ignored");
        var packet = new CCClientboundLevelCubeWithLightPacket(source);
        Fixture fixture = new Fixture(POSITION);
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(false);
        assertNull(fixture.receive(packet));
        when(((CanBeCubic) fixture.level).cc_isCubic()).thenReturn(true);
        fixture.client.level = mock();
        assertNull(fixture.receive(packet));
        fixture.client.level = fixture.level;
        fixture.center(CubePos.ZERO);
        assertNull(fixture.receive(packet));
        assertEquals(0, fixture.cache.cc_getLoadedCubeCount());
        assertTrue(fixture.lightQueue.isEmpty());
    }

    @Test
    void oversizedInitialTagsFailCaptureInsteadOfSilentlyOmittingEntities() {
        LevelCube source = source(POSITION);
        CompoundTag huge = new CompoundTag();
        huge.putByteArray("visible data", new byte[60000]);
        for (int x = 0; x < 9; x++) {
            BlockPos pos = POSITION.asBlockPos(x, 1, 1);
            SignBlockEntity entity = spy(sign(source, pos, "value"));
            doReturn(huge).when(entity).getUpdateTag(any());
            source.getBlockEntities().put(pos, entity);
        }
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubeWithLightPacket(source));
        assertEquals(9, source.getBlockEntities().size());
    }

    private static int local(BlockPos pos) {
        return (Coords.blockToIndex(pos) << 12) | SectionPos.sectionRelativePos(pos);
    }

    private static LevelCube source(CubePos pos) {
        return new LevelCube(mockLevelWithPalettes(Level.class), pos);
    }

    private static SignBlockEntity sign(LevelCube cube, BlockPos pos, String text) {
        cube.setBlockState(pos, Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity sign = (SignBlockEntity) cube.getBlockEntity(pos);
        setText(sign, text);
        return sign;
    }

    private static ChestBlockEntity chest(LevelCube cube, BlockPos pos) {
        cube.setBlockState(pos, Blocks.CHEST.defaultBlockState());
        return (ChestBlockEntity) cube.getBlockEntity(pos);
    }

    private static void setText(SignBlockEntity sign, String text) {
        List<Component> lines = List.of(Component.literal(text), Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(lines, lines, DyeColor.BLUE, true), SignTextSlot.FRONT);
    }

    private static String text(SignBlockEntity sign) {
        return sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString();
    }

    private static byte[] sectionBytes(CCClientboundLevelCubePacketData data) {
        FriendlyByteBuf buffer = data.getReadBuffer();
        try {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static CCClientboundLevelCubePacketData data(
            CCClientboundLevelCubePacketData template, byte[] sections, CubeBlockEntitySnapshot entities
    ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(2).writeVarInt(sections.length).writeBytes(sections);
            for (Heightmap.Types type : Heightmap.Types.values()) {
                long[] map = template.getHeightmaps().get(type);
                buffer.writeVarInt(map.length);
                for (long value : map) {
                    buffer.writeLong(value);
                }
            }
            entities.write(buffer);
            return new CCClientboundLevelCubePacketData(buffer);
        } finally {
            buffer.release();
        }
    }

    private static CCClientboundLevelCubeWithLightPacket roundTrip(CCClientboundLevelCubeWithLightPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            assertTrue(buffer.readableBytes() < 1048576);
            var decoded = CCClientboundLevelCubeWithLightPacket.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static final class Fixture {
        private final ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft client = mock();
        private final ClientCubeCache cache;
        private final io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext context = mock();
        private final Queue<Runnable> lightQueue = new ArrayDeque<>();
        private final List<TickingBlockEntity> tickers = new ArrayList<>();

        private Fixture(CubePos pos) {
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.isClientSide()).thenReturn(true);
            Misc.setupLevelHeight(level, -64, 384);
            ClientChunkCache vanilla = new ClientChunkCache(level, 5);
            cache = (ClientCubeCache) vanilla;
            when(level.getChunkSource()).thenReturn(vanilla);
            client.level = level;
            LocalPlayer player = mock();
            when(player.level()).thenReturn(level);
            when(context.client()).thenReturn(client);
            when(context.player()).thenReturn(player);
            doAnswer(invocation -> lightQueue.add(invocation.getArgument(0))).when(level).queueLightUpdate(any());
            doAnswer(invocation -> tickers.add(invocation.getArgument(0))).when(level).addBlockEntityTicker(any());
            center(pos);
        }

        private void center(CubePos pos) {
            cache.cc_updateViewCenter(pos.getX(), pos.getY(), pos.getZ());
        }

        private LevelCube receive(CCClientboundLevelCubeWithLightPacket packet) {
            new CCClientboundLevelCubeWithLightPacket.Handler().receive(roundTrip(packet), context);
            return cache.cc_getCube(packet.pos().getX(), packet.pos().getY(), packet.pos().getZ(), false);
        }
    }
}
