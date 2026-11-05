package io.github.opencubicchunks.cubicchunks.network;

import java.util.Objects;

import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.server.network.CubeBlockEntityEditing;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;

public record CCServerboundCubeSetStructureBlockPacket(
        BlockPos pos, StructureBlockEntity.UpdateType updateType, StructureMode mode, String name, BlockPos offset, Vec3i size, Mirror mirror,
        Rotation rotation, String data, boolean ignoreEntities, boolean strict, boolean showAir, boolean showBoundingBox, float integrity, long seed
) implements CustomPacketPayload {

    public static final Type<CCServerboundCubeSetStructureBlockPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "cube_set_structure_block"));
    public static final StreamCodec<FriendlyByteBuf, CCServerboundCubeSetStructureBlockPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCServerboundCubeSetStructureBlockPacket decode(FriendlyByteBuf buffer) {
            return new CCServerboundCubeSetStructureBlockPacket(CubeActionCodec.readPosition(buffer),
                    CubeActionCodec.readEnum(buffer, StructureBlockEntity.UpdateType.values()),
                    CubeActionCodec.readEnum(buffer, StructureMode.values()), buffer.readUtf(CubeEditCodec.MAX_STRING_LENGTH),
                    new BlockPos(buffer.readByte(), buffer.readByte(), buffer.readByte()),
                    new Vec3i(buffer.readByte(), buffer.readByte(), buffer.readByte()), CubeActionCodec.readEnum(buffer, Mirror.values()),
                    CubeActionCodec.readEnum(buffer, Rotation.values()), buffer.readUtf(CubeEditCodec.STRUCTURE_DATA_LENGTH),
                    CubeActionCodec.readBoolean(buffer), CubeActionCodec.readBoolean(buffer), CubeActionCodec.readBoolean(buffer),
                    CubeActionCodec.readBoolean(buffer), buffer.readFloat(), buffer.readVarLong());
        }

        @Override public void encode(FriendlyByteBuf buffer, CCServerboundCubeSetStructureBlockPacket packet) {
            CubeActionCodec.writePosition(buffer, packet.pos);
            buffer.writeEnum(packet.updateType);
            buffer.writeEnum(packet.mode);
            buffer.writeUtf(packet.name, CubeEditCodec.MAX_STRING_LENGTH);
            writeVector(buffer, packet.offset);
            writeVector(buffer, packet.size);
            buffer.writeEnum(packet.mirror);
            buffer.writeEnum(packet.rotation);
            buffer.writeUtf(packet.data, CubeEditCodec.STRUCTURE_DATA_LENGTH);
            buffer.writeBoolean(packet.ignoreEntities);
            buffer.writeBoolean(packet.strict);
            buffer.writeBoolean(packet.showAir);
            buffer.writeBoolean(packet.showBoundingBox);
            buffer.writeFloat(packet.integrity);
            buffer.writeVarLong(packet.seed);
        }
    };

    public CCServerboundCubeSetStructureBlockPacket {
        pos = CubicBlockBounds.requireValid(pos);
        Objects.requireNonNull(updateType, "updateType");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(mirror, "mirror");
        Objects.requireNonNull(rotation, "rotation");
        vector(offset, -StructureBlockEntity.MAX_OFFSET_PER_AXIS, StructureBlockEntity.MAX_OFFSET_PER_AXIS);
        offset = offset.immutable();
        size = vector(size, 0, StructureBlockEntity.MAX_SIZE_PER_AXIS);
        CubeEditCodec.string(name, CubeEditCodec.MAX_STRING_LENGTH);
        CubeEditCodec.string(data, CubeEditCodec.STRUCTURE_DATA_LENGTH);
        CubeEditCodec.textBudget(name, data);
        if (!Float.isFinite(integrity) || integrity < 0 || integrity > 1) {
            throw new IllegalArgumentException("Invalid structure integrity");
        }
    }

    private static Vec3i vector(Vec3i value, int min, int max) {
        return new Vec3i(CubeEditCodec.bounded(value.getX(), min, max), CubeEditCodec.bounded(value.getY(), min, max),
                CubeEditCodec.bounded(value.getZ(), min, max));
    }

    private static void writeVector(FriendlyByteBuf buffer, Vec3i value) {
        buffer.writeByte(value.getX());
        buffer.writeByte(value.getY());
        buffer.writeByte(value.getZ());
    }

    public static CCServerboundCubeSetStructureBlockPacket from(ServerboundSetStructureBlockPacket packet) {
        return new CCServerboundCubeSetStructureBlockPacket(packet.getPos(), packet.getUpdateType(), packet.getMode(), packet.getName(),
                packet.getOffset(), packet.getSize(), packet.getMirror(), packet.getRotation(), packet.getData(), packet.isIgnoreEntities(),
                packet.isStrict(), packet.isShowAir(), packet.isShowBoundingBox(), packet.getIntegrity(), packet.getSeed());
    }

    public ServerboundSetStructureBlockPacket vanilla() {
        return new ServerboundSetStructureBlockPacket(pos, updateType, mode, name, offset, size, mirror, rotation, data, ignoreEntities, strict,
                showAir, showBoundingBox, integrity, seed);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCServerboundCubeSetStructureBlockPacket packet, net.minecraft.server.level.ServerPlayer player) {
            CubeBlockEntityEditing.handle(player, packet);
        }
    }
}
