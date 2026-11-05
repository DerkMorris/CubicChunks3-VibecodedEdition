package io.github.opencubicchunks.cubicchunks.network;

import java.util.Arrays;
import java.util.Objects;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Detached bounded client-update NBT, with no packed world-position field. */
public final class CubeBlockEntityUpdate {
    public static final int MAX_BYTES = 65536;
    private static final int INITIAL_BUFFER_BYTES = 256;
    private static final int MAX_NBT_ALLOCATION = 2097152;
    private final BlockEntityType<?> type;
    private final byte[] encodedTag;

    private CubeBlockEntityUpdate(BlockEntityType<?> type, byte[] encodedTag) {
        this.type = Objects.requireNonNull(type, "type");
        if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(type) < 0) {
            throw new IllegalArgumentException("Unregistered block entity type");
        }
        this.encodedTag = encodedTag;
        // Validate fully before any client block mutation, including nested allocation limits and trailing data.
        this.tag();
    }

    public static CubeBlockEntityUpdate capture(BlockEntityType<?> type, CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(INITIAL_BUFFER_BYTES, MAX_BYTES));
        try {
            buffer.writeNbt(tag);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new CubeBlockEntityUpdate(type, bytes);
        } finally {
            buffer.release();
        }
    }

    static CubeBlockEntityUpdate read(FriendlyByteBuf buffer, int remainingBytes) {
        int typeId = buffer.readVarInt();
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.byId(typeId);
        if (typeId < 0 || type == null) {
            throw new IllegalArgumentException("Unknown block entity type ID");
        }
        return new CubeBlockEntityUpdate(type, buffer.readByteArray(Math.min(MAX_BYTES, remainingBytes)));
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(this.type));
        buffer.writeByteArray(this.encodedTag);
    }

    public int size() {
        return this.encodedTag.length;
    }

    int encodedSize() {
        return VarInt.getByteSize(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(type)) + VarInt.getByteSize(encodedTag.length) + encodedTag.length;
    }

    @Override public boolean equals(Object other) {
        return other instanceof CubeBlockEntityUpdate update && type == update.type && Arrays.equals(encodedTag, update.encodedTag);
    }

    @Override public int hashCode() {
        return Objects.hash(type, Arrays.hashCode(encodedTag));
    }

    public BlockEntityType<?> type() {
        return this.type;
    }

    public CompoundTag tag() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(this.encodedTag));
        try {
            var tag = buffer.readNbt(NbtAccounter.create(MAX_NBT_ALLOCATION));
            if (!(tag instanceof CompoundTag compound) || buffer.isReadable()) {
                throw new IllegalArgumentException("Invalid block entity update NBT");
            }
            return compound;
        } finally {
            buffer.release();
        }
    }
}
