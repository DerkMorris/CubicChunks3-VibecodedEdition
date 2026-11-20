package io.github.opencubicchunks.cubicchunks.network;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubeWeatherClient;
import io.github.opencubicchunks.cubicchunks.platform.ClientPacketContext;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A bounded authoritative weather surface; null revokes the column before changed block data is sent. */
public record CCClientboundWeatherColumnPacket(Identifier dimension, int cubeX, int cubeZ, long epoch, @Nullable int[] heights)
        implements CustomPacketPayload {

    public static final int UNKNOWN = Integer.MAX_VALUE;
    public static final int COUNT = CubicConstants.DIAMETER_IN_BLOCKS * CubicConstants.DIAMETER_IN_BLOCKS;
    private static final int FORMAT = 1;
    public static final Type<CCClientboundWeatherColumnPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CubicChunks.MODID, "weather_column"));
    public static final StreamCodec<FriendlyByteBuf, CCClientboundWeatherColumnPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public CCClientboundWeatherColumnPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readVarInt() != FORMAT) {
                throw new IllegalArgumentException("Unsupported weather surface format");
            }
            var dimension = buffer.readIdentifier();
            int x = buffer.readInt();
            int z = buffer.readInt();
            long epoch = buffer.readLong();
            int length = buffer.readVarInt();
            if (length != 0 && length != COUNT) {
                throw new IllegalArgumentException("Wrong weather surface size");
            }
            int[] values = length == 0 ? null : new int[length];
            for (int i = 0; i < length; i++) {
                values[i] = buffer.readInt();
            }
            return new CCClientboundWeatherColumnPacket(dimension, x, z, epoch, values);
        }

        @Override public void encode(FriendlyByteBuf buffer, CCClientboundWeatherColumnPacket packet) {
            buffer.writeVarInt(FORMAT);
            buffer.writeIdentifier(packet.dimension);
            buffer.writeInt(packet.cubeX);
            buffer.writeInt(packet.cubeZ);
            buffer.writeLong(packet.epoch);
            buffer.writeVarInt(packet.heights == null ? 0 : COUNT);
            if (packet.heights != null) {
                for (int value : packet.heights) {
                    buffer.writeInt(value);
                }
            }
        }
    };

    public CCClientboundWeatherColumnPacket {
        validateHeader(dimension, cubeX, cubeZ, epoch);
        heights = copyHeights(heights);
    }

    private static void validateHeader(Identifier dimension, int cubeX, int cubeZ, long epoch) {
        if (dimension == null || epoch < 0 || cubeX < -CubePos.MAX_COORDINATE_VALUE - 1 || cubeX > CubePos.MAX_COORDINATE_VALUE
                || cubeZ < -CubePos.MAX_COORDINATE_VALUE - 1 || cubeZ > CubePos.MAX_COORDINATE_VALUE) {
            throw new IllegalArgumentException("Invalid weather surface header");
        }
    }

    private static @Nullable int[] copyHeights(@Nullable int[] heights) {
        if (heights == null) {
            return null;
        }
        if (heights.length != COUNT) {
            throw new IllegalArgumentException("Wrong weather surface size");
        }
        var copy = heights.clone();
        for (int y : copy) {
            if (y != UNKNOWN && !CubicBlockBounds.contains(y)) {
                throw new IllegalArgumentException("Weather surface outside cubic bounds");
            }
        }
        return copy;
    }

    @Override public @Nullable int[] heights() {
        return heights == null ? null : heights.clone();
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class Handler {
        public void receive(CCClientboundWeatherColumnPacket packet, ClientPacketContext context) {
            var level = context.client().level;
            if (level != null && context.player() != null && context.player().level() == level
                    && packet.dimension.equals(level.dimension().identifier())) {
                ((CubeWeatherClient) level).cc_weatherColumns().apply(packet);
            }
        }
    }
}
