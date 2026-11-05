package io.github.opencubicchunks.cubicchunks.network;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Shared strict primitives for versioned action envelopes. No packed BlockPos longs. */
public final class CubeActionCodec {
    private static final int VERSION = 1;

    private CubeActionCodec() {}

    public static BlockPos readPosition(FriendlyByteBuf buffer) {
        if (buffer.readVarInt() != VERSION) {
            throw new IllegalArgumentException("Unsupported cube action format");
        }
        return CubicBlockBounds.requireValid(new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }

    public static void writePosition(FriendlyByteBuf buffer, BlockPos pos) {
        buffer.writeVarInt(VERSION);
        buffer.writeInt(pos.getX());
        buffer.writeInt(pos.getY());
        buffer.writeInt(pos.getZ());
    }

    public static int sequence(int sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("Negative cube action sequence");
        }
        return sequence;
    }

    public static <T> T readEnum(FriendlyByteBuf buffer, T[] values) {
        int index = buffer.readVarInt();
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("Invalid cube action enum");
        }
        return values[index];
    }

    public static boolean readBoolean(FriendlyByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) {
            throw new IllegalArgumentException("Invalid cube action flag");
        }
        return value != 0;
    }
}
