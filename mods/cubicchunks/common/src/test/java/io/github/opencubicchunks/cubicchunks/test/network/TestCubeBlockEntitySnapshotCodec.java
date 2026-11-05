package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundLevelCubePacketData;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntitySnapshot;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntitySnapshot.Entry;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.junit.jupiter.api.Test;

class TestCubeBlockEntitySnapshotCodec extends BaseTest {
    @Test
    void emptyAndMaximumCountRoundTripAndSnapshotsAreImmutableValues() {
        var empty = new CubeBlockEntitySnapshot(List.of());
        assertEquals(empty, roundTrip(empty));
        var tag = new CompoundTag();
        tag.putString("value", "captured");
        var data = CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, tag);
        var emptyData = CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, new CompoundTag());
        List<Entry> entries = new ArrayList<>();
        for (int pos = 0; pos < CubeBlockEntitySnapshot.MAX_ENTRIES; pos++) {
            entries.add(new Entry(pos, emptyData));
        }
        var snapshot = new CubeBlockEntitySnapshot(entries);
        entries.clear();
        tag.putString("value", "changed");
        data.tag().putString("value", "also changed");
        assertEquals(CubeBlockEntitySnapshot.MAX_ENTRIES, snapshot.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        var decoded = roundTrip(snapshot);
        assertEquals(snapshot, decoded);
        assertEquals(snapshot.hashCode(), decoded.hashCode());
        var detached = roundTrip(new CubeBlockEntitySnapshot(List.of(new Entry(0, data))));
        assertEquals("captured", detached.entries().getFirst().data().tag().getStringOr("value", ""));
    }

    @Test
    void localPositionsCoverAllSectionsAtBothCubeCoordinateLimits() {
        var data = CubeBlockEntityUpdate.capture(BlockEntityTypes.SIGN, new CompoundTag());
        for (int y : new int[] { -CubePos.MAX_COORDINATE_VALUE - 1, -1000000, 1000000, CubePos.MAX_COORDINATE_VALUE }) {
            CubePos cube = CubePos.of(-17, y, 31);
            for (int section = 0; section < 8; section++) {
                for (int local : new int[] { 0, 4095 }) {
                    Entry entry = roundTrip(new CubeBlockEntitySnapshot(List.of(new Entry((section << 12) | local, data)))).entries().getFirst();
                    assertEquals(Coords.sectionPosByIndex(cube, section).relativeToBlockPos((short) local), entry.blockPos(cube));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Entry(-1, data));
        assertThrows(IllegalArgumentException.class, () -> new Entry(CubeBlockEntitySnapshot.MAX_ENTRIES, data));
        assertThrows(IllegalArgumentException.class, () -> new CubeBlockEntitySnapshot(List.of(new Entry(0, data), new Entry(0, data))));
    }

    @Test
    void decoderRejectsBadCountsPositionsDuplicatesTypesAndNbt() {
        malformed(buffer -> buffer.writeVarInt(-1));
        malformed(buffer -> buffer.writeVarInt(CubeBlockEntitySnapshot.MAX_ENTRIES + 1));
        byte[] entry = encodedEntry(CubeBlockEntityUpdate.capture(BlockEntityTypes.CHEST, new CompoundTag()));
        malformed(buffer -> {
            buffer.writeVarInt(1).writeBytes(entry);
            buffer.setShort(1, CubeBlockEntitySnapshot.MAX_ENTRIES);
        });
        malformed(buffer -> buffer.writeVarInt(2).writeBytes(entry).writeBytes(entry));
        malformed(buffer -> buffer.writeVarInt(1).writeShort(0).writeVarInt(Integer.MAX_VALUE));
        int type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(BlockEntityTypes.CHEST);
        malformed(buffer -> buffer.writeVarInt(1).writeShort(0).writeVarInt(type).writeVarInt(CubeBlockEntityUpdate.MAX_BYTES + 1));
        malformed(buffer -> buffer.writeVarInt(1).writeShort(0).writeVarInt(type).writeVarInt(1).writeByte(0));
        malformed(buffer -> {
            buffer.writeVarInt(1).writeShort(0).writeVarInt(type);
            buffer.writeByteArray(new byte[] { 10, 0, 0, 0, 99 });
        });
    }

    @Test
    void aggregateBudgetCountsTagFramingAndRejectsOversizedSnapshotsOnBothSides() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("large", new byte[60000]);
        var data = CubeBlockEntityUpdate.capture(BlockEntityTypes.SIGN, tag);
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            entries.add(new Entry(index, data));
        }
        assertEquals(new CubeBlockEntitySnapshot(entries), roundTrip(new CubeBlockEntitySnapshot(entries)));
        entries.add(new Entry(8, data));
        assertThrows(IllegalArgumentException.class, () -> new CubeBlockEntitySnapshot(entries));
        byte[] entry = encodedEntry(data);
        malformed(buffer -> {
            buffer.writeVarInt(9);
            for (int index = 0; index < 9; index++) {
                int start = buffer.writerIndex();
                buffer.writeBytes(entry);
                buffer.setShort(start, index);
            }
        });
    }

    @Test
    void everyTruncatedSnapshotPrefixIsRejected() {
        CompoundTag tag = new CompoundTag();
        tag.putString("text", "detached snapshot");
        var snapshot = new CubeBlockEntitySnapshot(List.of(new Entry(32767, CubeBlockEntityUpdate.capture(BlockEntityTypes.SIGN, tag))));
        FriendlyByteBuf buffer = buffer();
        try {
            snapshot.write(buffer);
            for (int length = 0; length < buffer.writerIndex(); length++) {
                assertTruncated(buffer, length);
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    void sectionDataRejectsOldFormatAndOversizedAllocationBeforeReading() {
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(1);
            assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubePacketData(buffer));
            buffer.clear();
            buffer.writeVarInt(2).writeVarInt(CCClientboundLevelCubePacketData.MAX_SECTION_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> new CCClientboundLevelCubePacketData(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void assertTruncated(FriendlyByteBuf buffer, int length) {
        FriendlyByteBuf truncated = new FriendlyByteBuf(buffer.retainedSlice(0, length));
        try {
            assertThrows(RuntimeException.class, () -> CubeBlockEntitySnapshot.read(truncated));
        } finally {
            truncated.release();
        }
    }

    private static byte[] encodedEntry(CubeBlockEntityUpdate data) {
        FriendlyByteBuf buffer = buffer();
        try {
            new CubeBlockEntitySnapshot(List.of(new Entry(0, data))).write(buffer);
            assertEquals(1, buffer.readVarInt());
            byte[] result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally {
            buffer.release();
        }
    }

    private static CubeBlockEntitySnapshot roundTrip(CubeBlockEntitySnapshot snapshot) {
        FriendlyByteBuf buffer = buffer();
        try {
            snapshot.write(buffer);
            assertTrue(buffer.readableBytes() <= CubeBlockEntitySnapshot.MAX_BYTES);
            var decoded = CubeBlockEntitySnapshot.read(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void malformed(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = buffer();
        try {
            writer.accept(buffer);
            assertThrows(RuntimeException.class, () -> CubeBlockEntitySnapshot.read(buffer));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
