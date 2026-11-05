package io.github.opencubicchunks.cubicchunks.test.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import io.github.opencubicchunks.cubicchunks.network.CubeEntityDataSerializers;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeVillagerTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeVillagerMetadata extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void sleepingInitialAndDirtyMetadataMoveRealClientEntityToFullBedPositionAndClearIt() throws Exception {
        for (int y : new int[] { 32000008, -32000024 }) {
            try (var f = new CubeVillagerTestWorld(directory.resolve(Integer.toString(y)))) {
                BlockPos bed = new BlockPos(2, y, 2);
                f.world.block(bed, Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
                var serverBody = f.villager(y);
                ClientLevel client = Misc.mockLevelWithPalettes(ClientLevel.class);
                when(client.isClientSide()).thenReturn(true);
                when(client.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
                when(client.getBlockState(any())).thenReturn(Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
                var clientBody = EntityTypes.VILLAGER.create(client, EntitySpawnReason.LOAD);
                assertTrue(serverBody.startSleeping(bed));
                var initial = new ClientboundSetEntityDataPacket(serverBody.getId(), serverBody.getEntityData().getNonDefaultValues());
                clientBody.getEntityData().assignValues(roundTrip(ClientboundSetEntityDataPacket.STREAM_CODEC, initial).packedItems());
                assertEquals(bed, clientBody.getSleepingPos().orElseThrow());
                assertEquals(serverBody.position(), clientBody.position());
                assertEquals(Pose.SLEEPING, clientBody.getPose());
                serverBody.getEntityData().packDirty();
                serverBody.setSleepingPos(bed.above(4096));
                var changed = new ClientboundSetEntityDataPacket(serverBody.getId(), serverBody.getEntityData().packDirty());
                clientBody.getEntityData().assignValues(roundTrip(ClientboundSetEntityDataPacket.STREAM_CODEC, changed).packedItems());
                assertEquals(bed.above(4096), clientBody.getSleepingPos().orElseThrow());
                assertEquals(y + 4096 + 0.6875, clientBody.getY(), 0.0001);
                serverBody.clearSleepingPos();
                serverBody.setPose(Pose.STANDING);
                var clear = new ClientboundSetEntityDataPacket(serverBody.getId(), serverBody.getEntityData().packDirty());
                clientBody.getEntityData().assignValues(roundTrip(ClientboundSetEntityDataPacket.STREAM_CODEC, clear).packedItems());
                assertFalse(clientBody.isSleeping());
                assertEquals(Pose.STANDING, clientBody.getPose());
            }
        }
    }

    @Test
    void optionalPositionsRoundTripAtLimitsAndRestoreNativeAccessorSerializer() {
        for (int y : new int[] { CubicBlockBounds.MIN, -32000000, -2049, 2048, 32000000, CubicBlockBounds.MAX }) {
            var pos = Optional.of(new BlockPos(1, y, -1));
            var value = new SynchedEntityData.DataValue<>(4, EntityDataSerializers.OPTIONAL_BLOCK_POS, pos);
            var buffer = buffer();
            try {
                value.write(buffer);
                int id = buffer.readUnsignedByte();
                int typeIndex = buffer.readerIndex();
                assertEquals(EntityDataSerializers.getSerializedId(CubeEntityDataSerializers.OPTIONAL_POSITION), buffer.readVarInt());
                buffer.readerIndex(typeIndex);
                var decoded = SynchedEntityData.DataValue.read(buffer, id);
                assertSame(EntityDataSerializers.OPTIONAL_BLOCK_POS, decoded.serializer());
                assertEquals(pos, decoded.value());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void representableAndEmptyOptionalPositionMetadataRetainsNativeBytes() {
        for (Optional<BlockPos> pos : java.util.List.of(Optional.of(new BlockPos(1, -2048, 2)), Optional.of(new BlockPos(1, 2047, 2)),
                Optional.<BlockPos>empty())) {
            var actual = buffer();
            var expected = buffer();
            try {
                new SynchedEntityData.DataValue<>(4, EntityDataSerializers.OPTIONAL_BLOCK_POS, pos).write(actual);
                expected.writeByte(4);
                expected.writeVarInt(EntityDataSerializers.getSerializedId(EntityDataSerializers.OPTIONAL_BLOCK_POS));
                EntityDataSerializers.OPTIONAL_BLOCK_POS.codec().encode(expected, pos);
                assertEquals(expected, actual);
            } finally {
                actual.release();
                expected.release();
            }
        }
    }

    @Test
    void fullPositionMetadataRejectsMalformedFlagVersionBoundsAndTruncation() {
        var codec = CubeEntityDataSerializers.OPTIONAL_POSITION.codec();
        var buffer = buffer();
        try {
            buffer.writeByte(2);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            buffer.clear();
            buffer.writeBoolean(true).writeByte(2);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            buffer.clear();
            buffer.writeBoolean(true).writeByte(1).writeInt(0).writeInt(Integer.MAX_VALUE).writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            buffer.clear();
            codec.encode(buffer, Optional.of(new BlockPos(1, 32000000, 2)));
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

    static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), VanillaRegistriesExtension.shared());
    }

    static <T> T roundTrip(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T packet) {
        var buffer = buffer();
        try {
            codec.encode(buffer, packet);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
