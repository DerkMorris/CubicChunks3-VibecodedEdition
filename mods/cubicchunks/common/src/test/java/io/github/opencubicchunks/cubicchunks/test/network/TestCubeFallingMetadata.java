package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.stream.IntStream;

import io.github.opencubicchunks.cubicchunks.network.CubeEntityDataSerializers;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TestCubeFallingMetadata extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("heights")
    void nativeInitialAndDirtyFallingMetadataPreserveClientStartPositionAndModelSeed(int y) throws Exception {
        try (var world = new CubeMobTestWorld(directory)) {
            var serverBody = new FallingBlockEntity(EntityTypes.FALLING_BLOCK, world.level);
            ClientLevel client = Misc.mockLevelWithPalettes(ClientLevel.class);
            when(client.isClientSide()).thenReturn(true);
            var clientBody = new FallingBlockEntity(EntityTypes.FALLING_BLOCK, client);
            var start = new BlockPos(17, y, -9);
            serverBody.setStartPos(start);
            var initial = new ClientboundSetEntityDataPacket(serverBody.getId(), serverBody.getEntityData().getNonDefaultValues());
            clientBody.getEntityData()
                    .assignValues(TestCubeVillagerMetadata.roundTrip(ClientboundSetEntityDataPacket.STREAM_CODEC, initial).packedItems());
            assertEquals(start, clientBody.getStartPos());
            assertEquals(Blocks.SAND.defaultBlockState().getSeed(start), Blocks.SAND.defaultBlockState().getSeed(clientBody.getStartPos()),
                    "FallingBlockRenderer uses getStartPos as its moving-block model randomSeedPos");
            serverBody.getEntityData().packDirty();
            var changed = start.offset(1, y == CubicBlockBounds.MAX ? -1 : 1, 0);
            serverBody.setStartPos(changed);
            var dirty = new ClientboundSetEntityDataPacket(serverBody.getId(), serverBody.getEntityData().packDirty());
            clientBody.getEntityData()
                    .assignValues(TestCubeVillagerMetadata.roundTrip(ClientboundSetEntityDataPacket.STREAM_CODEC, dirty).packedItems());
            assertEquals(changed, clientBody.getStartPos());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { -2048, -64, 0, 96, 319, 2047 })
    void ordinaryPositionMetadataKeepsNativeSerializerAndBytes(int y) {
        var position = new BlockPos(1, y, -1);
        var actual = TestCubeVillagerMetadata.buffer();
        var expected = TestCubeVillagerMetadata.buffer();
        try {
            new SynchedEntityData.DataValue<>(4, EntityDataSerializers.BLOCK_POS, position).write(actual);
            expected.writeByte(4);
            expected.writeVarInt(EntityDataSerializers.getSerializedId(EntityDataSerializers.BLOCK_POS));
            EntityDataSerializers.BLOCK_POS.codec().encode(expected, position);
            assertEquals(expected, actual);
            int id = actual.readUnsignedByte();
            var decoded = SynchedEntityData.DataValue.read(actual, id);
            assertSame(EntityDataSerializers.BLOCK_POS, decoded.serializer());
            assertEquals(position, decoded.value());
        } finally {
            actual.release();
            expected.release();
        }
    }

    @Test
    void extendedMetadataRestoresNativeAccessorAndRejectsMalformedBounds() {
        var buffer = TestCubeVillagerMetadata.buffer();
        try {
            var position = new BlockPos(1, 32000, -1);
            new SynchedEntityData.DataValue<>(4, EntityDataSerializers.BLOCK_POS, position).write(buffer);
            int id = buffer.readUnsignedByte();
            int typeIndex = buffer.readerIndex();
            assertEquals(EntityDataSerializers.getSerializedId(CubeEntityDataSerializers.POSITION), buffer.readVarInt());
            buffer.readerIndex(typeIndex);
            var decoded = SynchedEntityData.DataValue.read(buffer, id);
            assertSame(EntityDataSerializers.BLOCK_POS, decoded.serializer());
            assertEquals(position, decoded.value());
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            buffer.writeByte(1).writeInt(0).writeInt(CubicBlockBounds.MAX + 1).writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> CubeEntityDataSerializers.POSITION.codec().decode(buffer));
            buffer.clear();
            buffer.writeByte(1).writeInt(0);
            assertThrows(IndexOutOfBoundsException.class, () -> CubeEntityDataSerializers.POSITION.codec().decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static IntStream heights() {
        return IntStream.of(96, 32000, -32000, 32000000, -32000000, CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }
}
