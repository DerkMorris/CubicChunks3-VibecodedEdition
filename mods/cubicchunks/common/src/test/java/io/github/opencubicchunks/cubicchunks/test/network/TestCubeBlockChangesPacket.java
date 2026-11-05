package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket.Change;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.junit.jupiter.api.Test;

class TestCubeBlockChangesPacket extends BaseTest {
    private static final CubePos POS = CubePos.of(-17, 1000000, 31);

    @Test
    void allSectionCornersRoundTripAtBothExtremeHeightsWithoutPositionAliases() {
        for (int y : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, -65, 64, CubePos.MAX_COORDINATE_VALUE }) {
            CubePos cube = CubePos.of(-17, y, 31);
            for (int section = 0; section < 8; section++) {
                var packet = new CCClientboundCubeBlockChangesPacket(cube, section,
                        List.of(new Change(0, Blocks.STONE.defaultBlockState(), null), new Change(4095, Blocks.GLOWSTONE.defaultBlockState(), null)));
                var copy = roundTrip(packet);
                assertEquals(packet, copy);
                BlockPos expected = Coords.sectionPosByIndex(cube, section).origin().offset(15, 15, 15);
                assertEquals(expected, copy.blockPos(copy.changes().getLast()));
                assertNotEquals(expected, BlockPos.of(expected.asLong()));
            }
        }
    }

    @Test
    void fullSectionRemainsBoundedAndDetachedFromTheCallerList() {
        List<Change> changes = new ArrayList<>();
        for (int local = 0; local < 4096; local++) {
            changes.add(new Change(local, Blocks.STONE.defaultBlockState(), null));
        }
        var packet = new CCClientboundCubeBlockChangesPacket(POS, 7, changes);
        changes.clear();
        assertEquals(4096, roundTrip(packet).changes().size());
        assertThrows(UnsupportedOperationException.class, () -> packet.changes().clear());
    }

    @Test
    void blockEntityTagsAreDetachedAndRetainRegistryType() {
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "before");
        var data = CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, tag);
        tag.putString("marker", "after");
        data.tag().putString("marker", "also after");
        var packet = new CCClientboundCubeBlockChangesPacket(POS, 0, List.of(new Change(1, Blocks.CHEST.defaultBlockState(), data)));
        var copy = roundTrip(packet).changes().getFirst().blockEntity();
        assertEquals(BlockEntityTypes.CHEST, copy.type());
        assertEquals("before", copy.tag().getStringOr("marker", ""));
    }

    @Test
    void rejectsInvalidConstructorPositionsCountsDuplicateEntriesAndMismatchedEntities() {
        var change = new Change(0, Blocks.STONE.defaultBlockState(), null);
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeBlockChangesPacket(CubePos.of(0, 1 << 21, 0), 0, List.of(change)));
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeBlockChangesPacket(POS, 8, List.of(change)));
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeBlockChangesPacket(POS, -1, List.of(change)));
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeBlockChangesPacket(POS, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeBlockChangesPacket(POS, 0, List.of(change, change)));
        assertThrows(IllegalArgumentException.class, () -> new Change(4096, Blocks.STONE.defaultBlockState(), null));
        var chest = CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, new CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> new Change(0, Blocks.STONE.defaultBlockState(), chest));
        assertThrows(IllegalArgumentException.class, () -> new Change(0, Blocks.OAK_SIGN.defaultBlockState(), chest));
    }

    @Test
    void decoderRejectsInvalidFramingAliasesSectionCountsStatesAndFlags() {
        malformed(buffer -> buffer.writeVarInt(2));
        malformed(buffer -> buffer.writeVarInt(1).writeLong(POS.asLong() ^ Long.MIN_VALUE));
        malformed(buffer -> header(buffer, 8, 1));
        malformed(buffer -> header(buffer, 0, 0));
        malformed(buffer -> header(buffer, 0, 4097));
        malformed(buffer -> {
            header(buffer, 0, 1);
            entry(buffer, 4096, 0, 0);
        });
        malformed(buffer -> {
            header(buffer, 0, 1);
            entry(buffer, 0, -1, 0);
        });
        malformed(buffer -> {
            header(buffer, 0, 1);
            entry(buffer, 0, Integer.MAX_VALUE, 0);
        });
        malformed(buffer -> {
            header(buffer, 0, 1);
            entry(buffer, 0, 0, 2);
        });
        malformed(buffer -> {
            header(buffer, 0, 2);
            entry(buffer, 0, 0, 0);
            entry(buffer, 0, 0, 0);
        });
    }

    @Test
    void decoderRejectsInvalidOrOversizedBlockEntityNbt() {
        malformed(buffer -> {
            entityHeader(buffer);
            buffer.writeVarInt(-1);
        });
        malformed(buffer -> {
            entityHeader(buffer);
            buffer.writeVarInt(Integer.MAX_VALUE);
        });
        malformed(buffer -> {
            entityHeader(buffer);
            buffer.writeVarInt(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(BlockEntityTypes.CHEST));
            buffer.writeVarInt(CubeBlockEntityUpdate.MAX_BYTES + 1);
        });
        malformed(buffer -> {
            entityHeader(buffer);
            buffer.writeVarInt(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(BlockEntityTypes.CHEST));
            buffer.writeByteArray(new byte[] { 0 });
        });
        malformed(buffer -> {
            entityHeader(buffer);
            buffer.writeVarInt(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(BlockEntityTypes.CHEST));
            buffer.writeByteArray(new byte[] { 10, 0, 0 });
        });
    }

    @Test
    void aggregateNbtBudgetRejectsOversizedSectionsBeforeEncoding() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("large", new byte[60000]);
        var data = CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, tag);
        List<Change> changes = new ArrayList<>();
        for (int local = 0; local < 5; local++) {
            changes.add(new Change(local, Blocks.CHEST.defaultBlockState(), data));
        }
        assertThrows(IllegalArgumentException.class, () -> new CCClientboundCubeBlockChangesPacket(POS, 0, changes));
        tag.putByteArray("large", new byte[CubeBlockEntityUpdate.MAX_BYTES]);
        assertThrows(RuntimeException.class, () -> CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, tag));
    }

    @Test
    void everyTruncatedPrefixIsRejected() {
        var packet = new CCClientboundCubeBlockChangesPacket(POS, 7, List.of(new Change(4095, Blocks.STONE.defaultBlockState(), null)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundCubeBlockChangesPacket.STREAM_CODEC.encode(buffer, packet);
            for (int length = 0; length < buffer.writerIndex(); length++) {
                assertTruncated(buffer, length);
            }
        } finally {
            buffer.release();
        }
    }

    private static void assertTruncated(FriendlyByteBuf buffer, int length) {
        FriendlyByteBuf truncated = new FriendlyByteBuf(buffer.retainedSlice(0, length));
        try {
            assertThrows(RuntimeException.class, () -> CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(truncated));
        } finally {
            truncated.release();
        }
    }

    private static void malformed(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            assertThrows(RuntimeException.class, () -> CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void header(FriendlyByteBuf buffer, int section, int count) {
        buffer.writeVarInt(1).writeLong(POS.asLong()).writeByte(section);
        buffer.writeVarInt(count);
    }

    private static void entry(FriendlyByteBuf buffer, int local, int state, int present) {
        buffer.writeShort(local).writeVarInt(state).writeByte(present);
    }

    private static void entityHeader(FriendlyByteBuf buffer) {
        header(buffer, 0, 1);
        entry(buffer, 0, Block.BLOCK_STATE_REGISTRY.getId(Blocks.CHEST.defaultBlockState()), 1);
    }

    public static CCClientboundCubeBlockChangesPacket roundTrip(CCClientboundCubeBlockChangesPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCClientboundCubeBlockChangesPacket.STREAM_CODEC.encode(buffer, packet);
            assertTrue(buffer.readableBytes() < 1048576);
            var decoded = CCClientboundCubeBlockChangesPacket.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
