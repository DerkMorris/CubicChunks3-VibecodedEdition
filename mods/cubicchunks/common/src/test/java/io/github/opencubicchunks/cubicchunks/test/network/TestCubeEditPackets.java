package io.github.opencubicchunks.cubicchunks.test.network;

import static io.github.opencubicchunks.cubicchunks.test.network.TestCubePlayerActionPackets.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockEntityDataPacket;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeOpenSignEditorPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetCommandBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSignUpdatePacket;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.network.CubeEditCodec;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import org.junit.jupiter.api.Test;

class TestCubeEditPackets extends BaseTest {
    static final int[] HEIGHTS = { CubicBlockBounds.MIN, -32000000, 32000000, CubicBlockBounds.MAX };

    @Test
    void signBothSidesAndUnicodeAndOpenEditorPreserveExtremeCoordinates() {
        for (int y : HEIGHTS) {
            BlockPos pos = new BlockPos(-17, y, 31);
            for (SignTextSlot slot : SignTextSlot.values()) {
                var sign = new CCServerboundCubeSignUpdatePacket(pos, List.of("Привет", "漢字", "", "🙂"), slot);
                assertEquals(sign, roundTrip(CCServerboundCubeSignUpdatePacket.STREAM_CODEC, sign));
                var open = new CCClientboundCubeOpenSignEditorPacket(pos, slot);
                assertEquals(open, roundTrip(CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC, open));
            }
            assertNotEquals(pos, BlockPos.of(pos.asLong()));
        }
    }

    @Test
    void commandModesAndEveryFlagCombinationRoundTripIntoVanillaAtExtremeHeights() {
        for (int y : HEIGHTS) {
            for (CommandBlockEntity.Mode mode : CommandBlockEntity.Mode.values()) {
                checkCommandFlags(new BlockPos(1, y, 2), mode);
            }
        }
    }

    private static void checkCommandFlags(BlockPos pos, CommandBlockEntity.Mode mode) {
        for (int flags = 0; flags < 8; flags++) {
            var packet = new CCServerboundCubeSetCommandBlockPacket(pos, "say Привет", mode, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
            var decoded = roundTrip(CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC, packet);
            assertEquals(packet, CCServerboundCubeSetCommandBlockPacket.from(decoded.vanilla()));
        }
    }

    @Test
    void structureSettingsActionsTransformsFlagsAndLimitsSurviveVanillaReconstruction() {
        for (int y : HEIGHTS) {
            for (int index = 0; index < 48; index++) {
                var packet = new CCServerboundCubeSetStructureBlockPacket(new BlockPos(-1, y, 2),
                        StructureBlockEntity.UpdateType.values()[index % StructureBlockEntity.UpdateType.values().length],
                        StructureMode.values()[index % StructureMode.values().length], "minecraft:example", new BlockPos(-48, 48, 0),
                        new Vec3i(48, 0, 48), Mirror.values()[index % Mirror.values().length], Rotation.values()[index % Rotation.values().length],
                        "метаданные", (index & 1) != 0, (index & 2) != 0, (index & 4) != 0, (index & 8) != 0, index % 2 == 0 ? 0 : 1,
                        index % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE);
                assertEquals(packet, CCServerboundCubeSetStructureBlockPacket
                        .from(roundTrip(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC, packet).vanilla()));
            }
        }
    }

    @Test
    void allPacketsFreezeMutableInputsAndDirectedEntityTag() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(-1, 32000000, 2);
        List<String> lines = new ArrayList<>(List.of("a", "b", "c", "d"));
        var sign = new CCServerboundCubeSignUpdatePacket(pos, lines, SignTextSlot.FRONT);
        var command = command(pos, "say a");
        var structure = structure(pos, pos.set(1, 2, 3), pos, 1);
        var open = new CCClientboundCubeOpenSignEditorPacket(pos, SignTextSlot.BACK);
        CompoundTag tag = new CompoundTag();
        tag.putString("Command", "say original");
        var entity = new CCClientboundCubeBlockEntityDataPacket(pos, CubeBlockEntityUpdate.capture(BlockEntityTypes.COMMAND_BLOCK, tag));
        pos.set(0, 0, 0);
        lines.set(0, "changed");
        tag.putString("Command", "changed");
        assertEquals(32000000, sign.pos().getY());
        assertEquals(sign.pos(), command.pos());
        assertEquals("a", sign.lines().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> sign.lines().clear());
        assertEquals(new Vec3i(1, 2, 3), structure.size());
        assertEquals(new BlockPos(1, 2, 3), structure.offset());
        assertEquals(structure.pos(), open.pos());
        var decoded = roundTrip(CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC, entity);
        assertEquals(entity, decoded);
        assertEquals("say original", decoded.data().tag().getStringOr("Command", ""));
        decoded.data().tag().putString("Command", "changed again");
        assertEquals(entity, decoded);
    }

    @Test
    void constructorsRejectBadTextSizesCoordinatesAndNonFiniteOrOutOfRangeStructureFields() {
        assertThrows(IllegalArgumentException.class, () -> new CCServerboundCubeSignUpdatePacket(BlockPos.ZERO, List.of("one"), SignTextSlot.FRONT));
        assertThrows(IllegalArgumentException.class,
                () -> new CCServerboundCubeSignUpdatePacket(BlockPos.ZERO, List.of("x".repeat(385), "", "", ""), SignTextSlot.FRONT));
        assertThrows(IllegalArgumentException.class, () -> command(new BlockPos(0, CubicBlockBounds.MAX + 1, 0), ""));
        assertThrows(IllegalArgumentException.class, () -> command(BlockPos.ZERO, "x".repeat(32768)));
        assertThrows(IllegalArgumentException.class, () -> structure(BlockPos.ZERO, new BlockPos(49, 0, 0), Vec3i.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> structure(BlockPos.ZERO, BlockPos.ZERO, new Vec3i(-1, 0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> structure(BlockPos.ZERO, BlockPos.ZERO, new Vec3i(0, 49, 0), 1));
        for (float invalid : new float[] { Float.NaN, Float.POSITIVE_INFINITY, -1, 2 }) {
            assertThrows(IllegalArgumentException.class, () -> structure(BlockPos.ZERO, BlockPos.ZERO, Vec3i.ZERO, invalid));
        }
    }

    @Test
    void textByteBudgetFitsActualServerboundPayloadLimitAndRejectsMultibyteOverflow() {
        var accepted = command(BlockPos.ZERO, "x".repeat(CubeEditCodec.MAX_TEXT_BYTES));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeIdentifier(CCServerboundCubeSetCommandBlockPacket.TYPE.id());
            CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC.encode(buffer, accepted);
            assertTrue(buffer.readableBytes() < 32767);
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> command(BlockPos.ZERO, "x".repeat(CubeEditCodec.MAX_TEXT_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> command(BlockPos.ZERO, "漢".repeat(11000)));
        assertEquals(accepted, roundTrip(CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC, accepted));
    }

    @Test
    void allCodecsRejectBadVersionCoordinatesAndEveryTruncatedPrefix() {
        headers(CCServerboundCubeSignUpdatePacket.STREAM_CODEC,
                new CCServerboundCubeSignUpdatePacket(BlockPos.ZERO, List.of("a", "b", "c", "d"), SignTextSlot.FRONT));
        headers(CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC, command(BlockPos.ZERO, "say test"));
        headers(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC, structure(BlockPos.ZERO, BlockPos.ZERO, Vec3i.ZERO, 1));
        headers(CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC, new CCClientboundCubeOpenSignEditorPacket(BlockPos.ZERO, SignTextSlot.FRONT));
        headers(CCClientboundCubeBlockEntityDataPacket.STREAM_CODEC,
                new CCClientboundCubeBlockEntityDataPacket(BlockPos.ZERO, CubeBlockEntityUpdate.capture(BlockEntityTypes.SIGN, new CompoundTag())));
    }

    @Test
    void decodersRejectUnknownSlotsModesAndFlagsAndMalformedStructureFields() {
        var sign = new CCServerboundCubeSignUpdatePacket(BlockPos.ZERO, List.of("", "", "", ""), SignTextSlot.FRONT);
        reject(CCServerboundCubeSignUpdatePacket.STREAM_CODEC, sign, b -> b.setByte(17, 127));
        reject(CCClientboundCubeOpenSignEditorPacket.STREAM_CODEC, new CCClientboundCubeOpenSignEditorPacket(BlockPos.ZERO, SignTextSlot.FRONT),
                b -> b.setByte(13, 127));
        var command = command(BlockPos.ZERO, "");
        for (int field = 14; field <= 17; field++) {
            int at = field;
            reject(CCServerboundCubeSetCommandBlockPacket.STREAM_CODEC, command, b -> b.setByte(at, 127));
        }
        var structure = structure(BlockPos.ZERO, BlockPos.ZERO, Vec3i.ZERO, 1);
        // Empty name/data: fields follow version+XYZ, two enums and the name length.
        for (int field : new int[] { 13, 14, 16, 19, 22, 23, 25, 26, 27, 28 }) {
            reject(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC, structure, b -> b.setByte(field, 127));
        }
        reject(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC, structure, b -> b.setFloat(29, Float.NaN));
    }

    static CCServerboundCubeSetCommandBlockPacket command(BlockPos pos, String text) {
        return new CCServerboundCubeSetCommandBlockPacket(pos, text, CommandBlockEntity.Mode.REDSTONE, true, false, false);
    }

    static CCServerboundCubeSetStructureBlockPacket structure(BlockPos pos, BlockPos offset, Vec3i size, float integrity) {
        return new CCServerboundCubeSetStructureBlockPacket(pos, StructureBlockEntity.UpdateType.UPDATE_DATA, StructureMode.DATA, "", offset, size,
                Mirror.NONE, Rotation.NONE, "", true, false, false, true, integrity, 0);
    }

    private static <T> void headers(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        reject(codec, packet, b -> b.setByte(0, 2));
        reject(codec, packet, b -> b.setInt(5, Integer.MIN_VALUE));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            int length = buffer.writerIndex();
            for (int end = 0; end < length; end++) {
                buffer.readerIndex(0);
                buffer.writerIndex(end);
                assertThrows(RuntimeException.class, () -> codec.decode(buffer));
            }
        } finally {
            buffer.release();
        }
    }

    private static <T> void reject(StreamCodec<FriendlyByteBuf, T> codec, T packet, Consumer<FriendlyByteBuf> mutate) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            mutate.accept(buffer);
            assertThrows(RuntimeException.class, () -> codec.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
