package io.github.opencubicchunks.cubicchunks.network;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

/** Full-height positions for selected native codecs; every legal vanilla-world position retains its vanilla eight bytes. */
public final class CubePositionSourceCodec {
    // Packed X=-33554432,Y=0,Z=0 lies outside the legal vanilla world border. Escape that position too if used by a cubic world.
    private static final long ESCAPE = Long.MIN_VALUE;
    private static final int PACKED_MIN_Y = -2048;
    private static final int PACKED_MAX_Y = 2047;
    public static final StreamCodec<ByteBuf, BlockPos> BLOCK_POSITION = new StreamCodec<>() {
        @Override public BlockPos decode(ByteBuf buffer) {
            long packed = buffer.readLong();
            BlockPos position = packed == ESCAPE ? new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt()) : BlockPos.of(packed);
            return CubicBlockBounds.requireValid(position);
        }

        @Override public void encode(ByteBuf buffer, BlockPos position) {
            CubicBlockBounds.requireValid(position);
            long packed = position.asLong();
            boolean extended = position.getY() < PACKED_MIN_Y || position.getY() > PACKED_MAX_Y || packed == ESCAPE;
            buffer.writeLong(extended ? ESCAPE : packed);
            if (extended) {
                buffer.writeInt(position.getX());
                buffer.writeInt(position.getY());
                buffer.writeInt(position.getZ());
            }
        }
    };

    private CubePositionSourceCodec() {}
}
